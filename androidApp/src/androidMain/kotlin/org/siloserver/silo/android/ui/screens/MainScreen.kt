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
import org.siloserver.silo.android.ui.components.TabTopBarActions
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.navigation.SiloBottomNavBar
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.android.ui.navigation.Tab
import org.siloserver.silo.android.ui.navigation.tabForRoute
import org.siloserver.silo.android.ui.navigation.tabSwitchNavOptions
import org.siloserver.silo.android.ui.navigation.bottomMostTabRoute
import org.siloserver.silo.android.ui.navigation.fallbackMobileTab
import org.siloserver.silo.android.ui.navigation.scopedLocalDownloadBytes
import org.siloserver.silo.android.ui.navigation.shouldShowDownloadsTab
import org.siloserver.silo.android.ui.navigation.visibleMobileTabs
import org.siloserver.silo.android.ui.navigation.continueWatchingDetailRoute
import org.siloserver.silo.android.ui.screens.calendar.CalendarScreen
import org.siloserver.silo.android.ui.screens.home.HomeScreen
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.ui.screens.cast.SiloCastMiniBar
import org.siloserver.silo.android.ui.screens.cast.SiloCastTargetPickerSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesScreen
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSelectorSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.android.ui.screens.recommendations.ForYouList
import org.siloserver.silo.android.ui.screens.recommendations.RecommendationsScreen
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.android.ui.screens.recommendations.headerTitle
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
import org.siloserver.silo.common.settings.CardPresentationStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
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
    var homeBottomNavMinimized by rememberSaveable { mutableStateOf(false) }

    // Entering (or re-entering) any tab starts with the complete tab capsule.
    // Home alone can minimize it after the user begins scrolling downward.
    LaunchedEffect(currentTab) {
        homeBottomNavMinimized = false
    }

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
    val overlayPrefsStore: OverlayPrefsStore = koinInject()
    val cardPresentationStore: CardPresentationStore = koinInject()
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
    // A tab that can no longer be shown must not be left on the stack: no entry
    // at the bottom for Back to reveal (its own effect would bounce straight
    // back, trapping the user), and no saved subtree for a later reappearance to
    // restore into. This can only act while a tab is composed — with a detail
    // page covering it, cleanup waits until Back returns here.
    //
    // Deliberately no saveState/restoreState on this path. Saving the vanishing
    // tab and then restoring on the way to the replacement is self-defeating:
    // restoreState is evaluated before launchSingleTop, so navigating to Home
    // immediately restored the Downloads subtree that had just been popped.
    LaunchedEffect(currentTab, visibleTabs) {
        val anchorRoute = navController.bottomMostTabRoute()
        val anchorTab = anchorRoute?.let(::tabForRoute)
        val vanished = when {
            currentTab !in visibleTabs -> currentTab
            // The ANCHOR can vanish while the user is on some other tab. Nothing
            // above it changed, so this is the only chance to notice.
            anchorTab != null && anchorTab !in visibleTabs -> anchorTab
            else -> null
        } ?: return@LaunchedEffect

        val target = if (vanished == currentTab) {
            fallbackMobileTab(visibleTabs, currentTab) ?: Tab.Home
        } else {
            // Re-rooting onto the tab in use also destroys its entry, losing
            // scroll position. Accepted: the alternative leaves an unreachable
            // root that Back can surface.
            currentTab
        }

        navController.navigate(target.route) {
            // Pop to the ANCHOR, not merely to the vanished tab. Popping just
            // the vanished one leaves any other tab entries below it in place,
            // and pushing the target then adds a SECOND copy of a tab already
            // down there — the duplicate that makes the anchor ambiguous.
            // Collapsing to the anchor first keeps at most one entry per tab,
            // and launchSingleTop absorbs the case where the target IS the
            // anchor.
            if (vanished == anchorTab) {
                popUpTo(vanished.route) { inclusive = true }
            } else {
                anchorRoute?.let { popUpTo(it) { inclusive = false } }
            }
            launchSingleTop = true
        }
        // Drop any subtree saved for it by an earlier ordinary tab switch —
        // popping without saveState does not clear existing mappings, and a
        // reappearing Downloads would otherwise restore a stale stack and land
        // the user on a different tab entirely.
        navController.clearBackStack(vanished.route)
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
            // Per-profile card caches, same teardown the Settings sign-out
            // does — otherwise the next user's shell renders (and can write
            // back) the previous profile's overlays and card presentation.
            overlayPrefsStore.clear()
            cardPresentationStore.clear()
            navController.navigate(Route.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    /**
     * Profile-menu "Switch Profile". Overlays and card presentation are cached
     * per profile and the app never backgrounds during an in-app switch, so
     * drop them here or the next profile keeps rendering — and writing back —
     * the previous one's values. Navigate first: clearing while the shell is
     * still composed repaints it with default cards behind the picker (the
     * TV shell's switch-profile path takes the same order).
     */
    fun switchProfileFromMenu() {
        navController.navigate(Route.ProfileSelection.route)
        overlayPrefsStore.clear()
        cardPresentationStore.clear()
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

    // Tab content registers as the blur source for the floating tab bar's
    // glass; the pill blurs whatever scrolls beneath it.
    val hazeState = rememberHazeState()
    // For You's Watchlist / Favorites toggle lives here so the shared header
    // can title itself after what the tab is showing.
    var forYouList by rememberSaveable { mutableStateOf<ForYouList?>(null) }
    // What For You is actually showing (the empty-feed fallback shows the
    // Watchlist without making it an explicit selection); drives the title.
    var forYouDisplayed by remember { mutableStateOf<ForYouList?>(null) }
    // Instantiate with the shell, not when the lazy tab is first opened, so
    // Discover and taste-profile requests run alongside profile/header setup.
    val recommendationsViewModel = koinViewModel<RecommendationsViewModel>()
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
                    minimizedToCurrentTab = currentTab == Tab.Home && homeBottomNavMinimized,
                    onTabSelected = { tab ->
                        if (tab == Tab.Home && currentTab == Tab.Home) {
                            // The minimized Home control remains tappable. A
                            // repeat tap expands the full capsule without
                            // disturbing the user's feed position.
                            homeBottomNavMinimized = false
                        } else {
                            navController.navigate(tab.route) {
                                // Pop to the tab stack's live anchor, not a
                                // hard-coded Home and not the graph's declared
                                // start (which can name a tab that has since
                                // been removed). Popping to a route that is not
                                // on the stack pops nothing — every tab then
                                // stacked, so Back walked back through
                                // previously visited tabs instead of leaving.
                                tabSwitchNavOptions(navController.bottomMostTabRoute())
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
            // The tab content is the blur source for both the floating pill and
            // the shared top bar. Both effects sit outside this Box (bottomBar,
            // and the sibling MainAppTopBar below) — an effect must never live
            // inside the source it reads. The background is painted inside the
            // source so the capture is opaque.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                when (currentTab) {
                    Tab.Home -> {
                        val homeViewModel = koinViewModel<HomeViewModel>()
                        HomeScreen(
                            onBottomNavMinimizedChange = { minimized ->
                                homeBottomNavMinimized = minimized
                            },
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            onContinueWatchingItemClick = { item ->
                                navController.navigate(continueWatchingDetailRoute(item))
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
                            onSwitchProfileClick = ::switchProfileFromMenu,
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
                            onSwitchProfileClick = ::switchProfileFromMenu,
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
                            savedListSelection = forYouList,
                            onSavedListSelectionChange = { forYouList = it },
                            onDisplayedListChange = { forYouDisplayed = it },
                            contentTopPadding = headerContentTop,
                            viewModel = recommendationsViewModel,
                        )
                    }
                    Tab.Calendar -> {
                        // Calendar's floating week card is its own header (iOS):
                        // the shared actions ride inside the card, no title row.
                        CalendarScreen(
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            headerActions = {
                                TabTopBarActions(
                                    activeProfile = headerState.activeProfile,
                                    onSearchClick = { navController.navigate(Route.Search().route) },
                                    onRequestsClick = requestsMenuAction,
                                    onWatchTogetherClick = watchTogetherMenuAction,
                                    onSettingsClick = { navController.navigate(Route.Settings.route) },
                                    onSwitchProfileClick = ::switchProfileFromMenu,
                                    onSwitchServerClick = {
                                        navController.navigate(Route.ServerList.route)
                                    },
                                    onSignOutClick = ::signOutFromProfileMenu,
                                )
                            },
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

            // Home, Libraries and Calendar paint their own floating chrome.
            // Downloads and For You use the shared iOS-style top chrome.
            if (currentTab == Tab.Downloads || currentTab == Tab.ForYou) {
                val title = when (currentTab) {
                    Tab.Downloads -> "Downloads"
                    // Names what For You is showing: the feed, or a saved list.
                    Tab.ForYou -> forYouDisplayed.headerTitle()
                    else -> null
                }
                MainAppTopBar(
                    activeProfile = headerState.activeProfile,
                    isProfileLoading = headerState.isLoading,
                    hazeState = hazeState,
                    onSearchClick = { navController.navigate(Route.Search().route) },
                    onRequestsClick = requestsMenuAction,
                    onWatchTogetherClick = watchTogetherMenuAction,
                    onSettingsClick = { navController.navigate(Route.Settings.route) },
                    onSwitchProfileClick = ::switchProfileFromMenu,
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
