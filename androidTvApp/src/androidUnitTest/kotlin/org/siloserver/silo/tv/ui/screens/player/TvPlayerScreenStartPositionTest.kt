package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerScreenStartPositionTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()

    @Test
    fun tvPlayerDelegatesInitialMountToVideoBackend() {
        assertTrue(
            source.contains("VideoPlayerMediaSpec("),
            "TV player must build the shared video media spec",
        )
        assertTrue(
            source.contains("VideoPlaybackBackendFactory"),
            "TV player must inject the shared backend factory",
        )
        assertTrue(
            source.contains("backend.mount(mediaSpec"),
            "TV player must mount through the backend",
        )
        assertTrue(
            !source.contains("controller.setMediaItem(mediaItem)"),
            "TV player must not call the no-position Media3 mount overload",
        )
        assertTrue(
            !source.contains("controller.seekTo(startMs)"),
            "TV player must not use post-mount seekTo for initial resume",
        )
        assertTrue(
            !source.contains("mountVideoMedia("),
            "TV player must not call the raw Media3 mounter directly",
        )
        assertTrue(
            source.contains("durationSeconds = state.duration"),
            "TV player media specs must carry known duration into system media metadata",
        )
    }

    @Test
    fun tvPlayerDelegatesSubtitleRefreshToVideoBackend() {
        assertTrue(
            source.contains("backend.refresh(mediaSpec"),
            "TV subtitle refresh must use the backend refresh path",
        )
        assertTrue(
            !source.contains("refreshMountedVideoMedia("),
            "TV subtitle refresh must not call the raw Media3 refresh helper directly",
        )
    }

    @Test
    fun tvPlayerRoutesTrackSelectionThroughBackend() {
        assertTrue(
            source.contains("backend.selectSubtitle(") ||
                source.contains("videoBackend?.selectSubtitle("),
            "TV subtitle selection must go through the mounted backend",
        )
        assertTrue(
            source.contains("videoBackend?.selectAudioTrack("),
            "TV audio selection must go through the mounted backend",
        )
        assertTrue(
            !source.contains("trackSelectionCoordinator.selectSubtitle("),
            "TV player must not call the subtitle coordinator directly",
        )
        assertTrue(
            !source.contains("trackSelectionCoordinator.selectAudioTrack("),
            "TV player must not call the audio coordinator directly",
        )
    }

    @Test
    fun tvPlayerDoesNotLogSubtitleCueTextSamples() {
        assertFalse(
            source.contains("override fun onCues"),
            "TV player must not intercept subtitle cues for diagnostics",
        )
        assertFalse(
            source.contains("sample="),
            "TV player must not log subtitle cue text samples",
        )
        assertFalse(
            source.contains("cue.text"),
            "TV player must not read subtitle cue text for diagnostics",
        )
    }

    @Test
    fun tvPlayerDoesNotMountOrRefreshMediaWhileExiting() {
        val mountEffect = source
            .substringAfter("// Prepare the player when a stream URL becomes available.")
            .substringBefore("// Subtitle refresh")
        val refreshEffect = source
            .substringAfter("// Subtitle refresh")
            .substringBefore("// Auto-select a freshly downloaded/translated subtitle track")

        assertTrue(
            mountEffect.contains("if (exitRequested) return@LaunchedEffect"),
            "TV player must not mount media after exit has been requested",
        )
        assertTrue(
            refreshEffect.contains("if (exitRequested) return@LaunchedEffect"),
            "TV player must not refresh media after exit has been requested",
        )
    }

    @Test
    fun tvPlayerMountsMedia3WithoutAnEngineSwitchRoundTrip() {
        val mountEffect = source
            .substringAfter("// Prepare the player when a stream URL becomes available.")
            .substringBefore("// Subtitle refresh")

        assertFalse(mountEffect.contains("awaitEngineSwitch"))
        assertFalse(mountEffect.contains("sendCustomCommand"))
        assertTrue(mountEffect.contains("val delivery = plan?.delivery ?: state.delivery"))
        assertTrue(mountEffect.contains("delivery = delivery"))
        assertTrue(mountEffect.contains("backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)"))
    }

    @Test
    fun recoveryMountUsesNonceAndAcknowledgesAppliedTimeline() {
        val mountEffect = source
            .substringAfter("// Prepare the player when a stream URL becomes available.")
            .substringBefore("// Subtitle refresh")

        assertTrue(mountEffect.contains("state.transportMountNonce"))
        assertTrue(mountEffect.contains("backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)"))
        assertTrue(mountEffect.contains("viewModel.onTransportMountApplied(state.transportMountNonce)"))
    }

    @Test
    fun transportReopenRearmsSharedWatchdog() {
        val watchdogEffect = source
            .substringAfter(
                "state.playbackPlan?.planId,\n        state.playbackPlan?.decisionTrace?.size,",
            )
            .substringBefore("// Prepare the player when a stream URL becomes available.")
        val mountEffect = source
            .substringAfter("// Prepare the player when a stream URL becomes available.")
            .substringBefore("// Subtitle refresh")

        assertTrue(watchdogEffect.contains("state.playbackPlan?.decisionTrace?.size ?: 0"))
        assertTrue(watchdogEffect.contains("state.transportMountNonce"))
        assertTrue(mountEffect.contains("plan?.decisionTrace?.size ?: 0"))
        assertTrue(mountEffect.contains("state.transportMountNonce"))
    }
}
