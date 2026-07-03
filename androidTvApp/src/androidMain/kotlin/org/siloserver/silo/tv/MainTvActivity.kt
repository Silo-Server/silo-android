package org.siloserver.silo.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.lifecycle.lifecycleScope
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.startup.warmAuthenticatedStartup
import org.siloserver.silo.common.ui.components.StartupSplashVideo
import org.siloserver.silo.common.ui.components.StartupSplashResizeMode
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.tv.control.TvControlReceiver
import org.siloserver.silo.tv.ui.navigation.TvAppNavigation
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.tv.ui.screens.player.TvPlayerRemoteKeyBridge
import org.siloserver.silo.tv.ui.theme.SiloTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get

class MainTvActivity : ComponentActivity() {

    // Shared flow with [TvAppNavigation]. We publish the launching Uri here so
    // the navigation Composable can consume it once the auth chain has landed
    // the user on Main — see [handleIntent] and the collector in TvAppNavigation.
    private val pendingDeepLink: MutableStateFlow<Uri?> by inject(named("pendingDeepLink"))

    companion object {
        // The startup splash plays once per process (a genuine cold launch). It
        // survives Activity recreation / return-from-background so we don't replay
        // it on warm re-open; a process death resets it, which is itself a cold
        // start. Mirrors the phone-side flag in MainActivity.
        @Volatile
        private var hasShownColdSplash = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture the launching intent's Uri (if any) before Compose starts so
        // the navigation collector observes it as soon as it subscribes.
        handleIntent(intent)

        // SiloControl receiver: advertise `_silocast._tcp` while a server is
        // active so a phone can remote-control TV playback. The receiver
        // observes ServerRegistry.activeEntry itself (no-op until a server is
        // configured). Guarded — a networking accelerator, never load-bearing.
        runCatching {
            get<TvControlReceiver>(TvControlReceiver::class.java).start()
        }.onFailure {
            android.util.Log.w("MainTvActivity", "SiloControl receiver start failed", it)
        }

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var splashPlaybackComplete by remember { mutableStateOf(hasShownColdSplash) }

            LaunchedEffect(Unit) {
                val route = resolveStartDestination()
                startRoute = route
                launchAuthenticatedStartupWarmup(route)
            }

            SiloTvTheme {
                val resolvedRoute = startRoute
                if (resolvedRoute == null || !splashPlaybackComplete) {
                    val splashFocus = remember { FocusRequester() }
                    val splashBackground = Color(0xFF070509)
                    LaunchedEffect(Unit) { runCatching { splashFocus.requestFocus() } }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(splashBackground)
                            .focusRequester(splashFocus)
                            .focusable()
                            .onPreviewKeyEvent {
                                // Consume input during the splash so it never causes an
                                // input-dispatch-timeout ANR.
                                true
                            },
                    ) {
                        if (!splashPlaybackComplete) {
                            StartupSplashVideo(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxSize(),
                                resizeMode = StartupSplashResizeMode.Crop,
                                backgroundColor = splashBackground,
                                minVisibleMillis = 5_200L,
                                onPlaybackComplete = {
                                    splashPlaybackComplete = true
                                    hasShownColdSplash = true
                                },
                            )
                        }
                    }
                } else {
                    TvAppNavigation(
                        startDestination = resolvedRoute,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /**
     * Warm-launch deep links arrive here while the Activity is already alive
     * (singleTop / singleTask). Forward to [handleIntent] and update the
     * Activity's stored intent so [getIntent] reflects the latest payload.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        setIntent(intent)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TvPlayerRemoteKeyBridge.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Pushes a Silo deep-link Uri into the shared [pendingDeepLink] flow for
     * [TvAppNavigation] to consume. Non-Silo schemes (and intents without data)
     * are ignored so unrelated launch intents don't clobber a queued URI.
     * Nullable parameter to accommodate the cold-launch call site where the
     * Activity's intent may be null.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        // `silo` is the current app scheme; `silo` is kept for legacy
        // Watch Next and pairing links created by older builds.
        if (data.scheme == "silo" || data.scheme == "silo") {
            pendingDeepLink.value = data
        }
    }

    /**
     * Mirror of the phone app's app-background flush — drain pending
     * device-setting writes when the user leaves so a process kill in
     * the debounce window doesn't lose what they just toggled.
     */
    override fun onStop() {
        super.onStop()
        val store = get<PlayerSettingsStore>(PlayerSettingsStore::class.java)
        lifecycleScope.launch { store.flushPendingDeviceSettings() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { get<TvControlReceiver>(TvControlReceiver::class.java).stop() }
    }

    /**
     * Mirrors the phone app's [org.siloserver.silo.android.MainActivity] startup
     * flow on top of the multi-server [ServerRegistry]. See that file for the
     * routing rules — they're identical: registry empty ⇒ ServerSetup,
     * tokens missing ⇒ Login, no active profile header scope ⇒
     * ProfileSelection, else Main.
     */
    private suspend fun resolveStartDestination(): String {
        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        val tokenManager = get<TokenManager>(TokenManager::class.java)

        val activeEntry = registry.activeEntry.value
            ?: return TvRoute.ServerSetup.route

        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) return TvRoute.Login().route

        val profileId = tokenManager.getProfileId()
        if (profileId.isNullOrBlank()) return TvRoute.ProfileSelection.route

        return TvRoute.Main.route
    }

    private fun launchAuthenticatedStartupWarmup(startRoute: String) {
        if (startRoute != TvRoute.Main.route) return
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
}
