package com.continuum.app.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.continuum.app.common.settings.OverlayPrefsStore
import com.continuum.app.model.feature.CLIENT_REQUESTS_SURFACE_ENABLED
import com.continuum.app.model.settings.SubtitleBackgroundStylePreset
import com.continuum.app.model.settings.SubtitleFontSizePreset
import com.continuum.app.model.settings.SubtitlePositionPreset
import com.continuum.app.tv.BuildConfig
import com.continuum.app.tv.data.preferences.PlaybackQuality
import com.continuum.app.tv.data.preferences.SubtitleMode
import com.continuum.app.tv.ui.screens.player.TvSubtitleAppearanceOptions
import com.continuum.app.tv.ui.shell.TvTopMenuLayout
import com.continuum.app.tv.ui.theme.FocusedContainer
import com.continuum.app.tv.ui.theme.FocusedContent
import com.continuum.app.tv.ui.theme.Spacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * TV Settings — a tvOS-style drill-in category menu (modeled on
 * `iosApp/.../tvOS/Screens/Settings/TVSettingsView.swift`).
 *
 * The root is a short list of categories rather than one wall of controls:
 *  - a tappable Account header (avatar + name + role/username) that switches
 *    profile,
 *  - a Preferences group of value rows (Playback, Subtitles, Card Overlays)
 *    that drill into dedicated sub-screens,
 *  - an Account-actions group (Admin Dashboard when gated, Sign Out with a
 *    confirm dialog, plus the Android-only Manage Sessions / Pair a Device),
 *  - an About group (Server name, URL, Manage Servers, app version from
 *    `BuildConfig.VERSION_NAME`).
 *
 * The Android-only extras the iOS root doesn't have (Notifications prefs,
 * Library shortcuts, Manage Sessions, Pair a Device) are relocated into
 * sensible groups rather than deleted.
 *
 * Sub-screens are presented as full-screen overlays (local `SubScreen`
 * state) instead of pushes — the TV NavHost owns the back stack and we want
 * Back to land on the root menu, matching the tvOS full-screen-cover model.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToBrowse: () -> Unit = {},
    onNavigateToRequests: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {},
    onManageSessions: () -> Unit = {},
    onPairDevice: () -> Unit = {},
    onManageServers: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onInitialContentFocus: () -> Unit = {},
    viewModel: TvSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val overlayPrefsStore: OverlayPrefsStore = koinInject()
    val firstActionFocusRequester = remember { FocusRequester() }

    var subScreen by remember { mutableStateOf<SubScreen?>(null) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { firstActionFocusRequester.requestFocus() }
        onInitialContentFocus()
    }

    LaunchedEffect(state.navAction) {
        when (state.navAction) {
            TvSettingsViewModel.NavAction.SIGNED_OUT -> {
                viewModel.onNavActionConsumed()
                onSignedOut()
            }
            TvSettingsViewModel.NavAction.SWITCH_PROFILE -> {
                viewModel.onNavActionConsumed()
                onSwitchProfile()
            }
            null -> Unit
        }
    }

    // Render the root only when no sub-screen is drilled in. The sub-screens
    // are full-screen; keeping the root composed underneath would leave its rows
    // in the focus tree, so focus could leak behind the overlay and fire hidden
    // root actions.
    if (subScreen == null) SettingsRootMenu(
        state = state,
        firstActionFocusRequester = firstActionFocusRequester,
        onSwitchProfile = { viewModel.onSwitchProfile(context) },
        onOpenPlayback = { subScreen = SubScreen.Playback },
        onOpenSubtitles = { subScreen = SubScreen.Subtitles },
        onOpenCardOverlays = { subScreen = SubScreen.CardOverlays },
        onNavigateToAdmin = onNavigateToAdmin,
        onManageSessions = onManageSessions,
        onPairDevice = onPairDevice,
        onManageServers = onManageServers,
        onRequestSignOut = { showSignOutConfirm = true },
        onNavigateToBrowse = onNavigateToBrowse,
        onNavigateToFavorites = onNavigateToFavorites,
        onNavigateToWatchlist = onNavigateToWatchlist,
        onNavigateToHistory = onNavigateToHistory,
        onNavigateToCollections = onNavigateToCollections,
        onNavigateToRequests = onNavigateToRequests,
        onNotificationsEnabledChanged = viewModel::onNotificationsEnabledChanged,
        onNotifyFavoritesChanged = viewModel::onNotifyFavoritesChanged,
        onNotifyWatchlistChanged = viewModel::onNotifyWatchlistChanged,
        onNotifyContinueWatchingChanged = viewModel::onNotifyContinueWatchingChanged,
        onNotifyNextUpChanged = viewModel::onNotifyNextUpChanged,
    )

    when (subScreen) {
        SubScreen.Playback -> TvPlaybackSettingsScreen(
            state = state,
            onQualityChanged = viewModel::onPlaybackQualityChanged,
            onAudioLanguageChanged = viewModel::onAudioLanguageChanged,
            onAutoPlayNextChanged = viewModel::onAutoPlayNextChanged,
            onAutoSkipIntroChanged = viewModel::onAutoSkipIntroChanged,
            onAutoSkipCreditsChanged = viewModel::onAutoSkipCreditsChanged,
            onResumeRewindSecondsChanged = viewModel::onResumeRewindSecondsChanged,
            onPassOutThresholdChanged = viewModel::onPassOutThresholdChanged,
            onNextUpPromptSecondsChanged = viewModel::onNextUpPromptSecondsChanged,
            onResetPlaybackOverrides = viewModel::resetPlaybackOverrides,
            onDismiss = { subScreen = null },
        )
        SubScreen.Subtitles -> TvSubtitleSettingsScreen(
            state = state,
            onSubtitleModeChanged = viewModel::onSubtitleModeChanged,
            onSubtitleLanguageChanged = viewModel::onSubtitleLanguageChanged,
            onShowForcedSubtitlesChanged = viewModel::onShowForcedSubtitlesChanged,
            onSubtitleFontSizeChanged = viewModel::setSubtitleFontSize,
            onSubtitleFontFamilyChanged = viewModel::setSubtitleFontFamily,
            onSubtitleFontColorChanged = viewModel::setSubtitleFontColor,
            onSubtitleTextOutlineChanged = viewModel::setSubtitleTextOutline,
            onSubtitleTextOutlineColorChanged = viewModel::setSubtitleTextOutlineColor,
            onSubtitleBackgroundStyleChanged = viewModel::setSubtitleBackgroundStyle,
            onSubtitleBackgroundOpacityChanged = viewModel::setSubtitleBackgroundOpacity,
            onSubtitleBackgroundColorChanged = viewModel::setSubtitleBackgroundColor,
            onSubtitlePositionChanged = viewModel::setSubtitlePosition,
            onSubtitleDeviceOverrideEnabledChanged = viewModel::setSubtitleDeviceOverrideEnabled,
            onDismiss = { subScreen = null },
        )
        SubScreen.CardOverlays -> TvCardOverlaySettingsScreen(
            store = overlayPrefsStore,
            onDismiss = { subScreen = null },
        )
        null -> Unit
    }

    if (showSignOutConfirm) {
        TvSettingsConfirmDialog(
            title = "Sign Out",
            message = "You will be returned to the login screen.",
            confirmLabel = "Sign Out",
            onConfirm = {
                showSignOutConfirm = false
                viewModel.onSignOut(context)
            },
            onDismiss = { showSignOutConfirm = false },
        )
    }
}

