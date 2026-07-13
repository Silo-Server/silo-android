package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerScreenStartPositionTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt",
    ).readText()

    @Test
    fun playerScreenDelegatesInitialMountToVideoBackend() {
        assertTrue(
            source.contains("VideoPlayerMediaSpec("),
            "mobile player must build the shared video media spec",
        )
        assertTrue(
            source.contains("VideoPlaybackBackendFactory"),
            "mobile player must inject the shared backend factory",
        )
        assertTrue(
            source.contains("backend.mount(mediaSpec"),
            "mobile player must mount through the backend",
        )
        assertTrue(
            !source.contains("controller.setMediaItem(mediaItem, startMs)"),
            "mobile player must not duplicate initial Media3 mount ordering",
        )
        assertFalse(
            source.contains("mountVideoMedia("),
            "mobile player must not call the raw Media3 mounter directly",
        )
        assertTrue(
            source.contains("durationSeconds = uiState.duration"),
            "mobile player media specs must carry known duration into system media metadata",
        )
    }

    @Test
    fun playerScreenMountsMedia3WithoutAnEngineSwitchRoundTrip() {
        val mountEffect = source
            .substringAfter("// Set up the media item when stream URL becomes available")
            .substringBefore("// Mid-playback subtitle refresh")

        assertTrue(
            mountEffect.contains("val delivery = plan?.delivery ?: uiState.delivery") &&
                mountEffect.contains("delivery = delivery"),
            "mobile mounts must carry playback-plan or inferred delivery",
        )
        assertFalse(mountEffect.contains("awaitEngineSwitch"))
        assertFalse(mountEffect.contains("sendCustomCommand"))
        assertTrue(mountEffect.contains("backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)"))
        assertTrue(
            mountEffect.contains("uiState.mediaMountGeneration") &&
                mountEffect.contains("viewModel.onMediaMountApplied(uiState.mediaMountGeneration)"),
            "server recovery must force a new mount and acknowledge it before old positions are accepted",
        )
    }

    @Test
    fun playerScreenNeverSilentlyReplacesTheViewModelTransportWithADownload() {
        val mountEffect = source
            .substringAfter("// Set up the media item when stream URL becomes available")
            .substringBefore("// Mid-playback subtitle refresh")

        assertTrue(mountEffect.contains("val effectiveStreamUrl = streamUrl"))
        assertTrue(mountEffect.contains("val plan = uiState.playbackPlan"))
        assertFalse(mountEffect.contains("locateLocalMedia"))
        assertFalse(source.contains("DownloadStorage = koinInject()"))
    }

    @Test
    fun transportReopenRearmsSharedWatchdog() {
        assertTrue(source.contains("uiState.playbackPlan?.decisionTrace?.size"))
        assertTrue(source.contains("plan?.decisionTrace?.size ?: 0"))
    }

    @Test
    fun playerScreenDelegatesSubtitleRefreshToVideoBackend() {
        assertTrue(
            source.contains("backend.refresh(mediaSpec"),
            "mobile subtitle refresh must use the backend refresh path",
        )
        assertFalse(
            source.contains("refreshMountedVideoMedia("),
            "mobile subtitle refresh must not call the raw Media3 refresh helper directly",
        )
    }

    @Test
    fun playerScreenTrackSelectionUsesMountedBackendState() {
        assertTrue(
            source.contains("backend.selectSubtitle("),
            "mobile subtitle selection must go through the mounted backend",
        )
        assertFalse(
            source.contains("trackSelectionMediaSpec("),
            "mobile player must not rebuild media specs for track selection once the backend owns mounted state",
        )
    }

    @Test
    fun playerScreenTrackChangeReselectsMountedSubtitleWithoutRemountingMedia() {
        val trackChangeBody = source
            .substringAfter("override fun onTracksChanged(tracks: androidx.media3.common.Tracks)")
            .substringBefore("controller.addListener(listener)")

        assertTrue(
            trackChangeBody.contains("videoBackend?.selectMountedSubtitle("),
            "track changes must reselect the already-mounted subtitle through the backend",
        )
        assertFalse(
            trackChangeBody.contains("selectSubtitle("),
            "track changes must not use the remounting subtitle selection path",
        )
        assertFalse(
            trackChangeBody.contains("VideoPlayerMediaSpec("),
            "track changes must not rebuild the media spec or remount media",
        )
    }

    @Test
    fun playerScreenDoesNotTurnPositionMirrorUpdatesIntoSeeks() {
        assertFalse(
            source.contains("LaunchedEffect(mediaController, uiState.position)"),
            "progress mirroring must not be keyed as a seek side effect",
        )
        assertTrue(
            source.contains("viewModel.seekRequests.collect"),
            "explicit user seeks must flow through a dedicated seek request channel",
        )
        assertTrue(
            source.contains("controller.seekTo((posSec * 1000).toLong())"),
            "seek requests must be the path that drives MediaController.seekTo",
        )
    }

    @Test
    fun pausedSeeksStillPublishTheirSettledPosition() {
        val playerListener = source
            .substringAfter("val listener = object : Player.Listener")
            .substringBefore("controller.addListener(listener)")
        val positionTicker = source
            .substringAfter("// Lifecycle-bounded position ticker.")
            .substringBefore("LaunchedEffect(\n        mediaController,\n        uiState.sessionId")

        assertTrue(playerListener.contains("override fun onPositionDiscontinuity("))
        assertTrue(playerListener.contains("newPosition.positionMs"))
        assertTrue(positionTicker.contains("if (controller.mediaItemCount > 0)"))
        assertFalse(positionTicker.contains("controller.isPlaying ||"))
    }

    @Test
    fun playerScreenDoesNotMountOrRefreshMediaWhileExiting() {
        val mountEffect = source
            .substringAfter("// Set up the media item when stream URL becomes available")
            .substringBefore("// Mid-playback subtitle refresh")
        val refreshEffect = source
            .substringAfter("// Mid-playback subtitle refresh")
            .substringBefore("// Handle subtitle selection")

        assertTrue(
            mountEffect.contains("if (exitRequested) return@LaunchedEffect"),
            "mobile player must not mount media after exit has been requested",
        )
        assertTrue(
            refreshEffect.contains("if (exitRequested) return@LaunchedEffect"),
            "mobile player must not refresh media after exit has been requested",
        )
    }

    @Test
    fun playerScreenDetectsDirectStartupStallsAndUsesExistingFallbackPath() {
        assertTrue(source.contains("PlaybackStartupStallDetector"))
        assertTrue(source.contains("startupStallDetector.onMounted("))
        assertTrue(source.contains("startupStallDetector.sample("))
        assertTrue(source.contains("viewModel.onUnsupportedPlayback(reason)"))
    }
}
