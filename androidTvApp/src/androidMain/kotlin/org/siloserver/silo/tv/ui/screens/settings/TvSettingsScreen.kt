package org.siloserver.silo.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
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
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.tv.BuildConfig
import org.siloserver.silo.tv.data.preferences.PlaybackQuality
import org.siloserver.silo.tv.data.preferences.SubtitleMode
import org.siloserver.silo.tv.ui.screens.player.TvSubtitleAppearanceOptions
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * TV Settings — a tvOS-style split rail/detail surface modeled on
 * `iosApp/.../tvOS/Screens/Settings/TVSettingsView.swift`.
 *
 * Requests/admin/watch-together routes stay compiled elsewhere, but this
 * settings surface deliberately avoids exposing them as normal user menu rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(
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
    val metadataAiStore: org.siloserver.silo.model.feature.MetadataAiFeatureStore =
        org.koin.compose.koinInject()
    val metadataAiStatus by metadataAiStore.status.collectAsState()
    val context = LocalContext.current
    val overlayPrefsStore: OverlayPrefsStore = koinInject()
    val firstActionFocusRequester = remember { FocusRequester() }

    var selectedCategory by remember { mutableStateOf(TvSettingsCategory.General) }
    var showCardOverlaysEditor by remember { mutableStateOf(false) }
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

    SettingsSplitLayout(
        state = state,
        selectedCategory = selectedCategory,
        firstActionFocusRequester = firstActionFocusRequester,
        onCategorySelected = { selectedCategory = it },
        onOpenCardOverlays = { showCardOverlaysEditor = true },
        onSwitchProfile = viewModel::onSwitchProfile,
        onManageSessions = onManageSessions,
        onPairDevice = onPairDevice,
        onManageServers = onManageServers,
        onRequestSignOut = { showSignOutConfirm = true },
        onNavigateToAdmin = onNavigateToAdmin,
        onNotificationsEnabledChanged = viewModel::onNotificationsEnabledChanged,
        onNotifyFavoritesChanged = viewModel::onNotifyFavoritesChanged,
        onNotifyWatchlistChanged = viewModel::onNotifyWatchlistChanged,
        onNotifyContinueWatchingChanged = viewModel::onNotifyContinueWatchingChanged,
        onNotifyNextUpChanged = viewModel::onNotifyNextUpChanged,
        onQualityChanged = viewModel::onPlaybackQualityChanged,
        onAudioLanguageChanged = viewModel::onAudioLanguageChanged,
        onAutoPlayNextChanged = viewModel::onAutoPlayNextChanged,
        onAutoSkipIntroChanged = viewModel::onAutoSkipIntroChanged,
        onAutoSkipCreditsChanged = viewModel::onAutoSkipCreditsChanged,
        onMatchContentFrameRateChanged = viewModel::onMatchContentFrameRateChanged,
        onDolbyVisionEnabledChanged = viewModel::onDolbyVisionEnabledChanged,
        onDvProfile7HDR10FallbackChanged = viewModel::onDvProfile7HDR10FallbackChanged,
        onPictureInPictureEnabledChanged = viewModel::onPictureInPictureEnabledChanged,
        onResumeRewindSecondsChanged = viewModel::onResumeRewindSecondsChanged,
        onPassOutThresholdChanged = viewModel::onPassOutThresholdChanged,
        onNextUpPromptSecondsChanged = viewModel::onNextUpPromptSecondsChanged,
        onResetPlaybackOverrides = viewModel::resetPlaybackOverrides,
        onSubtitleModeChanged = viewModel::onSubtitleModeChanged,
        onSubtitleLanguageChanged = viewModel::onSubtitleLanguageChanged,
        onMetadataLanguageChanged = viewModel::onMetadataLanguageChanged,
        metadataLanguageEnabled = metadataAiStatus.enabled && metadataAiStatus.onView != org.siloserver.silo.model.metadata.MetadataAiOnView.Off,
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
        onSubtitleMatchesDeviceChanged = viewModel::onSubtitleMatchesDeviceChanged,
        onShowAudiobooksTabChanged = viewModel::onShowAudiobooksTabChanged,
    )

    if (showCardOverlaysEditor) {
        TvCardOverlaySettingsScreen(
            store = overlayPrefsStore,
            onDismiss = { showCardOverlaysEditor = false },
        )
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

private enum class TvSettingsCategory(
    val title: String,
    val eyebrow: String,
    val blurb: String,
) {
    General(
        title = "General",
        eyebrow = "PREFERENCES",
        blurb = "App-level options for this Android TV.",
    ),
    Playback(
        title = "Playback",
        eyebrow = "PREFERENCES",
        blurb = "Streaming, episode, and playback behavior for this device.",
    ),
    Subtitles(
        title = "Subtitles",
        eyebrow = "PREFERENCES",
        blurb = "Language, behavior, and subtitle appearance.",
    ),
    Server(
        title = "Server",
        eyebrow = "CONNECTION",
        blurb = "Active server, device pairing, and account tools.",
    ),
}

// ---------------------------------------------------------------------------
// Split settings layout (tvOS parity)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun SettingsSplitLayout(
    state: TvSettingsViewModel.UiState,
    selectedCategory: TvSettingsCategory,
    firstActionFocusRequester: FocusRequester,
    onCategorySelected: (TvSettingsCategory) -> Unit,
    onOpenCardOverlays: () -> Unit,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onManageSessions: () -> Unit,
    onPairDevice: () -> Unit,
    onManageServers: () -> Unit,
    onRequestSignOut: () -> Unit,
    onNavigateToAdmin: () -> Unit,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onNotifyFavoritesChanged: (Boolean) -> Unit,
    onNotifyWatchlistChanged: (Boolean) -> Unit,
    onNotifyContinueWatchingChanged: (Boolean) -> Unit,
    onNotifyNextUpChanged: (Boolean) -> Unit,
    onQualityChanged: (PlaybackQuality) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onMetadataLanguageChanged: (String) -> Unit,
    metadataLanguageEnabled: Boolean,
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
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = 72.dp,
                top = TvTopMenuLayout.contentTopInset,
                end = 72.dp,
                bottom = Spacing.xxxl,
            ),
        horizontalArrangement = Arrangement.spacedBy(52.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsRail(
            state = state,
            selectedCategory = selectedCategory,
            firstActionFocusRequester = firstActionFocusRequester,
            onCategorySelected = onCategorySelected,
            onSwitchProfile = onSwitchProfile,
            onNavigateToAdmin = onNavigateToAdmin,
            onRequestSignOut = onRequestSignOut,
            modifier = Modifier.width(300.dp),
        )
        SettingsDetailPane(
            state = state,
            selectedCategory = selectedCategory,
            onOpenCardOverlays = onOpenCardOverlays,
            onShowAudiobooksTabChanged = onShowAudiobooksTabChanged,
            onManageSessions = onManageSessions,
            onPairDevice = onPairDevice,
            onManageServers = onManageServers,
            onNotificationsEnabledChanged = onNotificationsEnabledChanged,
            onNotifyFavoritesChanged = onNotifyFavoritesChanged,
            onNotifyWatchlistChanged = onNotifyWatchlistChanged,
            onNotifyContinueWatchingChanged = onNotifyContinueWatchingChanged,
            onNotifyNextUpChanged = onNotifyNextUpChanged,
            onQualityChanged = onQualityChanged,
            onAudioLanguageChanged = onAudioLanguageChanged,
            onAutoPlayNextChanged = onAutoPlayNextChanged,
            onAutoSkipIntroChanged = onAutoSkipIntroChanged,
            onAutoSkipCreditsChanged = onAutoSkipCreditsChanged,
            onMatchContentFrameRateChanged = onMatchContentFrameRateChanged,
            onDolbyVisionEnabledChanged = onDolbyVisionEnabledChanged,
            onDvProfile7HDR10FallbackChanged = onDvProfile7HDR10FallbackChanged,
            onPictureInPictureEnabledChanged = onPictureInPictureEnabledChanged,
            onResumeRewindSecondsChanged = onResumeRewindSecondsChanged,
            onPassOutThresholdChanged = onPassOutThresholdChanged,
            onNextUpPromptSecondsChanged = onNextUpPromptSecondsChanged,
            onResetPlaybackOverrides = onResetPlaybackOverrides,
            onSubtitleModeChanged = onSubtitleModeChanged,
            onSubtitleLanguageChanged = onSubtitleLanguageChanged,
            onMetadataLanguageChanged = onMetadataLanguageChanged,
            metadataLanguageEnabled = metadataLanguageEnabled,
            onShowForcedSubtitlesChanged = onShowForcedSubtitlesChanged,
            onSubtitleFontSizeChanged = onSubtitleFontSizeChanged,
            onSubtitleFontFamilyChanged = onSubtitleFontFamilyChanged,
            onSubtitleFontColorChanged = onSubtitleFontColorChanged,
            onSubtitleTextOutlineChanged = onSubtitleTextOutlineChanged,
            onSubtitleTextOutlineColorChanged = onSubtitleTextOutlineColorChanged,
            onSubtitleBackgroundStyleChanged = onSubtitleBackgroundStyleChanged,
            onSubtitleBackgroundOpacityChanged = onSubtitleBackgroundOpacityChanged,
            onSubtitleBackgroundColorChanged = onSubtitleBackgroundColorChanged,
            onSubtitlePositionChanged = onSubtitlePositionChanged,
            onSubtitleDeviceOverrideEnabledChanged = onSubtitleDeviceOverrideEnabledChanged,
            onSubtitleMatchesDeviceChanged = onSubtitleMatchesDeviceChanged,
            // Contain Up at the pane's top row: escaping to the top menu from
            // inside a category was disorienting (QA 2026-07-08). Left still
            // exits to the category rail.
            modifier = Modifier
                .weight(1f)
                .focusGroup()
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Up) FocusRequester.Cancel
                        else FocusRequester.Default
                    }
                },
        )
    }
}

