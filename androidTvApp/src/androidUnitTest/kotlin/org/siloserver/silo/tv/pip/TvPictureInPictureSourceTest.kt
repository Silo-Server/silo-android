package org.siloserver.silo.tv.pip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvPictureInPictureSourceTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val activity = File("src/androidMain/kotlin/org/siloserver/silo/tv/MainTvActivity.kt").readText()
    private val player = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()
    private val settings = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvSettingsScreen.kt",
    ).readText()

    @Test
    fun tvActivityDisablesPictureInPicture() {
        assertTrue(manifest.contains("""android:supportsPictureInPicture="false""""))
        assertTrue(!activity.contains("enterPictureInPictureIfEligible"))
    }

    @Test
    fun tvPlayerKeepsPlaybackAliveAndHidesHudInPip() {
        assertTrue(player.contains("SiloPictureInPictureCoordinator"))
        assertTrue(player.contains("updatePlaybackState("))
        val pipEffect = player
            .substringAfter("LaunchedEffect(\n        context,")
            .substringBefore(") {\n        pictureInPictureCoordinator.updatePlaybackState")
        assertTrue(pipEffect.contains("state.isPlaying"))
        assertTrue(player.contains("isPlaying = state.isPlaying && !state.isPaused"))
        assertTrue(player.contains("!isInPictureInPictureMode"))
        assertTrue(player.contains("if (!isInPictureInPictureMode && state.showControls"))
        assertTrue(player.contains("Lifecycle.Event.ON_PAUSE -> if (!isInPictureInPictureMode)"))
    }

    @Test
    fun tvSettingsHidePipToggle() {
        assertTrue(!settings.contains("Picture-in-Picture"))
    }
}
