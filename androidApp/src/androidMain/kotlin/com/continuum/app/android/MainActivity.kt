package com.continuum.app.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.continuum.app.android.downloads.LEGACY_PUBLIC_DOWNLOAD_PERMISSION
import com.continuum.app.android.downloads.hasLegacyPublicDownloadPermission
import com.continuum.app.android.ui.navigation.AppNavigation
import com.continuum.app.android.ui.navigation.Route
import com.continuum.app.android.ui.navigation.deviceLoginPairRouteOrNull
import com.continuum.app.android.ui.navigation.hasLocalDownloadsForScope
import com.continuum.app.android.ui.theme.ContinuumTheme
import com.continuum.app.common.settings.PlayerSettingsStore
import com.continuum.app.common.startup.warmAuthenticatedStartup
import com.continuum.app.common.ui.components.StartupSplashVideo
import com.continuum.app.network.ServerRegistry
import com.continuum.app.network.TokenManager
import com.continuum.app.repository.AuthRepository
import com.continuum.app.repository.PersonalDataRepository
import com.continuum.app.repository.ProfileRepository
import com.continuum.app.repository.SectionRepository
import com.continuum.app.repository.port.HomeCachePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private val incomingDeviceLoginRoutes = MutableSharedFlow<String>(extraBufferCapacity = 1)

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
            var pendingExternalRoute by remember { mutableStateOf<String?>(null) }
            var splashPlaybackComplete by remember { mutableStateOf(hasShownColdSplash) }

            LaunchedEffect(Unit) {
                val route = resolveStartDestination()
                startRoute = route
                launchAuthenticatedStartupWarmup(route)
            }
            LaunchedEffect(Unit) {
                incomingDeviceLoginRoutes.collect { route ->
                    pendingExternalRoute = route
                }
            }

            ContinuumTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val resolvedRoute = startRoute
                    if (resolvedRoute == null || !splashPlaybackComplete) {
                        StartupSplashVideo(
                            onPlaybackComplete = {
                                splashPlaybackComplete = true
                                hasShownColdSplash = true
                            },
                        )
                    } else {
                        AppNavigation(
                            startDestination = resolvedRoute,
                            pendingExternalRoute = pendingExternalRoute,
                            onExternalRouteConsumed = { pendingExternalRoute = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deviceLoginPairRouteOrNull(intent.dataString)?.let { route ->
            incomingDeviceLoginRoutes.tryEmit(route)
        }
    }

    /**
     * Mirrors iOS scene-resign / app-background flush — when the user
     * sends the app to the background, drain any debounced device
     * settings so the next session sees what they just toggled. Without
     * this, a process death within the 750ms debounce window would lose
     * the write.
     */
    override fun onStop() {
        super.onStop()
        val store = get<PlayerSettingsStore>(PlayerSettingsStore::class.java)
        lifecycleScope.launch { store.flushPendingDeviceSettings() }
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

        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) return Route.Login.route

        // Profile id is per-server: prefer the registry entry's saved value,
        // fall back to whatever the token manager has cached.
        val profileId = activeEntry.profileId ?: tokenManager.getProfileId()
        if (profileId.isNullOrBlank()) return Route.ProfileSelection.route

        // Offline-with-downloads fast path: if the device has no network AND
        // we have downloaded media on disk, land directly on the Downloads
        // tab so the user isn't greeted with HomeScreen's "Something went
        // wrong / Check your connection" error. Online flows are unchanged.
        if (!isOnline() && hasLocalDownloads(activeEntry.id, profileId)) {
            return Route.Downloads.route
        }

        return Route.Home.route
    }

    private fun launchAuthenticatedStartupWarmup(startRoute: String) {
        if (startRoute != Route.Home.route) return
        lifecycleScope.launch(Dispatchers.IO) {
            warmAuthenticatedStartup(
                authRepository = get(AuthRepository::class.java),
                profileRepository = get(ProfileRepository::class.java),
                personalDataRepository = get(PersonalDataRepository::class.java),
                sectionRepository = get(SectionRepository::class.java),
                homeCache = get(HomeCachePort::class.java),
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
        val storage = get<com.continuum.app.common.downloads.DownloadStorage>(
            com.continuum.app.common.downloads.DownloadStorage::class.java
        )
        return hasLocalDownloadsForScope(storage, serverId, profileId)
    }
}