@Composable
private fun SettingsRail(
    state: TvSettingsViewModel.UiState,
    selectedCategory: TvSettingsCategory,
    firstActionFocusRequester: FocusRequester,
    onCategorySelected: (TvSettingsCategory) -> Unit,
    onSwitchProfile: () -> Unit,
    onRequestSignOut: () -> Unit,
    onNavigateToAdmin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp, bottom = 20.dp),
        )
        SettingsAccountRow(
            name = state.profileName ?: state.user?.username ?: "-",
            subtitle = accountSubtitle(state),
            avatar = state.profileAvatar,
            onClick = onSwitchProfile,
            focusRequester = firstActionFocusRequester,
        )
        Spacer(modifier = Modifier.height(14.dp))
        TvSettingsCategory.entries.forEach { category ->
            SettingsRailCategoryRow(
                category = category,
                selected = category == selectedCategory,
                onClick = { onCategorySelected(category) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Apple-parity admin surface: the stats dashboard only, role-gated.
        if (state.adminVisible) {
            SettingsActionRow(
                label = "Admin",
                onClick = onNavigateToAdmin,
            )
        }
        SettingsActionRow(
            label = "Sign Out",
            onClick = onRequestSignOut,
            destructive = true,
        )
        Text(
            text = "Silo ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(start = 8.dp, top = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRailCategoryRow(
    category: TvSettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = if (isFocused) FocusedContent else Color.White
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsDetailPane(
    state: TvSettingsViewModel.UiState,
    selectedCategory: TvSettingsCategory,
    onOpenCardOverlays: () -> Unit,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onManageSessions: () -> Unit,
    onPairDevice: () -> Unit,
    onManageServers: () -> Unit,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onNotifyFavoritesChanged: (Boolean) -> Unit,
    onNotifyWatchlistChanged: (Boolean) -> Unit,
    onNotifyContinueWatchingChanged: (Boolean) -> Unit,
    onNotifyNextUpChanged: (Boolean) -> Unit,
    onQualityChanged: (PlaybackQuality) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onMetadataLanguageChanged: (String) -> Unit,
    metadataLanguageEnabled: Boolean,
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
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = selectedCategory.eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = selectedCategory.title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = selectedCategory.blurb,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )

        when (selectedCategory) {
            TvSettingsCategory.General -> TvGeneralSettingsPane(
                state = state,
                onOpenCardOverlays = onOpenCardOverlays,
            onShowAudiobooksTabChanged = onShowAudiobooksTabChanged,
                onNotificationsEnabledChanged = onNotificationsEnabledChanged,
                onNotifyFavoritesChanged = onNotifyFavoritesChanged,
                onNotifyWatchlistChanged = onNotifyWatchlistChanged,
                onNotifyContinueWatchingChanged = onNotifyContinueWatchingChanged,
                onNotifyNextUpChanged = onNotifyNextUpChanged,
            )
            TvSettingsCategory.Playback -> TvPlaybackSettingsPane(
                state = state,
                onQualityChanged = onQualityChanged,
                onAudioLanguageChanged = onAudioLanguageChanged,
                onAutoPlayNextChanged = onAutoPlayNextChanged,
                onAutoSkipIntroChanged = onAutoSkipIntroChanged,
                onAutoSkipCreditsChanged = onAutoSkipCreditsChanged,
            onMatchContentFrameRateChanged = onMatchContentFrameRateChanged,
            onDolbyVisionEnabledChanged = onDolbyVisionEnabledChanged,
            onDvProfile7HDR10FallbackChanged = onDvProfile7HDR10FallbackChanged,
                onPictureInPictureEnabledChanged = onPictureInPictureEnabledChanged,
                onResumeRewindSecondsChanged = onResumeRewindSecondsChanged,
                onPassOutThresholdChanged = onPassOutThresholdChanged,
                onNextUpPromptSecondsChanged = onNextUpPromptSecondsChanged,
                onResetPlaybackOverrides = onResetPlaybackOverrides,
            )
            TvSettingsCategory.Subtitles -> TvSubtitleSettingsPane(
                state = state,
                onSubtitleModeChanged = onSubtitleModeChanged,
                onSubtitleLanguageChanged = onSubtitleLanguageChanged,
                onMetadataLanguageChanged = onMetadataLanguageChanged,
                metadataLanguageEnabled = metadataLanguageEnabled,
                onShowForcedSubtitlesChanged = onShowForcedSubtitlesChanged,
                onSubtitleFontSizeChanged = onSubtitleFontSizeChanged,
                onSubtitleFontFamilyChanged = onSubtitleFontFamilyChanged,
                onSubtitleFontColorChanged = onSubtitleFontColorChanged,
                onSubtitleTextOutlineChanged = onSubtitleTextOutlineChanged,
                onSubtitleTextOutlineColorChanged = onSubtitleTextOutlineColorChanged,
                onSubtitleBackgroundStyleChanged = onSubtitleBackgroundStyleChanged,
                onSubtitleBackgroundOpacityChanged = onSubtitleBackgroundOpacityChanged,
                onSubtitleBackgroundColorChanged = onSubtitleBackgroundColorChanged,
                onSubtitlePositionChanged = onSubtitlePositionChanged,
                onSubtitleDeviceOverrideEnabledChanged = onSubtitleDeviceOverrideEnabledChanged,
            onSubtitleMatchesDeviceChanged = onSubtitleMatchesDeviceChanged,
            )
            TvSettingsCategory.Server -> TvServerSettingsPane(
                state = state,
                onManageSessions = onManageSessions,
                onPairDevice = onPairDevice,
                onManageServers = onManageServers,
            )
        }
    }
}

@Composable
private fun TvGeneralSettingsPane(
    state: TvSettingsViewModel.UiState,
    onOpenCardOverlays: () -> Unit,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onNotificationsEnabledChanged: (Boolean) -> Unit,
    onNotifyFavoritesChanged: (Boolean) -> Unit,
    onNotifyWatchlistChanged: (Boolean) -> Unit,
    onNotifyContinueWatchingChanged: (Boolean) -> Unit,
    onNotifyNextUpChanged: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        item {
            // tvOS TVGeneralSettingsPane TOP MENU parity: the Audiobooks tab
            // is opt-in (hidden by default) even when the server has an
            // audiobook library.
            SettingsGroup(title = "Top Menu") {
                SettingsToggleRow(
                    label = "Show Audiobooks",
                    checked = state.showAudiobooksTab,
                    onCheckedChange = onShowAudiobooksTabChanged,
                )
            }
        }
        item {
            SettingsGroup(title = "Preferences") {
                SettingsActionRow(label = "Card Overlays", onClick = onOpenCardOverlays)
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
        // No Library group — tvOS parity: Apple's TVSettingsView has no such
        // section (it is iOS-only). On TV these destinations live in the
        // For You dropdown (Watchlist/Favorites), the profile menu
        // (Watchlist/Favorites/History), Home (Browse), and each library's
        // cascade (Collections). (QA 2026-07-08.)
    }
}

@Composable
private fun TvPlaybackSettingsPane(
    state: TvSettingsViewModel.UiState,
    onQualityChanged: (PlaybackQuality) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
) {
    var activePicker by remember { mutableStateOf<PlaybackPicker?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
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
                // tvOS TVPlaybackSettingsPane STREAMING parity: Dolby Vision
                // (default on; off plays the HDR10 base layer) with the
                // narrower Profile 7 fallback nested under it — the P7 row
                // only shows while Dolby Vision is on, as on Apple TV.
                SettingsToggleRow(
                    label = "Dolby Vision",
                    checked = state.dolbyVisionEnabled,
                    onCheckedChange = onDolbyVisionEnabledChanged,
                )
                if (state.dolbyVisionEnabled) {
                    SettingsToggleRow(
                        label = "Profile 7 HDR10 Fallback",
                        checked = state.dvProfile7HDR10Fallback,
                        onCheckedChange = onDvProfile7HDR10FallbackChanged,
                    )
                }
                SettingsToggleRow(
                    label = "Picture-in-Picture",
                    checked = state.pictureInPictureEnabled,
                    onCheckedChange = onPictureInPictureEnabledChanged,
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
                // Apple TV parity: "Match Content" is a viewer choice and
                // defaults OFF — the HDMI mode switch black-screens for a
                // second or two on entry/exit (QA 2026-07-08).
                SettingsToggleRow(
                    label = "Match Content Frame Rate",
                    checked = state.matchContentFrameRate,
                    onCheckedChange = onMatchContentFrameRateChanged,
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

@Composable
private fun TvSubtitleSettingsPane(
    state: TvSettingsViewModel.UiState,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onMetadataLanguageChanged: (String) -> Unit,
    metadataLanguageEnabled: Boolean,
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
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
) {
    var activePicker by remember { mutableStateOf<SubtitlePicker?>(null) }
    val appearance = state.subtitleAppearance

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
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
                if (metadataLanguageEnabled) {
                    SettingsValueRow(
                        label = "Metadata Language",
                        value = subtitleLanguageLabel(state.metadataLanguage),
                        onClick = { activePicker = SubtitlePicker.MetadataLanguage },
                    )
                }
                SettingsToggleRow(
                    label = "Show Forced Subtitles",
                    checked = state.showForcedSubtitles,
                    onCheckedChange = onShowForcedSubtitlesChanged,
                )
            }
        }
        item {
            SettingsGroup(title = "Appearance") {
                SettingsToggleRow(
                    // tvOS parity: appearance follows the OS captioning
                    // settings while this is on.
                    label = "Match Device Settings",
                    checked = state.subtitleMatchesDevice,
                    onCheckedChange = onSubtitleMatchesDeviceChanged,
                )
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
        SubtitlePicker.MetadataLanguage -> TvSettingsPickerSheet(
            title = "Metadata Language",
            options = SubtitleLanguages.map { PickerOption(it.first, it.second) },
            selectedId = state.metadataLanguage,
            onSelect = { onMetadataLanguageChanged(it); activePicker = null },
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

@Composable
private fun TvServerSettingsPane(
    state: TvSettingsViewModel.UiState,
    onManageSessions: () -> Unit,
    onPairDevice: () -> Unit,
    onManageServers: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        item {
            SettingsGroup(title = "Active Server") {
                SettingsInfoRow(
                    label = "Server",
                    value = state.serverName.ifBlank { "Not configured" },
                )
                if (state.serverUrl.isNotBlank() && state.serverName != state.serverUrl) {
                    SettingsInfoRow(label = "URL", value = state.serverUrl, singleLine = false)
                }
                SettingsActionRow(label = "Manage Servers", onClick = onManageServers)
            }
        }
        item {
            SettingsGroup(title = "Account") {
                SettingsActionRow(label = "Manage Sessions", onClick = onManageSessions)
                SettingsActionRow(label = "Pair a Device", onClick = onPairDevice)
            }
        }
        item {
            SettingsGroup(title = "About") {
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

private enum class PlaybackPicker { Quality, AudioLanguage, NextUpPrompt, ResumeRewind, PassOutThreshold }

private enum class SubtitlePicker {
    Mode,
    Language,
    MetadataLanguage,
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
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 18.sp, lineHeight = 21.sp),
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
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 17.sp, lineHeight = 20.sp),
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isFocused) FocusedContent else Color.White,
                    modifier = Modifier.size(14.dp),
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
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
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
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
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
private val RowMaxWidth = 840.dp
private val RowHeight = 56.dp

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
private fun SettingsInfoRow(label: String, value: String, singleLine: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = RowMaxWidth)
            .let { if (singleLine) it.height(RowHeight) else it.heightIn(min = RowHeight) }
            .clip(RowShape)
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 24.dp, vertical = if (singleLine) 0.dp else 12.dp),
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
            // Long values (the server URL) wrap instead of truncating into
            // unreadability (QA 2026-07-08).
            maxLines = if (singleLine) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f),
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
