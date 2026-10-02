package org.siloserver.silo.common.pairing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.pairing.PairingEndpoint
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

    @Test
    fun signedOutTvGetsOnlyItsServerWithIdentityAndNoChooser() = runTest {
        val transport = ScriptedPhoneTransport()
        val home = CompanionPairingServer(id = "srv-1", url = "https://lib.example", displayName = "Home", isActive = true)
        val other = CompanionPairingServer(id = "srv-2", url = "https://other.example", displayName = "Other")
        val identities = mapOf("srv-1" to "S-HOME", "srv-2" to "S-OTHER")
        val endpoints = listOf(PairingEndpoint.of("https://lib.example", PairingEndpoint.Kind.Public))
        val coordinator = CompanionPairingCoordinator(
            serverStore = FakeCompanionServerStore(CompanionPairingServerSnapshot("srv-1", listOf(home, other))),
            deviceLoginApprover = FakeCompanionApprover(
                matchCodes = mapOf("ABCD-0001" to "MATCH-1"),
                onApprove = { transport.signIn("https://other.example") },
            ),
            transportFactory = { transport },
            identitySource = object : CompanionServerIdentitySource {
                override suspend fun identity(server: CompanionPairingServer) = identities[server.id]
                override suspend fun endpoints(server: CompanionPairingServer) = endpoints
            },
        )
        val target = CompanionPairingTarget(
            deviceId = "tv-1", name = "Den TV", host = "127.0.0.1", port = 9999,
            state = "login", serverIdentity = "S-OTHER",
        )
        assertEquals(other, coordinator.serverForSignedOutTv(target))

        var chooserShown = false
        val result = coordinator.pair(target, chooseServers = { chooserShown = true; it })

        assertEquals(CompanionPairingResult.Completed(serverCount = 1), result)
        assertEquals(false, chooserShown)
        val push = transport.sent.filterIsInstance<PairingMessage.PushServer>().single()
        assertEquals("https://other.example", push.serverURL)
        assertEquals("S-OTHER", push.serverIdentity)
        assertEquals(endpoints, push.endpoints)
    }

    @Test
    fun signedOutTvForAServerThisPhoneLacksIsNotOffered() = runTest {
        val coordinator = CompanionPairingCoordinator(
            serverStore = FakeCompanionServerStore(
                CompanionPairingServerSnapshot("srv-1", listOf(CompanionPairingServer("srv-1", "https://lib.example", "Home"))),
            ),
            deviceLoginApprover = FakeCompanionApprover(),
            transportFactory = { error("must not connect") },
            identitySource = object : CompanionServerIdentitySource {
                override suspend fun identity(server: CompanionPairingServer) = "S-HOME"
                override suspend fun endpoints(server: CompanionPairingServer) = null
            },
        )
        val target = CompanionPairingTarget("tv-1", "Den TV", "127.0.0.1", 9999, state = "login", serverIdentity = "S-ELSEWHERE")
        assertEquals(null, coordinator.serverForSignedOutTv(target))
        assertIs<CompanionPairingResult.Failed>(coordinator.pair(target))
        // st filtering: login without srv, and unknown states, are never offered.
        assertEquals(false, target.copy(serverIdentity = null).isOfferable)
        assertEquals(false, target.copy(state = "future").isOfferable)
        assertEquals(true, target.copy(state = null).isOfferable)
        assertEquals(true, target.copy(state = "setup").isOfferable)
    }

    @Test
    fun tvFailureCodeBecomesReadablePhoneCopy() = runTest {
        val transport = object : PairingTransport {
            private val inbound = Channel<PairingMessage>(Channel.UNLIMITED)
            override val incoming: Flow<PairingMessage> = inbound.consumeAsFlow()
            init {
                inbound.trySend(PairingMessage.Hello("Den TV", "tv-1", PairingReceiverState.Setup, listOf(1)))
            }
            override suspend fun send(message: PairingMessage) {
                if (message is PairingMessage.PushServer) {
                    inbound.send(PairingMessage.DeviceStarted(message.serverURL, "ABCD-0001", "MATCH-1"))
                }
            }
            suspend fun fail(url: String) {
                inbound.send(PairingMessage.ServerResult(url, PairingServerStatus.Failed, "unreachable"))
            }
            override fun close() { inbound.close() }
        }
        val server = CompanionPairingServer("srv-1", "https://lib.example", "Home")
        val coordinator = CompanionPairingCoordinator(
            serverStore = FakeCompanionServerStore(CompanionPairingServerSnapshot("srv-1", listOf(server))),
            deviceLoginApprover = FakeCompanionApprover(onApprove = { transport.fail(server.url) }),
            transportFactory = { transport },
        )
        val result = coordinator.pair(CompanionPairingTarget("tv-1", "Den TV", "127.0.0.1", 9999))
        assertEquals(
            CompanionPairingResult.Failed("Den TV can't reach Home. Check the TV's network connection."),
            result,
        )
    }

    /** A TV that refuses the push before starting a sign-in fails the pairing at once, with its reason. */
    @Test
    fun tvRefusingThePushBeforeStartingFailsAtOnceWithItsReason() = runTest {
        val result = pairWithTvThat { inbound, push ->
            inbound.send(PairingMessage.ServerResult(push.serverURL, PairingServerStatus.Failed, "identity_mismatch"))
        }
        assertEquals(
            CompanionPairingResult.Failed("Den TV reached a different server at that address. Nothing was signed in."),
            result,
        )
    }

    @Test
    fun tvDecliningThePushFailsAtOnceInsteadOfSpinning() = runTest {
        // The TV user picks "Don't allow": the TV sends `cancel` and hangs up.
        val result = pairWithTvThat { inbound, _ ->
            inbound.send(PairingMessage.Cancel(reason = "consent_denied"))
            inbound.close()
        }
        assertEquals(CompanionPairingResult.Failed("Den TV didn't allow it."), result)
    }

    @Test
    fun tvHangingUpMidPushFailsAtOnce() = runTest {
        val result = pairWithTvThat { inbound, _ -> inbound.close() }
        assertEquals(CompanionPairingResult.Failed("Den TV closed the connection. Try again."), result)
    }

    @Test
    fun silentTvTimesOutIntoAFailureNotAStuckCard() = runTest {
        val coordinator = arrayOfNulls<CompanionPairingCoordinator>(1)
        val result = pairWithTvThat(onCoordinator = { coordinator[0] = it }) { _, _ -> }
        assertEquals(CompanionPairingResult.Failed("Den TV stopped responding. Try again."), result)
        assertIs<CompanionPairingStatus.Failed>(coordinator[0]?.status?.value)
    }

    /** Pairs with a TV whose reaction to the pushed server is [onPush]. */
    private suspend fun pairWithTvThat(
        onCoordinator: (CompanionPairingCoordinator) -> Unit = {},
        onPush: suspend (Channel<PairingMessage>, PairingMessage.PushServer) -> Unit,
    ): CompanionPairingResult {
        val transport = object : PairingTransport {
            private val inbound = Channel<PairingMessage>(Channel.UNLIMITED)
            override val incoming: Flow<PairingMessage> = inbound.consumeAsFlow()
            init {
                inbound.trySend(PairingMessage.Hello("Den TV", "tv-1", PairingReceiverState.Setup, listOf(1)))
            }
            override suspend fun send(message: PairingMessage) {
                if (message is PairingMessage.PushServer) onPush(inbound, message)
            }
            override fun close() { inbound.close() }
        }
        val server = CompanionPairingServer("srv-1", "https://lib.example", "Home")
        val coordinator = CompanionPairingCoordinator(
            serverStore = FakeCompanionServerStore(CompanionPairingServerSnapshot("srv-1", listOf(server))),
            deviceLoginApprover = FakeCompanionApprover(),
            transportFactory = { transport },
        )
        onCoordinator(coordinator)
        return coordinator.pair(CompanionPairingTarget("tv-1", "Den TV", "127.0.0.1", 9999))
    }
}
