package org.siloserver.silo.tv.cast

import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DeviceLoginApi

/** Remote-playback device-login API that approves every handoff unless told otherwise. */
internal class FakeRemotePlaybackApi : DeviceLoginApi {
    var endCalls = 0
    var approvedProfileId = "profile-1"
    var pollStatus = "approved"

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
            status = pollStatus,
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
