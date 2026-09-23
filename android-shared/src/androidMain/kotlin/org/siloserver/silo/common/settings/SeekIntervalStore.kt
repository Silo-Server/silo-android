package org.siloserver.silo.common.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.siloserver.silo.domain.settings.SeekIntervalController
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import org.siloserver.silo.model.settings.SeekDirection
import org.siloserver.silo.model.settings.SeekImportOutcome
import org.siloserver.silo.model.settings.SeekImportResult
import org.siloserver.silo.model.settings.SeekIntervalPair
import org.siloserver.silo.model.settings.SeekIntervalState
import org.siloserver.silo.model.settings.SeekIntervalSupport
import org.siloserver.silo.model.settings.SeekIntervals
import org.siloserver.silo.model.settings.SeekMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Profile-wide forward/rewind intervals (settings contract revision 9) for the
 * signed-in profile, shared by every playback surface on phone and TV.
 *
 * Players read [state] and apply it to the next relative seek, so a change
 * takes effect without restarting playback. Surfaces fall back to their own
 * pre-revision-9 constants through [SeekIntervalState.video] /
 * [SeekIntervalState.audiobook] while support is anything but
 * [SeekIntervalSupport.Supported]; nothing is written to a server that has not
 * confirmed support.
 *
 * Refresh edges: profile or server change ([identityChanges]), foreground and
 * reconnect via [ServerDrivenConfigRefresher], and each player open. Android
 * has no `user_settings.changed` consumer, so a change made on another device
 * lands at the next of those edges.
 */
interface SeekIntervalStore {
    val state: StateFlow<SeekIntervalState>
    val lastError: StateFlow<String?>

    /** Idempotent first load; [refresh] seeds from the last-known cache first. */
    suspend fun hydrateIfNeeded()

    /**
     * Re-probe capabilities and re-resolve the four keys. Until an answer is
     * committed for the current identity, first applies the cached answer.
     * A failed check with no earlier answer sets [SeekIntervalSupport.Unavailable].
     */
    suspend fun refresh()

    /**
     * Apply [seconds] locally, then write it at profile scope; restores the
     * last server-confirmed value if the write fails. The write settles in the
     * store's scope, so cancelling the caller does not leave it half-applied. Refused without a request unless the
     * server is known to support the keys.
     */
    suspend fun save(media: SeekMedia, direction: SeekDirection, seconds: Int): SeekIntervalController.SaveResult

    /**
     * Explicit import of legacy device-local audiobook intervals. Returns null
     * when the server does not support the keys (nothing is written).
     */
    suspend fun importLegacyAudiobook(legacy: LegacyAudiobookIntervals): SeekImportResult?

    /** Session boundary (sign-out): drop state so the next profile starts clean. */
    fun clear()
}

