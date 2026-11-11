package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayImage
import org.siloserver.silo.overlays.OverlaySchema
import org.siloserver.silo.overlays.OverlayServerSupport
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
 *
 * A save writes the document as the connected server can store it (web
 * `useOverlayPrefs`: `overlayPrefsForServer`), judged by the server's
 * settings manifest revision and the overlay ids its stored value already
 * holds. While the revision is unknown the write is conservative.
 */
interface OverlayPrefsStore {
    /**
     * `true` when cards should render overlays at all. The profile's
     * `ui.card_overlays_enabled` choice wins in either direction; a profile
     * that has not chosen inherits the server-wide `overlays.enabled`
     * default from `GET /settings/overlay-config`. When `false`, cards
     * render no overlays even if the profile has prefs configured.
     */
    val enabled: StateFlow<Boolean>

    /**
     * Resolved prefs (user value > admin defaults > registry defaults), as
     * stored: image refs stay even when the library no longer lists them.
     * This is the document an editor edits and [setPrefs] writes back.
     */
    val prefs: StateFlow<CardOverlayPrefs>

    /**
     * The document cards render: [prefs] without image refs [images] does not
     * list (web `withoutUnlistedImages`), so a deleted image costs no request
     * per card. The same instance as [prefs] when nothing is dropped.
     */
    val cardPrefs: StateFlow<CardOverlayPrefs>

    /**
     * Images administrators uploaded for badges, in upload order, as
     * `GET /api/v2/settings/overlay-config` lists them. Each [OverlayImage.url]
     * is absolute (resolved against the server that listed it). A failed
     * config read keeps the last list.
     */
    val images: StateFlow<List<OverlayImage>>

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
    private val _cardPrefs = MutableStateFlow(_prefs.value)
    private val _images = MutableStateFlow<List<OverlayImage>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val prefs: StateFlow<CardOverlayPrefs> = _prefs.asStateFlow()
    override val cardPrefs: StateFlow<CardOverlayPrefs> = _cardPrefs.asStateFlow()
    override val images: StateFlow<List<OverlayImage>> = _images.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    override var hasUserOverride: Boolean = false
        private set

    @Volatile
    private var hasHydrated: Boolean = false

    @Volatile
    private var adminDefaultsRaw: String? = null

    // The two inputs to [enabled], cached separately so a failed read of
    // one keeps the last answer for that half without discarding the other.
    // Both change only under [stateLock].
    private var serverEnabled: Boolean = true
    private var profileEnabled: Boolean? = null

    // The last state confirmed by a successful canonical read or write. An
    // optimistic edit rolls back here when its PUT fails, including when the
    // network is unavailable and a follow-up refresh would fail as well.
    @Volatile
    private var confirmedPrefs: CardOverlayPrefs = _prefs.value

    @Volatile
    private var confirmedHasUserOverride: Boolean = false

    // What the server can store in ui.card_overlays (web
    // `OverlayServerSupport`). [manifestRevision] is null until a capabilities
    // read answers for this session; a failed read keeps the last answer.
    // [storedOverlayIds] are the ids of the profile value behind
    // [confirmedPrefs]: the last successful read's, or the last successful
    // PUT's. Both change only under [stateLock].
    private var manifestRevision: Int? = null
    private var storedOverlayIds: Set<String> = emptySet()

