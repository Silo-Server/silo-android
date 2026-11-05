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

/**
 * A TV that answers every push with a new code (ABCD-0001, ABCD-0002, ...)
 * whose match words are MATCH-n, and signs in when told to. [refuse] makes it
 * refuse the nth push with a failure code.
 */
private class MultiPushTv(
    private val refuse: Map<Int, String> = emptyMap(),
    state: PairingReceiverState = PairingReceiverState.Setup,
) : PairingTransport {
    private val inbound = Channel<PairingMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<PairingMessage>()
    private var pushes = 0

    /** URL of each code handed out. */
    val urlOfCode = mutableMapOf<String, String>()

    override val incoming: Flow<PairingMessage> = inbound.consumeAsFlow()

    init {
        inbound.trySend(PairingMessage.Hello("Living Room", "tv-1", state, listOf(1)))
    }

    override suspend fun send(message: PairingMessage) {
        sent += message
        if (message is PairingMessage.PushServer) {
            pushes += 1
            val refusal = refuse[pushes]
            if (refusal != null) {
                inbound.send(PairingMessage.ServerResult(message.serverURL, PairingServerStatus.Failed, refusal))
                return
            }
            val code = "ABCD-${pushes.toString().padStart(4, '0')}"
            urlOfCode[code] = message.serverURL
            inbound.send(PairingMessage.DeviceStarted(message.serverURL, code, "MATCH-$pushes"))
        }
    }

    suspend fun signIn(code: String) {
        inbound.send(PairingMessage.ServerResult(urlOfCode.getValue(code), PairingServerStatus.SignedIn, null))
    }

    override fun close() {
        inbound.close()
    }

    val pushed: List<PairingMessage.PushServer> get() = sent.filterIsInstance<PairingMessage.PushServer>()
}

