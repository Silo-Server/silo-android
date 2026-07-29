package org.siloserver.silo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import org.siloserver.silo.android.ui.components.MainAppHeaderBodyHeight
import org.siloserver.silo.android.ui.components.MainAppTopBar
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.navigation.SiloBottomNavBar
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.android.ui.navigation.Tab
import org.siloserver.silo.android.ui.navigation.fallbackMobileTab
import org.siloserver.silo.android.ui.navigation.scopedLocalDownloadBytes
import org.siloserver.silo.android.ui.navigation.shouldShowDownloadsTab
import org.siloserver.silo.android.ui.navigation.visibleMobileTabs
import org.siloserver.silo.android.ui.screens.calendar.CalendarScreen
import org.siloserver.silo.android.ui.screens.home.HomeScreen
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.ui.screens.cast.SiloCastMiniBar
import org.siloserver.silo.android.ui.screens.cast.SiloCastTargetPickerSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesScreen
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSelectorSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.android.ui.screens.recommendations.RecommendationsScreen
import org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherMenuEntrySheet
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED
import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.MediaModeCapabilities
import org.siloserver.silo.model.navigation.mobileMediaModeCapabilities
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.network.ServerReachabilityStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main scaffold that hosts the bottom navigation bar and tab content.
 *
 * Each tab's content is rendered inline with real screen implementations.
 */
