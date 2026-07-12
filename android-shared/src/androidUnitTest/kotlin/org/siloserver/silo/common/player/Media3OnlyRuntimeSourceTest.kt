package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Media3OnlyRuntimeSourceTest {
    @Test
    fun serviceOwnsOneMedia3PlayerWithoutEngineCommands() {
        val source = File("src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlaybackService.kt").readText()
        assertTrue(source.contains("MediaSession.Builder(this, player).build()"))
        assertFalse(source.contains("SET_ENGINE"))
        assertFalse(source.contains("switchEngine"))
        assertFalse(source.contains("createMpvPlayer"))
    }

    @Test
    fun selectorAndFactoryAreMedia3Only() {
        val selector = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/backend/VideoPlaybackBackendSelector.kt",
        ).readText()
        val factory = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/backend/VideoPlaybackBackendFactory.kt",
        ).readText()
        assertTrue(selector.contains("VideoPlaybackBackendKind.Media3"))
        assertFalse(selector.contains("VideoPlaybackBackendKind.Mpv"))
        assertTrue(factory.contains("Media3VideoPlaybackBackend"))
        assertFalse(factory.contains("MpvVideoPlaybackBackend"))
    }
}
