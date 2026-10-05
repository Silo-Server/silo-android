package org.siloserver.silo.common.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.siloserver.silo.domain.settings.TitleArtController
import org.siloserver.silo.domain.settings.TitleArtPreference
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.AuthScopeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the connected server knows `ui.title_art` (settings revision 16). */
enum class TitleArtSupport {
    /** Not answered yet (cold start before the probe, nothing cached). */
    Unknown,
    Supported,

    /** Definitive: the server predates the key. Nothing is written. */
    Unsupported,

    /** The probe failed and nothing earlier is known. */
    Unavailable,
}

data class TitleArtState(
    val support: TitleArtSupport = TitleArtSupport.Unknown,
    val preference: TitleArtPreference = TitleArtPreference(),
    /**
     * The server answered for this profile since the last identity change. A
     * cached answer alone may be stale, and the main switch's scope comes from
     * [appliesToAllDevices], so the switches wait for this.
     */
    val isConfirmed: Boolean = false,
) {
    /**
     * What title surfaces render. Anything short of a supporting server keeps
     * the logo behaviour every client had before the setting existed.
     */
    val showTitleArt: Boolean
        get() = if (support == TitleArtSupport.Supported) {
            preference.showTitleArt
        } else {
            TitleArtController.DEFAULT_SHOW_TITLE_ART
        }

    val appliesToAllDevices: Boolean get() = preference.appliesToAllDevices

    /** Only a server that answered for the key gets the settings controls. */
    val isSupported: Boolean get() = support == TitleArtSupport.Supported

    /** The switches accept changes only once the server has confirmed [preference]. */
    val canEdit: Boolean get() = isSupported && isConfirmed
}

/**
 * The signed-in profile's "Show title art" choice for this device — the one
 * source every title surface (phone and TV detail heroes, the TV marquee, logo
 * prefetch) and both settings screens read, so a change made in settings
 * reaches an open detail page without a restart.
 *
 * Refresh edges: profile or server change ([identityChanges]), the shell's
 * session key, and foreground/reconnect via [ServerDrivenConfigRefresher].
 */
interface TitleArtStore {
    val state: StateFlow<TitleArtState>

    /**
     * What the settings screens show after a failed save, e.g. "Couldn't save
     * title art: Server error". Cleared by the next successful save, a fresh
     * read when Settings opens, and an identity change.
     */
    val saveError: StateFlow<String?>

    /** Idempotent first load; seeds from the last-known answer first. */
    suspend fun hydrateIfNeeded()

    /**
     * Re-probe capabilities and re-resolve the effective value. Joins a load
     * already in flight for this identity instead of starting another.
     */
    suspend fun refresh()

    /**
     * The main switch: applied locally at once, then written at `profile`
     * while the value applies to all devices, else at `profile_device`.
     * Restores the last confirmed state if the write fails. Ignored until
     * [TitleArtState.canEdit].
     */
    fun setShowTitleArt(show: Boolean)

    /**
     * "Apply to all devices": on writes the current value at `profile`; off
     * pins it at `profile_device` and then clears the `profile` value.
     */
    fun setAppliesToAllDevices(enabled: Boolean)

    /** Session boundary (sign-out, profile switch): drop state. */
    fun clear()
}

