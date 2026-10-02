package org.siloserver.silo.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.profile.ActiveProfileStore
import org.siloserver.silo.android.auth.SignOutTeardown
import org.siloserver.silo.common.settings.CardPresentationSource
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.CardPresentationUiState
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.SeekIntervalSettingsModel
import org.siloserver.silo.common.settings.SeekIntervalStore
import org.siloserver.silo.common.player.AudiobookSettingsStore
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.effectiveDefault
import org.siloserver.silo.model.download.labelFor
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.settings.CardCaption
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.CardPresentationPreset
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.NotificationsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Subtitle display mode. [wire] is the `playback.subtitle_mode` enum member
 * the settings contract declares — the labels are display only.
 */
enum class SubtitleMode(val label: String, val wire: String) {
    OFF("Off", "off"),
    AUTO("Auto", "auto"),
    ALWAYS("Always", "always");

    companion object {
        fun fromWire(value: String?): SubtitleMode =
            entries.firstOrNull { it.wire == value?.lowercase() } ?: AUTO
    }
}

data class SettingsUiState(
    // Account
    val user: User? = null,
    val serverUrl: String = "",
    // The account card shows the active profile's name and avatar, and the
    // Server row the server's display name, as the Apple apps do.
    val activeProfile: Profile? = null,
    val serverName: String = "",
    val isLoadingUser: Boolean = false,
    val loggedOut: Boolean = false,

    // Whether the canonical settings probe succeeded. Playback keeps working
    // from the local defaults either way.
    val settingsAvailability: ProfileSettingsController.Availability =
        ProfileSettingsController.Availability.UNKNOWN,

    // Playback
    // The quality picker composes playback.preferred_quality (a resolution
    // cap) and playback.max_bitrate_kbps (a bandwidth cap; null = uncapped)
    // into one list. The compound legacy spellings are dead and never written.
    val qualityResolution: String = QualityPresets.RESOLUTION_AUTO,
    val maxBitrateKbps: Int? = null,
    /** True when policy capped the resolution below the profile's choice. */
    val qualityConstrained: Boolean = false,
    // BCP 47 tag, "" = no preference. The picker converts to and from labels.
    val audioLanguage: String = "",
    val audioLanguageSuggestions: List<String> = emptyList(),
    val introSkipMode: IntroSkipMode = IntroSkipMode.Default,
    val autoSkipCredits: Boolean = false,
    val pictureInPictureEnabled: Boolean = true,
    val dolbyVisionEnabled: Boolean = true,
    val dvProfile7HDR10Fallback: Boolean = true,
    val subtitleMatchesDevice: Boolean = false,
    val showAudiobooks: Boolean = false,
    val subtitleAppearance: org.siloserver.silo.model.settings.SubtitleAppearance =
        org.siloserver.silo.model.settings.SubtitleAppearance.DEFAULT,
    /**
     * What playback draws: [subtitleAppearance], or the device caption style
     * while Use Device Settings is on. The preview shows this; the editors
     * edit [subtitleAppearance].
     */
    val effectiveSubtitleAppearance: org.siloserver.silo.model.settings.SubtitleAppearance =
        org.siloserver.silo.model.settings.SubtitleAppearance.DEFAULT,
    /** False when the server is known to discard subtitle text opacity. */
    val subtitleTextOpacitySupported: Boolean = true,
    // Up Next card: auto-play the next episode at countdown expiry, and how
    // many seconds before the end to surface the card (0 = only at end).
    val autoPlayNext: Boolean = true,
    val nextUpPromptSeconds: Int = 30,
    // Seconds to skip back on resume (0 = off); consecutive auto-advances
    // before the "Still watching?" prompt (0 = off).
    val resumeRewindSeconds: Int = 7,
    val passOutThreshold: Int = 3,

    // Downloads
    val downloadsWifiOnly: Boolean = true,
    val keepWatchedDownloads: Boolean = false,
    val defaultDownloadQuality: String = DownloadQuality.Original.label,
    // The presets this account may request, labelled with the server's
    // resolution ceiling once the download capability has loaded.
    val downloadQualityOptions: List<String> = DownloadQuality.entries.map { it.label },

    // Subtitles
    // BCP 47 tag, "" = off. The picker converts to and from labels.
    val subtitleLanguage: String = "",
    val subtitleLanguageSuggestions: List<String> = emptyList(),
    // Metadata AI: preferred description/metadata language.
    // ISO 639-1 code; "" = inherit library metadata language.
    val metadataLanguage: String = "",
    val metadataLanguageSuggestions: List<String> = emptyList(),
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val showForcedSubtitles: Boolean = true,

    // Media cards: the effective `ui.card_presentation` value plus where it
    // resolved from and whether the server supports the key at all.
    val cardPresentation: CardPresentationUiState = CardPresentationUiState(),

    // Notifications (in-app). Section is hidden entirely unless the server
    // reports in-app notifications are enabled AND preferences load.
    val notificationsAvailable: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notifyFavorites: Boolean = true,
    val notifyWatchlist: Boolean = true,
    val notifyContinueWatching: Boolean = true,
    val notifyNextUp: Boolean = true,
)

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val activeProfileStore: ActiveProfileStore,
    private val notificationsRepository: NotificationsRepository,
    private val profileSettings: ProfileSettingsController,
    private val cardPresentationStore: CardPresentationStore,
    private val seekIntervalStore: SeekIntervalStore,
    private val signOutTeardown: SignOutTeardown,
    audiobookSettingsStore: AudiobookSettingsStore,
    private val downloadsRepository: DownloadsRepository? = null,
    private val serverRegistry: ServerRegistry? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var subtitleLanguageEditGeneration = 0L
    private var subtitleModeEditGeneration = 0L
    private var forcedSubtitlesEditGeneration = 0L
    private var subtitleLanguageConfirmedGeneration = 0L
    private var subtitleModeConfirmedGeneration = 0L
    private var forcedSubtitlesConfirmedGeneration = 0L

    /** Profile-wide video and audiobook skip intervals (settings revision 9). */
    val seekIntervals = SeekIntervalSettingsModel(seekIntervalStore, audiobookSettingsStore, viewModelScope)

    init {
        loadUserInfo()
        observeAccountCard()
        observePlayerSettings()
        observePlaybackBehaviorSettings()
        observeNotifications()
        observeCardPresentation()
        // Opening Settings is a refresh edge for the seek-interval support probe.
        seekIntervals.refresh()
    }

    private fun observeAccountCard() {
        activeProfileStore.activeProfile.onEach { profile ->
            _uiState.update { it.copy(activeProfile = profile) }
        }.launchIn(viewModelScope)
        serverRegistry?.activeEntry?.onEach { entry ->
            _uiState.update { it.copy(serverName = entry?.displayName.orEmpty()) }
        }?.launchIn(viewModelScope)
        // Cached after the first fetch, so this is cheap on every later visit.
        viewModelScope.launch { activeProfileStore.refresh() }
    }

    private fun loadUserInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingUser = true) }
            when (val result = authRepository.getCurrentUser()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(user = result.data, isLoadingUser = false) }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingUser = false) }
                }
            }

            val serverUrl = authRepository.getServerUrl()
            _uiState.update { it.copy(serverUrl = serverUrl) }

            playerSettingsStore.refreshFromServer()

            // The active profile is no longer resolved here. It existed only to
            // decide the admin gate, which this screen no longer has; the
            // profile-scoped *preferences* are resolved canonically below.
            loadProfileSettings()
        }
    }

    /**
     * Resolves the profile-scoped preferences through the canonical settings
     * API. When the probe fails the values are left as they are. Playback is
     * unaffected: it runs from the device-scoped store, which has its own
     * defaults.
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
                    subtitleLanguage = snapshot.subtitleLanguage,
                    subtitleMode = SubtitleMode.fromWire(snapshot.subtitleMode),
                    showForcedSubtitles = snapshot.showForcedSubtitles,
                    metadataLanguage = snapshot.metadataLanguage,
                    audioLanguageSuggestions = snapshot.audioLanguageSuggestions,
                    subtitleLanguageSuggestions = snapshot.subtitleLanguageSuggestions,
                    metadataLanguageSuggestions = snapshot.metadataLanguageSuggestions,
                )
            }
        }
    }

    private data class PlayerSettingsSnapshot(
        val quality: String,
        val maxBitrateKbps: Int?,
        val audioLanguage: String,
        val introSkipMode: IntroSkipMode,
        val autoSkipCredits: Boolean,
    )

    private fun observePlayerSettings() {
        combine(
            playerSettingsStore.preferredQualityFlow,
            playerSettingsStore.maxBitrateKbpsFlow,
            playerSettingsStore.audioLanguageFlow,
            playerSettingsStore.introSkipModeFlow,
            playerSettingsStore.autoSkipCreditsFlow,
            ::PlayerSettingsSnapshot,
        ).onEach { snap ->
            _uiState.update {
                it.copy(
                    qualityResolution = snap.quality,
                    maxBitrateKbps = snap.maxBitrateKbps,
                    audioLanguage = snap.audioLanguage,
                    introSkipMode = snap.introSkipMode,
                    autoSkipCredits = snap.autoSkipCredits,
                )
            }
        }.launchIn(viewModelScope)
        playerSettingsStore.downloadsWifiOnlyFlow.onEach { wifiOnly ->
            _uiState.update { it.copy(downloadsWifiOnly = wifiOnly) }
        }.launchIn(viewModelScope)
        playerSettingsStore.keepWatchedDownloadsFlow.onEach { keepWatched ->
            _uiState.update { it.copy(keepWatchedDownloads = keepWatched) }
        }.launchIn(viewModelScope)
        val downloadCapability = downloadsRepository?.capability ?: MutableStateFlow(null)
        combine(playerSettingsStore.defaultDownloadQualityFlow, downloadCapability) { quality, capability ->
            val offered = capability?.allowedQualities() ?: DownloadQuality.entries
            _uiState.update {
                it.copy(
                    // The preset new downloads will actually use.
                    defaultDownloadQuality = capability.labelFor(capability.effectiveDefault(DownloadQuality.fromWire(quality))),
                    downloadQualityOptions = offered.map { preset -> capability.labelFor(preset) },
                )
            }
        }.launchIn(viewModelScope)
        // Opening Settings refreshes the capability, as the detail screen does,
        // so the labels reflect the server's current download settings.
        downloadsRepository?.let { repository -> viewModelScope.launch { repository.refreshCapability() } }
        playerSettingsStore.pictureInPictureEnabledFlow.onEach { enabled ->
            _uiState.update { it.copy(pictureInPictureEnabled = enabled) }
        }.launchIn(viewModelScope)

        playerSettingsStore.dolbyVisionEnabledFlow.onEach { enabled ->
            _uiState.update { it.copy(dolbyVisionEnabled = enabled) }
        }.launchIn(viewModelScope)
        playerSettingsStore.dvProfile7HDR10FallbackFlow.onEach { enabled ->
            _uiState.update { it.copy(dvProfile7HDR10Fallback = enabled) }
        }.launchIn(viewModelScope)
        playerSettingsStore.subtitleMatchesDeviceFlow.onEach { enabled ->
            _uiState.update { it.copy(subtitleMatchesDevice = enabled) }
        }.launchIn(viewModelScope)
        playerSettingsStore.showAudiobooksFlow.onEach { enabled ->
            _uiState.update { it.copy(showAudiobooks = enabled) }
        }.launchIn(viewModelScope)
        playerSettingsStore.subtitleAppearanceFlow.onEach { appearance ->
            _uiState.update { it.copy(subtitleAppearance = appearance) }
        }.launchIn(viewModelScope)
        playerSettingsStore.effectiveSubtitleAppearanceFlow.onEach { appearance ->
            _uiState.update { it.copy(effectiveSubtitleAppearance = appearance) }
        }.launchIn(viewModelScope)
        playerSettingsStore.subtitleTextOpacitySupportedFlow.onEach { supported ->
            _uiState.update { it.copy(subtitleTextOpacitySupported = supported) }
        }.launchIn(viewModelScope)    }

    fun setDownloadsWifiOnly(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDownloadsWifiOnly(value) }
    }

    fun setKeepWatchedDownloads(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setKeepWatchedDownloads(value) }
    }

    fun setDefaultDownloadQuality(value: String) {
        viewModelScope.launch {
            playerSettingsStore.setDefaultDownloadQuality(downloadQualityWireValue(value))
        }
    }

    // Separate from observePlayerSettings() because combine() has no typed
    // overload past 5 flows — these behavior settings get their own.
    private fun observePlaybackBehaviorSettings() {
        combine(
            playerSettingsStore.resumeRewindSecondsFlow,
            playerSettingsStore.passOutThresholdFlow,
            playerSettingsStore.autoPlayNextFlow,
            playerSettingsStore.nextUpPromptSecondsFlow,
        ) { rewind, threshold, autoPlayNext, nextUpPrompt ->
            _uiState.update {
                it.copy(
                    resumeRewindSeconds = rewind,
                    passOutThreshold = threshold,
                    autoPlayNext = autoPlayNext,
                    nextUpPromptSeconds = nextUpPrompt,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun setResumeRewindSeconds(value: Int) {
        viewModelScope.launch { playerSettingsStore.setResumeRewindSeconds(value) }
    }

    fun setAutoPlayNext(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoPlayNext(value) }
    }

    fun setNextUpPromptSeconds(value: Int) {
        viewModelScope.launch { playerSettingsStore.setNextUpPromptSeconds(value) }
    }

    fun setPassOutThreshold(value: Int) {
        viewModelScope.launch { playerSettingsStore.setPassOutThreshold(value) }
    }

    // -- Notifications (in-app) --

    /**
     * Folds capability + preferences into UI state. The section is gated on the
     * server reporting in-app notifications enabled AND preferences having
     * loaded — a failed fetch leaves both null, so the section stays hidden and
     * no toggles (least of all push) ever render. Refresh runs on init.
     */
    private fun observeNotifications() {
        combine(
            notificationsRepository.capability,
            notificationsRepository.preferences,
        ) { capability, preferences ->
            val available = capability?.inApp?.enabled == true
            _uiState.update { state ->
                if (!available || preferences == null) {
                    state.copy(notificationsAvailable = false)
                } else {
                    state.copy(
                        notificationsAvailable = true,
                        notificationsEnabled = preferences.enabled,
                        notifyFavorites = preferences.notifyFavorites,
                        notifyWatchlist = preferences.notifyWatchlist,
                        notifyContinueWatching = preferences.notifyContinueWatching,
                        notifyNextUp = preferences.notifyNextUp,
                    )
                }
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch { notificationsRepository.loadCapability() }
        viewModelScope.launch { notificationsRepository.loadPreferences() }
    }

    // -- Media cards --

    private fun observeCardPresentation() {
        cardPresentationStore.state.onEach { state ->
            _uiState.update { it.copy(cardPresentation = state) }
        }.launchIn(viewModelScope)
        // The provider above the nav graph hydrates too, but this screen can
        // be reached before any card rendered — make hydration unconditional.
        viewModelScope.launch { cardPresentationStore.hydrateIfNeeded() }
    }

    fun setCardPreset(preset: CardPresentationPreset) {
        setCardPresentation(preset.presentation)
    }

    fun setCardPosterSize(size: CardPosterSize) {
        setCardPresentation(
            _uiState.value.cardPresentation.presentation.copy(posterSize = size),
        )
    }

    fun setCardCaption(caption: CardCaption) {
        setCardPresentation(
            _uiState.value.cardPresentation.presentation.copy(caption = caption),
        )
    }

    /**
     * Optimistic write through the store — at `profile_device` while the
     * device override is active, else at `profile_client` so the choice roams
     * among this profile's like devices (Apple/web parity).
     */
    private fun setCardPresentation(presentation: CardPresentation) {
        val deviceOnly =
            _uiState.value.cardPresentation.source == CardPresentationSource.DeviceOverride
        cardPresentationStore.set(presentation, deviceOnly = deviceOnly)
    }

    /**
     * "Only this device": ON pins the current presentation at
     * `profile_device`; OFF deletes that row so resolution falls back to the
     * client-family value.
     */
    fun setCardDeviceOnly(enabled: Boolean) {
        if (enabled) {
            cardPresentationStore.set(
                _uiState.value.cardPresentation.presentation,
                deviceOnly = true,
            )
        } else {
            viewModelScope.launch { cardPresentationStore.clearDeviceOverride() }
        }
    }

    /** Deletes the `profile_client` row so the profile-wide value applies. */
    fun useCardProfileDefault() {
        viewModelScope.launch { cardPresentationStore.useProfileDefault() }
    }

    fun setNotificationsEnabled(value: Boolean) {
        updateNotificationPreferences(NotificationPreferencesUpdate(enabled = value))
    }

    fun setNotifyFavorites(value: Boolean) {
        updateNotificationPreferences(NotificationPreferencesUpdate(notifyFavorites = value))
    }

    fun setNotifyWatchlist(value: Boolean) {
        updateNotificationPreferences(NotificationPreferencesUpdate(notifyWatchlist = value))
    }

    fun setNotifyContinueWatching(value: Boolean) {
        updateNotificationPreferences(NotificationPreferencesUpdate(notifyContinueWatching = value))
    }

    fun setNotifyNextUp(value: Boolean) {
        updateNotificationPreferences(NotificationPreferencesUpdate(notifyNextUp = value))
    }

    /**
     * Sends a partial PUT (one named field) and lets the repository's
     * preferences flow drive the UI back to the server's truth.
     */
    private fun updateNotificationPreferences(update: NotificationPreferencesUpdate) {
        viewModelScope.launch { notificationsRepository.updatePreferences(update) }
    }

    fun logout() {
        viewModelScope.launch {
            // Pushes in-flight settings, then drops per-profile cached prefs so
            // the next user doesn't see stale rows flash before the fresh fetch.
            signOutTeardown.signOut()
            _uiState.update { it.copy(loggedOut = true) }
        }
    }

    fun onLogoutConsumed() {
        _uiState.update { it.copy(loggedOut = false) }
    }

    // -- Playback --

    /**
     * Applies one quality preset — the two axes it decomposes into. The
     * compound legacy spellings ("1080p-high") are never written.
     */
    fun setQualityPreset(presetId: String) {
        val preset = QualityPresets.byId(presetId) ?: return
        viewModelScope.launch {
            playerSettingsStore.setQuality(preset.resolution, preset.bitrateKbps)
        }
    }

    /** [language] is a BCP 47 tag, or "" for no preference. */
    fun setAudioLanguage(language: String) {
        viewModelScope.launch {
            playerSettingsStore.setAudioLanguage(language)
        }
    }

    fun setIntroSkipMode(mode: IntroSkipMode) {
        viewModelScope.launch { playerSettingsStore.setIntroSkipMode(mode) }
    }

    fun setAutoSkipCredits(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipCredits(enabled) }
    }

    fun setPictureInPictureEnabled(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setPictureInPictureEnabled(enabled) }
    }

    fun setDolbyVisionEnabled(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDolbyVisionEnabled(enabled) }
    }

    fun setDvProfile7HDR10Fallback(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDvProfile7HDR10Fallback(enabled) }
    }

    fun setSubtitleMatchesDevice(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setSubtitleMatchesDevice(enabled) }
    }

    fun setShowAudiobooks(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setShowAudiobooks(enabled) }
    }

    /**
     * Commits a subtitle-appearance change via a transform rather than a
     * precomputed value (replaced the former `setSubtitleAppearance`).
     * [PlayerSettingsStore.updateSubtitleAppearance][org.siloserver.silo.common.settings.PlayerSettingsStore.updateSubtitleAppearance]
     * applies it atomically inside the store's own write transaction, so two
     * edits committing around the same time (e.g. two opacity fields as the
     * sheet is dismissed) can't race on a snapshot read before either writes.
     */
    fun editSubtitleAppearance(
        transform: (org.siloserver.silo.model.settings.SubtitleAppearance) -> org.siloserver.silo.model.settings.SubtitleAppearance,
    ) {
        viewModelScope.launch {
            playerSettingsStore.updateSubtitleAppearance(transform)
            playerSettingsStore.flushProjectedSubtitleAppearance()
        }
    }

    fun resetPlaybackOverrides() {
        viewModelScope.launch { playerSettingsStore.resetAllDeviceSettings() }
    }

    /** Lifecycle hook — call from ON_STOP so debounced writes survive. */
    fun flushPendingSettings() {
        viewModelScope.launch { playerSettingsStore.flushPendingDeviceSettings() }
    }

    // -- Subtitles --

    // These four are profile-scoped canonical settings. They used to ride
    // named columns on PUT /profiles/{id}; each now writes exactly the one key
    // it changes at scope=profile, so a failed write cannot also revert the
    // other three (which sending the whole triple every time did).
    //
    // Each applies optimistically and rolls back only if the state still shows
    // the value it wrote — a newer edit landing during the request wins.

    fun setMetadataLanguage(code: String) {
        val previous = _uiState.value.metadataLanguage
        _uiState.update { it.copy(metadataLanguage = code) }
        viewModelScope.launch {
            val result = profileSettings.setMetadataLanguage(code)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.metadataLanguage == code) it.copy(metadataLanguage = previous) else it
                }
            } else {
                applyResolved(result.snapshot, edited = code) { it.metadataLanguage }
            }
        }
    }

    // The subtitle setters below also store each write's confirmed value in
    // the cached profile, which offline playback reads for its subtitle
    // preferences. Only a newer successful write to the same field supersedes
    // a confirmed value; pending or failed edits leave that value available.

    /** [language] is a BCP 47 tag, or "" for off. */
    fun setSubtitleLanguage(language: String) {
        val editGeneration = ++subtitleLanguageEditGeneration
        val previous = _uiState.value.subtitleLanguage
        _uiState.update { it.copy(subtitleLanguage = language) }
        viewModelScope.launch {
            val result = profileSettings.setSubtitleLanguage(language)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.subtitleLanguage == language) it.copy(subtitleLanguage = previous) else it
                }
            } else {
                if (editGeneration > subtitleLanguageConfirmedGeneration) {
                    applyResolved(result.snapshot, edited = language) { it.subtitleLanguage }
                    subtitleLanguageConfirmedGeneration = editGeneration
                    val confirmed = result.snapshot?.subtitleLanguage ?: language
                    activeProfileStore.update { it.copy(subtitleLanguage = confirmed) }
                }
            }
        }
    }

    fun setSubtitleMode(mode: SubtitleMode) {
        val editGeneration = ++subtitleModeEditGeneration
        val previous = _uiState.value.subtitleMode
        _uiState.update { it.copy(subtitleMode = mode) }
        viewModelScope.launch {
            val result = profileSettings.setSubtitleMode(mode.wire)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.subtitleMode == mode) it.copy(subtitleMode = previous) else it
                }
            } else {
                if (editGeneration > subtitleModeConfirmedGeneration) {
                    applyResolved(result.snapshot, edited = mode.wire) { it.subtitleMode }
                    subtitleModeConfirmedGeneration = editGeneration
                    val confirmed = result.snapshot?.subtitleMode ?: mode.wire
                    activeProfileStore.update { it.copy(subtitleMode = confirmed) }
                }
            }
        }
    }

    fun setShowForcedSubtitles(enabled: Boolean) {
        val editGeneration = ++forcedSubtitlesEditGeneration
        val previous = _uiState.value.showForcedSubtitles
        _uiState.update { it.copy(showForcedSubtitles = enabled) }
        viewModelScope.launch {
            val result = profileSettings.setShowForcedSubtitles(enabled)
            if (!result.succeeded) {
                _uiState.update {
                    if (it.showForcedSubtitles == enabled) it.copy(showForcedSubtitles = previous) else it
                }
            } else {
                if (editGeneration > forcedSubtitlesConfirmedGeneration) {
                    applyResolved(result.snapshot, edited = enabled.toString()) {
                        it.showForcedSubtitles.toString()
                    }
                    forcedSubtitlesConfirmedGeneration = editGeneration
                    val confirmed = result.snapshot?.showForcedSubtitles ?: enabled
                    activeProfileStore.update { it.copy(showForcedSubtitles = confirmed) }
                }
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
     * edit for the *same* field landed while the round trip was in flight
     * ([edited] no longer matches [fieldOf]), which the optimistic rollback
     * above guards the same way.
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
                subtitleLanguage = snapshot.subtitleLanguage,
                subtitleMode = SubtitleMode.fromWire(snapshot.subtitleMode),
                showForcedSubtitles = snapshot.showForcedSubtitles,
                metadataLanguage = snapshot.metadataLanguage,
                audioLanguageSuggestions = snapshot.audioLanguageSuggestions,
                subtitleLanguageSuggestions = snapshot.subtitleLanguageSuggestions,
                metadataLanguageSuggestions = snapshot.metadataLanguageSuggestions,
            )
        }
    }

    // A shown label is the preset's bitrate label plus an optional
    // " · up to …" suffix, so the bitrate part alone identifies the preset
    // even if the capability refreshed after the list was drawn.
    private fun downloadQualityWireValue(value: String): String =
        DownloadQuality.entries.firstOrNull { it.label == value.substringBefore(" · ") }?.wire
            ?: DownloadQuality.Original.wire
}
