package org.siloserver.silo.network.api

import org.siloserver.silo.model.auth.DeviceLoginPollRequest
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionRequest
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginStartRequest
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.map
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.skipSiloAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * OAuth-style device-login endpoints. Mirrors Apple's tvOS
 * `AuthService.startDeviceLogin` / `AuthService.pollDeviceLogin`
 * (see `/opt/silo-apple/iosApp/iosApp/Screens/Auth/AuthService.swift:209-227`).
 *
 * Modeled as an interface so [DeviceLoginRepository] tests can substitute
 * a fake without standing up a Ktor [HttpClient]. The real implementation
 * is [DefaultDeviceLoginApi].
 */
interface DeviceLoginApi {

    suspend fun startDeviceLogin(
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse>

    /**
     * Polls the server for the device-login status. A 404 surfaces as
     * [ApiResult.Error] with `code = 404` — the repository treats that
     * as a terminal "expired pairing row" signal.
     */
    suspend fun pollDeviceLogin(deviceCode: String): ApiResult<DeviceLoginPollResponse>

    /**
     * Start device login against an untrusted candidate server without changing
     * the app's active server or sending its current bearer/profile headers.
     */
    suspend fun startDeviceLoginAt(
        serverUrl: String,
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = unsupportedScopedDeviceLoginOperation()

    /** Poll the same candidate server without mutating or authenticating the active scope. */
    suspend fun pollDeviceLoginAt(
        serverUrl: String,
        deviceCode: String,
    ): ApiResult<DeviceLoginPollResponse> = unsupportedScopedDeviceLoginOperation()

    suspend fun remotePlaybackCapabilityAt(
        serverUrl: String,
    ): ApiResult<DeviceLoginCapabilityResponse> = ApiResult.Error(
        code = 501,
        error = "remote_playback_unsupported",
        message = "Remote playback handoff is not supported.",
    )

    suspend fun startRemotePlaybackAt(
        serverUrl: String,
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = unsupportedScopedDeviceLoginOperation()

    suspend fun lookupDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginLookupResponse>

    suspend fun approveDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginDecisionResponse>

    suspend fun denyDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginDecisionResponse>

    /** Authorize a device-login request against a profileless, pinned server scope. */
    suspend fun lookupDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginLookupResponse> = unsupportedScopedDeviceLoginOperation()

    suspend fun approveDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = unsupportedScopedDeviceLoginOperation()

    suspend fun approveRemotePlaybackForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = approveDeviceLoginForScope(scope, code)

    suspend fun endRemotePlayback(scope: AuthScopeSnapshot): ApiResult<Unit> = unsupportedScopedDeviceLoginOperation()

    suspend fun denyDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = unsupportedScopedDeviceLoginOperation()
}

private fun <T> unsupportedScopedDeviceLoginOperation(): ApiResult<T> = ApiResult.Error(
    code = 501,
    error = "scoped_device_login_unsupported",
    message = "This device-login implementation does not support candidate or pinned auth scopes.",
)

/**
 * Ktor-backed implementation. Uses [safeApiV2Call]
 * for unified error handling, matching [AuthApi]'s pattern.
 */
class DefaultDeviceLoginApi(private val client: HttpClient, private val gate: ApiV2Gate) : DeviceLoginApi {

    override suspend fun startDeviceLogin(
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/start") {
            skipSiloAuth()
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginStartRequest(deviceName, devicePlatform))
        }.requireAuthStatus(201)
    }

    override suspend fun pollDeviceLogin(deviceCode: String): ApiResult<DeviceLoginPollResponse> = safeApiV2Call<DevicePollV2>(gate) {
        client.post("/api/v2/auth/device/poll") {
            skipSiloAuth()
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginPollRequest(deviceCode))
        }.requireAuthStatus(200)
    }.map { it.domain() }

    override suspend fun startDeviceLoginAt(
        serverUrl: String,
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = safeApiV2Call(ApiV2Gate.Unrestricted) {
        client.post("${serverUrl.trimEnd('/')}/api/v2/auth/device/start") {
            skipSiloAuth()
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginStartRequest(deviceName, devicePlatform))
        }.requireAuthStatus(201)
    }

    override suspend fun pollDeviceLoginAt(
        serverUrl: String,
        deviceCode: String,
    ): ApiResult<DeviceLoginPollResponse> = safeApiV2Call<DevicePollV2>(ApiV2Gate.Unrestricted) {
        client.post("${serverUrl.trimEnd('/')}/api/v2/auth/device/poll") {
            skipSiloAuth()
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginPollRequest(deviceCode))
        }.requireAuthStatus(200)
    }.map { it.domain() }

    override suspend fun remotePlaybackCapabilityAt(
        serverUrl: String,
    ): ApiResult<DeviceLoginCapabilityResponse> = safeApiV2Call<DeviceCapabilityV2>(ApiV2Gate.Unrestricted) {
        client.get("${serverUrl.trimEnd('/')}/api/v2/auth/device/capability") {
            skipSiloAuth()
        }.requireAuthStatus(200)
    }.map { it.domain() }

    override suspend fun startRemotePlaybackAt(
        serverUrl: String,
        deviceName: String?,
        devicePlatform: String?,
    ): ApiResult<DeviceLoginStartResponse> = safeApiV2Call(ApiV2Gate.Unrestricted) {
        client.post("${serverUrl.trimEnd('/')}/api/v2/auth/device/start") {
            skipSiloAuth()
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(
                DeviceLoginStartRequest(
                    deviceName = deviceName,
                    devicePlatform = devicePlatform,
                    clientPurpose = "remote_playback",
                    temporary = true,
                ),
            )
        }.requireAuthStatus(201)
    }

    override suspend fun lookupDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginLookupResponse> = safeApiV2Call(gate) {
        client.get("/api/v2/auth/device") {
            skipSiloAuth()
            parameter("token", token?.takeIf { it.isNotBlank() })
            parameter("code", code?.takeIf { it.isNotBlank() })
        }.requireAuthStatus(200)
    }

    override suspend fun approveDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginDecisionResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/approve") {
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginDecisionRequest(token = token, code = code))
        }.requireAuthStatus(200)
    }

    override suspend fun denyDeviceLogin(
        token: String?,
        code: String?,
    ): ApiResult<DeviceLoginDecisionResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/deny") {
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginDecisionRequest(token = token, code = code))
        }.requireAuthStatus(200)
    }

    override suspend fun lookupDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginLookupResponse> = safeApiV2Call(gate) {
        client.get("/api/v2/auth/device") {
            skipSiloAuth()
            authScope(scope)
            parameter("code", code)
        }.requireAuthStatus(200)
    }

    override suspend fun approveDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/approve") {
            authScope(scope)
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginDecisionRequest(code = code))
        }.requireAuthStatus(200)
    }

    override suspend fun approveRemotePlaybackForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/approve-handoff") {
            authScope(scope)
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginDecisionRequest(code = code))
        }.requireAuthStatus(200)
    }

    override suspend fun endRemotePlayback(scope: AuthScopeSnapshot): ApiResult<Unit> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/logout") {
            authScope(scope)
            contentType(ContentType.Application.Json)
        }.requireAuthStatus(204)
    }

    override suspend fun denyDeviceLoginForScope(
        scope: AuthScopeSnapshot,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = safeApiV2Call(gate) {
        client.post("/api/v2/auth/device/deny") {
            authScope(scope)
            contentType(ContentType.Application.Json)
            setBody(DeviceLoginDecisionRequest(code = code))
        }.requireAuthStatus(200)
    }
}
