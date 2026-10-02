package org.siloserver.silo.common.pairing

import android.util.Log
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.pairing.PairingEndpoint
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingProtocol
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.pairing.normalizeEndpointUrl
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/** How the sign-in screen's own request ended, relayed to the phone. */
sealed interface NearbySignInOutcome {
    data object SignedIn : NearbySignInOutcome
    data class Failed(val code: PairingFailureCode) : NearbySignInOutcome
}

/**
 * The sign-in screen's own device request, lent to the LAN receiver in
 * `login` mode. The phone approves the code the TV already shows, so there is
 * only ever one code on screen; the sign-in screen polls, saves the session
 * and routes on, and the receiver only relays the outcome. Mirrors the
 * silo-apple `NearbySignInCodeSource`.
 */
interface NearbySignInCodeSource {
    /** The code on screen, waiting briefly while one is being fetched; null when none is available. */
    suspend fun codeForNearbyApproval(): DeviceLoginStartResponse?

    /** Waits until the request behind [deviceCode] signs in, fails, or is replaced by another code. */
    suspend fun nearbyApprovalOutcome(deviceCode: String): NearbySignInOutcome
}

/**
 * What the TV advertises while a screen accepts phones.
 *
 * - [Setup]: a first-run TV (or Add Server). Any signed-in phone may offer to
 *   set it up and choose which of its servers to push.
 * - [login]: a TV on its sign-in screen for one server. TXT carries
 *   `st=login` and `srv=<serverIdentity>`; phones offer it only when they hold
 *   that server (matched by verified identity), and push only that one. The
 *   phone approves the code [source] already shows at [serverUrl].
 */
data class PairingAdvertisement(
    val state: PairingReceiverState,
    val serverIdentity: String? = null,
    val serverUrl: String? = null,
    val source: NearbySignInCodeSource? = null,
) {
    companion object {
        val Setup = PairingAdvertisement(PairingReceiverState.Setup)

        fun login(serverIdentity: String, serverUrl: String, source: NearbySignInCodeSource) =
            PairingAdvertisement(PairingReceiverState.Login, serverIdentity, serverUrl, source)
    }
}

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
     * device-login (and its sign-in code) starts — tvOS-parity consent step.
     */
    data class ConsentRequested(
        val serverURL: String,
        val serverName: String,
    ) : PairingReceiverStatus()

    /** Checking the server's address and starting device sign-in there (interim). */
    data class Pairing(val serverURL: String, val serverName: String) : PairingReceiverStatus()

    /**
     * The pushed address didn't answer from this TV. [providerName] names the
     * network provider behind it when the phone listed one; [alternateUrl] is
     * a verified address of the same server the TV user may choose instead.
     * Nothing switches without that choice.
     */
    data class Unreachable(
        val serverURL: String,
        val serverName: String,
        val providerName: String?,
        val alternateUrl: String?,
    ) : PairingReceiverStatus()

    /**
     * Showing the TV's sign-in code for a pushed server while the phone
     * approves. People compare [userCode] (the code the TV shows everywhere).
     * [automatic] = a later server in a multi-server push, verified by the
     * phone without asking. [matchCode] is the legacy match words: phones
     * released before user codes show them instead, so a person-checked
     * attempt names them in a fallback line (remove with the phones'
     * "Older TV apps show ..." line).
     */
    data class AwaitingApproval(
        val serverURL: String,
        val serverName: String,
        val userCode: String,
        val automatic: Boolean = false,
        val matchCode: String? = null,
    ) : PairingReceiverStatus()

    /** A single server finished signing in; the phone may push more. */
    data class SignedIn(val serverCount: Int) : PairingReceiverStatus()

    /** The phone sent Done after one or more servers signed in. */
    data class Completed(val serverNames: List<String>) : PairingReceiverStatus()

    /** Terminal failure for the last attempted server. */
    data class Failed(val serverName: String, val code: PairingFailureCode) : PairingReceiverStatus()
}

