package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastServerEndpoint
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

    private val manager = RemotePlaybackIdentityManager(
        api,
        tokens,
        deviceNameProvider = { "TV" },
        identityProbe = { url ->
            probed += url
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

        assertEquals(listOf(LAN_URL), probed)
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

    private companion object {
        const val IDENTITY = "deployment-1"
        const val PUBLIC_URL = "https://media.example.test"
        const val LAN_URL = "http://192.168.1.20:8090"
        const val TAILNET_URL = "https://silo.tailnet.test"
    }
}
