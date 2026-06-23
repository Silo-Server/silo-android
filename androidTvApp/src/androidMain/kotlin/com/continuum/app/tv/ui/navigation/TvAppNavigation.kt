package com.continuum.app.tv.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.continuum.app.common.player.video.VideoPlayerRouteArgs
import com.continuum.app.network.TokenManager
import com.continuum.app.repository.AuthRepository
import com.continuum.app.repository.ProfileRepository
import com.continuum.app.tv.ui.shell.TvMainShell
import com.continuum.app.tv.ui.screens.audiobook.TvAudiobookPlayerScreen
import com.continuum.app.tv.ui.screens.auth.TvLoginScreen
import com.continuum.app.tv.ui.screens.auth.TvPairDeviceScreen
import com.continuum.app.tv.ui.screens.auth.TvServerSetupScreen
import com.continuum.app.tv.ui.screens.auth.TvSetupScreen
import com.continuum.app.tv.ui.screens.auth.TvSignupScreen
import com.continuum.app.tv.ui.screens.profiles.TvCreateProfileScreen
import com.continuum.app.tv.ui.screens.profiles.TvEditProfileScreen
import com.continuum.app.tv.ui.screens.collections.TvCollectionDetailScreen
import com.continuum.app.tv.ui.screens.detail.TvItemDetailScreen
import com.continuum.app.tv.ui.screens.people.TvPersonDetailScreen
import com.continuum.app.tv.ui.screens.library.TvLibraryCollectionDetailScreen
import com.continuum.app.tv.ui.screens.player.TvPlayerScreen
import com.continuum.app.tv.ui.screens.profiles.TvProfileSelectionScreen
import com.continuum.app.tv.ui.screens.servers.TvServerListScreen
import com.continuum.app.tv.ui.screens.servers.TvServerSwitchDestination
import com.continuum.app.tv.ui.screens.watchtogether.TvWatchTogetherLobbyScreen
import com.continuum.app.common.overlays.ProvideCardOverlays
import com.continuum.app.common.settings.OverlayPrefsStore
import com.continuum.app.tv.watchnext.WatchNextSeeder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * Top-level TV navigation graph.
 *
 * ServerSetup → Login → ProfileSelection → Main (drawer). Item detail, player,
 * and collection detail are pushed on top of Main when the user drills down.
 * Settings-reachable grids (favorites, watchlist, history, collections) are
 * also top-level routes so they can cover the full screen.
 */
