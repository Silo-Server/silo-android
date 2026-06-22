package com.continuum.app.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerViewModelSharedCoordinatorTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerViewModel.kt",
    ).readText()
    private val moduleSource = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/android/di/AndroidModule.kt",
    ).readText()

    @Test
    fun mobilePlayerViewModelStartsRemotePlaybackThroughSharedCoordinator() {
        assertTrue(
            viewModelSource.contains("VideoPlaybackSessionCoordinator"),
            "Mobile player ViewModel must depend on the shared video playback coordinator",
        )
        assertTrue(
            viewModelSource.contains("VideoPlaybackStartRequest("),
            "Mobile player ViewModel must build the shared video playback start request",
        )
        assertTrue(
            viewModelSource.contains("contentId = contentId"),
            "Mobile player ViewModel must pass contentId into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("preferredFileId = preferredFileId"),
            "Mobile player ViewModel must pass the requested file id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("roomId = null"),
            "Mobile player ViewModel must explicitly pass no Watch Together room id for normal mobile startup",
        )
        assertTrue(
            viewModelSource.contains("resumePositionOverride = resumePositionOverride"),
            "Mobile player ViewModel must preserve explicit resume override semantics",
        )
        assertTrue(
            viewModelSource.contains("subtitleTrackIndex = initialSubtitleTrackIndex"),
            "Mobile player ViewModel must pass initial subtitle selection into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("videoPlaybackCoordinator.start("),
            "Mobile player ViewModel must delegate remote startup to the shared coordinator",
        )
    }

    @Test
    fun mobilePlayerViewModelKeepsOfflineFallbackBeforeRemoteCoordinatorStartup() {
        assertTrue(
            viewModelSource.contains("private suspend fun tryLocalPlayback"),
            "Mobile player ViewModel must retain the offline/local playback fallback",
        )

        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val localIndex = loadContentBody.indexOf("tryLocalPlayback(")
        val coordinatorIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(localIndex >= 0, "loadContent must try local playback")
        assertTrue(coordinatorIndex >= 0, "loadContent must start remote playback through the coordinator")
        assertTrue(
            localIndex < coordinatorIndex,
            "Mobile offline/local playback fallback must run before remote coordinator startup",
        )
        assertFalse(
            loadContentBody.contains("playbackSessionManager.startSession("),
            "Mobile initial remote startup must not start sessions directly in PlayerViewModel",
        )
    }

    @Test
    fun mobileVersionSwitchStopsLifecycleBeforeReplacingSession() {
        val onSelectVersionBody = viewModelSource
            .substringAfter("fun onSelectVersion(")
            .substringBefore("fun onPlaybackSpeedChanged")
        val lifecycleStopIndex = onSelectVersionBody.indexOf("sessionLifecycle.stop()")
        val directStopIndex = onSelectVersionBody.indexOf("playbackSessionManager.stopSession(")
        val startIndex = onSelectVersionBody.indexOf("playbackSessionManager.startSessionV2(")

        assertTrue(lifecycleStopIndex >= 0, "version switch must stop lifecycle ownership first")
        assertTrue(startIndex >= 0, "version switch must still start the selected version through v2 planning")
        assertTrue(
            lifecycleStopIndex < startIndex,
            "version switch must cancel the old lifecycle session before starting the new one",
        )
        assertTrue(
            directStopIndex < 0 || lifecycleStopIndex < directStopIndex,
            "direct session stop must not race the lifecycle owner",
        )
        assertTrue(
            onSelectVersionBody.contains("val resolvedStartPosition = if (") &&
                onSelectVersionBody.contains("resolved.sessionId != session.sessionId") &&
                onSelectVersionBody.contains("startPosition = resolvedStartPosition"),
            "version switch fallback sessions must use the returned player start position",
        )
        assertTrue(
            onSelectVersionBody.contains("requestedOriginalPlaybackMethod(") &&
                onSelectVersionBody.contains("audioTrackIndex = currentState.selectedAudioIndex"),
            "version switch direct-play requests must consider the currently selected audio track",
        )
        assertTrue(
            onSelectVersionBody.contains("playMethod = requestedPlayMethod"),
            "version switch must pass the resolved direct-play preference to session planning",
        )
    }

    @Test
    fun mobileUnsupportedFallbackAdoptsReturnedSessionIntoLifecycle() {
        val unsupportedBody = viewModelSource
            .substringAfter("fun onUnsupportedPlayback(")
            .substringBefore("/** Called by the player when the current position changes. */")

        assertTrue(
            unsupportedBody.contains("startTranscodeFallback("),
            "unsupported direct play must request a fallback stream",
        )
        assertTrue(
            unsupportedBody.contains("sessionLifecycle.adoptActiveSession("),
            "fallback success must re-home lifecycle progress/stop ownership to the returned session",
        )
        assertTrue(
            unsupportedBody.contains("StartParams("),
            "fallback lifecycle adoption must preserve restart parameters for 404 recovery",
        )
    }

    @Test
    fun mobilePlaybackStarterOwnsRemoteStartupAlgorithm() {
        val starterFile = java.io.File(
            "src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/MobileVideoPlaybackStarter.kt",
        )
        assertTrue(
            starterFile.exists(),
            "Mobile remote playback startup algorithm must live in MobileVideoPlaybackStarter",
        )
        val starterSource = starterFile.readText()

        assertTrue(
            starterSource.contains("class MobileVideoPlaybackStarter"),
            "Mobile starter must expose MobileVideoPlaybackStarter",
        )
        assertTrue(
            starterSource.contains("startSessionV2("),
            "Mobile starter must start plan-aware playback sessions",
        )
        assertTrue(
            starterSource.contains("detectPlaybackContext(") &&
                starterSource.contains("formFactor = \"mobile\""),
            "Mobile starter must send a mobile client playback context for route planning",
        )
        assertTrue(
            starterSource.contains("subtitleTrackIndex = request.subtitleTrackIndex"),
            "Mobile starter must pass subtitle selection into playback planning",
        )
        assertTrue(
            starterSource.contains("startTranscodeFallback("),
            "Mobile starter must preserve remux/transcode fallback",
        )
        assertTrue(
            starterSource.contains("playbackPlan = resolved.playbackPlan"),
            "Mobile starter must return the server playback plan to the player UI",
        )
        assertTrue(
            starterSource.contains("session.canPlayResolvedStreamDirectly()") &&
                starterSource.contains("delivery = resolvedDelivery"),
            "Mobile starter must direct-play legacy progressive remux sessions without HLS fallback",
        )
        assertTrue(
            starterSource.contains("preserveDirectSelection") &&
                starterSource.contains("requestedPlayMethod == PlayMethod.DIRECT") &&
                starterSource.contains("audioTrackIndex = request.audioTrackIndex") &&
                starterSource.contains("preserveDirectAudioSelection = preserveDirectSelection"),
            "Mobile starter must preserve client-side audio selection when requesting original direct playback",
        )
        assertTrue(
            starterSource.contains("resolvePlaybackStartPosition("),
            "Mobile starter must preserve resolved resume/start position semantics",
        )
        assertTrue(
            starterSource.contains("resolvePlaybackStartRequestPosition("),
            "Mobile starter must preserve explicit Start Over request semantics",
        )
        assertTrue(
            starterSource.contains("adoptActiveSession("),
            "Mobile starter must adopt the initial session into PlaybackSessionLifecycle",
        )
        assertFalse(
            starterSource.contains("manageProgress = false"),
            "Mobile starter must use lifecycle-managed progress/recovery adoption",
        )
    }

    @Test
    fun androidModuleWiresMobileStarterAndCoordinatorIntoPlayerViewModel() {
        assertTrue(
            moduleSource.contains("MobileVideoPlaybackStarter"),
            "Android Koin module must provide the mobile video playback starter",
        )
        assertTrue(
            moduleSource.contains("VideoPlaybackSessionCoordinator"),
            "Android Koin module must provide the shared video playback coordinator",
        )
        assertTrue(
            moduleSource.contains("mobileVideoPlaybackStarter"),
            "Android Koin module should name the mobile starter binding",
        )
        assertTrue(
            moduleSource.contains("videoPlaybackCoordinator = get()"),
            "Android Koin module must inject the shared coordinator into PlayerViewModel",
        )
    }
}
