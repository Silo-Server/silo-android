package org.siloserver.silo.android.pip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class MobilePictureInPictureSourceTest {
    private val manifest = File("src/androidMain/AndroidManifest.xml").readText()
    private val activity = File("src/androidMain/kotlin/org/siloserver/silo/android/MainActivity.kt").readText()
    private val player = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt",
    ).readText()
    private val settings = File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/settings/PlaybackSettings.kt",
    ).readText()

    @Test
    fun mobileActivityDeclaresPictureInPictureWithoutLosingAndroid7Support() {
        assertTrue(manifest.contains("""android:supportsPictureInPicture="true""""))
        assertTrue(manifest.contains("smallestScreenSize"))
    }

    @Test
    fun mobileActivityOwnsSystemPipCallbacks() {
        assertTrue(activity.contains("SiloPictureInPictureCoordinator"))
        assertTrue(activity.contains("SiloPictureInPictureSurface.Mobile"))
        assertTrue(activity.contains("override fun onUserLeaveHint()"))
        assertTrue(activity.contains("override fun onPictureInPictureModeChanged"))
        assertTrue(activity.contains("enterPictureInPictureIfEligible"))
        assertTrue(activity.contains("setInPictureInPictureMode(isInPictureInPictureMode)"))
    }

    @Test
    fun mobilePlayerPublishesPlaybackStateAndHidesControlsInPip() {
        assertTrue(player.contains("SiloPictureInPictureCoordinator"))
        assertTrue(player.contains("pictureInPictureEnabledFlow"))
        assertTrue(player.contains("updatePlaybackState("))
        val pipEffect = player
            .substringAfter("LaunchedEffect(\n        activity,")
            .substringBefore(") {\n        pictureInPictureCoordinator.updatePlaybackState")
        assertTrue(pipEffect.contains("uiState.isPlaying"))
        assertTrue(player.contains("isPlaying = uiState.isPlaying && !uiState.isPaused"))
        assertTrue(player.contains("onGloballyPositioned"))
        assertTrue(player.contains("if (!isInPictureInPictureMode && !castState.isConnected)"))
    }

    @Test
    fun mobileSettingsExposePipToggle() {
        // Pin the binding, not just the label: the label is copy and moves
        // with the settings voice (it was "Picture-in-Picture" before the
        // sentence-case pass), while a switch row bound to the PiP preference
        // is what actually makes the toggle reachable.
        assertTrue(settings.contains("checked = pictureInPictureEnabled"))
        assertTrue(settings.contains("onCheckedChange = onPictureInPictureEnabledChanged"))
        assertTrue(settings.contains("Picture-in-picture"))
    }
}
