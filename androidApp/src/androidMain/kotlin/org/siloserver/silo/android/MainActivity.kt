package org.siloserver.silo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import org.siloserver.silo.common.diagnostics.DiagnosticsLifecycleLogger
import org.siloserver.silo.android.downloads.LEGACY_PUBLIC_DOWNLOAD_PERMISSION
import org.siloserver.silo.android.downloads.hasLegacyPublicDownloadPermission
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.android.push.PushNotificationPresenter
import org.siloserver.silo.android.ui.navigation.AppNavigation
import org.siloserver.silo.android.ui.navigation.ExternalRouteRequest
import org.siloserver.silo.android.ui.navigation.ExternalRouteRequestFactory
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.android.ui.navigation.clearConsumedExternalRouteRequest
import org.siloserver.silo.android.ui.navigation.contentDeepLinkRouteOrNull
import org.siloserver.silo.android.ui.navigation.ExternalRouteScope
import org.siloserver.silo.android.ui.navigation.notificationExternalRouteOrNull
import org.siloserver.silo.android.ui.navigation.deviceLoginPairRouteOrNull
import org.siloserver.silo.android.ui.navigation.hasLocalDownloadsForScope
import org.siloserver.silo.android.ui.navigation.inviteClaimRouteOrNull
import org.siloserver.silo.android.ui.navigation.notificationNavigationRouteOrNull
import org.siloserver.silo.android.ui.navigation.shouldStartOnDownloads
import org.siloserver.silo.android.ui.screens.onboarding.OnboardingTourLocalCache
import org.siloserver.silo.android.ui.theme.SiloTheme
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.pip.SiloPictureInPictureSurface
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.ServerDrivenConfigRefresher
import org.siloserver.silo.common.startup.StartupArtworkPlan
import org.siloserver.silo.common.startup.warmAuthenticatedStartup
import org.siloserver.silo.common.startup.warmProfileSelectionStartup
import org.siloserver.silo.common.ui.components.StartupSplashVideo
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.requiresApproval
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

class MainActivity : ComponentActivity() {

    companion object {
        // The startup splash plays once per process (a genuine cold launch). It
        // survives Activity recreation / return-from-background so we don't replay
        // it on warm re-open; a process death resets it, which is itself a cold
        // start. Mirrors the TV-side flag in MainTvActivity.
        @Volatile
        private var hasShownColdSplash = false

        /**
         * Set on the launch Intent once its external route has been delivered.
         * `putExtra` mutates the process-local Intent, which covers ordinary
         * in-process Activity recreation but NOT process death — the system may
         * rebuild the task from the original launch Intent, without this. The
         * saved-state route below is what covers that case; this is the fast
         * path.
         */
        private const val EXTRA_EXTERNAL_ROUTE_CONSUMED =
            "org.siloserver.silo.EXTERNAL_ROUTE_CONSUMED"

        /**
         * Stands in for an active server whose identity could not be read, so a
         * scope built from it matches nothing instead of everything.
         */
        private const val UNRESOLVED_IDENTITY = "silo:unresolved-identity"

        /** Saved-state key for [consumedExternalRoute]. */
        private const val STATE_CONSUMED_EXTERNAL_ROUTE =
            "org.siloserver.silo.CONSUMED_EXTERNAL_ROUTE"
    }

    private val externalRouteRequestFactory = ExternalRouteRequestFactory()
    // Retain the latest request even while Compose is between collectors (for
    // example while an existing top Activity is being resumed by onNewIntent).
    // A replay-free SharedFlow can silently drop exactly that warm delivery.
    private val pendingExternalRouteRequests = MutableStateFlow<ExternalRouteRequest?>(null)

    /**
     * The external route already delivered for the Intent this Activity was
     * launched with, carried across process death in saved state so a restored
     * task cannot replay a link the user already followed and navigated away
     * from.
     */
    private var consumedExternalRoute: String? = null

    // POST_NOTIFICATIONS is required on Android 13+ for any notification —
    // download progress / completion notifications silently never appear
    // without it.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fire-and-forget */ }
    private val requestLegacyPublicDownloadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fire-and-forget */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedExternalRoute = savedInstanceState?.getString(STATE_CONSUMED_EXTERNAL_ROUTE)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        maybeRequestLegacyPublicDownloadPermission()

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            val pendingExternalRoute by pendingExternalRouteRequests.collectAsState()
            var splashPlaybackComplete by remember { mutableStateOf(hasShownColdSplash) }

            LaunchedEffect(Unit) {
                val route = resolveStartDestination()
                startRoute = route
                // Capture unconditionally: a notification tapped while the
                // user still has to sign in / pick a profile should land on
                // its target after auth instead of being silently dropped.
                // The pending route is only consumed once the main graph is
                // showing, so pre-auth starts just hold it.
                // Skip an Intent whose route was already delivered: it is only
                // still here because the Activity retains it.
                if (intent?.getBooleanExtra(EXTRA_EXTERNAL_ROUTE_CONSUMED, false) != true) {
                    queueExternalRouteFrom(intent)
                }
                launchAuthenticatedStartupWarmup(route)
            }

            SiloTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val resolvedRoute = startRoute
                    if (resolvedRoute == null || !splashPlaybackComplete) {
                        // iOS StartupSplashView parity: the video plays in a
                        // SMALL centered box — min(60% of screen width, 320dp)
                        // on black — not full-bleed (QA 2026-07-08).
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        ) {
                            val videoWidth = minOf(maxWidth * 0.6f, 320.dp)
                            StartupSplashVideo(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(videoWidth)
                                    .aspectRatio(16f / 9f),
                                backgroundColor = Color.Black,
                                // iOS parity: video end or 4s, whichever first
                                // (StartupSplashView maximumDisplayDuration).
                                maxVisibleMillis = 4_000L,
                                onPlaybackComplete = {
                                    splashPlaybackComplete = true
                                    hasShownColdSplash = true
                                },
                            )
                        }
                    } else {
                        AppNavigation(
                            startDestination = resolvedRoute,
                            pendingExternalRoute = pendingExternalRoute,
                            onRequeueExternalRoute = { route ->
                                // A fresh request: clear the consumed marker so
                                // this re-delivery is not mistaken for the
                                // already-followed original.
                                consumedExternalRoute = null
                                intent?.removeExtra(EXTRA_EXTERNAL_ROUTE_CONSUMED)
                                pendingExternalRouteRequests.value =
                                    externalRouteRequestFactory.create(route)
                            },
                            onExternalRouteConsumed = { consumedRequest ->
                                // Record the delivery in two places. The Intent
                                // extra covers in-process Activity recreation,
                                // which re-parses the retained Intent in
                                // onCreate and would otherwise yank the user
                                // back to a link they already followed. It is
                                // process-local, so the saved-state route below
                                // is what covers process death.
                                intent?.putExtra(EXTRA_EXTERNAL_ROUTE_CONSUMED, true)
                                consumedExternalRoute = consumedRequest.route
                                pendingExternalRouteRequests.update { pendingRequest ->
                                    clearConsumedExternalRouteRequest(
                                        pendingRequest = pendingRequest,
                                        consumedRequest = consumedRequest,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * While the full Remote Control owns volume, consume both halves of each
     * hardware-key event so Android neither changes local volume nor shows its
     * volume HUD. Repeated ACTION_DOWN events intentionally remain individual
     * remote steps when the user holds a button.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> return super.dispatchKeyEvent(event)
        }
        val controller = get<SiloCastController>(SiloCastController::class.java)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                controller.stepVolumeOptimistic(step) || super.dispatchKeyEvent(event)
            }
            else -> {
                controller.shouldInterceptHardwareVolumeKeys() || super.dispatchKeyEvent(event)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DiagnosticsLifecycleLogger.state("foreground")
        val refresher = get<ServerDrivenConfigRefresher>(ServerDrivenConfigRefresher::class.java)
        val monitor = get<ServerReachabilityMonitor>(ServerReachabilityMonitor::class.java)
        monitor.startForeground()
        lifecycleScope.launch(Dispatchers.IO) { refresher.refreshIfStale() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        consumedExternalRoute?.let { outState.putString(STATE_CONSUMED_EXTERNAL_ROUTE, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A genuinely new Intent has not been consumed, whatever the old one
        // carried.
        intent.removeExtra(EXTRA_EXTERNAL_ROUTE_CONSUMED)
        consumedExternalRoute = null
        setIntent(intent)
        lifecycleScope.launch { queueExternalRouteFrom(intent) }
    }

    /**
     * Mirrors iOS scene-resign / app-background flush — when the user
     * sends the app to the background, drain any debounced device
     * settings so the next session sees what they just toggled. Without
     * this, a process death within the 750ms debounce window would lose
     * the write.
     */
    override fun onStop() {
        DiagnosticsLifecycleLogger.state("background")
        super.onStop()
        val monitor = get<ServerReachabilityMonitor>(ServerReachabilityMonitor::class.java)
        monitor.stopForeground()
        val store = get<PlayerSettingsStore>(PlayerSettingsStore::class.java)
        lifecycleScope.launch { store.flushPendingDeviceSettings() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        get<SiloPictureInPictureCoordinator>(SiloPictureInPictureCoordinator::class.java)
            .enterPictureInPictureIfEligible(this, SiloPictureInPictureSurface.Mobile)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        get<SiloPictureInPictureCoordinator>(SiloPictureInPictureCoordinator::class.java)
            .setInPictureInPictureMode(isInPictureInPictureMode)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return
        requestNotificationPermission.launch(perm)
    }

    private fun maybeRequestLegacyPublicDownloadPermission() {
        if (hasLegacyPublicDownloadPermission(this)) return
        requestLegacyPublicDownloadPermission.launch(LEGACY_PUBLIC_DOWNLOAD_PERMISSION)
    }


    /**
     * Parses an Intent into a pending external route, tagged with the identity
     * it is only meaningful under.
     *
     * Everything that can wait through authentication has to declare its scope,
     * because "wait" can mean days for a notification PendingIntent and several
     * profile switches:
     *  - a pairing link names its issuing SERVER ORIGIN;
     *  - a notification was generated for one profile's inbox on one server, so
     *    it carries the identity stamped on it at post time;
     *  - a content link (`silo://item`, `silo://play`) carries no identity of
     *    its own, but its ids are server-local — so it is pinned to whoever is
     *    signed in when the link arrives. Arriving signed-out pins nothing,
     *    which is what lets a link opened before login still work after it.
     */
    private suspend fun queueExternalRouteFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_EXTERNAL_ROUTE_CONSUMED, false) == true) return

        // NOT gated on the active server. The route carries its issuing origin
        // and the pairing destination refuses — and explains — a mismatch, with
        // a switch action. Dropping it here was silent: the user scanned a code
        // and nothing happened. A link whose origin cannot be read does not
        // parse into a route at all.
        val deviceRoute = deviceLoginPairRouteOrNull(intent?.dataString)
        // Rejected outright unless it says whose it is — see
        // [notificationExternalRouteOrNull].
        val notification = notificationExternalRouteOrNull(
            route = notificationRouteOrNull(intent),
            serverId = intent?.getStringExtra(PushNotificationPresenter.EXTRA_SERVER_ID),
            profileId = intent?.getStringExtra(PushNotificationPresenter.EXTRA_PROFILE_ID),
        )
        val notificationRoute = notification?.first
        val contentRoute = contentDeepLinkRouteOrNull(intent?.dataString)
        val inviteRoute = inviteClaimRouteOrNull(intent?.dataString)

        val route = notificationRoute ?: contentRoute ?: deviceRoute ?: inviteRoute ?: return
        if (route == consumedExternalRoute) return

        val scope = when {
            // Unscoped for DELIVERY: the pairing screen owns the server check,
            // so the request must actually arrive for it to be explained.
            route === deviceRoute -> ExternalRouteScope.Unscoped
            // Non-null by construction: `route` is only this when `notification`
            // produced it, and that requires a complete identity.
            route === notificationRoute -> checkNotNull(notification).second
            route === contentRoute -> currentIdentityScope()
            // An invite claim carries its own target server and is designed to
            // work before authentication, so it must NOT be pinned to the
            // current identity.
            else -> ExternalRouteScope.Unscoped
        }

        pendingExternalRouteRequests.value =
            externalRouteRequestFactory.create(route = route, scope = scope)
    }

    /**
     * One cohesive read of the live identity.
     *
     * Reading the server and profile through separate getters could tear across
     * a switch — the cached server id from before it, the profile id from after
     * — producing a hybrid identity that belongs to nobody, which then either
     * consumes a valid one-shot route or weakens it with a null wildcard.
     */
    private suspend fun currentIdentityScope(): ExternalRouteScope {
        val scope = get<TokenManager>(TokenManager::class.java).snapshotCurrentScope()
        if (scope != null) {
            return ExternalRouteScope.Identity(
                serverId = scope.serverId,
                profileId = scope.profileId,
                identityGeneration = scope.identityGeneration,
            )
        }
        // A null snapshot means "no active server" — nothing to pin to, and the
        // link must survive setup and login. But it ALSO means "snapshotting
        // failed" or "this manager does not model scopes", and turning those
        // into a wildcard would quietly unpin a link that should have been
        // pinned. Only an actually-absent server is allowed to be unpinned.
        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        return if (registry.activeServerId.value == null) {
            ExternalRouteScope.Identity(serverId = null, profileId = null)
        } else {
            // An active server we cannot describe: pin to something nothing
            // matches rather than to everything.
            ExternalRouteScope.Identity(serverId = UNRESOLVED_IDENTITY, profileId = null)
        }
    }

    private fun notificationRouteOrNull(intent: Intent?): String? =
        notificationNavigationRouteOrNull(
            intent?.getStringExtra(PushNotificationPresenter.EXTRA_NAV_ROUTE),
        )

    private fun String.isAuthenticatedStartRoute(): Boolean =
        this == Route.Home.route || this == Route.Downloads.route

    /**
     * Decides which auth-flow screen to land on.
     *
     * The [ServerRegistry] is the source of truth for which servers are saved
     * and which one is active. The [TokenManager] holds the per-server tokens
     * for the active entry — both are loaded from EncryptedSharedPreferences
     * during DI construction, so by the time we run we just consult them.
     *
     *  - No active server → `ServerSetup` (registry is empty)
     *  - Active server but no access token → `Login`
     *  - Tokens but no profile selected for THIS server → `ProfileSelection`
     *  - All set → `Home`
     */
    private suspend fun resolveStartDestination(): String {
        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        val tokenManager = get<TokenManager>(TokenManager::class.java)

        // NOTE: a device link is deliberately NOT returned as the start
        // destination. It used to be, which put Pair Device at the root of a
        // signed-out app: its "Sign In" pushed Login, and the successful login
        // then cleared the whole stack with popUpTo(0), losing the pairing
        // request entirely. It is queued as a pending external route instead,
        // so the normal server/token/profile gates run first and the pairing
        // screen arrives on top of an authenticated stack — which also means
        // its Back/Done has somewhere real to return to.

        val activeEntry = registry.activeEntry.value
            ?: return Route.ServerSetup.route

        val cleartextConsent = get<org.siloserver.silo.network.CleartextOriginConsent>(
            org.siloserver.silo.network.CleartextOriginConsent::class.java,
        )
        if (cleartextConsent.requiresApproval(activeEntry.url)) {
            return Route.ServerSetup.route
        }

        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) return Route.Login.route

        // Profile id is per-server: prefer the registry entry's saved value,
        // fall back to whatever the token manager has cached.
        val profileId = activeEntry.profileId ?: tokenManager.getProfileId()
        if (profileId.isNullOrBlank()) return Route.ProfileSelection.route

        // Offline-with-downloads fast path: if local media exists and either
        // the device has no network OR the configured Silo server fails the
        // authoritative health probe, land directly on Downloads instead of
        // greeting the user with a dead Home request.
        // Filesystem walk + health probe off the main dispatcher: this runs
        // in a main-thread LaunchedEffect during cold start and would
        // otherwise block first-frame work.
        val hasDownloads = kotlinx.coroutines.withContext(Dispatchers.IO) {
            hasLocalDownloads(activeEntry.id, profileId)
        }
        val online = isOnline()
        val canUseServer = if (hasDownloads && online) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                get<ServerReachabilityMonitor>(ServerReachabilityMonitor::class.java).retryNow().canUseServer
            }
        } else {
            online
        }
        if (
            shouldStartOnDownloads(
                hasLocalDownloads = hasDownloads,
                isDeviceOnline = online,
                canUseServer = canUseServer,
            )
        ) {
            return Route.Downloads.route
        }

        // A warm start would otherwise bypass the tour gate entirely (e.g.
        // process death mid-tour). Once completion is confirmed the local
        // cache short-circuits inside the gate, so this costs nothing on
        // launches after the first; the gate itself fails open to Home on
        // any error, so it can't strand an offline start.
        val tourCache = get<OnboardingTourLocalCache>(OnboardingTourLocalCache::class.java)
        if (!tourCache.isDone(activeEntry.id, profileId)) {
            return Route.OnboardingTour.route
        }

        return Route.Home.route
    }

    private fun launchAuthenticatedStartupWarmup(startRoute: String) {
        // A launch that lands on profile selection still warms the profile
        // list + avatar art so the grid paints finished after the splash
        // (Apple's prefetchForInitialRoute(.needsProfile)).
        if (startRoute == Route.ProfileSelection.route) {
            lifecycleScope.launch(Dispatchers.IO) {
                warmProfileSelectionStartup(
                    context = applicationContext,
                    profileRepository = get(ProfileRepository::class.java),
                    serverUrl = get<ServerRegistry>(ServerRegistry::class.java).activeEntry.value?.url,
                )
            }
            return
        }
        if (startRoute != Route.Home.route) return
        lifecycleScope.launch(Dispatchers.IO) {
            warmAuthenticatedStartup(
                context = applicationContext,
                authRepository = get(AuthRepository::class.java),
                profileRepository = get(ProfileRepository::class.java),
                personalDataRepository = get(PersonalDataRepository::class.java),
                sectionRepository = get(SectionRepository::class.java),
                homeCache = get(HomeCachePort::class.java),
                identityTransitions = get<org.siloserver.silo.network.IdentityTransitionBarrier>(
                    org.siloserver.silo.network.IdentityTransitionBarrier::class.java,
                ),
                serverUrl = get<ServerRegistry>(ServerRegistry::class.java).activeEntry.value?.url,
                artworkPlan = StartupArtworkPlan.phone(),
            )
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return true   // unknown — assume online so we don't surprise online users
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun hasLocalDownloads(serverId: String, profileId: String): Boolean {
        val storage = get<org.siloserver.silo.common.downloads.DownloadStorage>(
            org.siloserver.silo.common.downloads.DownloadStorage::class.java
        )
        return hasLocalDownloadsForScope(storage, serverId, profileId)
    }
}
