package com.continuum.app.android.ui.screens.settings

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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.components.ContinuumTopBar
import org.koin.compose.viewmodel.koinViewModel

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
    onNavigateToRequests: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToCardOverlays: () -> Unit = {},
    showTopBar: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sessionsSheetState = rememberModalBottomSheetState()

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            viewModel.onLogoutConsumed()
            onLoggedOut()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                ContinuumTopBar(
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
                    user = state.user,
                    isLoadingUser = state.isLoadingUser,
                    isAdminVisible = state.isAdminVisible,
                    onManageSessions = viewModel::loadSessions,
                    onPairDevice = onPairDevice,
                    onRequests = onNavigateToRequests,
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

            item {
                PlaybackSettings(
                    defaultQuality = state.defaultQuality,
                    audioLanguage = state.audioLanguage,
                    autoSkipIntro = state.autoSkipIntro,
                    autoSkipCredits = state.autoSkipCredits,
                    resumeRewindSeconds = state.resumeRewindSeconds,
                    passOutThreshold = state.passOutThreshold,
                    onQualityChanged = viewModel::setDefaultQuality,
                    onAudioLanguageChanged = viewModel::setAudioLanguage,
                    onAutoSkipIntroChanged = viewModel::setAutoSkipIntro,
                    onAutoSkipCreditsChanged = viewModel::setAutoSkipCredits,
                    onResumeRewindSecondsChanged = viewModel::setResumeRewindSeconds,
                    onPassOutThresholdChanged = viewModel::setPassOutThreshold,
                    onResetPlaybackOverrides = viewModel::resetPlaybackOverrides,
                )
            }

            item {
                SubtitleSettings(
                    subtitleLanguage = state.subtitleLanguage,
                    subtitleMode = state.subtitleMode,
                    showForcedSubtitles = state.showForcedSubtitles,
                    onLanguageChanged = viewModel::setSubtitleLanguage,
                    onModeChanged = viewModel::setSubtitleMode,
                    onForcedSubtitlesChanged = viewModel::setShowForcedSubtitles,
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
                    SettingsSwitchRow(
                        label = "Wi-Fi only",
                        checked = state.downloadsWifiOnly,
                        onCheckedChange = viewModel::setDownloadsWifiOnly,
                    )
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
 * `Section` whose rows sit on `continuumSurfaceElevated`. iOS uses a
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
            // iOS rows sit on `continuumSurfaceElevated`, which the Android
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
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
        )
    }
}
