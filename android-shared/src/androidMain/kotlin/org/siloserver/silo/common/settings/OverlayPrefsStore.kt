package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlaySchema
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Cached card-overlay configuration for the signed-in profile. Mirrors
 * iOS `OverlayPrefsStore.swift` (Networking/OverlayPrefsStore.swift).
 *
 * Resolves a single rendered [CardOverlayPrefs] from one of two sources,
 * in this priority:
 *   1. The user's canonical profile value (`ui.card_overlays`) — if
 *      present, this is the entire source of truth.
 *   2. Otherwise, the admin-configured baseline JSON from
 *      `GET /settings/overlay-config` (`defaults` field).
 *   3. Otherwise, registry defaults ([OverlaySchema.buildDefaults]).
 *
 * Winner-take-all, not layered merging — [setPrefs] always saves a full
 * document (not a diff), keeping the wire format compatible with web,
 * iOS, and tvOS. Hydrated lazily on first read; successful canonical writes
 * become the confirmed local state so card views immediately see the shape
 * they just persisted.
 */
interface OverlayPrefsStore {
    /**
     * `true` when the server allows overlays at all. An admin can flip
     * this off globally; when `false`, cards should not render overlays
     * even if the user has prefs configured.
     */
    val enabled: StateFlow<Boolean>

    /** Resolved prefs (user value > admin defaults > registry defaults). */
    val prefs: StateFlow<CardOverlayPrefs>

    val isLoading: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    /** Whether the user has any saved override vs. running on admin defaults. */
    val hasUserOverride: Boolean

    /** Idempotent first-load. Safe to call on every view that wants overlays. */
    suspend fun hydrateIfNeeded()

    /** Re-fetch admin config + user setting and recompute [prefs]. */
    suspend fun refresh()

    /** Optimistically update local state, then persist (coalesced writes). */
    fun setPrefs(next: CardOverlayPrefs)

    /** Drop the user's override and fall back to the admin baseline. */
    suspend fun resetToDefaults()

    /** Wipe local state on sign-out so the next user gets a clean hydration. */
    fun clear()
}