private enum class SubScreen { Playback, Subtitles, CardOverlays }

// ---------------------------------------------------------------------------
// Root menu
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRootMenu(
    state: TvSettingsViewModel.UiState,
    firstActionFocusRequester: FocusRequester,
    onSwitchProfile: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenCardOverlays: () -> Unit,
    onNavigateToAdmin: () -> Unit,
    onManageSessions: () -> Unit,
    onPairDevice: () -> Unit,
    onManageServers: () -> Unit,
    onRequestSignOut: () -> Unit,
    onNavigateToBrowse: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToWatchlist: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToCollections: () -> Unit,
    onNavigateToRequests: () -> Unit,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onNotifyFavoritesChanged: (Boolean) -> Unit,
    onNotifyWatchlistChanged: (Boolean) -> Unit,
    onNotifyContinueWatchingChanged: (Boolean) -> Unit,
    onNotifyNextUpChanged: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 72.dp,
            top = TvTopMenuLayout.contentTopInset,
            end = 72.dp,
            bottom = Spacing.xxxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        // Account header — tappable, switches profile.
        item {
            SettingsAccountRow(
                name = state.profileName ?: state.user?.username ?: "—",
                subtitle = accountSubtitle(state),
                avatar = state.profileAvatar,
                onClick = onSwitchProfile,
                focusRequester = firstActionFocusRequester,
            )
        }

        // Preferences — drill-in categories.
        item {
            SettingsGroup(title = "Preferences") {
                SettingsValueRow(
                    label = "Playback",
                    value = state.playbackQuality.label,
                    onClick = onOpenPlayback,
                )
                SettingsValueRow(
                    label = "Subtitles",
                    value = subtitleLanguageLabel(state.subtitleLanguage),
                    onClick = onOpenSubtitles,
                )
                SettingsValueRow(
                    label = "Card Overlays",
                    value = "",
                    onClick = onOpenCardOverlays,
                )
            }
        }

        if (state.notificationsVisible) {
            item {
                SettingsGroup(title = "Notifications") {
                    SettingsToggleRow(
                        label = "In-app notifications",
                        checked = state.notificationsEnabled,
                        onCheckedChange = onNotificationsEnabledChanged,
                    )
                    if (state.notificationsEnabled) {
                        SettingsToggleRow(
                            label = "Favorites",
                            checked = state.notifyFavorites,
                            onCheckedChange = onNotifyFavoritesChanged,
                        )
                        SettingsToggleRow(
                            label = "Watchlist",
                            checked = state.notifyWatchlist,
                            onCheckedChange = onNotifyWatchlistChanged,
                        )
                        SettingsToggleRow(
                            label = "Continue watching",
                            checked = state.notifyContinueWatching,
                            onCheckedChange = onNotifyContinueWatchingChanged,
                        )
                        SettingsToggleRow(
                            label = "Next up",
                            checked = state.notifyNextUp,
                            onCheckedChange = onNotifyNextUpChanged,
                        )
                    }
                }
            }
        }

        // Library shortcuts (Android-only extra).
        item {
            SettingsGroup(title = "Library") {
                SettingsActionRow(label = "Browse", onClick = onNavigateToBrowse)
                SettingsActionRow(label = "Favorites", onClick = onNavigateToFavorites)
                SettingsActionRow(label = "Watchlist", onClick = onNavigateToWatchlist)
                SettingsActionRow(label = "Watch history", onClick = onNavigateToHistory)
                SettingsActionRow(label = "Collections", onClick = onNavigateToCollections)
                if (CLIENT_REQUESTS_SURFACE_ENABLED) {
                    SettingsActionRow(label = "Requests", onClick = onNavigateToRequests)
                }
            }
        }

        // Account actions.
        item {
            SettingsGroup(title = "Account") {
                if (state.adminVisible) {
                    SettingsActionRow(label = "Admin Dashboard", onClick = onNavigateToAdmin)
                }
                SettingsActionRow(label = "Manage Sessions", onClick = onManageSessions)
                SettingsActionRow(label = "Pair a Device", onClick = onPairDevice)
                SettingsActionRow(
                    label = "Sign Out",
                    onClick = onRequestSignOut,
                    destructive = true,
                )
            }
        }

        // About / Server.
        item {
            SettingsGroup(title = "About") {
                SettingsInfoRow(
                    label = "Server",
                    value = state.serverName.ifBlank { "Not configured" },
                )
                if (state.serverUrl.isNotBlank() && state.serverName != state.serverUrl) {
                    SettingsInfoRow(label = "URL", value = state.serverUrl)
                }
                SettingsActionRow(label = "Manage Servers", onClick = onManageServers)
                SettingsInfoRow(label = "Version", value = BuildConfig.VERSION_NAME)
            }
        }
    }
}

