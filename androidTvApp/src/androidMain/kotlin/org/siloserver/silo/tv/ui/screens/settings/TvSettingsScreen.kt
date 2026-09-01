package org.siloserver.silo.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.network.clientVersionLabel
import org.siloserver.silo.common.settings.CardPresentationSource
import org.siloserver.silo.common.settings.CardPresentationSupport
import org.siloserver.silo.model.settings.CardCaption
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.CardPresentationPreset
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.pointSize
import org.siloserver.silo.tv.BuildConfig
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.data.preferences.SubtitleMode
import org.siloserver.silo.tv.ui.screens.player.TvSubtitleAppearanceOptions
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsSettingsPane
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsViewModel
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

/**
 * TV Settings — a tvOS-style split rail/detail surface modeled on
 * `iosApp/.../tvOS/Screens/Settings/TVSettingsView.swift`.
 *
 * Requests/watch-together routes stay compiled elsewhere without normal menu
 * rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSettingsScreen(
    onManageServers: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    onOpenDiagnosticsReport: (reportId: String) -> Unit = {},
    onInitialContentFocus: () -> Unit = {},
    initialManageServersFocus: Boolean = false,
    onManageServersReturnFocusConsumed: () -> Unit = {},
    viewModel: TvSettingsViewModel = koinViewModel(),
    diagnosticsViewModel: TvDiagnosticsViewModel = koinViewModel(),
    homeSectionsViewModel: HomeViewModel = koinViewModel(key = "settings-home-sections"),
) {
    val state by viewModel.uiState.collectAsState()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    val metadataAiStore: org.siloserver.silo.model.feature.MetadataAiFeatureStore =
        org.koin.compose.koinInject()
    val metadataAiStatus by metadataAiStore.status.collectAsState()
    val context = LocalContext.current
    val categoryFocusRequesters = remember {
        TvSettingsCategory.entries.associateWith { FocusRequester() }
    }
    val detailFocusRequester = remember { FocusRequester() }

    // Saveable so a drill-out to the pending-report route and back returns to
    // the category the viewer was reading, not to General.
    var selectedCategory by rememberSaveable {
        mutableStateOf(
            if (initialManageServersFocus) TvSettingsCategory.Server else TvSettingsCategory.General,
        )
    }
    var detailHasFocus by remember { mutableStateOf(false) }
    var categoryColumnHasFocus by remember { mutableStateOf(false) }
    var detailFocusRequest by remember { mutableStateOf(0) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Was four attempts judged on requestFocus() returning true — that is
        // acceptance, not arrival. onInitialContentFocus() hands content focus
        // to the shell, so firing it regardless told the shell focus had landed
        // even when the loop had just failed four times.
        val focusRestored = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            // Resolved per attempt rather than captured up front: rememberSaveable
            // may have restored a category other than General, and the eligibility
            // fallback below can retarget it on the same frame. Claiming General
            // unconditionally undid the restore, because that row's onFocused
            // resets the selection on the way in.
            requestFocus = {
                if (initialManageServersFocus) {
                    detailFocusRequester.requestFocus()
                } else {
                    categoryFocusRequesters.getValue(selectedCategory).requestFocus()
                }
            },
            isFocused = { categoryColumnHasFocus },
        ) == TvObservedFocusResult.Focused
        if (initialManageServersFocus && focusRestored) onManageServersReturnFocusConsumed()
        if (focusRestored) onInitialContentFocus()
    }

    // tvOS parity: eligibility can flip while Settings is open (profile switch,
    // server capability refresh). Falling back keeps the pane and the rail in
    // agreement instead of stranding focus in a category that just vanished.
    LaunchedEffect(diagnosticsState.profileEligible) {
        val fallback = tvSettingsCategoryForEligibility(
            selectedCategory,
            diagnosticsState.profileEligible,
        )
        if (fallback == selectedCategory) return@LaunchedEffect
        selectedCategory = fallback
        // Swapping the model is not enough: the row (or detail control) holding
        // focus is the one that just left the rail, and Compose clears focus
        // rather than re-homing it, which leaves the remote with nothing to
        // move from. The fallback's row is always composed, so claim it here.
        categoryFocusRequesters.getValue(fallback)
            .claimFocusOrReport(target = "settings_category", action = "eligibility_fallback")
    }

    LaunchedEffect(detailFocusRequest) {
        if (detailFocusRequest > 0) {
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = detailFocusRequester::requestFocus,
                isFocused = { detailHasFocus },
            )
        }
    }

    BackHandler {
        if (detailHasFocus) {
            categoryFocusRequesters.getValue(selectedCategory)
                .claimFocusOrReport(target = "settings_category", action = "back_from_detail")
        } else {
            onNavigateHome()
        }
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
        diagnosticsState = diagnosticsState,
        diagnosticsViewModel = diagnosticsViewModel,
        homeSectionsViewModel = homeSectionsViewModel,
        selectedCategory = selectedCategory,
        categoryFocusRequesters = categoryFocusRequesters,
        detailFocusRequester = detailFocusRequester,
        onDetailFocusChanged = { detailHasFocus = it },
        onRailCategoryFocusChanged = { categoryColumnHasFocus = it },
        onCategorySelected = { selectedCategory = it },
        onEnterCategory = {
            selectedCategory = it
            detailFocusRequest += 1
        },
        onSwitchProfile = viewModel::onSwitchProfile,
        onManageServers = onManageServers,
        onOpenDiagnosticsReport = onOpenDiagnosticsReport,
        onRequestSignOut = { showSignOutConfirm = true },
        onQualityPresetSelected = viewModel::onQualityPresetSelected,
        onAudioLanguageChanged = viewModel::onAudioLanguageChanged,
        onAutoPlayNextChanged = viewModel::onAutoPlayNextChanged,
        onIntroSkipModeChanged = viewModel::onIntroSkipModeChanged,
        onAutoSkipCreditsChanged = viewModel::onAutoSkipCreditsChanged,
        onMatchContentFrameRateChanged = viewModel::onMatchContentFrameRateChanged,
        onDolbyVisionEnabledChanged = viewModel::onDolbyVisionEnabledChanged,
        onDvProfile7HDR10FallbackChanged = viewModel::onDvProfile7HDR10FallbackChanged,
        onResumeRewindSecondsChanged = viewModel::onResumeRewindSecondsChanged,
        onPassOutThresholdChanged = viewModel::onPassOutThresholdChanged,
        onNextUpPromptSecondsChanged = viewModel::onNextUpPromptSecondsChanged,
        onResetPlaybackOverrides = viewModel::resetPlaybackOverrides,
        onSubtitleModeChanged = viewModel::onSubtitleModeChanged,
        onSubtitleLanguageChanged = viewModel::onSubtitleLanguageChanged,
        onMetadataLanguageChanged = viewModel::onMetadataLanguageChanged,
        metadataLanguageEnabled = metadataAiStatus.enabled && metadataAiStatus.onView != org.siloserver.silo.model.metadata.MetadataAiOnView.Off,
        onShowForcedSubtitlesChanged = viewModel::onShowForcedSubtitlesChanged,
        onCardPresentationChanged = viewModel::onCardPresentationSelected,
        onCardPresentationDeviceOnlyChanged = viewModel::onCardPresentationDeviceOnlyChanged,
        onUseProfileCardDefault = viewModel::onUseProfileCardDefault,
        onSubtitleFontSizeChanged = viewModel::setSubtitleFontSize,
        onSubtitleFontFamilyChanged = viewModel::setSubtitleFontFamily,
        onSubtitleFontColorChanged = viewModel::setSubtitleFontColor,
        onSubtitleTextOutlineChanged = viewModel::setSubtitleTextOutline,
        onSubtitleTextOutlineColorChanged = viewModel::setSubtitleTextOutlineColor,
        onSubtitleBackgroundStyleChanged = viewModel::setSubtitleBackgroundStyle,
        onSubtitleBackgroundOpacityChanged = viewModel::setSubtitleBackgroundOpacity,
        onSubtitleBackgroundColorChanged = viewModel::setSubtitleBackgroundColor,
        onSubtitlePositionChanged = viewModel::setSubtitlePosition,
        onResetSubtitleAppearance = viewModel::resetSubtitleAppearance,
        onSubtitleDeviceOverrideEnabledChanged = viewModel::setSubtitleDeviceOverrideEnabled,
        onSubtitleMatchesDeviceChanged = viewModel::onSubtitleMatchesDeviceChanged,
        onShowAudiobooksTabChanged = viewModel::onShowAudiobooksTabChanged,
    )

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

internal enum class TvSettingsCategory(
    val title: String,
    val eyebrow: String,
    val blurb: String,
    val icon: ImageVector,
) {
    General(
        title = "General",
        eyebrow = "PREFERENCES",
        blurb = "App-level options for this Android TV.",
        icon = Icons.Filled.Settings,
    ),
    Playback(
        title = "Playback",
        eyebrow = "PREFERENCES",
        blurb = "Streaming, episode, and playback behavior for this device.",
        icon = Icons.Filled.PlayCircle,
    ),
    Subtitles(
        title = "Subtitles",
        eyebrow = "PREFERENCES",
        blurb = "Language, behavior, and subtitle appearance.",
        icon = Icons.Filled.ClosedCaption,
    ),
    // tvOS `TVSettingsCategory` puts Diagnostics fourth, ahead of Server, under
    // its own SUPPORT eyebrow. `stethoscope` has no Material twin; MonitorHeart
    // is the nearest "check the patient" glyph.
    Diagnostics(
        title = "Diagnostics",
        eyebrow = "SUPPORT",
        blurb = "Review and send diagnostics to this Silo server.",
        icon = Icons.Filled.MonitorHeart,
    ),
    Server(
        title = "Server",
        eyebrow = "CONNECTION",
        blurb = "Active server, device pairing, and account tools.",
        icon = Icons.Filled.Dns,
    ),
}

/**
 * tvOS `visibleCategories`: Diagnostics is hidden outright for a profile that
 * may not manage diagnostics (a kids profile, or a server that hides it).
 */
