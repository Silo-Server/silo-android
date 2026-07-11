package org.siloserver.silo.common.pairing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingServerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedPhoneTransport : PairingTransport {
    private val inbound = Channel<PairingMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<PairingMessage>()
    private var nextCode = 1
    var closed = false
        private set

    override val incoming: Flow<PairingMessage> = inbound.consumeAsFlow()

    init {
        inbound.trySend(
            PairingMessage.Hello(
                tvName = "Living Room",
                tvDeviceId = "tv-1",
                state = PairingReceiverState.Setup,
                supportedVersions = listOf(1),
            ),
        )
    }

    override suspend fun send(message: PairingMessage) {
        sent += message
        if (message is PairingMessage.PushServer) {
            val code = "ABCD-${nextCode.toString().padStart(4, '0')}"
            val matchCode = "MATCH-$nextCode"
            nextCode += 1
            inbound.send(
                PairingMessage.DeviceStarted(
                    serverURL = message.serverURL,
                    userCode = code,
                    matchCode = matchCode,
                ),
            )
        }
    }

    suspend fun signIn(serverURL: String) {
        inbound.send(
            PairingMessage.ServerResult(
                serverURL = serverURL,
                status = PairingServerStatus.SignedIn,
                error = null,
            ),
        )
    }

    override fun close() {
        closed = true
        inbound.close()
    }
}

private class FakeCompanionApprover(
    private val matchCodes: Map<String, String> = mapOf("ABCD-0001" to "MATCH-1"),
    private val onApprove: suspend (String) -> Unit = {},
) : CompanionDeviceLoginApprover {
    val lookups = mutableListOf<String>()
    val approvals = mutableListOf<String>()
    val denials = mutableListOf<String>()

    override suspend fun lookup(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginLookupResponse> {
        lookups += code
        return ApiResult.Success(
            DeviceLoginLookupResponse(
                status = "pending",
                userCode = code,
                matchCode = matchCodes[code],
                deviceName = "Living Room",
                devicePlatform = "Android TV",
            ),
        )
    }

    override suspend fun approve(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> {
        approvals += code
        onApprove(code)
        return ApiResult.Success(DeviceLoginDecisionResponse(status = "approved"))
    }

    override suspend fun deny(
        server: CompanionPairingServer,
        code: String,
    ): ApiResult<DeviceLoginDecisionResponse> {
        denials += code
        return ApiResult.Success(DeviceLoginDecisionResponse(status = "denied"))
    }
}

private class FakeCompanionServerStore(
    private val snapshot: CompanionPairingServerSnapshot,
) : CompanionPairingServerStore {
    override suspend fun snapshot(): CompanionPairingServerSnapshot = snapshot
}

class CompanionPairingCoordinatorTest {
    @Test
    fun activeServerIsPushedAndApprovedWithoutChangingPhoneScope() = runTest {
        val transport = ScriptedPhoneTransport()
        val server = CompanionPairingServer(
            id = "srv-1",
            url = "https://lib.example",
            displayName = "Home",
        )
        val store = FakeCompanionServerStore(
            CompanionPairingServerSnapshot(
                activeServerId = "srv-1",
                servers = listOf(server),
            ),
        )
        val approver = FakeCompanionApprover(
            onApprove = { transport.signIn(server.url) },
        )
        val coordinator = CompanionPairingCoordinator(
            serverStore = store,
            deviceLoginApprover = approver,
            transportFactory = { transport },
        )

        val result = coordinator.pair(target = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", 9999))

        assertEquals(CompanionPairingResult.Completed(serverCount = 1), result)
        assertEquals(listOf("ABCD-0001"), approver.lookups)
        assertEquals(listOf("ABCD-0001"), approver.approvals)
        assertTrue(approver.denials.isEmpty())
        assertIs<PairingMessage.PushServer>(transport.sent.first())
        assertIs<PairingMessage.Done>(transport.sent.last())
        assertTrue(transport.closed)
    }

    @Test
    fun multiServerPairingConfirmsFirstMatchThenAutoApprovesWithoutChangingPhoneScope() = runTest {
        val transport = ScriptedPhoneTransport()
        val servers = listOf(
            CompanionPairingServer(
                id = "srv-1",
                url = "https://primary.example",
                displayName = "Primary",
            ),
            CompanionPairingServer(
                id = "srv-2",
                url = "https://secondary.example",
                displayName = "Secondary",
            ),
        )
        val store = FakeCompanionServerStore(
            CompanionPairingServerSnapshot(
                activeServerId = "srv-2",
                servers = servers,
            ),
        )
        val approver = FakeCompanionApprover(
            matchCodes = mapOf(
                "ABCD-0001" to "MATCH-1",
                "ABCD-0002" to "MATCH-2",
            ),
            onApprove = { code ->
                val serverUrl = when (code) {
                    "ABCD-0001" -> "https://secondary.example"
                    "ABCD-0002" -> "https://primary.example"
                    else -> error("Unexpected code $code")
                }
                transport.signIn(serverUrl)
            },
        )
        val coordinator = CompanionPairingCoordinator(
            serverStore = store,
            deviceLoginApprover = approver,
            transportFactory = { transport },
        )
        val confirmations = mutableListOf<CompanionPairingApproval>()

        val result = coordinator.pair(
            target = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", 9999),
            confirmFirstMatch = {
                confirmations += it
                true
            },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 2), result)
        assertEquals(listOf("ABCD-0001", "ABCD-0002"), approver.lookups)
        assertEquals(listOf("ABCD-0001", "ABCD-0002"), approver.approvals)
        assertEquals(listOf("Secondary"), confirmations.map { it.serverName })
        assertTrue(approver.denials.isEmpty())
        assertEquals(
            listOf(
                "https://secondary.example",
                "https://primary.example",
            ),
            transport.sent.filterIsInstance<PairingMessage.PushServer>().map { it.serverURL },
        )
        assertIs<PairingMessage.Done>(transport.sent.last())
    }

    @Test
    fun onlyUserSelectedServersAreSentToTv() = runTest {
        val transport = ScriptedPhoneTransport()
        val selected = CompanionPairingServer(
            id = "srv-selected",
            url = "https://selected.example",
            displayName = "Selected",
        )
        val skipped = CompanionPairingServer(
            id = "srv-skipped",
            url = "https://skipped.example",
            displayName = "Skipped",
        )
        val coordinator = CompanionPairingCoordinator(
            serverStore = FakeCompanionServerStore(
                CompanionPairingServerSnapshot(
                    activeServerId = skipped.id,
                    servers = listOf(selected, skipped),
                ),
            ),
            deviceLoginApprover = FakeCompanionApprover(
                onApprove = { transport.signIn(selected.url) },
            ),
            transportFactory = { transport },
        )

        val result = coordinator.pair(
            target = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", 9999),
            chooseServers = { choices -> choices.filter { it.id == selected.id } },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 1), result)
        assertEquals(
            listOf(selected.url),
            transport.sent.filterIsInstance<PairingMessage.PushServer>().map { it.serverURL },
        )
        assertEquals(
            CompanionPairingStatus.Completed("Living Room", listOf("Selected")),
            coordinator.status.value,
        )
    }
}