class DefaultTitleArtStore private constructor(
    private val controller: TitleArtController,
    private val scope: CoroutineScope,
    private val getAuthScope: suspend () -> AuthScopeSnapshot?,
    private val cache: TitleArtCache,
    identityChanges: Flow<Unit>,
) : TitleArtStore {

    constructor(
        context: Context,
        controller: TitleArtController,
        scope: CoroutineScope,
        getAuthScope: suspend () -> AuthScopeSnapshot?,
        identityChanges: Flow<Unit> = emptyFlow(),
    ) : this(
        controller = controller,
        scope = scope,
        getAuthScope = getAuthScope,
        cache = SharedPreferencesTitleArtCache(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        identityChanges = identityChanges,
    )

    private val _state = MutableStateFlow(TitleArtState())
    private val _saveError = MutableStateFlow<String?>(null)
    override val state: StateFlow<TitleArtState> = _state.asStateFlow()
    override val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val lock = Any()

    /** Bumped at every identity boundary; stale work compares and bails. */
    @Volatile
    private var generation = 0

    /** Bumped by every local mutation, so a refresh that started earlier
     *  cannot paint its older answer over a newer choice. */
    private var mutationEpoch = 0L

    @Volatile
    private var hasHydrated = false

    /** The last state the server confirmed; a failed write restores it. */
    private var confirmed = TitleArtState()

    /** The profile the committed state was read for; writes must match it. */
    private var confirmedAuthority: AuthScopeSnapshot? = null

    /** The cache key the committed state belongs to. */
    private var confirmedIdentity: String? = null

    /**
     * Every request numbered at or below this was queued behind a write that
     * failed. Each one was built on the failed write's unsaved state, so none
     * is sent; the store returns to the confirmed state and re-reads.
     */
    private var droppedThrough = 0L

    /** Only the latest local request may decide what is shown when it settles. */
    private var requestCounter = 0L
    private var pendingWrites = 0

    /**
     * Refreshes run without a lock held across the network, so a stalled
     * request for one server never delays another server's answer. Each takes
     * a sequence number and only a newer answer than the last committed one
     * may land.
     */
    private var refreshSequence = 0L
    private var committedRefresh = 0L

    /** A refresh answered while a write was queued; re-read once they drain. */
    private var refreshAfterWrites = false

    /**
     * The load in flight for the current identity. Startup asks from several
     * places at once (the identity collector, the shell, the foreground
     * refresher); they share one capabilities and effective-values round trip.
     */
    private var inFlightLoad: Deferred<Unit>? = null

    /**
     * Keeps one identity's writes in the order the user made them. Replaced at
     * every identity boundary, so a write still on the wire for the previous
     * server or profile never holds back the next one's saves.
     */
    private var writeLock = Mutex()

    init {
        scope.launch {
            // collectLatest: a new identity invalidates the old state at once
            // and cancels the previous identity's hydration instead of queueing
            // behind it.
            identityChanges.collectLatest {
                synchronized(lock) { resetLocked() }
                hydrateIfNeeded()
            }
        }
    }

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated) return
        load()
    }

    override suspend fun refresh() = load()

    private suspend fun load() {
        // Lazy, so the load starts outside the lock (Main.immediate would
        // otherwise run it inline while the lock is held).
        val load = synchronized(lock) {
            inFlightLoad?.takeUnless { it.isCompleted }
                ?: scope.async(start = CoroutineStart.LAZY) { refresh(keepError = false) }
                    .also { inFlightLoad = it }
        }
        load.await()
    }

    /** [keepError]: a re-read after writes keeps their outcome (a failure stays reported). */
    private suspend fun refresh(keepError: Boolean) {
        // Capture the generation before the suspending authority read: a
        // reset while it runs must not pair the previous profile's authority
        // with the new generation. The cache key comes from the same snapshot
        // the load is pinned to, so the answer is always cached for the
        // profile it was read for.
        val startGeneration = synchronized(lock) { generation }
        val authority = getAuthScope() ?: return
        val identity = authority.cacheIdentity() ?: return
        val startEpoch: Long
        val sequence: Long
        synchronized(lock) {
            if (generation != startGeneration) return
            startEpoch = mutationEpoch
            sequence = ++refreshSequence
        }
        seedFromCache(identity, startGeneration)
        val resolved = when (val result = controller.load(authority)) {
            is TitleArtController.LoadResult.Supported ->
                TitleArtState(TitleArtSupport.Supported, result.preference, isConfirmed = true)
            TitleArtController.LoadResult.Unsupported ->
                TitleArtState(TitleArtSupport.Unsupported, isConfirmed = true)
            is TitleArtController.LoadResult.Failed -> {
                synchronized(lock) {
                    if (generation != startGeneration) return@synchronized
                    if (_state.value.support == TitleArtSupport.Unknown) {
                        _state.value = TitleArtState(TitleArtSupport.Unavailable)
                    }
                }
                return
            }
        }
        val committed = synchronized(lock) {
            if (generation != startGeneration || sequence <= committedRefresh) return@synchronized false
            // A queued or in-flight write makes this answer stale by the time
            // it lands: it was read before the write, and committing it would
            // repaint the old value and route the next toggle to the old
            // scope. Drop it and re-read once the writes drain.
            if (pendingWrites > 0) {
                refreshAfterWrites = true
                return@synchronized false
            }
            if (mutationEpoch != startEpoch) return@synchronized false
            committedRefresh = sequence
            confirmedAuthority = authority
            confirmedIdentity = identity
            _state.value = resolved
            confirmed = resolved
            if (!keepError) _saveError.value = null
            hasHydrated = true
            true
        }
        if (committed) cache.write(identity, resolved)
    }

    /** Apply the last-known answer while nothing better is on screen. */
    private fun seedFromCache(identity: String, startGeneration: Int) {
        if (hasHydrated) return
        val cached = cache.read(identity) ?: return
        synchronized(lock) {
            val current = _state.value.support
            val waiting = current == TitleArtSupport.Unknown || current == TitleArtSupport.Unavailable
            if (!hasHydrated && generation == startGeneration && waiting) {
                _state.value = cached
                confirmed = cached
                confirmedIdentity = identity
            }
        }
    }

    override fun setShowTitleArt(show: Boolean) {
        mutate(
            next = { it.copy(showTitleArt = show) },
            write = { pref, authority -> controller.setShowTitleArt(show, pref.appliesToAllDevices, authority) },
        )
    }

    override fun setAppliesToAllDevices(enabled: Boolean) {
        mutate(
            next = { it.copy(appliesToAllDevices = enabled) },
            write = { pref, authority -> controller.setAppliesToAllDevices(enabled, pref.showTitleArt, authority) },
        )
    }

    /**
     * Paints [next] of the preference on screen, then sends [write] with the
     * preference as it was *before* this change (which scope the main switch
     * addresses, and which value the companion switch carries, both come from
     * what the user was looking at when they chose).
     */
    private fun mutate(
        next: (TitleArtPreference) -> TitleArtPreference,
        write: suspend (TitleArtPreference, AuthScopeSnapshot) -> TitleArtController.WriteResult,
    ) {
        val startGeneration: Int
        val request: Long
        val before: TitleArtPreference
        val optimistic: TitleArtPreference
        val writes: Mutex
        synchronized(lock) {
            val current = _state.value
            // Never write to a server that has not confirmed the key, nor
            // pick a scope from a cached answer the server has not re-read.
            if (!current.canEdit) return
            before = current.preference
            optimistic = next(before)
            if (optimistic == before) return
            startGeneration = generation
            writes = writeLock
            request = ++requestCounter
            mutationEpoch += 1
            pendingWrites += 1
            _state.value = current.copy(preference = optimistic)
        }
        // The store owns the write, so leaving the screen cannot strand an
        // unsaved value on screen.
        scope.launch {
            // One profile for the whole change: every request it makes (up to
            // a PUT, a DELETE and a re-read) is pinned to this snapshot, so a
            // profile switch part-way through fails the rest instead of
            // landing them on the new profile.
            val authority = getAuthScope()
            val result = writes.withLock {
                val readFor = synchronized(lock) {
                    if (generation != startGeneration || request <= droppedThrough) return@withLock null
                    confirmedAuthority
                }
                if (authority == null || readFor == null || !authority.isSameProfileAs(readFor)) {
                    TitleArtController.WriteResult.Failed(IDENTITY_CHANGED)
                } else {
                    write(before, authority)
                }
            }
            var refreshNow = false
            val persisted = synchronized(lock) {
                if (generation != startGeneration) return@synchronized false
                pendingWrites = (pendingWrites - 1).coerceAtLeast(0)
                if (pendingWrites == 0 && refreshAfterWrites) {
                    refreshAfterWrites = false
                    refreshNow = true
                }
                when (result) {
                    // Dropped: queued behind a failed write.
                    null -> false
                    is TitleArtController.WriteResult.Saved -> {
                        confirmed = TitleArtState(
                            TitleArtSupport.Supported,
                            result.resolved ?: optimistic,
                            isConfirmed = true,
                        )
                        _saveError.value = null
                        if (requestCounter == request) {
                            mutationEpoch += 1
                            _state.value = confirmed
                        }
                        // Cache only what the server confirmed by re-reading.
                        result.resolved != null
                    }
                    is TitleArtController.WriteResult.Failed -> {
                        _saveError.value = saveErrorText(result.message)
                        // Every change queued behind this one was built on its
                        // unsaved state (which scope to write, which value to
                        // carry). Drop them all, show the confirmed state, and
                        // re-read the server once nothing is in flight.
                        droppedThrough = requestCounter
                        mutationEpoch += 1
                        _state.value = confirmed
                        if (pendingWrites == 0) {
                            refreshNow = true
                        } else {
                            refreshAfterWrites = true
                        }
                        false
                    }
                }
            }
            if (persisted) persistConfirmed(startGeneration)
            if (refreshNow) refresh(keepError = true)
        }
    }

    /** Caches under the identity the confirmed state was read for, never a re-read one. */
    private fun persistConfirmed(startGeneration: Int) {
        val (identity, snapshot) = synchronized(lock) {
            if (generation != startGeneration) return
            (confirmedIdentity ?: return) to confirmed
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
        _state.value = TitleArtState()
        confirmed = TitleArtState()
        confirmedAuthority = null
        confirmedIdentity = null
        pendingWrites = 0
        refreshAfterWrites = false
        // A load still running for the previous identity bails on the
        // generation check; the next caller starts a fresh one.
        inFlightLoad = null
        // The previous identity's write may still be on the wire; it keeps its
        // own lock and its completion bails on the generation check.
        writeLock = Mutex()
        _saveError.value = null
    }

    internal companion object {
        const val PREFS_NAME = "silo_title_art"
        const val IDENTITY_CHANGED = "The acting account or profile changed."

        /** Same wording as the skip-interval save error. */
        fun saveErrorText(message: String?): String =
            "Couldn't save title art" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")

        /** The cache key for this snapshot's server and profile; null without a profile. */
        private fun AuthScopeSnapshot.cacheIdentity(): String? =
            profileId?.takeIf { it.isNotBlank() }?.let { "$serverUrl|$it" }

        /**
         * Same server and profile, and the same sign-in when both are the
         * saved account. Not [AuthScopeSnapshot.isSameIdentityAs]: its
         * identity generation also moves when a remote-playback overlay starts
         * or ends, which leaves the profile a toggle addresses unchanged. An
         * overlay's snapshot carries no credential epoch (it is always 0), so
         * the epoch is compared only between two saved-account snapshots, and
         * the server ids are matched the way the overlay handoff matches them.
         * A real profile or server switch resets the store before the write
         * runs.
         */
        private fun AuthScopeSnapshot.isSameProfileAs(other: AuthScopeSnapshot): Boolean {
            if (profileId != other.profileId) return false
            if (!AndroidServerRegistry.serverIdsMatch(serverId, other.serverId)) return false
            val bothSavedAccount = credentialGenerationId == null && other.credentialGenerationId == null
            return !bothSavedAccount || credentialEpoch == other.credentialEpoch
        }

        /** Test seam: no Android Context, in-memory cache. */
        internal fun forTest(
            controller: TitleArtController,
            scope: CoroutineScope,
            getActiveProfileId: suspend () -> String? = { "profile-1" },
            getServerUrl: suspend () -> String? = { "https://server.test" },
            getAuthScope: (suspend () -> AuthScopeSnapshot?)? = null,
            cache: TitleArtCache = InMemoryTitleArtCache(),
            identityChanges: Flow<Unit> = emptyFlow(),
        ): DefaultTitleArtStore = DefaultTitleArtStore(
            controller = controller,
            scope = scope,
            getAuthScope = getAuthScope ?: {
                val url = getServerUrl().orEmpty()
                AuthScopeSnapshot(
                    serverId = url,
                    profileId = getActiveProfileId(),
                    serverUrl = url,
                    profileToken = "token-${getActiveProfileId()}",
                )
            },
            cache = cache,
            identityChanges = identityChanges,
        )
    }
}

