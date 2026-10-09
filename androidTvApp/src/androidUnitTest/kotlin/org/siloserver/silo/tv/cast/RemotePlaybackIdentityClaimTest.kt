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
 * The receiver's stop() ends the temporary identity only after the player's
 * teardown, so a returning phone can hand off again (reusing the same
 * generation, or replacing it) in the meantime. The deferred cleanup must leave
 * an identity claimed by a later receiver run alone, but still end one that
 * only the stopped run ever claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemotePlaybackIdentityClaimTest {

    private val api = FakeRemotePlaybackApi()
    private val tokens = TokenManagerImpl()
    private val manager = RemotePlaybackIdentityManager(api, tokens, deviceNameProvider = { "TV" })

    @Test fun reuseByALaterRunBlocksTheStaleCleanup() = runTest {
        prepare(offer(), run = STOPPED_RUN)
        val installed = assertNotNull(manager.activeIdentity)

        val ready = prepare(offer(), run = NEXT_RUN)

        assertTrue(ready.reused)
        assertFalse(manager.endIfNotClaimedSince(STOPPED_RUN))
        assertEquals(installed.generationId, manager.activeIdentity?.generationId)
        assertTrue(tokens.hasTemporaryScope())
        assertEquals(0, api.endCalls)
    }

    @Test fun reuseByTheStoppedRunStillLetsTheCleanupEndIt() = runTest {
        prepare(offer(), run = STOPPED_RUN)

        // A handoff from the session stop() is tearing down can finish its
        // reuse after stop() ran. That run is gone, so nothing else ends it.
        assertTrue(prepare(offer(), run = STOPPED_RUN).reused)

        assertTrue(manager.endIfNotClaimedSince(STOPPED_RUN))
        assertNull(manager.activeIdentity)
        assertFalse(tokens.hasTemporaryScope())
        assertEquals(1, api.endCalls)
    }

    @Test fun anOfferThatFailsBeforeClaimingLeavesItToTheCleanup() = runTest {
        prepare(offer(), run = STOPPED_RUN)

        // The server id does not encode the URL, so validation rejects it
        // before the active identity is touched.
        assertFails { prepare(offer().copy(serverId = "mismatched"), run = NEXT_RUN) }

        assertTrue(manager.endIfNotClaimedSince(STOPPED_RUN))
        assertNull(manager.activeIdentity)
        assertFalse(tokens.hasTemporaryScope())
        assertEquals(1, api.endCalls)
    }

    @Test fun aReplacementIdentityIsNotEndedByTheOldCleanup() = runTest {
        prepare(offer(), run = STOPPED_RUN)
        val installed = assertNotNull(manager.activeIdentity)

        api.approvedProfileId = "profile-2"
        prepare(offer(profileId = "profile-2"), run = NEXT_RUN)
        val replacement = assertNotNull(manager.activeIdentity)

        assertNotEquals(installed.generationId, replacement.generationId)
        assertFalse(manager.endIfNotClaimedSince(STOPPED_RUN))
        assertEquals(replacement, manager.activeIdentity)
        assertTrue(tokens.hasTemporaryScope())
    }

    private suspend fun prepare(offer: SiloCastHandoffOffer, run: Long) =
        manager.prepare(offer, CONTROLLER, controllerDeviceName = null, receiverRun = run) {}

    private fun offer(profileId: String = "profile-1") = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(SERVER_URL),
        serverURL = SERVER_URL,
        profileId = profileId,
    )

    private companion object {
        const val SERVER_URL = "https://media.example.test"
        const val CONTROLLER = "phone-1"
        const val STOPPED_RUN = 1L
        const val NEXT_RUN = 2L
    }
}
