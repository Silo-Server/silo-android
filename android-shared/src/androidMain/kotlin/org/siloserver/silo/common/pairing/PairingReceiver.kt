package org.siloserver.silo.common.pairing

import android.util.Log
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingProtocol
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Identity + state the receiver advertises and announces in its [PairingMessage.Hello].
 */
data class PairingDeviceIdentity(
    val name: String,
    val deviceId: String,
    /**
     * Passed to device-login start as `device_platform`. Same spelling as the
     * X-Silo-Device-Platform header and the two other TV login entry points
     * (TvLoginViewModel, RemotePlaybackIdentityManager), so a TV signed in over
     * LAN companion pairing is classified as a TV rather than falling into the
     * web frontend's mobile bucket.
     */
    val platform: String = "android-tv",
)

/**
 * UI-facing status of the TV pairing receiver. Mirrors the tvOS
 * `ReceiverPairingCoordinator.State`.
 */
sealed class PairingReceiverStatus {
    /** Not advertising / not connected. */
    data object Idle : PairingReceiverStatus()

    /** Advertising on the LAN, waiting for a phone to connect. */
    data object Advertising : PairingReceiverStatus()

    /** A phone connected and is choosing servers on its end. */
    data object Connected : PairingReceiverStatus()

    /**
     * A phone pushed a server and the TV user must allow it before the
     * device-login (and its pairing code) starts — tvOS-parity consent step.
     */
    data class ConsentRequested(
        val serverURL: String,
        val serverName: String,
    ) : PairingReceiverStatus()

    /** Device-login started for a pushed server (interim). */
    data class Pairing(val serverURL: String, val serverName: String) : PairingReceiverStatus()

    /** Showing the match code for a pushed server while the phone approves. */
    data class AwaitingApproval(
        val serverURL: String,
        val serverName: String,
        val matchCode: String,
    ) : PairingReceiverStatus()

    /** A single server finished signing in; the phone may push more. */
    data class SignedIn(val serverCount: Int) : PairingReceiverStatus()

    /** The phone sent Done after one or more servers signed in. */
    data class Completed(val serverNames: List<String>) : PairingReceiverStatus()

    /** Terminal failure. */
    data class Failed(val serverName: String, val message: String) : PairingReceiverStatus()
}

/**
 * State machine for the TV side of a companion-pairing connection. Coroutine
 * driven and transport-agnostic: it consumes one [PairingTransport] (real
 * TLS-PSK socket in production, in-memory fake in tests) and drives the flow:
 *
 *  1. send [PairingMessage.Hello] (state = current setup/login).
 *  2. on [PairingMessage.PushServer]: set the server via [AuthRepository], then
 *     run [DeviceLoginRepository.begin]; while it runs, observe its `state` —
 *     on Awaiting send [PairingMessage.DeviceStarted]; on Approved send
 *     [PairingMessage.ServerResult] signedIn; on Failed send `failed`.
 *  3. on [PairingMessage.Done] / [PairingMessage.Cancel] / EOF / error: finish.
 *
 * One [run] call drives exactly one connection. The real advertiser serializes
 * connections (one at a time) and calls [run] per accepted connection.
 *
 * Mirrors the silo-apple `ReceiverPairingCoordinator`.
 */
