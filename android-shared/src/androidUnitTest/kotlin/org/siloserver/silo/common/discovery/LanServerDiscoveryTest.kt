package org.siloserver.silo.common.discovery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.api.ServerIdentityProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LanServerDiscoveryTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeBrowser : LanServiceBrowser {
        var events: LanServiceBrowser.Events? = null
        var starts = 0

        override fun start(events: LanServiceBrowser.Events) {
            starts++
            this.events = events
        }

        override fun stop() {
            events = null
        }

        override fun refresh() = Unit
    }

    /** Identity answers by URL; anything missing is unreachable. */
    private val identities = mutableMapOf<String, String>()
    private val names = mutableMapOf<String, String>()
    private val probed = mutableListOf<String>()
    private val browser = FakeBrowser()

    private fun discovery() = LanServerDiscovery(
        browser = browser,
        probeIdentity = { url ->
            probed += url
            identities[url]?.let { ServerIdentityProbe.Identity(it) } ?: ServerIdentityProbe.Unreachable
        },
        fetchName = { names[it] },
        dispatcher = dispatcher,
    )

    private fun ad(id: String?, vararg addresses: String, port: Int = 8090, name: String = "Silo") = LanAdvertisement(
        instanceName = name,
        addresses = addresses.toList(),
        port = port,
        attributes = buildMap {
            put("v", "1".encodeToByteArray())
            put("future", "x".encodeToByteArray())
            if (id != null) put("id", id.encodeToByteArray())
        },
    )

    private fun TestScope.resolve(key: String, advertisement: LanAdvertisement) {
        browser.events!!.resolved(key, advertisement)
        runCurrent()
    }

    @Test
    fun listsAnAdvertisementOnlyWhenItsAddressConfirmsTheAdvertisedId() = runTest(dispatcher) {
        identities["http://192.168.1.5:8090"] = "srv-a"
        identities["http://192.168.1.6:8090"] = "srv-b"
        names["http://192.168.1.5:8090"] = "Den"
        val discovery = discovery()
        discovery.start()

        resolve("a", ad("srv-a", "192.168.1.5"))
        // Advertises one deployment's id, but its address answers as another.
        resolve("spoof", ad("srv-a", "192.168.1.6", name = "Den"))
        advanceTimeBy(LanServerDiscovery.RETRY_DELAY_MS + 1)

        assertEquals(
            listOf(DiscoveredServer(serverId = "srv-a", name = "Den", url = "http://192.168.1.5:8090")),
            discovery.servers.value,
        )
        assertEquals("On this network · 192.168.1.5:8090", discovery.servers.value.single().detail)
        discovery.stop()
    }

    @Test
    fun advertisementWithoutAnIdIsNotProbed() = runTest(dispatcher) {
        val discovery = discovery()
        discovery.start()

        resolve("a", ad(null, "192.168.1.5"))

        assertEquals(emptyList(), probed)
        assertEquals(emptyList(), discovery.servers.value)
        discovery.stop()
    }

    @Test
    fun resultsWithOneIdShareARowSortedByName() = runTest(dispatcher) {
        identities["http://192.168.1.5:8090"] = "srv-a"
        identities["http://10.0.0.5:8090"] = "srv-a"
        identities["http://192.168.1.9:8090"] = "srv-b"
        names["http://192.168.1.5:8090"] = "Zoo"
        names["http://10.0.0.5:8090"] = "Zoo"
        names["http://192.168.1.9:8090"] = "attic"
        val discovery = discovery()
        discovery.start()

        resolve("a-wifi", ad("srv-a", "192.168.1.5"))
        resolve("a-eth", ad("srv-a", "10.0.0.5"))
        resolve("b", ad("srv-b", "192.168.1.9"))

        assertEquals(
            listOf("attic" to "http://192.168.1.9:8090", "Zoo" to "http://10.0.0.5:8090"),
            discovery.servers.value.map { it.name to it.url },
        )
        discovery.stop()
    }

    @Test
    fun anUnreachableFirstAddressFallsBackToTheNext() = runTest(dispatcher) {
        identities["http://[2001:db8::5]:8090"] = "srv-a"
        val discovery = discovery()
        discovery.start()

        resolve("a", ad("srv-a", "192.168.1.5", "fe80::1%wlan0", "2001:db8::5%wlan0", name = "Media"))

        assertEquals(listOf("http://192.168.1.5:8090", "http://[2001:db8::5]:8090"), probed)
        assertEquals(listOf("Media" to "http://[2001:db8::5]:8090"), discovery.servers.value.map { it.name to it.url })
        discovery.stop()
    }

    @Test
    fun unreachableResultIsRetriedOnceThenAgainOnRefresh() = runTest(dispatcher) {
        val discovery = discovery()
        discovery.start()

        resolve("a", ad("srv-a", "192.168.1.5"))
        advanceTimeBy(LanServerDiscovery.RETRY_DELAY_MS + 1)
        assertEquals(2, probed.size)
        assertEquals(emptyList(), discovery.servers.value)

        // The server finishes starting; the next refresh finds it.
        identities["http://192.168.1.5:8090"] = "srv-a"
        advanceTimeBy(LanServerDiscovery.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("http://192.168.1.5:8090"), discovery.servers.value.map { it.url })
        discovery.stop()
    }

    @Test
    fun listedRowSurvivesAMissedRecheckAndGoesAfterThreeOrWhenLost() = runTest(dispatcher) {
        identities["http://192.168.1.5:8090"] = "srv-a"
        val discovery = discovery()
        discovery.start()
        resolve("a", ad("srv-a", "192.168.1.5"))
        assertEquals(1, discovery.servers.value.size)

        identities.clear()
        repeat(LanServerDiscovery.MAX_MISSES - 1) {
            advanceTimeBy(LanServerDiscovery.REFRESH_INTERVAL_MS)
            runCurrent()
            assertEquals(1, discovery.servers.value.size, "kept after ${it + 1} missed re-check(s)")
        }
        advanceTimeBy(LanServerDiscovery.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(emptyList(), discovery.servers.value)

        identities["http://192.168.1.5:8090"] = "srv-a"
        resolve("b", ad("srv-a", "192.168.1.5"))
        assertEquals(1, discovery.servers.value.size)
        browser.events!!.gone("b")
        runCurrent()
        assertEquals(emptyList(), discovery.servers.value)
        discovery.stop()
    }

    @Test
    fun aConfirmationStillRunningWhenTheResultIsLostDoesNotRestoreIt() = runTest(dispatcher) {
        val answer = CompletableDeferred<ServerIdentityProbe>()
        val discovery = LanServerDiscovery(
            browser = browser,
            probeIdentity = { answer.await() },
            fetchName = { null },
            dispatcher = dispatcher,
        )
        discovery.start()
        resolve("a", ad("srv-a", "192.168.1.5"))
        browser.events!!.gone("a")
        runCurrent()

        answer.complete(ServerIdentityProbe.Identity("srv-a"))
        runCurrent()

        assertEquals(emptyList(), discovery.servers.value)
        discovery.stop()
    }

    @Test
    fun stopClearsTheListAndDropsLateEvents() = runTest(dispatcher) {
        identities["http://192.168.1.5:8090"] = "srv-a"
        val discovery = discovery()
        discovery.start()
        val firstSession = browser.events!!
        resolve("a", ad("srv-a", "192.168.1.5"))
        assertEquals(1, discovery.servers.value.size)

        discovery.stop()
        assertEquals(emptyList(), discovery.servers.value)

        discovery.start()
        firstSession.resolved("a", ad("srv-a", "192.168.1.5"))
        runCurrent()
        assertEquals(emptyList(), discovery.servers.value)
        assertEquals(2, browser.starts)
        discovery.stop()
    }

    @Test
    fun failedBrowseRetriesOnceQuicklyThenOnRefresh() = runTest(dispatcher) {
        val discovery = discovery()
        discovery.start()
        browser.events!!.failed()
        runCurrent()
        assertEquals(emptyList(), discovery.servers.value)

        advanceTimeBy(LanServerDiscovery.BROWSE_RETRY_DELAY_MS + 1)
        assertEquals(2, browser.starts)

        browser.events!!.failed()
        runCurrent()
        advanceTimeBy(LanServerDiscovery.BROWSE_RETRY_DELAY_MS + 1)
        assertEquals(2, browser.starts, "only one quick retry per visit")
        advanceTimeBy(LanServerDiscovery.REFRESH_INTERVAL_MS)
        runCurrent()
        assertEquals(3, browser.starts)
        discovery.stop()
    }

    @Test
    fun aMovedAddressIsNotListedUnderTheOldOneWhileItIsChecked() = runTest(dispatcher) {
        val answer = CompletableDeferred<ServerIdentityProbe>()
        val discovery = LanServerDiscovery(
            browser = browser,
            probeIdentity = { url ->
                if (url == "http://192.168.1.5:8090") ServerIdentityProbe.Identity("srv-a") else answer.await()
            },
            fetchName = { null },
            dispatcher = dispatcher,
        )
        discovery.start()
        resolve("a", ad("srv-a", "192.168.1.5"))
        assertEquals(listOf("http://192.168.1.5:8090"), discovery.servers.value.map { it.url })

        // A new lease: the advertisement now names .9 only.
        resolve("a", ad("srv-a", "192.168.1.9"))
        assertEquals(emptyList(), discovery.servers.value)

        answer.complete(ServerIdentityProbe.Identity("srv-a"))
        runCurrent()
        assertEquals(listOf("http://192.168.1.9:8090"), discovery.servers.value.map { it.url })
        discovery.stop()
    }

    @Test
    fun aResolveWithNoUsableAddressKeepsTheListedRow() = runTest(dispatcher) {
        identities["http://192.168.1.5:8090"] = "srv-a"
        val discovery = discovery()
        discovery.start()
        resolve("a", ad("srv-a", "192.168.1.5"))

        resolve("a", ad("srv-a", "fe80::1%wlan0"))

        assertEquals(listOf("http://192.168.1.5:8090"), discovery.servers.value.map { it.url })
        discovery.stop()
    }

    @Test
    fun advertisedIdIgnoresOtherKeysAndBlankValues() {
        assertEquals(
            "srv",
            advertisedServerId(mapOf("v" to "9".encodeToByteArray(), "x" to null, "ID" to " srv ".encodeToByteArray())),
        )
        assertNull(advertisedServerId(mapOf("id" to " ".encodeToByteArray())))
        assertNull(advertisedServerId(mapOf("id" to null)))
        assertNull(advertisedServerId(mapOf("v" to "1".encodeToByteArray())))
    }

    @Test
    fun originsPreferIpv4AndBracketRoutableIpv6() {
        assertEquals(
            listOf("http://192.168.1.5:8091", "http://[fd00::5]:8091"),
            lanOrigins(listOf("fd00::5%eth0", "fe80::abcd%wlan0", "/192.168.1.5", "192.168.1.5"), 8091),
        )
        assertTrue(lanOrigins(listOf("fe80::1"), 8090).isEmpty())
        assertTrue(lanOrigins(listOf("192.168.1.5"), 0).isEmpty())
        assertTrue(lanOrigins(listOf("silo-abc.local"), 8090).isEmpty())
        assertTrue(lanOrigins(listOf("127.0.0.1", "0.0.0.0", "224.0.0.251", "::1", "::", "ff02::fb"), 8090).isEmpty())
    }
}
