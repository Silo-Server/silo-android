package org.siloserver.silo.tv.ui.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.MainTvActivity
import org.siloserver.silo.tv.ui.shell.TvMainShell
import org.siloserver.silo.tv.ui.screens.audiobook.TvAudiobookPlayerScreen
import org.siloserver.silo.tv.ui.screens.auth.TvLoginScreen
import org.siloserver.silo.tv.ui.screens.auth.TvPairDeviceScreen
import org.siloserver.silo.tv.ui.screens.auth.TvServerSetupScreen
import org.siloserver.silo.tv.ui.screens.auth.TvSetupScreen
import org.siloserver.silo.tv.ui.screens.auth.TvSignupScreen
import org.siloserver.silo.tv.ui.screens.profiles.TvCreateProfileScreen
import org.siloserver.silo.tv.ui.screens.profiles.TvEditProfileScreen
import org.siloserver.silo.tv.ui.screens.collections.TvCollectionDetailScreen
import org.siloserver.silo.tv.ui.screens.detail.TvItemDetailScreen
import org.siloserver.silo.tv.ui.screens.people.TvPersonDetailScreen
import org.siloserver.silo.tv.ui.screens.library.TvLibraryCollectionDetailScreen
import org.siloserver.silo.tv.ui.screens.player.TvPlayerScreen
import org.siloserver.silo.tv.ui.screens.profiles.TvProfileSelectionScreen
import org.siloserver.silo.tv.ui.screens.servers.TvServerListScreen
import org.siloserver.silo.tv.ui.screens.servers.TvServerSwitchDestination
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsPromptScreen
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsReportScreen
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsSettingsScreen
import org.siloserver.silo.tv.ui.screens.settings.diagnostics.TvDiagnosticsViewModel
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherLobbyScreen
import org.siloserver.silo.tv.ui.screens.watchtogether.tvWatchTogetherDestination
import org.siloserver.silo.common.overlays.ProvideCardOverlays
import org.siloserver.silo.common.diagnostics.DiagnosticsLifecycleLogger
import org.siloserver.silo.common.settings.LibraryPlaybackPrefsStore
import org.siloserver.silo.common.settings.OverlayPrefsStore
import org.siloserver.silo.tv.watchnext.WatchNextSeeder
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import org.siloserver.silo.tv.ui.screens.cast.TvSiloCastStandbyView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.qualifier.named

private const val RETURN_TO_MANAGE_SERVERS_KEY = "return_to_manage_servers"

internal fun tvShouldShowDiagnosticsPrompt(currentRoute: String?): Boolean =
    currentRoute != TvRoute.Diagnostics.route && currentRoute != TvRoute.DiagnosticsReport.ROUTE

/**
 * Upper bound on re-navigations for one queued deep link. Arrival-gated
 * consumption (see the collector in [TvAppNavigation]) retries a dropped
 * navigation whenever the back stack settles; a link whose target can never
 * become current must not retry forever.
 */
private const val MAX_DEEP_LINK_NAV_ATTEMPTS = 3

/**
 * Top-level TV navigation graph.
 *
 * ServerSetup → Login → ProfileSelection → Main (drawer). Item detail, player,
 * and collection detail are pushed on top of Main when the user drills down.
 * Settings-reachable grids (favorites, watchlist, history, collections) are
 * also top-level routes so they can cover the full screen.
 */
// The unauthenticated chain where content deep links stay queued: routes the
// graph passes through BEFORE the authenticated Main shell. A queued content
// deep link stays queued while the current destination is one of these (an
// unauthenticated/mid-auth launch, consumed once auth completes and lands on
// Main); any other route means the session is authenticated and the link is
// consumed immediately (see the deep-link collector in [TvAppNavigation]).
private val preMainAuthRoutes: Set<String> = setOf(
    TvRoute.ServerSetup.route,
    TvRoute.Setup.route,
    TvRoute.Signup.route,
    TvRoute.Login.ROUTE,
    TvRoute.ServerList.route,
    TvRoute.ProfileSelection.route,
    TvRoute.CreateProfile.route,
    TvRoute.EditProfile.ROUTE,
)

/**
 * Page-to-page cross-fade duration (ms). A middle ground between Compose Nav's
 * sluggish 700ms default and a phone-snappy 200ms — a touch more deliberate for
 * the 10-foot view so focus settles, without the old drag (Jim, TV feel).
 */
