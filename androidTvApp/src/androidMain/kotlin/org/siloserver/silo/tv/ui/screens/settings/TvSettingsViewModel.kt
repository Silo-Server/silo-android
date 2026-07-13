package org.siloserver.silo.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.admin.shouldShowClientAdminSurface
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.auth.isActingAdmin
import org.siloserver.silo.model.profile.UpdateProfileRequest
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
import org.siloserver.silo.tv.data.preferences.PlaybackQuality
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
 * `PlayerSettings.shared`); profile-level subtitle prefs still go via
 * [profileRepository]. [LegacyTvPrefsMigration] runs the one-time legacy
 * `tv_prefs` → server import on first boot (sentinel-gated no-op after).
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
    private val legacyTvPrefsMigration: LegacyTvPrefsMigration,
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
        val playbackQuality: PlaybackQuality = PlaybackQuality.Auto,
        val subtitleMode: SubtitleMode = SubtitleMode.Auto,
        val subtitleLanguage: String = "",
        // Metadata AI: preferred description/metadata language ("" = server default).
        val metadataLanguage: String = "",
        val audioLanguage: String = "",
        val subtitleSize: SubtitleSize = SubtitleSize.Medium,
        val showForcedSubtitles: Boolean = true,
        // Full subtitle appearance + whether the device-scoped override is on.
        // Mirrors iOS `subtitleAppearance` / `subtitleUsesDeviceAppearanceOverride`.
        val subtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,
        val effectiveSubtitleAppearance: SubtitleAppearance = SubtitleAppearance.DEFAULT,
        val subtitleUsesDeviceOverride: Boolean = false,
        val autoPlayNext: Boolean = true,
        val autoSkipIntro: Boolean = false,
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
        // Client admin is hidden for now even when the server would accept acting-admin.
        val adminVisible: Boolean = false,
        val navAction: NavAction? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadUser()
        loadSettings()
        observePlayerSettings()
    }

    /**
     * Loads the current user (and derives [UiState.adminVisible]). A transient
     * failure here would silently drop the Admin dashboard entry for an acting
     * admin — the fetch is what gates admin visibility — so retry a few times
     * with a short backoff before surfacing [UiState.userError]. Only the final
     * attempt's failure is reported; a flaky load recovers and keeps the Admin
     * entry. Exposed publicly so a screen-level retry can also call it.
     */
    fun loadUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(userLoading = true, userError = null) }
            repeat(UserLoadMaxAttempts) { attempt ->
                val isLastAttempt = attempt == UserLoadMaxAttempts - 1
                when (val r = authRepository.getCurrentUser()) {
                    is ApiResult.Success -> {
                        val profile = profileRepository.getActiveProfile()
                        _uiState.update {
                            it.copy(
                                user = r.data,
                                userLoading = false,
                                userError = null,
                                profileName = profile?.name,
                                profileAvatar = profile?.avatar,
                                adminVisible = shouldShowClientAdminSurface(isActingAdmin(r.data, profile)),
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

            when (val profileResult = profileRepository.getActiveProfileResult()) {
                is ApiResult.Success -> {
                    val profile = profileResult.data
                    _uiState.update {
                        it.copy(
                            subtitleMode = SubtitleMode.fromWire(profile.subtitleMode),
                            subtitleLanguage = profile.subtitleLanguage.orEmpty(),
                            metadataLanguage = profile.preferredMetadataLanguage.orEmpty(),
                            showForcedSubtitles = profile.showForcedSubtitles ?: true,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> Unit
            }
        }
    }

    /**
     * Mirror device-scoped flows into UI state. The store is the single
     * source of truth — this just projects to the TV-specific UI types
     * (PlaybackQuality, SubtitleSize).
     */
    private fun observePlayerSettings() {
        viewModelScope.launch {
            combine(
                playerSettingsStore.preferredQualityFlow,
                playerSettingsStore.autoPlayNextFlow,
                playerSettingsStore.autoSkipIntroFlow,
                playerSettingsStore.autoSkipCreditsFlow,
                playerSettingsStore.savedCustomSubtitleAppearanceFlow,
                playerSettingsStore.audioLanguageFlow,
                playerSettingsStore.resumeRewindSecondsFlow,
                playerSettingsStore.passOutThresholdFlow,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val quality = values[0] as String
                @Suppress("UNCHECKED_CAST")
                val autoPlay = values[1] as Boolean
                @Suppress("UNCHECKED_CAST")
                val skipIntro = values[2] as Boolean
                @Suppress("UNCHECKED_CAST")
                val skipCredits = values[3] as Boolean
                @Suppress("UNCHECKED_CAST")
                val appearance = values[4] as SubtitleAppearance
                @Suppress("UNCHECKED_CAST")
                val audioLang = values[5] as String
                val rewind = values[6] as Int
                val threshold = values[7] as Int
                Snapshot(quality, autoPlay, skipIntro, skipCredits, appearance, audioLang, rewind, threshold)
            }.collect { snap ->
                _uiState.update {
                    it.copy(
                        playbackQuality = PlaybackQuality.fromWire(snap.quality),
                        autoPlayNext = snap.autoPlay,
                        autoSkipIntro = snap.skipIntro,
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

    fun onPlaybackQualityChanged(value: PlaybackQuality) {
        viewModelScope.launch { playerSettingsStore.setPreferredQuality(value.wireValue) }
    }

    fun onSubtitleModeChanged(value: SubtitleMode) {
        val previousState = _uiState.value
        _uiState.update { it.copy(subtitleMode = value) }
        persistProfileSubtitleSettings(previousState)
    }

    fun onMetadataLanguageChanged(value: String) {
        val previous = _uiState.value.metadataLanguage
        _uiState.update { it.copy(metadataLanguage = value) }
        viewModelScope.launch {
            when (
                profileRepository.updateActiveProfile(
                    UpdateProfileRequest(preferredMetadataLanguage = value.ifBlank { null })
                )
            ) {
                is ApiResult.Success -> Unit
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { current ->
                        if (current.metadataLanguage == value) current.copy(metadataLanguage = previous) else current
                    }
                }
            }
        }
    }

    fun onSubtitleLanguageChanged(value: String) {
        val previousState = _uiState.value
        _uiState.update { it.copy(subtitleLanguage = value) }
        persistProfileSubtitleSettings(previousState)
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
        val previousState = _uiState.value
        _uiState.update { it.copy(showForcedSubtitles = enabled) }
        persistProfileSubtitleSettings(previousState)
    }

    fun onSubtitleSizeChanged(value: SubtitleSize) {
        viewModelScope.launch {
            val current = playerSettingsStore.subtitleAppearanceFlow.first()
            val updated = current.copy(fontSize = value.toFontSizePreset())
            playerSettingsStore.setSubtitleAppearance(updated)
        }
    }

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

    fun onAutoSkipIntroChanged(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipIntro(value) }
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

    private fun persistProfileSubtitleSettings(previousState: UiState) {
        val state = _uiState.value
        viewModelScope.launch {
            when (
                profileRepository.updateActiveProfile(
                    UpdateProfileRequest(
                        subtitleLanguage = state.subtitleLanguage.ifBlank { null },
                        subtitleMode = state.subtitleMode.wireValue,
                        showForcedSubtitles = state.showForcedSubtitles,
                    )
                )
            ) {
                is ApiResult.Success -> Unit
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { current ->
                        if (
                            current.subtitleLanguage == state.subtitleLanguage &&
                            current.subtitleMode == state.subtitleMode &&
                            current.showForcedSubtitles == state.showForcedSubtitles
                        ) {
                            current.copy(
                                subtitleLanguage = previousState.subtitleLanguage,
                                subtitleMode = previousState.subtitleMode,
                                showForcedSubtitles = previousState.showForcedSubtitles,
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
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
        val autoPlay: Boolean,
        val skipIntro: Boolean,
        val skipCredits: Boolean,
        val appearance: SubtitleAppearance,
        val audioLanguage: String,
        val resumeRewindSeconds: Int,
        val passOutThreshold: Int,
    )

    private companion object {
        // Retry the user load a few times before surfacing an error, so a
        // flaky fetch doesn't silently strip the Admin entry from an admin.
        const val UserLoadMaxAttempts = 3
        const val UserLoadRetryDelayMs = 400L
    }
}