    // The refs [images] lists, for [_cardPrefs]. Changes only under [stateLock].
    private var listedImageRefs: Set<String> = emptySet()

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
     * Re-fetch both the admin config and the canonical profile values, then
     * recompute [prefs] and [enabled].
     *
     * Failure semantics mirror iOS:
     * - A canonical null/default means "not set yet" and renders from admin
     *   defaults or registry defaults.
     * - Any other transport error on either endpoint leaves
     *   [hasHydrated] false so the next [hydrateIfNeeded] retries. This is
     *   critical for the server-wide overlay default: if `/overlay-config`
     *   errors but the user settings resolve, we MUST NOT mark hydrated, or
     *   [enabled] is stuck at `true` for a profile that has not chosen and
     *   the admin's "off for everyone" default is silently ignored for the
     *   session.
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
            var resolvedImages: List<OverlayImage> = emptyList()
            var configFetchFailed = false
            var configError: String? = null
            when (val config = repository.overlayConfig()) {
                is ApiResult.Success -> {
                    resolvedEnabled = config.data.enabled
                    resolvedAdminDefaults = config.data.defaults
                    resolvedImages = config.data.images
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
            var userEnabled: Boolean? = null
            var userFetchFailed = false
            var userError: String? = null
            when (
                val result = repository.getEffectiveValues(
                    listOf(OVERLAY_SETTING_KEY, OVERLAYS_ENABLED_SETTING_KEY),
                )
            ) {
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
                    // A server that predates the key omits it, which reads
                    // the same as a profile that has not chosen.
                    userEnabled = result.data[OVERLAYS_ENABLED_SETTING_KEY]
                        ?.takeIf { it.source == SettingScope.PROFILE.wire }
                        ?.value
                        ?.let { it as? JsonPrimitive }
                        ?.booleanOrNull
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

            // Only decides how the next save is shaped, so a failure neither
            // fails the refresh nor blocks hydration.
            val resolvedRevision = fetchManifestRevision()

            val resolvedPrefs = if (userFetchFailed) {
                null
            } else if (userValue != null) {
                OverlaySchema.parse(userValue)
            } else {
                val defaults = if (configFetchFailed) {
                    refreshState.adminDefaultsRaw
                } else {
                    resolvedAdminDefaults
                }
                OverlaySchema.parse(defaults)
            }
            val resolvedAdminPrefs = if (configFetchFailed) {
                null
            } else {
                OverlaySchema.parse(resolvedAdminDefaults)
            }

            synchronized(stateLock) {
                // Clear crosses a session boundary, so none of the old refresh
                // may land. Profile edits and reset only invalidate the
                // user-derived half: the independent server-wide default and
                // baseline remain valid and must still update.
                if (sessionStateEpoch == refreshState.sessionStateEpoch) {
                    val userResponseIsCurrent =
                        canonicalStateEpoch == refreshState.canonicalStateEpoch
                    if (resolvedRevision != null) manifestRevision = resolvedRevision
                    // Preserve cached config state on transient failures. The
                    // sentinel `resolvedEnabled = true` is only valid when the
                    // fetch actually succeeded.
                    if (!configFetchFailed) {
                        serverEnabled = resolvedEnabled
                        adminDefaultsRaw = resolvedAdminDefaults
                        if (_images.value != resolvedImages) {
                            _images.value = resolvedImages
                            listedImageRefs = resolvedImages.mapTo(HashSet()) { it.ref }
                            publishPrefs(_prefs.value)
                        }
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
                                publishPrefs(resolvedAdminPrefs)
                            }
                        }
                    }
                    // This store never writes ui.card_overlays_enabled, so a
                    // local document edit cannot make this answer stale.
                    if (!userFetchFailed) {
                        profileEnabled = userEnabled
                    }
                    // An explicit profile choice overrides the server-wide
                    // default in either direction, as on web.
                    _enabled.value = profileEnabled ?: serverEnabled

                    if (userResponseIsCurrent) {
                        _lastError.value = userError ?: configError
                        if (!userFetchFailed && resolvedPrefs != null) {
                            val hasOverride = userValue != null
                            hasUserOverride = hasOverride
                            publishPrefs(resolvedPrefs)
                            confirmedHasUserOverride = hasOverride
                            confirmedPrefs = resolvedPrefs
                            storedOverlayIds = OverlaySchema.storedOverlayIds(userValue)
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
            publishPrefs(next)
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
                            publishPrefs(next)
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
            val support = serverSupportForWrite(generation)
            if (currentCoroutineContext()[Job]?.isActive != true) return
            if (writeGeneration != generation) return
            // Without the server's revision a save could only be the
            // conservative legacy document, which would erase the profile's
            // edge positions, style, images and preset on a current server.
            // Fail the save instead: nothing is lost, and the next edit or
            // refresh can try again once capabilities answer.
            if (support.manifestRevision == null) {
                reconcileFailedWrite(UNKNOWN_REVISION_ERROR, generation)
                continue
            }
            val written = OverlaySchema.serialize(snapshot, support)
            val json = Json.parseToJsonElement(written)
            // Below revision 25 the server stores an adapted document (rails
            // as corners, no style or images, classic for plate and band).
            // That is what is confirmed and shown, not the edit as made.
            val accepted = if (written == OverlaySchema.serialize(snapshot)) snapshot else OverlaySchema.parse(json)
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
                                confirmedPrefs = accepted
                                confirmedHasUserOverride = true
                                // The server now holds exactly what this PUT
                                // wrote, so those are the ids it accepts.
                                storedOverlayIds = OverlaySchema.storedOverlayIds(json)
                                // Do not paint an older successful snapshot
                                // over a newer edit that is already queued.
                                if (pendingSnapshot == null) {
                                    publishPrefs(accepted)
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
                    publishPrefs(confirmedPrefs)
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
                        storedOverlayIds = emptySet()
                        if (isStillLatestMutation) {
                            hasUserOverride = false
                            publishPrefs(fallback)
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
                        publishPrefs(confirmedPrefs)
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
                        publishPrefs(confirmedPrefs)
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
            serverEnabled = true
            profileEnabled = null
            _enabled.value = true
            _images.value = emptyList()
            listedImageRefs = emptySet()
            manifestRevision = null
            storedOverlayIds = emptySet()
            publishPrefs(OverlaySchema.buildDefaults())
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

    /**
     * Publish [prefs] as the stored document and its render form. Callers hold
     * [stateLock], so the two flows never describe different documents.
     */
    private fun publishPrefs(prefs: CardOverlayPrefs) {
        _prefs.value = prefs
        _cardPrefs.value = OverlaySchema.withoutUnlistedImages(prefs, listedImageRefs)
    }

    /**
     * The server's settings manifest revision, or null when it is unknown:
     * the read failed, or the server speaks another settings API version
     * (web reads `manifest_revision` only for `api_version` 1).
     */
    private suspend fun fetchManifestRevision(): Int? =
        when (val caps = repository.contractCapabilities()) {
            is ApiResult.Success ->
                caps.data.manifestRevision.takeIf { caps.data.apiVersion == SETTINGS_API_VERSION }
            is ApiResult.Error, is ApiResult.NetworkError -> null
        }

    /**
     * What the server accepts for the next PUT. When no capabilities read has
     * answered yet this session, try once more; if that fails too the
     * revision stays null and the write is refused (see [flushPendingWrites]).
     */
    private suspend fun serverSupportForWrite(generation: Int): OverlayServerSupport {
        synchronized(stateLock) {
            manifestRevision?.let { return OverlayServerSupport(it, storedOverlayIds) }
        }
        val fetched = fetchManifestRevision()
        return synchronized(stateLock) {
            if (fetched != null && writeGeneration == generation && manifestRevision == null) {
                manifestRevision = fetched
            }
            OverlayServerSupport(manifestRevision ?: fetched, storedOverlayIds)
        }
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
        const val OVERLAYS_ENABLED_SETTING_KEY = SettingKeys.UI_CARD_OVERLAYS_ENABLED
        private const val SETTINGS_API_VERSION = 1
        private const val UNKNOWN_REVISION_ERROR = "Could not read the server's settings revision" // i18n-ignore
    }
}
