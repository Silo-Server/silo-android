package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.android.ui.screens.home.HomeSectionsEditor
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.android.ui.screens.settings.diagnostics.shouldShowDiagnosticsEntry
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloBorder
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloMutedText
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainerHigh
import org.siloserver.silo.android.ui.theme.siloRowTopDivider
import org.siloserver.silo.android.ui.util.formatBytes
import org.siloserver.silo.model.download.DownloadQuality
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView

/**
 * Main settings screen organized in grouped sections.
 *
 * This screen is used as tab content within MainScreen (Settings tab)
 * and does NOT have its own top bar back button since it's a tab root.
 *
 * @param onLoggedOut Called after the user signs out.
 * @param showTopBar Whether to show the top bar (false when inside MainScreen tab).
 * @param onBackClick Back navigation handler for standalone mode.
 */
@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit,
    onNavigateToServers: () -> Unit = {},
    onPairDevice: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    showTopBar: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
    downloadsViewModel: DownloadsViewModel = koinViewModel(),
    diagnosticsViewModel: DiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var subtitleStyleVisible by remember { mutableStateOf(false) }
    org.siloserver.silo.android.ui.screens.player.SubtitleStyleSheet(
        isVisible = subtitleStyleVisible,
        appearance = state.subtitleAppearance,
        onUpdate = viewModel::setSubtitleAppearance,
        onDismiss = { subtitleStyleVisible = false },
    )
    val downloadsState by downloadsViewModel.uiState.collectAsState()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    var showRemoveAllDownloadsConfirm by remember { mutableStateOf(false) }
    var showHomeSectionsEditor by remember { mutableStateOf(false) }

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            viewModel.onLogoutConsumed()
            onLoggedOut()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                SiloTopBar(
                    title = "Settings",
                    onBackClick = onBackClick,
                    containerColor = SiloSettingsBackground,
                )
            }
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(SettingsDimens.pageGutter),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.sectionGap),
        ) {
            item {
                if (!showTopBar) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.displayMedium,
                        color = SiloForeground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            top = SettingsDimens.pageTopPadding,
                            bottom = SettingsDimens.headerStartInset,
                        ),
                    )
                }
            }

            item {
                AccountSection(
                    onSwitchProfile = onSwitchProfile,
                    user = state.user,
                    isLoadingUser = state.isLoadingUser,
                    onPairDevice = onPairDevice,
                    onSignOut = viewModel::logout,
                )
            }

            if (shouldShowDiagnosticsEntry(diagnosticsState)) {
                item {
                    SettingsSectionCard {
                        SettingsNavigationRow(
                            label = "Diagnostics",
                            description = "Capture and review a report when something goes wrong.",
                            value = when (diagnosticsState.availability) {
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.AVAILABLE -> "Available"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.DISABLED -> "Disabled"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Unavailable"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.OFFLINE -> "Offline"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.INELIGIBLE -> null
                            },
                            onClick = onNavigateToDiagnostics,
                        )
                    }
                }
            }

            if (state.settingsAvailability ==
                org.siloserver.silo.domain.settings.ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED
            ) {
                item { SettingsUpgradeRequiredNotice() }
            }

            item {
                PlaybackSettings(
                    qualityResolution = state.qualityResolution,
                    maxBitrateKbps = state.maxBitrateKbps,
                    audioLanguage = state.audioLanguage,
                    audioLanguageSuggestions = state.audioLanguageSuggestions,
                    introSkipMode = state.introSkipMode,
                    autoSkipCredits = state.autoSkipCredits,
                    pictureInPictureEnabled = state.pictureInPictureEnabled,
                    dolbyVisionEnabled = state.dolbyVisionEnabled,
                    dvProfile7HDR10Fallback = state.dvProfile7HDR10Fallback,
                    autoPlayNext = state.autoPlayNext,
                    nextUpPromptSeconds = state.nextUpPromptSeconds,
                    resumeRewindSeconds = state.resumeRewindSeconds,
                    passOutThreshold = state.passOutThreshold,
                    onQualityPresetSelected = viewModel::setQualityPreset,
                    onAudioLanguageChanged = viewModel::setAudioLanguage,
                    onIntroSkipModeChanged = viewModel::setIntroSkipMode,
                    onAutoSkipCreditsChanged = viewModel::setAutoSkipCredits,
                    onPictureInPictureEnabledChanged = viewModel::setPictureInPictureEnabled,
                    onDolbyVisionEnabledChanged = viewModel::setDolbyVisionEnabled,
                    onDvProfile7HDR10FallbackChanged = viewModel::setDvProfile7HDR10Fallback,
                    onAutoPlayNextChanged = viewModel::setAutoPlayNext,
                    onNextUpPromptSecondsChanged = viewModel::setNextUpPromptSeconds,
                    onResumeRewindSecondsChanged = viewModel::setResumeRewindSeconds,
                    onPassOutThresholdChanged = viewModel::setPassOutThreshold,
                    onResetPlaybackOverrides = viewModel::resetPlaybackOverrides,
                )
            }

            item {
                val metadataAiStore: MetadataAiFeatureStore = koinInject()
                val metadataAiStatus by metadataAiStore.status.collectAsState()
                SubtitleSettings(
                    subtitleLanguage = state.subtitleLanguage,
                    subtitleLanguageSuggestions = state.subtitleLanguageSuggestions,
                    subtitleMode = state.subtitleMode,
                    showForcedSubtitles = state.showForcedSubtitles,
                    onLanguageChanged = viewModel::setSubtitleLanguage,
                    onModeChanged = viewModel::setSubtitleMode,
                    onForcedSubtitlesChanged = viewModel::setShowForcedSubtitles,
                    subtitleMatchesDevice = state.subtitleMatchesDevice,
                    onSubtitleMatchesDeviceChanged = viewModel::setSubtitleMatchesDevice,
                    onOpenSubtitleAppearance = { subtitleStyleVisible = true },
                    metadataLanguageEnabled = metadataAiStatus.enabled &&
                        metadataAiStatus.onView != MetadataAiOnView.Off,
                    metadataLanguage = state.metadataLanguage,
                    metadataLanguageSuggestions = state.metadataLanguageSuggestions,
                    onMetadataLanguageChanged = viewModel::setMetadataLanguage,
                )
            }

            item {
                SettingsSection(title = "Library") {
                    SettingsNavigationRow(
                        label = "Watchlist",
                        description = "Titles you saved to watch later.",
                        onClick = onNavigateToWatchlist,
                    )
                    SettingsNavigationRow(
                        label = "Favorites",
                        description = "Titles you marked as favorites.",
                        onClick = onNavigateToFavorites,
                    )
                    SettingsNavigationRow(
                        label = "Watch history",
                        description = "Everything you have played, most recent first.",
                        onClick = onNavigateToHistory,
                    )
                    SettingsNavigationRow(
                        label = "Collections",
                        description = "Curated groups of titles from your libraries.",
                        onClick = onNavigateToCollections,
                    )
                    SettingsSwitchRow(
                        label = "Show audiobooks",
                        description = "Show the Audiobooks section in navigation.",
                        checked = state.showAudiobooks,
                        onCheckedChange = viewModel::setShowAudiobooks,
                    )
                }
            }

            item {
                SettingsSection(title = "Interface") {
                    SettingsNavigationRow(
                        label = "Home Sections",
                        description = "Choose which Home rows are visible and the order they appear in.",
                        onClick = { showHomeSectionsEditor = true },
                    )
                }
            }

            item {
                MediaCardsSettings(
                    state = state.cardPresentation,
                    onPresetSelected = viewModel::setCardPreset,
                    onPosterSizeSelected = viewModel::setCardPosterSize,
                    onCaptionSelected = viewModel::setCardCaption,
                    onDeviceOnlyChanged = viewModel::setCardDeviceOnly,
                    onUseProfileDefault = viewModel::useCardProfileDefault,
                )
            }

            if (state.notificationsAvailable) {
                item {
                    SettingsSection(title = "Notifications") {
                        SettingsSwitchRow(
                            label = "In-app notifications",
                            description = "Show alerts inside Silo as new releases arrive.",
                            checked = state.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled,
                        )
                        if (state.notificationsEnabled) {
                            SettingsSwitchRow(
                                label = "Favorites",
                                description = "Notify when something you favorited has a new episode.",
                                checked = state.notifyFavorites,
                                onCheckedChange = viewModel::setNotifyFavorites,
                            )
                            SettingsSwitchRow(
                                label = "Watchlist",
                                description = "Notify when something on your watchlist becomes available.",
                                checked = state.notifyWatchlist,
                                onCheckedChange = viewModel::setNotifyWatchlist,
                            )
                            SettingsSwitchRow(
                                label = "Continue watching",
                                description = "Notify about titles you started but have not finished.",
                                checked = state.notifyContinueWatching,
                                onCheckedChange = viewModel::setNotifyContinueWatching,
                            )
                            SettingsSwitchRow(
                                label = "Next up",
                                description = "Notify when the next episode of a series you watch arrives.",
                                checked = state.notifyNextUp,
                                onCheckedChange = viewModel::setNotifyNextUp,
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Downloads") {
                    SettingsDropdownRow(
                        label = "Download quality",
                        description = "Quality preset used for new downloads.",
                        value = state.defaultDownloadQuality,
                        options = DownloadQuality.entries.map { it.label },
                        onOptionSelected = viewModel::setDefaultDownloadQuality,
                    )
                    SettingsSwitchRow(
                        label = "Download over Wi-Fi only",
                        description = "Only download while connected to Wi-Fi.",
                        checked = state.downloadsWifiOnly,
                        onCheckedChange = viewModel::setDownloadsWifiOnly,
                    )
                    SettingsSwitchRow(
                        label = "Keep watched downloads",
                        description = "Do not suggest reclaiming space from downloads you have finished.",
                        checked = state.keepWatchedDownloads,
                        onCheckedChange = viewModel::setKeepWatchedDownloads,
                    )
                    if (!downloadsState.isEmpty || downloadsState.totalBytesUsed > 0L) {
                        SettingsDestructiveRow(
                            label = if (downloadsState.isRemovingAllDownloads) {
                                "Removing downloads…"
                            } else {
                                "Remove all downloads"
                            },
                            description = "Delete every downloaded file from this device.",
                            value = formatBytes(downloadsState.totalBytesUsed),
                            enabled = !downloadsState.isRemovingAllDownloads,
                            onClick = { showRemoveAllDownloadsConfirm = true },
                        )
                    }
                }
            }

            item {
                ServerInfoSection(
                    serverUrl = state.serverUrl,
                    onManageServersClick = onNavigateToServers,
                )
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(SettingsDimens.pageBottomSpacer)) }
        }
    }

    if (showRemoveAllDownloadsConfirm) {
        SiloConfirmDialog(
            title = "Remove all downloads?",
            body = "This removes ${formatBytes(downloadsState.totalBytesUsed)} of downloaded files " +
                "from this device. Your library and server media stay intact.",
            confirmLabel = if (downloadsState.isRemovingAllDownloads) "Removing…" else "Remove all",
            confirmEnabled = !downloadsState.isRemovingAllDownloads,
            onConfirm = {
                showRemoveAllDownloadsConfirm = false
                downloadsViewModel.removeAllDownloads()
            },
            onDismiss = { showRemoveAllDownloadsConfirm = false },
        )
    }

    if (showHomeSectionsEditor) {
        HomeSectionsEditor(onDismiss = { showHomeSectionsEditor = false })
    }
}