@Composable
fun MainScreen(
    navController: NavHostController,
    currentTab: Tab,
) {
    val headerViewModel = koinViewModel<MainHeaderViewModel>()
    val headerState by headerViewModel.uiState.collectAsState()
    val siloCastController: SiloCastController = koinInject()
    val siloCastState by siloCastController.state.collectAsState()
    var showSiloCastTargetPicker by rememberSaveable { mutableStateOf(false) }
    var showWatchTogetherEntry by rememberSaveable { mutableStateOf(false) }

    fun playVideo(contentId: String, fileId: Int? = null, resumePositionSeconds: Double? = null) {
        val launchedRemotely = siloCastController.launchOnConnectedTarget(
            SiloCastPlaybackRequest(
                contentId = contentId,
                fileId = fileId,
                startFromBeginning = resumePositionSeconds == null,
                resumePosition = resumePositionSeconds,
            ),
        )
        if (launchedRemotely) {
            navController.navigate(Route.SiloCastRemote.route) { launchSingleTop = true }
        } else {
            navController.navigate(
                Route.Player(
                    contentId = contentId,
                    fileId = fileId,
                    resumePositionSeconds = resumePositionSeconds,
                ).route,
            )
        }
    }
    val librariesViewModel = if (currentTab == Tab.Libraries) {
        koinViewModel<LibrariesViewModel>()
    } else {
        null
    }
    val librariesState = if (librariesViewModel != null) {
        librariesViewModel.uiState.collectAsState().value
    } else {
        null
    }
    var showLibrarySelector by rememberSaveable(currentTab) { mutableStateOf(false) }
    // Bumped when the Home tab is re-tapped while already on Home; HomeScreen
    // reacts by scrolling back to the top.
    var homeScrollToTopTick by remember { mutableStateOf(0) }

    // Downloads tab visibility: show whenever EITHER the server says there
    // are records OR we have bytes on disk. The on-disk check is what makes
    // the tab survive airplane mode — `repository.refresh()` returns an
    // empty list when offline, but the downloaded files are still there
    // and we want the user to reach them.
    val personalDataRepository: PersonalDataRepository = koinInject()
    val downloadsRepository: org.siloserver.silo.repository.DownloadsRepository = koinInject()
    val downloadStorage: org.siloserver.silo.common.downloads.DownloadStorage = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    val authRepository: AuthRepository = koinInject()
    val reachabilityMonitor: ServerReachabilityMonitor = koinInject()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val metadataAiFeatureStore: MetadataAiFeatureStore = koinInject()
    val reachabilityState by reachabilityMonitor.state.collectAsState()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val reachabilityScope = rememberCoroutineScope()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val mediaCapabilities by produceState(
        initialValue = MediaModeCapabilities(
            listOf(
                MediaMode.Video,
                MediaMode.Audio,
                MediaMode.Reading,
            ),
        ),
        personalDataRepository,
    ) {
        value = when (val result = personalDataRepository.listUserLibraries()) {
            is ApiResult.Success -> result.data.mobileMediaModeCapabilities()
            else -> value
        }
    }
    val downloadRecords by downloadsRepository.records.collectAsState()
    val activeScopeLocalBytes by produceState(
        initialValue = 0L,
        downloadRecords,
        activeEntry?.id,
        activeEntry?.profileId,
        headerState.activeProfile?.id,
    ) {
        value = scopedLocalDownloadBytes(
            storage = downloadStorage,
            serverId = activeEntry?.id,
            profileId = activeEntry?.profileId ?: headerState.activeProfile?.id,
        )
    }
    val visibleTabs = remember(mediaCapabilities, downloadRecords, activeScopeLocalBytes) {
        val hasAnyDownload = shouldShowDownloadsTab(
            serverRecordCount = downloadRecords.size,
            activeScopeLocalBytes = activeScopeLocalBytes,
        )
        visibleMobileTabs(
            capabilities = mediaCapabilities,
            showDownloads = hasAnyDownload,
        )
    }

    // The shared top bar floats over content; its real height is the status-bar
    // inset plus the fixed bar body. Offset tab content by that so the bar can't
    // overlap content on tall-cutout / large-display devices.
    val headerContentTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        MainAppHeaderBodyHeight

    // If the user is on a tab no longer supported by their libraries (or
    // Downloads disappears), move them to the nearest visible media tab.
    LaunchedEffect(currentTab, visibleTabs) {
        if (currentTab !in visibleTabs) {
            val fallback = fallbackMobileTab(visibleTabs, currentTab) ?: Tab.Home
            navController.navigate(fallback.route) {
                popUpTo(Route.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(activeEntry?.id, activeEntry?.profileId, headerState.activeProfile?.id) {
        requestsFeatureStore.reset()
        requestsFeatureStore.refresh()
        metadataAiFeatureStore.reset()
        metadataAiFeatureStore.refresh()
    }

    fun signOutFromProfileMenu() {
        reachabilityScope.launch {
            authRepository.logout()
            requestsFeatureStore.reset()
            metadataAiFeatureStore.reset()
            navController.navigate(Route.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    val requestsMenuAction: (() -> Unit)? = if (requestsEnabled) {
        { navController.navigate(Route.Requests.route) }
    } else {
        null
    }
    val watchTogetherMenuAction: (() -> Unit)? =
        if (CLIENT_WATCH_TOGETHER_SURFACE_ENABLED) {
            { showWatchTogetherEntry = true }
        } else {
            null
        }

    Scaffold(
        bottomBar = {
            // The cast bar rests above the nav menu (iOS tabViewBottomAccessory
            // placement); the Scaffold then pads tab content past both.
            Column {
                SiloCastMiniBar(
                    controller = siloCastController,
                    onOpenRemote = {
                        navController.navigate(Route.SiloCastRemote.route) { launchSingleTop = true }
                    },
                )
                SiloBottomNavBar(
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        if (tab == Tab.Home && currentTab == Tab.Home) {
                            // Re-tapping Home while on Home scrolls it back to the
                            // top (standard Android bottom-nav convention).
                            homeScrollToTopTick += 1
                        } else {
                            navController.navigate(tab.route) {
                                popUpTo(Route.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    tabs = visibleTabs,
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .consumeWindowInsets(padding),
        ) {
            // Content extends edge-to-edge under the translucent bottom chrome
            // (iOS glass tab bar); screens read the measured chrome height and
            // pad their scroll ends so the last items stay reachable.
            CompositionLocalProvider(
                LocalBottomChromeInset provides padding.calculateBottomPadding(),
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
                    Tab.Home -> {
                        val homeViewModel = koinViewModel<HomeViewModel>()
                        HomeScreen(
                            scrollToTopTick = homeScrollToTopTick,
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            onPlayClick = { contentId, resumePositionSeconds ->
                                playVideo(contentId, resumePositionSeconds = resumePositionSeconds)
                            },
                            viewModel = homeViewModel,
                            activeProfile = headerState.activeProfile,
                            onSearchClick = { navController.navigate(Route.Search().route) },
                            onRemoteControlClick = {
                                if (siloCastState.hasActiveSession) {
                                    navController.navigate(Route.SiloCastRemote.route)
                                } else {
                                    showSiloCastTargetPicker = true
                                }
                            },
                            onRemoteChooseTvClick = { showSiloCastTargetPicker = true },
                            onRemoteDisconnectClick = { siloCastController.disconnect() },
                            isRemoteControlActive = siloCastState.hasActiveSession,
                            onRequestsClick = requestsMenuAction,
                            onWatchTogetherClick = watchTogetherMenuAction,
                            onSettingsClick = { navController.navigate(Route.Settings.route) },
                            onSwitchProfileClick = {
                                navController.navigate(Route.ProfileSelection.route)
                            },
                            onSwitchServerClick = {
                                navController.navigate(Route.ServerList.route)
                            },
                            onSignOutClick = ::signOutFromProfileMenu,
                        )
                    }
                    Tab.Libraries -> {
                        LibrariesScreen(
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            onPlayClick = { contentId, resumePositionSeconds ->
                                playVideo(contentId, resumePositionSeconds = resumePositionSeconds)
                            },
                            onCollectionClick = { collectionId, libraryId ->
                                navController.navigate(Route.CollectionDetail(collectionId, libraryId).route)
                            },
                            viewModel = requireNotNull(librariesViewModel),
                            activeProfile = headerState.activeProfile,
                            onLibrarySelectorClick = { showLibrarySelector = true },
                            onSearchClick = { navController.navigate(Route.Search().route) },
                            onRequestsClick = requestsMenuAction,
                            onWatchTogetherClick = watchTogetherMenuAction,
                            onSettingsClick = { navController.navigate(Route.Settings.route) },
                            onSwitchProfileClick = {
                                navController.navigate(Route.ProfileSelection.route)
                            },
                            onSwitchServerClick = {
                                navController.navigate(Route.ServerList.route)
                            },
                            onSignOutClick = ::signOutFromProfileMenu,
                        )
                    }
                    Tab.ForYou -> {
                        RecommendationsScreen(
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            contentTopPadding = headerContentTop,
                        )
                    }
                    Tab.Calendar -> {
                        CalendarScreen(
                            onBackClick = { navController.popBackStack() },
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            showTopBar = false,
                            contentTopPadding = headerContentTop,
                        )
                    }
                    Tab.Downloads -> {
                        org.siloserver.silo.android.ui.screens.downloads.DownloadsScreen(
                            // Tap on a downloaded row goes directly to the right
                            // player so it works offline (ItemDetail needs the
                            // server and would block with "No internet"). Route
                            // audiobooks to the audiobook player so they get the
                            // audiobook UI + offline resume; everything else uses
                            // the video player's offline-first tryLocalPlayback.
                            onItemClick = { item ->
                                if (item.mediaType == org.siloserver.silo.model.download.DownloadMediaType.Audiobook) {
                                    navController.navigate(
                                        Route.AudiobookPlayer(item.contentId, item.fileId).route,
                                    )
                                } else {
                                    // Downloads are explicitly local/offline;
                                    // bypass playVideo's active-cast redirect.
                                    navController.navigate(
                                        Route.Player(
                                            contentId = item.contentId,
                                            fileId = item.fileId,
                                        ).route,
                                    )
                                }
                            },
                            onReadEbook = { contentId, fileId ->
                                navController.navigate(Route.BookReader(contentId, fileId).route)
                            },
                            contentTopPadding = headerContentTop,
                        )
                    }
                }
            }

            // Home and Libraries paint their own floating chrome. Calendar,
            // Downloads, and For You use the shared iOS-style top chrome.
            if (currentTab == Tab.Downloads || currentTab == Tab.ForYou || currentTab == Tab.Calendar) {
                val title = when (currentTab) {
                    Tab.Calendar -> "Calendar"
                    Tab.Downloads -> "Downloads"
                    Tab.ForYou -> "For You"
                    else -> null
                }
                MainAppTopBar(
                    activeProfile = headerState.activeProfile,
                    isProfileLoading = headerState.isLoading,
                    onSearchClick = { navController.navigate(Route.Search().route) },
                    onRequestsClick = requestsMenuAction,
                    onWatchTogetherClick = watchTogetherMenuAction,
                    onSettingsClick = { navController.navigate(Route.Settings.route) },
                    onSwitchProfileClick = {
                        navController.navigate(Route.ProfileSelection.route)
                    },
                    onSwitchServerClick = {
                        navController.navigate(Route.ServerList.route)
                    },
                    onSignOutClick = ::signOutFromProfileMenu,
                    leadingContent = {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            org.siloserver.silo.android.ui.components.SiloWordmark()
                        }
                    },
                )
            }

            if (reachabilityState.status == ServerReachabilityStatus.Unreachable) {
                MobileServerOfflinePill(
                    onRetry = { reachabilityScope.launch { reachabilityMonitor.retryNow() } },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = headerContentTop + 8.dp,
                            start = 20.dp,
                            end = 20.dp,
                        ),
                )
            }

            if (currentTab == Tab.Libraries && librariesState != null && showLibrarySelector) {
                LibrariesSelectorSheet(
                    libraries = librariesState.libraries,
                    selectedLibraryId = librariesState.selectedLibraryId,
                    onSelectLibrary = { libraryId ->
                        showLibrarySelector = false
                        librariesViewModel?.selectLibrary(libraryId)
                    },
                    onDismiss = { showLibrarySelector = false },
                )
            }

            if (showSiloCastTargetPicker) {
                SiloCastTargetPickerSheet(
                    onDismiss = { showSiloCastTargetPicker = false },
                    controller = siloCastController,
                )
            }

            if (showWatchTogetherEntry) {
                WatchTogetherMenuEntrySheet(
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                        }
                    },
                    onDismiss = { showWatchTogetherEntry = false },
                )
            }
        }
    }
    }
}

@Composable
private fun MobileServerOfflinePill(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clickable(onClick = onRetry),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = "Offline mode - tap to retry",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
