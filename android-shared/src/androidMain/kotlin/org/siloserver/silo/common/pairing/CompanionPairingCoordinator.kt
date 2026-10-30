package org.siloserver.silo.common.pairing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthUnavailableException
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.signedInEntries
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.pairing.PairingEndpoint
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingProtocol
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.ServerIdentityRepository

data class CompanionPairingTarget(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val state: String? = null,
    val version: Int = PairingProtocol.VERSION,
    /** Fresh TXT-record nonce for one TV advertising session. */
    val sessionId: String? = null,
    /** DNS-SD instance name used by onServiceLost; it can differ from the TXT display name. */
    val serviceName: String = name,
    /** TXT `srv`: the deployment identity a signed-out (`st=login`) TV wants. */
    val serverIdentity: String? = null,
    /** Found as `_silopair-roku._tcp`: dialed over plain TCP, never TLS-PSK, and signed in to one server. */
    val isRoku: Boolean = false,
) {
    /** A Roku keeps one server, so setup pushes exactly one (silo-roku `docs/companion-pairing.md` section 4). */
    val keepsOneServer: Boolean get() = isRoku

    /** What the phone calls this device in its copy. */
    val deviceNoun: String get() = if (isRoku) "Roku" else "TV"

    /**
     * A TV on its sign-in screen for one server. Older TVs advertise no `st`
     * (or `setup`); anything that isn't `login` is treated as a setup TV.
     */
    val isSignedOutTv: Boolean get() = state == PairingReceiverState.Login.wire

    /** Only `setup` and `login` TVs are offered; an unknown state is a newer TV this build can't serve. */
    val isOfferable: Boolean
        get() = state == null || state == PairingReceiverState.Setup.wire ||
            (isSignedOutTv && !serverIdentity.isNullOrBlank())
}

data class CompanionPairingServer(
    val id: String,
    val url: String,
    val displayName: String,
    /** The phone's active server, preselected in the chooser. */
    val isActive: Boolean = false,
)

/**
 * What the phone knows about a saved server beyond its URL: the deployment
 * identity verified at that URL and the other addresses it offers
 * (`GET /api/v2/system/connections`). Sent in `pushServer` so the TV can
 * verify the server and reach it at another address (silo-apple parity).
 */
interface CompanionServerIdentitySource {
    /** Verified identity of [server], or null when it can't be verified. */
    suspend fun identity(server: CompanionPairingServer): String?

    /** Addresses [server] offers other devices, or null when unavailable. */
    suspend fun endpoints(server: CompanionPairingServer): List<PairingEndpoint>?

    /** The servers among [servers] whose verified identity is [serverId], in the order given. */
    suspend fun serversWithIdentity(
        servers: List<CompanionPairingServer>,
        serverId: String,
    ): List<CompanionPairingServer> = servers.filter { identity(it) == serverId }
}

/** Production identity source over the saved-server registry. */
class RegistryCompanionServerIdentitySource(
    private val registry: ServerRegistry,
    private val identities: ServerIdentityRepository,
    private val identityApi: ServerIdentityApi,
    private val identityTransitions: IdentityTransitionBarrier,
) : CompanionServerIdentitySource {
    override suspend fun identity(server: CompanionPairingServer): String? {
        val entry = registry.entries.value.firstOrNull { it.id == server.id } ?: return null
        return identities.identityOf(entry)
    }

    /** Recorded identities match without the network; the rest are probed in parallel. */
    override suspend fun serversWithIdentity(
        servers: List<CompanionPairingServer>,
        serverId: String,
    ): List<CompanionPairingServer> {
        val candidates = registry.entries.value.filter { entry -> servers.any { it.id == entry.id } }
        val matched = identities.entriesFor(serverId, candidates).map { it.id }.toSet()
        return servers.filter { it.id in matched }
    }

    override suspend fun endpoints(server: CompanionPairingServer): List<PairingEndpoint>? =
        identityApi.connections(
            AuthScopeSnapshot.profileless(server.id, server.url, identityTransitions.generation.value),
        )?.endpoints?.takeIf { it.isNotEmpty() }
}

data class CompanionPairingServerSnapshot(
    val activeServerId: String?,
    val servers: List<CompanionPairingServer>,
)