internal fun tvSettingsVisibleCategories(diagnosticsEligible: Boolean): List<TvSettingsCategory> =
    TvSettingsCategory.entries.filter {
        it != TvSettingsCategory.Diagnostics || diagnosticsEligible
    }

/**
 * tvOS `.onChange(of: shouldShowSettings)`: if the category being shown stops
 * being visible, fall back to General rather than leaving the pane rendering a
 * category the rail no longer offers.
 */
internal fun tvSettingsCategoryForEligibility(
    current: TvSettingsCategory,
    diagnosticsEligible: Boolean,
): TvSettingsCategory =
    if (current in tvSettingsVisibleCategories(diagnosticsEligible)) {
        current
    } else {
        TvSettingsCategory.General
    }

private val LocalSettingsDetailFocusReporter = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

// ---------------------------------------------------------------------------
// Split settings layout (tvOS parity)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun SettingsSplitLayout(
    state: TvSettingsViewModel.UiState,
    diagnosticsState: org.siloserver.silo.common.diagnostics.DiagnosticsUiState,
    diagnosticsViewModel: TvDiagnosticsViewModel,
    homeSectionsViewModel: HomeViewModel,
    selectedCategory: TvSettingsCategory,
    categoryFocusRequesters: Map<TvSettingsCategory, FocusRequester>,
    detailFocusRequester: FocusRequester,
    onDetailFocusChanged: (Boolean) -> Unit,
    // Observed arrival of the entry claim, so the shell handover below only
    // fires when a category actually took focus.
    onRailCategoryFocusChanged: (Boolean) -> Unit,
    onCategorySelected: (TvSettingsCategory) -> Unit,
    onEnterCategory: (TvSettingsCategory) -> Unit,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onManageServers: () -> Unit,
    onOpenDiagnosticsReport: (reportId: String) -> Unit,
    onRequestSignOut: () -> Unit,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onMetadataLanguageChanged: (String) -> Unit,
    metadataLanguageEnabled: Boolean,
    onShowForcedSubtitlesChanged: (Boolean) -> Unit,
    onCardPresentationChanged: (CardPresentation) -> Unit,
    onCardPresentationDeviceOnlyChanged: (Boolean) -> Unit,
    onUseProfileCardDefault: () -> Unit,
    onSubtitleFontSizeChanged: (SubtitleFontSizePreset) -> Unit,
    onSubtitleFontFamilyChanged: (String) -> Unit,
    onSubtitleFontColorChanged: (String) -> Unit,
    onSubtitleTextOutlineChanged: (Boolean) -> Unit,
    onSubtitleTextOutlineColorChanged: (String) -> Unit,
    onSubtitleBackgroundStyleChanged: (SubtitleBackgroundStylePreset) -> Unit,
    onSubtitleBackgroundOpacityChanged: (Int) -> Unit,
    onSubtitleBackgroundColorChanged: (String) -> Unit,
    onSubtitlePositionChanged: (SubtitlePositionPreset) -> Unit,
    onResetSubtitleAppearance: () -> Unit,
    onSubtitleDeviceOverrideEnabledChanged: (Boolean) -> Unit,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackground)
            // tvOS TVSettingsView: safeAreaX 88pt + HStack spacing 64pt, with a
            // 430pt rail — halved to Android dp. Full-screen surface (the shell
            // hides the top bar on this route), so only the safe-area inset.
            .padding(
                start = 44.dp,
                top = Spacing.safeArea,
                end = 44.dp,
                bottom = Spacing.xxxl,
            ),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsRail(
            state = state,
            visibleCategories = tvSettingsVisibleCategories(diagnosticsState.profileEligible),
            selectedCategory = selectedCategory,
            categoryFocusRequesters = categoryFocusRequesters,
            detailFocusRequester = detailFocusRequester,
            onCategorySelected = onCategorySelected,
            onEnterCategory = onEnterCategory,
            onRailCategoryFocused = {
                onDetailFocusChanged(false)
                onRailCategoryFocusChanged(true)
            },
            onSwitchProfile = onSwitchProfile,
            onRequestSignOut = onRequestSignOut,
            modifier = Modifier.width(200.dp),
        )
        SettingsDetailPane(
            state = state,
            diagnosticsState = diagnosticsState,
            diagnosticsViewModel = diagnosticsViewModel,
            homeSectionsViewModel = homeSectionsViewModel,
            selectedCategory = selectedCategory,
            detailFocusRequester = detailFocusRequester,
            onDetailFocusChanged = onDetailFocusChanged,
            onShowAudiobooksTabChanged = onShowAudiobooksTabChanged,
            onManageServers = onManageServers,
            onOpenDiagnosticsReport = onOpenDiagnosticsReport,
            onQualityPresetSelected = onQualityPresetSelected,
            onAudioLanguageChanged = onAudioLanguageChanged,
            onAutoPlayNextChanged = onAutoPlayNextChanged,
            onIntroSkipModeChanged = onIntroSkipModeChanged,
            onAutoSkipCreditsChanged = onAutoSkipCreditsChanged,
            onMatchContentFrameRateChanged = onMatchContentFrameRateChanged,
            onDolbyVisionEnabledChanged = onDolbyVisionEnabledChanged,
            onDvProfile7HDR10FallbackChanged = onDvProfile7HDR10FallbackChanged,
            onResumeRewindSecondsChanged = onResumeRewindSecondsChanged,
            onPassOutThresholdChanged = onPassOutThresholdChanged,
            onNextUpPromptSecondsChanged = onNextUpPromptSecondsChanged,
            onResetPlaybackOverrides = onResetPlaybackOverrides,
            onSubtitleModeChanged = onSubtitleModeChanged,
            onSubtitleLanguageChanged = onSubtitleLanguageChanged,
            onMetadataLanguageChanged = onMetadataLanguageChanged,
            metadataLanguageEnabled = metadataLanguageEnabled,
            onShowForcedSubtitlesChanged = onShowForcedSubtitlesChanged,
            onCardPresentationChanged = onCardPresentationChanged,
            onCardPresentationDeviceOnlyChanged = onCardPresentationDeviceOnlyChanged,
            onUseProfileCardDefault = onUseProfileCardDefault,
            onSubtitleFontSizeChanged = onSubtitleFontSizeChanged,
            onSubtitleFontFamilyChanged = onSubtitleFontFamilyChanged,
            onSubtitleFontColorChanged = onSubtitleFontColorChanged,
            onSubtitleTextOutlineChanged = onSubtitleTextOutlineChanged,
            onSubtitleTextOutlineColorChanged = onSubtitleTextOutlineColorChanged,
            onSubtitleBackgroundStyleChanged = onSubtitleBackgroundStyleChanged,
            onSubtitleBackgroundOpacityChanged = onSubtitleBackgroundOpacityChanged,
            onSubtitleBackgroundColorChanged = onSubtitleBackgroundColorChanged,
            onSubtitlePositionChanged = onSubtitlePositionChanged,
            onResetSubtitleAppearance = onResetSubtitleAppearance,
            onSubtitleDeviceOverrideEnabledChanged = onSubtitleDeviceOverrideEnabledChanged,
            onSubtitleMatchesDeviceChanged = onSubtitleMatchesDeviceChanged,
            // Contain Up at the pane's top row: escaping to the top menu from
            // inside a category was disorienting (QA 2026-07-08). Left still
            // exits to the category rail.
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onDetailFocusChanged(it.hasFocus) }
                .focusGroup()
                .focusProperties {
                    exit = { direction ->
                        when (direction) {
                            FocusDirection.Up -> FocusRequester.Cancel
                            FocusDirection.Left -> categoryFocusRequesters.getValue(selectedCategory)
                            else -> FocusRequester.Default
                        }
                    }
                },
        )
    }
}