/**
 * Last-known answer per server and profile, so a cold start paints the
 * profile's choice (the TV marquee is on screen immediately) instead of
 * flashing logos until the network answers.
 */
internal interface TitleArtCache {
    fun read(identity: String): TitleArtState?
    fun write(identity: String, state: TitleArtState)
}

internal class InMemoryTitleArtCache : TitleArtCache {
    private val entries = mutableMapOf<String, TitleArtState>()
    override fun read(identity: String): TitleArtState? = synchronized(entries) { entries[identity] }
    override fun write(identity: String, state: TitleArtState) {
        if (state.support != TitleArtSupport.Supported && state.support != TitleArtSupport.Unsupported) return
        synchronized(entries) { entries[identity] = state.copy(isConfirmed = false) }
    }
}

private class SharedPreferencesTitleArtCache(
    private val prefs: SharedPreferences,
) : TitleArtCache {

    override fun read(identity: String): TitleArtState? {
        val prefix = prefix(identity)
        val support = when (prefs.getString("$prefix.support", null)) {
            SUPPORTED -> TitleArtSupport.Supported
            UNSUPPORTED -> TitleArtSupport.Unsupported
            else -> return null
        }
        return TitleArtState(
            support = support,
            preference = TitleArtPreference(
                showTitleArt = prefs.getBoolean("$prefix.show", TitleArtController.DEFAULT_SHOW_TITLE_ART),
                appliesToAllDevices = prefs.getBoolean("$prefix.all_devices", false),
            ),
        )
    }

    override fun write(identity: String, state: TitleArtState) {
        val support = when (state.support) {
            TitleArtSupport.Supported -> SUPPORTED
            TitleArtSupport.Unsupported -> UNSUPPORTED
            TitleArtSupport.Unknown, TitleArtSupport.Unavailable -> return
        }
        val prefix = prefix(identity)
        prefs.edit {
            putString("$prefix.support", support)
            putBoolean("$prefix.show", state.preference.showTitleArt)
            putBoolean("$prefix.all_devices", state.preference.appliesToAllDevices)
        }
    }

    private fun prefix(identity: String): String = settingsCachePrefix("ta_", identity)

    private companion object {
        const val SUPPORTED = "supported"
        const val UNSUPPORTED = "unsupported"
    }
}