/**
 * The first server's confirmation. People compare [userCode], the code the TV
 * shows on screen (from the server's lookup, not the TV's frame). The match
 * words are checked programmatically; [serverMatchCode] is shown only on a
 * secondary line for TV apps released before user codes, which show the
 * words alone. Drop that line on the same schedule as the web `/activate`
 * card's "Older TV apps show ..." line.
 */
data class CompanionPairingApproval(
    val targetName: String,
    val serverName: String,
    val userCode: String,
    val serverMatchCode: String,
    /** The server's host, shown next to its name ("Home (silo.example.com)"). */
    val serverHost: String = "",
    /** The account approving signs the TV in as; null when it couldn't be read. */
    val accountName: String? = null,
)

sealed class CompanionPairingStatus {
    data object Idle : CompanionPairingStatus()
    data class Connecting(val targetName: String) : CompanionPairingStatus()
    data class PickServers(val targetName: String) : CompanionPairingStatus()
    data class PushingServer(val targetName: String, val serverName: String) : CompanionPairingStatus()
    data class AwaitingMatchConfirmation(val approval: CompanionPairingApproval) : CompanionPairingStatus()
    data class Approving(val targetName: String, val serverName: String) : CompanionPairingStatus()
    data class SignedIn(val targetName: String, val serverName: String, val serverCount: Int) : CompanionPairingStatus()
    data class Completed(val targetName: String, val serverNames: List<String>) : CompanionPairingStatus()
    data class Failed(val targetName: String, val message: String) : CompanionPairingStatus()
}

sealed class CompanionPairingResult {
    data class Completed(val serverCount: Int) : CompanionPairingResult()
    data class Failed(val message: String) : CompanionPairingResult()
}

interface CompanionPairingServerStore {
    suspend fun snapshot(): CompanionPairingServerSnapshot
}

interface CompanionDeviceLoginApprover {
    suspend fun lookup(server: CompanionPairingServer, code: String): ApiResult<DeviceLoginLookupResponse>
    suspend fun approve(server: CompanionPairingServer, code: String): ApiResult<DeviceLoginDecisionResponse>
    suspend fun deny(server: CompanionPairingServer, code: String): ApiResult<DeviceLoginDecisionResponse>

    /**
     * The account signed in on [server], which approving signs the TV in as
     * (the approval card names it, like the web `/activate` card and the
     * iPhone app). Null when it can't be read; the card then says "with this
     * account".
     */
    suspend fun accountName(server: CompanionPairingServer): String? = null
}

fun interface CompanionPairingTransportFactory {
    suspend fun connect(target: CompanionPairingTarget): PairingTransport
}

class RegistryCompanionPairingServerStore(
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
) : CompanionPairingServerStore {
    override suspend fun snapshot(): CompanionPairingServerSnapshot {
        val activeId = registry.activeServerId.value
        return CompanionPairingServerSnapshot(
            activeServerId = activeId,
            servers = registry.signedInEntries(tokenManager).map {
                CompanionPairingServer(
                    id = it.id,
                    url = it.url,
                    displayName = it.displayName,
                    isActive = it.id == activeId,
                )
            },
        )
    }
}