private const val TvPageFadeDurationMs = 500

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
    val libraryPlaybackPrefsStore: LibraryPlaybackPrefsStore = koinInject()
    val watchNextSeeder: WatchNextSeeder = koinInject()
    val siloCastReceiver: TvSiloCastReceiver = koinInject()
    val diagnosticsViewModel = koinViewModel<TvDiagnosticsViewModel>()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    val pendingDeepLink: MutableStateFlow<Uri?> =
        koinInject(qualifier = named("pendingDeepLink"))
    val siloCastStandby by siloCastReceiver.standbyState.collectAsState()

    LaunchedEffect(siloCastReceiver) {
        siloCastReceiver.launchRequests.collect { request ->
            val playback = request.playback
            val destination = TvRoute.Player(
                contentId = playback.contentId,
                fileId = playback.fileId,
                resumePositionSeconds = if (playback.startFromBeginning) 0.0 else playback.resumePosition,
                audioTrackIndex = playback.audioTrackIndex,
                subtitleTrackIndex = playback.subtitleTrackIndex,
            ).route
            val replaceCurrentPlayer = navController.currentDestination?.route == TvRoute.Player.ROUTE
            navController.navigate(destination) {
                if (replaceCurrentPlayer) {
                    popUpTo(TvRoute.Player.ROUTE) { inclusive = true }
                }
            }
        }
    }

    // Watch Next launcher deep links. [MainTvActivity] publishes the launching
    // (or warm-launch) URI into the shared flow; we consume it here once and
    // route to ItemDetail / Player. For unauthenticated launches the URI just
    // sits in the flow until the auth chain lands the user on Main, at which
    // point the existing destination is rendered and this collector fires.
    // No special queueing path — the flow IS the queue.
    LaunchedEffect(Unit) {
        // Consume-on-arrival bookkeeping: a content link is only cleared once
        // the nav graph actually shows its target. Clearing at navigate() time
        // lost the link whenever the call was silently dropped by an in-flight
        // teardown (observed on TV: player exit racing an immediate launcher
        // deep link) — with arrival-gating the still-queued link re-navigates
        // when the graph settles. The attempt cap keeps a link whose
        // navigation can never stick (e.g. a route that instantly pops) from
        // looping forever.
        var attemptUri: Uri? = null
        var attempts = 0
        kotlinx.coroutines.flow.combine(
            pendingDeepLink,
            navController.currentBackStackEntryFlow,
        ) { uri, entry -> uri to entry }.collect { (uri, entry) ->
            if (uri == null) return@collect
            if (uri != attemptUri) {
                attemptUri = uri
                attempts = 0
            }
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
            // Content links need an authenticated session: consuming them
            // while the auth chain is still on Login/ProfileSelection pushes
            // a 401 detail screen over the auth flow (launcher Watch-Next
            // tiles after session expiry hit exactly this). Leave the link
            // queued — this collector re-runs when the value is still set and
            // the graph reaches Main. Pairing links above stay ungated: they
            // ARE the sign-in path.
            // The combine with currentBackStackEntryFlow makes this re-fire
            // when the graph lands on Main with the link still queued.
            // Any authenticated route consumes the link right away: ItemDetail,
            // Player, and AudiobookPlayer are root-NavHost destinations, so the
            // navigate calls below work from anywhere (a warm launcher click
            // while the user sits on a detail/player screen navigates
            // immediately). Immediate consumption also means a queued link can
            // never go stale on an authenticated session.
            val route = entry.destination.route
            if (route == null || route in preMainAuthRoutes) return@collect // unauthenticated flow: keep queued until Main
            val contentId = uri.pathSegments.lastOrNull() ?: run {
                pendingDeepLink.value = null
                return@collect
            }
            // Arrived: the current destination is this link's target, so the
            // link is spent. This is the only place a successful content link
            // is cleared — see the bookkeeping comment above.
            val arrived = entry.arguments?.getString(TvRoute.ItemDetail.ARG_CONTENT_ID) == contentId &&
                when (uri.host) {
                    "item" -> route.startsWith("item/")
                    "play" -> route.startsWith("player/") || route.startsWith("audiobook/")
                    else -> false
                }
            if (arrived) {
                Log.i(MainTvActivity.DEEP_LINK_TAG, "deep link arrived: ${uri.host}/$contentId")
                pendingDeepLink.value = null
                return@collect
            }
            if (attempts >= MAX_DEEP_LINK_NAV_ATTEMPTS) {
                Log.w(
                    MainTvActivity.DEEP_LINK_TAG,
                    "deep link dropped after $attempts unarrived attempts: ${uri.host}/$contentId",
                )
                pendingDeepLink.value = null
                return@collect
            }
            attempts++
            Log.i(
                MainTvActivity.DEEP_LINK_TAG,
                "deep link navigating (attempt $attempts): ${uri.host}/$contentId from $route",
            )
            when (uri.host) {
                "item" -> navController.navigate(TvRoute.ItemDetail(contentId).route)
                "play" -> {
                    // The Watch Next mapper tags play intents with the item type
                    // (`silo://play/<contentId>?type=<type>`) so audiobook tiles
                    // route to [TvRoute.AudiobookPlayer] instead of the video
                    // player. Older already-published tiles carry no type param —
                    // [tvPlayDestinationFor] treats a null type as non-audiobook
                    // and falls through to [TvRoute.Player], preserving today's
                    // behavior for movie/episode tiles.
                    val itemType = uri.getQueryParameter("type")
                    val playbackArgs = parseTvPlaybackDeepLinkArgs(uri::getQueryParameter)
                    navController.navigate(
                        tvPlayDestinationFor(
                            itemType = itemType,
                            contentId = contentId,
                            fileId = playbackArgs.fileId,
                            resumePositionSeconds = null,
                            audioTrackIndex = playbackArgs.audioTrackIndex,
                            subtitleTrackIndex = playbackArgs.subtitleTrackIndex,
                            quality = playbackArgs.quality,
                        ),
                    ) {
                        // A retried link (arrival-gated above) must not stack a
                        // second player over one already being created.
                        launchSingleTop = true
                    }
                }
            }
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
    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // Replace Compose Nav's sluggish 700ms default cross-fade with a snappy one.
        enterTransition = { fadeIn(tween(TvPageFadeDurationMs)) },
        exitTransition = { fadeOut(tween(TvPageFadeDurationMs)) },
        popEnterTransition = { fadeIn(tween(TvPageFadeDurationMs)) },
        popExitTransition = { fadeOut(tween(TvPageFadeDurationMs)) },
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
                    // Landing straight on Home means the target server is already
                    // authenticated, so no Login/ProfileSelection callback fires to
                    // refresh state. Do the hygiene here: drop the previous
                    // server's per-profile caches and re-seed Watch Next so its
                    // launcher tiles and cached prefs don't ghost. The
                    // Login/ProfileSelection destinations re-seed via their own
                    // callbacks — only the already-authed path needs this.
                    if (destination == TvServerSwitchDestination.Home) {
                        libraryPlaybackPrefsStore.clear()
                        overlayPrefsStore.clear()
                        watchNextSeeder.clear()
                        watchNextSeeder.seedNow()
                        watchNextSeeder.enqueuePeriodic()
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

        composable(TvRoute.Main.route) { mainEntry ->
            val returnToManageServers =
                mainEntry.savedStateHandle.get<Boolean>(RETURN_TO_MANAGE_SERVERS_KEY) == true
            TvMainShell(
                returnToManageServers = returnToManageServers,
                onManageServersReturnFocusConsumed = {
                    mainEntry.savedStateHandle.remove<Boolean>(RETURN_TO_MANAGE_SERVERS_KEY)
                },
                onManageServers = {
                    mainEntry.savedStateHandle[RETURN_TO_MANAGE_SERVERS_KEY] = true
                    navController.navigate(TvRoute.ServerList.route)
                },
                onOpenDiagnostics = {
                    navController.navigate(TvRoute.Diagnostics.route)
                },
                onOpenItemDetail = { contentId ->
                    // launchSingleTop collapses a double-OK on the same card into
                    // one ItemDetail entry (consecutive identical contentId), so
                    // Back doesn't appear inert against a duplicate. Distinct
                    // pushes are unaffected — their route args differ.
                    navController.navigate(TvRoute.ItemDetail(contentId).route) {
                        launchSingleTop = true
                    }
                },
                onOpenWatchTogether = { room ->
                    navController.navigate(tvWatchTogetherDestination(room)) {
                        launchSingleTop = true
                    }
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
                    scope.launch {
                        // Full sign-out teardown — parity with the Settings and
                        // ProfileSelection sign-out paths. Clearing Watch Next
                        // alone left the tokens, profileId, and server session
                        // alive, so a relaunch resumed the previous user. Drop
                        // credentials and per-profile caches before navigating.
                        // The Settings sign-out path may have already logged out
                        // server-side and cleared tokens — only hit the network
                        // when a session still exists so we don't 401 a second
                        // logout. The local clears below are idempotent and run
                        // unconditionally (the Settings path doesn't clear
                        // Watch Next, so that teardown must still happen here).
                        if (!tokenManager.getAccessToken().isNullOrBlank()) {
                            authRepository.logout()
                        }
                        profileRepository.clearProfile()
                        tokenManager.clearTokens()
                        libraryPlaybackPrefsStore.clear()
                        overlayPrefsStore.clear()
                        // Drop our Watch Next rows + cancel the periodic refresh so
                        // the launcher doesn't keep showing the signed-out user's
                        // progress.
                        watchNextSeeder.clear()
                        navController.navigate(TvRoute.ServerSetup.route) {
                            popUpTo(TvRoute.Main.route) { inclusive = true }
                        }
                    }
                },
                onSwitchProfile = {
                    scope.launch {
                        // Leave the main shell before clearing profile-scoped
                        // state. Clearing first briefly recomposed Home with
                        // default overlay pills behind Settings before the
                        // profile picker appeared.
                        navController.navigate(TvRoute.ProfileSelection.route) {
                            popUpTo(TvRoute.Main.route) { inclusive = true }
                        }
                        profileRepository.clearProfile()
                        // Library/overlay prefs are per-profile — drop the caches
                        // so the next profile's prefs don't ghost-render the
                        // previous user's rows. Parity with the Settings
                        // switch-profile path.
                        libraryPlaybackPrefsStore.clear()
                        overlayPrefsStore.clear()
                        // Clear the previous profile's Watch Next rows before
                        // landing on the picker; the new profile will re-seed
                        // via [onProfileSelected].
                        watchNextSeeder.clear()
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

        composable(TvRoute.Diagnostics.route) {
            TvDiagnosticsSettingsScreen(
                onBack = { navController.popBackStack() },
                onReportSelected = { reportId ->
                    navController.navigate(TvRoute.DiagnosticsReport(reportId).route)
                },
            )
        }

        composable(
            route = TvRoute.DiagnosticsReport.ROUTE,
            arguments = listOf(
                navArgument(TvRoute.DiagnosticsReport.ARG_REPORT_ID) { type = NavType.StringType },
            ),
        ) { backStack ->
            TvDiagnosticsReportScreen(
                reportId = backStack.arguments
                    ?.getString(TvRoute.DiagnosticsReport.ARG_REPORT_ID)
                    .orEmpty(),
                onBack = { navController.popBackStack() },
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
                    ) {
                        // A fast Select after entering detail can overlap the
                        // route transition. Collapse an identical second Play
                        // request instead of creating two player ViewModels and
                        // two concurrent playback-session starts.
                        launchSingleTop = true
                    }
                },
                onItemDetail = { itemContentId ->
                    // launchSingleTop suppresses the exact double-tap dupe; a
                    // distinct related item (always a different contentId) still
                    // pushes normally.
                    navController.navigate(TvRoute.ItemDetail(itemContentId).route) {
                        launchSingleTop = true
                    }
                },
                // Season switching replaces the current detail entry so paging
                // through seasons never stacks pages — one Back returns to the
                // screen the user arrived from.
                onItemDetailReplace = { itemContentId ->
                    val current = navController.currentBackStackEntry?.destination?.route
                    navController.navigate(TvRoute.ItemDetail(itemContentId).route) {
                        launchSingleTop = true
                        current?.let { popUpTo(it) { inclusive = true } }
                    }
                },
                onSeriesClick = { seriesId ->
                    navController.navigate(TvRoute.ItemDetail(seriesId).route) {
                        launchSingleTop = true
                    }
                },
                onSeasonClick = { seriesId, selectedSeason ->
                    navController.navigate(TvRoute.ItemDetail(seriesId, selectedSeason).route) {
                        launchSingleTop = true
                    }
                },
                onWatchTogether = { snapshot ->
                    navController.navigate(tvWatchTogetherDestination(snapshot))
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
                    navController.navigate(TvRoute.ItemDetail(itemContentId).route) {
                        launchSingleTop = true
                    }
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
                navArgument(TvRoute.Player.ARG_QUALITY) {
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
            val preferredQuality = backStack.arguments
                ?.getString(TvRoute.Player.ARG_QUALITY)
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
                preferredQuality = preferredQuality,
                roomId = roomId,
                resumePositionOverride = resumePositionOverride,
                initialAudioTrackIndex = audioTrackIndex,
                initialSubtitleTrackIndex = subtitleTrackIndex,
                autoAdvanceCount = autoAdvanceCount,
                onPlayNext = { nextContentId, nextCount, nextQuality ->
                    // Replace the current player in the back stack so an
                    // auto-played chain doesn't pile up episodes behind Back.
                    navController.navigate(
                        TvRoute.Player(contentId = nextContentId, quality = nextQuality, autoAdvanceCount = nextCount).route,
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
                    navController.navigate(TvRoute.ItemDetail(contentId).route) {
                        launchSingleTop = true
                    }
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
                    navController.navigate(TvRoute.ItemDetail(contentId).route) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    siloCastStandby?.let { state ->
        TvSiloCastStandbyView(
            state = state,
            onDisconnect = siloCastReceiver::disconnectRemoteControl,
        )
    }
    diagnosticsState.prompt
        ?.takeIf { tvShouldShowDiagnosticsPrompt(currentEntry?.destination?.route) }
        ?.let { prompt ->
        TvDiagnosticsPromptScreen(
            prompt = prompt,
            onReview = { navController.navigate(TvRoute.Diagnostics.route) },
            onSend = { diagnosticsViewModel.uploadPrompt(prompt) },
            onAlwaysSend = { diagnosticsViewModel.alwaysSendPrompt(prompt) },
            onDontSend = { diagnosticsViewModel.declinePrompt(prompt) },
        )
    }
    }
    }
}