private fun accountSubtitle(state: TvSettingsViewModel.UiState): String {
    val role = state.user?.role?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() }
    val username = state.user?.username?.takeIf { it.isNotBlank() }
    return listOfNotNull(role, username).joinToString(" · ").ifBlank { "Switch profile" }
}

// ---------------------------------------------------------------------------
// Playback sub-screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPlaybackSettingsScreen(
    state: TvSettingsViewModel.UiState,
    onQualityChanged: (PlaybackQuality) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    onDismiss: () -> Unit,
) {
    var activePicker by remember { mutableStateOf<PlaybackPicker?>(null) }

    TvSettingsSubScreenScaffold(title = "Playback", onDismiss = onDismiss) {
        item {
            SettingsGroup(title = "Streaming") {
                SettingsValueRow(
                    label = "Quality",
                    value = state.playbackQuality.label,
                    onClick = { activePicker = PlaybackPicker.Quality },
                )
                SettingsValueRow(
                    label = "Audio Language",
                    value = audioLanguageLabel(state.audioLanguage),
                    onClick = { activePicker = PlaybackPicker.AudioLanguage },
                )
            }
        }
        item {
            SettingsGroup(title = "Episodes") {
                SettingsToggleRow(
                    label = "Auto-Play Next Episode",
                    checked = state.autoPlayNext,
                    onCheckedChange = onAutoPlayNextChanged,
                )
                SettingsValueRow(
                    label = "Show Next Up",
                    value = nextUpPromptLabel(state.nextUpPromptSeconds),
                    onClick = { activePicker = PlaybackPicker.NextUpPrompt },
                )
                SettingsToggleRow(
                    label = "Auto-Skip Intros",
                    checked = state.autoSkipIntro,
                    onCheckedChange = onAutoSkipIntroChanged,
                )
                SettingsToggleRow(
                    label = "Auto-Skip Credits",
                    checked = state.autoSkipCredits,
                    onCheckedChange = onAutoSkipCreditsChanged,
                )
                SettingsValueRow(
                    label = "Resume Skip-Back",
                    value = resumeRewindLabel(state.resumeRewindSeconds),
                    onClick = { activePicker = PlaybackPicker.ResumeRewind },
                )
                SettingsValueRow(
                    label = "Still-Watching Prompt After",
                    value = passOutThresholdLabel(state.passOutThreshold),
                    onClick = { activePicker = PlaybackPicker.PassOutThreshold },
                )
            }
        }
        item {
            SettingsGroup(title = "Reset") {
                SettingsActionRow(
                    label = "Reset Playback Overrides",
                    onClick = onResetPlaybackOverrides,
                    destructive = true,
                )
            }
        }
    }

    when (activePicker) {
        PlaybackPicker.Quality -> TvSettingsPickerSheet(
            title = "Quality",
            options = PlaybackQuality.values().map { PickerOption(it.name, it.label) },
            selectedId = state.playbackQuality.name,
            onSelect = { id ->
                PlaybackQuality.values().firstOrNull { it.name == id }?.let(onQualityChanged)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        PlaybackPicker.AudioLanguage -> TvSettingsPickerSheet(
            title = "Audio Language",
            options = AudioLanguages.map { PickerOption(it.first, it.second) },
            selectedId = state.audioLanguage,
            onSelect = { onAudioLanguageChanged(it); activePicker = null },
            onDismiss = { activePicker = null },
        )
        PlaybackPicker.NextUpPrompt -> TvSettingsPickerSheet(
            title = "Show Next Up",
            options = NextUpPromptOptions.map { PickerOption(it.toString(), nextUpPromptLabel(it)) },
            selectedId = state.nextUpPromptSeconds.toString(),
            onSelect = { id ->
                id.toIntOrNull()?.let(onNextUpPromptSecondsChanged)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        PlaybackPicker.ResumeRewind -> TvSettingsPickerSheet(
            title = "Resume Skip-Back",
            options = ResumeRewindOptions.map { PickerOption(it.toString(), resumeRewindLabel(it)) },
            selectedId = state.resumeRewindSeconds.toString(),
            onSelect = { id ->
                id.toIntOrNull()?.let(onResumeRewindSecondsChanged)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        PlaybackPicker.PassOutThreshold -> TvSettingsPickerSheet(
            title = "Still-Watching Prompt After",
            options = PassOutThresholdOptions.map { PickerOption(it.toString(), passOutThresholdLabel(it)) },
            selectedId = state.passOutThreshold.toString(),
            onSelect = { id ->
                id.toIntOrNull()?.let(onPassOutThresholdChanged)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        null -> Unit
    }
}

private enum class PlaybackPicker { Quality, AudioLanguage, NextUpPrompt, ResumeRewind, PassOutThreshold }

// ---------------------------------------------------------------------------
// Subtitle sub-screen (keeps existing controls)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSubtitleSettingsScreen(
    state: TvSettingsViewModel.UiState,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onShowForcedSubtitlesChanged: (Boolean) -> Unit,
    onSubtitleFontSizeChanged: (SubtitleFontSizePreset) -> Unit,
    onSubtitleFontFamilyChanged: (String) -> Unit,
    onSubtitleFontColorChanged: (String) -> Unit,
    onSubtitleTextOutlineChanged: (Boolean) -> Unit,
    onSubtitleTextOutlineColorChanged: (String) -> Unit,
    onSubtitleBackgroundStyleChanged: (SubtitleBackgroundStylePreset) -> Unit,
    onSubtitleBackgroundOpacityChanged: (Int) -> Unit,
    onSubtitleBackgroundColorChanged: (String) -> Unit,
    onSubtitlePositionChanged: (SubtitlePositionPreset) -> Unit,
    onSubtitleDeviceOverrideEnabledChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var activePicker by remember { mutableStateOf<SubtitlePicker?>(null) }
    val appearance = state.subtitleAppearance

    TvSettingsSubScreenScaffold(title = "Subtitles", onDismiss = onDismiss) {
        // Profile section — language / behavior / forced (unchanged).
        item {
            SettingsGroup(title = "Profile") {
                SettingsValueRow(
                    label = "Mode",
                    value = state.subtitleMode.label,
                    onClick = { activePicker = SubtitlePicker.Mode },
                )
                SettingsValueRow(
                    label = "Language",
                    value = subtitleLanguageLabel(state.subtitleLanguage),
                    onClick = { activePicker = SubtitlePicker.Language },
                )
                SettingsToggleRow(
                    label = "Show Forced Subtitles",
                    checked = state.showForcedSubtitles,
                    onCheckedChange = onShowForcedSubtitlesChanged,
                )
            }
        }

        // Appearance section — device-scoped override (ports tvOS
        // TVSubtitleSettingsView appearance block).
        item {
            SettingsGroup(title = "Appearance") {
                SettingsToggleRow(
                    label = "Custom Appearance",
                    checked = state.subtitleUsesDeviceOverride,
                    onCheckedChange = onSubtitleDeviceOverrideEnabledChanged,
                )
                SettingsValueRow(
                    label = "Font Size",
                    value = TvSubtitleAppearanceOptions.fontSizeLabel(appearance.fontSize),
                    onClick = { activePicker = SubtitlePicker.FontSize },
                )
                SettingsValueRow(
                    label = "Font Family",
                    value = TvSubtitleAppearanceOptions.fontFamilyLabel(appearance.fontFamily),
                    onClick = { activePicker = SubtitlePicker.FontFamily },
                )
                SettingsValueRow(
                    label = "Font Color",
                    value = TvSubtitleAppearanceOptions.fontColorLabel(appearance.fontColor),
                    onClick = { activePicker = SubtitlePicker.FontColor },
                )
                SettingsToggleRow(
                    label = "Text Outline",
                    checked = appearance.textOutline,
                    onCheckedChange = onSubtitleTextOutlineChanged,
                )
                SettingsValueRow(
                    label = "Outline Color",
                    value = TvSubtitleAppearanceOptions.outlineColorLabel(appearance.textOutlineColor),
                    onClick = { activePicker = SubtitlePicker.OutlineColor },
                )
                SettingsValueRow(
                    label = "Background Style",
                    value = TvSubtitleAppearanceOptions.backgroundStyleLabel(appearance.backgroundStyle),
                    onClick = { activePicker = SubtitlePicker.BackgroundStyle },
                )
                SettingsValueRow(
                    label = "Background Opacity",
                    value = "${appearance.backgroundOpacity}%",
                    onClick = { activePicker = SubtitlePicker.BackgroundOpacity },
                )
                SettingsValueRow(
                    label = "Background Color",
                    value = TvSubtitleAppearanceOptions.backgroundColorLabel(appearance.backgroundColor),
                    onClick = { activePicker = SubtitlePicker.BackgroundColor },
                )
                SettingsValueRow(
                    label = "Position",
                    value = TvSubtitleAppearanceOptions.positionLabel(appearance.position),
                    onClick = { activePicker = SubtitlePicker.Position },
                )
            }
        }
        item {
            SettingsFooterText(
                text = if (state.subtitleUsesDeviceOverride) {
                    "Appearance is saved on the server for this profile on this device."
                } else {
                    "Appearance is using the server fallback for this profile on this device."
                },
            )
        }
    }

    when (activePicker) {
        SubtitlePicker.Mode -> TvSettingsPickerSheet(
            title = "Mode",
            options = SubtitleMode.values().map { PickerOption(it.name, it.label) },
            selectedId = state.subtitleMode.name,
            onSelect = { id ->
                SubtitleMode.values().firstOrNull { it.name == id }?.let(onSubtitleModeChanged)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.Language -> TvSettingsPickerSheet(
            title = "Language",
            options = SubtitleLanguages.map { PickerOption(it.first, it.second) },
            selectedId = state.subtitleLanguage,
            onSelect = { onSubtitleLanguageChanged(it); activePicker = null },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.FontSize -> TvSettingsPickerSheet(
            title = "Font Size",
            options = TvSubtitleAppearanceOptions.FONT_SIZES.map { PickerOption(it.first.name, it.second) },
            selectedId = appearance.fontSize.name,
            onSelect = { id ->
                TvSubtitleAppearanceOptions.FONT_SIZES.firstOrNull { it.first.name == id }?.let {
                    onSubtitleFontSizeChanged(it.first)
                }
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.FontFamily -> TvSettingsPickerSheet(
            title = "Font Family",
            options = TvSubtitleAppearanceOptions.FONT_FAMILIES.map { PickerOption(it.first, it.second) },
            selectedId = appearance.fontFamily,
            onSelect = { id ->
                TvSubtitleAppearanceOptions.FONT_FAMILIES.firstOrNull { it.first == id }?.let {
                    onSubtitleFontFamilyChanged(it.first)
                }
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.FontColor -> TvSettingsPickerSheet(
            title = "Font Color",
            options = TvSubtitleAppearanceOptions.FONT_COLORS.map { PickerOption(it.first, it.second) },
            selectedId = appearance.fontColor.lowercase(),
            onSelect = { id ->
                onSubtitleFontColorChanged(id)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.OutlineColor -> TvSettingsPickerSheet(
            title = "Outline Color",
            options = TvSubtitleAppearanceOptions.OUTLINE_COLORS.map { PickerOption(it.first, it.second) },
            selectedId = appearance.textOutlineColor.lowercase(),
            onSelect = { id ->
                onSubtitleTextOutlineColorChanged(id)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.BackgroundStyle -> TvSettingsPickerSheet(
            title = "Background Style",
            options = TvSubtitleAppearanceOptions.BACKGROUND_STYLES.map { PickerOption(it.first.name, it.second) },
            selectedId = appearance.backgroundStyle.name,
            onSelect = { id ->
                TvSubtitleAppearanceOptions.BACKGROUND_STYLES.firstOrNull { it.first.name == id }?.let {
                    onSubtitleBackgroundStyleChanged(it.first)
                }
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.BackgroundOpacity -> TvSettingsPickerSheet(
            title = "Background Opacity",
            options = TvSubtitleAppearanceOptions.OPACITY_PERCENT_STEPS.map { PickerOption(it.toString(), "$it%") },
            selectedId = appearance.backgroundOpacity.toString(),
            onSelect = { id ->
                id.toIntOrNull()?.let { onSubtitleBackgroundOpacityChanged(it) }
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.BackgroundColor -> TvSettingsPickerSheet(
            title = "Background Color",
            options = TvSubtitleAppearanceOptions.BACKGROUND_COLORS.map { PickerOption(it.first, it.second) },
            selectedId = appearance.backgroundColor.lowercase(),
            onSelect = { id ->
                onSubtitleBackgroundColorChanged(id)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.Position -> TvSettingsPickerSheet(
            title = "Position",
            options = TvSubtitleAppearanceOptions.POSITIONS.map { PickerOption(it.first.name, it.second) },
            selectedId = appearance.position.name,
            onSelect = { id ->
                TvSubtitleAppearanceOptions.POSITIONS.firstOrNull { it.first.name == id }?.let {
                    onSubtitlePositionChanged(it.first)
                }
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        null -> Unit
    }
}

private enum class SubtitlePicker {
    Mode,
    Language,
    FontSize,
    FontFamily,
    FontColor,
    OutlineColor,
    BackgroundStyle,
    BackgroundOpacity,
    BackgroundColor,
    Position,
}

// ---------------------------------------------------------------------------
// Sub-screen scaffold (full-screen overlay with a title + back-to-dismiss)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSettingsSubScreenScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { firstRowFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(firstRowFocus),
            contentPadding = PaddingValues(
                start = 72.dp,
                top = TvTopMenuLayout.contentTopInset,
                end = 72.dp,
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable picker sheet (centered modal vertical option list)
// ---------------------------------------------------------------------------

data class PickerOption(val id: String, val label: String)

/**
 * Reusable centered modal option picker. Renders a vertical list with a
 * checkmark on the current selection, auto-focuses the selected row and
 * scrolls it into view, and dismisses on selection or Back. Mirrors the
 * tvOS `TVSettingsPickerSheet`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsPickerSheet(
    title: String,
    options: List<PickerOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val initialFocus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val focusTargetIndex = if (options.isEmpty()) -1 else selectedIndex
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(title, selectedId) {
        if (focusTargetIndex >= 0) {
            runCatching { listState.scrollToItem(focusTargetIndex) }
            runCatching { initialFocus.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 14.sp, lineHeight = 16.sp),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 28.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(340.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(options, key = { it.id }) { option ->
                        val isFocusTarget = option.id == (options.getOrNull(focusTargetIndex)?.id)
                        TvSettingsPickerOptionRow(
                            option = option,
                            selected = option.id == selectedId,
                            onClick = { onSelect(option.id) },
                            modifier = if (isFocusTarget) {
                                Modifier.focusRequester(initialFocus)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSettingsPickerOptionRow(
    option: PickerOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(7.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 15.sp, lineHeight = 17.sp),
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isFocused) FocusedContent else Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Confirm dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.86f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(
                        label = "Cancel",
                        onClick = onDismiss,
                    )
                    DialogButton(
                        label = confirmLabel,
                        onClick = onConfirm,
                        destructive = true,
                        focusRequester = confirmFocus,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
            color = if (isFocused) FocusedContent else if (destructive) MaterialTheme.colorScheme.error else Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Shared row primitives — inverted-capsule focus chrome
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        content()
    }
}

private val RowShape = RoundedCornerShape(12.dp)
private val RowMaxWidth = 960.dp
private val RowHeight = 64.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsAccountRow(
    name: String,
    subtitle: String,
    avatar: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFocused) FocusedContent.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.12f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isFocused) FocusedContent else Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFocused) FocusedContent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .height(RowHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .height(RowHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isFocused -> FocusedContent
                    destructive -> MaterialTheme.colorScheme.error
                    else -> Color.White
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = { onCheckedChange(!checked) },
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .height(RowHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (checked) "On" else "Off",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isFocused -> FocusedContent
                    checked -> MaterialTheme.colorScheme.primary
                    else -> Color.White.copy(alpha = 0.6f)
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .height(RowHeight)
            .clip(RowShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Non-focusable explanatory footer below a settings group (tvOS Section footer). */
@Composable
private fun SettingsFooterText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .padding(horizontal = 8.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun invertedRowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.06f),
    contentColor = Color.White,
    focusedContainerColor = FocusedContainer,
    focusedContentColor = FocusedContent,
    pressedContainerColor = FocusedContainer,
    pressedContentColor = FocusedContent,
)

// ---------------------------------------------------------------------------
// Option data + value formatting
// ---------------------------------------------------------------------------

// Discrete choices for the F1/F2 behavior settings (0 = off).
private val ResumeRewindOptions = listOf(0, 3, 5, 7, 10, 15, 20, 30)
private val PassOutThresholdOptions = listOf(0, 2, 3, 4, 5)

// Up-Next prompt timing (seconds before end; 0 = at end). Mirrors tvOS.
private val NextUpPromptOptions = listOf(0, 10, 30, 60, 120)

// Audio-language options mirror the phone: the stored value IS the display
// name (Default => "" locally), persisted to playerSettingsStore.audioLanguage.
private val AudioLanguages = listOf(
    "" to "Default",
    "English" to "English",
    "Spanish" to "Spanish",
    "French" to "French",
    "German" to "German",
    "Japanese" to "Japanese",
    "Korean" to "Korean",
    "Chinese" to "Chinese",
    "Portuguese" to "Portuguese",
    "Italian" to "Italian",
    "Russian" to "Russian",
)

private val SubtitleLanguages = listOf(
    "" to "Off",
    "en" to "English",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "ja" to "Japanese",
    "ko" to "Korean",
    "zh" to "Chinese",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ru" to "Russian",
)

private fun audioLanguageLabel(wire: String): String =
    AudioLanguages.firstOrNull { it.first == wire }?.second ?: "Default"

private fun subtitleLanguageLabel(wire: String): String =
    SubtitleLanguages.firstOrNull { it.first == wire }?.second ?: "Off"

private fun resumeRewindLabel(seconds: Int): String =
    if (seconds <= 0) "Off" else "${seconds}s"

private fun passOutThresholdLabel(count: Int): String =
    if (count <= 0) "Off" else "$count"

private fun nextUpPromptLabel(seconds: Int): String = when {
    seconds <= 0 -> "At end"
    seconds < 60 -> "$seconds seconds before end"
    seconds == 60 -> "1 minute before end"
    else -> "${seconds / 60} minutes before end"
}