/**
 * Shown when the connected server predates the canonical settings API.
 *
 * The failure mode this replaces was an empty (or silently non-saving)
 * settings screen: the profile preferences resolve to nothing, so the rows
 * render defaults and an edit goes nowhere with no explanation. Saying so is
 * the whole point — playback keeps working from the device's local defaults,
 * only the profile-wide preferences are unavailable.
 */
@Composable
fun SettingsUpgradeRequiredNotice(modifier: Modifier = Modifier) {
    SettingsSection(title = "Server update needed", modifier = modifier) {
        SettingsProse(
            title = "This server is too old for profile settings",
            body = "Subtitle and metadata preferences are stored by the server, and this one " +
                "does not support them yet. Playback still works using this device's settings. " +
                "Ask whoever runs the server to update it.",
        )
    }
}

// --- Shared Settings UI Components ---
//
// The grouped surface these build is the Silo web client's, adapted to Android
// row mechanics: an opaque card on a lifted page ground, a lettered heading
// above rather than inside it, a label over a muted description, and the
// control kept trailing rather than stacked underneath the way the web layout
// stacks it. Metrics live in `ui.theme.SettingsDimens` / `SettingsTextStyles`.
//
// There is deliberately no leading icon on any row. The web client puts icons
// only in its settings *sidebar*, never on a row; Android has no sidebar, so
// its destination rows sit inline among the value rows and an icon on half of
// them is exactly the "iOS Settings at the ends, unstyled form in the middle"
// split this pass removed. The description line is the scanning aid now, and
// the trailing affordance (chevron / value / switch) is what separates a
// destination from a setting.

