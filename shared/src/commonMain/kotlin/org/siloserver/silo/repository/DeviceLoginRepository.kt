package org.siloserver.silo.repository

import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.DeviceLoginStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DeviceLoginApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the OAuth device-login state machine: initiate → poll loop →
 * terminal (Approved / Failed). Exposes a single [state] StateFlow the
 * UI can observe.
 *
 * Mirrors Apple's [QRLoginViewModel.swift] polling behavior:
 *  - polls immediately after start (don't wait the first interval)
 *  - swallows transient network errors and keeps polling
 *  - 404 on poll = server cleaned up the row → terminal Failed(Expired)
 *  - honors server-requested poll-after override on Pending responses
 */
class DeviceLoginRepository(
    private val api: DeviceLoginApi,
) {
    sealed class DeviceLoginState {
        object Idle : DeviceLoginState()
        object Initiating : DeviceLoginState()
        data class Awaiting(val session: DeviceLoginStartResponse) : DeviceLoginState()
        data class Approved(val response: DeviceLoginPollResponse) : DeviceLoginState()
        data class Failed(val reason: FailureReason, val message: String? = null) : DeviceLoginState()
    }

    enum class FailureReason {
        StartFailed,    // initiate POST returned non-2xx
        Expired,        // polled row gone (404) or status=expired
        Denied,         // status=denied
        Consumed,       // status=consumed (already used)
        MissingTokens,  // status=approved but no access_token returned
        UnknownStatus,  // server returned a status we don't recognize
    }

    private val _state = MutableStateFlow<DeviceLoginState>(DeviceLoginState.Idle)
    val state: StateFlow<DeviceLoginState> = _state.asStateFlow()

    /**
     * Begins a new device-login session. Suspends through initiate;
     * starts polling internally and returns when the state machine
     * reaches a terminal value (Approved or Failed).
     *
     * Call this from a cancellable coroutine — cancel to abort.
     */
    suspend fun begin(deviceName: String?, devicePlatform: String?) {
        beginAt(serverUrl = null, deviceName = deviceName, devicePlatform = devicePlatform)
    }

    /**
     * Begin against [serverUrl] without changing the globally active server.
     * Companion setup uses this persist-on-success path for candidate servers.
     */
    suspend fun beginAt(serverUrl: String?, deviceName: String?, devicePlatform: String?) {
        _state.value = DeviceLoginState.Initiating

        val startResult = if (serverUrl == null) {
            api.startDeviceLogin(deviceName, devicePlatform)
        } else {
            api.startDeviceLoginAt(serverUrl, deviceName, devicePlatform)
        }
        val session = when (val r = startResult) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> {
                _state.value = DeviceLoginState.Failed(
                    FailureReason.StartFailed,
                    "Server returned ${r.code}: ${r.message}",
                )
                return
            }
            is ApiResult.NetworkError -> {
                _state.value = DeviceLoginState.Failed(
                    FailureReason.StartFailed,
                    r.exception.message,
                )
                return
            }
        }

        _state.value = DeviceLoginState.Awaiting(session)
        runPollLoop(session, serverUrl)
    }

    fun reset() {
        _state.value = DeviceLoginState.Idle
    }

    suspend fun lookup(token: String?, code: String?) =
        api.lookupDeviceLogin(token = token, code = code)

    suspend fun approve(token: String?, code: String?) =
        api.approveDeviceLogin(token = token, code = code)

    suspend fun deny(token: String?, code: String?) =
        api.denyDeviceLogin(token = token, code = code)

    suspend fun lookup(scope: AuthScopeSnapshot, code: String) =
        api.lookupDeviceLoginForScope(scope, code)

    suspend fun approve(scope: AuthScopeSnapshot, code: String) =
        api.approveDeviceLoginForScope(scope, code)

    suspend fun deny(scope: AuthScopeSnapshot, code: String) =
        api.denyDeviceLoginForScope(scope, code)

    private suspend fun runPollLoop(session: DeviceLoginStartResponse, serverUrl: String?) {
        var intervalMs = session.interval.coerceAtLeast(1) * 1_000L

        while (true) {
            try {
                val pollResult = if (serverUrl == null) {
                    api.pollDeviceLogin(session.deviceCode)
                } else {
                    api.pollDeviceLoginAt(serverUrl, session.deviceCode)
                }
                val response = when (val r = pollResult) {
                    is ApiResult.Success -> r.data
                    is ApiResult.Error -> {
                        if (r.code == 404) {
                            _state.value = DeviceLoginState.Failed(
                                FailureReason.Expired,
                                "This sign-in request has expired.",
                            )
                            return
                        }
                        // Transient — keep trying.
                        delay(intervalMs)
                        continue
                    }
                    is ApiResult.NetworkError -> {
                        // Transient network blip — keep trying.
                        delay(intervalMs)
                        continue
                    }
                }

                // Honor server's poll-after override if present.
                response.pollAfter?.let { intervalMs = it.coerceAtLeast(1) * 1_000L }

                when (DeviceLoginStatus.fromWire(response.status)) {
                    DeviceLoginStatus.Pending -> {
                        delay(intervalMs)
                    }
                    DeviceLoginStatus.Approved -> {
                        if (response.accessToken.isNullOrBlank() ||
                            response.refreshToken.isNullOrBlank()) {
                            _state.value = DeviceLoginState.Failed(
                                FailureReason.MissingTokens,
                                "Server approved the session but did not return tokens.",
                            )
                            return
                        }
                        _state.value = DeviceLoginState.Approved(response)
                        return
                    }
                    DeviceLoginStatus.Denied -> {
                        _state.value = DeviceLoginState.Failed(
                            FailureReason.Denied,
                            "Sign-in was denied on the other device.",
                        )
                        return
                    }
                    DeviceLoginStatus.Expired -> {
                        _state.value = DeviceLoginState.Failed(
                            FailureReason.Expired,
                            "This code expired before it was approved.",
                        )
                        return
                    }
                    DeviceLoginStatus.Consumed -> {
                        _state.value = DeviceLoginState.Failed(
                            FailureReason.Consumed,
                            "This code has already been used.",
                        )
                        return
                    }
                    DeviceLoginStatus.Unknown -> {
                        _state.value = DeviceLoginState.Failed(
                            FailureReason.UnknownStatus,
                            "Unexpected status: ${response.status}",
                        )
                        return
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
