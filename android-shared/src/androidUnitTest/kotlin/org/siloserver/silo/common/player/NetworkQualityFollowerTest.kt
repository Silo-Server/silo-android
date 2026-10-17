package org.siloserver.silo.common.player

import org.siloserver.silo.common.settings.PlayerSettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
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
        var playing = FollowedSession("s1", main)
        val replans = follow(network, { playing }) { _, pair -> playing = FollowedSession("s1", pair) }

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
        val replans = follow(network, { FollowedSession("s1", main) })

        network.value = snapshot("cellular", metered = true)
        advanceTimeBy(2_000)
        network.value = snapshot("wifi")
        settle()

        assertEquals(emptyList(), replans)
    }

    @Test
    fun `moments without a connection are not read as unmetered`() = runTest {
        val network = MutableStateFlow(snapshot("cellular", metered = true))
        val replans = follow(network, { FollowedSession("s1", cellular) })

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
        var playing = FollowedSession("s1", PlaybackQualityPair("auto", 40_000), autoPicked = true)
        val replans = follow(network, { playing }) { _, pair -> playing = playing.copy(pair = pair) }

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

    @Test
    fun `a move that does not take is tried again, reading the session afresh, until it takes`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        var playing = FollowedSession("s1", main)
        val attempts = mutableListOf<Pair<FollowedSession, Long>>()
        backgroundScope.launch {
            NetworkQualityFollower(network, store).follow({ playing }) { session, pair ->
                attempts += session to currentTime
                // The first request fails; then a recovery replaces the
                // session, still on the Wi-Fi pair; the second try takes.
                playing = if (attempts.size == 1) FollowedSession("s2", main) else FollowedSession("s2", pair)
            }
        }
        network.value = snapshot("cellular", metered = true)
        settle()
        val first = attempts.single().second

        advanceTimeBy(NetworkQualityFollower.RETRY_DELAYS_MS[1])
        runCurrent()
        // The retry moves the session playing now, not the one the first try read.
        assertEquals(listOf("s1", "s2"), attempts.map { it.first.sessionId })
        assertEquals(first + NetworkQualityFollower.RETRY_DELAYS_MS[1], attempts.last().second)

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, attempts.size, "nothing left to move once it took")
    }

    @Test
    fun `a settings read that fails is tried again, and following goes on`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        var failingReads = 1
        val flaky = qualityStore(main = main, cellular = cellular) { failingReads-- > 0 }
        var playing = FollowedSession("s1", main)
        val replans = mutableListOf<PlaybackQualityPair>()
        backgroundScope.launch {
            NetworkQualityFollower(network, flaky).follow({ playing }) { _, pair ->
                replans += pair
                playing = FollowedSession("s1", pair)
            }
        }

        network.value = snapshot("cellular", metered = true)
        settle()
        assertEquals(emptyList(), replans, "the read failed")
        advanceTimeBy(NetworkQualityFollower.RETRY_DELAYS_MS[1])
        runCurrent()
        assertEquals(listOf(cellular), replans)

        network.value = snapshot("wifi")
        settle()
        assertEquals(listOf(cellular, main), replans)
    }

    @Test
    fun `retries are bounded`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        // Every request fails: the session stays on the Wi-Fi pair.
        val replans = follow(network, { FollowedSession("s1", main) })

        network.value = snapshot("cellular", metered = true)
        settle()
        advanceTimeBy(10 * 60_000)
        runCurrent()

        assertEquals(List(NetworkQualityFollower.RETRY_DELAYS_MS.size) { cellular }, replans)
    }

    @Test
    fun `a newer network supersedes a pending retry`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        val replans = follow(network, { FollowedSession("s1", main) })

        network.value = snapshot("cellular", metered = true)
        settle()
        assertEquals(listOf(cellular), replans)

        // Back on Wi-Fi before the retry: the session already plays its pair.
        network.value = snapshot("wifi")
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(listOf(cellular), replans)
    }

    @Test
    fun `a retry stops once the viewer picks a fixed rung`() = runTest {
        val network = MutableStateFlow(snapshot("wifi"))
        var playing: FollowedSession? = FollowedSession("s1", main)
        val replans = follow(network, { playing }) { _, _ -> playing = null }

        network.value = snapshot("cellular", metered = true)
        settle()
        advanceTimeBy(10 * 60_000)
        runCurrent()

        assertEquals(listOf(cellular), replans)
    }

    private fun TestScope.follow(
        network: Flow<PlaybackNetworkSnapshot>,
        playing: () -> FollowedSession?,
        onReplan: (FollowedSession, PlaybackQualityPair) -> Unit = { _, _ -> },
    ): List<PlaybackQualityPair> {
        val replans = mutableListOf<PlaybackQualityPair>()
        // Collects forever, so it runs where the test does not wait for it.
        backgroundScope.launch {
            NetworkQualityFollower(network, store).follow(playing) { session, pair ->
                replans += pair
                onReplan(session, pair)
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

    /**
     * Only the four quality flows answer; anything else fails the test. A
     * read throws while [readFails] says so, as DataStore does on a disk error.
     */
    private fun qualityStore(
        main: PlaybackQualityPair,
        cellular: PlaybackQualityPair,
        readFails: () -> Boolean = { false },
    ): PlayerSettingsStore {
        val unused = Proxy.newProxyInstance(
            PlayerSettingsStore::class.java.classLoader,
            arrayOf(PlayerSettingsStore::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as PlayerSettingsStore
        fun <T> read(value: T): Flow<T> = flow {
            if (readFails()) throw IOException("settings read failed")
            emit(value)
        }
        return object : PlayerSettingsStore by unused {
            override val preferredQualityFlow = read(main.qualityPreference)
            override val maxBitrateKbpsFlow = read(main.bandwidthCapKbps)
            override val cellularPreferredQualityFlow = read(cellular.qualityPreference)
            override val cellularMaxBitrateKbpsFlow = read(cellular.bandwidthCapKbps)
        }
    }
}
