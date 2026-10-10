package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DeviceLoginApi

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

    @Test fun anIdentityBeingEndedIsNeverReused() = runTest {
        val gated = GatedLogoutApi(api)
        val manager = RemotePlaybackIdentityManager(gated, tokens, deviceNameProvider = { "TV" })
        suspend fun prepareOn(run: Long) =
            manager.prepare(offer(), CONTROLLER, controllerDeviceName = null, receiverRun = run) {}

        prepareOn(STOPPED_RUN)
        val ending = assertNotNull(manager.activeIdentity)
        val logout = CompletableDeferred<Unit>()
        gated.logoutGate = logout

        val end = launch { manager.end(ending.generationId) }
        runCurrent()
        // The logout is in flight: nothing outside the lock may launch on it.
        assertNull(manager.activeIdentity)

        val sameOffer = async { prepareOn(NEXT_RUN) }
        runCurrent()
        logout.complete(Unit)
        end.join()
        val ready = sameOffer.await()

        assertFalse(ready.reused)
        assertEquals(2, gated.startCalls)
        assertNotEquals(ending.generationId, manager.activeIdentity?.generationId)
    }

    private suspend fun prepare(offer: SiloCastHandoffOffer, run: Long) =
        manager.prepare(offer, CONTROLLER, controllerDeviceName = null, receiverRun = run) {}

    private fun offer(profileId: String = "profile-1") = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(SERVER_URL),
        serverURL = SERVER_URL,
        profileId = profileId,
    )

    /** Counts handoff starts and can hold a logout open; the rest is [FakeRemotePlaybackApi]. */
    private class GatedLogoutApi(private val inner: FakeRemotePlaybackApi) : DeviceLoginApi by inner {
        var startCalls = 0
        var logoutGate: CompletableDeferred<Unit>? = null

        override suspend fun startRemotePlaybackAt(
            serverUrl: String,
            deviceName: String?,
            devicePlatform: String?,
        ): ApiResult<DeviceLoginStartResponse> {
            startCalls += 1
            return inner.startRemotePlaybackAt(serverUrl, deviceName, devicePlatform)
        }

        override suspend fun endRemotePlayback(scope: AuthScopeSnapshot): ApiResult<Unit> {
            logoutGate?.await()
            return inner.endRemotePlayback(scope)
        }
    }

    private companion object {
        const val SERVER_URL = "https://media.example.test"
        const val CONTROLLER = "phone-1"
        const val STOPPED_RUN = 1L
        const val NEXT_RUN = 2L
    }
}
