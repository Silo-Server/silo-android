package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.BuildConfig
import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.components.SignOutConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.android.ui.screens.settings.diagnostics.shouldShowDiagnosticsEntry
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSettingsBackground
import org.siloserver.silo.common.diagnostics.DiagnosticsAvailabilityUi
import org.siloserver.silo.common.network.clientVersionLabel
import org.siloserver.silo.common.player.rememberPlaybackRecoverySettings
import org.siloserver.silo.common.settings.CardPresentationSupport
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SettingKeys

/** Silo's privacy policy, linked from About and from Diagnostics. */
internal const val SILO_PRIVACY_POLICY_URL = "https://siloserver.org/privacy"

/**
 * The Settings overview, laid out like the Apple apps' (silo-apple
 * `IOSSettingsOverview`): a large title, the account card, a search field,
 * then grouped one-line rows with graphite icon tiles that open the sub-pages.
 *
 * Android adds what the Apple apps keep elsewhere: Notifications, the Library
 * shortcuts (Watchlist, Favorites, History, Collections — reachable only from
 * here on the phone), Sign in a TV, and playback-stop recovery. Each sits in
 * the group it belongs to rather than in a group of its own. The Sign-in group
 * (the server's external sign-in providers) appears only on servers that
 * offer one.
 */