class RepositoryCompanionDeviceLoginApprover(
    private val repository: DeviceLoginRepository,
    private val identityTransitions: IdentityTransitionBarrier,
    /** The account on a scope's server, read with that server's own credentials. */
    private val accountNameOf: suspend (AuthScopeSnapshot) -> String? = { null },
) : CompanionDeviceLoginApprover {
    override suspend fun accountName(server: CompanionPairingServer): String? =
        runCatching { accountNameOf(server.profilelessScope()) }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun lookup(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginLookupResponse> = repository.lookup(server.profilelessScope(), code)

    override suspend fun approve(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = repository.approve(server.profilelessScope(), code)

    override suspend fun deny(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> = repository.deny(server.profilelessScope(), code)

    private fun CompanionPairingServer.profilelessScope() =
        AuthScopeSnapshot.profileless(id, url, identityTransitions.generation.value)
}

class CompanionPairingCoordinator(
    private val serverStore: CompanionPairingServerStore,
    private val deviceLoginApprover: CompanionDeviceLoginApprover,
    private val transportFactory: CompanionPairingTransportFactory,
    private val identitySource: CompanionServerIdentitySource? = null,
) {
    private val _status = MutableStateFlow<CompanionPairingStatus>(CompanionPairingStatus.Idle)
    val status: StateFlow<CompanionPairingStatus> = _status.asStateFlow()

    fun reset() {
        _status.value = CompanionPairingStatus.Idle
    }

    /**
     * The saved, signed-in server a signed-out TV ([CompanionPairingTarget.isSignedOutTv])
     * wants, matched by verified identity against its `srv`; null when this
     * phone doesn't hold it and must not offer the TV.
     */
    suspend fun serverForSignedOutTv(target: CompanionPairingTarget): CompanionPairingServer? {
        val wanted = target.serverIdentity?.takeIf { target.isSignedOutTv && it.isNotBlank() } ?: return null
        val source = identitySource ?: return null
        return source.serversWithIdentity(serverStore.snapshot().orderedServers(), wanted).firstOrNull()
    }

    /**
     * [signInServer]: for a signed-out TV, the server [serverForSignedOutTv]
     * already matched when the offer was made; resolved again only when absent.
     */
    suspend fun pair(
        target: CompanionPairingTarget,
        signInServer: CompanionPairingServer? = null,
        chooseServers: suspend (List<CompanionPairingServer>) -> List<CompanionPairingServer> = { it },
        confirmFirstMatch: suspend (CompanionPairingApproval) -> Boolean = { true },
    ): CompanionPairingResult {
        val snapshot = serverStore.snapshot()
        // A signed-out TV wants exactly one server, the one it advertised:
        // skip the chooser and push only that one. It must still be a server
        // this phone is signed in to.
        val signedOutServer = if (target.isSignedOutTv) {
            (signInServer?.takeIf { matched -> snapshot.servers.any { it.id == matched.id } } ?: serverForSignedOutTv(target))
                ?: return fail(target, "This phone isn't signed in to the server ${target.name} uses.")
        } else {
            null
        }
        val orderedServers = signedOutServer?.let(::listOf) ?: snapshot.orderedServers()
        if (orderedServers.isEmpty()) {
            return fail(target, "No saved Silo servers are available to send.")
        }

        var signedInCount = 0
        val signedInNames = mutableListOf<String>()
        var transport: PairingTransport? = null
        try {
            _status.value = CompanionPairingStatus.Connecting(target.name)
            Log.i(
                TAG,
                "Connecting to ${target.name} at ${target.host}:${target.port} " +
                    "(session=${target.sessionId ?: "unknown"})",
            )
            val connected = transportFactory.connect(target).also { transport = it }
            coroutineScope {
                val inbox = Channel<PairingMessage>(Channel.UNLIMITED)
                val peer = PeerState()
                val collector = launch {
                    try {
                        connected.incoming.collect {
                            peer.observe(it)
                            inbox.send(it)
                        }
                    } finally {
                        // The TV hung up (or the read failed): wake any wait
                        // for its next message instead of sitting on it until
                        // the step's timeout.
                        peer.ended()
                        inbox.close()
                    }
                }
                try {
                    awaitCompatibleHello(inbox)
                    _status.value = CompanionPairingStatus.PickServers(target.name)
                    val selectedServers = (if (signedOutServer != null) orderedServers else chooseServers(orderedServers))
                        .filter { selected -> orderedServers.any { it.id == selected.id } }
                        // A Roku refuses a second push once one has signed in.
                        .let { if (target.keepsOneServer) it.take(1) else it }
                    if (selectedServers.isEmpty()) {
                        error("Choose at least one server to continue.")
                    }

                    selectedServers.forEachIndexed { index, server ->
                        pushAndApproveServer(
                            target = target,
                            server = server,
                            transport = connected,
                            inbox = inbox,
                            peer = peer,
                            requireUserConfirmation = index == 0,
                            confirmFirstMatch = confirmFirstMatch,
                        )
                        signedInCount += 1
                        signedInNames += server.displayName
                        _status.value = CompanionPairingStatus.SignedIn(
                            targetName = target.name,
                            serverName = server.displayName,
                            serverCount = signedInCount,
                        )
                    }
                    connected.send(PairingMessage.Done)
                } finally {
                    // The TLS input stream performs a blocking socket read. Cancelling its
                    // collector alone cannot interrupt that read, so close the transport
                    // before joining or successful pairing can remain stuck in cleanup.
                    withContext(NonCancellable) {
                        collector.cancel()
                        val toClose = transport
                        transport = null
                        withContext(Dispatchers.IO) { toClose?.close() }
                        collector.join()
                        inbox.close()
                    }
                }
            }
            _status.value = CompanionPairingStatus.Completed(
                targetName = target.name,
                serverNames = signedInNames.toList(),
            )
            return CompanionPairingResult.Completed(signedInCount)
        } catch (e: TimeoutCancellationException) {
            // A step timeout is a failure, not a cancellation of the caller:
            // rethrowing it left the card spinning on its progress step.
            Log.w(TAG, "Companion setup timed out for ${target.name}", e)
            return fail(target, "${target.name} stopped responding. Try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Companion setup failed for ${target.name}", t)
            val message = when (t) {
                is PeerCancelledException -> if (t.reason == CONSENT_DENIED_REASON) {
                    "${target.name} didn't allow it."
                } else {
                    "${target.name} cancelled."
                }
                is ClosedReceiveChannelException -> "${target.name} closed the connection. Try again."
                else -> t.userFacingPairingMessage(target)
            }
            _status.value = CompanionPairingStatus.Failed(target.name, message)
            return CompanionPairingResult.Failed(message)
        } finally {
            val toClose = transport
            transport = null
            withContext(NonCancellable + Dispatchers.IO) {
                toClose?.close()
            }
        }
    }

    /** What the phone says when the TV reports [this] failure for [serverName]. */
    private fun PairingFailureCode.phoneMessage(target: CompanionPairingTarget, serverName: String): String {
        val tvName = target.name
        return when (this) {
            PairingFailureCode.Denied -> "The sign-in was declined."
            PairingFailureCode.Expired -> "The code on $tvName expired. Try again."
            PairingFailureCode.Unreachable ->
                "$tvName can't reach $serverName. Check the ${target.deviceNoun}'s network connection."
            PairingFailureCode.IdentityMismatch -> "$tvName reached a different server at that address. Nothing was signed in."
            PairingFailureCode.UpdateRequired -> "$serverName or the Silo app on $tvName needs an update first."
            PairingFailureCode.AuthFailed -> "$tvName couldn't finish signing in to $serverName."
        }
    }

    private fun Throwable.userFacingPairingMessage(target: CompanionPairingTarget): String {
        val detail = generateSequence(this) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
        return detail ?: "Couldn't connect to the ${target.deviceNoun}. Keep its setup screen open and try again."
    }

    private suspend fun pushAndApproveServer(
        target: CompanionPairingTarget,
        server: CompanionPairingServer,
        transport: PairingTransport,
        inbox: ReceiveChannel<PairingMessage>,
        peer: PeerState,
        requireUserConfirmation: Boolean,
        confirmFirstMatch: suspend (CompanionPairingApproval) -> Boolean,
    ) {
        // A signed-out TV keeps its sign-in request after it cancels this
        // attempt, so once it has ended the attempt (cancelled, hung up, or
        // reported this server failed) the phone must not approve or deny
        // that request. Checked before every decision.
        fun ensureAttemptLive() {
            peer.interruption(server.url)?.let { throw it.failure(target, server.displayName) }
        }

        _status.value = CompanionPairingStatus.PushingServer(target.name, server.displayName)
        transport.send(
            PairingMessage.PushServer(
                serverURL = server.url,
                serverName = server.displayName,
                serverIdentity = identitySource?.identity(server),
                endpoints = identitySource?.endpoints(server),
            ),
        )

        // A TV can refuse the push before it starts a sign-in (a different
        // server at that address, an expired code): it answers with the
        // failed result instead, and the phone reports it at once.
        val started = when (
            val first = inbox.receiveFirst<PairingMessage>(timeoutMs = deviceStartedTimeoutMs(requireUserConfirmation)) {
                (it is PairingMessage.DeviceStarted && it.serverURL == server.url) ||
                    (it is PairingMessage.ServerResult && it.serverURL == server.url)
            }
        ) {
            is PairingMessage.DeviceStarted -> first
            is PairingMessage.ServerResult ->
                error(PairingFailureCode.fromWire(first.error).phoneMessage(target, server.displayName))
            else -> error("Unexpected TV setup message.")
        }
        val lookup = when (val result = deviceLoginApprover.lookup(server, started.userCode)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> error(approverFailure(result, "Unable to verify TV setup code."))
            is ApiResult.NetworkError -> error(approverFailure(result, "Unable to verify TV setup code."))
        }
        val serverMatchCode = lookup.matchCode?.takeIf { it.isNotBlank() }
            ?: error("Server did not return a match code for TV setup.")
        ensureAttemptLive()
        if (serverMatchCode != started.matchCode) {
            deviceLoginApprover.deny(server, started.userCode)
            transport.send(PairingMessage.Cancel(reason = "match-code-mismatch"))
            error("The codes didn't match. Nothing was approved.")
        }

        val approval = CompanionPairingApproval(
            targetName = target.name,
            serverName = server.displayName,
            userCode = lookup.userCode?.takeIf { it.isNotBlank() } ?: started.userCode,
            serverMatchCode = serverMatchCode,
            serverHost = DeviceCodeFormat.host(server.url),
        )
        if (requireUserConfirmation) {
            // The card names the account approving signs the TV in as, and
            // nothing is approved for an account it couldn't name. The TV
            // keeps its code, so "Try again" can pick it up.
            val accountName = deviceLoginApprover.accountName(server)
            ensureAttemptLive()
            if (accountName.isNullOrBlank()) {
                transport.send(PairingMessage.Cancel(reason = "account-unknown"))
                error("Couldn't confirm which account would sign in on ${target.name}. Try again.")
            }
            val shown = approval.copy(accountName = accountName)
            _status.value = CompanionPairingStatus.AwaitingMatchConfirmation(shown)
            // The TV can end the attempt while the person compares codes:
            // the card's buttons go away at once instead of acting on it.
            val confirmed = coroutineScope {
                val answer = async { confirmFirstMatch(shown) }
                val interrupted = async { peer.awaitInterruption(server.url) }
                select<Unit> {
                    answer.onAwait {}
                    interrupted.onAwait {}
                }
                interrupted.cancel()
                // Also wins when both finished together; leaving this scope
                // by throwing cancels the pending answer.
                ensureAttemptLive()
                answer.await()
            }
            if (!confirmed) {
                // Leaving the scope above waits for the cancelled watcher,
                // and the TV can end the attempt in that time.
                ensureAttemptLive()
                deviceLoginApprover.deny(server, started.userCode)
                transport.send(PairingMessage.Cancel(reason = "user-cancelled"))
                error("Cancelled. Nothing was approved.")
            }
        }

        ensureAttemptLive()
        _status.value = CompanionPairingStatus.Approving(target.name, server.displayName)
        when (val approved = deviceLoginApprover.approve(server, started.userCode)) {
            is ApiResult.Success -> Unit
            is ApiResult.Error -> error(approverFailure(approved, "Unable to approve TV setup."))
            is ApiResult.NetworkError -> error(approverFailure(approved, "Unable to approve TV setup."))
        }
        val result = inbox.receiveFirst<PairingMessage.ServerResult>(
            timeoutMs = SERVER_RESULT_TIMEOUT_MS,
        ) { it.serverURL == server.url }
        if (result.status != PairingServerStatus.SignedIn) {
            error(PairingFailureCode.fromWire(result.error).phoneMessage(target, server.displayName))
        }
    }

    private suspend fun awaitCompatibleHello(inbox: ReceiveChannel<PairingMessage>) {
        val hello = inbox.receiveFirst<PairingMessage.Hello>(timeoutMs = HELLO_TIMEOUT_MS)
        if (PairingProtocol.VERSION !in hello.supportedVersions) {
            error("TV setup protocol is not compatible.")
        }
    }

    private suspend inline fun <reified T : PairingMessage> ReceiveChannel<PairingMessage>.receiveFirst(
        timeoutMs: Long,
        crossinline predicate: (T) -> Boolean = { true },
    ): T = withTimeout<T>(timeoutMs) {
        var found: T? = null
        while (found == null) {
            val message = receive()
            // The TV withdrew (its user declined, or it left the screen):
            // nothing it would have sent next is coming.
            if (message is PairingMessage.Cancel && T::class != PairingMessage.Cancel::class) {
                throw PeerCancelledException(message.reason)
            }
            if (message is T && predicate(message)) {
                found = message
            }
        }
        found
    }

    private fun CompanionPairingServerSnapshot.orderedServers(): List<CompanionPairingServer> {
        val activeId = activeServerId
        return servers
            .filter { it.url.isNotBlank() }
            .sortedByDescending { it.id == activeId }
    }

    private fun fail(target: CompanionPairingTarget, message: String): CompanionPairingResult.Failed {
        _status.value = CompanionPairingStatus.Failed(target.name, message)
        return CompanionPairingResult.Failed(message)
    }

    private fun deviceStartedTimeoutMs(isFirstServer: Boolean): Long =
        if (isFirstServer) FIRST_DEVICE_STARTED_TIMEOUT_MS else NEXT_DEVICE_STARTED_TIMEOUT_MS

    /** The TV sent `cancel` while the phone waited for its next message. */
    private class PeerCancelledException(val reason: String) : IllegalStateException("peer cancelled: $reason")

    /** What ended the TV's side of a server's attempt. */
    private sealed class PeerInterruption {
        data class Cancelled(val reason: String) : PeerInterruption()
        data object Closed : PeerInterruption()
        data class Failed(val error: String?) : PeerInterruption()
    }

    /**
     * Records, as the TV's messages arrive, anything that ends an attempt: a
     * `cancel`, the connection closing, or a failed result for a server. The
     * inbox only shows these when the phone next reads it, which is after
     * the decision it would have stopped.
     */
    private class PeerState {
        private val ended = MutableStateFlow<PeerInterruption?>(null)
        private val failed = MutableStateFlow<Map<String, PeerInterruption.Failed>>(emptyMap())

        fun observe(message: PairingMessage) {
            when (message) {
                is PairingMessage.Cancel -> ended.compareAndSet(null, PeerInterruption.Cancelled(message.reason))
                is PairingMessage.ServerResult -> if (message.status != PairingServerStatus.SignedIn) {
                    failed.update { it + (message.serverURL to PeerInterruption.Failed(message.error)) }
                }
                else -> Unit
            }
        }

        fun ended() {
            ended.compareAndSet(null, PeerInterruption.Closed)
        }

        /** Why [serverURL]'s attempt is over, or null while it is live. */
        fun interruption(serverURL: String): PeerInterruption? = ended.value ?: failed.value[serverURL]

        suspend fun awaitInterruption(serverURL: String): PeerInterruption =
            combine(ended, failed) { end, failures -> end ?: failures[serverURL] }.filterNotNull().first()
    }

    /** The failure the phone reports for [this], phrased as when it's read from the inbox. */
    private fun PeerInterruption.failure(target: CompanionPairingTarget, serverName: String): Throwable = when (this) {
        is PeerInterruption.Cancelled -> PeerCancelledException(reason)
        PeerInterruption.Closed -> ClosedReceiveChannelException("${target.name} closed the connection")
        is PeerInterruption.Failed -> IllegalStateException(PairingFailureCode.fromWire(error).phoneMessage(target, serverName))
    }

    private companion object {
        const val TAG = "CompanionPairing"
        /** [PairingReceiver.denyPendingServer]'s reason when the TV user declines the push. */
        const val CONSENT_DENIED_REASON = "consent_denied"
        const val HELLO_TIMEOUT_MS = 15_000L
        const val FIRST_DEVICE_STARTED_TIMEOUT_MS = 90_000L
        const val NEXT_DEVICE_STARTED_TIMEOUT_MS = 30_000L
        const val SERVER_RESULT_TIMEOUT_MS = 30_000L
    }
}

/**
 * The phone's text for a lookup or approval that failed. The sign-in
 * provider being unreachable (503 `provider_unavailable` on the approver's
 * refresh or answer) keeps this phone's session, so it says so instead of a
 * raw reason.
 */
internal fun approverFailure(result: ApiResult.Error, fallback: String): String =
    if (result.code == 503 && result.error == SiloAuthUnavailableException.PROVIDER_UNAVAILABLE_PROBLEM) {
        APPROVER_PROVIDER_UNAVAILABLE
    } else {
        result.message.ifBlank { fallback }
    }

internal fun approverFailure(result: ApiResult.NetworkError, fallback: String): String = when {
    SiloAuthUnavailableException.isProviderUnavailable(result.exception) -> APPROVER_PROVIDER_UNAVAILABLE
    result.exception is SiloAuthUnavailableException -> fallback
    else -> result.exception.message ?: fallback
}

internal const val APPROVER_PROVIDER_UNAVAILABLE =
    "The sign-in provider can't be reached right now, so this device couldn't confirm your account. " +
        "You're still signed in. Try again in a moment."
