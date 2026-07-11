package org.siloserver.silo.tv.cast

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvSiloCastReceiverSourceTest {
    @Test
    fun receiverAdvertisesSilocastAndAllowsNewestControllerToWin() {
        val receiver = File("src/androidMain/kotlin/org/siloserver/silo/tv/cast/TvSiloCastReceiver.kt").takeIf { it.exists() }?.readText().orEmpty()
        val identity = File("src/androidMain/kotlin/org/siloserver/silo/tv/cast/RemotePlaybackIdentityManager.kt").readText()
        val module = File("src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt").readText()
        val navigation = File("src/androidMain/kotlin/org/siloserver/silo/tv/ui/navigation/TvAppNavigation.kt").readText()

        assertTrue(receiver.contains("_silocast._tcp") || receiver.contains("SiloCastProtocol.serviceType"))
        assertTrue(receiver.contains("activeSession"))
        assertTrue(receiver.contains("closePreviousController"))
        assertTrue(receiver.contains("SiloCastMessage.HandoffOffer"))
        assertTrue(receiver.contains("launchRequests"))
        assertTrue(receiver.contains("withContext(Dispatchers.Main.immediate)"))
        assertTrue(identity.contains("beginTemporaryScope"))
        assertTrue(navigation.contains("siloCastReceiver.launchRequests.collect"))
        assertTrue(navigation.contains("TvRoute.Player"))
        assertTrue(module.contains("TvSiloCastReceiver"))
    }

    @Test
    fun playerAdapterMapsCoreControls() {
        val adapter = File("src/androidMain/kotlin/org/siloserver/silo/tv/cast/TvSiloCastPlayerAdapter.kt").takeIf { it.exists() }?.readText().orEmpty()
        listOf("playPause", "seek", "selectSubtitle", "selectAudio", "setPlaybackSpeed", "playNext").forEach {
            assertTrue(adapter.contains(it), "Adapter must map $it.")
        }
    }
}