/**
 * Per-card row counter backing the "no divider above the first row" rule.
 *
 * Rows claim a slot on first composition and remember it, so the index is
 * stable across recomposition and follows source order within the card. A card
 * whose *first* row is conditional would need [SettingsRow]'s `showDivider`
 * override — no section does that today, since every card's opening row is
 * unconditional.
 */
@Stable
internal class SettingsSectionSlots {
    private var next = 0

    fun claim(): Int = next++
}

internal val LocalSettingsSectionSlots = staticCompositionLocalOf<SettingsSectionSlots?> { null }

/** True for the first row composed into the enclosing [SettingsSectionCard]. */
@Composable
private fun isFirstSettingsRow(): Boolean {
    val slots = LocalSettingsSectionSlots.current ?: return true
    return remember(slots) { slots.claim() } == 0
}

/**
 * Claims a row slot for a card child that is not a [SettingsRow] — the account
 * header, a prose pane — and reports whether it should draw a hairline above
 * itself. A custom child that skips this is invisible to the divider rule, and
 * the row after it would wrongly believe it is the card's first.
 *
 * Pair with [settingsRowDivider].
 */
@Composable
fun settingsRowDividerVisible(): Boolean = !isFirstSettingsRow()

/** Draws the standard inter-row hairline along this element's top edge. */
fun Modifier.settingsRowDivider(show: Boolean): Modifier = settingsRowTopDivider(show)

