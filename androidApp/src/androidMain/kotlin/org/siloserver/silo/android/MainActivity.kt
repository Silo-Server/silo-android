package org.siloserver.silo.android

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import org.siloserver.silo.android.push.PushNotificationPresenter
import org.siloserver.silo.android.ui.navigation.AppNavigation
import org.siloserver.silo.android.ui.navigation.ExternalRouteRequest
import org.siloserver.silo.android.ui.navigation.ExternalRouteRequestFactory
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.android.ui.navigation.clearConsumedExternalRouteRequest
import org.siloserver.silo.android.ui.navigation.contentDeepLinkRouteOrNull
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
    }

    private val externalRouteRequestFactory = ExternalRouteRequestFactory()
    // Retain the latest request even while Compose is between collectors (for
    // example while an existing top Activity is being resumed by onNewIntent).
    // A replay-free SharedFlow can silently drop exactly that warm delivery.
    private val pendingExternalRouteRequests = MutableStateFlow<ExternalRouteRequest?>(null)

    // POST_NOTIFICATIONS is required on Android 13+ for any notification —
    // download progress / completion notifications silently never appear
    // without it.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fire-and-forget */ }
    private val requestLegacyPublicDownloadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* fire-and-forget */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                (notificationRouteOrNull(intent) ?: contentDeepLinkRouteOrNull(intent?.dataString))
                    ?.let { route ->
                        pendingExternalRouteRequests.value = externalRouteRequestFactory.create(route)
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
                            onExternalRouteConsumed = { consumedRequest ->
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

    override fun onStart() {
        super.onStart()
        DiagnosticsLifecycleLogger.state("foreground")
        val refresher = get<ServerDrivenConfigRefresher>(ServerDrivenConfigRefresher::class.java)
        val monitor = get<ServerReachabilityMonitor>(ServerReachabilityMonitor::class.java)
        monitor.startForeground()
        lifecycleScope.launch(Dispatchers.IO) { refresher.refreshIfStale() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val route = deviceLoginPairRouteOrNull(intent.dataString)
            ?: inviteClaimRouteOrNull(intent.dataString)
            ?: notificationRouteOrNull(intent)
            ?: contentDeepLinkRouteOrNull(intent.dataString)
        route?.let { pendingExternalRouteRequests.value = externalRouteRequestFactory.create(it) }
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
        deviceLoginPairRouteOrNull(intent?.dataString)?.let { return it }

        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        val tokenManager = get<TokenManager>(TokenManager::class.java)

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
