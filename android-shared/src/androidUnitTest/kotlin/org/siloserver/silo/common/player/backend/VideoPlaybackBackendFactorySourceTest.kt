package org.siloserver.silo.common.player.backend

import kotlin.test.Test
import kotlin.test.assertTrue

class VideoPlaybackBackendFactorySourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/backend/VideoPlaybackBackendFactory.kt",
    )

    @Test
    fun factoryWrapsBoundPlayerWithSelectedBackend() {
        val text = source.readText()

        assertTrue(text.contains("class VideoPlaybackBackendFactory"))
        assertTrue(text.contains("fun create("))
        assertTrue(text.contains("player: Player"))
        assertTrue(text.contains("request: VideoPlaybackBackendRequest = VideoPlaybackBackendRequest()"))
        assertTrue(text.contains("VideoPlaybackBackendSelector.select(request)"))
        assertTrue(text.contains("Media3VideoPlaybackBackend("))
        assertTrue(!text.contains("MpvVideoPlaybackBackend("))
        assertTrue(text.contains("VideoTrackSelectionCoordinator(subtitleManager)"))
        assertTrue(!text.contains("createPlayer("), "factory must wrap the bound MediaController, not create a service player")
        assertTrue(!text.contains("Build.VERSION.SDK_INT"))
    }
}
