package org.siloserver.silo.android.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import org.siloserver.silo.android.cast.GoogleCastMiniBar
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.cast.SiloCastSessionManager
import org.siloserver.silo.android.ui.screens.cast.SiloCastMiniBar
import org.siloserver.silo.android.ui.screens.cast.SiloCastRemoteScreen
import org.siloserver.silo.android.ui.screens.MainScreen
import org.siloserver.silo.android.ui.screens.auth.LoginScreen
import org.siloserver.silo.android.ui.screens.auth.DevicePairingScreen
import org.siloserver.silo.android.ui.screens.auth.ServerSetupScreen
import org.siloserver.silo.android.ui.screens.auth.SetupScreen
import org.siloserver.silo.android.ui.screens.auth.InviteClaimScreen
import org.siloserver.silo.android.ui.screens.auth.SignupScreen
import org.siloserver.silo.android.ui.screens.onboarding.OnboardingTourScreen
import org.siloserver.silo.android.ui.screens.browse.BrowseScreen
import org.siloserver.silo.android.ui.screens.browse.BrowseViewModel
import org.siloserver.silo.android.ui.screens.calendar.CalendarScreen
import org.siloserver.silo.android.ui.screens.collections.CollectionDetailScreen
import org.siloserver.silo.android.ui.screens.collections.CollectionsScreen
import org.siloserver.silo.android.ui.screens.collections.LibraryCollectionsScreen
import org.siloserver.silo.android.ui.screens.detail.ItemDetailScreen
import org.siloserver.silo.android.ui.screens.detail.ItemDetailViewModel
import org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherEntrySheet
import org.siloserver.silo.android.ui.screens.watchtogether.WatchTogetherLobbyScreen
import org.siloserver.silo.android.ui.screens.people.PersonDetailScreen
import org.siloserver.silo.android.ui.screens.people.PersonDetailViewModel
import org.siloserver.silo.android.ui.screens.notifications.InboxScreen
import org.siloserver.silo.android.ui.screens.personal.FavoritesScreen
import org.siloserver.silo.android.ui.screens.personal.HistoryScreen
import org.siloserver.silo.android.ui.screens.personal.PersonalListsScreen
import org.siloserver.silo.android.ui.screens.personal.WatchlistScreen
import org.siloserver.silo.android.ui.screens.player.PlayerScreen
import org.siloserver.silo.android.ui.screens.profiles.CreateProfileScreen
import org.siloserver.silo.android.ui.screens.profiles.EditProfileScreen
import org.siloserver.silo.android.ui.screens.profiles.ProfileSelectionScreen
import org.siloserver.silo.android.ui.screens.requests.MyRequestsScreen
import org.siloserver.silo.android.ui.screens.requests.RequestDetailScreen
import org.siloserver.silo.android.ui.screens.requests.RequestsScreen
import org.siloserver.silo.android.ui.screens.search.MobileSearchMediaType
import org.siloserver.silo.android.ui.screens.search.SearchScreen
import org.siloserver.silo.android.ui.screens.search.SearchViewModel
import org.siloserver.silo.android.ui.screens.servers.ServerListScreen
import org.siloserver.silo.android.ui.screens.servers.ServerSwitchDestination
import org.siloserver.silo.android.ui.screens.settings.CardOverlaySettingsScreen
import org.siloserver.silo.android.ui.screens.settings.SettingsScreen
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsPromptDialog
import org.siloserver.silo.common.diagnostics.DiagnosticsLifecycleLogger
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsReportScreen
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsSettingsScreen
import org.siloserver.silo.android.ui.screens.settings.diagnostics.DiagnosticsViewModel
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.common.overlays.ProvideCardOverlays
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.network.TokenManager
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Page-to-page cross-fade duration (ms). Snappier than Compose Nav's 700ms default. */
private const val PageFadeDurationMs = 200

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Route.Login.route,
    pendingExternalRoute: String? = null,
    onExternalRouteConsumed: () -> Unit = {},
) {
    val tokenManager: TokenManager = koinInject()
    val overlayPrefsStore: OverlayPrefsStore = koinInject()
    val siloCastController: SiloCastController = koinInject()
    val diagnosticsViewModel = koinViewModel<DiagnosticsViewModel>()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()

    DisposableEffect(siloCastController) {
        siloCastController.startBrowsing()
        onDispose { siloCastController.stopBrowsing() }
    }

    // Graceful handling of server-side session invalidation (refresh 401'd).
    // The TokenManager has already wiped the active server's tokens by the
    // time this fires; we just route the user back to Login so they can
    // re-authenticate against the same server.
    LaunchedEffect(Unit) {
        tokenManager.sessionExpired.collect {
            navController.navigate(Route.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Keyed on the route so a notification arriving later restarts the
    // collection; currentBackStackEntryFlow emits the current entry
    // immediately on collect, so both "route arrives while on Main" and
    // "Main arrives with route queued" are covered.
    LaunchedEffect(pendingExternalRoute) {
        // Consume only while the main (authenticated) graph is showing —
        // a notification tapped pre-sign-in stays queued until auth lands,
        // instead of pushing its target over Login. The back-stack flow makes
        // this re-fire when Main arrives with the route still pending.
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = pendingExternalRoute?.takeIf { it.isNotBlank() } ?: return@collect
            // Every pre-auth / onboarding destination — a notification tapped
            // on any of these stays queued until the authenticated graph
            // shows, instead of pushing a content route that would 401.
            val authRoutes = setOf(
                Route.Login.route,
                Route.ServerSetup.route,
                Route.ServerList.route,
                Route.Setup.route,
                Route.Signup.route,
                Route.ProfileSelection.route,
                Route.CreateProfile.route,
                Route.EditProfile.ROUTE,
                Route.PairDevice.ROUTE,
                Route.InviteClaim.ROUTE,
                Route.OnboardingTour.route,
            )
            // An invite claim is itself the signed-out flow — holding it
            // until the authenticated graph shows would queue it forever on
            // Login, which is exactly where an invitee starts.
            val isPreAuthTarget = route.startsWith("invite_claim")
            if (!isPreAuthTarget && entry.destination.route in authRoutes) return@collect
            navController.navigate(route) {
                launchSingleTop = true
            }
            onExternalRouteConsumed()
        }
    }

    // Re-read the authenticated profile id whenever the current destination
    // changes (Login → ProfileSelection → Main). This drives card-overlay
    // hydration off the authenticated identity instead of a one-shot at app
    // start, where the user is still on Login and the settings calls 401.
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route) {
        DiagnosticsLifecycleLogger.route(currentEntry?.destination?.route)
    }
    val overlaySessionKey by produceState<String?>(
        initialValue = null,
        currentEntry?.destination?.route,
    ) {
        value = tokenManager.getProfileId()
    }

    ProvideCardOverlays(store = overlayPrefsStore, sessionKey = overlaySessionKey) {
    // Shared-element host: lets a tapped poster morph into the item-detail
    // backdrop. The scope is published via CompositionLocal so deep descendants
    // (a poster card, the detail hero) can opt in without threading it through
    // every composable signature in between.
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
    // One hand-off shared by every poster source and the detail hero target, so
    // the tapped card's unique key reaches the backdrop (iOS pendingZoomSourceID).
    val heroSourceHandoff = remember { HeroSourceHandoff() }
    CompositionLocalProvider(
        LocalSharedTransitionScope provides this,
        LocalHeroSourceHandoff provides heroSourceHandoff,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Fill the bounded Surface so destinations get a bounded height. Without
        // this the host wraps to content, leaking unbounded height into screens
        // like the reader whose WebView paginates against the viewport height.
        modifier = Modifier.fillMaxSize(),
        // Compose Navigation defaults to a 700ms cross-fade, which reads as
        // sluggish page-to-page (Jim, Fold). Snap it to a quick fade; the
        // poster→detail hero morph rides the same scope, so keep it long enough
        // to stay smooth.
        enterTransition = { fadeIn(tween(PageFadeDurationMs)) },
        exitTransition = { fadeOut(tween(PageFadeDurationMs)) },
        popEnterTransition = { fadeIn(tween(PageFadeDurationMs)) },
        popExitTransition = { fadeOut(tween(PageFadeDurationMs)) },
    ) {
        // ---- Auth flow ----
        composable(Route.ServerSetup.route) {
            ServerSetupScreen(
                onNavigateToSetup = {
                    navController.navigate(Route.Setup.route) {
                        popUpTo(Route.ServerSetup.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { _ ->
                    navController.navigate(Route.Login.route) {
                        popUpTo(Route.ServerSetup.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.Login.route) {
            LoginScreen(
                onNavigateToSignup = {
                    navController.navigate(Route.Signup.route)
                },
                onNavigateToProfiles = {
                    navController.navigate(Route.ProfileSelection.route) {
                        popUpTo(Route.Login.route) { inclusive = true }
                    }
                },
                onChangeServer = {
                    navController.navigate(Route.ServerSetup.route) {
                        popUpTo(Route.Login.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.Setup.route) {
            SetupScreen(
                onNavigateToProfiles = {
                    navController.navigate(Route.ProfileSelection.route) {
                        popUpTo(Route.Setup.route) { inclusive = true }
                    }
                },
            )
        }
        composable(Route.Signup.route) {
            SignupScreen(
                onNavigateToLogin = {
                    navController.popBackStack()
                },
                onNavigateToProfiles = {
                    navController.navigate(Route.ProfileSelection.route) {
                        popUpTo(Route.Signup.route) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Route.InviteClaim.ROUTE,
            arguments = listOf(
                navArgument("server") { type = NavType.StringType },
                navArgument("token") { type = NavType.StringType },
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "silo://invite?server={server}&token={token}" },
            ),
        ) { backStackEntry ->
            val server = backStackEntry.arguments?.getString("server").orEmpty()
            val claimToken = backStackEntry.arguments?.getString("token").orEmpty()
            InviteClaimScreen(
                serverUrl = server,
                token = claimToken,
                onNavigateToLogin = {
                    navController.navigate(Route.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onClaimComplete = {
                    navController.navigate(Route.ProfileSelection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.OnboardingTour.route) {
            OnboardingTourScreen(
                onDone = {
                    navController.navigate(Route.Home.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Route.PairDevice.ROUTE,
            arguments = listOf(
                navArgument("token") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("code") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "silo://device?token={token}" },
                navDeepLink { uriPattern = "silo://device?code={code}" },
            ),
        ) { backStackEntry ->
            val token = backStackEntry.arguments?.getString("token")
            val code = backStackEntry.arguments?.getString("code")
            DevicePairingScreen(
                token = token,
                code = code,
                onDone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Route.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSignIn = {
                    navController.navigate(Route.Login.route)
                },
            )
        }

        // ---- Server list (multi-server management) ----
        composable(Route.ServerList.route) {
            ServerListScreen(
                onAddServer = {
                    navController.navigate(Route.ServerSetup.route)
                },
                onSwitched = { destination ->
                    // Route to whichever screen the new server's stored
                    // credentials can support — Home if a token+profile
                    // already exist (so the user stays signed in), else
                    // ProfileSelection or Login as appropriate.
                    val target = when (destination) {
                        // Through the tour gate, not straight to Home — the
                        // switched-to server's profile may not have seen the
                        // tour; the gate short-circuits when it has.
                        ServerSwitchDestination.Home -> Route.OnboardingTour.route
                        ServerSwitchDestination.ProfileSelection -> Route.ProfileSelection.route
                        ServerSwitchDestination.Login -> Route.Login.route
                    }
                    navController.navigate(target) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ---- Profile selection ----
        composable(Route.ProfileSelection.route) {
            ProfileSelectionScreen(
                onNavigateToHome = {
                    // Route through the tour gate: OnboardingTourScreen checks
                    // server-side state and immediately hands off to Home when
                    // the profile has already completed or skipped the tour.
                    navController.navigate(Route.OnboardingTour.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToCreateProfile = {
                    navController.navigate(Route.CreateProfile.route)
                },
                onNavigateToEditProfile = { profileId ->
                    navController.navigate(Route.EditProfile(profileId).route)
                },
            )
        }
        composable(Route.CreateProfile.route) {
            CreateProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onProfileCreated = {
                    navController.popBackStack(
                        route = Route.ProfileSelection.route,
                        inclusive = false,
                    )
                },
            )
        }
        composable(
            route = Route.EditProfile.ROUTE,
            arguments = listOf(
                navArgument("profileId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId").orEmpty()
            EditProfileScreen(
                profileId = profileId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ---- Main tabs ----
        composable(Route.Home.route) {
            // Publish this destination's visibility scope so poster cards in the
            // home rails can morph into the detail hero on tap.
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                MainScreen(navController, Tab.Home)
            }
        }
        composable(Route.Libraries.route) {
            MainScreen(navController, Tab.Libraries)
        }
        composable(Route.Recommendations.route) {
            MainScreen(navController, Tab.ForYou)
        }
        composable(Route.Downloads.route) {
            MainScreen(navController, Tab.Downloads)
        }
        composable(Route.Inbox.route) {
            InboxScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        // ---- Legacy route aliases (defensive) ----
        // Pre-consolidation builds had standalone Video/Audio/Reading
        // destinations; they were folded into the Home shell. A NavController
        // back stack restored from such a build could still reference these route
        // strings — and navigating to a route with no registered destination
        // throws (crash on launch). Register no-op aliases that redirect to Home
        // so a restore can never hit an unregistered destination.
        for (legacyRoute in listOf("video", "audio", "reading")) {
            composable(legacyRoute) {
                LaunchedEffect(Unit) {
                    navController.navigate(Route.Home.route) {
                        popUpTo(legacyRoute) { inclusive = true }
                    }
                }
            }
        }
        composable(Route.Settings.route) {
            SettingsScreen(
                onNavigateToServers = {
                    navController.navigate(Route.ServerList.route)
                },
                onPairDevice = {
                    navController.navigate(Route.PairDevice().route)
                },
                onNavigateToAdmin = {
                    navController.navigate(Route.Admin.route)
                },
                onSwitchProfile = { navController.navigate(Route.ProfileSelection.route) },
                onNavigateToWatchlist = { navController.navigate(Route.Watchlist.route) },
                onNavigateToFavorites = { navController.navigate(Route.Favorites.route) },
                onNavigateToHistory = { navController.navigate(Route.History.route) },
                onNavigateToCollections = { navController.navigate(Route.Collections().route) },
                onNavigateToCardOverlays = {
                    navController.navigate(Route.CardOverlays.route)
                },
                onNavigateToDiagnostics = {
                    navController.navigate(Route.Diagnostics.route)
                },
                onLoggedOut = {
                    navController.navigate(Route.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                showTopBar = true,
                onBackClick = { navController.popBackStack() },
            )
        }
        composable(Route.CardOverlays.route) {
            CardOverlaySettingsScreen(
                store = overlayPrefsStore,
                onBackClick = { navController.popBackStack() },
            )
        }
        composable(Route.Diagnostics.route) {
            DiagnosticsSettingsScreen(
                onBackClick = { navController.popBackStack() },
                onReportSelected = { reportId ->
                    navController.navigate(Route.DiagnosticsReport(reportId).route)
                },
            )
        }
        composable(
            route = Route.DiagnosticsReport.ROUTE,
            arguments = listOf(navArgument("reportId") { type = NavType.StringType }),
        ) { backStackEntry ->
            DiagnosticsReportScreen(
                reportId = backStackEntry.arguments?.getString("reportId").orEmpty(),
                onBackClick = { navController.popBackStack() },
            )
        }
        composable(Route.SiloCastRemote.route) {
            SiloCastRemoteScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Route.Search.ROUTE,
            arguments = listOf(
                navArgument("mediaType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val searchViewModel = koinViewModel<SearchViewModel>()
            SearchScreen(
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
                onRequestMediaClick = { item ->
                    navController.navigate(Route.RequestDetail(item.mediaType, item.tmdbId).route)
                },
                onRequestLibraryItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
                onBackClick = { navController.popBackStack() },
                viewModel = searchViewModel,
                initialMediaType = MobileSearchMediaType.fromRouteValue(
                    backStackEntry.arguments?.getString("mediaType"),
                ),
            )
        }

        // ---- Requests ----
        composable(Route.Requests.route) {
            RequestsScreen(
                onBackClick = { navController.popBackStack() },
                onMyRequestsClick = { navController.navigate(Route.MyRequests.route) },
                onMediaClick = { item ->
                    navController.navigate(Route.RequestDetail(item.mediaType, item.tmdbId).route)
                },
                onLibraryItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        composable(Route.MyRequests.route) {
            MyRequestsScreen(
                onBackClick = { navController.popBackStack() },
                onRequestClick = { request ->
                    request.libraryContentId?.takeIf { it.isNotBlank() }?.let { contentId ->
                        navController.navigate(Route.ItemDetail(contentId).route)
                    } ?: navController.navigate(Route.RequestDetail(request.mediaType, request.tmdbId).route)
                },
            )
        }
        composable(
            route = Route.RequestDetail.ROUTE,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("tmdbId") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val mediaType = backStackEntry.arguments?.getString("mediaType").orEmpty()
            val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
            RequestDetailScreen(
                mediaType = mediaType,
                tmdbId = tmdbId,
                onBackClick = { navController.popBackStack() },
                onMediaClick = { item ->
                    navController.navigate(Route.RequestDetail(item.mediaType, item.tmdbId).route)
                },
                onLibraryItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }

        // ---- Browse / Catalog ----
        composable(
            route = Route.Browse.ROUTE,
            arguments = listOf(
                navArgument("libraryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            val browseViewModel = koinViewModel<BrowseViewModel>()
            BrowseScreen(
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
                onBackClick = { navController.popBackStack() },
                viewModel = browseViewModel,
            )
        }

        composable(
            route = Route.CollectionDetail.ROUTE,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("libraryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            CollectionDetailScreen(
                collectionId = backStackEntry.arguments?.getString("collectionId") ?: "",
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }

        // ---- Detail screens ----
        composable(
            route = Route.ItemDetail.ROUTE,
            arguments = listOf(
                navArgument("contentId") { type = NavType.StringType },
                navArgument("seasonNumber") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            // Publish this destination's visibility scope so the detail backdrop
            // (and the "More Like This" rail) take part in the hero morph.
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            val detailViewModel = koinViewModel<ItemDetailViewModel>()
            var wtTarget by remember { mutableStateOf<Pair<String, Int?>?>(null) }
            ItemDetailScreen(
                onBackClick = { navController.popBackStack() },
                onPlayClick = { contentId, fileId, audioTrackIndex, subtitleTrackIndex, resumePositionSeconds ->
                    val launchedRemotely = siloCastController.launchOnConnectedTarget(
                        SiloCastPlaybackRequest(
                            contentId = contentId,
                            fileId = fileId,
                            audioTrackIndex = audioTrackIndex,
                            subtitleTrackIndex = subtitleTrackIndex,
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
                                audioTrackIndex = audioTrackIndex,
                                subtitleTrackIndex = subtitleTrackIndex,
                                resumePositionSeconds = resumePositionSeconds,
                            ).route,
                        )
                    }
                },
                onItemDetailClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
                onSeriesClick = { seriesId ->
                    navController.navigate(Route.ItemDetail(seriesId).route)
                },
                onSeasonClick = { seriesId, seasonNumber ->
                    navController.navigate(Route.ItemDetail(seriesId, seasonNumber).route)
                },
                onPersonClick = { personId ->
                    personId.toLongOrNull()?.let { id ->
                        navController.navigate(Route.PersonDetail(id).route)
                    }
                },
                onAudiobookPlayClick = { contentId, fileId, fromStart, startPosition ->
                    navController.navigate(
                        Route.AudiobookPlayer(contentId, fileId, fromStart, startPosition).route,
                    )
                },
                onBookReadClick = { contentId, fileId ->
                    navController.navigate(Route.BookReader(contentId, fileId).route)
                },
                onWatchTogether = { contentId, fileId -> wtTarget = contentId to fileId },
                onOpenCastRemote = {
                    navController.navigate(Route.SiloCastRemote.route) { launchSingleTop = true }
                },
                viewModel = detailViewModel,
            )
            wtTarget?.let { (cid, fid) ->
                WatchTogetherEntrySheet(
                    contentId = cid,
                    fileId = fid,
                    onNavigate = { route -> navController.navigate(route) },
                    onDismiss = { wtTarget = null },
                )
            }
            }
        }

        // ---- Watch Together lobby ----
        composable(
            route = Route.WatchTogetherLobby.ROUTE,
            arguments = listOf(
                navArgument(Route.WatchTogetherLobby.ARG_ROOM_ID) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments
                ?.getString(Route.WatchTogetherLobby.ARG_ROOM_ID)
                .orEmpty()
            WatchTogetherLobbyScreen(
                roomId = roomId,
                onNavigateToPlayer = { route ->
                    navController.navigate(route) {
                        popUpTo(Route.WatchTogetherLobby.ROUTE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ---- Audiobook player (audio-only UI) ----
        composable(
            route = Route.AudiobookPlayer.ROUTE,
            arguments = listOf(
                navArgument(Route.AudiobookPlayer.ARG_CONTENT_ID) { type = NavType.StringType },
                navArgument(Route.AudiobookPlayer.ARG_FILE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Route.AudiobookPlayer.ARG_FROM_START) {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument(Route.AudiobookPlayer.ARG_START_POSITION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            org.siloserver.silo.android.ui.screens.audiobook.AudiobookPlayerScreen(
                onBackClick = { navController.popBackStack() },
            )
        }

        // ---- Book reader (dispatches on format) ----
        composable(
            route = Route.BookReader.ROUTE,
            arguments = listOf(
                navArgument(Route.BookReader.ARG_CONTENT_ID) { type = NavType.StringType },
                navArgument(Route.BookReader.ARG_FILE_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            org.siloserver.silo.android.ui.screens.reader.ReaderScreen(
                onBackClick = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.PersonDetail.ROUTE,
            arguments = listOf(
                navArgument("personId") { type = NavType.LongType },
            ),
        ) {
            val personViewModel = koinViewModel<PersonDetailViewModel>()
            PersonDetailScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
                viewModel = personViewModel,
            )
        }

        // ---- Player (fullscreen) ----
        composable(
            route = Route.Player.ROUTE,
            arguments = listOf(
                navArgument("contentId") { type = NavType.StringType },
                navArgument("fileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("quality") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("audioTrackIndex") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("subtitleTrackIndex") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("resumePosition") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("roomId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            PlayerScreen(
                contentId = backStackEntry.arguments?.getString("contentId") ?: "",
                initialFileId = backStackEntry.arguments?.getString("fileId")?.toIntOrNull(),
                initialQuality = VideoPlayerRouteArgs.normalizeQuality(
                    backStackEntry.arguments?.getString("quality"),
                ),
                initialAudioTrackIndex = backStackEntry.arguments?.getString("audioTrackIndex")?.toIntOrNull(),
                initialSubtitleTrackIndex = backStackEntry.arguments?.getString("subtitleTrackIndex")?.toIntOrNull(),
                resumePositionOverride = VideoPlayerRouteArgs.parseResumePosition(
                    backStackEntry.arguments?.getString("resumePosition"),
                ),
                roomId = backStackEntry.arguments?.getString("roomId"),
                navController = navController,
            )
        }

        // ---- Personal data ----
        composable(Route.Favorites.route) {
            FavoritesScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        composable(Route.Admin.route) {
            org.siloserver.silo.android.ui.screens.admin.AdminStatsScreen(
                onBackClick = { navController.popBackStack() },
            )
        }
        composable(Route.Watchlist.route) {
            WatchlistScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        composable(Route.PersonalLists.route) {
            PersonalListsScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        composable(Route.Calendar.route) {
            MainScreen(navController, Tab.Calendar)
        }
        composable(Route.History.route) {
            HistoryScreen(
                onBackClick = { navController.popBackStack() },
                onItemClick = { contentId ->
                    navController.navigate(Route.ItemDetail(contentId).route)
                },
            )
        }
        composable(
            route = Route.Collections.ROUTE,
            arguments = listOf(
                navArgument("libraryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val libraryId = backStackEntry.arguments?.getString("libraryId")?.toIntOrNull()
            if (libraryId != null) {
                LibraryCollectionsScreen(
                    onBackClick = { navController.popBackStack() },
                    onCollectionClick = { collectionId ->
                        navController.navigate(Route.CollectionDetail(collectionId, libraryId).route)
                    },
                )
            } else {
                CollectionsScreen(
                    onBackClick = { navController.popBackStack() },
                    onCollectionClick = { collectionId ->
                        navController.navigate(Route.CollectionDetail(collectionId).route)
                    },
                )
            }
        }

    }
        diagnosticsState.prompt
            ?.takeIf {
                currentEntry?.destination?.route != Route.Diagnostics.route &&
                    currentEntry?.destination?.route != Route.DiagnosticsReport.ROUTE
            }
            ?.let { prompt ->
            DiagnosticsPromptDialog(
                prompt = prompt,
                onReview = { navController.navigate(Route.Diagnostics.route) },
                onSend = { diagnosticsViewModel.uploadPrompt(prompt) },
                onAlwaysSend = { diagnosticsViewModel.alwaysSendPrompt(prompt) },
                onDontSend = { diagnosticsViewModel.declinePrompt(prompt) },
            )
        }
        // Menu-less routes (detail screens etc.) get the cast bar as a bottom
        // overlay. Tab routes render it inside MainScreen's Scaffold, stacked
        // above the nav menu (iOS placement); the full remote and the local
        // player own their whole screen.
        val currentRoute = currentEntry?.destination?.route
        val castBarInlineRoutes = setOf(
            Route.Home.route,
            Route.Libraries.route,
            Route.Recommendations.route,
            Route.Downloads.route,
            Route.Calendar.route,
            Route.SiloCastRemote.route,
            Route.Player.ROUTE,
        )
        if (currentRoute !in castBarInlineRoutes) {
            SiloCastMiniBar(
                controller = siloCastController,
                onOpenRemote = { navController.navigate(Route.SiloCastRemote.route) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }

        // Google Cast (Chromecast) mini controller — app-wide except the player,
        // which shows the full cast takeover overlay instead. On tab routes it
        // stacks above MainScreen's bottom nav menu.
        val googleCastManager: SiloCastSessionManager = koinInject()
        val googleCastState by googleCastManager.castState.collectAsState()
        if (googleCastState.isConnected && currentRoute != Route.Player.ROUTE) {
            val tabRoutes = setOf(
                Route.Home.route,
                Route.Libraries.route,
                Route.Recommendations.route,
                Route.Downloads.route,
                Route.Calendar.route,
            )
            GoogleCastMiniBar(
                castState = googleCastState,
                onPlayPause = { googleCastManager.togglePlayback() },
                onSkipBack = { googleCastManager.skipBy(-30.0) },
                onSkipForward = { googleCastManager.skipBy(30.0) },
                onSelectSubtitle = { googleCastManager.selectSubtitleTrack(it) },
                onStop = { googleCastManager.disconnect() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (currentRoute in tabRoutes) 80.dp else 0.dp),
            )
        }
    }
    }
    }
    }
}
