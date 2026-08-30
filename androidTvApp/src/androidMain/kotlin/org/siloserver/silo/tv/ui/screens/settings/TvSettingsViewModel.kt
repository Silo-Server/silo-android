package org.siloserver.silo.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.settings.CardPresentationSource
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.CardPresentationSupport
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.data.preferences.LegacyTvPrefsMigration
import org.siloserver.silo.tv.data.preferences.SubtitleMode
import org.siloserver.silo.tv.data.preferences.SubtitleSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the TV settings screen. Server-managed device settings
 * flow exclusively through [PlayerSettingsStore] (mirror of iOS
 * `PlayerSettings.shared`); profile-level preferences go through
 * [ProfileSettingsController], which writes them as canonical settings at
 * `scope=profile` rather than as columns on the profile endpoint — the same
 * path the phone uses, so the two screens cannot drift.
 * [LegacyTvPrefsMigration] runs the one-time legacy `tv_prefs` → server
 * import on first boot (sentinel-gated no-op after).
 *
 * Sign-out and switch-profile operations emit a one-shot [NavAction]
 * signal that the screen collects and forwards to the top-level NavHost.
 */
class TvSettingsViewModel(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
    private val playerSettingsStore: PlayerSettingsStore,
    private val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val cardPresentationStore: CardPresentationStore,
    private val legacyTvPrefsMigration: LegacyTvPrefsMigration,
    private val profileSettings: ProfileSettingsController,
    private val tvLibraryScopeStore: org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore? = null,
) : ViewModel() {

    enum class NavAction { SIGNED_OUT, SWITCH_PROFILE }

    data class UiState(
        val user: User? = null,
        val userLoading: Boolean = true,
        val userError: String? = null,
        // Active profile identity for the tappable account header row.
        val profileName: String? = null,
        val profileAvatar: String? = null,
        val serverUrl: String = "",
        val serverName: String = "",
        // Whether this server serves the canonical settings API at all. When
        // it reports SERVER_UPGRADE_REQUIRED the pane explains that instead of
        // showing rows whose edits go nowhere; playback is unaffected.
        val settingsAvailability: ProfileSettingsController.Availability =
            ProfileSettingsController.Availability.UNKNOWN,
        // Quality is two orthogonal values behind one picker:
        // playback.preferred_quality (resolution) and
        // playback.max_bitrate_kbps (bandwidth; null = uncapped). The preset
        // table is shared with the phone, so the two cannot drift.
        val qualityResolution: String = QualityPresets.RESOLUTION_AUTO,
        val maxBitrateKbps: Int? = null,
        val subtitleMode: SubtitleMode = SubtitleMode.Auto,
        val subtitleLanguage: String = "",
        val subtitleLanguageSuggestions: List<String> = emptyList(),
        // Metadata AI: preferred description/metadata language ("" = server default).
        val metadataLanguage: String = "",
        val metadataLanguageSuggestions: List<String> = emptyList(),
        val audioLanguage: String = "",
        val audioLanguageSuggestions: List<String> = emptyList(),
        val subtitleSize: SubtitleSize = SubtitleSize.Medium,
        val showForcedSubtitles: Boolean = true,
        // Full subtitle appearance + whether the device-scoped override is on.
        // Mirrors iOS `subtitleAppearance` / `subtitleUsesDeviceAppearanceOverride`.
        val subtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,
        val effectiveSubtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,
        val subtitleUsesDeviceOverride: Boolean = false,
        val autoPlayNext: Boolean = true,
        val introSkipMode: IntroSkipMode = IntroSkipMode.Default,
        val matchContentFrameRate: Boolean = false,
        val dolbyVisionEnabled: Boolean = true,
        val showAudiobooksTab: Boolean = false,
        val subtitleMatchesDevice: Boolean = false,
        val dvProfile7HDR10Fallback: Boolean = true,
        val autoSkipCredits: Boolean = false,
        // Seconds to skip back on resume (0 = off); consecutive auto-advances
        // before the "Still watching?" prompt (0 = off).
        val resumeRewindSeconds: Int = 7,
        val passOutThreshold: Int = 3,
        // Seconds before the end of an episode to surface the Up-Next prompt
        // (0 = at the very end). Mirrors tvOS `nextUpPromptSeconds`.
        val nextUpPromptSeconds: Int = 10,
        // Cards & Posters (`ui.card_presentation`), mirrored from
        // CardPresentationStore. Source drives the "Only This Device" toggle
        // and the "Use Profile Default" action; support gates the whole group.
        val cardPresentation: CardPresentation = CardPresentation.DEFAULT,
        val cardPresentationSource: CardPresentationSource = CardPresentationSource.Unknown,
        val cardPresentationSupport: CardPresentationSupport = CardPresentationSupport.Unknown,
        val navAction: NavAction? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadUser()
        loadSettings()
        observePlayerSettings()
        observeCardPresentation()
    }

    /**
     * Loads the current user, and the active profile that supplies the account
     * header's name and avatar. A transient failure would blank that header, so
     * retry a few times with a short backoff before surfacing
     * [UiState.userError]. Only the final attempt's failure is reported; a
     * flaky load recovers. Exposed publicly so a screen-level retry can also
     * call it.
     */
    fun loadUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(userLoading = true, userError = null) }
            repeat(UserLoadMaxAttempts) { attempt ->
                val isLastAttempt = attempt == UserLoadMaxAttempts - 1
                when (val r = authRepository.getCurrentUser()) {
                    is ApiResult.Success -> {
                        // Retried alongside /me. getActiveProfile collapses
                        // "network failed", "no active id" and "not found" into
                        // null, so without this a transient failure left the
                        // account header without a name or avatar for the life
                        // of this ViewModel. Bounded by the same attempt budget
                        // so it cannot become a poll.
                        var profile = profileRepository.getActiveProfile()
                        var profileAttempt = 1
                        while (profile == null && profileAttempt < UserLoadMaxAttempts) {
                            delay(ProfileResolveRetryMs)
                            profile = profileRepository.getActiveProfile()
                            profileAttempt += 1
                        }
                        _uiState.update {
                            it.copy(
                                user = r.data,
                                userLoading = false,
                                userError = null,
                                profileName = profile?.name,
                                profileAvatar = profile?.avatar,
                            )
                        }
                        return@launch
                    }
                    is ApiResult.Error -> if (isLastAttempt) {
                        _uiState.update {
                            it.copy(
                                userLoading = false,
                                userError = r.message.ifBlank { "Failed to load user" },
                            )
                        }
                    }
                    is ApiResult.NetworkError -> if (isLastAttempt) {
                        _uiState.update {
                            it.copy(
                                userLoading = false,
                                userError = "Network error: ${r.exception.message ?: "unknown"}",
                            )
                        }
                    }
                }
                if (!isLastAttempt) delay(UserLoadRetryDelayMs)
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val activeServer = serverRegistry.activeEntry.value
            val serverUrl = activeServer?.url ?: tokenManager.getServerUrl()
            _uiState.update {
                it.copy(
                    serverUrl = serverUrl,
                    serverName = activeServer?.fetchedName?.takeIf { it.isNotBlank() }
                        ?: activeServer?.displayName
                        ?: serverDisplayName(serverUrl),
                )
            }

            // One-shot import of pre-server-sync TvPreferences values.
            // Idempotent — sentinel-gated inside the migration.
            legacyTvPrefsMigration.migrateIfNeeded()

            // Pull effective device settings (cascade user → device → default).
            // The store writes them to its DataStore; observePlayerSettings()
            // mirrors them into _uiState.
            playerSettingsStore.refreshFromServer()

            // Idempotent — the nav-level ProvideCardPresentation usually got
            // here first; this covers a cold open straight into Settings.
            cardPresentationStore.hydrateIfNeeded()

            loadProfileSettings()
        }
    }

    /**
     * Resolves the profile-scoped preferences through the canonical settings
     * API, and records whether this server speaks it at all.
     *
     * On [ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED] the
     * values are left alone and the Subtitles pane explains why — rendering
     * rows whose edits silently go nowhere is the failure this replaces.
     * Playback keeps running from the device-scoped store.
     */
    fun loadProfileSettings() {
        viewModelScope.launch {
            val result = profileSettings.load()
            _uiState.update { state ->
                val snapshot = result.snapshot ?: return@update state.copy(
                    settingsAvailability = result.availability,
                )
                state.copy(
                    settingsAvailability = result.availability,
                    subtitleMode = SubtitleMode.fromWire(snapshot.subtitleMode),
                    subtitleLanguage = snapshot.subtitleLanguage,
                    metadataLanguage = snapshot.metadataLanguage,
                    showForcedSubtitles = snapshot.showForcedSubtitles,
                    audioLanguageSuggestions = snapshot.audioLanguageSuggestions,
                    subtitleLanguageSuggestions = snapshot.subtitleLanguageSuggestions,
                    metadataLanguageSuggestions = snapshot.metadataLanguageSuggestions,
                )
            }
        }
    }

    /**
     * Replaces the optimistic values with what the server actually resolves.
     *
     * A successful PUT stores the authored value; it does not make it
     * effective. Policy can narrow it, and a device-scoped row for the same key
     * outranks the profile row these setters write — so the screen would
     * otherwise show a preference playback is not using. Skipped when a newer
     * edit for the *same* field landed while the round trip was in flight,
     * which the optimistic rollbacks guard the same way.
     */
    private fun applyResolved(
        snapshot: ProfileSettingsController.Snapshot?,
        edited: String,
        fieldOf: (ProfileSettingsController.Snapshot) -> String,
    ) {
        if (snapshot == null) return
        if (fieldOf(snapshot) == edited) {
            _uiState.update {
                it.copy(
                    audioLanguageSuggestions = snapshot.audioLanguageSuggestions,
                    subtitleLanguageSuggestions = snapshot.subtitleLanguageSuggestions,
                    metadataLanguageSuggestions = snapshot.metadataLanguageSuggestions,
                )
            }
            return
        }
        _uiState.update { state ->
            state.copy(
                subtitleMode = SubtitleMode.fromWire(snapshot.subtitleMode),
                subtitleLanguage = snapshot.subtitleLanguage,
                metadataLanguage = snapshot.metadataLanguage,
                showForcedSubtitles = snapshot.showForcedSubtitles,
                audioLanguageSuggestions = snapshot.audioLanguageSuggestions,
                subtitleLanguageSuggestions = snapshot.subtitleLanguageSuggestions,
                metadataLanguageSuggestions = snapshot.metadataLanguageSuggestions,
            )
        }
    }

    /**
     * Mirror device-scoped flows into UI state. The store is the single
     * source of truth — this just projects to the TV-specific UI types
     * (the two quality axes, SubtitleSize).
     */
    private fun observePlayerSettings() {
        viewModelScope.launch {
            combine(
                playerSettingsStore.preferredQualityFlow,
                playerSettingsStore.maxBitrateKbpsFlow,
                playerSettingsStore.autoPlayNextFlow,
                playerSettingsStore.introSkipModeFlow,
                playerSettingsStore.autoSkipCreditsFlow,
                playerSettingsStore.savedCustomSubtitleAppearanceFlow,
                playerSettingsStore.audioLanguageFlow,
                playerSettingsStore.resumeRewindSecondsFlow,
                playerSettingsStore.passOutThresholdFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val quality = values[0] as String
                val bitrate = values[1] as Int?
                @Suppress("UNCHECKED_CAST")
                val autoPlay = values[2] as Boolean
                @Suppress("UNCHECKED_CAST")
                val skipIntro = values[3] as IntroSkipMode
                @Suppress("UNCHECKED_CAST")
                val skipCredits = values[4] as Boolean
                @Suppress("UNCHECKED_CAST")
                val appearance = values[5] as SubtitleAppearance
                @Suppress("UNCHECKED_CAST")
                val audioLang = values[6] as String
                val rewind = values[7] as Int
                val threshold = values[8] as Int
                Snapshot(
                    quality, bitrate, autoPlay, skipIntro, skipCredits,
                    appearance, audioLang, rewind, threshold,
                )
            }.collect { snap ->
                _uiState.update {
                    it.copy(
                        qualityResolution = snap.quality,
                        maxBitrateKbps = snap.maxBitrateKbps,
                        autoPlayNext = snap.autoPlay,
                        introSkipMode = snap.skipIntro,
                        autoSkipCredits = snap.skipCredits,
                        subtitleSize = snap.appearance.fontSize.toTvSubtitleSize(),
                        subtitleAppearance = snap.appearance,
                        audioLanguage = snap.audioLanguage,
                        resumeRewindSeconds = snap.resumeRewindSeconds,
                        passOutThreshold = snap.passOutThreshold,
                    )
                }
            }
        }
        // Up-Next prompt timing lives outside the 8-arg combine above.
        viewModelScope.launch {
            playerSettingsStore.nextUpPromptSecondsFlow.collect { seconds ->
                _uiState.update { it.copy(nextUpPromptSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            playerSettingsStore.matchContentFrameRateFlow.collect { value ->
                _uiState.update { it.copy(matchContentFrameRate = value) }
            }
        }
        viewModelScope.launch {
            playerSettingsStore.dolbyVisionEnabledFlow.collect { value ->
                _uiState.update { it.copy(dolbyVisionEnabled = value) }
            }
        }
        viewModelScope.launch {
            tvLibraryScopeStore?.let { store ->
                val value = runCatching { store.getShowAudiobooksTab() }.getOrDefault(false)
                _uiState.update { it.copy(showAudiobooksTab = value) }
            }
        }
        viewModelScope.launch {
            playerSettingsStore.dvProfile7HDR10FallbackFlow.collect { value ->
                _uiState.update { it.copy(dvProfile7HDR10Fallback = value) }
            }
        }
        viewModelScope.launch {
            playerSettingsStore.subtitleMatchesDeviceFlow.collect { value ->
                _uiState.update { it.copy(subtitleMatchesDevice = value) }
            }
        }
        // Device-scoped subtitle-appearance override toggle (same source the
        // player HUD reads); also kept out of the 8-arg combine.
        viewModelScope.launch {
            playerSettingsStore.subtitleUsesDeviceOverrideFlow.collect { enabled ->
                _uiState.update { it.copy(subtitleUsesDeviceOverride = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsStore.effectiveSubtitleAppearanceFlow.collect { appearance ->
                _uiState.update { it.copy(effectiveSubtitleAppearance = appearance) }
            }
        }
    }

    /** Mirror the card-presentation store into UI state (single source of truth). */
    private fun observeCardPresentation() {
        viewModelScope.launch {
            cardPresentationStore.state.collect { cardState ->
                _uiState.update {
                    it.copy(
                        cardPresentation = cardState.presentation,
                        cardPresentationSource = cardState.source,
                        cardPresentationSupport = cardState.support,
                    )
                }
            }
        }
    }

    /**
     * Apply a new card presentation. The store is optimistic (cards resize
     * immediately through LocalCardPresentation) and rolls back on failure.
     * While the "Only This Device" override is active the edit stays at
     * `profile_device`; otherwise it roams at `profile_client`.
     */
    fun onCardPresentationSelected(presentation: CardPresentation) {
        val deviceOnly =
            _uiState.value.cardPresentationSource == CardPresentationSource.DeviceOverride
        cardPresentationStore.set(presentation, deviceOnly = deviceOnly)
    }

    /**
     * "Only This Device": ON copies the current value to `profile_device`;
     * OFF deletes that row so resolution falls back to the family value.
     */
    fun onCardPresentationDeviceOnlyChanged(enabled: Boolean) {
        if (enabled) {
            cardPresentationStore.set(_uiState.value.cardPresentation, deviceOnly = true)
        } else {
            viewModelScope.launch { cardPresentationStore.clearDeviceOverride() }
        }
    }

    /** Drop the `profile_client` value so this TV follows the profile default. */
    fun onUseProfileCardDefault() {
        viewModelScope.launch { cardPresentationStore.useProfileDefault() }
    }

    /**
     * Friendly server name for the About group — the host of the configured
     * URL (mirrors tvOS `serverDisplayName`, which collapses to the host when
     * no nicer name is known). Falls back to the raw value if it can't be
     * parsed.
     */
    private fun serverDisplayName(url: String): String {
        if (url.isBlank()) return ""
        return url
            .substringAfter("://", url)
            .substringBefore('/')
            .ifBlank { url }
    }

    /**
     * Applies one quality preset — the two axes it decomposes into. The
     * compound legacy spellings ("1080p-high") are never written.
     */
    fun onQualityPresetSelected(presetId: String) {
        val preset = QualityPresets.byId(presetId) ?: return
        viewModelScope.launch {
            playerSettingsStore.setQuality(preset.resolution, preset.bitrateKbps)
        }
    }

    // The four profile preferences below are canonical settings written at
    // scope=profile, one key per edit. They used to be named columns sent
    // together on PUT /profiles/{id}, where one failed write reverted all
    // three. Each applies optimistically and rolls back only if state still
    // holds the value it wrote — a newer edit mid-request wins.

    fun onSubtitleModeChanged(value: SubtitleMode) {
        val previous = _uiState.value.subtitleMode
        _uiState.update { it.copy(subtitleMode = value) }
        viewModelScope.launch {
            val result = profileSettings.setSubtitleMode(value.wireValue)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.subtitleMode == value) it.copy(subtitleMode = previous) else it
                }
            } else {
                applyResolved(result.snapshot, edited = value.wireValue) { it.subtitleMode }
            }
        }
    }

    fun onMetadataLanguageChanged(value: String) {
        val previous = _uiState.value.metadataLanguage
        _uiState.update { it.copy(metadataLanguage = value) }
        viewModelScope.launch {
            val result = profileSettings.setMetadataLanguage(value)
            if (!result.succeeded) {
                _uiState.update { current ->
                    if (current.metadataLanguage == value) current.copy(metadataLanguage = previous) else current
                }
            } else {
                applyResolved(result.snapshot, edited = value) { it.metadataLanguage }
            }
        }
    }

    fun onSubtitleLanguageChanged(value: String) {
        val previous = _uiState.value.subtitleLanguage
        _uiState.update { it.copy(subtitleLanguage = value) }
        viewModelScope.launch {
            val result = profileSettings.setSubtitleLanguage(value)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.subtitleLanguage == value) it.copy(subtitleLanguage = previous) else it
                }
            } else {
                applyResolved(result.snapshot, edited = value) { it.subtitleLanguage }
            }
        }
    }

    /**
     * Default audio language — a LOCAL player setting (not a profile field),
     * matching the phone. Writing the store emits on audioLanguageFlow which
     * the combine above folds back into [UiState.audioLanguage].
     */
    fun onAudioLanguageChanged(value: String) {
        viewModelScope.launch { playerSettingsStore.setAudioLanguage(value) }
    }

    fun onShowForcedSubtitlesChanged(enabled: Boolean) {
        val previous = _uiState.value.showForcedSubtitles
        _uiState.update { it.copy(showForcedSubtitles = enabled) }
        viewModelScope.launch {
            val result = profileSettings.setShowForcedSubtitles(enabled)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.showForcedSubtitles == enabled) it.copy(showForcedSubtitles = previous) else it
                }
            } else {
                applyResolved(result.snapshot, edited = enabled.toString()) {
                    it.showForcedSubtitles.toString()
                }
            }
        }
    }

    fun onSubtitleSizeChanged(value: SubtitleSize) =
        editAppearance { it.copy(fontSize = value.toFontSizePreset()) }

    /**
     * Commit a full subtitle-appearance value (device-scoped). The Appearance
     * picker rows build [next] by copying the current appearance and changing
     * one field, mirroring the tvOS bindings. The store debounces the server
     * write and re-emits on [subtitleAppearanceFlow], which the combine folds
     * back into [UiState.subtitleAppearance].
     */
    fun setSubtitleAppearance(next: SubtitleAppearance) {
        viewModelScope.launch { playerSettingsStore.setSubtitleAppearance(next) }
    }

    /**
     * Per-field appearance setters. Each reads the freshest appearance from the
     * store before copying the single changed field, so a concurrent edit (e.g.
     * a HUD change while a Settings picker is open) is not clobbered by a stale
     * composable-captured snapshot. Mirrors [onSubtitleSizeChanged].
     */
    private fun editAppearance(transform: (SubtitleAppearance) -> SubtitleAppearance) {
        viewModelScope.launch {
            val current = playerSettingsStore.subtitleAppearanceFlow.first()
            playerSettingsStore.setSubtitleAppearance(transform(current))
            // The granular subtitle.* fields are client-local — the contract
            // carries appearance as one object — so a per-field edit only
            // reaches the server once it is projected into the composite.
            playerSettingsStore.flushProjectedSubtitleAppearance()
        }
    }

    fun setSubtitleFontSize(value: SubtitleFontSizePreset) = editAppearance { it.copy(fontSize = value) }

    fun setSubtitleFontFamily(value: String) = editAppearance { it.copy(fontFamily = value) }

    fun setSubtitleFontColor(value: String) = editAppearance { it.copy(fontColor = value) }

    fun setSubtitleTextOutline(value: Boolean) = editAppearance { it.copy(textOutline = value) }

    fun setSubtitleTextOutlineColor(value: String) = editAppearance { it.copy(textOutlineColor = value) }

    fun setSubtitleBackgroundStyle(value: SubtitleBackgroundStylePreset) =
        editAppearance { it.copy(backgroundStyle = value) }

    fun setSubtitleBackgroundOpacity(value: Int) = editAppearance { it.copy(backgroundOpacity = value) }

    fun setSubtitleBackgroundColor(value: String) = editAppearance { it.copy(backgroundColor = value) }

    fun setSubtitlePosition(value: SubtitlePositionPreset) = editAppearance { it.copy(position = value) }

    fun resetSubtitleAppearance() {
        viewModelScope.launch {
            playerSettingsStore.setSubtitleAppearance(SubtitleAppearance.DEFAULT)
        }
    }

    /** Toggle the device-level subtitle-appearance override (Custom Appearance). */
    fun setSubtitleDeviceOverrideEnabled(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setSubtitleDeviceOverrideEnabled(enabled) }
    }

    fun onAutoPlayNextChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoPlayNext(value) }
    }

    fun onMatchContentFrameRateChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setMatchContentFrameRate(value) }
    }

    /** tvOS navPrefs.showAudiobooks parity — opt-in Audiobooks top-menu tab. */
    fun onShowAudiobooksTabChanged(value: Boolean) {
        _uiState.update { it.copy(showAudiobooksTab = value) }
        viewModelScope.launch {
            runCatching { tvLibraryScopeStore?.setShowAudiobooksTab(value) }
        }
    }

    fun onSubtitleMatchesDeviceChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setSubtitleMatchesDevice(value) }
    }

    fun onDolbyVisionEnabledChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDolbyVisionEnabled(value) }
    }

    fun onDvProfile7HDR10FallbackChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDvProfile7HDR10Fallback(value) }
    }

    fun onIntroSkipModeChanged(value: IntroSkipMode) {
        viewModelScope.launch { playerSettingsStore.setIntroSkipMode(value) }
    }

    fun onAutoSkipCreditsChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipCredits(value) }
    }

    fun onResumeRewindSecondsChanged(value: Int) {
        viewModelScope.launch { playerSettingsStore.setResumeRewindSeconds(value) }
    }

    fun onPassOutThresholdChanged(value: Int) {
        viewModelScope.launch { playerSettingsStore.setPassOutThreshold(value) }
    }

    fun onNextUpPromptSecondsChanged(value: Int) {
        viewModelScope.launch { playerSettingsStore.setNextUpPromptSeconds(value) }
    }

    /**
     * Clear every server-side device override for this device. Mirrors
     * iOS tvOS "Reset Playback Overrides" (TVSettingsView.swift:137).
     */
    fun resetPlaybackOverrides() {
        viewModelScope.launch { playerSettingsStore.resetAllDeviceSettings() }
    }

    /** Lifecycle hook — call from MainTvActivity.onStop. */
    fun flushPendingSettings() {
        viewModelScope.launch { playerSettingsStore.flushPendingDeviceSettings() }
    }

    fun onSignOut(context: Context) {
        viewModelScope.launch {
            playerSettingsStore.flushPendingDeviceSettings()
            authRepository.logout()
            profileRepository.clearProfile()
            tokenManager.clearTokens()
            // Drop per-profile cached prefs so the next user doesn't see
            // them flash before the fresh fetch lands. iOS parity:
            // `PlaybackPrefsStore.clear()` in the sign-out path.
            libraryPlaybackPrefsStore.clear()
            overlayPrefsStore.clear()
            cardPresentationStore.clear()
            _uiState.update { it.copy(navAction = NavAction.SIGNED_OUT) }
        }
    }

    fun onSwitchProfile() {
        viewModelScope.launch {
            playerSettingsStore.flushPendingDeviceSettings()
            _uiState.update { it.copy(navAction = NavAction.SWITCH_PROFILE) }
        }
    }

    fun onNavActionConsumed() {
        _uiState.update { it.copy(navAction = null) }
    }

    private fun SubtitleSize.toFontSizePreset(): SubtitleFontSizePreset = when (this) {
        SubtitleSize.Small -> SubtitleFontSizePreset.Small
        SubtitleSize.Medium -> SubtitleFontSizePreset.Medium
        SubtitleSize.Large -> SubtitleFontSizePreset.Large
    }

    private fun SubtitleFontSizePreset.toTvSubtitleSize(): SubtitleSize = when (this) {
        SubtitleFontSizePreset.Small -> SubtitleSize.Small
        SubtitleFontSizePreset.Medium -> SubtitleSize.Medium
        // Large / XLarge / XXLarge — collapse anything bigger than Medium
        // back onto Large in the TV picker (the TV UI only exposes 3 sizes).
        SubtitleFontSizePreset.Large,
        SubtitleFontSizePreset.XLarge,
        SubtitleFontSizePreset.XXLarge -> SubtitleSize.Large
    }

    private data class Snapshot(
        val quality: String,
        val maxBitrateKbps: Int?,
        val autoPlay: Boolean,
        val skipIntro: IntroSkipMode,
        val skipCredits: Boolean,
        val appearance: SubtitleAppearance,
        val audioLanguage: String,
        val resumeRewindSeconds: Int,
        val passOutThreshold: Int,
    )

    private companion object {
        // Retry the user load a few times before surfacing an error, so a
        // flaky fetch doesn't blank the account header.
        const val UserLoadMaxAttempts = 3

        /** Gap between profile lookups while the profile is unresolved. */
        const val ProfileResolveRetryMs = 400L
        const val UserLoadRetryDelayMs = 400L
    }
}
