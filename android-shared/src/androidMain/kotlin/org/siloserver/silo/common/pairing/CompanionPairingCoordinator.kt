package org.siloserver.silo.common.pairing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingProtocol
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.repository.DeviceLoginRepository

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
)

data class CompanionPairingServer(
    val id: String,
    val url: String,
    val displayName: String,
)

data class CompanionPairingServerSnapshot(
    val activeServerId: String?,
    val servers: List<CompanionPairingServer>,
)

data class CompanionPairingApproval(
    val targetName: String,
    val serverName: String,
    val userCode: String,
    val tvMatchCode: String,
    val serverMatchCode: String,
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
}

fun interface CompanionPairingTransportFactory {
    suspend fun connect(target: CompanionPairingTarget): PairingTransport
}

class RegistryCompanionPairingServerStore(
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
) : CompanionPairingServerStore {
    override suspend fun snapshot(): CompanionPairingServerSnapshot {
        val signedInServers = buildList {
            for (entry in registry.entries.value) {
                if (entry.url.isBlank()) continue
                if (tokenManager.getAccessTokenForScope(entry.id).isNullOrBlank()) continue
                add(entry)
            }
        }
        return CompanionPairingServerSnapshot(
            activeServerId = registry.activeServerId.value,
            servers = signedInServers
                .sortedWith(
                    compareByDescending<org.siloserver.silo.model.server.ServerEntry> {
                        it.id == registry.activeServerId.value
                    }.thenByDescending { it.lastUsedAtEpochMs },
                )
                .map {
                    CompanionPairingServer(
                        id = it.id,
                        url = it.url,
                        displayName = it.displayName,
                    )
                },
        )
    }

}

class RepositoryCompanionDeviceLoginApprover(
    private val repository: DeviceLoginRepository,
    private val identityTransitions: IdentityTransitionBarrier,
) : CompanionDeviceLoginApprover {
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

    private fun CompanionPairingServer.profilelessScope() = AuthScopeSnapshot(
        serverId = id,
        serverUrl = url,
        profileId = null,
        profileToken = null,
        // This inactive-server scope cannot carry the token manager's live
        // persistent credential epoch. Pin its request to the current identity
        // generation instead so a late refresh cannot overwrite a same-server
        // account replacement.
        identityGeneration = identityTransitions.generation.value,
        isIdentityGenerationStamped = true,
    )
}

class CompanionPairingCoordinator(
    private val serverStore: CompanionPairingServerStore,
    private val deviceLoginApprover: CompanionDeviceLoginApprover,
    private val transportFactory: CompanionPairingTransportFactory,
) {
    private val _status = MutableStateFlow<CompanionPairingStatus>(CompanionPairingStatus.Idle)
    val status: StateFlow<CompanionPairingStatus> = _status.asStateFlow()

    fun reset() {
        _status.value = CompanionPairingStatus.Idle
    }

    suspend fun pair(
        target: CompanionPairingTarget,
        chooseServers: suspend (List<CompanionPairingServer>) -> List<CompanionPairingServer> = { it },
        confirmFirstMatch: suspend (CompanionPairingApproval) -> Boolean = { true },
    ): CompanionPairingResult {
        val snapshot = serverStore.snapshot()
        val orderedServers = snapshot.orderedServers()
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
                val collector = launch {
                    connected.incoming.collect { inbox.send(it) }
                }
                try {
                    awaitCompatibleHello(inbox)
                    _status.value = CompanionPairingStatus.PickServers(target.name)
                    val selectedServers = chooseServers(orderedServers)
                        .filter { selected -> orderedServers.any { it.id == selected.id } }
                    if (selectedServers.isEmpty()) {
                        error("Choose at least one server to continue.")
                    }

                    selectedServers.forEachIndexed { index, server ->
                        pushAndApproveServer(
                            target = target,
                            server = server,
                            transport = connected,
                            inbox = inbox,
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Companion setup failed for ${target.name}", t)
            val message = t.userFacingPairingMessage()
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

    private fun Throwable.userFacingPairingMessage(): String {
        val detail = generateSequence(this) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
        return detail ?: "Couldn't connect to the TV. Keep its setup screen open and try again."
    }

    private suspend fun pushAndApproveServer(
        target: CompanionPairingTarget,
        server: CompanionPairingServer,
        transport: PairingTransport,
        inbox: ReceiveChannel<PairingMessage>,
        requireUserConfirmation: Boolean,
        confirmFirstMatch: suspend (CompanionPairingApproval) -> Boolean,
    ) {
        _status.value = CompanionPairingStatus.PushingServer(target.name, server.displayName)
        transport.send(PairingMessage.PushServer(serverURL = server.url, serverName = server.displayName))

        val started = inbox.receiveFirst<PairingMessage.DeviceStarted>(
            timeoutMs = deviceStartedTimeoutMs(requireUserConfirmation),
        ) { it.serverURL == server.url }
        val lookup = when (val result = deviceLoginApprover.lookup(server, started.userCode)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> error(result.message.ifBlank { "Unable to verify TV setup code." })
            is ApiResult.NetworkError -> error(result.exception.message ?: "Unable to verify TV setup code.")
        }
        val serverMatchCode = lookup.matchCode?.takeIf { it.isNotBlank() }
            ?: error("Server did not return a match code for TV setup.")
        if (serverMatchCode != started.matchCode) {
            deviceLoginApprover.deny(server, started.userCode)
            transport.send(PairingMessage.Cancel(reason = "match-code-mismatch"))
            error("TV setup code mismatch. Nothing was approved.")
        }

        val approval = CompanionPairingApproval(
            targetName = target.name,
            serverName = server.displayName,
            userCode = started.userCode,
            tvMatchCode = started.matchCode,
            serverMatchCode = serverMatchCode,
        )
        if (requireUserConfirmation) {
            _status.value = CompanionPairingStatus.AwaitingMatchConfirmation(approval)
            if (!confirmFirstMatch(approval)) {
                deviceLoginApprover.deny(server, started.userCode)
                transport.send(PairingMessage.Cancel(reason = "user-cancelled"))
                error("TV setup was cancelled.")
            }
        }

        _status.value = CompanionPairingStatus.Approving(target.name, server.displayName)
        when (val approved = deviceLoginApprover.approve(server, started.userCode)) {
            is ApiResult.Success -> Unit
            is ApiResult.Error -> error(approved.message.ifBlank { "Unable to approve TV setup." })
            is ApiResult.NetworkError -> error(approved.exception.message ?: "Unable to approve TV setup.")
        }
        val result = inbox.receiveFirst<PairingMessage.ServerResult>(
            timeoutMs = SERVER_RESULT_TIMEOUT_MS,
        ) { it.serverURL == server.url }
        if (result.status != PairingServerStatus.SignedIn) {
            error(result.error ?: "The TV could not finish signing in.")
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

    private companion object {
        const val TAG = "CompanionPairing"
        const val HELLO_TIMEOUT_MS = 15_000L
        const val FIRST_DEVICE_STARTED_TIMEOUT_MS = 90_000L
        const val NEXT_DEVICE_STARTED_TIMEOUT_MS = 30_000L
        const val SERVER_RESULT_TIMEOUT_MS = 30_000L
    }
}
