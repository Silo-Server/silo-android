package com.continuum.app.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerScreenStartPositionTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/android/ui/screens/player/PlayerScreen.kt",
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
    fun playerScreenAwaitsPlanAwareEngineSwitchBeforeMounting() {
        val mountEffect = source
            .substringAfter("// Set up the media item when stream URL becomes available")
            .substringBefore("// Mid-playback subtitle refresh")

        assertTrue(
            mountEffect.contains("val delivery = if (localUri == null) plan?.delivery ?: uiState.delivery else null") &&
                mountEffect.contains("delivery = delivery"),
            "mobile engine requests must carry playback-plan or inferred delivery",
        )
        assertTrue(
            mountEffect.contains("plannedEngine = plan?.engine"),
            "mobile engine requests must carry the server-planned engine",
        )
        assertTrue(
            mountEffect.contains("hasHardContainer = mediaSpec.playMethod == PlayMethod.DIRECT"),
            "mobile direct MKV/container routes must be visible to backend selection",
        )
        assertTrue(
            mountEffect.contains("hasStyledSubtitles = uiState.subtitleTracks.any { it.isStyledSubtitle() }"),
            "mobile styled subtitle routes must be visible to backend selection",
        )
        assertTrue(
            mountEffect.contains("val switchResult = controller.awaitEngineSwitch(engineRequest)"),
            "mobile player must wait for the service engine switch before mounting",
        )
        assertTrue(
            mountEffect.contains("if (switchResult.swapped)") &&
                mountEffect.contains("return@LaunchedEffect"),
            "mobile player must let ActivePlayerHolder rebind before mounting after a swap",
        )
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
