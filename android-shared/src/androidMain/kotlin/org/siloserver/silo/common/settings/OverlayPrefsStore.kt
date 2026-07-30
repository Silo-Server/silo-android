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

    // Refreshes run on a foreground IO coroutine while edits are drained on
    // the application Main scope. Keep their short state commits atomic so a
    // response captured before a newer edit cannot replace that edit after it
    // succeeds. This is deliberately separate from [writeGeneration], whose
    // only job is invalidating queued/in-flight drains at a session boundary.
    private val stateLock = Any()

    // Only [clear] crosses an authenticated-session boundary. Resetting the
    // profile value must not discard an otherwise valid admin-config response.
    private var sessionStateEpoch: Long = 0L

    private var canonicalStateEpoch: Long = 0L

    private val refreshLock = Mutex()

    // A reset must finish its DELETE before a later edit is staged for PUT.
    // The edit still updates [_prefs] synchronously, but its drain waits here
    // so the wire order is always DELETE -> PUT and the later edit wins.
    private val mutationBoundaryMutex = Mutex()

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
        val refreshState = synchronized(stateLock) {
            _isLoading.value = true
            _lastError.value = null
            RefreshState(
                sessionStateEpoch = sessionStateEpoch,
                canonicalStateEpoch = canonicalStateEpoch,
                adminDefaultsRaw = adminDefaultsRaw,
            )
        }
        try {
            var resolvedEnabled = true
            var resolvedAdminDefaults: String? = null
            var configFetchFailed = false
            var configError: String? = null
            when (val config = repository.overlayConfig()) {
                is ApiResult.Success -> {
                    resolvedEnabled = config.data.enabled
                    resolvedAdminDefaults = config.data.defaults
                }
                is ApiResult.Error -> {
                    configError = config.message
                    configFetchFailed = true
                }
                is ApiResult.NetworkError -> {
                    configError = config.exception.message
                    configFetchFailed = true
                }
            }

            var userValue: JsonElement? = null
            var userFetchFailed = false
            var userError: String? = null
            when (val result = repository.getEffectiveValues(listOf(OVERLAY_SETTING_KEY))) {
                is ApiResult.Success -> {
                    val entry = result.data[OVERLAY_SETTING_KEY]
                    if (entry == null) {
                        userError = "The server did not resolve $OVERLAY_SETTING_KEY"
                        userFetchFailed = true
                    } else if (
                        entry.source == SettingScope.PROFILE.wire &&
                        entry.value !is JsonNull
                    ) {
                        userValue = entry.value
                    }
                }
                is ApiResult.Error -> {
                    userError = result.message
                    userFetchFailed = true
                }
                is ApiResult.NetworkError -> {
                    userError = result.exception.message
                    userFetchFailed = true
                }
            }

            val resolvedPrefs = if (userFetchFailed) {
                null
            } else {
                val defaults = if (configFetchFailed) {
                    refreshState.adminDefaultsRaw
                } else {
                    resolvedAdminDefaults
                }
                OverlaySchema.parse(userValue?.toString() ?: defaults)
            }
            val resolvedAdminPrefs = if (configFetchFailed) {
                null
            } else {
                OverlaySchema.parse(resolvedAdminDefaults)
            }

            synchronized(stateLock) {
                // Clear crosses a session boundary, so none of the old refresh
                // may land. Profile edits and reset only invalidate the
                // user-derived half: the independent admin kill-switch and
                // baseline remain valid and must still update.
                if (sessionStateEpoch == refreshState.sessionStateEpoch) {
                    val userResponseIsCurrent =
                        canonicalStateEpoch == refreshState.canonicalStateEpoch
                    // Preserve cached config state on transient failures. The
                    // sentinel `resolvedEnabled = true` is only valid when the
                    // fetch actually succeeded.
                    if (!configFetchFailed) {
                        _enabled.value = resolvedEnabled
                        adminDefaultsRaw = resolvedAdminDefaults
                        // When the current confirmed state has no user value,
                        // the admin baseline is independently authoritative.
                        // Keep an optimistic edit visible, but update its
                        // rollback target so a failed PUT cannot restore an
                        // obsolete baseline.
                        if (
                            (!userResponseIsCurrent || userFetchFailed) &&
                            !confirmedHasUserOverride &&
                            resolvedAdminPrefs != null
                        ) {
                            val wasShowingConfirmedState =
                                _prefs.value == confirmedPrefs &&
                                    hasUserOverride == confirmedHasUserOverride
                            confirmedPrefs = resolvedAdminPrefs
                            if (wasShowingConfirmedState) {
                                _prefs.value = resolvedAdminPrefs
                            }
                        }
                    }

                    if (userResponseIsCurrent) {
                        _lastError.value = userError ?: configError
                        if (!userFetchFailed && resolvedPrefs != null) {
                            val hasOverride = userValue != null
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
                    }
                }
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
        val generation = synchronized(stateLock) {
            canonicalStateEpoch += 1
            _prefs.value = next
            hasUserOverride = true
            writeGeneration
        }
        scope.launch {
            mutationBoundaryMutex.withLock {
                writeMutex.withLock {
                    val belongsToCurrentSession = synchronized(stateLock) {
                        if (writeGeneration != generation) {
                            false
                        } else {
                            // Re-assert the optimistic state while staging the
                            // snapshot. A preceding write may have completed
                            // between the immediate UI update above and this
                            // coroutine acquiring the mutex.
                            _prefs.value = next
                            hasUserOverride = true
                            true
                        }
                    }
                    if (!belongsToCurrentSession) return@withLock

                    pendingSnapshot = next
                    if (pendingWrite?.isActive != true) {
                        pendingWrite = scope.launch { flushPendingWrites(generation) }
                    }
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
                    val applied = writeMutex.withLock {
                        synchronized(stateLock) {
                            if (writeGeneration != generation) {
                                false
                            } else {
                                // Every confirmed snapshot invalidates a GET
                                // that was captured before this PUT completed,
                                // even when a newer snapshot is already queued.
                                canonicalStateEpoch += 1
                                confirmedPrefs = snapshot
                                confirmedHasUserOverride = true
                                // Do not paint an older successful snapshot
                                // over a newer edit that is already queued.
                                if (pendingSnapshot == null) {
                                    _prefs.value = snapshot
                                    hasUserOverride = true
                                    _lastError.value = null
                                }
                                true
                            }
                        }
                    }
                    if (!applied) return
                }
                is ApiResult.Error -> {
                    if (writeGeneration != generation) return
                    reconcileFailedWrite(result.message, generation)
                }
                is ApiResult.NetworkError -> {
                    if (writeGeneration != generation) return
                    reconcileFailedWrite(result.exception.message, generation)
                }
            }
        }
    }

    private suspend fun reconcileFailedWrite(message: String?, generation: Int) {
        writeMutex.withLock {
            synchronized(stateLock) {
                if (writeGeneration != generation) return@synchronized
                // A refresh may have started after the optimistic edit but
                // before this terminal failure. Make that response stale so
                // it cannot erase the rejection or re-confirm old state.
                canonicalStateEpoch += 1
                // If another edit is queued, it is still the optimistic state
                // the user should see. Otherwise restore the last
                // server-confirmed document instead of leaving a rejected
                // value on screen.
                if (pendingSnapshot == null) {
                    _prefs.value = confirmedPrefs
                    hasUserOverride = confirmedHasUserOverride
                }
                _lastError.value = message
            }
        }
    }

    /**
     * Drop the user's override and fall back to the admin baseline.
     * Cancels and awaits any in-flight write before issuing the DELETE so
     * a slower earlier PUT can't land server-side after the DELETE and
     * recreate the document the user just asked us to drop.
     */
    override suspend fun resetToDefaults() = mutationBoundaryMutex.withLock {
        // Bump first so any drain that's mid-flight (already past its snapshot
        // grab) sees the generation change and bails before its PUT lands.
        val resetState = synchronized(stateLock) {
            writeGeneration += 1
            canonicalStateEpoch += 1
            ResetState(
                writeGeneration = writeGeneration,
                canonicalStateEpoch = canonicalStateEpoch,
            )
        }
        val inflight = writeMutex.withLock {
            pendingSnapshot = null
            pendingWrite.also { it?.cancel() }
        }
        inflight?.join()
        writeMutex.withLock {
            if (pendingWrite === inflight) pendingWrite = null
        }

        when (val result = repository.clearProfileValue(OVERLAY_SETTING_KEY)) {
            is ApiResult.Success -> {
                val shouldRefresh = synchronized(stateLock) {
                    if (writeGeneration != resetState.writeGeneration) {
                        false
                    } else {
                        val isStillLatestMutation =
                            canonicalStateEpoch == resetState.canonicalStateEpoch
                        canonicalStateEpoch += 1
                        // The DELETE is canonical even when a newer optimistic
                        // edit is waiting. If that later PUT fails, it must
                        // roll back to the now-cleared server state, not the
                        // override that the DELETE removed.
                        val fallback = OverlaySchema.parse(adminDefaultsRaw)
                        confirmedHasUserOverride = false
                        confirmedPrefs = fallback
                        if (isStillLatestMutation) {
                            hasUserOverride = false
                            _prefs.value = fallback
                        }
                        isStillLatestMutation
                    }
                }
                if (shouldRefresh) refresh()
            }
            is ApiResult.Error -> {
                synchronized(stateLock) {
                    if (writeGeneration == resetState.writeGeneration) {
                        val isStillLatestMutation =
                            canonicalStateEpoch == resetState.canonicalStateEpoch
                        canonicalStateEpoch += 1
                        if (!isStillLatestMutation) return@synchronized
                        _prefs.value = confirmedPrefs
                        hasUserOverride = confirmedHasUserOverride
                        _lastError.value = result.message
                    }
                }
            }
            is ApiResult.NetworkError -> {
                synchronized(stateLock) {
                    if (writeGeneration == resetState.writeGeneration) {
                        val isStillLatestMutation =
                            canonicalStateEpoch == resetState.canonicalStateEpoch
                        canonicalStateEpoch += 1
                        if (!isStillLatestMutation) return@synchronized
                        _prefs.value = confirmedPrefs
                        hasUserOverride = confirmedHasUserOverride
                        _lastError.value = result.exception.message
                    }
                }
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
        val inflight = synchronized(stateLock) {
            writeGeneration += 1
            sessionStateEpoch += 1
            canonicalStateEpoch += 1
            pendingSnapshot = null
            val activeWrite = pendingWrite
            pendingWrite = null
            _enabled.value = true
            _prefs.value = OverlaySchema.buildDefaults()
            confirmedPrefs = _prefs.value
            adminDefaultsRaw = null
            hasUserOverride = false
            confirmedHasUserOverride = false
            hasHydrated = false
            // Let the next authenticated session queue its refresh behind any
            // old in-flight request. Otherwise hydrateIfNeeded() observes the
            // previous session's loading flag, returns, and never retries.
            _isLoading.value = false
            _lastError.value = null
            activeWrite
        }
        inflight?.cancel()
    }

    private data class RefreshState(
        val sessionStateEpoch: Long,
        val canonicalStateEpoch: Long,
        val adminDefaultsRaw: String?,
    )

    private data class ResetState(
        val writeGeneration: Int,
        val canonicalStateEpoch: Long,
    )

    companion object {
        const val OVERLAY_SETTING_KEY = SettingKeys.UI_CARD_OVERLAYS
    }
}
