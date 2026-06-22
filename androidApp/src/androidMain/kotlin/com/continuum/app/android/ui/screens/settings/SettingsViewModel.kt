package com.continuum.app.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.common.settings.LibraryPlaybackPrefsStore
import com.continuum.app.common.settings.OverlayPrefsStore
import com.continuum.app.common.settings.PlayerSettingsStore
import com.continuum.app.model.admin.shouldShowClientAdminSurface
import com.continuum.app.model.auth.AuthSession
import com.continuum.app.model.auth.User
import com.continuum.app.model.auth.isActingAdmin
import com.continuum.app.model.notifications.NotificationPreferencesUpdate
import com.continuum.app.model.profile.UpdateProfileRequest
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.AuthRepository
import com.continuum.app.repository.NotificationsRepository
import com.continuum.app.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Subtitle display mode.
 */
enum class SubtitleMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ALWAYS("Always"),
}

data class SettingsUiState(
    // Account
    val user: User? = null,
    val serverUrl: String = "",
    val isLoadingUser: Boolean = false,
    val sessions: List<AuthSession> = emptyList(),
    val isLoadingSessions: Boolean = false,
    val showSessions: Boolean = false,
    val loggedOut: Boolean = false,
    // Client admin is hidden for now even when the server would accept acting-admin.
    val isAdminVisible: Boolean = false,

    // Playback
    val defaultQuality: String = "Auto",
    val audioLanguage: String = "Default",
    val autoSkipIntro: Boolean = false,
    val autoSkipCredits: Boolean = false,
    // Seconds to skip back on resume (0 = off); consecutive auto-advances
    // before the "Still watching?" prompt (0 = off).
    val resumeRewindSeconds: Int = 7,
    val passOutThreshold: Int = 3,

    // Downloads
    val downloadsWifiOnly: Boolean = true,

    // Subtitles
    val subtitleLanguage: String = "Off",
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val showForcedSubtitles: Boolean = true,

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
    private val profileRepository: ProfileRepository,
    private val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore,
    private val overlayPrefsStore: OverlayPrefsStore,
    private val notificationsRepository: NotificationsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadUserInfo()
        observePlayerSettings()
        observePlaybackBehaviorSettings()
        observeNotifications()
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

            when (val profileResult = profileRepository.getActiveProfileResult()) {
                is ApiResult.Success -> {
                    val profile = profileResult.data
                    _uiState.update {
                        it.copy(
                            subtitleLanguage = profile.subtitleLanguage?.ifBlank { "Off" } ?: "Off",
                            subtitleMode = subtitleModeFromServer(profile.subtitleMode),
                            showForcedSubtitles = profile.showForcedSubtitles ?: true,
                            isAdminVisible = shouldShowClientAdminSurface(isActingAdmin(it.user, profile)),
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    // Active profile unresolved — fall back to the user role
                    // only (a null profile does not block an admin per the gate).
                    _uiState.update {
                        it.copy(isAdminVisible = shouldShowClientAdminSurface(isActingAdmin(it.user, null)))
                    }
                }
            }
        }
    }

    private data class PlayerSettingsSnapshot(
        val quality: String,
        val audioLanguage: String,
        val autoSkipIntro: Boolean,
        val autoSkipCredits: Boolean,
        val downloadsWifiOnly: Boolean,
    )

    private fun observePlayerSettings() {
        combine(
            playerSettingsStore.preferredQualityFlow,
            playerSettingsStore.audioLanguageFlow,
            playerSettingsStore.autoSkipIntroFlow,
            playerSettingsStore.autoSkipCreditsFlow,
            playerSettingsStore.downloadsWifiOnlyFlow,
            ::PlayerSettingsSnapshot,
        ).onEach { snap ->
            _uiState.update {
                it.copy(
                    defaultQuality = qualityLabel(snap.quality),
                    audioLanguage = audioLanguageLabel(snap.audioLanguage),
                    autoSkipIntro = snap.autoSkipIntro,
                    autoSkipCredits = snap.autoSkipCredits,
                    downloadsWifiOnly = snap.downloadsWifiOnly,
                )
            }
        }.launchIn(viewModelScope)
    }

    fun setDownloadsWifiOnly(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDownloadsWifiOnly(value) }
    }

    // Separate from observePlayerSettings() because combine() has no typed
    // overload past 5 flows — these two local-only Int settings get their own.
    private fun observePlaybackBehaviorSettings() {
        combine(
            playerSettingsStore.resumeRewindSecondsFlow,
            playerSettingsStore.passOutThresholdFlow,
        ) { rewind, threshold -> rewind to threshold }
            .onEach { (rewind, threshold) ->
                _uiState.update { it.copy(resumeRewindSeconds = rewind, passOutThreshold = threshold) }
            }
            .launchIn(viewModelScope)
    }

    fun setResumeRewindSeconds(value: Int) {
        viewModelScope.launch { playerSettingsStore.setResumeRewindSeconds(value) }
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

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSessions = true, showSessions = true) }
            when (val result = authRepository.getSessions()) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(sessions = result.data, isLoadingSessions = false)
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingSessions = false) }
                }
            }
        }
    }

    fun hideSessions() {
        _uiState.update { it.copy(showSessions = false) }
    }

    fun revokeSession(id: String) {
        viewModelScope.launch {
            when (authRepository.deleteSession(id)) {
                is ApiResult.Success -> {
                    _uiState.update { state ->
                        state.copy(sessions = state.sessions.filter { it.id != id })
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> Unit
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Push any in-flight settings before tearing down the session.
            playerSettingsStore.flushPendingDeviceSettings()
            authRepository.logout()
            // Drop per-profile cached prefs so the next user doesn't see
            // stale rows flash before the fresh fetch lands.
            libraryPlaybackPrefsStore.clear()
            overlayPrefsStore.clear()
            _uiState.update { it.copy(loggedOut = true) }
        }
    }

    fun onLogoutConsumed() {
        _uiState.update { it.copy(loggedOut = false) }
    }

    // -- Playback --

    fun setDefaultQuality(quality: String) {
        viewModelScope.launch {
            playerSettingsStore.setPreferredQuality(qualityWireValue(quality))
        }
    }

    fun setAudioLanguage(language: String) {
        viewModelScope.launch {
            playerSettingsStore.setAudioLanguage(audioLanguageWireValue(language))
        }
    }

    fun setAutoSkipIntro(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipIntro(enabled) }
    }

    fun setAutoSkipCredits(enabled: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipCredits(enabled) }
    }

    fun resetPlaybackOverrides() {
        viewModelScope.launch { playerSettingsStore.resetAllDeviceSettings() }
    }

    /** Lifecycle hook — call from ON_STOP so debounced writes survive. */
    fun flushPendingSettings() {
        viewModelScope.launch { playerSettingsStore.flushPendingDeviceSettings() }
    }

    // -- Subtitles --

    fun setSubtitleLanguage(language: String) {
        _uiState.update { it.copy(subtitleLanguage = language) }
        persistProfileSubtitleSettings()
    }

    fun setSubtitleMode(mode: SubtitleMode) {
        _uiState.update { it.copy(subtitleMode = mode) }
        persistProfileSubtitleSettings()
    }

    fun setShowForcedSubtitles(enabled: Boolean) {
        _uiState.update { it.copy(showForcedSubtitles = enabled) }
        persistProfileSubtitleSettings()
    }

    private fun persistProfileSubtitleSettings() {
        val state = _uiState.value
        viewModelScope.launch {
            profileRepository.updateActiveProfile(
                UpdateProfileRequest(
                    subtitleLanguage = state.subtitleLanguage.takeUnless { it == "Off" },
                    subtitleMode = state.subtitleMode.toServerValue(),
                    showForcedSubtitles = state.showForcedSubtitles,
                )
            )
        }
    }

    private fun qualityLabel(value: String): String =
        when (value.lowercase()) {
            "auto" -> "Auto"
            "original" -> "Original"
            else -> value.uppercase()
        }

    private fun qualityWireValue(value: String): String =
        when (value) {
            "Auto" -> "auto"
            "Original" -> "original"
            else -> value.lowercase()
        }

    private fun audioLanguageLabel(value: String): String =
        value.ifBlank { "Default" }

    private fun audioLanguageWireValue(value: String): String =
        value.takeUnless { it == "Default" }.orEmpty()

    private fun subtitleModeFromServer(value: String?): SubtitleMode =
        when (value?.lowercase()) {
            "off" -> SubtitleMode.OFF
            "always" -> SubtitleMode.ALWAYS
            else -> SubtitleMode.AUTO
        }

    private fun SubtitleMode.toServerValue(): String =
        when (this) {
            SubtitleMode.OFF -> "off"
            SubtitleMode.AUTO -> "auto"
            SubtitleMode.ALWAYS -> "always"
        }
}
