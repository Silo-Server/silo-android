package org.siloserver.silo.tv.cast

import android.app.Application
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DeviceLoginApi

/**
 * The receiver's stop() ends the identity it captured only after the player's
 * teardown, so a same-phone handoff can reuse that identity (same generation)
 * in the meantime. The deferred cleanup must leave a reused identity alone but
 * still end one that no handoff ever claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemotePlaybackIdentityClaimTest {

    private val api = FakeRemotePlaybackApi()
    private val tokens = TokenManagerImpl()
    private val manager = RemotePlaybackIdentityManager(api, tokens, deviceNameProvider = { "TV" })

    @Test fun reuseByTheSamePhoneBlocksTheStaleCleanup() = runTest {
        prepare(offer())
        val captured = assertNotNull(manager.activeIdentity)

        val ready = prepare(offer())

        assertTrue(ready.reused)
        assertFalse(manager.endUnclaimed(captured))
        assertEquals(captured.generationId, manager.activeIdentity?.generationId)
        assertTrue(tokens.hasTemporaryScope())
        assertEquals(0, api.endCalls)
    }

    @Test fun anOfferThatFailsBeforeClaimingLeavesItToTheCleanup() = runTest {
        prepare(offer())
        val captured = assertNotNull(manager.activeIdentity)

        // The server id does not encode the URL, so validation rejects it
        // before the active identity is touched.
        assertFails { prepare(offer().copy(serverId = "mismatched")) }

        assertTrue(manager.endUnclaimed(captured))
        assertNull(manager.activeIdentity)
        assertFalse(tokens.hasTemporaryScope())
        assertEquals(1, api.endCalls)
    }

    @Test fun aReplacementIdentityIsNotEndedByTheOldCleanup() = runTest {
        prepare(offer())
        val captured = assertNotNull(manager.activeIdentity)

        api.approvedProfileId = "profile-2"
        prepare(offer(profileId = "profile-2"))
        val replacement = assertNotNull(manager.activeIdentity)

        assertNotEquals(captured.generationId, replacement.generationId)
        assertFalse(manager.endUnclaimed(captured))
        assertEquals(replacement, manager.activeIdentity)
        assertTrue(tokens.hasTemporaryScope())
    }

    private suspend fun prepare(offer: SiloCastHandoffOffer) =
        manager.prepare(offer, CONTROLLER, controllerDeviceName = null) {}

    private fun offer(profileId: String = "profile-1") = SiloCastHandoffOffer(
        requestId = "request",
        serverId = AndroidServerRegistry.idFor(SERVER_URL),
        serverURL = SERVER_URL,
        profileId = profileId,
    )

    private class FakeRemotePlaybackApi : DeviceLoginApi {
        var endCalls = 0
        var approvedProfileId = "profile-1"

        override suspend fun remotePlaybackCapabilityAt(serverUrl: String) = ApiResult.Success(
            DeviceLoginCapabilityResponse(
                remotePlaybackHandoff = true,
                protocolVersions = listOf(SiloCastProtocol.version),
            ),
        )

        override suspend fun startRemotePlaybackAt(
            serverUrl: String,
            deviceName: String?,
            devicePlatform: String?,
        ) = ApiResult.Success(
            DeviceLoginStartResponse(
                deviceCode = "device-code",
                userCode = "USER",
                matchCode = "12",
                verificationUri = "$serverUrl/pair",
                verificationUriComplete = "$serverUrl/pair?code=USER",
                expiresAt = "2099-01-01T00:00:00Z",
                expiresIn = 60,
                interval = 1,
                deviceName = deviceName.orEmpty(),
                devicePlatform = devicePlatform.orEmpty(),
                clientPurpose = "remote_playback",
                temporary = true,
            ),
        )

        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String) = ApiResult.Success(
            DeviceLoginPollResponse(
                status = "approved",
                accessToken = "access",
                refreshToken = "refresh",
                profileId = approvedProfileId,
                profileToken = "profile-token",
                temporary = true,
            ),
        )

        override suspend fun endRemotePlayback(scope: AuthScopeSnapshot): ApiResult<Unit> {
            endCalls += 1
            return ApiResult.Success(Unit)
        }

        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("unused")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("unused")
        override suspend fun lookupDeviceLogin(token: String?, code: String?) = error("unused")
        override suspend fun approveDeviceLogin(token: String?, code: String?) = error("unused")
        override suspend fun denyDeviceLogin(token: String?, code: String?) = error("unused")
    }

    private companion object {
        const val SERVER_URL = "https://media.example.test"
        const val CONTROLLER = "phone-1"
    }
}
