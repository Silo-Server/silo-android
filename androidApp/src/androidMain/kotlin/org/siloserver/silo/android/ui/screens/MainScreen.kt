package org.siloserver.silo.android.ui.screens

import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import org.siloserver.silo.android.ui.components.MainAppHeaderBodyHeight
import org.siloserver.silo.android.ui.components.MainAppTopBar
import org.siloserver.silo.android.ui.components.TabContentTop
import org.siloserver.silo.android.ui.components.LocalTabHeaderScrim
import org.siloserver.silo.android.ui.components.TopBarRemoteControl
import org.siloserver.silo.android.ui.components.TabTopBarActions
import org.siloserver.silo.android.ui.navigation.BottomNavMinimizeTracker
import org.siloserver.silo.android.ui.navigation.BottomNavAccessory
import org.siloserver.silo.android.ui.navigation.BottomNavAccessoryStyle
import org.siloserver.silo.android.ui.navigation.LocalPageDockHost
import org.siloserver.silo.android.ui.navigation.PageDockHost
import org.siloserver.silo.android.ui.navigation.rememberNonShrinking
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeHeight
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.navigation.LocalBottomNavPageMovedItself
import org.siloserver.silo.android.ui.navigation.LocalBottomNavScrolledToTop
import org.siloserver.silo.android.ui.navigation.SiloBottomNavBar
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.android.ui.navigation.routeAfterSignOut
import org.siloserver.silo.android.ui.navigation.Tab
import org.siloserver.silo.android.ui.navigation.tabForRoute
import org.siloserver.silo.android.ui.navigation.tabSwitchNavOptions
import org.siloserver.silo.android.ui.navigation.bottomMostTabRoute
import org.siloserver.silo.android.ui.navigation.fallbackMobileTab
import org.siloserver.silo.android.ui.navigation.libraryCollectionDetailRoute
import org.siloserver.silo.android.ui.navigation.sectionCollectionCardRoute
import org.siloserver.silo.android.ui.navigation.scopedLocalDownloadBytes
import org.siloserver.silo.android.ui.navigation.shouldShowDownloadsTab
import org.siloserver.silo.android.ui.navigation.visibleMobileTabs
import org.siloserver.silo.android.ui.navigation.continueWatchingDetailRoute
import org.siloserver.silo.android.ui.screens.calendar.CalendarScreen
import org.siloserver.silo.android.ui.screens.home.HomeScreen
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.ui.screens.cast.SiloCastMiniBar
import org.siloserver.silo.android.ui.screens.cast.showsMiniBar
import org.siloserver.silo.android.ui.screens.audiobook.AudiobookMiniPlayer
import org.siloserver.silo.android.ui.screens.audiobook.LocalAudiobookSession
import org.siloserver.silo.android.ui.screens.cast.rememberSiloCastArtwork
import org.siloserver.silo.android.ui.screens.cast.SiloCastTargetPickerSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesScreen
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSelectorSheet
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.android.ui.screens.recommendations.ForYouList
import org.siloserver.silo.android.ui.screens.recommendations.RecommendationsScreen
import org.siloserver.silo.common.i18n.sectionTitle
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.android.ui.screens.recommendations.headerTitle
import org.siloserver.silo.android.ui.screens.watchparty.WatchPartySoloGuardDialog
import org.siloserver.silo.android.ui.screens.watchparty.rememberWatchPartySoloGuard
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.model.feature.WatchPartyExposure
import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.MediaModeCapabilities
import org.siloserver.silo.model.navigation.mobileMediaModeCapabilities
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.network.ServerReachabilityStatus
import org.siloserver.silo.android.ui.theme.siloPageBackdrop
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.OnViewerAccessChanged
import org.siloserver.silo.common.ui.rememberViewerAccessKey
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.android.cast.engagedStatus

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
    val context = LocalContext.current
    val headerViewModel = koinViewModel<MainHeaderViewModel>()
    val headerState by headerViewModel.uiState.collectAsState()
    val siloCastController: SiloCastController = koinInject()
    val siloCastState by siloCastController.state.collectAsState()
    var showSiloCastTargetPicker by rememberSaveable { mutableStateOf(false) }
    val watchPartyExposure: WatchPartyExposure = koinInject()
    val watchPartyEnabled by watchPartyExposure.enabled.collectAsState()
    // D5: quick play from these tabs asks to leave a Watch Party first.
    val soloGuard = rememberWatchPartySoloGuard()

    fun startVideo(contentId: String, fileId: Int?, resumePositionSeconds: Double?) {
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

    fun playVideo(contentId: String, fileId: Int? = null, resumePositionSeconds: Double? = null) =
        soloGuard.run { startVideo(contentId, fileId, resumePositionSeconds) }

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

    // iOS tabBarMinimizeBehavior(.onScrollDown) on every tab: scrolling a page
    // toward its end collapses the capsule to the current tab's control, and
    // it stays collapsed until the page is back at its top. One nested-scroll observer on the tab content
    // sees every vertical scrollable without per-screen plumbing, and only
    // what the content consumed: horizontal rows, a list already pinned at an
    // end, and layout-driven offset changes report nothing. Programmatic
    // scrolls (restoration, scroll-to-top) bypass nested scroll entirely, and
    // pull-to-refresh consumes the leftover drag at a page's top, so pages
    // report arriving at their top from their own scroll position
    // (ReportBottomNavPageTop) and report jumps elsewhere through
    // LocalBottomNavPageMovedItself.
    //
    // The shell is composed per tab destination, so switching tabs or
    // returning from a pushed page composes it afresh and the bar starts
    // expanded. The tracker's picture of the page (how far down it is, whether
    // it is too short to minimize) is saved with the destination, alongside the
    // page's own scroll position, so a feed restored near its end is not
    // mistaken for a short page.
    //
    // Tablets and open foldables keep the full bar, as iPad does (it never
    // minimizes a bottom bar). Phones minimize in landscape too, where the bar
    // centres as on other wide windows and keeps its accessory above it.
    val bottomNavCanMinimize = LocalConfiguration.current.smallestScreenWidthDp < 600
    val density = LocalDensity.current
    val createNavTracker = {
        with(density) {
            BottomNavMinimizeTracker(
                minimizeDistance = BottomNavMinimizeDistance.toPx(),
                shortPageHysteresis = BottomNavShortPageHysteresis.toPx(),
            )
        }
    }
    val navTracker = rememberSaveable(saver = BottomNavMinimizeTracker.saver(createNavTracker)) { createNavTracker() }
    var bottomNavMinimizedState by remember { mutableStateOf(false) }
    // Where minimizing is not allowed the tracker still follows the page (so
    // its position stays true across a fold or unfold); only what the bar
    // shows is gated.
    val bottomNavMinimized = bottomNavCanMinimize && bottomNavMinimizedState
    val navScrollConnection = remember(navTracker) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Compose scroll deltas are positive toward the top.
                navTracker.onContentScrolled(-consumed.y)
                when {
                    available.y > EdgeEpsilonPx -> navTracker.onReachedTop()
                    available.y < -EdgeEpsilonPx -> navTracker.onReachedEnd()
                }
                bottomNavMinimizedState = navTracker.minimized
                return Offset.Zero
            }
        }
    }
    fun expandBottomNav() {
        navTracker.expand()
        bottomNavMinimizedState = false
    }
    val bottomNavScrolledToTop: () -> Unit = remember(navTracker) {
        {
            navTracker.onScrolledToTop()
            bottomNavMinimizedState = false
        }
    }

    // Entering (or re-entering) any tab starts with the complete tab capsule;
    // the page's position estimate carries over with its scroll position. A
    // fold or unfold (the activity handles it, so this shell survives) also
    // starts expanded, rather than surfacing a minimize decided while the bar
    // could not show it.
    LaunchedEffect(currentTab, bottomNavCanMinimize) {
        navTracker.expand()
        bottomNavMinimizedState = false
    }
    // A different library, Movies/Series side, or filter starts the pages at
    // their tops; another page keeps its own position. Either is new content:
    // the bar expands and the page's length is measured afresh. Compared with
    // the keys saved alongside the tracker, so restoring the shell, or the
    // state arriving after entry, is not mistaken for a change.
    val libraryContentKey = librariesState?.let { "${it.selectedLibraryId}|${it.mediaScope}|${it.filterState}" }
    val libraryPageKey = librariesState?.selectedTab?.name
    var trackedLibraryContentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var trackedLibraryPageKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(libraryContentKey, libraryPageKey) {
        if (libraryContentKey == null || libraryPageKey == null) return@LaunchedEffect
        val contentChanged = trackedLibraryContentKey != null && trackedLibraryContentKey != libraryContentKey
        val pageChanged = trackedLibraryPageKey != null && trackedLibraryPageKey != libraryPageKey
        trackedLibraryContentKey = libraryContentKey
        trackedLibraryPageKey = libraryPageKey
        if (contentChanged || pageChanged) {
            navTracker.onPageChanged(startsAtTop = contentChanged)
            bottomNavMinimizedState = false
        }
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
    val reachabilityMonitor: ServerReachabilityMonitor = koinInject()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val metadataAiFeatureStore: MetadataAiFeatureStore = koinInject()
    val shuffleFeatureStore: org.siloserver.silo.model.feature.ShuffleFeatureStore = koinInject()
    val ratingSourcesStore: org.siloserver.silo.model.feature.RatingSourcesStore = koinInject()
    val signOutTeardown: org.siloserver.silo.android.auth.SignOutTeardown = koinInject()
    val switchProfile = org.siloserver.silo.android.ui.navigation.LocalProfileSwitch.current
    val reachabilityState by reachabilityMonitor.state.collectAsState()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val reachabilityScope = rememberCoroutineScope()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    // Drives the initial load and every re-load. Keyed on the active server,
    // account and profile, so switching any of them re-fetches the avatar
    // rather than leaving the previous profile's (or none) in the header.
    LaunchedEffect(activeEntry?.id, activeEntry?.accountId, activeEntry?.profileId) {
        headerViewModel.refresh()
    }
    // An access change can add or remove whole libraries: re-derive the tabs
    // and reload the Libraries hub under the new policy. The hub's ViewModel
    // outlives tab switches and pushed routes, so its cursor catches up on a
    // change reported while another tab or route was showing.
    val accessChangeSignals: AccessChangeSignals = koinInject()
    val viewerAccessKey = rememberViewerAccessKey(accessChangeSignals)
    librariesViewModel?.let { viewModel ->
        OnViewerAccessChanged(accessChangeSignals, viewModel.accessChanges) { viewModel.refreshAfterAccessChange() }
    }
    val mediaCapabilities by produceState(
        initialValue = MediaModeCapabilities(
            listOf(
                MediaMode.Video,
                MediaMode.Audio,
                MediaMode.Reading,
            ),
        ),
        personalDataRepository,
        viewerAccessKey,
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
        activeEntry?.accountId,
        activeEntry?.profileId,
        headerState.activeProfile?.id,
    ) {
        value = scopedLocalDownloadBytes(
            storage = downloadStorage,
            serverId = activeEntry?.id,
            accountId = activeEntry?.accountId,
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
    // overlap content on tall-cutout / large-display devices. Tabs without a
    // pill row (Downloads) start at the first-row line instead.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerContentTop = statusBarTop + MainAppHeaderBodyHeight
    val tabContentTop = statusBarTop + TabContentTop

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

    LaunchedEffect(activeEntry?.id, activeEntry?.accountId, activeEntry?.profileId, headerState.activeProfile?.id) {
        // First, in its own coroutine: sort lists need it, and it should not
        // wait on the suspending refreshes below.
        ratingSourcesStore.reset()
        launch { ratingSourcesStore.refresh() }
        requestsFeatureStore.reset()
        requestsFeatureStore.refresh()
        metadataAiFeatureStore.reset()
        metadataAiFeatureStore.refresh()
        shuffleFeatureStore.reset()
        shuffleFeatureStore.refresh()
    }

    fun signOutFromProfileMenu(removeServer: Boolean) {
        reachabilityScope.launch {
            // Same teardown as the Settings sign-out: otherwise the next
            // user's shell renders (and can write back) the previous
            // profile's overlays and card presentation.
            val signedOut = if (removeServer) signOutTeardown.signOutAndRemoveServer() else signOutTeardown.signOut()
            if (!signedOut) return@launch
            // Change Account while another account has a session, else sign-in.
            navController.navigate(serverRegistry.routeAfterSignOut()) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    /**
     * Profile-menu "Switch Profile": pushes pending settings while this
     * profile is still active, then leaves the shell and drops the per-profile
     * caches (see [org.siloserver.silo.android.auth.ProfileSwitchTeardown]).
     * Runs in the navigation host's scope ([org.siloserver.silo.android.ui.navigation.LocalProfileSwitch]).
     */
    fun switchProfileFromMenu() = switchProfile()

    /** Profile-menu "Change account": every saved server's accounts, over the shell so Back returns. */
    fun changeAccountFromMenu() {
        navController.navigate(Route.ChangeAccount().route) { launchSingleTop = true }
    }
    val requestsMenuAction: (() -> Unit)? = if (requestsEnabled) {
        { navController.navigate(Route.Requests.route) }
    } else {
        null
    }
    val watchPartyMenuAction: (() -> Unit)? =
        if (watchPartyEnabled) {
            { navController.navigate(Route.WatchPartyHub().route) { launchSingleTop = true } }
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
    // How far For You / Downloads content sits under the shared header; the
    // tab's own list reports it (see ReportTabHeaderScrim).
    val headerScrim = remember { mutableFloatStateOf(0f) }
    // Instantiate with the shell, not when the lazy tab is first opened, so
    // Discover and taste-profile requests run alongside profile/header setup.
    val recommendationsViewModel = koinViewModel<RecommendationsViewModel>()
    // Every tab header shows the remote-control button beside search.
    val remoteControl = TopBarRemoteControl(
        isActive = siloCastState.hasActiveSession,
        status = siloCastState.engagedStatus(),
        onOpen = {
            if (siloCastState.hasActiveSession) {
                navController.navigate(Route.SiloCastRemote.route)
            } else {
                showSiloCastTargetPicker = true
            }
        },
        onChooseTv = { showSiloCastTargetPicker = true },
        onDisconnect = { siloCastController.disconnect() },
    )
    // Resolved here, above both cast-bar placements, so the bar keeps its
    // title and poster while it moves between them.
    val siloCastArtwork = rememberSiloCastArtwork(
        siloCastState.launch?.contentId ?: siloCastState.playbackState?.contentId,
    )
    val activeAudiobook = LocalAudiobookSession.current?.active?.collectAsState()?.value
    // The tab bar's accessory slot (iOS NowPlayingShelf): Remote Control while
    // a TV session is up, then the audiobook (it keeps playing, and its bar
    // returns when the remote's leaves), then the page dock of the tab on
    // screen. Now playing keeps priority; the page then floats its own dock.
    val nowPlayingHoldsSlot = siloCastState.showsMiniBar || activeAudiobook != null
    val nowPlayingState = rememberUpdatedState(nowPlayingHoldsSlot)
    val pageDockHost = remember(nowPlayingState) { PageDockHost(nowPlayingState) }
    val pageDock = pageDockHost.dock
    val bottomNavAccessory = when {
        siloCastState.showsMiniBar -> BottomNavAccessory(
            key = "remote-control",
            style = BottomNavAccessoryStyle.NowPlaying,
        ) { inline ->
            SiloCastMiniBar(
                controller = siloCastController,
                onOpenRemote = {
                    navController.navigate(Route.SiloCastRemote.route) { launchSingleTop = true }
                },
                inline = inline,
                drawsCard = false,
                sharedArtwork = siloCastArtwork,
            )
        }
        activeAudiobook != null -> BottomNavAccessory(
            key = "audiobook",
            style = BottomNavAccessoryStyle.NowPlaying,
        ) { inline ->
            AudiobookMiniPlayer(
                player = activeAudiobook.player,
                onOpen = {
                    navController.navigate(activeAudiobook.request.resumeRoute()) { launchSingleTop = true }
                },
                inline = inline,
            )
        }
        pageDock != null -> BottomNavAccessory(
            key = "page-dock",
            style = BottomNavAccessoryStyle.PageDock,
            content = pageDock,
        )
        else -> null
    }
    Scaffold(
        bottomBar = {
            // The slot's holder rests above the nav menu (iOS
            // tabViewBottomAccessory placement) and moves inline beside the
            // minimized control; the Scaffold pads tab content past the whole
            // chrome.
            SiloBottomNavBar(
                currentTab = currentTab,
                minimizedToCurrentTab = bottomNavMinimized,
                onTabSelected = { tab ->
                    if (tab == currentTab) {
                        // The minimized control remains tappable. A tap on
                        // the current tab expands the full capsule without
                        // navigating or moving the page's scroll position.
                        expandBottomNav()
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
                accessory = bottomNavAccessory,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // The chrome's live height follows the accessory as it moves inline
        // or changes hands. Scroll padding never shrinks while this tab is on
        // screen, so a list resting at its end is not pulled down when the bar
        // minimizes or a now-playing bar ends (see LocalBottomChromeInset).
        // Read only through these: the bar's measured height changes every
        // frame while it moves, and reading it in composition here would
        // recompose the whole tab body on each of them.
        val liveChrome = remember(padding) { { padding.calculateBottomPadding() } }
        val configuration = LocalConfiguration.current
        val contentChromeInset by rememberNonShrinking(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            value = liveChrome,
        )
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
                LocalBottomChromeInset provides contentChromeInset,
                LocalBottomChromeHeight provides liveChrome,
                LocalBottomNavScrolledToTop provides bottomNavScrolledToTop,
                LocalBottomNavPageMovedItself provides navTracker::onPageMovedItself,
                LocalPageDockHost provides pageDockHost,
            ) {
            // The tab content is the blur source for both the floating pill and
            // the shared top bar. Both effects sit outside this Box (bottomBar,
            // and the sibling MainAppTopBar below) — an effect must never live
            // inside the source it reads. The background is painted inside the
            // source so the capture is opaque.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { navTracker.viewportHeight = it.height.toFloat() }
                    .nestedScroll(navScrollConnection)
                    .hazeSource(hazeState)
                    .siloPageBackdrop(),
            ) {
                when (currentTab) {
                    Tab.Home -> {
                        val homeViewModel = koinViewModel<HomeViewModel>()
                        HomeScreen(
                            onItemClick = { contentId ->
                                navController.navigate(Route.ItemDetail(contentId).route)
                            },
                            onContinueWatchingItemClick = { item ->
                                navController.navigate(continueWatchingDetailRoute(item))
                            },
                            onPlayClick = { contentId, resumePositionSeconds ->
                                playVideo(contentId, resumePositionSeconds = resumePositionSeconds)
                            },
                            onExploreSection = { section ->
                                navController.navigate(Route.SectionBrowse(section.id, context.sectionTitle(section)).route)
                            },
                            onCollectionClick = { card ->
                                sectionCollectionCardRoute(card)?.let { navController.navigate(it) }
                            },
                            viewModel = homeViewModel,
                            activeProfile = headerState.activeProfile,
                            onSearchClick = { navController.navigate(Route.Search().route) },
                            remoteControl = remoteControl,
                            onRequestsClick = requestsMenuAction,
                            onWatchPartyClick = watchPartyMenuAction,
                            onSettingsClick = { navController.navigate(Route.Settings.route) },
                            onSwitchProfileClick = ::switchProfileFromMenu,
                            onChangeAccountClick = ::changeAccountFromMenu,
                            onSignOutClick = ::signOutFromProfileMenu,
                        )
                    }
                    Tab.Libraries -> {
                        val shuffleLauncher = org.siloserver.silo.android.ui.screens.shuffle.rememberShuffleLauncher { shuffle ->
                            navController.navigate(
                                org.siloserver.silo.android.ui.screens.shuffle.shufflePlayerRoute(
                                    shuffle,
                                    libraryId = shuffle.scope.id.toIntOrNull(),
                                ),
                            )
                        }
                        LibrariesScreen(
                            shuffleLauncher = shuffleLauncher,
                            onItemClick = { contentId, libraryId ->
                                navController.navigate(Route.ItemDetail(contentId, libraryId = libraryId).route)
                            },
                            onCollectionClick = { collection, libraryId, mediaScope ->
                                navController.navigate(
                                    libraryCollectionDetailRoute(collection, libraryId, mediaScope),
                                )
                            },
                            onPersonalCollectionClick = { collectionId ->
                                navController.navigate(Route.CollectionDetail(collectionId).route)
                            },
                            onExploreSection = { section, libraryId, mediaScope ->
                                navController.navigate(
                                    Route.SectionBrowse(section.id, context.sectionTitle(section), libraryId, mediaScope).route,
                                )
                            },
                            viewModel = requireNotNull(librariesViewModel),
                            activeProfile = headerState.activeProfile,
                            remoteControl = remoteControl,
                            onLibrarySelectorClick = { showLibrarySelector = true },
                            onSearchClick = { navController.navigate(Route.Search().route) },
                            onRequestsClick = requestsMenuAction,
                            onWatchPartyClick = watchPartyMenuAction,
                            onSettingsClick = { navController.navigate(Route.Settings.route) },
                            onSwitchProfileClick = ::switchProfileFromMenu,
                            onChangeAccountClick = ::changeAccountFromMenu,
                            onSignOutClick = ::signOutFromProfileMenu,
                        )
                    }
                    Tab.ForYou -> CompositionLocalProvider(LocalTabHeaderScrim provides headerScrim) {
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
                                    remoteControl = remoteControl,
                                    onSearchClick = { navController.navigate(Route.Search().route) },
                                    onRequestsClick = requestsMenuAction,
                                    onWatchPartyClick = watchPartyMenuAction,
                                    onSettingsClick = { navController.navigate(Route.Settings.route) },
                                    onSwitchProfileClick = ::switchProfileFromMenu,
                                    onChangeAccountClick = ::changeAccountFromMenu,
                                    onSignOutClick = ::signOutFromProfileMenu,
                                )
                            },
                        )
                    }
                    Tab.Downloads -> CompositionLocalProvider(LocalTabHeaderScrim provides headerScrim) {
                        org.siloserver.silo.android.ui.screens.downloads.DownloadsScreen(
                            // Tap on a downloaded row goes directly to the right
                            // player so it works offline (ItemDetail needs the
                            // server and would block with "No internet"). Route
                            // audiobooks to the audiobook player so they get the
                            // audiobook UI + offline resume; everything else uses
                            // the video player's offline-first tryLocalPlayback.
                            onItemClick = { item ->
                                soloGuard.run {
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
                                }
                            },
                            onReadEbook = { contentId, fileId ->
                                navController.navigate(Route.BookReader(contentId, fileId).route)
                            },
                            // Downloads has no pill row; its list still starts
                            // where every other tab's first row does.
                            contentTopPadding = tabContentTop,
                        )
                    }
                }
            }

            // Home, Libraries and Calendar paint their own floating chrome.
            // Downloads and For You use the shared iOS-style top chrome.
            if (currentTab == Tab.Downloads || currentTab == Tab.ForYou) {
                val title = when (currentTab) {
                    Tab.Downloads -> stringResource(CommonR.string.tab_downloads)
                    // Names what For You is showing: the feed, or a saved list.
                    Tab.ForYou -> forYouDisplayed.headerTitle()
                    else -> null
                }
                MainAppTopBar(
                    activeProfile = headerState.activeProfile,
                    remoteControl = remoteControl,
                    isProfileLoading = headerState.isLoading,
                    hazeState = hazeState,
                    scrimProgress = headerScrim.floatValue,
                    onSearchClick = { navController.navigate(Route.Search().route) },
                    onRequestsClick = requestsMenuAction,
                    onWatchPartyClick = watchPartyMenuAction,
                    onSettingsClick = { navController.navigate(Route.Settings.route) },
                    onSwitchProfileClick = ::switchProfileFromMenu,
                    onChangeAccountClick = ::changeAccountFromMenu,
                    onSignOutClick = ::signOutFromProfileMenu,
                    leadingContent = {
                        if (title != null) {
                            // Same type as Libraries' selector title.
                            Text(
                                text = title,
                                fontSize = 18.sp,
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

            WatchPartySoloGuardDialog(soloGuard)
        }
    }
    }
}

// Content travel toward the end that minimizes the tab bar. Only the top of
// the page (or a tap on the minimized control) expands it again.
private val BottomNavMinimizeDistance: Dp = 32.dp
// iOS LibraryDock's 80pt: a page judged too short to minimize must scroll this
// far past one viewport before it minimizes again.
private val BottomNavShortPageHysteresis: Dp = 80.dp
// Sub-pixel leftovers are rounding, not a scroll running into an edge.
private const val EdgeEpsilonPx = 0.5f

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
            text = stringResource(CommonR.string.shell_offline_tapToRetry),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