@Composable
private fun SettingsRail(
    state: TvSettingsViewModel.UiState,
    visibleCategories: List<TvSettingsCategory>,
    selectedCategory: TvSettingsCategory,
    categoryFocusRequesters: Map<TvSettingsCategory, FocusRequester>,
    detailFocusRequester: FocusRequester,
    onCategorySelected: (TvSettingsCategory) -> Unit,
    onEnterCategory: (TvSettingsCategory) -> Unit,
    onRailCategoryFocused: () -> Unit,
    onSwitchProfile: () -> Unit,
    onRequestSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var railActionHasFocus by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayMedium.copy(fontSize = 22.sp, lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
        )
        SettingsAccountRow(
            name = state.profileName ?: state.user?.username ?: "-",
            subtitle = accountSubtitle(state),
            avatar = state.profileAvatar,
            onClick = onSwitchProfile,
        )
        Spacer(modifier = Modifier.height(9.dp))
        visibleCategories.forEach { category ->
            SettingsRailCategoryRow(
                category = category,
                selected = category == selectedCategory && !railActionHasFocus,
                onClick = { onEnterCategory(category) },
                // tvOS parity: focusing a category live-swaps the detail pane.
                onFocused = {
                    railActionHasFocus = false
                    onRailCategoryFocused()
                    onCategorySelected(category)
                },
                // Entry focus lands on General, not the profile row.
                focusRequester = categoryFocusRequesters.getValue(category),
                rightFocusRequester = detailFocusRequester,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        SettingsRailActionRow(
            label = "Sign Out",
            icon = Icons.AutoMirrored.Filled.Logout,
            onClick = onRequestSignOut,
            destructive = true,
            onFocused = { railActionHasFocus = true },
        )
        Text(
            text = "Silo ${clientVersionLabel(BuildConfig.DISPLAY_VERSION, BuildConfig.BUILD_NUMBER)}",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                letterSpacing = 1.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        )
    }
}

/**
 * tvOS `TVSettingsRailRowStyle` parity: rows rest transparent, the selected
 * category keeps a soft white fill + hairline border while unfocused, and the
 * focused row inverts to the white platter.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRailCategoryRow(
    category: TvSettingsCategory,
    selected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = if (isFocused) FocusedContent else Color.White
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) {
                Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = RowShape)
            } else {
                Border.None
            },
            focusedBorder = Border.None,
            pressedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .fillMaxWidth()
            .height(38.dp)
            .focusProperties {
                right = rightFocusRequester ?: FocusRequester.Default
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = foreground,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Rail action row (Sign Out) — same transparent rest chrome as categories. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRailActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = when {
        isFocused && destructive -> DestructiveRedOnPlatter
        isFocused -> FocusedContent
        destructive -> DestructiveRed
        else -> Color.White
    }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = foreground,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsDetailPane(
    state: TvSettingsViewModel.UiState,
    diagnosticsState: org.siloserver.silo.common.diagnostics.DiagnosticsUiState,
    diagnosticsViewModel: TvDiagnosticsViewModel,
    homeSectionsViewModel: HomeViewModel,
    selectedCategory: TvSettingsCategory,
    detailFocusRequester: FocusRequester,
    onDetailFocusChanged: (Boolean) -> Unit,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onManageServers: () -> Unit,
    onOpenDiagnosticsReport: (reportId: String) -> Unit,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    onSubtitleModeChanged: (SubtitleMode) -> Unit,
    onSubtitleLanguageChanged: (String) -> Unit,
    onMetadataLanguageChanged: (String) -> Unit,
    metadataLanguageEnabled: Boolean,
    onShowForcedSubtitlesChanged: (Boolean) -> Unit,
    onCardPresentationChanged: (CardPresentation) -> Unit,
    onCardPresentationDeviceOnlyChanged: (Boolean) -> Unit,
    onUseProfileCardDefault: () -> Unit,
    onSubtitleFontSizeChanged: (SubtitleFontSizePreset) -> Unit,
    onSubtitleFontFamilyChanged: (String) -> Unit,
    onSubtitleFontColorChanged: (String) -> Unit,
    onSubtitleTextOutlineChanged: (Boolean) -> Unit,
    onSubtitleTextOutlineColorChanged: (String) -> Unit,
    onSubtitleBackgroundStyleChanged: (SubtitleBackgroundStylePreset) -> Unit,
    onSubtitleBackgroundOpacityChanged: (Int) -> Unit,
    onSubtitleBackgroundColorChanged: (String) -> Unit,
    onSubtitlePositionChanged: (SubtitlePositionPreset) -> Unit,
    onResetSubtitleAppearance: () -> Unit,
    onSubtitleDeviceOverrideEnabledChanged: (Boolean) -> Unit,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalSettingsDetailFocusReporter provides onDetailFocusChanged) {
      Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = selectedCategory.eyebrow,
            style = SettingsMonoHeaderStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = selectedCategory.title,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 20.sp, lineHeight = 24.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            text = selectedCategory.blurb,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp, bottom = 14.dp),
        )

        when (selectedCategory) {
            TvSettingsCategory.General -> TvGeneralSettingsPane(
                state = state,
                homeSectionsViewModel = homeSectionsViewModel,
                firstFocusRequester = detailFocusRequester,
                onShowAudiobooksTabChanged = onShowAudiobooksTabChanged,
                onCardPresentationChanged = onCardPresentationChanged,
                onCardPresentationDeviceOnlyChanged = onCardPresentationDeviceOnlyChanged,
                onUseProfileCardDefault = onUseProfileCardDefault,
            )
            TvSettingsCategory.Playback -> TvPlaybackSettingsPane(
                state = state,
                firstFocusRequester = detailFocusRequester,
                onQualityPresetSelected = onQualityPresetSelected,
                onAudioLanguageChanged = onAudioLanguageChanged,
                onAutoPlayNextChanged = onAutoPlayNextChanged,
                onIntroSkipModeChanged = onIntroSkipModeChanged,
                onAutoSkipCreditsChanged = onAutoSkipCreditsChanged,
            onMatchContentFrameRateChanged = onMatchContentFrameRateChanged,
            onDolbyVisionEnabledChanged = onDolbyVisionEnabledChanged,
            onDvProfile7HDR10FallbackChanged = onDvProfile7HDR10FallbackChanged,
                onResumeRewindSecondsChanged = onResumeRewindSecondsChanged,
                onPassOutThresholdChanged = onPassOutThresholdChanged,
                onNextUpPromptSecondsChanged = onNextUpPromptSecondsChanged,
                onResetPlaybackOverrides = onResetPlaybackOverrides,
            )
            TvSettingsCategory.Subtitles -> TvSubtitleSettingsPane(
                state = state,
                firstFocusRequester = detailFocusRequester,
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
                onResetSubtitleAppearance = onResetSubtitleAppearance,
                onSubtitleDeviceOverrideEnabledChanged = onSubtitleDeviceOverrideEnabledChanged,
            onSubtitleMatchesDeviceChanged = onSubtitleMatchesDeviceChanged,
            )
            TvSettingsCategory.Diagnostics -> TvDiagnosticsSettingsPane(
                state = diagnosticsState,
                serverName = state.serverName,
                firstFocusRequester = detailFocusRequester,
                onSetDestination = diagnosticsViewModel::setDestination,
                onSetConsent = diagnosticsViewModel::setConsent,
                onSetDebugLogging = diagnosticsViewModel::setDebugLogging,
                onCaptureNow = { diagnosticsViewModel.captureNow(onOpenDiagnosticsReport) },
                onStartTimedCapture = diagnosticsViewModel::startTimedCapture,
                onStopTimedCapture = {
                    diagnosticsViewModel.stopTimedCapture(onOpenDiagnosticsReport)
                },
                onCancelTimedCapture = diagnosticsViewModel::cancelTimedCapture,
                onReportSelected = onOpenDiagnosticsReport,
            )
            TvSettingsCategory.Server -> TvServerSettingsPane(
                state = state,
                firstFocusRequester = detailFocusRequester,
                onManageServers = onManageServers,
            )
        }
      }
    }
}

@Composable
private fun TvGeneralSettingsPane(
    state: TvSettingsViewModel.UiState,
    homeSectionsViewModel: HomeViewModel,
    firstFocusRequester: FocusRequester,
    onShowAudiobooksTabChanged: (Boolean) -> Unit,
    onCardPresentationChanged: (CardPresentation) -> Unit,
    onCardPresentationDeviceOnlyChanged: (Boolean) -> Unit,
    onUseProfileCardDefault: () -> Unit,
) {
    var activeCardPicker by remember { mutableStateOf<CardPresentationPicker?>(null) }
    var showHomeSectionsEditor by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        item {
            // Device-local, server/profile-specific visibility and order for
            // the populated rows returned by Home, matching tvOS General.
            SettingsGroup(title = "Home Sections") {
                SettingsValueRow(
                    label = "Home Sections",
                    value = "",
                    onClick = { showHomeSectionsEditor = true },
                    focusRequester = firstFocusRequester,
                )
                SettingsFooterText(
                    text = "Choose which Home rows are visible and edit the order in which they appear on this Android TV.",
                )
            }
        }
        item {
            // tvOS General → CARDS & POSTERS parity, backed by the
            // server-driven `ui.card_presentation` setting. Unknown support
            // (offline probe) keeps the controls up over the cached value;
            // only a confirmed too-old server hides them.
            SettingsGroup(title = "Cards & Posters") {
                if (state.cardPresentationSupport == CardPresentationSupport.Unsupported) {
                    SettingsFooterText(
                        text = "Update your Silo server to customize media cards.",
                    )
                } else {
                    SettingsValueRow(
                        label = "Preset",
                        value = state.cardPresentation.preset?.displayName ?: "Custom",
                        onClick = { activeCardPicker = CardPresentationPicker.Preset },
                    )
                    SettingsValueRow(
                        label = "Poster Size",
                        value = state.cardPresentation.posterSize.displayName,
                        onClick = { activeCardPicker = CardPresentationPicker.PosterSize },
                    )
                    SettingsValueRow(
                        label = "Captions",
                        value = state.cardPresentation.caption.displayName,
                        onClick = { activeCardPicker = CardPresentationPicker.Captions },
                    )
                    SettingsToggleRow(
                        label = "Only This Device",
                        checked = state.cardPresentationSource ==
                            CardPresentationSource.DeviceOverride,
                        onCheckedChange = onCardPresentationDeviceOnlyChanged,
                    )
                    if (state.cardPresentationSource == CardPresentationSource.ClientFamily) {
                        SettingsActionRow(
                            label = "Use Profile Default",
                            onClick = onUseProfileCardDefault,
                        )
                    }
                    SettingsFooterText(
                        text = "Choices sync with other TVs signed into this profile " +
                            "unless \"Only This Device\" is on.",
                    )
                }
            }
        }
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
                SettingsFooterText(
                    text = "Adds an Audiobooks tab to the top menu when your server has an audiobook library. Hidden by default.",
                )
            }
        }
        // No Library group — tvOS parity: Apple's TVSettingsView has no such
        // section (it is iOS-only). On TV these destinations live in the
        // For You dropdown (Watchlist/Favorites), the profile menu
        // (Watchlist/Favorites/History), Home (Browse), and each library's
        // cascade (Collections). (QA 2026-07-08.)
    }

    when (activeCardPicker) {
        CardPresentationPicker.Preset -> TvSettingsPickerSheet(
            title = "Preset",
            // Synthetic "Custom" appears only while the current pair matches
            // no preset; it is a label for the state, not a choice.
            options = buildList {
                CardPresentationPreset.entries.forEach {
                    add(PickerOption(it.name, it.displayName))
                }
                if (state.cardPresentation.preset == null) {
                    add(PickerOption(CustomCardPresetId, "Custom"))
                }
            },
            selectedId = state.cardPresentation.preset?.name ?: CustomCardPresetId,
            onSelect = { id ->
                CardPresentationPreset.entries.firstOrNull { it.name == id }
                    ?.let { onCardPresentationChanged(it.presentation) }
                activeCardPicker = null
            },
            onDismiss = { activeCardPicker = null },
        )
        CardPresentationPicker.PosterSize -> TvSettingsPickerSheet(
            title = "Poster Size",
            options = CardPosterSize.entries.map { PickerOption(it.raw, it.displayName) },
            selectedId = state.cardPresentation.posterSize.raw,
            onSelect = { id ->
                CardPosterSize.fromRaw(id)?.let {
                    onCardPresentationChanged(state.cardPresentation.copy(posterSize = it))
                }
                activeCardPicker = null
            },
            onDismiss = { activeCardPicker = null },
        )
        CardPresentationPicker.Captions -> TvSettingsPickerSheet(
            title = "Captions",
            options = CardCaption.entries.map { PickerOption(it.raw, it.displayName) },
            selectedId = state.cardPresentation.caption.raw,
            onSelect = { id ->
                CardCaption.fromRaw(id)?.let {
                    onCardPresentationChanged(state.cardPresentation.copy(caption = it))
                }
                activeCardPicker = null
            },
            onDismiss = { activeCardPicker = null },
        )
        null -> Unit
    }

    if (showHomeSectionsEditor) {
        TvHomeSectionsEditor(
            onDismiss = { showHomeSectionsEditor = false },
            viewModel = homeSectionsViewModel,
        )
    }
}

private enum class CardPresentationPicker {
    Preset,
    PosterSize,
    Captions,
}

/** Picker id for the synthetic "Custom" preset row (never on the wire). */
private const val CustomCardPresetId = "custom"

@Composable
private fun TvPlaybackSettingsPane(
    state: TvSettingsViewModel.UiState,
    firstFocusRequester: FocusRequester,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onMatchContentFrameRateChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
) {
    var activePicker by remember { mutableStateOf<PlaybackPicker?>(null) }
    val audioLanguages = remember(state.audioLanguage, state.audioLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
            currentValue = state.audioLanguage,
            runtimeValues = state.audioLanguageSuggestions,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        item {
            SettingsGroup(title = "Streaming") {
                SettingsValueRow(
                    label = "Quality",
                    value = QualityPresets.describe(state.qualityResolution, state.maxBitrateKbps),
                    onClick = { activePicker = PlaybackPicker.Quality },
                    focusRequester = firstFocusRequester,
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
                    label = "Match Content Frame Rate",
                    checked = state.matchContentFrameRate,
                    onCheckedChange = onMatchContentFrameRateChanged,
                )
            }
        }
        item {
            SettingsGroup(title = "Audio") {
                SettingsValueRow(
                    label = "Preferred Audio Language",
                    value = LanguageOptions.label(
                        state.audioLanguage,
                        SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
                    ),
                    onClick = { activePicker = PlaybackPicker.AudioLanguage },
                )
                SettingsInfoRow(
                    label = "Audio Quality",
                    value = "Best Compatible",
                )
                SettingsFooterText(
                    text = "Uses the preferred language when available, then English. " +
                        "Within that language, Silo chooses the highest-quality track " +
                        "supported by this TV and its current audio output.",
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
                // Three-way, not a switch: the schema's recommended control
                // is a select and TV has no segmented control, so this uses the
                // same value row + picker sheet every other enum here does.
                SettingsValueRow(
                    label = stringResource(R.string.settings_intro_skip_title),
                    value = stringResource(introSkipModeLabel(state.introSkipMode)),
                    onClick = { activePicker = PlaybackPicker.IntroSkipMode },
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
                SettingsFooterText(
                    text = "Resets playback choices for this Android TV and profile back to the server fallback.",
                )
            }
        }
    }

    when (activePicker) {
        // The picker offers presets; a stored pair no preset covers (set
        // through the API, or left by a legacy compound value) selects
        // nothing rather than silently highlighting the wrong entry.
        PlaybackPicker.Quality -> TvSettingsPickerSheet(
            title = "Quality",
            options = QualityPresets.ALL.map { PickerOption(it.id, it.label) },
            selectedId = QualityPresets.presetFor(state.qualityResolution, state.maxBitrateKbps)?.id
                ?: "",
            onSelect = { id ->
                onQualityPresetSelected(id)
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
        PlaybackPicker.AudioLanguage -> TvSettingsPickerSheet(
            title = "Preferred Audio Language",
            options = audioLanguages.map { PickerOption(it.first, it.second) },
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
        // Three short options: a compact popup over the settings list, not the
        // full-screen picker the longer lists use.
        PlaybackPicker.IntroSkipMode -> TvOptionDialog(
            title = stringResource(R.string.settings_intro_skip_title),
            options = IntroSkipMode.entries.map { mode ->
                TvDialogOption(
                    key = mode.wireValue,
                    title = stringResource(introSkipModeLabel(mode)),
                    selected = mode == state.introSkipMode,
                    onClick = {
                        onIntroSkipModeChanged(mode)
                        activePicker = null
                    },
                )
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
    firstFocusRequester: FocusRequester,
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
    onResetSubtitleAppearance: () -> Unit,
    onSubtitleDeviceOverrideEnabledChanged: (Boolean) -> Unit,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
) {
    var activePicker by remember { mutableStateOf<SubtitlePicker?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val appearance = state.subtitleAppearance
    val subtitleLanguages = remember(
        state.subtitleLanguage,
        state.subtitleLanguageSuggestions,
    ) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = state.subtitleLanguage,
            runtimeValues = state.subtitleLanguageSuggestions,
        )
    }
    val metadataLanguages = remember(
        state.metadataLanguage,
        state.metadataLanguageSuggestions,
    ) {
        LanguageOptions.options(
            key = SettingKeys.CATALOG_METADATA_LANGUAGE,
            currentValue = state.metadataLanguage,
            runtimeValues = state.metadataLanguageSuggestions,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = Spacing.xxxl),
    ) {
        if (state.settingsAvailability ==
            ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED
        ) {
            item { TvSettingsUpgradeRequiredNotice() }
        }
        item {
            SettingsGroup(title = "Profile") {
                SettingsValueRow(
                    label = "Mode",
                    value = state.subtitleMode.label,
                    onClick = { activePicker = SubtitlePicker.Mode },
                    focusRequester = firstFocusRequester,
                )
                SettingsValueRow(
                    label = "Language",
                    value = LanguageOptions.label(
                        state.subtitleLanguage,
                        SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
                    ),
                    onClick = { activePicker = SubtitlePicker.Language },
                )
                if (metadataLanguageEnabled) {
                    SettingsValueRow(
                        label = "Metadata Language",
                        value = LanguageOptions.label(
                            state.metadataLanguage,
                            SettingKeys.CATALOG_METADATA_LANGUAGE,
                        ),
                        onClick = { activePicker = SubtitlePicker.MetadataLanguage },
                    )
                }
                SettingsToggleRow(
                    label = "Show Forced Subtitles",
                    checked = state.showForcedSubtitles,
                    onCheckedChange = onShowForcedSubtitlesChanged,
                )
                SettingsFooterText(
                    text = "Used to pick a matching track when one is available. Forced subtitles cover " +
                        "foreign-language dialogue even when subtitles are off or set to auto.",
                )
            }
        }
        item {
            SettingsGroup(title = "Appearance") {
                TvSettingsSubtitlePreview(state.effectiveSubtitleAppearance)
                SettingsToggleRow(
                    // tvOS parity: appearance follows the OS captioning
                    // settings while this is on.
                    label = "Use System Caption Style",
                    checked = state.subtitleMatchesDevice,
                    onCheckedChange = onSubtitleMatchesDeviceChanged,
                )
                SettingsToggleRow(
                    label = "Custom Subtitle Appearance",
                    checked = state.subtitleUsesDeviceOverride,
                    onCheckedChange = onSubtitleDeviceOverrideEnabledChanged,
                )
                SettingsNestedGroup(enabled = state.subtitleUsesDeviceOverride) {
                    SettingsValueRow(
                        label = "Font Size",
                        value = TvSubtitleAppearanceOptions.fontSizeLabel(appearance.fontSize),
                        onClick = { activePicker = SubtitlePicker.FontSize },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Font Family",
                        value = TvSubtitleAppearanceOptions.fontFamilyLabel(appearance.fontFamily),
                        onClick = { activePicker = SubtitlePicker.FontFamily },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Font Color",
                        value = TvSubtitleAppearanceOptions.fontColorLabel(appearance.fontColor),
                        onClick = { activePicker = SubtitlePicker.FontColor },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsToggleRow(
                        label = "Text Outline",
                        checked = appearance.textOutline,
                        onCheckedChange = onSubtitleTextOutlineChanged,
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Outline Color",
                        value = TvSubtitleAppearanceOptions.outlineColorLabel(appearance.textOutlineColor),
                        onClick = { activePicker = SubtitlePicker.OutlineColor },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Background Style",
                        value = TvSubtitleAppearanceOptions.backgroundStyleLabel(appearance.backgroundStyle),
                        onClick = { activePicker = SubtitlePicker.BackgroundStyle },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Background Opacity",
                        value = "${appearance.backgroundOpacity}%",
                        onClick = { activePicker = SubtitlePicker.BackgroundOpacity },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Background Color",
                        value = TvSubtitleAppearanceOptions.backgroundColorLabel(appearance.backgroundColor),
                        onClick = { activePicker = SubtitlePicker.BackgroundColor },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsValueRow(
                        label = "Position",
                        value = TvSubtitleAppearanceOptions.positionLabel(appearance.position),
                        onClick = { activePicker = SubtitlePicker.Position },
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                    SettingsActionRow(
                        label = "Reset Custom Appearance",
                        onClick = { showResetConfirmation = true },
                        destructive = true,
                        enabled = state.subtitleUsesDeviceOverride,
                    )
                }
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
            options = subtitleLanguages.map { PickerOption(it.first, it.second) },
            selectedId = state.subtitleLanguage,
            onSelect = { onSubtitleLanguageChanged(it); activePicker = null },
            onDismiss = { activePicker = null },
        )
        SubtitlePicker.MetadataLanguage -> TvSettingsPickerSheet(
            title = "Metadata Language",
            options = metadataLanguages.map { PickerOption(it.first, it.second) },
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

    if (showResetConfirmation) {
        TvSettingsConfirmDialog(
            title = "Reset Custom Appearance?",
            message = "This restores all custom subtitle appearance options to their defaults.",
            confirmLabel = "Reset",
            onConfirm = {
                showResetConfirmation = false
                onResetSubtitleAppearance()
            },
            onDismiss = { showResetConfirmation = false },
        )
    }
}

/** Live approximation of the effective subtitle style, matching tvOS Settings. */
@Composable
private fun TvSettingsSubtitlePreview(appearance: SubtitleAppearance) {
    val safe = appearance.sanitized()
    val alignment = when (safe.position) {
        SubtitlePositionPreset.Top -> Alignment.TopCenter
        SubtitlePositionPreset.LowerThird -> Alignment.BottomCenter
        SubtitlePositionPreset.Bottom -> Alignment.BottomCenter
    }
    val bottomPadding = if (safe.position == SubtitlePositionPreset.LowerThird) 18.dp else 7.dp
    val fontFamily = when (safe.fontFamily.lowercase()) {
        SubtitleAppearance.SERIF -> FontFamily.Serif
        SubtitleAppearance.MONOSPACE -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val fontSize = (safe.fontSize.pointSize * 0.36).sp
    val foreground = settingsHexColor(safe.fontColor)
    val outline = settingsHexColor(safe.textOutlineColor)
    val showOutline = safe.textOutline || safe.backgroundStyle == SubtitleBackgroundStylePreset.Outline
    val boxColor = settingsHexColor(safe.backgroundColor).copy(
        alpha = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) {
            safe.backgroundOpacity.coerceIn(0, 100) / 100f
        } else {
            0f
        },
    )

    Box(
        modifier = Modifier
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .height(76.dp)
            .padding(bottom = 7.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF55575A), Color(0xFF101113)),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .clip(RoundedCornerShape(3.dp))
                .background(boxColor)
                .padding(
                    horizontal = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) 7.dp else 0.dp,
                    vertical = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Box) 2.dp else 0.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (showOutline) {
                listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).forEach { (x, y) ->
                    Text(
                        text = SubtitlePreviewText,
                        color = outline,
                        fontFamily = fontFamily,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x.dp, y.dp),
                    )
                }
            }
            Text(
                text = SubtitlePreviewText,
                color = foreground,
                fontFamily = fontFamily,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TvServerSettingsPane(
    state: TvSettingsViewModel.UiState,
    firstFocusRequester: FocusRequester,
    onManageServers: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                SettingsActionRow(
                    label = "Manage Servers",
                    onClick = onManageServers,
                    focusRequester = firstFocusRequester,
                )
            }
        }
        // Diagnostics used to hang off this pane as a "Diagnostics & Crash
        // Reports" drill-in to a route outside the shell. It is its own
        // category now (tvOS parity), so nothing here points at it.
        item {
            SettingsGroup(title = "About") {
                // Same "1.0.0 (5)" form as the phone About row, so a TV support
                // report names the build the server's admin Activity page shows.
                SettingsInfoRow(
                    label = "Version",
                    value = clientVersionLabel(BuildConfig.DISPLAY_VERSION, BuildConfig.BUILD_NUMBER),
                )
            }
        }
    }
}

private const val SubtitlePreviewText = "Subtitles will look like this"

private fun settingsHexColor(value: String): Color = runCatching {
    val normalized = value.trim().removePrefix("#")
    Color(0xFF000000.toInt() or (normalized.toLong(16).toInt() and 0x00FFFFFF))
}.getOrElse { Color.White }

// tvOS `accountSubtitle` parity: "Administrator" for admins, else the
// username, else a generic signed-in line.
private fun accountSubtitle(state: TvSettingsViewModel.UiState): String {
    val role = state.user?.role?.trim().orEmpty()
    if (role.equals("admin", ignoreCase = true)) return "Administrator"
    return state.user?.username?.takeIf { it.isNotBlank() } ?: "Signed in"
}

private enum class PlaybackPicker {
    Quality,
    AudioLanguage,
    NextUpPrompt,
    IntroSkipMode,
    ResumeRewind,
    PassOutThreshold,
}

/** The label each intro-skip mode is offered under. The copy is fixed by the contract. */
@StringRes
private fun introSkipModeLabel(mode: IntroSkipMode): Int = when (mode) {
    IntroSkipMode.NEVER -> R.string.settings_intro_skip_never
    IntroSkipMode.ASK -> R.string.settings_intro_skip_ask
    IntroSkipMode.ALWAYS -> R.string.settings_intro_skip_always
}

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
    var pickerHasFocus by remember { mutableStateOf(false) }
    val focusTargetIndex = if (options.isEmpty()) -1 else selectedIndex
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(title, selectedId) {
        if (focusTargetIndex >= 0) {
            runCatching { listState.scrollToItem(focusTargetIndex) }
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = initialFocus::requestFocus,
                isFocused = { pickerHasFocus },
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // tvOS presents pickers as a fullScreenCover over the opaque app
        // background with a leading nav title and a centered option column.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { pickerHasFocus = it.hasFocus }
                .background(Color.Black.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 19.sp, lineHeight = 23.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(420.dp),
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
            containerColor = Color.White.copy(alpha = 0.07f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)), shape = shape),
            focusedBorder = Border.None,
            pressedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // tvOS TVSettingsPickerOptionRow: the checkmark leads and always
            // reserves its slot so option labels stay aligned.
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier
                    .size(15.dp)
                    .alpha(if (selected) 1f else 0f),
            )
            Text(
                text = option.label,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 15.sp, lineHeight = 18.sp),
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Confirm dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvSettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Default focus lands on Cancel so a stray OK press never triggers the
    // destructive action.
    val cancelFocus = remember { FocusRequester() }
    var confirmHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = cancelFocus::requestFocus,
            isFocused = { confirmHasFocus },
        )
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
                        focusRequester = cancelFocus,
                    )
                    DialogButton(
                        label = confirmLabel,
                        onClick = onConfirm,
                        destructive = true,
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

/**
 * tvOS `TVSettingsSectionHeader` parity: small mono uppercase section label
 * (size 15pt mono semibold, tracking 2) above tightly packed rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SettingsGroupRowSpacing)) {
        Text(
            text = title.uppercase(),
            style = SettingsMonoHeaderStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
        )
        content()
    }
}

@Composable
private fun SettingsNestedGroup(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .padding(start = 14.dp, top = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.025f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
            .padding(6.dp)
            .alpha(if (enabled) 1f else 0.42f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsMonoHeaderStyle() =
    MaterialTheme.typography.labelMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.SemiBold,
    )

/** Shared 16sp text for all detail-pane row labels and values. */
@Composable
private fun SettingsRowTextStyle() =
    MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 20.sp)

private val RowShape = RoundedCornerShape(10.dp)
private val RowMaxWidth = 520.dp

/**
 * Gap between the rows (and the trailing footer) inside one [SettingsGroup].
 *
 * Exposed rather than inlined because a pane that asks a focused row to pull
 * its group's footer into view has to add this gap to the footer's measured
 * height — see `TvDiagnosticsSettingsPane`. Two copies of the number would
 * silently drift.
 */
internal val SettingsGroupRowSpacing = 6.dp
// 42dp keeps the 16sp row text comfortably centered — audit 2026-07-20.
private val RowHeight = 42.dp

/** The one settings-surface ground color. Shared so no screen re-hardcodes it. */
internal val SettingsBackground = Color(0xFF17181A)

// tvOS destructive row colors: bright red at rest on black, deeper red on the
// focused white platter (TVSettingsRailRowStyle).
private val DestructiveRed = Color(0xFFD22F3F)
private val DestructiveRedOnPlatter = Color(0xFFB00020)

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
        // tvOS rail parity: the profile row rests transparent (no card fill)
        // and only inverts to the platter on focus.
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFocused) FocusedContent.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.12f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                    color = if (isFocused) FocusedContent else Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFocused) FocusedContent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsValueRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reportDetailFocus = LocalSettingsDetailFocusReporter.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        border = invertedRowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        // widthIn must precede fillMaxWidth: as the outer constraint it caps
        // the row at RowMaxWidth, and fillMaxWidth then stretches to that cap
        // (the reverse order lets fillMaxWidth's fixed constraints win).
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .height(RowHeight)
            .onFocusChanged { if (it.isFocused) reportDetailFocus(true) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                style = SettingsRowTextStyle(),
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = SettingsRowTextStyle(),
                    color = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.55f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reportDetailFocus = LocalSettingsDetailFocusReporter.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        border = invertedRowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        // widthIn must precede fillMaxWidth: as the outer constraint it caps
        // the row at RowMaxWidth, and fillMaxWidth then stretches to that cap
        // (the reverse order lets fillMaxWidth's fixed constraints win).
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .height(RowHeight)
            .onFocusChanged { if (it.isFocused) reportDetailFocus(true) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = SettingsRowTextStyle(),
                color = when {
                    isFocused && destructive -> DestructiveRedOnPlatter
                    isFocused -> FocusedContent
                    destructive -> DestructiveRed
                    else -> Color.White
                },
                modifier = Modifier.weight(1f),
            )
            if (!destructive) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = (if (isFocused) FocusedContent else Color.White).copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val reportDetailFocus = LocalSettingsDetailFocusReporter.current
    Surface(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RowShape),
        colors = invertedRowColors(),
        border = invertedRowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        // widthIn must precede fillMaxWidth: as the outer constraint it caps
        // the row at RowMaxWidth, and fillMaxWidth then stretches to that cap
        // (the reverse order lets fillMaxWidth's fixed constraints win).
        modifier = modifier
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .height(RowHeight)
            .onFocusChanged { if (it.isFocused) reportDetailFocus(true) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = SettingsRowTextStyle(),
                color = if (isFocused) FocusedContent else Color.White,
                modifier = Modifier.weight(1f),
            )
            // tvOS TVSettingsToggleRow: the state reads as text — semibold and
            // near-opaque when on, faded when off. No accent color.
            Text(
                text = if (checked) "On" else "Off",
                style = SettingsRowTextStyle(),
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = (if (isFocused) FocusedContent else Color.White)
                    .copy(alpha = if (checked) 0.9f else 0.55f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsInfoRow(label: String, value: String, singleLine: Boolean = true) {
    Row(
        modifier = Modifier
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .let { if (singleLine) it.height(RowHeight) else it.heightIn(min = RowHeight) }
            .clip(RowShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RowShape)
            .padding(horizontal = 16.dp, vertical = if (singleLine) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = SettingsRowTextStyle(),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = SettingsRowTextStyle(),
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

/** Non-focusable explanatory footer below a settings group (tvOS `TVSettingsFooter`). */
/**
 * Shown when the connected server predates the canonical settings API.
 *
 * The failure mode this replaces was a settings pane that looked normal but
 * saved nothing: the profile preferences resolve to nothing, so the rows show
 * defaults and every edit goes nowhere with no explanation. Playback is
 * unaffected — it runs from this device's own settings.
 */
@Composable
private fun TvSettingsUpgradeRequiredNotice() {
    SettingsGroup(title = "Server Update Needed") {
        SettingsFooterText(
            text = "This server is too old to store profile settings. Subtitle and metadata " +
                "preferences below will not save until it is updated. Playback still works " +
                "using this Android TV's own settings.",
        )
    }
}

@Composable
internal fun SettingsFooterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        // The modifier goes outermost so a caller measuring this footer sees the
        // laid-out block, not the text before its width cap and padding apply.
        modifier = modifier
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun invertedRowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.07f),
    contentColor = Color.White,
    focusedContainerColor = FocusedContainer,
    focusedContentColor = FocusedContent,
    pressedContainerColor = FocusedContainer,
    pressedContentColor = FocusedContent,
)

/**
 * tvOS `TVSettingsPaneRowStyle` parity: resting rows carry a hairline
 * `white 0.09` border over the `white 0.07` fill; the focused platter drops it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun invertedRowBorder() = ClickableSurfaceDefaults.border(
    border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)), shape = RowShape),
    focusedBorder = Border.None,
    pressedBorder = Border.None,
)

// ---------------------------------------------------------------------------
// Option data + value formatting
// ---------------------------------------------------------------------------

// Discrete choices for the F1/F2 behavior settings (0 = off).
private val ResumeRewindOptions = listOf(0, 3, 5, 7, 10, 15, 20, 30)
private val PassOutThresholdOptions = listOf(0, 2, 3, 4, 5)

// Up-Next prompt timing (seconds before end; 0 = at end). Mirrors tvOS.
private val NextUpPromptOptions = listOf(0, 10, 30, 60, 120)

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