@Composable
fun TvAppNavigation(
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val tokenManager: TokenManager = koinInject()
    val authRepository: AuthRepository = koinInject()
    val profileRepository: ProfileRepository = koinInject()
    val overlayPrefsStore: OverlayPrefsStore = koinInject()
    val watchNextSeeder: WatchNextSeeder = koinInject()
    val pendingDeepLink: MutableStateFlow<Uri?> =
        koinInject(qualifier = named("pendingDeepLink"))

    // Watch Next launcher deep links. [MainTvActivity] publishes the launching
    // (or warm-launch) URI into the shared flow; we consume it here once and
    // route to ItemDetail / Player. For unauthenticated launches the URI just
    // sits in the flow until the auth chain lands the user on Main, at which
    // point the existing destination is rendered and this collector fires.
    // No special queueing path — the flow IS the queue.
    LaunchedEffect(Unit) {
        pendingDeepLink.collect { uri ->
            if (uri == null) return@collect
            // Device-pairing links (`silo://device?token=…` / `?code=…`) carry no
            // path segment — route on the query params instead of a contentId.
            if (uri.host.equals("device", ignoreCase = true)) {
                val token = uri.getQueryParameter("token")?.takeIf { it.isNotBlank() }
                val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
                if (token != null || code != null) {
                    navController.navigate(
                        // Prefer token (precise); only fall back to code when no token.
                        TvRoute.PairDevice(token = token, code = if (token == null) code else null).route,
                    ) {
                        // Repeated intent deliveries shouldn't stack duplicate
                        // pairing screens on the back stack.
                        launchSingleTop = true
                    }
                }
                pendingDeepLink.value = null
                return@collect
            }
            val contentId = uri.pathSegments.lastOrNull() ?: run {
                pendingDeepLink.value = null
                return@collect
            }
            when (uri.host) {
                "item" -> navController.navigate(TvRoute.ItemDetail(contentId).route)
                "play" -> navController.navigate(
                    TvRoute.Player(contentId, fileId = null).route,
                )
            }
            pendingDeepLink.value = null
        }
    }

    // Graceful handling of server-side session invalidation (refresh 401'd).
    // The TokenManager has already cleared the active server's tokens by the
    // time this fires; we just route the user back to Login so they can
    // re-authenticate against the same server (the [ServerRegistry] entry is
    // preserved so they don't have to re-enter the URL).
    LaunchedEffect(Unit) {
        tokenManager.sessionExpired.collect {
            navController.navigate(TvRoute.Login().route) {
                // Clear the entire back stack so the user can't press Back
                // to return to a screen that has no credentials to render.
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Re-read the authenticated profile id whenever the destination changes
    // (Login → ProfileSelection → Main). Drives card-overlay hydration off the
    // authenticated identity instead of a one-shot at app start, where the user
    // is still on Login and the settings calls would 401.
    val currentEntry by navController.currentBackStackEntryAsState()
    val overlaySessionKey by produceState<String?>(
        initialValue = null,
        currentEntry?.destination?.route,
    ) {
        value = tokenManager.getProfileId()
    }

    ProvideCardOverlays(store = overlayPrefsStore, sessionKey = overlaySessionKey) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(TvRoute.ServerSetup.route) {
            TvServerSetupScreen(
                onContinueToLogin = { signupEnabled ->
                    navController.navigate(TvRoute.Login(signupEnabled).route) {
                        popUpTo(TvRoute.ServerSetup.route) { inclusive = true }
                    }
                },
                onNeedsSetup = { navController.navigate(TvRoute.Setup.route) },
                // Companion pairing pushed a server AND completed device-login,
                // so the TV is already authenticated — skip the login screen and
                // go straight to profile selection (same as a successful sign-in).
                onPairedSignIn = {
                    navController.navigate(TvRoute.ProfileSelection.route) {
                        popUpTo(TvRoute.ServerSetup.route) { inclusive = true }
                    }
                },
            )
        }

        composable(TvRoute.Setup.route) {
            TvSetupScreen(
                onSetupComplete = {
                    navController.navigate(TvRoute.ProfileSelection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(TvRoute.Signup.route) {
            TvSignupScreen(
                onSignupComplete = {
                    navController.navigate(TvRoute.ProfileSelection.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBackToLogin = { navController.popBackStack() },
            )
        }

        composable(TvRoute.ServerList.route) {
            TvServerListScreen(
                onAddServer = {
                    navController.navigate(TvRoute.ServerSetup.route)
                },
                onSwitched = { destination ->
                    // Land on the deepest route the new server's stored
                    // credentials support — keeps the user signed in across
                    // a server switch when tokens already exist for the target.
                    val target = when (destination) {
                        TvServerSwitchDestination.Home -> TvRoute.Main.route
                        TvServerSwitchDestination.ProfileSelection ->
                            TvRoute.ProfileSelection.route
                        TvServerSwitchDestination.Login -> TvRoute.Login().route
                    }
                    navController.navigate(target) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.Login.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.Login.ARG_SIGNUP_ENABLED) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStack ->
            val signupEnabled = backStack.arguments?.getBoolean(TvRoute.Login.ARG_SIGNUP_ENABLED) ?: false
            TvLoginScreen(
                signupEnabled = signupEnabled,
                onCreateAccount = { navController.navigate(TvRoute.Signup.route) },
                // Point this TV at a different server — drop Login so Back from
                // setup can't return to a credential form with no server bound.
                onChangeServer = {
                    navController.navigate(TvRoute.ServerSetup.route) {
                        popUpTo(TvRoute.Login.ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onLoginSuccess = {
                    navController.navigate(TvRoute.ProfileSelection.route) {
                        popUpTo(TvRoute.Login.ROUTE) { inclusive = true }
                    }
                    // Seed Watch Next now and schedule periodic refresh; the user has
                    // just authenticated so /api/v1/home/sections will return their
                    // actual continue-watching / next-up.
                    watchNextSeeder.seedNow()
                    watchNextSeeder.enqueuePeriodic()
                },
            )
        }

        composable(TvRoute.ProfileSelection.route) {
            TvProfileSelectionScreen(
                onProfileSelected = {
                    navController.navigate(TvRoute.Main.route) {
                        popUpTo(TvRoute.ProfileSelection.route) { inclusive = true }
                    }
                    // Re-seed for the newly selected profile so the launcher row
                    // reflects this profile's continue-watching / next-up rather
                    // than whatever was last synced.
                    watchNextSeeder.seedNow()
                    watchNextSeeder.enqueuePeriodic()
                },
                onAddProfile = { navController.navigate(TvRoute.CreateProfile.route) },
                onEditProfile = { profileId ->
                    navController.navigate(TvRoute.EditProfile(profileId).route)
                },
                onChangeServer = {
                    navController.navigate(TvRoute.ServerList.route)
                },
                onSignOut = {
                    scope.launch {
                        authRepository.logout()
                        watchNextSeeder.clear()
                        navController.navigate(TvRoute.ServerSetup.route) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }

        composable(TvRoute.CreateProfile.route) {
            TvCreateProfileScreen(
                onNavigateBack = { navController.popBackStack() },
                onProfileCreated = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.EditProfile.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.EditProfile.ARG_PROFILE_ID) { type = NavType.StringType },
            ),
        ) { backStack ->
            val profileId = backStack.arguments?.getString(TvRoute.EditProfile.ARG_PROFILE_ID)
                ?: return@composable
            TvEditProfileScreen(
                profileId = profileId,
                onSaved = { navController.popBackStack() },
            )
        }

        composable(TvRoute.Main.route) {
            TvMainShell(
                onOpenItemDetail = { contentId ->
                    navController.navigate(TvRoute.ItemDetail(contentId).route)
                },
                onOpenLibraryCollectionDetail = { libraryId, collectionId, title ->
                    navController.navigate(
                        TvRoute.LibraryCollectionDetail(libraryId, collectionId, title).route,
                    )
                },
                onOpenCollectionDetail = { collectionId, title ->
                    navController.navigate(TvRoute.CollectionDetail(collectionId, title).route)
                },
                onSignedOut = {
                    // Drop our Watch Next rows + cancel the periodic refresh so
                    // the launcher doesn't keep showing the signed-out user's
                    // progress.
                    watchNextSeeder.clear()
                    navController.navigate(TvRoute.ServerSetup.route) {
                        popUpTo(TvRoute.Main.route) { inclusive = true }
                    }
                },
                onSwitchProfile = {
                    scope.launch {
                        profileRepository.clearProfile()
                        // Clear the previous profile's Watch Next rows before
                        // landing on the picker; the new profile will re-seed
                        // via [onProfileSelected].
                        watchNextSeeder.clear()
                        navController.navigate(TvRoute.ProfileSelection.route) {
                            popUpTo(TvRoute.Main.route) { inclusive = true }
                        }
                    }
                },
                // Android TV is now multi-server (parity with tvOS). "Switch
                // Server" opens the server list; the user picks an existing
                // saved server or chooses Add to enter a new URL.
                onSwitchServer = {
                    navController.navigate(TvRoute.ServerList.route)
                },
                onPairDevice = {
                    navController.navigate(TvRoute.PairDevice().route) {
                        launchSingleTop = true
                    }
                },
                onPlayItem = { playContentId, itemType, resumePositionSeconds ->
                    navController.navigate(
                        tvPlayDestinationFor(
                            itemType = itemType,
                            contentId = playContentId,
                            fileId = null,
                            resumePositionSeconds = resumePositionSeconds,
                        ),
                    )
                },
                onOpenPersonDetail = { personId ->
                    navController.navigate(TvRoute.PersonDetail(personId).route)
                },
            )
        }

        composable(
            route = TvRoute.ItemDetail.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.ItemDetail.ARG_CONTENT_ID) { type = NavType.StringType },
                navArgument(TvRoute.ItemDetail.ARG_SEASON_NUMBER) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStack ->
            val contentId = backStack.arguments
                ?.getString(TvRoute.ItemDetail.ARG_CONTENT_ID)
                .orEmpty()
            val seasonNumber = backStack.arguments
                ?.getString(TvRoute.ItemDetail.ARG_SEASON_NUMBER)
                ?.toIntOrNull()
            TvItemDetailScreen(
                contentId = contentId,
                seasonNumber = seasonNumber,
                // The detail screen's playback selector row writes the chosen
                // version's fileId into [TvItemDetailViewModel.selectedFileId];
                // we forward it through the route so the player session
                // actually binds to that version instead of always defaulting
                // to the server's first listed file (which for multi-version
                // titles is often the lower-resolution encode).
                onPlay = { playContentId, fileId, audioTrackIndex, subtitleTrackIndex, itemType, resumePositionSeconds ->
                    navController.navigate(
                        tvPlayDestinationFor(
                            itemType = itemType,
                            contentId = playContentId,
                            fileId = fileId,
                            resumePositionSeconds = resumePositionSeconds,
                            audioTrackIndex = audioTrackIndex,
                            subtitleTrackIndex = subtitleTrackIndex,
                        ),
                    )
                },
                onItemDetail = { itemContentId ->
                    navController.navigate(TvRoute.ItemDetail(itemContentId).route)
                },
                onSeriesClick = { seriesId ->
                    navController.navigate(TvRoute.ItemDetail(seriesId).route)
                },
                onSeasonClick = { seriesId, selectedSeason ->
                    navController.navigate(TvRoute.ItemDetail(seriesId, selectedSeason).route)
                },
                // Watch Together: the entry dialog resolves a room snapshot; route
                // host-with-selection straight to the synced player (carrying
                // roomId), otherwise into the lobby to wait/vote/pick.
                onWatchTogether = { snapshot ->
                    val target = if (!snapshot.selectedContentId.isNullOrBlank()) {
                        TvRoute.Player(
                            contentId = snapshot.selectedContentId!!,
                            fileId = snapshot.selectedFileId,
                            roomId = snapshot.roomId,
                            resumePositionSeconds = snapshot.anchorPositionSeconds
                                .takeIf { it.isFinite() && it > 0.0 },
                        ).route
                    } else {
                        TvRoute.WatchTogetherLobby(roomId = snapshot.roomId).route
                    }
                    navController.navigate(target)
                },
                onOpenPerson = { personId ->
                    navController.navigate(TvRoute.PersonDetail(personId).route)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.PersonDetail.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.PersonDetail.ARG_PERSON_ID) { type = NavType.LongType },
            ),
        ) { backStack ->
            val personId = backStack.arguments?.getLong(TvRoute.PersonDetail.ARG_PERSON_ID) ?: 0L
            TvPersonDetailScreen(
                personId = personId,
                onOpenItemDetail = { itemContentId ->
                    navController.navigate(TvRoute.ItemDetail(itemContentId).route)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.PairDevice.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.PairDevice.ARG_TOKEN) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.PairDevice.ARG_CODE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStack ->
            val token = backStack.arguments?.getString(TvRoute.PairDevice.ARG_TOKEN)
            val code = backStack.arguments?.getString(TvRoute.PairDevice.ARG_CODE)
            TvPairDeviceScreen(
                token = token,
                code = code,
                onDone = {
                    if (!navController.popBackStack()) {
                        navController.navigate(TvRoute.Main.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onSignIn = { navController.navigate(TvRoute.Login().route) },
            )
        }

        composable(
            route = TvRoute.Player.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.Player.ARG_CONTENT_ID) { type = NavType.StringType },
                navArgument(TvRoute.Player.ARG_FILE_ID) {
                    // Keep StringType because the query param is serialized as
                    // a string in [TvRoute.Player] and may be absent; NavType
                    // IntType can't represent "missing". We parse at the edge.
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.Player.ARG_ROOM_ID) {
                    // Watch Together room binding; null for solo play. Consumed
                    // by TvPlayerScreen in T3.
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.Player.ARG_AUDIO_TRACK_INDEX) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.Player.ARG_SUBTITLE_TRACK_INDEX) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.Player.ARG_AUTO_ADVANCE_COUNT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.Player.ARG_RESUME_POSITION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStack ->
            val contentId = backStack.arguments
                ?.getString(TvRoute.Player.ARG_CONTENT_ID)
                .orEmpty()
            val preferredFileId = backStack.arguments
                ?.getString(TvRoute.Player.ARG_FILE_ID)
                ?.toIntOrNull()
            val roomId = backStack.arguments
                ?.getString(TvRoute.Player.ARG_ROOM_ID)
            val audioTrackIndex = backStack.arguments
                ?.getString(TvRoute.Player.ARG_AUDIO_TRACK_INDEX)
                ?.toIntOrNull()
            val subtitleTrackIndex = backStack.arguments
                ?.getString(TvRoute.Player.ARG_SUBTITLE_TRACK_INDEX)
                ?.toIntOrNull()
            val resumePositionOverride = VideoPlayerRouteArgs.parseResumePosition(
                backStack.arguments?.getString(TvRoute.Player.ARG_RESUME_POSITION),
            )
            val autoAdvanceCount = backStack.arguments
                ?.getString(TvRoute.Player.ARG_AUTO_ADVANCE_COUNT)
                ?.toIntOrNull() ?: 0
            TvPlayerScreen(
                contentId = contentId,
                preferredFileId = preferredFileId,
                roomId = roomId,
                resumePositionOverride = resumePositionOverride,
                initialAudioTrackIndex = audioTrackIndex,
                initialSubtitleTrackIndex = subtitleTrackIndex,
                autoAdvanceCount = autoAdvanceCount,
                onPlayNext = { nextContentId, nextCount ->
                    // Replace the current player in the back stack so an
                    // auto-played chain doesn't pile up episodes behind Back.
                    navController.navigate(
                        TvRoute.Player(contentId = nextContentId, autoAdvanceCount = nextCount).route,
                    ) {
                        popUpTo(TvRoute.Player.ROUTE) { inclusive = true }
                    }
                },
                onExit = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.AudiobookPlayer.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.AudiobookPlayer.ARG_CONTENT_ID) { type = NavType.StringType },
                navArgument(TvRoute.AudiobookPlayer.ARG_FILE_ID) {
                    // Query param serialized as a string and may be absent; the
                    // shared VM parses it off SavedStateHandle ("fileId").
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(TvRoute.AudiobookPlayer.ARG_START_POSITION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            // contentId/fileId/startPosition reach AudiobookPlayerViewModel via
            // SavedStateHandle, so the screen needs no explicit args here.
            TvAudiobookPlayerScreen(
                onExit = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.WatchTogetherLobby.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.WatchTogetherLobby.ARG_ROOM_ID) { type = NavType.StringType },
            ),
        ) { backStack ->
            val roomId = backStack.arguments
                ?.getString(TvRoute.WatchTogetherLobby.ARG_ROOM_ID)
                ?: return@composable
            TvWatchTogetherLobbyScreen(
                roomId = roomId,
                // The lobby computes the synced-player route from its snapshot
                // (T2). We pop the lobby so Back from the player exits the room
                // rather than returning to a stale lobby.
                onNavigateToPlayer = { route ->
                    navController.navigate(route) {
                        popUpTo(TvRoute.WatchTogetherLobby.ROUTE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // --- Personal data grids (Favorites/Watchlist/History) and Collections
        // are now nested rail destinations inside TvMainShell; only their
        // immersive detail screens remain at the top level.

        composable(
            route = TvRoute.LibraryCollectionDetail.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.LibraryCollectionDetail.ARG_LIBRARY_ID) { type = NavType.IntType },
                navArgument(TvRoute.LibraryCollectionDetail.ARG_COLLECTION_ID) {
                    type = NavType.StringType
                },
                navArgument(TvRoute.LibraryCollectionDetail.ARG_TITLE) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStack ->
            val libraryId = backStack.arguments
                ?.getInt(TvRoute.LibraryCollectionDetail.ARG_LIBRARY_ID)
                ?: return@composable
            val collectionId = backStack.arguments
                ?.getString(TvRoute.LibraryCollectionDetail.ARG_COLLECTION_ID)
                ?: return@composable
            val title = backStack.arguments
                ?.getString(TvRoute.LibraryCollectionDetail.ARG_TITLE)
                .orEmpty()
            TvLibraryCollectionDetailScreen(
                libraryId = libraryId,
                collectionId = collectionId,
                title = title,
                onItemClick = { contentId ->
                    navController.navigate(TvRoute.ItemDetail(contentId).route)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = TvRoute.CollectionDetail.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.CollectionDetail.ARG_COLLECTION_ID) {
                    type = NavType.StringType
                },
                navArgument(TvRoute.CollectionDetail.ARG_TITLE) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStack ->
            val collectionId = backStack.arguments
                ?.getString(TvRoute.CollectionDetail.ARG_COLLECTION_ID) ?: return@composable
            val title = backStack.arguments
                ?.getString(TvRoute.CollectionDetail.ARG_TITLE).orEmpty()
            TvCollectionDetailScreen(
                collectionId = collectionId,
                title = title,
                onItemClick = { contentId ->
                    navController.navigate(TvRoute.ItemDetail(contentId).route)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    }
}
