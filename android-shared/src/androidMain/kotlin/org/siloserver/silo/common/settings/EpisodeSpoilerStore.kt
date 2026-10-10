package org.siloserver.silo.common.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Whether the connected server knows the revision-17 spoiler keys. */
enum class EpisodeSpoilerSupport {
    Supported,

    /** Definitive answer: the server predates the keys. Never write. */
    Unsupported,

    /** No answer yet (cold start without a cache, or offline). */
    Unknown,
}

/** One of the two spoiler switches. */
enum class EpisodeSpoilerSetting(val key: String) {
    Images(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_IMAGES),
    Overviews(SettingKeys.CATALOG_HIDE_UNWATCHED_EPISODE_OVERVIEWS),
}

data class EpisodeSpoilerState(
    val support: EpisodeSpoilerSupport = EpisodeSpoilerSupport.Unknown,
    val hideImages: Boolean = false,
    val hideOverviews: Boolean = false,
) {
    val isSupported: Boolean get() = support == EpisodeSpoilerSupport.Supported

    /**
     * What surfaces apply. Nothing is hidden unless the server is known to
     * support the keys, so an older server shows episodes as before.
     */
    val prefs: EpisodeSpoilerPrefs
        get() = if (isSupported) EpisodeSpoilerPrefs(hideImages, hideOverviews) else EpisodeSpoilerPrefs.NONE

    fun value(setting: EpisodeSpoilerSetting): Boolean = when (setting) {
        EpisodeSpoilerSetting.Images -> hideImages
        EpisodeSpoilerSetting.Overviews -> hideOverviews
    }

    fun with(setting: EpisodeSpoilerSetting, enabled: Boolean): EpisodeSpoilerState = when (setting) {
        EpisodeSpoilerSetting.Images -> copy(hideImages = enabled)
        EpisodeSpoilerSetting.Overviews -> copy(hideOverviews = enabled)
    }
}

/**
 * The signed-in profile's spoiler protection for unwatched episodes
 * (`catalog.hide_unwatched_episode_images` and
 * `catalog.hide_unwatched_episode_overviews`, settings contract revision 17),
 * shared by every phone and TV surface through `LocalEpisodeSpoilerPrefs`.
 *
 * Both keys allow only `scope=profile`, so writes go straight to the profile
 * row (never through [ServerSettingsFlusher], which writes `profile_device`).
 * Same lifecycle as [CardPresentationStore]: lazy hydration seeded from a
 * last-known cache, refresh on foreground and reconnect through
 * [ServerDrivenConfigRefresher], optimistic writes with rollback, and [clear]
 * at sign-out and profile-switch boundaries.
 *
 * Like [SeekIntervalStore], it also resets and re-resolves on its own when the
 * active server or profile changes, and a hydration only counts for the
 * identity it ran against. Signing in to another server through Add Server
 * clears nothing, so a store that trusted a plain "already hydrated" flag
 * kept the previous server's answer until the next cold start.
 */
interface EpisodeSpoilerStore {
    val state: StateFlow<EpisodeSpoilerState>
    val lastError: StateFlow<String?>

    /** Idempotent first load. Safe to call from every provider mount. */
    suspend fun hydrateIfNeeded()

    /** Re-probe capabilities and re-resolve both keys. */
    suspend fun refresh()

    /**
     * Apply [enabled] locally, then write it at profile scope; restores the
     * last server-confirmed value if the write fails. Ignored unless the
     * server is known to support the keys.
     */
    fun set(setting: EpisodeSpoilerSetting, enabled: Boolean)

    /** Session boundary: drop state so the next profile starts clean. */
    fun clear()
}

