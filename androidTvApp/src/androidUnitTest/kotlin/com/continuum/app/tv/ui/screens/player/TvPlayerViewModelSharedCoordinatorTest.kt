package com.continuum.app.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerViewModelSharedCoordinatorTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()
    private val moduleSource = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/tv/di/AndroidTvModule.kt",
    ).readText()

    @Test
    fun tvPlayerViewModelStartsPlaybackThroughSharedCoordinator() {
        assertTrue(
            viewModelSource.contains("VideoPlaybackSessionCoordinator"),
            "TV player ViewModel must depend on the shared video playback coordinator",
        )
        assertTrue(
            viewModelSource.contains("VideoPlaybackStartRequest("),
            "TV player ViewModel must build the shared video playback start request",
        )
        assertTrue(
            viewModelSource.contains("contentId = contentId"),
            "TV player ViewModel must pass contentId into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("preferredFileId = preferredFileIdOverride ?: preferredFileId"),
            "TV player ViewModel must pass the resolved preferred file id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("roomId = roomId"),
            "TV player ViewModel must pass Watch Together room id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("resumePositionOverride = startPositionOverride"),
            "TV player ViewModel must pass the explicit resume override into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("videoPlaybackCoordinator.start("),
            "TV player ViewModel must delegate initial startup to the shared coordinator",
        )
        assertFalse(
            viewModelSource.contains("resolvePlaybackStartPosition"),
            "TV player ViewModel must not resolve initial playback positions directly",
        )
        assertFalse(
            viewModelSource.contains("resolvePlaybackStartRequestPosition"),
            "TV player ViewModel must not resolve session start positions directly",
        )
    }

    @Test
    fun tvPlaybackStarterOwnsTvStartupAlgorithm() {
        val starterFile = java.io.File(
            "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvVideoPlaybackStarter.kt",
        )
        assertTrue(
            starterFile.exists(),
            "TV playback startup algorithm must live in TvVideoPlaybackStarter",
        )
        val starterSource = starterFile.readText()

        assertTrue(
            starterSource.contains("class TvVideoPlaybackStarter"),
            "TV starter must expose TvVideoPlaybackStarter",
        )
        assertTrue(
            starterSource.contains("startSessionV2("),
            "TV starter must start playback sessions",
        )
        assertTrue(
            starterSource.contains("startTranscodeFallback("),
            "TV starter must preserve remux/transcode fallback",
        )
        assertTrue(
            starterSource.contains("resolvePlaybackStartPosition("),
            "TV starter must preserve resolved resume/start position semantics",
        )
        assertTrue(
            starterSource.contains("resolvePlaybackStartRequestPosition("),
            "TV starter must preserve explicit Start Over request semantics",
        )
        assertTrue(
            starterSource.contains("import com.continuum.app.model.playback.resolvePlaybackStartPosition"),
            "TV starter must use the shared resume resolver that mobile and audiobooks use",
        )
        assertTrue(
            starterSource.contains("import com.continuum.app.model.playback.resolvePlaybackStartRequestPosition"),
            "TV starter must use the shared start-request resolver that mobile and audiobooks use",
        )
        assertFalse(
            starterSource.contains("private fun resolvePlaybackStartPosition("),
            "TV starter must not keep a private copy of shared resume semantics",
        )
        assertFalse(
            starterSource.contains("private fun resolvePlaybackStartRequestPosition("),
            "TV starter must not keep a private copy of shared start-request semantics",
        )
        assertTrue(
            starterSource.contains("adoptActiveSession("),
            "TV starter must adopt the initial session into PlaybackSessionLifecycle",
        )
        assertTrue(
            starterSource.contains("requestedOriginalPlaybackMethod(") &&
                starterSource.contains("audioTrackIndex = request.audioTrackIndex") &&
                starterSource.contains("playMethod = requestedPlayMethod"),
            "TV starter must request original direct playback only after checking the selected audio track",
        )
        assertTrue(
            starterSource.contains("preserveDirectSelection") &&
                starterSource.contains("preserveDirectAudioSelection = preserveDirectSelection"),
            "TV starter must preserve client-side audio selection when direct-original playback is requested",
        )
        assertFalse(
            starterSource.contains("manageProgress = false"),
            "TV starter must let PlaybackSessionLifecycle own progress reporting and resume persistence",
        )
        assertFalse(
            starterSource.contains("stopSessionOnStop = false"),
            "TV starter must let PlaybackSessionLifecycle stop the adopted session on exit",
        )
    }

    @Test
    fun tvUnsupportedFallbackAdoptsReturnedSessionIntoLifecycle() {
        val unsupportedBody = viewModelSource
            .substringAfter("fun onUnsupportedPlayback(")
            .substringBefore("fun onPositionChanged(positionMs: Long, durationMs: Long)")

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
        assertTrue(
            viewModelSource.contains("private val capabilityDetector: PlaybackCapabilityDetector"),
            "TV fallback lifecycle adoption needs real device capabilities for recovery restarts",
        )
    }

    @Test
    fun androidTvModuleWiresStarterAndCoordinator() {
        assertTrue(
            moduleSource.contains("TvVideoPlaybackStarter"),
            "TV Koin module must provide the TV video playback starter",
        )
        assertTrue(
            moduleSource.contains("VideoPlaybackSessionCoordinator"),
            "TV Koin module must provide the shared video playback coordinator",
        )
        assertTrue(
            moduleSource.contains("capabilityDetector = get()"),
            "TV Koin module must inject PlaybackCapabilityDetector into TvPlayerViewModel",
        )
    }
}
