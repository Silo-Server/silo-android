package org.siloserver.silo.android.cast

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SiloCastPhoneSourceTest {
    @Test
    fun phoneHasBrowserControllerRemoteAndPlayOnDeviceEntrypoint() {
        val controller = File("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastController.kt").takeIf { it.exists() }?.readText().orEmpty()
        val picker = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastTargetPickerSheet.kt").takeIf { it.exists() }?.readText().orEmpty()
        val remote = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastRemoteScreen.kt").takeIf { it.exists() }?.readText().orEmpty()
        val mini = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastMiniBar.kt").takeIf { it.exists() }?.readText().orEmpty()
        val home = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/home/HomeScreen.kt").readText()
        val main = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/MainScreen.kt").readText()
        val navigation = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/navigation/AppNavigation.kt").readText()
        val detail = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/detail/ItemDetailScreen.kt").readText()

        assertTrue(controller.contains("SiloCastController"))
        assertTrue(controller.contains("SiloCastNsdBrowser"))
        assertTrue(picker.contains("SiloCastTargetPickerSheet"))
        assertTrue(remote.contains("SiloCastRemoteScreen"))
        assertTrue(mini.contains("SiloCastMiniBar"))
        assertTrue(home.contains("Icons.Outlined.SettingsRemote"))
        assertTrue(home.contains("contentDescription = \"Remote Control\""))
        assertTrue(controller.contains("launchOnConnectedTarget"))
        assertTrue(main.contains("siloCastController.launchOnConnectedTarget"))
        assertTrue(navigation.contains("siloCastController.launchOnConnectedTarget"))
        assertTrue(detail.contains("Play on device"))
    }

    @Test
    fun phoneControllerHasAppleParitySessionLifecycle() {
        val controller = File("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastController.kt").readText()
        val starter = File("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastForegroundStarter.kt").readText()
        val store = File("src/androidMain/kotlin/org/siloserver/silo/android/cast/SiloCastLastTargetStore.kt").readText()

        // Heartbeat: phone pings every 3 s; only a pong resets the miss counter.
        assertTrue(controller.contains("heartbeatLoop"))
        assertTrue(controller.contains("MAX_MISSED_HEARTBEATS"))
        // Auto-reconnect with backoff after a dropped (non-deliberate) transport.
        assertTrue(controller.contains("beginReconnect"))
        assertTrue(controller.contains("MAX_RECONNECT_ATTEMPTS"))
        assertTrue(controller.contains("isReconnecting"))
        // Persisted last target + playing-gated silent auto-resume.
        assertTrue(controller.contains("attemptAutoResumeIfIdle"))
        assertTrue(controller.contains("lastTargetStore"))
        assertTrue(store.contains("SiloCastPersistedTarget"))
        assertTrue(starter.contains("ProcessLifecycleOwner"))
        // Reused-identity handoff: the TV skips the challenge and answers
        // handoff_ready directly when switching items mid-playback, so the
        // phone must race both instead of deadlocking on the challenge.
        assertTrue(controller.contains("handoffReady.onAwait"))
        assertTrue(controller.contains("handoffChallenge.onAwait"))
    }

    @Test
    fun phoneRemoteUiHasAppleParitySurfaces() {
        val remote = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastRemoteScreen.kt").readText()
        val picker = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastTargetPickerSheet.kt").readText()
        val artwork = File("src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/cast/SiloCastArtwork.kt").readText()
        val browser = File("../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/cast/SiloCastNsdBrowser.kt").readText()

        // Remote: scrubber, buffering spinner, connection states, overflow menu.
        assertTrue(remote.contains("RemoteScrubber"))
        assertTrue(remote.contains("Reconnecting…"))
        assertTrue(remote.contains("Connected to "))
        assertTrue(remote.contains("Choose a Different TV"))
        assertTrue(remote.contains("isBuffering"))
        // Artwork resolved from contentId, like Apple's SiloControlArtworkResolver.
        assertTrue(artwork.contains("rememberSiloCastArtwork"))
        // Picker: searching/empty states and TXT-driven row context.
        assertTrue(picker.contains("Searching for Silo TVs"))
        assertTrue(picker.contains("No Silo TVs Found"))
        assertTrue(picker.contains("Playing now"))
        // Browser surfaces the TXT metadata the picker/auto-resume rely on.
        assertTrue(browser.contains("serverId"))
        assertTrue(browser.contains("isPlaying"))
    }
}