class DefaultSeekIntervalStore private constructor(
    private val controller: SeekIntervalController,
    private val scope: CoroutineScope,
    private val getActiveProfileId: suspend () -> String?,
    private val getServerUrl: suspend () -> String?,
    private val cache: SeekIntervalCache,
    identityChanges: Flow<Unit>,
) : SeekIntervalStore {

    constructor(
        context: Context,
        controller: SeekIntervalController,
        scope: CoroutineScope,
        getActiveProfileId: suspend () -> String?,
        getServerUrl: suspend () -> String?,
        identityChanges: Flow<Unit> = emptyFlow(),
    ) : this(
        controller = controller,
        scope = scope,
        getActiveProfileId = getActiveProfileId,
        getServerUrl = getServerUrl,
        cache = SharedPreferencesSeekIntervalCache(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        identityChanges = identityChanges,
    )

    private val _state = MutableStateFlow(SeekIntervalState())
    private val _lastError = MutableStateFlow<String?>(null)
    override val state: StateFlow<SeekIntervalState> = _state.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lock = Any()

    /** Bumped at every identity boundary; stale work compares and bails. */
    @Volatile
    private var generation = 0

    /** Bumped by every local mutation; a refresh that started earlier must not
     *  paint its older server answer over the newer local value. */
    private var mutationEpoch = 0L

    @Volatile
    private var hasHydrated = false

    /** The last values the server confirmed (a committed refresh, a saved
     *  write, an imported direction). A failed write restores these rather
     *  than whatever unconfirmed value was on screen before it. */
    private var confirmed = SeekIntervalState()

    /** Latest local request per media and direction. Only that request may
     *  change what is shown when it settles; an older one updates [confirmed]. */
    private val latestRequest = mutableMapOf<Pair<SeekMedia, SeekDirection>, Long>()
    private var requestCounter = 0L

    private val refreshLock = Mutex()
    private val writeLock = Mutex()

    init {
        scope.launch {
            identityChanges.collect { onIdentityChanged() }
        }
    }

    private suspend fun onIdentityChanged() {
        synchronized(lock) { resetLocked() }
        hydrateIfNeeded()
    }

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated) return
        refresh()
    }

    override suspend fun refresh(): Unit = refreshLock.withLock {
        val identity = currentIdentity() ?: return
        val startGeneration: Int
        val startEpoch: Long
        synchronized(lock) {
            startGeneration = generation
            startEpoch = mutationEpoch
        }
        seedFromCache(identity, startGeneration)
        val resolved = when (val result = controller.load()) {
            is SeekIntervalController.LoadResult.Supported -> SeekIntervalState(
                support = SeekIntervalSupport.Supported,
                videoIntervals = result.video,
                audiobookIntervals = result.audiobook,
            )
            SeekIntervalController.LoadResult.Unsupported ->
                SeekIntervalState(support = SeekIntervalSupport.Unsupported)
            is SeekIntervalController.LoadResult.Failed -> {
                synchronized(lock) {
                    if (generation != startGeneration) return@synchronized
                    _lastError.value = result.message
                    // Keep a cached or earlier answer; with none, stop waiting so
                    // surfaces fall back to device values instead of staying locked.
                    if (_state.value.support == SeekIntervalSupport.Unknown) {
                        _state.value = SeekIntervalState(support = SeekIntervalSupport.Unavailable)
                    }
                }
                return
            }
        }
        val committed = synchronized(lock) {
            if (generation != startGeneration || mutationEpoch != startEpoch) return@synchronized false
            _state.value = resolved
            confirmed = resolved
            _lastError.value = null
            hasHydrated = true
            true
        }
        if (committed) cache.write(identity, resolved)
    }

    /**
     * Apply the last-known answer while nothing better is on screen, so every
     * refresh edge (not only the first hydrate) restores it after a reset.
     */
    private fun seedFromCache(identity: String, startGeneration: Int) {
        if (hasHydrated) return
        val cached = cache.read(identity) ?: return
        synchronized(lock) {
            val current = _state.value.support
            val waiting = current == SeekIntervalSupport.Unknown || current == SeekIntervalSupport.Unavailable
            if (!hasHydrated && generation == startGeneration && waiting) {
                _state.value = cached
                confirmed = cached
            }
        }
    }

    override suspend fun save(
        media: SeekMedia,
        direction: SeekDirection,
        seconds: Int,
    ): SeekIntervalController.SaveResult {
        if (!SeekIntervals.isValid(seconds)) return SeekIntervalController.SaveResult.Invalid(seconds)
        val key = media to direction
        val startGeneration: Int
        val request: Long
        synchronized(lock) {
            val current = _state.value
            if (!current.isSupported) {
                return SeekIntervalController.SaveResult.Failed(UNSUPPORTED_MESSAGE)
            }
            startGeneration = generation
            request = ++requestCounter
            latestRequest[key] = request
            mutationEpoch += 1
            _state.value = current.with(media, direction, seconds)
        }
        // The store owns the write and its settlement: a caller that leaves the
        // screen (cancelling its scope) must not strand an unsaved value on screen.
        return scope.async {
            val result = writeLock.withLock {
                if (generation != startGeneration) return@async SeekIntervalController.SaveResult.Failed(null)
                controller.save(media, direction, seconds)
            }
            val saved = result is SeekIntervalController.SaveResult.Saved
            synchronized(lock) {
                if (generation != startGeneration) return@async result
                if (saved) confirmed = confirmed.with(media, direction, seconds)
                if (latestRequest[key] == request) {
                    mutationEpoch += 1
                    // Saved: re-assert the value (an import may have painted over it).
                    // Failed: return to what the server last confirmed.
                    val shown = if (saved) seconds else confirmed.pair(media).seconds(direction)
                    _state.value = _state.value.with(media, direction, shown)
                }
                if (!saved) _lastError.value = (result as? SeekIntervalController.SaveResult.Failed)?.message
            }
            if (saved) persistConfirmed(startGeneration)
            result
        }.await()
    }

    override suspend fun importLegacyAudiobook(legacy: LegacyAudiobookIntervals): SeekImportResult? {
        val startGeneration: Int
        val startRequest: Long
        synchronized(lock) {
            if (!_state.value.isSupported) return null
            startGeneration = generation
            startRequest = requestCounter
        }
        return scope.async {
            val result = writeLock.withLock {
                if (generation != startGeneration) return@async null
                controller.importLegacyAudiobook(legacy)
            }
            synchronized(lock) {
                if (generation != startGeneration) return@async result
                mutationEpoch += 1
                var next = _state.value
                SeekDirection.entries.forEach { direction ->
                    val outcome = result.outcome(direction)
                    if (outcome is SeekImportOutcome.Imported) {
                        confirmed = confirmed.with(SeekMedia.Audiobook, direction, outcome.seconds)
                        // A choice made while the import ran is newer; keep it on screen.
                        val chosenSince = (latestRequest[SeekMedia.Audiobook to direction] ?: 0L) > startRequest
                        if (!chosenSince) next = next.with(SeekMedia.Audiobook, direction, outcome.seconds)
                    }
                }
                _state.value = next
            }
            persistConfirmed(startGeneration)
            result
        }.await()
    }

    /** Caches what the server confirmed, never an unconfirmed value on screen. */
    private suspend fun persistConfirmed(startGeneration: Int) {
        val identity = currentIdentity() ?: return
        val snapshot = synchronized(lock) {
            if (generation != startGeneration) return
            confirmed
        }
        cache.write(identity, snapshot)
    }

    override fun clear() {
        synchronized(lock) { resetLocked() }
    }

    private fun resetLocked() {
        generation += 1
        mutationEpoch += 1
        hasHydrated = false
        _state.value = SeekIntervalState()
        confirmed = SeekIntervalState()
        latestRequest.clear()
        _lastError.value = null
    }

    private suspend fun currentIdentity(): String? {
        val profileId = getActiveProfileId()?.takeIf { it.isNotBlank() } ?: return null
        return "${getServerUrl().orEmpty()}|$profileId"
    }

    internal companion object {
        const val PREFS_NAME = "silo_seek_intervals"
        const val UNSUPPORTED_MESSAGE =
            "This server does not support profile seek intervals."

        /** Test seam: no Android Context, in-memory cache. */
        internal fun forTest(
            controller: SeekIntervalController,
            scope: CoroutineScope,
            getActiveProfileId: suspend () -> String?,
            getServerUrl: suspend () -> String? = { "https://server.test" },
            cache: SeekIntervalCache = InMemorySeekIntervalCache(),
            identityChanges: Flow<Unit> = emptyFlow(),
        ): DefaultSeekIntervalStore = DefaultSeekIntervalStore(
            controller = controller,
            scope = scope,
            getActiveProfileId = getActiveProfileId,
            getServerUrl = getServerUrl,
            cache = cache,
            identityChanges = identityChanges,
        )
    }
}

