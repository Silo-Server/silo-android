package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.downloads.DownloadsViewModel
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.android.ui.screens.settings.diagnostics.shouldShowDiagnosticsEntry
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit,
    onNavigateToServers: () -> Unit = {},
    onPairDevice: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToCardOverlays: () -> Unit = {},
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
    val sessionsSheetState = rememberModalBottomSheetState()
    var showRemoveAllDownloadsConfirm by remember { mutableStateOf(false) }

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
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                if (!showTopBar) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
            }

            item {
                AccountSection(
                    onSwitchProfile = onSwitchProfile,
                    user = state.user,
                    isLoadingUser = state.isLoadingUser,
                    isAdminVisible = state.isAdminVisible,
                    onManageSessions = viewModel::loadSessions,
                    onPairDevice = onPairDevice,
                    onAdmin = onNavigateToAdmin,
                    onSignOut = viewModel::logout,
                )
            }

            item {
                SettingsSectionCard {
                    SettingsRowLabel(
                        title = "Card Overlays",
                        icon = Icons.Filled.Layers,
                        badgeColor = SettingsBadgeIndigo,
                        onClick = onNavigateToCardOverlays,
                        showChevron = true,
                    )
                }
            }

            if (shouldShowDiagnosticsEntry(diagnosticsState)) {
                item {
                    SettingsSectionCard {
                        SettingsRowLabel(
                            title = "Diagnostics",
                            icon = Icons.Outlined.Info,
                            badgeColor = SettingsBadgeOrange,
                            value = when (diagnosticsState.availability) {
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.AVAILABLE -> "Available"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.DISABLED -> "Disabled"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Unavailable"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.OFFLINE -> "Offline"
                                org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi.INELIGIBLE -> null
                            },
                            onClick = onNavigateToDiagnostics,
                            showChevron = true,
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
                    autoSkipIntro = state.autoSkipIntro,
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
                    onAutoSkipIntroChanged = viewModel::setAutoSkipIntro,
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
                    onMetadataLanguageChanged = viewModel::setMetadataLanguage,
                )
            }

            item {
                SettingsSectionCard {
                    SettingsSectionHeader(title = "Library")
                    SettingsClickableRow(
                        icon = Icons.Outlined.BookmarkBorder,
                        label = "Watchlist",
                        onClick = onNavigateToWatchlist,
                    )
                    SettingsClickableRow(
                        icon = Icons.Outlined.FavoriteBorder,
                        label = "Favorites",
                        onClick = onNavigateToFavorites,
                    )
                    SettingsClickableRow(
                        icon = Icons.Outlined.History,
                        label = "Watch History",
                        onClick = onNavigateToHistory,
                    )
                    SettingsClickableRow(
                        icon = Icons.Outlined.GridView,
                        label = "Collections",
                        onClick = onNavigateToCollections,
                    )
                    SettingsSwitchRow(
                        label = "Show Audiobooks",
                        checked = state.showAudiobooks,
                        onCheckedChange = viewModel::setShowAudiobooks,
                    )
                }
            }

            if (state.notificationsAvailable) {
                item {
                    SettingsSectionCard {
                        SettingsSectionHeader(title = "Notifications")
                        SettingsSwitchRow(
                            label = "In-app notifications",
                            checked = state.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled,
                        )
                        if (state.notificationsEnabled) {
                            SettingsSwitchRow(
                                label = "Favorites",
                                checked = state.notifyFavorites,
                                onCheckedChange = viewModel::setNotifyFavorites,
                            )
                            SettingsSwitchRow(
                                label = "Watchlist",
                                checked = state.notifyWatchlist,
                                onCheckedChange = viewModel::setNotifyWatchlist,
                            )
                            SettingsSwitchRow(
                                label = "Continue watching",
                                checked = state.notifyContinueWatching,
                                onCheckedChange = viewModel::setNotifyContinueWatching,
                            )
                            SettingsSwitchRow(
                                label = "Next up",
                                checked = state.notifyNextUp,
                                onCheckedChange = viewModel::setNotifyNextUp,
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionCard {
                    SettingsSectionHeader(title = "Downloads")
                    SettingsDropdownRow(
                        label = "Default Quality",
                        value = state.defaultDownloadQuality,
                        options = DownloadQuality.entries.map { it.label },
                        onOptionSelected = viewModel::setDefaultDownloadQuality,
                    )
                    SettingsSwitchRow(
                        label = "Wi-Fi only",
                        checked = state.downloadsWifiOnly,
                        onCheckedChange = viewModel::setDownloadsWifiOnly,
                    )
                    SettingsSwitchRow(
                        label = "Keep watched downloads",
                        checked = state.keepWatchedDownloads,
                        onCheckedChange = viewModel::setKeepWatchedDownloads,
                    )
                    if (!downloadsState.isEmpty || downloadsState.totalBytesUsed > 0L) {
                        SettingsClickableRow(
                            icon = Icons.Outlined.Delete,
                            label = if (downloadsState.isRemovingAllDownloads) {
                                "Removing Downloads..."
                            } else {
                                "Remove All Downloads"
                            },
                            onClick = { showRemoveAllDownloadsConfirm = true },
                            labelColor = SettingsBadgeRed,
                            iconTint = SettingsBadgeRed,
                            enabled = !downloadsState.isRemovingAllDownloads,
                            trailingText = formatBytes(downloadsState.totalBytesUsed),
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
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // Sessions bottom sheet
    if (state.showSessions) {
        SessionsSheet(
            sheetState = sessionsSheetState,
            sessions = state.sessions,
            isLoading = state.isLoadingSessions,
            onRevokeSession = viewModel::revokeSession,
            onDismiss = viewModel::hideSessions,
        )
    }

    if (showRemoveAllDownloadsConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDownloadsConfirm = false },
            title = { Text("Remove all downloads?") },
            text = {
                Text(
                    "This removes ${formatBytes(downloadsState.totalBytesUsed)} of downloaded files from this device. " +
                        "Your library and server media stay intact.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !downloadsState.isRemovingAllDownloads,
                    onClick = {
                        showRemoveAllDownloadsConfirm = false
                        downloadsViewModel.removeAllDownloads()
                    },
                ) {
                    Text(if (downloadsState.isRemovingAllDownloads) "Removing..." else "Remove All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveAllDownloadsConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
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
    SettingsSectionCard(modifier = modifier) {
        SettingsSectionHeader(title = "Server Update Needed")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            Text(
                text = "This server is too old for profile settings",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Subtitle and metadata preferences are stored by the server, and this one " +
                    "does not support them yet. Playback still works using this device's settings. " +
                    "Ask whoever runs the server to update it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- iOS system-color badge palette (maps SwiftUI .blue/.pink/etc.) ---

val SettingsBadgeBlue = Color(0xFF0A84FF)
val SettingsBadgePink = Color(0xFFFF375F)
val SettingsBadgeIndigo = Color(0xFF5E5CE6)
val SettingsBadgeTeal = Color(0xFF64D2FF)
val SettingsBadgeOrange = Color(0xFFFF9F0A)
val SettingsBadgeRed = Color(0xFFFF453A)
val SettingsBadgeGray = Color(0xFF8E8E93)
val SettingsBadgePurple = Color(0xFFBF5AF2)

// --- Shared Settings UI Components ---

/**
 * Card container for a settings section. Mirrors the iOS inset-grouped
 * `Section` whose rows sit on `siloSurfaceElevated`. iOS uses a
 * ~10pt corner radius for grouped sections.
 */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // iOS rows sit on `siloSurfaceElevated`, which the Android
            // theme exposes as `primaryContainer` (0xFF15171C).
            .background(MaterialTheme.colorScheme.primaryContainer),
        content = content,
    )
}

/**
 * Section header text. iOS grouped-list section headers are uppercased
 * footnote text in the secondary color, sitting above the card with a
 * small inset.
 */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
    )
}

/**
 * iOS Settings-app style row: a colored rounded-square icon badge
 * (cornerRadius 7, 29x29), the row title, and an optional trailing
 * value in secondary color. Mirrors `SettingsRowLabel`.
 */
@Composable
fun SettingsRowLabel(
    title: String,
    icon: ImageVector,
    badgeColor: Color,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(29.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(badgeColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        if (value != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        if (showChevron) {
            Spacer(modifier = Modifier.width(8.dp))
            SettingsRowChevron()
        }
    }
}

/**
 * Disclosure chevron matching the iOS `SettingsRowChevron`.
 */
@Composable
fun SettingsRowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.size(18.dp),
    )
}

/**
 * Generic settings row with a label and a trailing content slot.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * Settings row with a switch toggle.
 */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRow(label = label, modifier = modifier) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/**
 * Clickable row with an icon and label, used for action items like "Sign Out".
 */
@Composable
fun SettingsClickableRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    trailingText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.weight(1f),
        )
        if (trailingText != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