class PairingReceiver(
    private val authPort: PairingAuthPort,
    private val deviceLogin: DeviceLoginPort,
    private val identityProvider: () -> PairingDeviceIdentity,
    private val receiverStateProvider: () -> PairingReceiverState,
) {
    private companion object {
        private const val TAG = "PairingReceiver"
    }

    private val _status = MutableStateFlow<PairingReceiverStatus>(PairingReceiverStatus.Idle)
    val status: StateFlow<PairingReceiverStatus> = _status.asStateFlow()

    /** True while a server's device-login is in flight; the protocol is one at a time. */
    private var pollingServerUrl: String? = null

    /** Push held until the TV user allows it (tvOS consent parity). */
    private var pendingPush: PairingMessage.PushServer? = null

    /** Per-session: once the user allows one push, later pushes skip the ask. */
    private var consented = false

    /** Set on deny: a buffered PushServer must not re-open the consent prompt
     *  while the Cancel/close is still in flight (CodeRabbit PR#44). */
    private var sessionDenied = false

    /** Session scope captured so [allowPendingServer] can launch the login. */
    private var sessionScope: CoroutineScope? = null

    /** The in-flight device-login child job, so EOF/Cancel teardown can cancel it. */
    private var pushJob: Job? = null

    /** Active transport, so the UI can cancel the in-place receiver flow. */
    private var activeTransport: PairingTransport? = null

    private var signedInCount = 0
    private val signedInNames = mutableListOf<String>()

    fun setAdvertising() {
        _status.value = PairingReceiverStatus.Advertising
    }

    fun setIdle() {
        _status.value = PairingReceiverStatus.Idle
    }

    fun cancelActiveSession() {
        pushJob?.cancel()
        runCatching { activeTransport?.close() }
        _status.value = PairingReceiverStatus.Idle
    }

    /**
     * TV user allowed the pending pushed server. Consent is per-session — the
     * same phone may push more servers without being re-asked (tvOS parity:
     * `ReceiverPairingCoordinator.allowPendingServer`).
     */
    fun allowPendingServer() {
        val push = pendingPush ?: return
        val transport = activeTransport ?: return
        val scope = sessionScope ?: return
        if (_status.value !is PairingReceiverStatus.ConsentRequested) return
        consented = true
        pendingPush = null
        handlePushServer(push, transport, scope)
    }

    /**
     * TV user declined the pending pushed server — tear the session down; the
     * phone sees the connection end (tvOS sends Cancel("consent_denied")).
     */
    fun denyPendingServer() {
        if (_status.value !is PairingReceiverStatus.ConsentRequested) return
        sessionDenied = true
        pendingPush = null
        val transport = activeTransport
        sessionScope?.launch {
            runCatching {
                transport?.send(PairingMessage.Cancel(reason = "consent_denied"))
            }
            runCatching { transport?.close() }
        } ?: runCatching { transport?.close() }
        _status.value = PairingReceiverStatus.Idle
    }

    /**
     * Drive one connection to completion. Returns when the peer finishes
     * (Done), cancels, the stream ends, or an error is thrown. Always closes the
     * [transport] before returning. Safe to cancel — cancellation propagates to
     * the in-flight device-login and the transport is closed.
     */
    suspend fun run(transport: PairingTransport) {
        pollingServerUrl = null
        pushJob = null
        pendingPush = null
        consented = false
        sessionDenied = false
        activeTransport = transport
        signedInCount = 0
        signedInNames.clear()
        var preserveTerminalStatus = false
        try {
            val identity = identityProvider()
            Log.i(TAG, "sending pairing hello")
            transport.send(
                PairingMessage.Hello(
                    tvName = identity.name,
                    tvDeviceId = identity.deviceId,
                    state = receiverStateProvider(),
                    supportedVersions = listOf(PairingProtocol.VERSION),
                ),
            )
            _status.value = PairingReceiverStatus.Connected
            Log.i(TAG, "pairing receiver connected")

            // Collect inbound messages on the session scope. PushServer launches
            // the device-login work as a CHILD coroutine (rather than blocking the
            // collector) so a phone disconnect / Cancel / EOF mid-approval is still
            // observed promptly and tears the session down.
            coroutineScope {
                val scope = this
                sessionScope = scope
                transport.incoming.collectMessage { message ->
                    when (message) {
                        is PairingMessage.PushServer ->
                            handlePushServer(message, transport, scope)
                        is PairingMessage.Done -> {
                            Log.i(TAG, "received pairing done")
                            pushJob?.cancelAndJoin()
                            if (signedInCount > 0) {
                                _status.value = PairingReceiverStatus.Completed(signedInNames.toList())
                                preserveTerminalStatus = true
                            } else {
                                _status.value = PairingReceiverStatus.Idle
                            }
                            return@collectMessage false
                        }
                        is PairingMessage.Cancel -> {
                            Log.i(TAG, "received pairing cancel")
                            pushJob?.cancelAndJoin()
                            _status.value = PairingReceiverStatus.Idle
                            return@collectMessage false
                        }
                        else -> {
                            // Receiver only consumes phone→TV message kinds; ignore others.
                        }
                    }
                    true
                }
                // Stream ended (Done/Cancel/EOF) — cancel any in-flight device-login.
                pushJob?.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Transport / decode error: connection dropped mid-session.
        } finally {
            pushJob?.cancel()
            pushJob = null
            pendingPush = null
            sessionScope = null
            if (activeTransport === transport) {
                activeTransport = null
            }
            if (!preserveTerminalStatus) {
                _status.value = PairingReceiverStatus.Idle
            }
            transport.close()
        }
    }

    /**
     * Start device-login against the pushed (not-yet-trusted) server and drive
     * its outcome back to the phone. One at a time — an overlapping PushServer
     * while a poll is in flight is ignored.
     */
    private fun handlePushServer(
        message: PairingMessage.PushServer,
        transport: PairingTransport,
        sessionScope: CoroutineScope,
    ) {
        if (pollingServerUrl != null) return // one at a time; ignore overlap.
        if (sessionDenied) return // denied: session is tearing down.
        val serverURL = message.serverURL
        val serverName = message.serverName?.takeIf { it.isNotBlank() } ?: serverURL
        Log.i(TAG, "received pushed server")
        // tvOS parity: the first push of a session needs the TV user's consent
        // before device-login starts (and before any pairing code shows). A
        // newer push while consent is pending supersedes the earlier one.
        if (!consented) {
            pendingPush = message
            _status.value = PairingReceiverStatus.ConsentRequested(serverURL, serverName)
            return
        }
        pollingServerUrl = serverURL
        // Run the device-login begin/await CONCURRENTLY with continued inbound
        // reading so Cancel/EOF can cancel this job and tear the session down.
        pushJob = sessionScope.launch {
            try {
                runDeviceLogin(
                    serverURL = serverURL,
                    serverName = serverName,
                    fetchedName = message.serverName,
                    transport = transport,
                )
            } finally {
                deviceLogin.reset()
                pollingServerUrl = null
            }
        }
    }

    private suspend fun runDeviceLogin(
        serverURL: String,
        serverName: String,
        fetchedName: String?,
        transport: PairingTransport,
    ) {
        _status.value = PairingReceiverStatus.Pairing(serverURL, serverName)

        val identity = identityProvider()
        var deviceStartedSent = false

        coroutineScope {
            // Observe the device-login state machine while begin() runs the
            // poll loop. Cancel the collector once we reach a terminal state.
            val observer = launch {
                deviceLogin.state.collectMessage { state ->
                    when (state) {
                        is DeviceLoginRepository.DeviceLoginState.Awaiting -> {
                                if (!deviceStartedSent) {
                                    deviceStartedSent = true
                                    val session = state.session
                                    _status.value = PairingReceiverStatus.AwaitingApproval(
                                        serverURL = serverURL,
                                        serverName = serverName,
                                        matchCode = session.matchCode,
                                    )
                                    transport.send(
                                        PairingMessage.DeviceStarted(
                                            serverURL = serverURL,
                                            userCode = session.userCode,
                                            matchCode = session.matchCode,
                                        ),
                                    )
                                    Log.i(TAG, "sent device-started")
                                }
                                true
                            }
                            is DeviceLoginRepository.DeviceLoginState.Approved -> {
                                // Persist the approved session's tokens BEFORE
                                // signaling SignedIn — same as the credential / QR
                                // login flow (TvLoginViewModel.handleDeviceLoginApproved).
                                // Without this the TV navigates to profile selection
                                // unauthenticated. The repository already guards
                                // against null tokens (Failed.MissingTokens).
                                val response = state.response
                                val accessToken = response.accessToken
                                val refreshToken = response.refreshToken
                                if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
                                    authPort.persistApprovedSession(
                                        serverUrl = serverURL,
                                        serverName = fetchedName,
                                        accessToken = accessToken,
                                        refreshToken = refreshToken,
                                        expiresIn = response.expiresIn ?: 0L,
                                    )
                                }
                                signedInCount += 1
                                signedInNames += serverName
                                _status.value = PairingReceiverStatus.SignedIn(signedInCount)
                                transport.send(
                                    PairingMessage.ServerResult(
                                        serverURL = serverURL,
                                        status = PairingServerStatus.SignedIn,
                                        error = null,
                                    ),
                                )
                                Log.i(TAG, "sent signed-in result")
                                false // terminal — stop observing.
                            }
                            is DeviceLoginRepository.DeviceLoginState.Failed -> {
                                _status.value = PairingReceiverStatus.Failed(
                                    serverName = serverName,
                                    message = state.message ?: state.reason.name,
                                )
                                transport.send(
                                    PairingMessage.ServerResult(
                                        serverURL = serverURL,
                                        status = PairingServerStatus.Failed,
                                        error = state.message ?: state.reason.name,
                                    ),
                                )
                                Log.i(TAG, "sent failed result")
                                false // terminal — stop observing.
                            }
                            else -> true // Idle / Initiating — keep observing.
                        }
                    }
                }

                deviceLogin.begin(
                    serverUrl = serverURL,
                    deviceName = identity.name,
                    devicePlatform = identity.platform,
                )
                // begin() has reached a terminal state; let the observer drain it
                // (it stops itself on the terminal value).
                observer.join()
            }
    }
}

/**
 * Collect [this] flow, invoking [block] for each value; stop collecting as soon
 * as [block] returns false. A tiny helper so the state machine reads top-down.
 */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectMessage(
    block: suspend (T) -> Boolean,
) {
    val flow = this
    try {
        flow.collect { value ->
            if (!block(value)) {
                throw StopCollect
            }
        }
    } catch (e: StopCollectException) {
        // Normal termination signal — swallow.
        if (e !== StopCollect) throw e
    }
}

private object StopCollect : StopCollectException()
private open class StopCollectException : CancellationException("stop-collect")
