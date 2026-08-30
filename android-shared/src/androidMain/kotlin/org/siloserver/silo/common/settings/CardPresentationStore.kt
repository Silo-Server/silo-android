package org.siloserver.silo.common.settings

import android.content.Context
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloDeviceMetadata
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the effective `ui.card_presentation` value resolved from. */
enum class CardPresentationSource {
    /** `profile_device` — "Only this device" override. */
    DeviceOverride,

    /** `profile_client` — roams among this profile's like-family devices. */
    ClientFamily,

    /** `profile` — profile-wide value (written by another client). */
    Profile,

    /** Contract default; nothing stored at any scope. */
    Default,

    /** Not resolved yet, or an unrecognized wire scope. */
    Unknown,
}

/** Whether the connected server supports the canonical card-presentation key. */
enum class CardPresentationSupport {
    Supported,

    /** Server too old (capabilities gate failed or contract routes 404). */
    Unsupported,

    /** No definitive capability answer yet (cold start / offline). */
    Unknown,
}

data class CardPresentationUiState(
    val presentation: CardPresentation = CardPresentation.DEFAULT,
    val source: CardPresentationSource = CardPresentationSource.Unknown,
    val support: CardPresentationSupport = CardPresentationSupport.Unknown,
)

/**
 * Cached card-presentation (poster size + caption) state for the signed-in
 * profile. Same shape as [OverlayPrefsStore]: lazy hydration, refresh on
 * foreground/reconnect via [ServerDrivenConfigRefresher], optimistic writes
 * with rollback, and [clear] at sign-out boundaries.
 *
 * Unlike overlays this key resolves through the like-client layer
 * (`profile_device > profile_client > profile > default`), so the store also
 * reports the resolved [CardPresentationSource] — the settings UI derives the
 * "Only this device" toggle and "Use profile default" action from it.
 */
interface CardPresentationStore {
    val state: StateFlow<CardPresentationUiState>
    val isLoading: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    /** Idempotent first-load. Safe to call from every provider mount. */
    suspend fun hydrateIfNeeded()

    /** Re-probe capabilities + re-resolve the effective value. */
    suspend fun refresh()

    /**
     * Optimistically apply [presentation] locally, then persist — at
     * `profile_device` when [deviceOnly], else `profile_client`. Rolls back
     * to the last confirmed state when the write fails.
     */
    fun set(presentation: CardPresentation, deviceOnly: Boolean)

    /** DELETE the `profile_device` row so resolution falls back, then refresh. */
    suspend fun clearDeviceOverride()

    /** DELETE the `profile_client` row ("Use profile default"), then refresh. */
    suspend fun useProfileDefault()

    /** Wipe in-memory state on sign-out so the next user re-hydrates clean. */
    fun clear()
}