/** Looks codes up with their MATCH-n words and the given bindings; approving signs the TV in. */
private class AccountApprover(
    private val tv: MultiPushTv,
    private val bindings: Map<String, String> = emptyMap(),
    private val failApprove: Set<String> = emptySet(),
) : CompanionDeviceLoginApprover {
    val approvedWith = mutableListOf<Pair<String, String>>()

    override suspend fun accountName(destination: CompanionPairingDestination): String? = destination.account.username

    override suspend fun lookup(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginLookupResponse> =
        ApiResult.Success(
            DeviceLoginLookupResponse(
                status = "pending",
                userCode = code,
                matchCode = "MATCH-${code.takeLast(4).toInt()}",
                binding = bindings[code],
            ),
        )

    override suspend fun approve(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginDecisionResponse> {
        if (code in failApprove) return ApiResult.Error(500, "boom", "boom")
        approvedWith += code to destination.key
        tv.signIn(code)
        return ApiResult.Success(DeviceLoginDecisionResponse("approved"))
    }

    override suspend fun deny(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginDecisionResponse> =
        ApiResult.Success(DeviceLoginDecisionResponse("denied"))
}

class CompanionPairingMultiAccountTest {
    private val home = CompanionPairingServer(
        id = "home",
        url = "https://home.example",
        displayName = "Home",
        accounts = listOf(
            CompanionPairingAccount("alex", "alex", isCurrent = true),
            CompanionPairingAccount("sam", "sam"),
        ),
    )
    private val lake = CompanionPairingServer(
        id = "lake",
        url = "https://lake.example",
        displayName = "Lake House",
        accounts = listOf(
            CompanionPairingAccount("alex-lake", "alex", isCurrent = true),
            CompanionPairingAccount("kim", "kim", needsSignIn = true),
        ),
    )
    private val acctTv = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", 9999, acceptsAccounts = true)

    private fun coordinator(tv: MultiPushTv, approver: CompanionDeviceLoginApprover, active: String = "lake") =
        CompanionPairingCoordinator(
            serverStore = object : CompanionPairingServerStore {
                override suspend fun snapshot() = CompanionPairingServerSnapshot(active, listOf(home, lake))
            },
            deviceLoginApprover = approver,
            transportFactory = { tv },
        )

    private fun key(server: CompanionPairingServer, accountId: String) =
        CompanionPairingDestination.destinationKey(server.id, accountId)

    /**
     * Each chosen account is approved with its own credentials and named in
     * its push. The phone's own account goes first, then list order. With no
     * binding anywhere, every target is compared on its own.
     */
    @Test
    fun everyChosenAccountIsSentInOrderAndApprovedAsItself() = runTest {
        val tv = MultiPushTv()
        val approver = AccountApprover(tv)
        val compared = mutableListOf<CompanionPairingApproval>()
        val coordinator = coordinator(tv, approver)

        val result = coordinator.pair(
            target = acctTv,
            chooseAccounts = { setOf(key(home, "sam"), key(home, "alex"), key(lake, "alex-lake")) },
            confirmMatch = { compared += it; true },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 3), result)
        // Lake House is the phone's active server: its account in use goes first.
        assertEquals(
            listOf("https://lake.example" to "alex", "https://home.example" to "alex", "https://home.example" to "sam"),
            tv.pushed.map { it.serverURL to it.accountName },
        )
        assertEquals(
            listOf("ABCD-0001" to key(lake, "alex-lake"), "ABCD-0002" to key(home, "alex"), "ABCD-0003" to key(home, "sam")),
            approver.approvedWith,
        )
        assertEquals(listOf(false, true, true), compared.map { it.isFollowUp })
        assertEquals(listOf("alex", "alex", "sam"), compared.map { it.accountName })
        assertIs<PairingMessage.Done>(tv.sent.last())
        val completed = assertIs<CompanionPairingStatus.Completed>(coordinator.status.value)
        assertEquals(listOf("Lake House — alex", "Home — alex, sam"), companionSummaryLines(completed.outcomes))
    }

    /** One comparison covers every later target whose lookup carries the same binding. */
    @Test
    fun aMatchingBindingIsApprovedWithoutAnotherComparison() = runTest {
        val tv = MultiPushTv()
        val approver = AccountApprover(tv, bindings = mapOf("ABCD-0001" to "B", "ABCD-0002" to "B", "ABCD-0003" to "B"))
        val compared = mutableListOf<CompanionPairingApproval>()

        val result = coordinator(tv, approver).pair(
            target = acctTv,
            chooseAccounts = { setOf(key(home, "sam"), key(home, "alex"), key(lake, "alex-lake")) },
            confirmMatch = { compared += it; true },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 3), result)
        assertEquals(1, compared.size)
        assertEquals(3, approver.approvedWith.size)
    }

    /**
     * A later lookup without a binding asks for its own comparison; one with
     * a different binding is refused without asking, and the session goes on
     * to the next target and sends `done`.
     */
    @Test
    fun aMissingBindingIsComparedAndADifferentOneIsRefused() = runTest {
        val tv = MultiPushTv()
        val approver = AccountApprover(
            tv,
            bindings = mapOf("ABCD-0001" to "B", "ABCD-0002" to "OTHER", "ABCD-0004" to "B"),
        )
        val compared = mutableListOf<CompanionPairingApproval>()
        val alsoLake = lake.copy(accounts = lake.accounts.map { if (it.id == "kim") it.copy(needsSignIn = false) else it })
        val coordinator = CompanionPairingCoordinator(
            serverStore = object : CompanionPairingServerStore {
                override suspend fun snapshot() = CompanionPairingServerSnapshot("lake", listOf(home, alsoLake))
            },
            deviceLoginApprover = approver,
            transportFactory = { tv },
        )

        val result = coordinator.pair(
            target = acctTv,
            chooseAccounts = { choice -> choice.destinations.map { it.key }.toSet() },
            confirmMatch = { compared += it; true },
        )

        // Order: lake/alex (B, compared), lake/kim (OTHER: refused), home/alex (none: compared), home/sam (B: trusted).
        val completed = assertIs<CompanionPairingResult.Completed>(result)
        assertEquals(3, completed.serverCount)
        assertEquals(
            listOf(CompanionTargetOutcome("Lake House", "kim", "Living Room couldn't finish signing in to Lake House.")),
            completed.failures,
        )
        assertEquals(listOf("ABCD-0001", "ABCD-0003"), compared.map { it.userCode })
        assertEquals(listOf("ABCD-0001", "ABCD-0003", "ABCD-0004"), approver.approvedWith.map { it.first })
        assertIs<PairingMessage.Done>(tv.sent.last())
        assertTrue(tv.sent.none { it is PairingMessage.Cancel })
    }

    /**
     * The person says a later account's code doesn't match the TV: that is a
     * sign of someone else on the network, so the whole session stops (as on
     * Apple). The request is denied, the TV is told to cancel, and nothing
     * after it is pushed.
     */
    @Test
    fun aDeclinedLaterComparisonStopsTheSession() = runTest {
        val tv = MultiPushTv()
        val approver = AccountApprover(tv)
        val compared = mutableListOf<String>()

        val result = coordinator(tv, approver).pair(
            target = acctTv,
            chooseAccounts = { setOf(key(lake, "alex-lake"), key(home, "alex"), key(home, "sam")) },
            confirmMatch = { compared += it.userCode; compared.size == 1 },
        )

        assertEquals(listOf("ABCD-0001", "ABCD-0002"), compared)
        assertEquals(listOf("ABCD-0001"), approver.approvedWith.map { it.first })
        assertEquals(2, tv.sent.count { it is PairingMessage.PushServer })
        assertTrue(tv.sent.any { it is PairingMessage.Cancel })
        assertTrue(tv.sent.none { it is PairingMessage.Done })
        // What already signed in is still reported, with the reason it stopped.
        assertEquals(
            CompanionPairingResult.Completed(
                serverCount = 1,
                failures = listOf(
                    CompanionTargetOutcome(
                        "Home",
                        "alex",
                        "The codes didn't match, so setup stopped. Accounts already signed in stay on the TV.",
                    ),
                ),
            ),
            result,
        )
    }

    /**
     * The first target's server has no binding: nothing is trusted, so the
     * next target is compared, and its binding is trusted from then on.
     */
    @Test
    fun aFirstTargetWithoutBindingLeavesTheNextToBeComparedAndTrusted() = runTest {
        val tv = MultiPushTv()
        val approver = AccountApprover(tv, bindings = mapOf("ABCD-0002" to "B", "ABCD-0003" to "B"))
        val compared = mutableListOf<String>()

        val result = coordinator(tv, approver).pair(
            target = acctTv,
            chooseAccounts = { setOf(key(lake, "alex-lake"), key(home, "alex"), key(home, "sam")) },
            confirmMatch = { compared += it.userCode; true },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 3), result)
        assertEquals(listOf("ABCD-0001", "ABCD-0002"), compared)
    }

    /** A target that fails (the TV refuses it, or approving fails) doesn't stop the rest; the summary names each. */
    @Test
    fun aFailedTargetIsReportedAndTheRestAreStillSent() = runTest {
        val tv = MultiPushTv(refuse = mapOf(2 to "limit_reached"))
        val approver = AccountApprover(tv, failApprove = setOf("ABCD-0003"))

        val result = coordinator(tv, approver).pair(
            target = acctTv.copy(signInLimit = 4),
            chooseAccounts = { setOf(key(lake, "alex-lake"), key(home, "alex"), key(home, "sam")) },
        )

        val completed = assertIs<CompanionPairingResult.Completed>(result)
        assertEquals(1, completed.serverCount)
        assertEquals(
            listOf(
                "Lake House — alex",
                "alex on Home: Living Room can't keep any more sign-ins.",
                "sam on Home: Home couldn't approve the sign-in. Try again.",
            ),
            companionSummaryLines(listOf(CompanionTargetOutcome("Lake House", "alex")) + completed.failures),
        )
        assertEquals(3, tv.pushed.size)
        assertIs<PairingMessage.Done>(tv.sent.last())
    }

    /** Nothing signed in: the session still ends with `done`, and the card shows the failure. */
    @Test
    fun everyTargetFailingStillSendsDone() = runTest {
        val tv = MultiPushTv(refuse = mapOf(1 to "unreachable", 2 to "expired"))
        val result = coordinator(tv, AccountApprover(tv)).pair(
            target = acctTv,
            chooseAccounts = { setOf(key(lake, "alex-lake"), key(home, "alex")) },
        )
        val failed = assertIs<CompanionPairingResult.Failed>(result)
        assertTrue("Living Room can't reach Lake House" in failed.message, failed.message)
        assertTrue("The code on Living Room expired" in failed.message, failed.message)
        assertIs<PairingMessage.Done>(tv.sent.last())
    }

    /** A TV on its sign-in screen gets the account chosen on its card, alone. */
    @Test
    fun aSignInTvGetsTheChosenAccountOnly() = runTest {
        val tv = MultiPushTv(state = PairingReceiverState.Login)
        val approver = AccountApprover(tv)
        val coordinator = CompanionPairingCoordinator(
            serverStore = object : CompanionPairingServerStore {
                override suspend fun snapshot() = CompanionPairingServerSnapshot("home", listOf(home, lake))
            },
            deviceLoginApprover = approver,
            transportFactory = { tv },
            identitySource = object : CompanionServerIdentitySource {
                override suspend fun identity(server: CompanionPairingServer) = "S-${server.id}"
                override suspend fun endpoints(server: CompanionPairingServer) = null
            },
        )
        val target = CompanionPairingTarget("tv-1", "Den TV", "127.0.0.1", 9999, state = "login", serverIdentity = "S-home")
        var chooser = false

        val result = coordinator.pair(
            target = target,
            signInServer = coordinator.serverForSignedOutTv(target),
            signInAccountId = "sam",
            chooseAccounts = { chooser = true; it.initialSelection() },
        )

        assertEquals(CompanionPairingResult.Completed(serverCount = 1), result)
        assertEquals(false, chooser)
        assertEquals(listOf("ABCD-0001" to key(home, "sam")), approver.approvedWith)
        assertEquals("sam", tv.pushed.single().accountName)
    }
}
