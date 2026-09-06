package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.LibraryPlaybackPref
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.LibraryPlaybackPrefsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of the user's per-library playback preferences. Mirrors
 * iOS `PlaybackPrefsStore.swift` (Networking/PlaybackPrefsStore.swift).
 *
 * The settings UI is the only consumer that needs the full list — playback
 * time reads `effective_*` straight off the WatchDetail response, so this
 * store sits outside the hot path.
 *
 * Per-series subtitle / audio overrides are not cached here for the same
 * reason iOS doesn't cache them: they're fetched on demand by the player
 * and re-fetched from `WatchDetail.effective_*` on the next playback, so
 * caching would just duplicate authority.
 */
interface LibraryPlaybackPrefsStore {
    val libraryPrefs: StateFlow<Map<Int, LibraryPlaybackPref>>
    val isLoading: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    /** Fetch the full set if we haven't yet. Idempotent within a session. */
    suspend fun hydrateIfNeeded()

    /** Force a re-fetch. Settings UI calls this after a successful PUT/DELETE. */
    suspend fun refresh()

    /** Look up the cached pref row for a library. */
    fun pref(libraryId: Int): LibraryPlaybackPref?

    /** PUT to the server, then refresh local cache. Returns the API result. */
    suspend fun setPref(
        libraryId: Int,
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForcedSubtitles: Boolean?,
    ): ApiResult<Unit>

    /** DELETE on the server, then refresh local cache. */
    suspend fun deletePref(libraryId: Int): ApiResult<Unit>

    /**
     * Drop in-memory state. Called on sign-out so the next user doesn't
     * see the previous user's prefs flash onto the screen before the
     * fresh fetch lands.
     */
    fun clear()
}

class DefaultLibraryPlaybackPrefsStore(
    private val repository: LibraryPlaybackPrefsRepository,
) : LibraryPlaybackPrefsStore {

    private val _libraryPrefs = MutableStateFlow<Map<Int, LibraryPlaybackPref>>(emptyMap())
    private val _isLoading = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)
    private val refreshLock = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    @Volatile private var hasHydrated: Boolean = false

    override val libraryPrefs: StateFlow<Map<Int, LibraryPlaybackPref>> = _libraryPrefs.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated || _isLoading.value) return
        refresh()
    }

    override suspend fun refresh() = refreshLock.withLock {
        val expected = synchronized(stateLock) {
            _isLoading.value = true
            _lastError.value = null
            generation
        }
        try {
            val result = repository.list()
            synchronized(stateLock) {
                if (generation != expected) return@synchronized
                when (result) {
                    is ApiResult.Success -> {
                        _libraryPrefs.value = result.data
                        hasHydrated = true
                    }
                    is ApiResult.Error -> _lastError.value = result.message
                    is ApiResult.NetworkError -> _lastError.value = result.exception.message
                }
            }
        } finally {
            synchronized(stateLock) {
                if (generation == expected) _isLoading.value = false
            }
        }
    }

    override fun pref(libraryId: Int): LibraryPlaybackPref? = _libraryPrefs.value[libraryId]

    override suspend fun setPref(
        libraryId: Int,
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitleMode: String?,
        showForcedSubtitles: Boolean?,
    ): ApiResult<Unit> {
        val result = repository.set(
            libraryId = libraryId,
            audioLanguage = audioLanguage,
            subtitleLanguage = subtitleLanguage,
            subtitleMode = subtitleMode,
            showForcedSubtitles = showForcedSubtitles,
        )
        if (result is ApiResult.Success) refresh()
        return result
    }

    override suspend fun deletePref(libraryId: Int): ApiResult<Unit> {
        val result = repository.delete(libraryId)
        if (result is ApiResult.Success) refresh()
        return result
    }

    override fun clear() = synchronized(stateLock) {
        generation += 1
        _isLoading.value = false
        _libraryPrefs.value = emptyMap()
        _lastError.value = null
        hasHydrated = false
    }
}
