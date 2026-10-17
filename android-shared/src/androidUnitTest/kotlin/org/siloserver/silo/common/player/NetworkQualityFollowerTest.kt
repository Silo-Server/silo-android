package org.siloserver.silo.common.player

import org.siloserver.silo.common.settings.PlayerSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkQualityFollowerTest {
    private val main = PlaybackQualityPair("2160p", 40_000)
    private val cellular = PlaybackQualityPair("720p", 2_000)
    private val store = qualityStore(main = main, cellular = cellular)

    @Test
    fun `mobile data and metered networks use the cellular pair, a VPN only over mobile data`() {
        assertTrue(snapshot("cellular", metered = true).usesCellularQuality, "cellular")
        // A carrier that marks its plan unmetered is still mobile data.
        assertTrue(snapshot("cellular", metered = false).usesCellularQuality, "unmetered cellular")
        // A phone hotspot, or Wi-Fi the user marked metered.
        assertTrue(snapshot("wifi", metered = true).usesCellularQuality, "metered Wi-Fi")
        assertFalse(snapshot("wifi", metered = false).usesCellularQuality, "unmetered Wi-Fi")
        assertFalse(snapshot("ethernet", metered = false).usesCellularQuality, "ethernet")
        // Android marks most VPNs metered; Tailscale on home Wi-Fi is not mobile data.
        assertFalse(snapshot("wifi", metered = true, vpn = true).usesCellularQuality, "VPN over Wi-Fi")
        assertTrue(snapshot("cellular", metered = true, vpn = true).usesCellularQuality, "VPN over cellular")
        assertFalse(snapshot("cellular", metered = true).copy(connected = false).usesCellularQuality, "offline")
    }

    @Test
    fun `an Auto picked in the player takes the saved cap for the connection`() = runTest {
        assertEquals(PlaybackQualityPair("auto", 40_000), store.inPlayerQualityPair("auto", cellular = false))
        assertEquals(PlaybackQualityPair("auto", 2_000), store.inPlayerQualityPair("Auto", cellular = true))
        // No limit sends no cap.
        val uncapped = qualityStore(main = PlaybackQualityPair("1080p", null), cellular = cellular)
        assertEquals(PlaybackQualityPair("auto", null), uncapped.inPlayerQualityPair("auto", cellular = false))
        // Any other pick keeps the session's cap.
        assertEquals(null, store.inPlayerQualityPair("1080p-medium", cellular = true))
        assertEquals(null, store.inPlayerQualityPair(null, cellular = true))
    }

    @Test
    fun `the start pair follows the network`() = runTest {
        assertEquals(cellular, store.qualityPair(cellular = snapshot("wifi", metered = true).usesCellularQuality))
        assertEquals(main, store.qualityPair(cellular = snapshot("wifi", metered = false).usesCellularQuality))
    }

    @Test
    fun `a network change that holds moves the session to the other pair`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        var playing = FollowedSession(main)
        val replans = follow(network, { playing }) { playing = FollowedSession(it) }

        settle()
        assertEquals(emptyList(), replans, "already on the Wi-Fi pair")

        network.value = snapshot("cellular", metered = true)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(emptyList(), replans, "not settled yet")
        settle()
        assertEquals(listOf(cellular), replans)

        network.value = snapshot("wifi")
        settle()
        assertEquals(listOf(cellular, main), replans)
    }

    @Test
    fun `a brief drop to another network does not replan`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        val replans = follow(network, { FollowedSession(main) })

        network.value = snapshot("cellular", metered = true)
        advanceTimeBy(2_000)
        network.value = snapshot("wifi")
        settle()

        assertEquals(emptyList(), replans)
    }

    @Test
    fun `moments without a connection are not read as unmetered`() = runTest {
        val network = MutableStateFlow(snapshot("cellular", metered = true))
        val replans = follow(network, { FollowedSession(cellular) })

        network.value = PlaybackNetworkSnapshot()
        settle()
        network.value = snapshot("cellular", metered = true)
        settle()

        assertEquals(emptyList(), replans)
    }

    @Test
    fun `Auto picked in the player keeps following, with only the cap moving`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        // The saved resolution is 4K; the viewer picked Auto over it.
        var playing = FollowedSession(PlaybackQualityPair("auto", 40_000), autoPicked = true)
        val replans = follow(network, { playing }) { playing = playing.copy(pair = it) }

        settle()
        assertEquals(emptyList(), replans)

        network.value = snapshot("cellular", metered = true)
        settle()
        assertEquals(listOf(PlaybackQualityPair("auto", 2_000)), replans)

        network.value = snapshot("wifi")
        settle()
        assertEquals(listOf(PlaybackQualityPair("auto", 2_000), PlaybackQualityPair("auto", 40_000)), replans)
    }

    @Test
    fun `a fixed rung the viewer picked in the player is not overridden`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        // The player reports no followed session once the viewer picks a rung.
        val replans = follow(network, { null })

        network.value = snapshot("cellular", metered = true)
        settle()

        assertEquals(emptyList(), replans)
    }

    private fun TestScope.follow(
        network: Flow<PlaybackNetworkSnapshot>,
        playing: () -> FollowedSession?,
        onReplan: (PlaybackQualityPair) -> Unit = {},
    ): List<PlaybackQualityPair> {
        val replans = mutableListOf<PlaybackQualityPair>()
        // Collects forever, so it runs where the test does not wait for it.
        backgroundScope.launch {
            NetworkQualityFollower(network, store).follow(playing) { pair ->
                replans += pair
                onReplan(pair)
            }
        }
        return replans
    }

    private fun TestScope.settle() {
        advanceTimeBy(NetworkQualityFollower.DEFAULT_SETTLE_MS + 1_000)
        runCurrent()
    }

    private fun snapshot(transport: String, metered: Boolean = false, vpn: Boolean = false) =
        PlaybackNetworkSnapshot(
            connected = true,
            validated = true,
            metered = metered,
            transport = transport,
            cellular = transport == "cellular",
            vpn = vpn,
        )

    /** Only the four quality flows answer; anything else fails the test. */
    private fun qualityStore(main: PlaybackQualityPair, cellular: PlaybackQualityPair): PlayerSettingsStore {
        val unused = Proxy.newProxyInstance(
            PlayerSettingsStore::class.java.classLoader,
            arrayOf(PlayerSettingsStore::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as PlayerSettingsStore
        return object : PlayerSettingsStore by unused {
            override val preferredQualityFlow = MutableStateFlow(main.qualityPreference)
            override val maxBitrateKbpsFlow = MutableStateFlow(main.bandwidthCapKbps)
            override val cellularPreferredQualityFlow = MutableStateFlow(cellular.qualityPreference)
            override val cellularMaxBitrateKbpsFlow = MutableStateFlow(cellular.bandwidthCapKbps)
        }
    }
}