class DefaultOverlayPrefsStore(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
) : OverlayPrefsStore {

    private val _enabled = MutableStateFlow(true)
    private val _prefs = MutableStateFlow(OverlaySchema.buildDefaults())
    private val _isLoading = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val prefs: StateFlow<CardOverlayPrefs> = _prefs.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    override var hasUserOverride: Boolean = false
        private set

    @Volatile
    private var hasHydrated: Boolean = false

    @Volatile
    private var adminDefaultsRaw: String? = null

    // The last state confirmed by a successful canonical read or write. An
    // optimistic edit rolls back here when its PUT fails, including when the
    // network is unavailable and a follow-up refresh would fail as well.
    @Volatile
    private var confirmedPrefs: CardOverlayPrefs = _prefs.value

    @Volatile
    private var confirmedHasUserOverride: Boolean = false

    private val refreshLock = Mutex()

    // Coalesced-write state. `writeMutex` guards the mutable bookkeeping
    // below; the actual PUT happens inside the drain coroutine.
    private val writeMutex = Mutex()

    @Volatile
    private var pendingWrite: Job? = null

    @Volatile
    private var pendingSnapshot: CardOverlayPrefs? = null

    // Monotonic token bumped by [clear]/[resetToDefaults]. Each drain captures
    // the value live when it starts; if [clear] bumps it mid-flight the drain
    // sees the mismatch and bails before serializing or issuing a PUT, so a
    // queued write for the previous auth scope can't land under the next one.
    // `@Volatile` so [clear] (which must run synchronously, off the write
    // coroutine) is visible to the drain without taking `writeMutex`.
    @Volatile
    private var writeGeneration: Int = 0

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated || _isLoading.value) return
        refresh()
    }

    /**
     * Re-fetch both the admin config and the canonical profile value, then recompute
     * [prefs].
     *
     * Failure semantics mirror iOS:
     * - A canonical null/default means "not set yet" and renders from admin
     *   defaults or registry defaults.
     * - Any other transport error on either endpoint leaves
     *   [hasHydrated] false so the next [hydrateIfNeeded] retries. This is
     *   critical for the admin kill-switch: if `/overlay-config` errors
     *   but the user setting resolves, we MUST NOT mark hydrated, or
     *   [enabled] is stuck at `true` and the admin's "disable globally"
     *   toggle is silently ignored for the session.
     */
    override suspend fun refresh() = refreshLock.withLock {
        _isLoading.value = true
        _lastError.value = null
        try {
            var resolvedEnabled = true
            var resolvedAdminDefaults: String? = null
            var configFetchFailed = false
            when (val config = repository.overlayConfig()) {
                is ApiResult.Success -> {
                    resolvedEnabled = config.data.enabled
                    resolvedAdminDefaults = config.data.defaults
                }
                is ApiResult.Error -> {
                    _lastError.value = config.message
                    configFetchFailed = true
                }
                is ApiResult.NetworkError -> {
                    _lastError.value = config.exception.message
                    configFetchFailed = true
                }
            }

            var userValue: JsonElement? = null
            var userFetchFailed = false
            when (val result = repository.getEffectiveValues(listOf(OVERLAY_SETTING_KEY))) {
                is ApiResult.Success -> {
                    val entry = result.data[OVERLAY_SETTING_KEY]
                    if (entry == null) {
                        _lastError.value = "The server did not resolve $OVERLAY_SETTING_KEY"
                        userFetchFailed = true
                    } else if (
                        entry.source == SettingScope.PROFILE.wire &&
                        entry.value !is JsonNull
                    ) {
                        userValue = entry.value
                    }
                }
                is ApiResult.Error -> {
                    _lastError.value = result.message
                    userFetchFailed = true
                }
                is ApiResult.NetworkError -> {
                    _lastError.value = result.exception.message
                    userFetchFailed = true
                }
            }

            // Preserve cached config state on transient failures. The
            // sentinel `resolvedEnabled = true` is only valid when the
            // fetch actually succeeded.
            if (!configFetchFailed) {
                _enabled.value = resolvedEnabled
                adminDefaultsRaw = resolvedAdminDefaults
            }
            if (!userFetchFailed) {
                val hasOverride = userValue != null
                val defaults = if (configFetchFailed) adminDefaultsRaw else resolvedAdminDefaults
                val resolvedPrefs = OverlaySchema.parse(userValue?.toString() ?: defaults)
                hasUserOverride = hasOverride
                _prefs.value = resolvedPrefs
                confirmedHasUserOverride = hasOverride
                confirmedPrefs = resolvedPrefs
            }
            // Only complete hydration when BOTH endpoints gave a
            // definitive answer.
            if (!configFetchFailed && !userFetchFailed) {
                hasHydrated = true
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Optimistically update local state, then persist. Writes are
     * serialized and coalesced: rapid changes (e.g. flipping presets)
     * issue one PUT at a time and intermediate snapshots are dropped,
     * preventing the stale-overwrite race where a slower earlier PUT
     * lands after a faster later one.
     */
    override fun setPrefs(next: CardOverlayPrefs) {
        _prefs.value = next
        hasUserOverride = true
        scope.launch {
            writeMutex.withLock {
                // Re-assert the optimistic state while staging the snapshot.
                // A preceding write may have completed between the immediate
                // UI update above and this coroutine acquiring the mutex.
                _prefs.value = next
                hasUserOverride = true
                pendingSnapshot = next
                if (pendingWrite?.isActive != true) {
                    val generation = writeGeneration
                    pendingWrite = scope.launch { flushPendingWrites(generation) }
                }
            }
        }
    }

    private suspend fun flushPendingWrites(generation: Int) {
        while (true) {
            // Bail if our coroutine was cancelled OR `clear()`/`resetToDefaults()`
            // bumped the generation out from under us — the queued snapshot
            // belongs to a session that is being torn down.
            if (currentCoroutineContext()[Job]?.isActive != true) return
            if (writeGeneration != generation) return
            val snapshot = writeMutex.withLock {
                val s = pendingSnapshot
                pendingSnapshot = null
                if (s == null) pendingWrite = null
                s
            } ?: return

            // Re-check immediately before serializing and before issuing the
            // PUT: `clear()` may have fired after we took the snapshot above.
            // Mirrors OverlayPrefsStore.swift's cancellation checks so no PUT
            // for the cleared session reaches the wire.
            if (currentCoroutineContext()[Job]?.isActive != true) return
            if (writeGeneration != generation) return
            val json = Json.parseToJsonElement(OverlaySchema.serialize(snapshot))
            if (currentCoroutineContext()[Job]?.isActive != true) return
            if (writeGeneration != generation) return
            when (val result = repository.setProfileValue(OVERLAY_SETTING_KEY, json)) {
                is ApiResult.Success -> {
                    writeMutex.withLock {
                        confirmedPrefs = snapshot
                        confirmedHasUserOverride = true
                        // Do not paint an older successful snapshot over a
                        // newer edit that is already queued.
                        if (pendingSnapshot == null) {
                            _prefs.value = snapshot
                            hasUserOverride = true
                            _lastError.value = null
                        }
                    }
                }
                is ApiResult.Error -> {
                    if (writeGeneration != generation) return
                    reconcileFailedWrite(result.message)
                }
                is ApiResult.NetworkError -> {
                    if (writeGeneration != generation) return
                    reconcileFailedWrite(result.exception.message)
                }
            }
        }
    }

    private suspend fun reconcileFailedWrite(message: String?) {
        writeMutex.withLock {
            // If another edit is queued, it is still the optimistic state the
            // user should see. Otherwise restore the last server-confirmed
            // document instead of leaving a rejected value on screen.
            if (pendingSnapshot == null) {
                _prefs.value = confirmedPrefs
                hasUserOverride = confirmedHasUserOverride
            }
            _lastError.value = message
        }
    }

    /**
     * Drop the user's override and fall back to the admin baseline.
     * Cancels and awaits any in-flight write before issuing the DELETE so
     * a slower earlier PUT can't land server-side after the DELETE and
     * recreate the document the user just asked us to drop.
     */
    override suspend fun resetToDefaults() {
        // Bump first so any drain that's mid-flight (already past its snapshot
        // grab) sees the generation change and bails before its PUT lands.
        writeGeneration += 1
        writeMutex.withLock {
            pendingSnapshot = null
            pendingWrite?.cancel()
        }
        pendingWrite?.join()
        writeMutex.withLock { pendingWrite = null }

        when (val result = repository.clearProfileValue(OVERLAY_SETTING_KEY)) {
            is ApiResult.Success -> {
                val fallback = OverlaySchema.parse(adminDefaultsRaw)
                hasUserOverride = false
                _prefs.value = fallback
                confirmedHasUserOverride = false
                confirmedPrefs = fallback
                refresh()
            }
            is ApiResult.Error -> {
                _prefs.value = confirmedPrefs
                hasUserOverride = confirmedHasUserOverride
                _lastError.value = result.message
            }
            is ApiResult.NetworkError -> {
                _prefs.value = confirmedPrefs
                hasUserOverride = confirmedHasUserOverride
                _lastError.value = result.exception.message
            }
        }
    }

    override fun clear() {
        // Synchronously invalidate the current drain so no further PUT for this
        // (now-ending) session can reach the wire. `writeGeneration` is
        // `@Volatile`, so a drain coroutine sees the bump at its next
        // cancellation check (immediately before serialize and before the PUT)
        // even though we don't hold `writeMutex` here. We also null the pending
        // snapshot synchronously so a drain that's about to grab it gets null
        // and exits. Cancelling the Job + nulling `pendingWrite` still happens
        // under the mutex on the write coroutine, but correctness no longer
        // depends on that running before the session boundary.
        writeGeneration += 1
        pendingSnapshot = null
        val inflight = pendingWrite
        pendingWrite = null
        inflight?.cancel()
        _enabled.value = true
        _prefs.value = OverlaySchema.buildDefaults()
        confirmedPrefs = _prefs.value
        adminDefaultsRaw = null
        hasUserOverride = false
        confirmedHasUserOverride = false
        hasHydrated = false
        _lastError.value = null
    }

    companion object {
        const val OVERLAY_SETTING_KEY = SettingKeys.UI_CARD_OVERLAYS
    }
}