class DefaultCardPresentationStore(
    context: Context,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val getActiveProfileId: suspend () -> String?,
    private val getServerUrl: suspend () -> String?,
    private val getDeviceMetadata: suspend () -> SiloDeviceMetadata?,
) : CardPresentationStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(CardPresentationUiState())
    private val _isLoading = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)

    override val state: StateFlow<CardPresentationUiState> = _state.asStateFlow()
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var hasHydrated: Boolean = false

    // The last state confirmed by a canonical read or write; the rollback
    // target for a failed optimistic edit.
    private var confirmedState: CardPresentationUiState = _state.value

    // Guards the short state commits so a refresh response captured before a
    // newer optimistic edit cannot replace that edit after it succeeds.
    private val stateLock = Any()

    // Bumped by every mutation; a refresh only commits its user-value half
    // when the epoch it started at is still current.
    private var canonicalEpoch: Long = 0L

    // Bumped by every [set]. A queued drain persists its snapshot unless a
    // NEWER edit was staged behind it (latest-wins coalescing). Deliberately
    // not a comparison against `_state.value`: an interleaved refresh can
    // commit a server value over the optimistic state, and that must not
    // cancel the user's pending write.
    private var editSequence: Long = 0L

    // Bumped by [clear] — a session boundary. `@Volatile` so a queued write
    // coroutine sees the bump without taking a lock and bails before its PUT.
    @Volatile
    private var writeGeneration: Int = 0

    private val refreshLock = Mutex()

    // Serializes PUT/DELETE drains so the wire order matches user intent.
    private val mutationMutex = Mutex()

    override suspend fun hydrateIfNeeded() {
        if (hasHydrated || _isLoading.value) return
        // Seed from the last-known cache for this exact identity so cold start
        // renders at the right size without a resize jump once the network
        // answer lands.
        currentIdentity()?.let { identity ->
            readCachedState(identity)?.let { cached ->
                synchronized(stateLock) {
                    // Only paint the cache over untouched (pre-hydration,
                    // pre-edit) state.
                    if (!hasHydrated && _state.value == confirmedState) {
                        confirmedState = cached
                        _state.value = cached
                    }
                }
            }
        }
        refresh()
    }

    /**
     * Capability probe + effective read. Failure semantics:
     * - Probe/read transport failure keeps the current (cached) state active
     *   and leaves [hasHydrated] false so the next [hydrateIfNeeded] retries.
     * - A definitive "unsupported" answer renders the default presentation
     *   and completes hydration; the settings UI shows the upgrade row.
     */
    override suspend fun refresh(): Unit = refreshLock.withLock {
        val startGeneration: Int
        val startEpoch: Long
        synchronized(stateLock) {
            startGeneration = writeGeneration
            _isLoading.value = true
            _lastError.value = null
            startEpoch = canonicalEpoch
        }
        try {
            val identity = currentIdentity() ?: return

            val support = when (val caps = repository.contractCapabilities()) {
                is SettingsCapabilitiesResult.Available ->
                    if (
                        caps.capabilities.apiVersion == 1 &&
                        caps.capabilities.revision >= MIN_CONTRACT_REVISION &&
                        caps.capabilities.supportsBatchedEffective &&
                        caps.capabilities.supportsIdempotentWrites
                    ) {
                        CardPresentationSupport.Supported
                    } else {
                        CardPresentationSupport.Unsupported
                    }
                is SettingsCapabilitiesResult.ServerUpgradeRequired ->
                    CardPresentationSupport.Unsupported
                is SettingsCapabilitiesResult.Error -> {
                    commitError(caps.message, startGeneration)
                    return
                }
                is SettingsCapabilitiesResult.NetworkError -> {
                    commitError(caps.exception.message, startGeneration)
                    return
                }
            }

            if (support == CardPresentationSupport.Unsupported) {
                val resolved = CardPresentationUiState(
                    presentation = CardPresentation.DEFAULT,
                    source = CardPresentationSource.Default,
                    support = CardPresentationSupport.Unsupported,
                )
                commitResolved(resolved, identity, startGeneration, startEpoch)
                return
            }

            when (val result = repository.getEffectiveValues(listOf(SETTING_KEY))) {
                is ApiResult.Success -> {
                    val entry = result.data[SETTING_KEY]
                    if (entry == null) {
                        commitError(
                            "The server did not resolve $SETTING_KEY",
                            startGeneration,
                        )
                        return
                    }
                    val resolved = CardPresentationUiState(
                        presentation = CardPresentation.decodeOrDefault(entry.value),
                        source = sourceFromWire(entry.source),
                        support = CardPresentationSupport.Supported,
                    )
                    commitResolved(resolved, identity, startGeneration, startEpoch)
                }
                is ApiResult.Error -> commitError(result.message, startGeneration)
                is ApiResult.NetworkError ->
                    commitError(result.exception.message, startGeneration)
            }
        } finally {
            _isLoading.value = false
        }
    }

    private fun commitResolved(
        resolved: CardPresentationUiState,
        identity: Identity,
        startGeneration: Int,
        startEpoch: Long,
    ) {
        val persisted = synchronized(stateLock) {
            if (writeGeneration != startGeneration) return@synchronized false
            // A mutation landed after this refresh started; its optimistic
            // state (or its own refresh) is newer than this response.
            if (canonicalEpoch != startEpoch) return@synchronized false
            confirmedState = resolved
            _state.value = resolved
            hasHydrated = true
            true
        }
        if (persisted) writeCachedState(identity, resolved)
    }

    private fun commitError(message: String?, startGeneration: Int) {
        synchronized(stateLock) {
            if (writeGeneration == startGeneration) {
                _lastError.value = message
            }
        }
    }

    override fun set(presentation: CardPresentation, deviceOnly: Boolean) {
        val optimistic = CardPresentationUiState(
            presentation = presentation,
            source = if (deviceOnly) {
                CardPresentationSource.DeviceOverride
            } else {
                CardPresentationSource.ClientFamily
            },
            support = CardPresentationSupport.Supported,
        )
        var edit = 0L
        val generation = synchronized(stateLock) {
            canonicalEpoch += 1
            editSequence += 1
            edit = editSequence
            _state.value = optimistic
            writeGeneration
        }
        scope.launch {
            mutationMutex.withLock {
                if (writeGeneration != generation) return@withLock
                val superseded = synchronized(stateLock) {
                    // Latest-wins coalescing: a newer optimistic edit was
                    // staged behind this one and its own queued drain will
                    // persist it — skip the stale PUT.
                    if (editSequence != edit) {
                        true
                    } else {
                        // Re-assert the optimistic state while staging the
                        // write (OverlayPrefsStore precedent): a refresh may
                        // have committed a server value over it since, and
                        // that must not silently cancel the user's edit.
                        _state.value = optimistic
                        false
                    }
                }
                if (superseded) return@withLock
                val value = presentation.toJsonElement()
                val result = if (deviceOnly) {
                    repository.setProfileDeviceValue(SETTING_KEY, value)
                } else {
                    repository.setProfileClientValue(SETTING_KEY, value)
                }
                when (result) {
                    is ApiResult.Success -> {
                        val identity = currentIdentity()
                        val persisted = synchronized(stateLock) {
                            if (writeGeneration != generation) return@synchronized false
                            // Invalidate any refresh captured before this PUT.
                            canonicalEpoch += 1
                            confirmedState = optimistic
                            // Do not paint a confirmed write over a newer edit
                            // that is already staged.
                            if (editSequence == edit) {
                                _state.value = optimistic
                                _lastError.value = null
                            }
                            true
                        }
                        if (persisted && identity != null) {
                            writeCachedState(identity, optimistic)
                        }
                    }
                    is ApiResult.Error ->
                        rollbackFailedWrite(edit, generation, result.message)
                    is ApiResult.NetworkError ->
                        rollbackFailedWrite(edit, generation, result.exception.message)
                }
            }
        }
    }

    private fun rollbackFailedWrite(
        edit: Long,
        generation: Int,
        message: String?,
    ) {
        synchronized(stateLock) {
            if (writeGeneration != generation) return
            canonicalEpoch += 1
            // A newer edit is the optimistic state the user should still see;
            // only restore the confirmed state when this edit is the latest.
            if (editSequence == edit) {
                _state.value = confirmedState
            }
            _lastError.value = message
        }
    }

    override suspend fun clearDeviceOverride() =
        // "Only this device" is off the moment the row is deleted, so demote
        // the source right away rather than leaving it at DeviceOverride until
        // the refresh lands: the toggle would snap back, and a preset picked
        // in between would be written at `profile_device` again — resurrecting
        // the override the user just removed.
        deleteScope(optimisticSource = CardPresentationSource.ClientFamily) {
            repository.clearProfileDeviceValue(SETTING_KEY)
        }

    override suspend fun useProfileDefault() =
        deleteScope { repository.clearProfileClientValue(SETTING_KEY) }

    /**
     * DELETE one scope row, then refresh so the state reflects whatever the
     * resolution falls back to. The presentation itself is not updated
     * optimistically — the fallback value is unknowable locally (it lives at
     * another scope), so the current cards hold until the refresh answers —
     * but [optimisticSource] lets a caller pin the scope the deletion moves
     * resolution to, rolled back if the DELETE never lands.
     */
    private suspend fun deleteScope(
        optimisticSource: CardPresentationSource? = null,
        delete: suspend () -> ApiResult<Unit>,
    ) {
        val generation = writeGeneration
        // The pre-delete state and the state we optimistically painted, so a
        // failed DELETE restores the former only while the latter is still on
        // screen (a newer edit outranks both).
        var previous: CardPresentationUiState? = null
        var optimistic: CardPresentationUiState? = null
        if (optimisticSource != null) {
            synchronized(stateLock) {
                val current = _state.value
                if (writeGeneration == generation && current.source != optimisticSource) {
                    val next = current.copy(source = optimisticSource)
                    canonicalEpoch += 1
                    previous = current
                    optimistic = next
                    _state.value = next
                }
            }
        }
        val deleted = mutationMutex.withLock {
            if (writeGeneration != generation) return@withLock false
            when (val result = delete()) {
                is ApiResult.Success -> {
                    synchronized(stateLock) {
                        if (writeGeneration == generation) canonicalEpoch += 1
                    }
                    true
                }
                is ApiResult.Error -> {
                    restoreSource(previous, optimistic, generation)
                    commitError(result.message, generation)
                    false
                }
                is ApiResult.NetworkError -> {
                    restoreSource(previous, optimistic, generation)
                    commitError(result.exception.message, generation)
                    false
                }
            }
        }
        if (deleted && writeGeneration == generation) refresh()
    }

    /** Undoes [deleteScope]'s optimistic source when the DELETE never landed. */
    private fun restoreSource(
        previous: CardPresentationUiState?,
        optimistic: CardPresentationUiState?,
        generation: Int,
    ) {
        if (previous == null || optimistic == null) return
        synchronized(stateLock) {
            if (writeGeneration != generation) return
            canonicalEpoch += 1
            if (_state.value == optimistic) _state.value = previous
        }
    }

    override fun clear() {
        synchronized(stateLock) {
            writeGeneration += 1
            canonicalEpoch += 1
            _state.value = CardPresentationUiState()
            confirmedState = _state.value
            hasHydrated = false
            // Let the next session's hydrateIfNeeded run instead of observing
            // the previous session's loading flag and returning early.
            _isLoading.value = false
            _lastError.value = null
        }
    }

    /**
     * The identity the last-known cache is keyed by. The client family is
     * part of it deliberately: a backup restored from a tablet to a phone
     * must not cold-start with the tablet family's cached choice.
     */
    private data class Identity(
        val serverUrl: String,
        val profileId: String,
        val clientFamily: String,
        val deviceId: String,
    ) {
        val cacheKeyPrefix: String =
            "cp_" + sha256Hex("$serverUrl|$profileId|$clientFamily|$deviceId").take(24)
    }

    private suspend fun currentIdentity(): Identity? {
        val profileId = getActiveProfileId() ?: return null
        val device = getDeviceMetadata()
        return Identity(
            serverUrl = getServerUrl().orEmpty(),
            profileId = profileId,
            clientFamily = device?.clientFamily.orEmpty(),
            deviceId = device?.id.orEmpty(),
        )
    }

    private fun readCachedState(identity: Identity): CardPresentationUiState? {
        val support = when (prefs.getString("${identity.cacheKeyPrefix}.support", null)) {
            SUPPORT_SUPPORTED -> CardPresentationSupport.Supported
            SUPPORT_UNSUPPORTED -> CardPresentationSupport.Unsupported
            else -> return null
        }
        val presentation = CardPresentation.decodeOrNull(
            prefs.getString("${identity.cacheKeyPrefix}.value", null),
        ) ?: CardPresentation.DEFAULT
        return CardPresentationUiState(
            presentation = presentation,
            source = sourceFromWire(prefs.getString("${identity.cacheKeyPrefix}.source", null)),
            support = support,
        )
    }

    private fun writeCachedState(identity: Identity, state: CardPresentationUiState) {
        prefs.edit()
            .putString("${identity.cacheKeyPrefix}.value", state.presentation.serialize())
            .putString("${identity.cacheKeyPrefix}.source", sourceToWire(state.source))
            .putString(
                "${identity.cacheKeyPrefix}.support",
                if (state.support == CardPresentationSupport.Unsupported) {
                    SUPPORT_UNSUPPORTED
                } else {
                    SUPPORT_SUPPORTED
                },
            )
            .apply()
    }

    private companion object {
        const val SETTING_KEY = SettingKeys.UI_CARD_PRESENTATION

        /** Contract revision that introduced `ui.card_presentation`. */
        const val MIN_CONTRACT_REVISION = 5

        const val PREFS_NAME = "silo_card_presentation"
        const val SUPPORT_SUPPORTED = "supported"
        const val SUPPORT_UNSUPPORTED = "unsupported"

        fun sourceFromWire(source: String?): CardPresentationSource = when (source) {
            SettingScope.PROFILE_DEVICE.wire -> CardPresentationSource.DeviceOverride
            SettingScope.PROFILE_CLIENT.wire -> CardPresentationSource.ClientFamily
            SettingScope.PROFILE.wire -> CardPresentationSource.Profile
            EffectiveSettingValue.SOURCE_DEFAULT -> CardPresentationSource.Default
            else -> CardPresentationSource.Unknown
        }

        fun sourceToWire(source: CardPresentationSource): String? = when (source) {
            CardPresentationSource.DeviceOverride -> SettingScope.PROFILE_DEVICE.wire
            CardPresentationSource.ClientFamily -> SettingScope.PROFILE_CLIENT.wire
            CardPresentationSource.Profile -> SettingScope.PROFILE.wire
            CardPresentationSource.Default -> EffectiveSettingValue.SOURCE_DEFAULT
            CardPresentationSource.Unknown -> null
        }

        fun sha256Hex(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