class DefaultEpisodeSpoilerStore private constructor(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val getActiveProfileId: suspend () -> String?,
    private val getServerUrl: suspend () -> String?,
    private val captureAuthority: suspend () -> AuthScopeSnapshot?,
    private val cache: EpisodeSpoilerCache,
    identityChanges: Flow<Unit>,
) : EpisodeSpoilerStore {

    constructor(
        context: Context,
        repository: SettingsRepository,
        scope: CoroutineScope,
        getActiveProfileId: suspend () -> String?,
        getServerUrl: suspend () -> String?,
        captureAuthority: suspend () -> AuthScopeSnapshot?,
        identityChanges: Flow<Unit> = emptyFlow(),
    ) : this(
        repository = repository,
        scope = scope,
        getActiveProfileId = getActiveProfileId,
        getServerUrl = getServerUrl,
        captureAuthority = captureAuthority,
        cache = SharedPreferencesEpisodeSpoilerCache(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        identityChanges = identityChanges,
    )

    private val _state = MutableStateFlow(EpisodeSpoilerState())
    private val _lastError = MutableStateFlow<String?>(null)
    override val state: StateFlow<EpisodeSpoilerState> = _state.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lock = Any()

    /** Bumped at every session boundary; stale work compares and bails. */
    @Volatile
    private var generation = 0

    /** Bumped by every local mutation; a refresh that started earlier must
     *  not paint its older server answer over the newer local value. */
    private var mutationEpoch = 0L

    @Volatile
    private var hasHydrated = false

    /** The server|profile the shown state belongs to; null after a reset. */
    @Volatile
    private var stateIdentity: String? = null

    /** The auth scope captured with [stateIdentity]; writes are pinned to it so
     *  a server or profile switch cannot retarget a queued toggle. */
    private var stateAuthority: AuthScopeSnapshot? = null

    /** The last values the server confirmed; a failed write restores these. */
    private var confirmed = EpisodeSpoilerState()

    /** Latest local request per setting. Only that request may change what is
     *  shown when it settles; an older one only updates [confirmed]. */
    private val latestRequest = mutableMapOf<EpisodeSpoilerSetting, Long>()
    private var requestCounter = 0L

    private val refreshLock = Mutex()

    // Serializes writes so the wire order matches the order of the taps.
    private val writeLock = Mutex()

    init {
        scope.launch {
            identityChanges.collectLatest { onIdentityChanged() }
        }
    }

    private suspend fun onIdentityChanged() {
        val identity = currentIdentity()
        val authority = captureAuthority()
        val startGeneration = synchronized(lock) {
            resetLocked()
            stateIdentity = identity
            stateAuthority = authority
            generation
        }
        // A refresh for the previous identity can hold refreshLock through a
        // network call; show this identity's last-known answer meanwhile.
        if (identity != null) seedFromCache(identity, startGeneration)
        hydrateIfNeeded()
    }

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated && stateIdentity == currentIdentity()) return
        refresh()
    }

    override suspend fun refresh(): Unit = refreshLock.withLock {
        val identity = currentIdentity() ?: return
        val authority = captureAuthority()
        val startGeneration: Int
        val startEpoch: Long
        synchronized(lock) {
            // Another server or profile: the shown answer is not ours.
            if (stateIdentity != identity) {
                resetLocked()
                stateIdentity = identity
            }
            stateAuthority = authority
            startGeneration = generation
            startEpoch = mutationEpoch
        }
        seedFromCache(identity, startGeneration)

        val supported = when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success -> EpisodeSpoilers.isSupported(caps.data)
            // A server without the contract routes answers 404: that is an
            // older server, not a transient failure.
            is ApiResult.Error ->
                if (caps.code == 404) false else return commitError(caps.message, startGeneration)
            is ApiResult.NetworkError -> return commitError(caps.exception.message, startGeneration)
        }
        // Superseded by a session boundary: release refreshLock now rather
        // than after a second request whose answer would be discarded.
        if (generation != startGeneration) return
        val resolved = if (!supported) {
            EpisodeSpoilerState(support = EpisodeSpoilerSupport.Unsupported)
        } else {
            when (val result = repository.getEffectiveValues(EpisodeSpoilers.KEYS)) {
                is ApiResult.Success -> EpisodeSpoilerState(
                    support = EpisodeSpoilerSupport.Supported,
                    hideImages = decode(result.data, EpisodeSpoilerSetting.Images),
                    hideOverviews = decode(result.data, EpisodeSpoilerSetting.Overviews),
                )
                // A server can reach the revision without these keys when
                // another setting took that revision number; it refuses them
                // as unknown_setting (404). That is unsupported, not a
                // transient failure to retry forever.
                is ApiResult.Error ->
                    if (result.code == 404) EpisodeSpoilerState(support = EpisodeSpoilerSupport.Unsupported)
                    else return commitError(result.message, startGeneration)
                is ApiResult.NetworkError ->
                    return commitError(result.exception.message, startGeneration)
            }
        }
        val committed = synchronized(lock) {
            if (generation != startGeneration || mutationEpoch != startEpoch || latestRequest.isNotEmpty()) {
                return@synchronized false
            }
            _state.value = resolved
            confirmed = resolved
            _lastError.value = null
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
            if (!hasHydrated && generation == startGeneration &&
                _state.value.support == EpisodeSpoilerSupport.Unknown
            ) {
                _state.value = cached
                confirmed = cached
            }
        }
    }

    /** A transient failure keeps whatever answer (cached or earlier) is shown. */
    private fun commitError(message: String?, startGeneration: Int) {
        synchronized(lock) {
            if (generation == startGeneration) _lastError.value = message
        }
    }

    override fun set(setting: EpisodeSpoilerSetting, enabled: Boolean) {
        val startGeneration: Int
        val request: Long
        val identity: String
        val authority: AuthScopeSnapshot?
        synchronized(lock) {
            val current = _state.value
            if (!current.isSupported) return
            identity = stateIdentity ?: return
            authority = stateAuthority
            startGeneration = generation
            request = ++requestCounter
            latestRequest[setting] = request
            mutationEpoch += 1
            _state.value = current.with(setting, enabled)
        }
        scope.launch {
            val result = writeLock.withLock {
                if (generation != startGeneration) return@launch
                repository.setProfileValue(setting.key, JsonPrimitive(enabled), authority)
            }
            val error = when (result) {
                is ApiResult.Success -> null
                is ApiResult.Error -> result.message.ifBlank { SAVE_FAILED_MESSAGE }
                is ApiResult.NetworkError -> result.exception.message ?: SAVE_FAILED_MESSAGE
            }
            val snapshot = synchronized(lock) {
                if (generation != startGeneration) return@launch
                if (error == null) confirmed = confirmed.with(setting, enabled)
                if (latestRequest[setting] == request) {
                    latestRequest.remove(setting)
                    mutationEpoch += 1
                    // Saved: re-assert the value. Failed: return to what the
                    // server last confirmed.
                    val shown = if (error == null) enabled else confirmed.value(setting)
                    _state.value = _state.value.with(setting, shown)
                }
                _lastError.value = error
                confirmed
            }
            if (error == null) synchronized(lock) {
                if (generation == startGeneration && stateIdentity == identity) cache.write(identity, snapshot)
            }
        }
    }

    override fun clear() {
        synchronized(lock) { resetLocked() }
    }

    private fun resetLocked() {
        generation += 1
        mutationEpoch += 1
        hasHydrated = false
        stateIdentity = null
        stateAuthority = null
        _state.value = EpisodeSpoilerState()
        confirmed = EpisodeSpoilerState()
        latestRequest.clear()
        _lastError.value = null
    }

    private suspend fun currentIdentity(): String? {
        val profileId = getActiveProfileId()?.takeIf { it.isNotBlank() } ?: return null
        return "${getServerUrl().orEmpty()}|$profileId"
    }

    internal companion object {
        const val PREFS_NAME = "silo_episode_spoilers"
        const val SAVE_FAILED_MESSAGE = "Could not save the setting."

        /** Only a JSON boolean `true` turns a switch on; anything else is off. */
        fun decode(effective: Map<String, EffectiveSettingValue>, setting: EpisodeSpoilerSetting): Boolean =
            decodeValue(effective[setting.key]?.value)

        private fun decodeValue(value: JsonElement?): Boolean {
            val primitive = value as? JsonPrimitive ?: return false
            return !primitive.isString && primitive.booleanOrNull == true
        }

        /** Test seam: no Android Context, in-memory cache. */
        internal fun forTest(
            repository: SettingsRepository,
            scope: CoroutineScope,
            getActiveProfileId: suspend () -> String? = { "profile-1" },
            getServerUrl: suspend () -> String? = { "https://server.test" },
            captureAuthority: suspend () -> AuthScopeSnapshot? = { null },
            cache: EpisodeSpoilerCache = InMemoryEpisodeSpoilerCache(),
            identityChanges: Flow<Unit> = emptyFlow(),
        ): DefaultEpisodeSpoilerStore = DefaultEpisodeSpoilerStore(
            repository = repository,
            scope = scope,
            getActiveProfileId = getActiveProfileId,
            getServerUrl = getServerUrl,
            captureAuthority = captureAuthority,
            cache = cache,
            identityChanges = identityChanges,
        )
    }
}