/**
 * State machine for the TV side of a companion-pairing connection. Coroutine
 * driven and transport-agnostic: it consumes one [PairingTransport] (real
 * TLS-PSK socket in production, in-memory fake in tests) and drives the flow:
 *
 *  1. send [PairingMessage.Hello] with the advertised state.
 *  2. on [PairingMessage.PushServer]: ask the TV user once per session. In
 *     `setup` mode, pick the address to sign in at (identity-checked), run
 *     device login there and save the session. In `login` mode, check the
 *     pushed identity against the advertised one and lend the sign-in
 *     screen's own code ([NearbySignInCodeSource]). Either way send
 *     [PairingMessage.DeviceStarted], then [PairingMessage.ServerResult]
 *     with a [PairingFailureCode] on failure. A newer push replaces one in
 *     flight (the protocol is one server at a time; a new push means the
 *     phone gave up on the previous one).
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
    /** Classifies one address; production probes `GET /api/v2/system/identity`. */
    private val identityProbe: suspend (serverUrl: String) -> ServerIdentityProbe = { ServerIdentityProbe.Unreachable },
) {
    private companion object {
        private const val TAG = "PairingReceiver"
    }

    private val _status = MutableStateFlow<PairingReceiverStatus>(PairingReceiverStatus.Idle)
    val status: StateFlow<PairingReceiverStatus> = _status.asStateFlow()

    /** What the TV currently advertises; set by [TvPairingAdvertiser.start]. */
    @Volatile
    var advertisement: PairingAdvertisement = PairingAdvertisement.Setup

    /** Push held until the TV user allows it (tvOS consent parity). */
    private var pendingPush: PairingMessage.PushServer? = null

    /** Per-session: once the user allows one push, later pushes skip the ask. */
    private var consented = false

    /** Set on deny: a buffered PushServer must not re-open the consent prompt
     *  while the Cancel/close is still in flight (CodeRabbit PR#44). */
    private var sessionDenied = false

    /** Session scope captured so [allowPendingServer] can launch the login. */
    private var sessionScope: CoroutineScope? = null

    /** The in-flight attempt, so a newer push, EOF or Cancel can cancel it. */
    private var pushJob: Job? = null

    /** The TV user's pending choice while [status] is [PairingReceiverStatus.Unreachable]. */
    private var alternateDecision: CompletableDeferred<AlternateChoice>? = null

    /** Active transport, so the UI can cancel the in-place receiver flow. */
    private var activeTransport: PairingTransport? = null

    private val signedInNames = mutableListOf<String>()

    private enum class AlternateChoice { UseAlternate, Retry }

    /** An attempt failure with its wire code. */
    private class AttemptFailure(val code: PairingFailureCode) : Exception(code.wire)

    private data class LoginTarget(val url: String, val verifiedServerId: String?)

    /** Whether the advertiser is listening, so closing a session returns to Advertising rather than Idle. */
    @Volatile
    private var listening = false

    fun setAdvertising() {
        listening = true
        _status.value = PairingReceiverStatus.Advertising
    }

    fun setIdle() {
        listening = false
        _status.value = PairingReceiverStatus.Idle
    }

    /** Cancel/Back/OK on the pairing panel: end the session, or close a failure left on screen. */
    fun cancelActiveSession() {
        pushJob?.cancel()
        runCatching { activeTransport?.close() }
        _status.value = if (listening) PairingReceiverStatus.Advertising else PairingReceiverStatus.Idle
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
        beginAttempt(push, transport, scope)
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

    /** TV user chose the verified alternate address shown on the unreachable screen. */
    fun useAlternateAddress() {
        val status = _status.value as? PairingReceiverStatus.Unreachable ?: return
        if (status.alternateUrl == null) return
        alternateDecision?.complete(AlternateChoice.UseAlternate)
    }

    /** TV user fixed the connection (or set up the provider) and wants the pushed address tried again. */
    fun retryPushedAddress() {
        if (_status.value !is PairingReceiverStatus.Unreachable) return
        alternateDecision?.complete(AlternateChoice.Retry)
    }

    /**
     * Drive one connection to completion. Returns when the peer finishes
     * (Done), cancels, the stream ends, or an error is thrown. Always closes the
     * [transport] before returning. Safe to cancel — cancellation propagates to
     * the in-flight device-login and the transport is closed.
     */
    suspend fun run(transport: PairingTransport) {
        pushJob = null
        pendingPush = null
        consented = false
        sessionDenied = false
        activeTransport = transport
        signedInNames.clear()
        var preserveTerminalStatus = false
        try {
            val identity = identityProvider()
            Log.i(TAG, "sending pairing hello")
            transport.send(
                PairingMessage.Hello(
                    tvName = identity.name,
                    tvDeviceId = identity.deviceId,
                    state = advertisement.state,
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
                            // An in-flight server has no committed result; abandon it.
                            pushJob?.cancelAndJoin()
                            when {
                                signedInNames.isNotEmpty() -> {
                                    _status.value = PairingReceiverStatus.Completed(signedInNames.toList())
                                    preserveTerminalStatus = true
                                }
                                // A lone failure keeps its explanation on screen.
                                _status.value is PairingReceiverStatus.Failed -> preserveTerminalStatus = true
                                else -> _status.value = PairingReceiverStatus.Idle
                            }
                            return@collectMessage false
                        }
                        is PairingMessage.Cancel -> {
                            Log.i(TAG, "received pairing cancel")
                            pushJob?.cancelAndJoin()
                            if (signedInNames.isNotEmpty()) {
                                // A peer timeout can race the persistence boundary.
                                // Never discard a sign-in that already committed.
                                _status.value = PairingReceiverStatus.Completed(signedInNames.toList())
                                preserveTerminalStatus = true
                            } else {
                                _status.value = PairingReceiverStatus.Idle
                            }
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
                if (!preserveTerminalStatus) {
                    if (signedInNames.isNotEmpty()) {
                        _status.value = PairingReceiverStatus.Completed(signedInNames.toList())
                        preserveTerminalStatus = true
                    } else if (_status.value is PairingReceiverStatus.Failed) {
                        preserveTerminalStatus = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Transport / decode error: connection dropped mid-session.
            if (signedInNames.isNotEmpty()) {
                _status.value = PairingReceiverStatus.Completed(signedInNames.toList())
                preserveTerminalStatus = true
            }
        } finally {
            pushJob?.cancel()
            pushJob = null
            pendingPush = null
            sessionScope = null
            alternateDecision?.cancel()
            alternateDecision = null
            if (activeTransport === transport) {
                activeTransport = null
            }
            if (!preserveTerminalStatus) {
                _status.value = PairingReceiverStatus.Idle
            }
            transport.close()
        }
    }

    private suspend fun handlePushServer(
        message: PairingMessage.PushServer,
        transport: PairingTransport,
        sessionScope: CoroutineScope,
    ) {
        if (sessionDenied) return // denied: session is tearing down.
        Log.i(TAG, "received pushed server")
        // The protocol is one server at a time: a new push while one is in
        // flight means the phone gave up on the previous server — supersede
        // it, don't ignore the push (silo-apple parity).
        pushJob?.cancelAndJoin()
        pushJob = null
        // tvOS parity: the first push of a session needs the TV user's consent
        // before device-login starts (and before any sign-in code shows). A
        // newer push while consent is pending supersedes the earlier one.
        if (!consented) {
            pendingPush = message
            _status.value = PairingReceiverStatus.ConsentRequested(message.serverURL, message.displayName())
            return
        }
        beginAttempt(message, transport, sessionScope)
    }

    private fun beginAttempt(
        push: PairingMessage.PushServer,
        transport: PairingTransport,
        sessionScope: CoroutineScope,
    ) {
        // "Automatic" only once a sign-in has been COMMITTED: the phone
        // auto-approves only after its user confirmed the first code.
        val automatic = signedInNames.isNotEmpty()
        val ad = advertisement
        pushJob = sessionScope.launch {
            if (ad.state == PairingReceiverState.Login) {
                runLoginAttempt(push, transport, ad)
            } else {
                try {
                    runAttempt(push, transport, automatic)
                } finally {
                    deviceLogin.reset()
                }
            }
        }
    }

    /**
     * `login` mode: only the advertised server is accepted, by the identity
     * the phone pushed (a push without one can't be checked, so it's
     * refused), and the phone approves the code the sign-in screen already
     * shows. That screen polls, saves the session and routes on; this only
     * relays the outcome. No second request is started, so there is nothing
     * of this attempt's own to withdraw.
     */
    private suspend fun runLoginAttempt(
        push: PairingMessage.PushServer,
        transport: PairingTransport,
        ad: PairingAdvertisement,
    ) {
        val pushedUrl = push.serverURL
        val displayName = push.displayName()
        try {
            val source = ad.source ?: throw AttemptFailure(PairingFailureCode.AuthFailed)
            val pushedIdentity = push.serverIdentity?.trim()?.takeIf { it.isNotEmpty() }
            if (ad.serverIdentity == null || pushedIdentity != ad.serverIdentity) {
                throw AttemptFailure(PairingFailureCode.IdentityMismatch)
            }
            _status.value = PairingReceiverStatus.Pairing(pushedUrl, displayName)
            val code = source.codeForNearbyApproval() ?: throw AttemptFailure(PairingFailureCode.Expired)
            _status.value = PairingReceiverStatus.AwaitingApproval(
                serverURL = pushedUrl,
                serverName = displayName,
                userCode = code.userCode,
                matchCode = code.matchCode,
            )
            transport.send(
                PairingMessage.DeviceStarted(serverURL = pushedUrl, userCode = code.userCode, matchCode = code.matchCode),
            )
            Log.i(TAG, "sent device-started for the sign-in screen's code")
            when (val outcome = source.nearbyApprovalOutcome(code.deviceCode)) {
                NearbySignInOutcome.SignedIn -> reportSignedIn(pushedUrl, displayName, transport)
                is NearbySignInOutcome.Failed -> throw AttemptFailure(outcome.code)
            }
        } catch (failure: AttemptFailure) {
            reportFailure(pushedUrl, displayName, failure.code, transport)
        }
    }

    private suspend fun reportSignedIn(pushedUrl: String, displayName: String, transport: PairingTransport) {
        signedInNames += displayName
        _status.value = PairingReceiverStatus.SignedIn(signedInNames.size)
        // Best-effort: the tokens are committed, so a lost confirmation
        // frame must not repaint a real sign-in as a failure.
        runCatching {
            transport.send(
                PairingMessage.ServerResult(
                    serverURL = pushedUrl,
                    status = PairingServerStatus.SignedIn,
                    error = null,
                ),
            )
        }
        Log.i(TAG, "sent signed-in result")
    }

    private suspend fun reportFailure(
        pushedUrl: String,
        displayName: String,
        code: PairingFailureCode,
        transport: PairingTransport,
    ) {
        Log.i(TAG, "server pairing failed: ${code.wire}")
        _status.value = PairingReceiverStatus.Failed(displayName, code)
        runCatching {
            transport.send(
                PairingMessage.ServerResult(
                    serverURL = pushedUrl,
                    status = PairingServerStatus.Failed,
                    error = code.wire,
                ),
            )
        }
    }

    private suspend fun runAttempt(
        push: PairingMessage.PushServer,
        transport: PairingTransport,
        automatic: Boolean,
    ) {
        // Every frame back to the phone names the PUSHED address exactly as
        // sent, whatever address this TV ends up using: phones key their
        // per-server state on the URL they sent.
        val pushedUrl = push.serverURL
        val displayName = push.displayName()
        try {
            _status.value = PairingReceiverStatus.Pairing(pushedUrl, displayName)
            val target = resolveLoginTarget(push, displayName)
            val expectedIdentity = authPort.captureExpectedIdentity()
                ?: throw AttemptFailure(PairingFailureCode.AuthFailed)
            val device = identityProvider()

            val terminal = coroutineScope {
                var deviceStartedSent = false
                val observer = launch {
                    deviceLogin.state.collectMessage { state ->
                        if (state is DeviceLoginRepository.DeviceLoginState.Awaiting && !deviceStartedSent) {
                            deviceStartedSent = true
                            val session = state.session
                            _status.value = PairingReceiverStatus.AwaitingApproval(
                                serverURL = pushedUrl,
                                serverName = displayName,
                                userCode = session.userCode,
                                automatic = automatic,
                                matchCode = session.matchCode,
                            )
                            transport.send(
                                PairingMessage.DeviceStarted(
                                    serverURL = pushedUrl,
                                    userCode = session.userCode,
                                    matchCode = session.matchCode,
                                ),
                            )
                            Log.i(TAG, "sent device-started")
                        }
                        true
                    }
                }
                deviceLogin.begin(
                    serverUrl = target.url,
                    deviceName = device.name,
                    devicePlatform = device.platform,
                )
                observer.cancel()
                deviceLogin.state.value
            }

            val approved = when (terminal) {
                is DeviceLoginRepository.DeviceLoginState.Approved -> terminal.response
                is DeviceLoginRepository.DeviceLoginState.Failed -> throw AttemptFailure(terminal.reason.pairingFailure())
                else -> throw AttemptFailure(PairingFailureCode.AuthFailed)
            }
            persist(approved, target, push, expectedIdentity)
            reportSignedIn(pushedUrl, displayName, transport)
        } catch (failure: AttemptFailure) {
            reportFailure(pushedUrl, displayName, failure.code, transport)
        }
    }

    /**
     * Commit the approved tokens. A failed commit is a failed attempt, and the
     * phone is told so: the TV must never sit on "almost there" while the phone
     * waits for a result that isn't coming (silo-apple#554).
     */
    private suspend fun persist(
        response: DeviceLoginPollResponse,
        target: LoginTarget,
        push: PairingMessage.PushServer,
        expectedIdentity: org.siloserver.silo.network.AccountSessionExpectation,
    ) {
        val accessToken = response.accessToken
        val refreshToken = response.refreshToken
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            throw AttemptFailure(PairingFailureCode.AuthFailed)
        }
        try {
            authPort.persistApprovedSession(
                serverUrl = target.url,
                serverName = push.serverName,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = response.expiresIn ?: 0L,
                expectedIdentity = expectedIdentity,
                verifiedServerId = target.verifiedServerId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "pairing persist failed", e)
            throw AttemptFailure(PairingFailureCode.AuthFailed)
        }
    }

    /**
     * The address to run device authorization at (`setup` mode).
     *
     * With an identity, the pushed address must answer with that
     * identity from this TV. If it doesn't answer at all, the server's other
     * addresses are checked for the same identity and the first match is
     * OFFERED, never taken. An address answering with a different identity is
     * never used. Legacy pushes (no identity) use the pushed address exactly.
     */
    private suspend fun resolveLoginTarget(push: PairingMessage.PushServer, displayName: String): LoginTarget {
        val pushedIdentity = push.serverIdentity?.trim()?.takeIf { it.isNotEmpty() }
        if (pushedIdentity == null) return LoginTarget(push.serverURL, null)

        val pushedUrl = normalizeEndpointUrl(push.serverURL)
        val endpoints = push.endpoints.orEmpty()
        while (true) {
            when (val probe = identityProbe(pushedUrl)) {
                is ServerIdentityProbe.Identity ->
                    if (probe.serverId == pushedIdentity) {
                        return LoginTarget(push.serverURL, pushedIdentity)
                    } else {
                        // The phone verified this identity at this very address;
                        // a different answer from here is not the server it meant.
                        throw AttemptFailure(PairingFailureCode.IdentityMismatch)
                    }
                ServerIdentityProbe.UnsupportedServer -> throw AttemptFailure(PairingFailureCode.IdentityMismatch)
                ServerIdentityProbe.Unreachable -> Unit
            }

            var alternate: PairingEndpoint? = null
            for (endpoint in endpoints) {
                if (endpoint.url == pushedUrl) continue
                val answer = identityProbe(endpoint.url)
                if (answer is ServerIdentityProbe.Identity && answer.serverId == pushedIdentity) {
                    alternate = endpoint
                    break
                }
            }
            val provider = endpoints.firstOrNull { it.kind == PairingEndpoint.Kind.Provider && it.url == pushedUrl }
            val decision = CompletableDeferred<AlternateChoice>()
            alternateDecision = decision
            _status.value = PairingReceiverStatus.Unreachable(
                serverURL = push.serverURL,
                serverName = displayName,
                providerName = provider?.displayName?.takeIf { it.isNotBlank() },
                alternateUrl = alternate?.url,
            )
            val choice = try {
                decision.await()
            } finally {
                alternateDecision = null
            }
            _status.value = PairingReceiverStatus.Pairing(push.serverURL, displayName)
            if (choice == AlternateChoice.UseAlternate && alternate != null) {
                return LoginTarget(alternate.url, pushedIdentity)
            }
        }
    }

    private fun PairingMessage.PushServer.displayName(): String =
        serverName?.takeIf { it.isNotBlank() } ?: normalizeEndpointUrl(serverURL)
}

/** The wire code for a failed device-login attempt. */
internal fun DeviceLoginRepository.FailureReason.pairingFailure(): PairingFailureCode = when (this) {
    DeviceLoginRepository.FailureReason.Denied -> PairingFailureCode.Denied
    DeviceLoginRepository.FailureReason.Expired,
    DeviceLoginRepository.FailureReason.Consumed,
    DeviceLoginRepository.FailureReason.Canceled,
    -> PairingFailureCode.Expired
    DeviceLoginRepository.FailureReason.Unreachable -> PairingFailureCode.Unreachable
    DeviceLoginRepository.FailureReason.UpdateRequired -> PairingFailureCode.UpdateRequired
    DeviceLoginRepository.FailureReason.StartFailed,
    DeviceLoginRepository.FailureReason.RateLimited,
    DeviceLoginRepository.FailureReason.Unsupported,
    DeviceLoginRepository.FailureReason.MissingTokens,
    DeviceLoginRepository.FailureReason.UnknownStatus,
    -> PairingFailureCode.AuthFailed
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
