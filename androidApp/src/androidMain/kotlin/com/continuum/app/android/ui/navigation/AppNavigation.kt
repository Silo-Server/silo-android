package com.continuum.app.android.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import com.continuum.app.android.ui.screens.MainScreen
import com.continuum.app.android.ui.screens.auth.LoginScreen
import com.continuum.app.android.ui.screens.auth.DevicePairingScreen
import com.continuum.app.android.ui.screens.auth.ServerSetupScreen
import com.continuum.app.android.ui.screens.auth.SetupScreen
import com.continuum.app.android.ui.screens.auth.SignupScreen
import com.continuum.app.android.ui.screens.browse.BrowseScreen
import com.continuum.app.android.ui.screens.browse.BrowseViewModel
import com.continuum.app.android.ui.screens.calendar.CalendarScreen
import com.continuum.app.android.ui.screens.collections.CollectionDetailScreen
import com.continuum.app.android.ui.screens.collections.CollectionsScreen
import com.continuum.app.android.ui.screens.collections.LibraryCollectionsScreen
import com.continuum.app.android.ui.screens.detail.ItemDetailScreen
import com.continuum.app.android.ui.screens.detail.ItemDetailViewModel
import com.continuum.app.android.ui.screens.watchtogether.WatchTogetherEntrySheet
import com.continuum.app.android.ui.screens.watchtogether.WatchTogetherLobbyScreen
import com.continuum.app.android.ui.screens.people.PersonDetailScreen
import com.continuum.app.android.ui.screens.people.PersonDetailViewModel
import com.continuum.app.android.ui.screens.personal.FavoritesScreen
import com.continuum.app.android.ui.screens.personal.HistoryScreen
import com.continuum.app.android.ui.screens.personal.PersonalListsScreen
import com.continuum.app.android.ui.screens.personal.WatchlistScreen
import com.continuum.app.android.ui.screens.player.PlayerScreen
import com.continuum.app.android.ui.screens.profiles.CreateProfileScreen
import com.continuum.app.android.ui.screens.profiles.EditProfileScreen
import com.continuum.app.android.ui.screens.profiles.ProfileSelectionScreen
import com.continuum.app.android.ui.screens.requests.MyRequestsScreen
import com.continuum.app.android.ui.screens.requests.RequestDetailScreen
import com.continuum.app.android.ui.screens.requests.RequestsScreen
import com.continuum.app.android.ui.screens.search.MobileSearchMediaType
import com.continuum.app.android.ui.screens.search.SearchScreen
import com.continuum.app.android.ui.screens.search.SearchViewModel
import com.continuum.app.android.ui.screens.servers.ServerListScreen
import com.continuum.app.android.ui.screens.servers.ServerSwitchDestination
import com.continuum.app.android.ui.screens.settings.CardOverlaySettingsScreen
import com.continuum.app.android.ui.screens.settings.SettingsScreen
import com.continuum.app.common.overlays.ProvideCardOverlays
import com.continuum.app.common.player.video.VideoPlayerRouteArgs
import com.continuum.app.common.settings.OverlayPrefsStore
import com.continuum.app.network.TokenManager
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

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

    LaunchedEffect(pendingExternalRoute) {
        val route = pendingExternalRoute?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        navController.navigate(route) {
            launchSingleTop = true
        }
        onExternalRouteConsumed()
    }

    // Re-read the authenticated profile id whenever the current destination
    // changes (Login → ProfileSelection → Main). This drives card-overlay
    // hydration off the authenticated identity instead of a one-shot at app
    // start, where the user is still on Login and the settings calls 401.
    val currentEntry by navController.currentBackStackEntryAsState()
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
    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Fill the bounded Surface so destinations get a bounded height. Without
        // this the host wraps to content, leaking unbounded height into screens
        // like the reader whose WebView paginates against the viewport height.
        modifier = Modifier.fillMaxSize(),
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
                navDeepLink { uriPattern = "continuum://device?token={token}" },
                navDeepLink { uriPattern = "continuum://device?code={code}" },
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
                        ServerSwitchDestination.Home -> Route.Home.route
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
                    navController.navigate(Route.Home.route) {
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
        // ---- Legacy route aliases (defensive) ----
        // Pre-consolidation builds had standalone Video/Audio/Reading/Inbox
        // destinations; they were folded into the Home shell. A NavController
        // back stack restored from such a build could still reference these route
        // strings — and navigating to a route with no registered destination
        // throws (crash on launch). Register no-op aliases that redirect to Home
        // so a restore can never hit an unregistered destination.
        for (legacyRoute in listOf("video", "audio", "reading", "inbox")) {
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
                onNavigateToRequests = {
                    navController.navigate(Route.Requests.route)
                },
                onNavigateToWatchlist = { navController.navigate(Route.Watchlist.route) },
                onNavigateToFavorites = { navController.navigate(Route.Favorites.route) },
                onNavigateToHistory = { navController.navigate(Route.History.route) },
                onNavigateToCollections = { navController.navigate(Route.Collections().route) },
                onNavigateToCardOverlays = {
                    navController.navigate(Route.CardOverlays.route)
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
                    navController.navigate(
                        Route.Player(
                            contentId = contentId,
                            fileId = fileId,
                            audioTrackIndex = audioTrackIndex,
                            subtitleTrackIndex = subtitleTrackIndex,
                            resumePositionSeconds = resumePositionSeconds,
                        ).route,
                    )
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
                onAudiobookPlayClick = { contentId, fileId, fromStart ->
                    navController.navigate(Route.AudiobookPlayer(contentId, fileId, fromStart).route)
                },
                onBookReadClick = { contentId, fileId ->
                    navController.navigate(Route.BookReader(contentId, fileId).route)
                },
                onWatchTogether = { contentId, fileId -> wtTarget = contentId to fileId },
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
            ),
        ) {
            com.continuum.app.android.ui.screens.audiobook.AudiobookPlayerScreen(
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
            com.continuum.app.android.ui.screens.reader.ReaderScreen(
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
    }
    }
    }
}
