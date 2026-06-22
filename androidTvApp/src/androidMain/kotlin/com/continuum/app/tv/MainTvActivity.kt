package com.continuum.app.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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
import com.continuum.app.tv.ui.navigation.TvAppNavigation
import com.continuum.app.tv.ui.navigation.TvRoute
import com.continuum.app.tv.ui.screens.player.TvPlayerRemoteKeyBridge
import com.continuum.app.tv.ui.theme.ContinuumTvTheme
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

        setContent {
            var startRoute by remember { mutableStateOf<String?>(null) }
            var splashPlaybackComplete by remember { mutableStateOf(hasShownColdSplash) }

            LaunchedEffect(Unit) {
                val route = resolveStartDestination()
                startRoute = route
                launchAuthenticatedStartupWarmup(route)
            }

            ContinuumTvTheme {
                val resolvedRoute = startRoute
                if (resolvedRoute == null || !splashPlaybackComplete) {
                    val splashFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { splashFocus.requestFocus() } }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF050505))
                            .focusRequester(splashFocus)
                            .focusable()
                            .onPreviewKeyEvent {
                                // Consume input during the splash so it never causes an
                                // input-dispatch-timeout ANR.
                                true
                            },
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val videoWidth = (maxWidth * 0.25f).coerceAtMost(440.dp)
                            StartupSplashVideo(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(videoWidth)
                                    .aspectRatio(16f / 9f),
                                backgroundColor = Color.Transparent,
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
     * Pushes a `continuum://` deep-link Uri into the shared [pendingDeepLink]
     * flow for [TvAppNavigation] to consume. Non-`continuum` schemes (and
     * intents without data) are ignored so unrelated launch intents don't
     * clobber a queued URI. Nullable parameter to accommodate the cold-launch
     * call site where the Activity's intent may be null.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        // `continuum` carries Watch Next / play links; `silo` carries
        // device-pairing handoffs (silo://device?token=…). Both are consumed
        // by TvAppNavigation's pendingDeepLink collector.
        if (data.scheme == "continuum" || data.scheme == "silo") {
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

    /**
     * Mirrors the phone app's [com.continuum.app.android.MainActivity] startup
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
