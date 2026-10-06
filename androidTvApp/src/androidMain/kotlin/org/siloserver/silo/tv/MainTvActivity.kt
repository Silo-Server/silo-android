package org.siloserver.silo.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import org.siloserver.silo.common.diagnostics.DiagnosticsLifecycleLogger
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.ServerDrivenConfigRefresher
import org.siloserver.silo.common.settings.TitleArtStore
import org.siloserver.silo.common.startup.StartupArtworkPlan
import org.siloserver.silo.common.startup.warmAuthenticatedStartup
import org.siloserver.silo.common.startup.warmProfileSelectionStartup
import org.siloserver.silo.common.ui.components.StartupSplashVideo
import org.siloserver.silo.common.ui.components.StartupSplashResizeMode
import org.siloserver.silo.network.IdentityTransition
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.requiresApproval
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import org.siloserver.silo.tv.ui.navigation.TvAppNavigation
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.tv.ui.screens.player.TvPlayerRemoteKeyBridge
import org.siloserver.silo.tv.ui.theme.SiloTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
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

        /** Shared with [TvAppNavigation]'s consumer so intake and consumption
         * of a deep link line up in one logcat filter. */
        const val DEEP_LINK_TAG = "SiloDeepLink"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // "Keys do nothing" with no other SiloTvFocus lines afterwards means
        // input is going to whichever window took focus (typically the Google
        // TV launcher), not to this app — an OS/emulator condition, not ours.
        TvFocusLog.d { "window focus ${if (hasFocus) "GAINED" else "LOST"}" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Own our window insets so WindowInsets.ime is dispatched to Compose.
        // The login/server-setup screens already use imePadding()+bringIntoView
        // to lift fields above the soft keyboard, but without this the decor
        // view consumes the IME inset (reports 0) and those become no-ops — so
        // on Android TV the keyboard covered the URL/login fields.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Capture the launching intent's Uri (if any) before Compose starts so
        // the navigation collector observes it as soon as it subscribes.
        handleIntent(intent)

        // Run the SiloCast receiver while the app is started AND signed in,
        // following sign-in, profile selection and sign-out without needing a
        // background/foreground cycle. start()/stop() run here on the main
        // thread, and repeatOnLifecycle cancels this block at ON_STOP, so no
        // start() can land after onStop()'s stop().
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val receiver = get<TvSiloCastReceiver>(TvSiloCastReceiver::class.java)
                val identityTransitions =
                    get<IdentityTransitionBarrier>(IdentityTransitionBarrier::class.java)
                var running = false
                castAuthentication(identityTransitions).collect { state ->
                    // A read that crossed the IO hop after a newer identity
                    // change is stale. Every generation bump ends in a
                    // DID_CHANGE that triggers a fresh read, so wait for that
                    // one; acting on this could stop a handoff that just began.
                    if (state.generation != identityTransitions.generation.value) return@collect
                    if (state.authenticated == running) return@collect
                    running = state.authenticated
                    if (running) receiver.start() else receiver.stop()
                }
            }
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
                val splashVisible = resolvedRoute == null || !splashPlaybackComplete
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent {
                            // Consume ALL input at the root while the splash
                            // overlay is up: no input-dispatch-timeout ANR, and
                            // no keys leak into the app pre-rendering below —
                            // even after its content grabs focus.
                            if (splashVisible) {
                                TvFocusLog.d { "key swallowed by splash gate" }
                            }
                            splashVisible
                        },
                ) {
                    if (resolvedRoute != null) {
                        // Compose the real app UNDER the splash overlay so Home
                        // fetches, paints its rows/hero art, and settles entry
                        // focus while the animation plays. Lifting the overlay
                        // then reveals a finished screen — no spinner, no
                        // image fade-up — mirroring tvOS "paint Home fully on
                        // first frame after startup splash".
                        TvAppNavigation(
                            startDestination = resolvedRoute,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (splashVisible) {
                        val splashFocus = remember { FocusRequester() }
                        // PURE black — the splash video's encoded black is #000000, and any
                        // off-black (the old 0xFF070509) reads as a visible box
                        // around the small video layer (QA 2026-07-08).
                        val splashBackground = Color.Black
                        // Holds key-event routing until the app UI below exists;
                        // its content may later claim focus, which is fine — the
                        // root gate above still swallows every event.
                        LaunchedEffect(Unit) { runCatching { splashFocus.requestFocus() } }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(splashBackground)
                                .focusRequester(splashFocus)
                                .focusable(),
                        ) {
                            if (!splashPlaybackComplete) {
                                // tvOS StartupSplashView parity: the video plays in a
                                // SMALL centered box — min(25% of screen width, 440pt
                                // → 220dp at Android TV scale) — on black, NOT
                                // full-bleed (full-bleed rendered the logo 4x too
                                // large; QA 2026-07-08).
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    val videoWidth = minOf(maxWidth * 0.25f, 220.dp)
                                    StartupSplashVideo(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .width(videoWidth)
                                            .aspectRatio(16f / 9f),
                                        resizeMode = StartupSplashResizeMode.Fit,
                                        backgroundColor = splashBackground,
                                        // tvOS parity: the splash completes at video end
                                        // or 4s, whichever comes first (StartupSplashView
                                        // maximumDisplayDuration) — never a floor past it.
                                        maxVisibleMillis = 4_000L,
                                        onPlaybackComplete = {
                                            splashPlaybackComplete = true
                                            hasShownColdSplash = true
                                        },
                                    )
                                }
                            }
                        }
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
        // Desktop TV emulators deliver the host keyboard's Escape key as
        // KEYCODE_ESCAPE, not always as Android's KEYCODE_BACK. Translate it
        // before normal window dispatch so a focused popup/menu gets first
        // refusal, exactly as it does for a physical remote's Back button.
        val translatedEvent = if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                KeyEvent.KEYCODE_BACK,
                event.repeatCount,
                event.metaState,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source,
            )
        } else {
            event
        }
        if (TvPlayerRemoteKeyBridge.dispatch(translatedEvent)) return true
        return super.dispatchKeyEvent(translatedEvent)
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
        // `silo` is the only scheme the manifest registers; anything else is
        // an unrelated launch intent and must not clobber a queued URI.
        if (data.scheme == "silo") {
            Log.i(DEEP_LINK_TAG, "deep link queued: ${data.host}/${data.lastPathSegment}")
            pendingDeepLink.value = data
        }
    }

    /**
     * Mirror of the phone app's app-background flush — drain pending
     * device-setting writes when the user leaves so a process kill in
     * the debounce window doesn't lose what they just toggled.
     */
    override fun onStop() {
        DiagnosticsLifecycleLogger.state("background")
        super.onStop()
        val monitor = get<ServerReachabilityMonitor>(ServerReachabilityMonitor::class.java)
        monitor.stopForeground()
        get<TvSiloCastReceiver>(TvSiloCastReceiver::class.java).stop()
        val store = get<PlayerSettingsStore>(PlayerSettingsStore::class.java)
        lifecycleScope.launch { store.flushPendingDeviceSettings() }
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

        // Restored servers were probed by whichever build saved them (or never,
        // before the v2 pilot); re-establish the contract verdict once per launch.
        // A stored UPDATE_REQUIRED may be stale (server upgraded since), and a
        // stored UNKNOWN (first launch after upgrading the app) passes the gate,
        // so authenticated startup consumers would race the probe and could
        // receive raw v2 404s from a v1-only server. Both wait (bounded) for
        // the probe before routing; only a settled V2 skips the wait. A null
        // result (V2, timeout, or failure) makes the name refresh re-probe.
        val authRepository = get<AuthRepository>(AuthRepository::class.java)
        val knownContract = kotlinx.coroutines.withContext(Dispatchers.IO) {
            authRepository.awaitContractRefreshIfUnsettled()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            authRepository.refreshActiveServerName(knownContract = knownContract)
        }

        val cleartextConsent = get<org.siloserver.silo.network.CleartextOriginConsent>(
            org.siloserver.silo.network.CleartextOriginConsent::class.java,
        )
        if (cleartextConsent.requiresApproval(activeEntry.url)) {
            return TvRoute.ServerSetup.route
        }

        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) return TvRoute.Login().route

        val profileId = tokenManager.getProfileId()
        if (profileId.isNullOrBlank()) return TvRoute.ProfileSelection.route

        return TvRoute.Main.route
    }

    private fun launchAuthenticatedStartupWarmup(startRoute: String) {
        // A launch that lands on profile selection still warms the profile
        // list + avatar art so the grid paints finished after the splash
        // (Apple's prefetchForInitialRoute(.needsProfile)).
        if (startRoute == TvRoute.ProfileSelection.route) {
            lifecycleScope.launch(Dispatchers.IO) {
                warmProfileSelectionStartup(
                    context = applicationContext,
                    profileRepository = get(ProfileRepository::class.java),
                    serverUrl = get<ServerRegistry>(ServerRegistry::class.java).activeEntry.value?.url,
                )
            }
            return
        }
        if (startRoute != TvRoute.Main.route) return
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
                artworkPlan = StartupArtworkPlan.tv(),
                // What Home will draw right now (cached answer, else logos on);
                // never waits on the title art request.
                showTitleArt = { get<TitleArtStore>(TitleArtStore::class.java).state.value.showTitleArt },
            )
        }
    }

    /** Whether the TV may receive casts, read under identity [generation]. */
    private data class CastAuthState(val authenticated: Boolean, val generation: Long)

    /**
     * Emits whether the TV is signed in far enough to receive casts, re-read
     * after every committed identity change, so signing in, picking a profile
     * or signing out while the app stays open starts or stops the receiver.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun castAuthentication(identityTransitions: IdentityTransitionBarrier): Flow<CastAuthState> {
        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        // Widened to nullable so a null marker can stand for "subscribed".
        val transitions: SharedFlow<IdentityTransition?> = identityTransitions.transitions
        return merge(
            // The marker triggers the first read only once the subscription is
            // live, so a transition finishing at that moment can't be missed.
            // DID_CHANGE, not WILL_CHANGE: the new identity is only readable
            // after the transition's block has run.
            transitions
                .onSubscription { emit(null) }
                .filter { it == null || it.phase == IdentityTransitionPhase.DID_CHANGE },
            registry.activeEntry,
        )
            .mapLatest {
                // Captured before the read, so a transition landing mid-read
                // marks the result stale.
                val generation = identityTransitions.generation.value
                CastAuthState(isAuthenticatedForCast(), generation)
            }
            // Keeps a repeated answer under a new generation: the collector may
            // have dropped the earlier one as stale.
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    private suspend fun isAuthenticatedForCast(): Boolean {
        val registry = get<ServerRegistry>(ServerRegistry::class.java)
        val tokenManager = get<TokenManager>(TokenManager::class.java)
        // A remote-playback overlay counts as signed in: stopping the receiver
        // would end the handoff it belongs to.
        if (tokenManager.hasTemporaryScope()) return true
        return registry.activeEntry.value != null &&
            !tokenManager.getAccessToken().isNullOrBlank() &&
            !tokenManager.getProfileId().isNullOrBlank()
    }
}