/**
 * Last-known answer per server and profile, so a cold start or an offline
 * session applies the profile's intervals (or the definitive "unsupported")
 * before the network answers.
 */
internal interface SeekIntervalCache {
    fun read(identity: String): SeekIntervalState?
    fun write(identity: String, state: SeekIntervalState)
}

internal class InMemorySeekIntervalCache : SeekIntervalCache {
    private val entries = mutableMapOf<String, SeekIntervalState>()
    override fun read(identity: String): SeekIntervalState? = synchronized(entries) { entries[identity] }
    override fun write(identity: String, state: SeekIntervalState) {
        if (state.support != SeekIntervalSupport.Supported && state.support != SeekIntervalSupport.Unsupported) return
        synchronized(entries) { entries[identity] = state }
    }
}

private class SharedPreferencesSeekIntervalCache(
    private val prefs: SharedPreferences,
) : SeekIntervalCache {

    override fun read(identity: String): SeekIntervalState? {
        val prefix = prefix(identity)
        val support = when (prefs.getString("$prefix.support", null)) {
            SUPPORTED -> SeekIntervalSupport.Supported
            UNSUPPORTED -> SeekIntervalSupport.Unsupported
            else -> return null
        }
        fun pair(tag: String) = SeekIntervalPair(
            backSeconds = prefs.getInt("$prefix.$tag.back", SeekIntervals.DEFAULT_BACK_SECONDS)
                .takeIf(SeekIntervals::isValid) ?: SeekIntervals.DEFAULT_BACK_SECONDS,
            forwardSeconds = prefs.getInt("$prefix.$tag.forward", SeekIntervals.DEFAULT_FORWARD_SECONDS)
                .takeIf(SeekIntervals::isValid) ?: SeekIntervals.DEFAULT_FORWARD_SECONDS,
        )
        return SeekIntervalState(
            support = support,
            videoIntervals = pair("video"),
            audiobookIntervals = pair("audiobook"),
        )
    }

    override fun write(identity: String, state: SeekIntervalState) {
        val support = when (state.support) {
            SeekIntervalSupport.Supported -> SUPPORTED
            SeekIntervalSupport.Unsupported -> UNSUPPORTED
            SeekIntervalSupport.Unknown, SeekIntervalSupport.Unavailable -> return
        }
        val prefix = prefix(identity)
        prefs.edit {
            putString("$prefix.support", support)
            putInt("$prefix.video.back", state.videoIntervals.backSeconds)
            putInt("$prefix.video.forward", state.videoIntervals.forwardSeconds)
            putInt("$prefix.audiobook.back", state.audiobookIntervals.backSeconds)
            putInt("$prefix.audiobook.forward", state.audiobookIntervals.forwardSeconds)
        }
    }

    private fun prefix(identity: String): String =
        "si_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(24)

    private companion object {
        const val SUPPORTED = "supported"
        const val UNSUPPORTED = "unsupported"
    }
}
