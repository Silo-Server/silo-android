package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastServerEndpoint
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.ServerIdentityProbe

/**
 * A TV signed in over the LAN keeps using the LAN when a phone on the
 * server's public address hands it a profile: the handoff runs at the first
 * address that reports the phone's deployment identity from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemotePlaybackServerAddressTest {

    private val api = FakeRemotePlaybackApi()
    private val tokens = TokenManagerImpl()
    private val answers = mutableMapOf<String, ServerIdentityProbe>()
    private val probed = mutableListOf<String>()
    private var savedUrls = listOf<String>()
    private val approvedCleartext = mutableSetOf<String>()
    /** Addresses whose probe hangs for the production timeout before failing. */
    private val stalled = mutableSetOf<String>()

    private val manager = RemotePlaybackIdentityManager(
        api,
        tokens,
        deviceNameProvider = { "TV" },
        identityProbe = { url ->
            probed += url
            if (url in stalled) delay(PROBE_TIMEOUT_MS)
            answers[url] ?: ServerIdentityProbe.Unreachable
        },
        savedUrlsFor = { identity -> if (identity == IDENTITY) savedUrls else emptyList() },
        cleartextApproved = { url -> url in approvedCleartext },
    )

    @Test fun theTvsOwnLanAddressIsUsedForThePhonesServer() = runTest {
        savedUrls = listOf(LAN_URL)
        approvedCleartext += LAN_URL
        answers[LAN_URL] = ServerIdentityProbe.Identity(IDENTITY)
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer())

        assertEquals(LAN_URL, api.serverUrls.single())
        assertEquals(LAN_URL, manager.activeIdentity?.serverUrl)
        assertEquals(LAN_URL, tokens.getServerUrl())
        // The handoff still names the phone's server.
        assertEquals(offer().serverId, manager.activeIdentity?.serverId)
    }

    @Test fun thePhonesAddressIsUsedWhenTheTvsOwnIsUnreachable() = runTest {
        savedUrls = listOf(LAN_URL)
        approvedCleartext += LAN_URL
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer())

        assertEquals(PUBLIC_URL, api.serverUrls.single())
    }

    @Test fun aSavedAddressNowServingAnotherServerIsSkipped() = runTest {
        savedUrls = listOf(LAN_URL)
        approvedCleartext += LAN_URL
        answers[LAN_URL] = ServerIdentityProbe.Identity("other-deployment")
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer())

        assertEquals(PUBLIC_URL, api.serverUrls.single())
    }

    @Test fun anotherServerAtThePhonesAddressEndsTheHandoff() = runTest {
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity("other-deployment")
        answers[TAILNET_URL] = ServerIdentityProbe.Identity(IDENTITY)

        assertFails { prepare(offer(endpoints = listOf(TAILNET_URL))) }

        assertTrue(api.serverUrls.isEmpty())
        assertNull(manager.activeIdentity)
    }

    @Test fun anotherServerAtThePhonesAddressEndsTheHandoffEvenWhenTheTvSavedIt() = runTest {
        savedUrls = listOf(PUBLIC_URL)
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity("other-deployment")
        answers[TAILNET_URL] = ServerIdentityProbe.Identity(IDENTITY)

        assertFails { prepare(offer(endpoints = listOf(TAILNET_URL))) }

        assertTrue(api.serverUrls.isEmpty())
    }

    @Test fun theEndpointCapCountsOnlyAddressesTheTvMayProbe() = runTest {
        val unapproved = (1..RemotePlaybackCandidatePolicy.MAX_ENDPOINTS).map { "http://10.0.0.$it:8090" }
        answers[TAILNET_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer(endpoints = listOf(PUBLIC_URL) + unapproved + TAILNET_URL))

        assertEquals(TAILNET_URL, api.serverUrls.single())
    }

    @Test fun anOfferedAddressIsTriedWhenNeitherTheTvsNorThePhonesAnswers() = runTest {
        answers[TAILNET_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer(endpoints = listOf(TAILNET_URL)))

        assertEquals(TAILNET_URL, api.serverUrls.single())
    }

    @Test fun anUnapprovedPlainHttpAddressIsNeverTried() = runTest {
        answers[LAN_URL] = ServerIdentityProbe.Identity(IDENTITY)

        assertFails { prepare(offer(endpoints = listOf(LAN_URL))) }

        assertFalse(LAN_URL in probed)
        assertTrue(api.serverUrls.isEmpty())
    }

    @Test fun anOfferWithoutAnIdentityUsesThePhonesAddressAsBefore() = runTest {
        savedUrls = listOf(LAN_URL)

        prepare(offer(identity = null))

        assertTrue(probed.isEmpty())
        assertEquals(PUBLIC_URL, api.serverUrls.single())
    }

    @Test fun staleSavedAddressesCostOneProbeTimeoutInTotal() = runTest {
        // Android phones give up on the challenge after 15 s.
        savedUrls = listOf("https://old-1.example.test", "https://old-2.example.test")
        stalled += savedUrls
        answers[PUBLIC_URL] = ServerIdentityProbe.Identity(IDENTITY)

        prepare(offer())

        assertEquals(PUBLIC_URL, api.serverUrls.single())
        assertTrue(currentTime <= PROBE_TIMEOUT_MS, "took ${currentTime} ms")
    }

    @Test fun anOfferProbesABoundedNumberOfAddresses() = runTest {
        val endpoints = (1..20).map { "https://e$it.example.test" }

        assertFails { prepare(offer(endpoints = endpoints)) }

        assertEquals(1 + RemotePlaybackCandidatePolicy.MAX_ENDPOINTS, probed.size)
    }

    @Test fun savedAddressesAreRecordedMatchesThenUnverifiedServersButNeverAnotherDeployment() {
        val entries = listOf(
            entry("https://elsewhere.test", verified = "other-deployment"),
            entry("https://unverified.test", verified = null),
            entry("https://recorded.test", verified = IDENTITY),
            entry(LAN_URL, verified = null),
        )

        // An unverified active server comes first among the unverified.
        assertEquals(
            listOf("https://recorded.test", LAN_URL, "https://unverified.test"),
            RemotePlaybackCandidatePolicy.savedUrls(entries, activeId = entries[3].id, identity = IDENTITY),
        )
        // Another deployment being active doesn't hide unverified saved servers.
        assertEquals(
            listOf("https://recorded.test", "https://unverified.test", LAN_URL),
            RemotePlaybackCandidatePolicy.savedUrls(entries, activeId = entries[0].id, identity = IDENTITY),
        )
    }

    @Test fun candidatesPutTheTvsAddressFirstAndDropRepeats() {
        assertEquals(
            listOf(LAN_URL, PUBLIC_URL, TAILNET_URL),
            RemotePlaybackCandidatePolicy.candidateUrls(
                savedUrls = listOf("$LAN_URL/"),
                offeredUrl = PUBLIC_URL,
                endpointUrls = listOf(PUBLIC_URL, TAILNET_URL, " $LAN_URL "),
            ),
        )
    }

    private suspend fun prepare(offer: SiloCastHandoffOffer) = manager.prepare(
        offer,
        "phone-1",
        controllerDeviceName = null,
        receiverRun = 1L,
        allowReuse = true,
    ) {}

    private fun offer(identity: String? = IDENTITY, endpoints: List<String> = emptyList()) = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(PUBLIC_URL),
        serverURL = PUBLIC_URL,
        profileId = "profile-1",
        serverIdentity = identity,
        serverEndpoints = endpoints.map { SiloCastServerEndpoint(url = it, kind = "public") }.ifEmpty { null },
    )

    private fun entry(url: String, verified: String?) =
        ServerEntry(id = AndroidServerRegistry.idFor(url), url = url, verifiedServerId = verified)

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val IDENTITY = "deployment-1"
        const val PUBLIC_URL = "https://media.example.test"
        const val LAN_URL = "http://192.168.1.20:8090"
        const val TAILNET_URL = "https://silo.tailnet.test"
    }
}