// The hairline itself lives in `ui.theme` beside the tokens it draws with:
// the popup menus rule their rows the same way, and one line rendered by two
// implementations is how two lines end up different.
private fun Modifier.settingsRowTopDivider(show: Boolean): Modifier = siloRowTopDivider(show)

/**
 * A settings group: a lettered heading sitting above its card.
 *
 * The heading used to render *inside* the card as its first child, which is
 * what made every section start with a stray caps line on the same surface as
 * the rows. Its own KDoc always described the intended placement.
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            SettingsSectionHeader(title)
        }
        SettingsSectionCard(content = content)
    }
}

/**
 * Card container for a settings section, and the owner of the row dividers.
 */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val slots = remember { SettingsSectionSlots() }
    CompositionLocalProvider(LocalSettingsSectionSlots provides slots) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingsDimens.cardRadius))
                .background(SiloSurfaceContainer),
            content = content,
        )
    }
}

/**
 * Section heading — uppercased, letter-spaced, muted, sitting above the card
 * with a small inset.
 */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = SettingsTextStyles.sectionHeader,
        color = SiloMutedText,
        modifier = Modifier.padding(
            start = SettingsDimens.headerStartInset,
            end = SettingsDimens.headerStartInset,
            bottom = SettingsDimens.headerBottomGap,
        ),
    )
}

/**
 * Disclosure chevron.
 */
@Composable
fun SettingsRowChevron(enabled: Boolean = true) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = SiloMutedText.copy(alpha = if (enabled) 1f else SettingsDimens.disabledAlpha),
        modifier = Modifier.size(SettingsDimens.chevronSize),
    )
}

/**
 * The one settings row.
 *
 * Every other row type in this package is this one with a different trailing
 * slot: label over an optional description, control trailing, a 60dp floor so
 * a described row and a bare row still read as the same list, and a hairline
 * above every row but the card's first.
 *
 * A trailing [value] shares the *label's* line rather than sitting beside the
 * whole text block. That is the structural half of a real defect: with the
 * value beside the block, a long one ("30 seconds before end") squeezed the
 * description into a narrow column whose last line then ended a few dp from
 * the value, and the two read as touching. On the label's line the value can
 * never abut the description — the description runs the full width beneath it
 * — and the wider column costs a line rather than adding one, so the page gets
 * shorter, not taller. Only [trailing] controls (chevron, switch, radio) sit
 * beside the block now, and an icon at [SettingsDimens.rowTrailingGap] does
 * not read as a collision the way text does.
 *
 * @param showDivider Overrides the automatic first-row rule. Only needed in a
 *   card whose opening row is conditional.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    labelColor: Color = SiloForeground,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val contentAlpha = if (enabled) 1f else SettingsDimens.disabledAlpha
    val divider = showDivider ?: !isFirstSettingsRow()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDimens.rowMinHeight)
            .settingsRowTopDivider(divider)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = SettingsDimens.rowHorizontalPadding,
                vertical = SettingsDimens.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = SettingsTextStyles.rowLabel,
                    color = labelColor.copy(alpha = contentAlpha),
                    // Fills the line so the value stays trailing-aligned, and
                    // yields — by wrapping — when a capped value needs room.
                    modifier = Modifier.weight(1f),
                )
                if (value != null) {
                    Spacer(modifier = Modifier.width(SettingsDimens.rowLabelValueGap))
                    SettingsRowValue(value = value, enabled = enabled)
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = SettingsTextStyles.rowDescription,
                    color = SiloMutedText.copy(alpha = contentAlpha),
                )
            }
        }
        trailing()
    }
}

/**
 * A row that navigates somewhere, optionally showing the current value.
 *
 * Replaces the old `SettingsRowLabel` (iOS coloured badge) and
 * `SettingsClickableRow` (bare 20dp icon), which differed only in their
 * leading treatment.
 */
