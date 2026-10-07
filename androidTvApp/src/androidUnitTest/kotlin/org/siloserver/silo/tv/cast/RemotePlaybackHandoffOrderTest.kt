package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.TokenManagerImpl

/**
 * Whatever the TV is playing has to close its server session under the
 * identity it started with. The handoff therefore stops it only once the
 * phone's profile is approved, and before that profile replaces the TV's
 * credentials. An offer that is denied or fails leaves playback alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemotePlaybackHandoffOrderTest {

    private val api = FakeRemotePlaybackApi()
    private val tokens = TokenManagerImpl()
    private val manager = RemotePlaybackIdentityManager(api, tokens, deviceNameProvider = { "TV" })

    @Test fun theTvsOwnPlaybackStopsBeforeTheFirstPhoneProfileIsInstalled() = runTest {
        var hadTemporaryScopeAtStop: Boolean? = null

        prepare(offer()) { hadTemporaryScopeAtStop = tokens.hasTemporaryScope() }

        assertEquals(false, hadTemporaryScopeAtStop)
        assertTrue(tokens.hasTemporaryScope())
    }

    @Test fun anEarlierPhoneProfileOutlivesItsPlayersStop() = runTest {
        prepare(offer())
        val earlier = assertNotNull(manager.activeIdentity)
        var identityAtStop: String? = null
        var endCallsAtStop = -1

        api.approvedProfileId = "profile-2"
        prepare(offer(profileId = "profile-2")) {
            identityAtStop = manager.activeIdentity?.generationId
            endCallsAtStop = api.endCalls
        }

        assertEquals(earlier.generationId, identityAtStop)
        assertEquals(0, endCallsAtStop)
        assertEquals(1, api.endCalls)
        assertNotEquals(earlier.generationId, manager.activeIdentity?.generationId)
    }

    @Test fun aDeniedHandoffNeitherStopsPlaybackNorEndsTheCurrentProfile() = runTest {
        prepare(offer())
        val current = assertNotNull(manager.activeIdentity)
        var stopped = false

        api.pollStatus = "denied"
        assertFails { prepare(offer(profileId = "profile-2")) { stopped = true } }

        assertFalse(stopped)
        assertEquals(current, manager.activeIdentity)
        assertTrue(tokens.hasTemporaryScope())
        assertEquals(0, api.endCalls)
    }

    @Test fun aStopThatFailsKeepsTheCurrentProfile() = runTest {
        prepare(offer())
        val current = assertNotNull(manager.activeIdentity)

        api.approvedProfileId = "profile-2"
        assertFails { prepare(offer(profileId = "profile-2")) { error("still playing") } }

        assertEquals(current, manager.activeIdentity)
        assertEquals(0, api.endCalls)
    }

    private suspend fun prepare(
        offer: SiloCastHandoffOffer,
        beforeActivation: suspend () -> Unit = {},
    ) = manager.prepare(
        offer,
        CONTROLLER,
        controllerDeviceName = null,
        receiverRun = 1L,
        beforeActivation = beforeActivation,
    ) {}

    private fun offer(profileId: String = "profile-1") = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(SERVER_URL),
        serverURL = SERVER_URL,
        profileId = profileId,
    )

    private companion object {
        const val SERVER_URL = "https://media.example.test"
        const val CONTROLLER = "phone-1"
    }
}