@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit,
    onBackClick: (() -> Unit)? = null,
    onOpenInterface: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onOpenSubtitles: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onNavigateToServers: () -> Unit = {},
    onPairDevice: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToFavorites: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToCollections: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
    diagnosticsViewModel: DiagnosticsViewModel = koinViewModel(),
    signInViewModel: SignInSettingsViewModel = koinViewModel(),
) {
    val recovery = rememberPlaybackRecoverySettings(koinInject())
    val state by viewModel.uiState.collectAsState()
    val signInState by signInViewModel.uiState.collectAsState()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var query by rememberSaveable { mutableStateOf("") }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // The large title scrolls away and the bar takes the title over, the way
    // a large navigation title collapses to an inline one.
    val titleScrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) {
            viewModel.onLogoutConsumed()
            onLoggedOut()
        }
    }

    val search = SettingsSearch(query)
    val versionLabel = clientVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER)
    val serverLabel = state.serverName.ifBlank { serverHost(state.serverUrl) ?: "Not connected" }
    val showDiagnostics = shouldShowDiagnosticsEntry(diagnosticsState) &&
        search.matches("diagnostics", "support", "reports", "debug", "crash")
    val showRecovery = recovery.visible &&
        search.matches("playback", "recovery", "retry", "stops", "support")

    val matchesInterface = search.matches(
        "interface", "appearance", "cards", "posters", "captions", "home", "sections",
        "title art", "logos",
    )
    val matchesNotifications = state.notificationsAvailable && search.matches(
        "notifications", "alerts", "favorites", "watchlist", "continue watching", "next up",
    )
    val matchesPlayback = search.matches(
        "playback", "quality", "audio", "dolby vision", "picture-in-picture", "episodes", "next up",
        "skip", "intros", "credits", "rewind", "still watching", "skip interval", "audiobooks",
    )
    val matchesSubtitles = search.matches(
        "subtitles", "captions", "language", "behavior", "appearance", "forced", "metadata",
    )
    val matchesDownloads = search.matches("downloads", "offline", "quality", "wi-fi", "cleanup", "storage")
    val matchesWatchlist = search.matches("library", "watchlist")
    val matchesFavorites = search.matches("library", "favorites")
    val matchesHistory = search.matches("library", "history", "watch history")
    val matchesCollections = search.matches("library", "collections")
    val matchesServer = search.matches("server", "connection", serverLabel)
    val matchesPairDevice = search.matches("connection", "pair", "device", "tv", "link", "sign in")
    // External sign-in (OIDC/LDAP): shown only on servers that have it.
    val matchesSignIn = signInState.visible &&
        search.matches("sign-in", "sign in", "account", "provider", "connect", "disconnect", "sso")
    val matchesExperimental = search.matches("experimental", "beta", "testing", "audiobooks", "navigation")
    val matchesAbout = search.matches("about", "version", versionLabel, "privacy", "policy", "information")
    val matchesSignOut = search.matches("sign out", "account")

    val hasResults = matchesInterface || matchesNotifications || matchesPlayback || matchesSubtitles ||
        matchesDownloads || matchesWatchlist || matchesFavorites || matchesHistory || matchesCollections ||
        showDiagnostics || showRecovery || matchesServer || matchesPairDevice || matchesExperimental ||
        matchesAbout || matchesSignOut || matchesSignIn

    Scaffold(
        topBar = {
            SiloTopBar(
                title = if (titleScrolledAway) "Settings" else "",
                onBackClick = onBackClick,
                containerColor = SiloSettingsBackground,
                centerTitle = true,
            )
        },
        containerColor = SiloSettingsBackground,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = SettingsDimens.pageGutter,
                end = SettingsDimens.pageGutter,
                bottom = SettingsDimens.pageBottomSpacer,
            ),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.sectionGap),
        ) {
            item(key = "title") {
                Column {
                    Text(
                        text = "Settings",
                        style = SettingsTextStyles.largeTitle,
                        color = SiloOnSurface,
                    )
                    Spacer(modifier = Modifier.height(SettingsDimens.headerBottomGap))
                    SettingsAccountCard(
                        profile = state.activeProfile,
                        user = state.user,
                        serverHost = serverHost(state.serverUrl),
                        onClick = onSwitchProfile,
                    )
                }
            }

            item(key = "search") {
                SettingsSearchField(query = query, onQueryChange = { query = it })
            }

            if (!hasResults) {
                item(key = "no-results") {
                    Text(
                        text = "No Results for “${query.trim()}”",
                        style = SettingsTextStyles.rowLabel,
                        color = SiloSecondaryText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                    )
                }
            }

            if (matchesInterface || matchesNotifications) {
                item(key = "preferences") {
                    SettingsSection(title = "Preferences") {
                        if (matchesInterface) {
                            SettingsNavigationRow(
                                label = "Interface",
                                icon = Icons.Filled.Dashboard,
                                value = state.cardPresentation.takeIf {
                                    it.support != CardPresentationSupport.Unsupported
                                }?.let { it.presentation.preset?.displayName ?: "Custom" },
                                onClick = onOpenInterface,
                            )
                        }
                        if (matchesNotifications) {
                            SettingsNavigationRow(
                                label = "Notifications",
                                icon = Icons.Filled.Notifications,
                                value = if (state.notificationsEnabled) "On" else "Off",
                                onClick = onOpenNotifications,
                            )
                        }
                    }
                }
            }

            if (matchesPlayback || matchesSubtitles || matchesDownloads) {
                item(key = "playback") {
                    SettingsSection(title = "Playback") {
                        if (matchesPlayback) {
                            SettingsNavigationRow(
                                label = "Playback",
                                icon = Icons.Filled.PlayArrow,
                                value = QualityPresets.describe(state.qualityResolution, state.maxBitrateKbps),
                                onClick = onOpenPlayback,
                            )
                        }
                        if (matchesSubtitles) {
                            SettingsNavigationRow(
                                label = "Subtitles",
                                icon = Icons.Filled.Subtitles,
                                value = LanguageOptions.label(state.subtitleLanguage, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
                                onClick = onOpenSubtitles,
                            )
                        }
                        if (matchesDownloads) {
                            SettingsNavigationRow(
                                label = "Downloads",
                                icon = Icons.Filled.ArrowCircleDown,
                                onClick = onOpenDownloads,
                            )
                        }
                    }
                }
            }

            if (matchesWatchlist || matchesFavorites || matchesHistory || matchesCollections) {
                item(key = "library") {
                    SettingsSection(title = "Library") {
                        if (matchesWatchlist) {
                            SettingsNavigationRow(
                                label = "Watchlist",
                                icon = Icons.Filled.Bookmark,
                                onClick = onNavigateToWatchlist,
                            )
                        }
                        if (matchesFavorites) {
                            SettingsNavigationRow(
                                label = "Favorites",
                                icon = Icons.Filled.Favorite,
                                onClick = onNavigateToFavorites,
                            )
                        }
                        if (matchesHistory) {
                            SettingsNavigationRow(
                                label = "Watch History",
                                icon = Icons.Filled.History,
                                onClick = onNavigateToHistory,
                            )
                        }
                        if (matchesCollections) {
                            SettingsNavigationRow(
                                label = "Collections",
                                icon = Icons.Filled.CollectionsBookmark,
                                onClick = onNavigateToCollections,
                            )
                        }
                    }
                }
            }

            if (showDiagnostics || showRecovery) {
                item(key = "support") {
                    SettingsSection(
                        title = "Support",
                        footer = recovery.message.takeIf { showRecovery },
                    ) {
                        if (showDiagnostics) {
                            SettingsNavigationRow(
                                label = "Diagnostics",
                                icon = Icons.Filled.MonitorHeart,
                                value = diagnosticsAvailabilityLabel(diagnosticsState.availability),
                                onClick = onNavigateToDiagnostics,
                            )
                        }
                        if (showRecovery) {
                            SettingsNavigationRow(
                                label = if (recovery.busy) "Recovering Playback…" else "Retry Pending Playback Stops",
                                icon = Icons.Filled.Restore,
                                onClick = recovery.retry,
                                enabled = !recovery.busy,
                            )
                        }
                    }
                }
            }

            if (matchesServer || matchesPairDevice) {
                item(key = "connection") {
                    SettingsSection(title = "Connection") {
                        if (matchesServer) {
                            SettingsNavigationRow(
                                label = "Server",
                                icon = Icons.Filled.Dns,
                                value = serverLabel,
                                onClick = onNavigateToServers,
                            )
                        }
                        if (matchesPairDevice) {
                            SettingsNavigationRow(
                                label = stringResource(R.string.sign_in_tv_settings_label),
                                icon = Icons.Filled.ConnectedTv,
                                onClick = onPairDevice,
                            )
                        }
                    }
                }
            }

            if (matchesSignIn) {
                item(key = "sign-in") {
                    SignInSection(viewModel = signInViewModel)
                }
            }

            if (matchesExperimental) {
                item(key = "experimental") {
                    SettingsSection(title = "Experimental") {
                        SettingsSwitchRow(
                            label = "Show Audiobooks",
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            checked = state.showAudiobooks,
                            onCheckedChange = viewModel::setShowAudiobooks,
                        )
                    }
                }
            }

            if (matchesAbout) {
                item(key = "about") {
                    SettingsSection(title = "About") {
                        // Includes the build number so a support report and the
                        // server's admin Activity page name the exact same build,
                        // in the "1.0.0 (5)" form Play, TestFlight and the server's
                        // diagnostics page all use.
                        SettingsNavigationRow(
                            label = "Version",
                            icon = Icons.Filled.Info,
                            value = versionLabel,
                        )
                        SettingsNavigationRow(
                            label = "Privacy Policy",
                            icon = Icons.Filled.PanTool,
                            onClick = { uriHandler.openUri(SILO_PRIVACY_POLICY_URL) },
                        )
                    }
                }
            }

            if (matchesSignOut) {
                item(key = "sign-out") {
                    SettingsSectionCard {
                        SettingsButtonRow(label = "Sign Out", onClick = { confirmSignOut = true })
                    }
                }
            }
        }
    }

    // Same dialog the profile menu raises, so the answer to "are you sure?"
    // does not depend on which of the two routes was taken.
    SignOutConfirmDialog(
        visible = confirmSignOut,
        accountName = state.user?.username,
        onConfirm = {
            confirmSignOut = false
            viewModel.logout()
        },
        onDismiss = { confirmSignOut = false },
    )
}

/**
 * The overview search (Apple `IOSSettingsOverview.matches`): a row shows when
 * any of its keywords contains the trimmed query, case-insensitively. An
 * empty query shows everything.
 */
private class SettingsSearch(query: String) {
    private val needle = query.trim()

    fun matches(vararg terms: String): Boolean =
        needle.isEmpty() || terms.any { it.contains(needle, ignoreCase = true) }
}

/** The host of [serverUrl], or null when there is no server. */
private fun serverHost(serverUrl: String): String? {
    if (serverUrl.isBlank()) return null
    return serverUrl
        .substringAfter("://", serverUrl)
        .substringBefore('/')
        .ifBlank { serverUrl }
}

/** The Diagnostics row's value, in the Apple apps' wording. */
private fun diagnosticsAvailabilityLabel(availability: DiagnosticsAvailabilityUi): String? = when (availability) {
    DiagnosticsAvailabilityUi.AVAILABLE -> "Available"
    DiagnosticsAvailabilityUi.DISABLED -> "Disabled by server"
    DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE -> "Storage unavailable"
    DiagnosticsAvailabilityUi.OFFLINE -> "Offline"
    DiagnosticsAvailabilityUi.INELIGIBLE -> null
}