/**
 * Last-known answer per server and profile, so a cold start hides spoilers
 * before the network answers instead of flashing them first.
 */
internal interface EpisodeSpoilerCache {
    fun read(identity: String): EpisodeSpoilerState?
    fun write(identity: String, state: EpisodeSpoilerState)
}

internal class InMemoryEpisodeSpoilerCache : EpisodeSpoilerCache {
    private val entries = mutableMapOf<String, EpisodeSpoilerState>()
    override fun read(identity: String): EpisodeSpoilerState? = synchronized(entries) { entries[identity] }
    override fun write(identity: String, state: EpisodeSpoilerState) {
        if (state.support == EpisodeSpoilerSupport.Unknown) return
        synchronized(entries) { entries[identity] = state }
    }
}

private class SharedPreferencesEpisodeSpoilerCache(
    private val prefs: SharedPreferences,
) : EpisodeSpoilerCache {

    override fun read(identity: String): EpisodeSpoilerState? {
        val prefix = prefix(identity)
        val support = when (prefs.getString("$prefix.support", null)) {
            SUPPORTED -> EpisodeSpoilerSupport.Supported
            UNSUPPORTED -> EpisodeSpoilerSupport.Unsupported
            else -> return null
        }
        return EpisodeSpoilerState(
            support = support,
            hideImages = prefs.getBoolean("$prefix.images", false),
            hideOverviews = prefs.getBoolean("$prefix.overviews", false),
        )
    }

    override fun write(identity: String, state: EpisodeSpoilerState) {
        val support = when (state.support) {
            EpisodeSpoilerSupport.Supported -> SUPPORTED
            EpisodeSpoilerSupport.Unsupported -> UNSUPPORTED
            EpisodeSpoilerSupport.Unknown -> return
        }
        val prefix = prefix(identity)
        prefs.edit {
            putString("$prefix.support", support)
            putBoolean("$prefix.images", state.hideImages)
            putBoolean("$prefix.overviews", state.hideOverviews)
        }
    }

    private fun prefix(identity: String): String =
        "es_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(24)

    private companion object {
        const val SUPPORTED = "supported"
        const val UNSUPPORTED = "unsupported"
    }
}
