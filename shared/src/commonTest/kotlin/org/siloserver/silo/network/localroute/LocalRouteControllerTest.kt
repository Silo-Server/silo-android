package org.siloserver.silo.network.localroute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.HttpOrigin

class LocalRouteControllerTest {

    @Test
    fun activatesTheConfiguredLocalAddressOnceItProvesItself() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(LAN, state.active.value?.localOrigin)
        assertEquals(SAVED, state.active.value?.savedOrigin)
        assertEquals(PIN, state.pinFor(LAN.host, LAN.port))
        assertEquals("https://192.168.1.20:8080", environment.records[entry.id]?.lastLocalOrigin)
    }

    @Test
    fun noRouteWhenNoCandidateProvesItself() = runControllerTest { controller, state, environment ->
        environment.answering = emptySet()
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(listOf(LAN), environment.probes)
        assertNull(state.active.value)
    }

    @Test
    fun needsAnHttpsSavedAddressWithoutAPathAndASignedInAccount() = runControllerTest { controller, state, environment ->
        start(controller, entry.copy(url = "http://media.example.com"))
        advanceUntilIdle()
        controller.update(entry.copy(url = "https://media.example.com/silo"), signedIn = true)
        advanceUntilIdle()
        controller.update(entry, signedIn = false)
        advanceUntilIdle()

        assertEquals(emptyList(), environment.offerReads)
        assertEquals(emptyList(), environment.probes)
        assertNull(state.active.value)
    }

    @Test
    fun aCellularNetworkIsNeverProbed() = runControllerTest { controller, state, environment ->
        start(controller, entry, allowsLocal = false)
        advanceUntilIdle()

        assertEquals(emptyList(), environment.probes)
        assertNull(state.active.value)
    }

    @Test
    fun anyNetworkChangeDropsTheRouteUntilItChecksOutAgain() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()
        val first = state.active.value

        environment.answering = emptySet()
        controller.networkChanged(allowsLocal = true)
        runCurrent()
        assertNull(state.active.value)
        advanceUntilIdle()
        assertNull(state.active.value)

        environment.answering = setOf(LAN)
        controller.networkChanged(allowsLocal = true)
        advanceUntilIdle()
        assertTrue(state.active.value!!.generation > first!!.generation)
    }

    @Test
    fun aKnownKeyIsCheckedWithoutWaitingForTheSavedAddress() = runControllerTest { controller, state, environment ->
        environment.records[entry.id] = LocalRouteRecord(pin = PIN, serverId = IDENTITY, localUrl = "http://192.168.1.20:8080")
        environment.offerGate = CompletableDeferred()
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(listOf(entry.id), environment.offerReads)
        assertEquals(LAN, state.active.value?.localOrigin)
    }

    @Test
    fun aFailureDropsTheRouteAtOnceAndChecksOnceMoreLater() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()
        val route = state.active.value!!
        environment.answering = emptySet()
        environment.probes.clear()

        state.reportFailure(route)
        assertNull(state.active.value)
        advanceTimeBy(environment.retryDelayMs + 1)
        runCurrent()
        assertEquals(listOf(LAN), environment.probes)

        // No periodic checks: nothing more until the network or the app changes.
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(listOf(LAN), environment.probes)
        assertNull(state.active.value)
    }

    @Test
    fun aServerThatStopsOfferingTheRouteIsForgotten() = runControllerTest { controller, state, environment ->
        environment.records[entry.id] = LocalRouteRecord(pin = PIN, serverId = IDENTITY, localUrl = "http://192.168.1.20:8080")
        environment.offer = LocalRouteOffer.NotOffered(IDENTITY)
        start(controller, entry)
        advanceUntilIdle()

        assertNull(state.active.value)
        assertNull(environment.records[entry.id])
        assertNull(state.pinFor(LAN.host, LAN.port))
    }

    @Test
    fun aNewKeyDropsTheRouteAndTheOldTrust() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()
        assertEquals(LAN, state.active.value?.localOrigin)

        environment.offer = LocalRouteOffer.Offered(IDENTITY, "rotated=", "http://192.168.1.20:8080")
        environment.now += environment.offerRefreshIntervalMs + 1
        controller.foreground(true)
        advanceUntilIdle()

        assertNull(state.active.value)
        assertEquals("rotated=", environment.records[entry.id]?.pin)
        // The old key is no longer trusted there, and the new one only once an address presents it.
        assertNull(state.pinFor(LAN.host, LAN.port))
    }

    @Test
    fun anAddressPresentingAnotherKeyRereadsThePinAtOnce() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()
        // SECRET_KEY rotates while the device is away, soon after the last read.
        controller.networkChanged(allowsLocal = false)
        advanceUntilIdle()
        environment.serverPin = "rotated="
        environment.offer = LocalRouteOffer.Offered(IDENTITY, "rotated=", "http://192.168.1.20:8080")
        environment.offerReads.clear()

        controller.networkChanged(allowsLocal = true)
        advanceUntilIdle()

        assertEquals(listOf(entry.id), environment.offerReads)
        assertEquals(LAN, state.active.value?.localOrigin)
        assertEquals("rotated=", state.pinFor(LAN.host, LAN.port))
    }

    @Test
    fun aRouteThatKeepsFailingIsCheckedOnlyOnceUntilTheNetworkChanges() = runControllerTest { controller, state, environment ->
        start(controller, entry)
        advanceUntilIdle()

        // The address passes the check but every request on it fails.
        repeat(3) {
            state.active.value?.let(state::reportFailure)
            advanceTimeBy(environment.retryDelayMs + 1)
            runCurrent()
        }
        assertNull(state.active.value)
        assertEquals(2, environment.probes.size)

        controller.networkChanged(allowsLocal = true)
        advanceUntilIdle()
        assertEquals(LAN, state.active.value?.localOrigin)
    }

    @Test
    fun aStoredRouteOfAnotherDeploymentIsNotUsed() = runControllerTest { controller, state, environment ->
        environment.records[entry.id] = LocalRouteRecord(pin = PIN, serverId = "old-deployment", localUrl = "http://192.168.1.20:8080")
        environment.offerGate = CompletableDeferred()
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(emptyList(), environment.probes)
        assertNull(state.active.value)
    }

    @Test
    fun aHostNameIsTriedAtItsPrivateIPv4AddressesOnTheSamePort() = runControllerTest { controller, state, environment ->
        environment.offer = LocalRouteOffer.Offered(IDENTITY, PIN, "http://nas.home:8080")
        environment.resolved = mapOf("nas.home" to listOf("192.168.1.20"))
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(listOf("nas.home"), environment.resolutions)
        assertEquals(LAN, state.active.value?.localOrigin)
    }

    @Test
    fun aHostNameIsLookedUpOnlyOnceTheKnownAddressesFail() = runControllerTest { controller, state, environment ->
        environment.records[entry.id] = LocalRouteRecord(
            pin = PIN, serverId = IDENTITY, localUrl = "http://nas.home:8080", lastLocalOrigin = "https://192.168.1.20:8080",
        )
        environment.offer = LocalRouteOffer.Offered(IDENTITY, PIN, "http://nas.home:8080")
        environment.resolved = mapOf("nas.home" to listOf("192.168.1.21"))
        start(controller, entry)
        advanceUntilIdle()

        assertEquals(LAN, state.active.value?.localOrigin)
        assertEquals(emptyList(), environment.resolutions)
    }

    @Test
    fun aSavedAddressAnsweringAsAnotherDeploymentVouchesForNothing() = runControllerTest { controller, state, environment ->
        start(controller, entry.copy(verifiedServerId = "another-server"))
        advanceUntilIdle()

        assertEquals(emptyList(), environment.probes)
        assertNull(environment.records[entry.id])
        assertNull(state.active.value)
    }

    @Test
    fun nothingIsCheckedInTheBackground() = runControllerTest { controller, state, environment ->
        controller.networkChanged(allowsLocal = true)
        controller.update(entry, signedIn = true)
        advanceUntilIdle()

        assertEquals(emptyList(), environment.offerReads)
        assertNull(state.active.value)
    }

    /** The network is known first, then the app comes to the foreground signed in. */
    private fun TestScope.start(controller: LocalRouteController, entry: ServerEntry, allowsLocal: Boolean = true) {
        controller.networkChanged(allowsLocal)
        advanceUntilIdle()
        controller.update(entry, signedIn = true)
        controller.foreground(true)
    }

    /**
     * The controller runs on the test dispatcher in a scope of its own, so
     * `advanceUntilIdle` runs its work (it skips `backgroundScope`), and is
     * cancelled at the end.
     */
    private fun runControllerTest(
        body: suspend TestScope.(LocalRouteController, LocalRouteState, FakeEnvironment) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val state = LocalRouteState()
        val environment = FakeEnvironment()
        try {
            body(LocalRouteController(state, environment, scope), state, environment)
        } finally {
            scope.cancel()
        }
    }

    private class FakeEnvironment : LocalRouteController.Environment {
        val records = mutableMapOf<String, LocalRouteRecord>()
        var offer: LocalRouteOffer = LocalRouteOffer.Offered(IDENTITY, PIN, "http://192.168.1.20:8080")
        var offerGate: CompletableDeferred<Unit>? = null
        val offerReads = mutableListOf<String>()
        var answering = setOf(LAN)
        /** The key the server presents. */
        var serverPin = PIN
        val probes = mutableListOf<HttpOrigin>()
        var resolved = emptyMap<String, List<String>>()
        val resolutions = mutableListOf<String>()
        var now = 0L

        override val store = object : LocalRouteStore {
            override fun load(entryId: String) = records[entryId]
            override fun save(entryId: String, record: LocalRouteRecord?) {
                if (record == null) records.remove(entryId) else records[entryId] = record
            }
        }

        override suspend fun readOffer(server: LocalRouteController.Server): LocalRouteOffer {
            offerReads += server.entryId
            offerGate?.await()
            return offer
        }

        override suspend fun confirms(origin: HttpOrigin, pin: String, identity: String): LocalRouteCheck {
            probes += origin
            return when {
                origin !in answering -> LocalRouteCheck.UNCONFIRMED
                pin != serverPin -> LocalRouteCheck.OTHER_KEY
                identity == IDENTITY -> LocalRouteCheck.CONFIRMED
                else -> LocalRouteCheck.UNCONFIRMED
            }
        }

        override suspend fun privateIPv4Addresses(host: String): List<String> {
            resolutions += host
            return resolved[host].orEmpty()
        }

        override fun nowMs(): Long = now
    }

    private companion object {
        const val IDENTITY = "deployment-1"
        const val PIN = "pin="
        val SAVED = HttpOrigin("https", "media.example.com", 443)
        val LAN = HttpOrigin("https", "192.168.1.20", 8080)
        val entry = ServerEntry(id = "server", url = "https://media.example.com", verifiedServerId = IDENTITY)
    }
}