@Composable
fun SettingsNavigationRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
    labelColor: Color = SiloForeground,
) {
    SettingsRow(
        label = label,
        modifier = modifier,
        description = description,
        value = value,
        labelColor = labelColor,
        enabled = enabled,
        onClick = onClick,
    ) {
        if (showChevron) {
            Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
            SettingsRowChevron(enabled = enabled)
        }
    }
}

/**
 * Destructive row. One tint, [SiloDestructive], for every destructive action
 * on this surface — Sign out, Reset playback settings, Remove all downloads —
 * which previously used three different layouts and two different reds.
 */
@Composable
fun SettingsDestructiveRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    SettingsNavigationRow(
        label = label,
        modifier = modifier,
        description = description,
        value = value,
        onClick = onClick,
        showChevron = false,
        enabled = enabled,
        labelColor = SiloDestructive,
    )
}

/**
 * Trailing value text — smaller and muted, so a picker's current choice does
 * not read as a second label.
 *
 * Unweighted, so [SettingsRow]'s label line measures it first: it gets the
 * width it asks for up to [SettingsDimens.rowValueMaxWidth], and the label
 * takes what is left.
 */
@Composable
private fun SettingsRowValue(value: String, enabled: Boolean) {
    Text(
        text = value,
        style = SettingsTextStyles.rowValue,
        color = SiloMutedText.copy(alpha = if (enabled) 1f else SettingsDimens.disabledAlpha),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(max = SettingsDimens.rowValueMaxWidth),
    )
}

/**
 * Settings row with a switch toggle. The whole row toggles, not just the
 * thumb, and [enabled] now exists — the diagnostics screen used to hand-roll
 * its own copy of this row purely to get a disabled switch.
 */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        label = label,
        description = description,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
        Switch(
            checked = checked,
            // The row owns the gesture; the switch is the indicator.
            onCheckedChange = null,
            enabled = enabled,
            colors = settingsSwitchColors(),
        )
    }
}

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = SiloSurfaceContainer,
    checkedTrackColor = SiloForeground,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = SiloMutedText,
    uncheckedTrackColor = SiloSurfaceContainerHigh,
    uncheckedBorderColor = SiloBorder,
    disabledCheckedThumbColor = SiloSurfaceContainer,
    disabledCheckedTrackColor = SiloForeground.copy(alpha = SettingsDimens.disabledAlpha),
    disabledCheckedBorderColor = Color.Transparent,
    disabledUncheckedThumbColor = SiloMutedText.copy(alpha = SettingsDimens.disabledAlpha),
    disabledUncheckedTrackColor = SiloSurfaceContainerHigh.copy(alpha = SettingsDimens.disabledAlpha),
    disabledUncheckedBorderColor = SiloBorder.copy(alpha = SettingsDimens.disabledAlpha),
)

/**
 * Settings row for one option in a mutually exclusive set. Like the switch
 * row, the whole row is the target.
 */
@Composable
fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        label = label,
        description = description,
        enabled = enabled,
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
    ) {
        Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
        RadioButton(
            selected = selected,
            // The row owns the gesture; the button is the indicator.
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = SiloForeground,
                unselectedColor = SiloMutedText,
                disabledSelectedColor = SiloForeground.copy(alpha = SettingsDimens.disabledAlpha),
                disabledUnselectedColor = SiloMutedText.copy(alpha = SettingsDimens.disabledAlpha),
            ),
        )
    }
}

/**
 * A settings row that opens a menu of options.
 *
 * Trailing value plus a chevron, so a picker reads as something you can open
 * rather than as a read-only fact.
 */
@Composable
fun SettingsDropdownRow(
    label: String,
    value: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SettingsNavigationRow(
            label = label,
            description = description,
            value = value,
            enabled = enabled,
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * A prose block inside a card, for the notices that are explanation rather
 * than setting. Carries the same divider rule as a row.
 */
@Composable
fun SettingsProse(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val divider = !isFirstSettingsRow()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsRowTopDivider(divider)
            .padding(
                horizontal = SettingsDimens.proseHorizontalPadding,
                vertical = SettingsDimens.proseVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = SettingsTextStyles.rowLabel,
                color = SiloForeground,
            )
        }
        Text(
            text = body,
            style = SettingsTextStyles.rowDescription,
            color = SiloMutedText,
        )
    }
}
