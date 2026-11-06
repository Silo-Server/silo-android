package org.siloserver.silo.common.pairing

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.repository.DeviceLoginRepository
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's real [CompanionPairingCoordinator] against the TV's real
 * [PairingReceiver] over a loopback TLS-PSK connection, with the real
 * device-login state machine ([DeviceLoginRepository]) on the TV. Only the
 * Silo servers ([FakeSiloServers]) and the TV's account store
 * ([RecordingAuthPort]) are fakes. Two emulators can't see each other's NSD,
 * so this is the protocol-level end-to-end check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CompanionPairingEndToEndTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private val home = "https://home.example"
    private val lake = "https://lake.example"

    private fun servers(homeBinds: Boolean = true, lakeBinds: Boolean = true, lake: String = this.lake) = listOf(
        CompanionPairingServer(
            id = "home",
            url = home,
            displayName = "Home",
            accounts = listOf(
                CompanionPairingAccount("alex@home", "alex", isCurrent = true),
                CompanionPairingAccount("sam@home", "sam"),
            ),
        ),
        CompanionPairingServer(
            id = "lake",
            url = lake,
            displayName = "Lake House",
            accounts = listOf(
                CompanionPairingAccount("alex@lake", "alex", isCurrent = true),
                CompanionPairingAccount("sam@lake", "sam"),
            ),
        ),
    ).also { fake.binds = mapOf(home to homeBinds, lake to lakeBinds) }

    private val fake = FakeSiloServers()

    /** Every row: 2 servers x 2 accounts. */
    private val all: suspend (CompanionAccountChoice) -> Set<String> = { c -> c.destinations.map { it.key }.toSet() }

    private class Run(
        val phone: CompanionPairingResult,
        val tvEnd: PairingReceiverStatus,
        val compared: List<CompanionPairingApproval>,
        val tvSaw: List<PairingReceiverStatus>,
        val auth: RecordingAuthPort,
    )

    private fun pair(
        servers: List<CompanionPairingServer>,
        choose: suspend (CompanionAccountChoice) -> Set<String> = all,
        lookupBindingOverride: (String) -> String? = { null },
    ): Run = runBlocking {
        withTimeout(90_000) {
            val auth = RecordingAuthPort()
            val receiver = PairingReceiver(
                authPort = auth,
                deviceLogin = DeviceLoginRepositoryPort(DeviceLoginRepository(fake.tvApi)),
                identityProvider = { PairingDeviceIdentity(name = "Living Room", deviceId = "tv-1") },
                cleartextConsent = NoCleartext,
            )
            val tvSaw = mutableListOf<PairingReceiverStatus>()
            // The TV user allows the first push, as on the consent screen.
            val watcher = scope.launch {
                receiver.status.collect { status ->
                    synchronized(tvSaw) { tvSaw += status }
                    if (status is PairingReceiverStatus.ConsentRequested) receiver.allowPendingServer()
                }
            }
            val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val tvSession = scope.launch {
                val socket = withContext(Dispatchers.IO) { listener.accept() }
                receiver.run(TlsPskPairingTransport.accept(socket))
            }
            val compared = mutableListOf<CompanionPairingApproval>()
            val coordinator = CompanionPairingCoordinator(
                serverStore = object : CompanionPairingServerStore {
                    override suspend fun snapshot() = CompanionPairingServerSnapshot("home", servers)
                },
                deviceLoginApprover = fake.phoneApprover(lookupBindingOverride),
                transportFactory = { TlsPskPairingClientTransport.connect("127.0.0.1", listener.localPort) },
            )
            val result = coordinator.pair(
                target = CompanionPairingTarget("tv-1", "Living Room", "127.0.0.1", listener.localPort, acceptsAccounts = true),
                chooseAccounts = choose,
                confirmMatch = { approval -> synchronized(compared) { compared += approval }; true },
            )
            tvSession.join()
            listener.close()
            watcher.cancel()
            Run(result, receiver.status.value, compared.toList(), synchronized(tvSaw) { tvSaw.toList() }, auth)
        }
    }

    /**
     * Both servers check the binding: the person compares the first code
     * only, all four accounts sign in, the TV keeps each as its own account
     * in sending order, and opens Change Account on the first.
     */
    @Test
    fun twoServersTwoAccountsBoundSessionNeedsOneComparison() {
        val run = pair(servers())

        assertEquals(CompanionPairingResult.Completed(serverCount = 4), run.phone)
        assertEquals(1, run.compared.size)
        val completed = assertIs<PairingReceiverStatus.Completed>(run.tvEnd)
        assertEquals(
            listOf("alex@home", "sam@home", "alex@lake", "sam@lake"),
            completed.accounts.map { it.accountId },
        )
        assertEquals(listOf("Home", "Lake House"), completed.serverNames)
        assertEquals(
            TvEndOfSession.ChangeAccountOn(PairingCommittedAccount(home, "alex@home")),
            TvEndOfSession.of(completed.accounts),
        )
        // One secret for the whole session, on every server: each start's
        // binding is SHA-256 of the secret every poll carried.
        val bindings = fake.starts.map { it.binding }.toSet()
        assertEquals(1, bindings.size)
        assertNotNull(bindings.single())
        assertTrue(fake.polls.isNotEmpty())
        fake.polls.forEach { (_, secret) -> assertEquals(bindings.single(), sha256(secret!!)) }
        // The TV showed each push's own code and the account the phone named.
        val shown = run.tvSaw.filterIsInstance<PairingReceiverStatus.AwaitingApproval>()
        assertEquals(fake.starts.map { it.userCode }, shown.map { it.userCode }.distinct())
        assertEquals(listOf("alex", "sam", "alex", "sam"), shown.distinctBy { it.userCode }.map { it.accountName })
        // Only the first was waiting on a person; later bound ones were automatic.
        assertEquals(listOf(false, true, true, true), shown.distinctBy { it.userCode }.map { it.automatic })
        assertEquals(listOf("alex", "sam", "alex", "sam"), fake.approvals.map { it.username })
    }

    /** A server without binding gets no binding from the TV, and the phone compares each of its codes. */
    @Test
    fun aServerWithoutBindingIsComparedPerAccount() {
        val run = pair(servers(lakeBinds = false))

        assertEquals(CompanionPairingResult.Completed(serverCount = 4), run.phone)
        // Home/alex (first), then Lake House alex and sam on their own.
        assertEquals(listOf("Home" to false, "Lake House" to true, "Lake House" to true), run.compared.map { it.serverName to it.isFollowUp })
        assertEquals(listOf("alex", "sam"), run.compared.filter { it.isFollowUp }.map { it.accountName })
        assertTrue(fake.starts.filter { it.server == lake }.all { it.binding == null })
        assertTrue(fake.polls.filter { it.first.startsWith("lake") }.all { it.second == null })
    }

    /**
     * A plain-HTTP server gets neither the binding nor the secret, even when
     * its capability says `session_binding`: an on-path attacker could read
     * them. Its accounts are compared one by one instead.
     */
    @Test
    fun aPlainHttpServerGetsNoBindingEvenWhenItSupportsBinding() {
        val plainLake = "http://lake.example"
        val run = pair(servers(lake = plainLake))

        assertEquals(CompanionPairingResult.Completed(serverCount = 4), run.phone)
        assertEquals(listOf("Home" to false, "Lake House" to true, "Lake House" to true), run.compared.map { it.serverName to it.isFollowUp })
        val plainStarts = fake.starts.filter { it.server == plainLake }
        assertEquals(2, plainStarts.size)
        assertTrue(plainStarts.all { it.sentBinding == null })
        assertTrue(fake.polls.filter { it.first.startsWith("lake") }.all { it.second == null })
        assertTrue(fake.starts.filter { it.server == home }.all { it.sentBinding != null })
    }

    /**
     * A later request whose lookup carries another binding is refused
     * without asking; the session goes on, ends with `done`, and the TV keeps
     * the rest.
     */
    @Test
    fun aDifferentBindingIsRefusedAndTheSessionContinues() {
        val run = pair(servers(), lookupBindingOverride = { code -> if (code == fake.starts.getOrNull(1)?.userCode) "copied-from-elsewhere" else null })

        val completed = assertIs<CompanionPairingResult.Completed>(run.phone)
        assertEquals(3, completed.serverCount)
        assertEquals(
            listOf(EnglishOutcome("Home", "sam", "Living Room couldn't finish signing in to Home.")),
            completed.failures.map { it.inEnglish() },
        )
        assertEquals(1, run.compared.size)
        assertEquals(listOf("alex", "alex", "sam"), fake.approvals.map { it.username })
        // The refused request was withdrawn by the TV when the next push replaced it.
        assertTrue(fake.starts[1].canceled)
        val tv = assertIs<PairingReceiverStatus.Completed>(run.tvEnd)
        assertEquals(listOf("alex@home", "alex@lake", "sam@lake"), tv.accounts.map { it.accountId })
    }

    /** No binding anywhere (older servers): one comparison per account, all still sign in. */
    @Test
    fun missingBindingEverywhereComparesEveryAccount() {
        val run = pair(servers(homeBinds = false, lakeBinds = false))
        assertEquals(CompanionPairingResult.Completed(serverCount = 4), run.phone)
        assertEquals(4, run.compared.size)
        assertTrue(fake.starts.all { it.binding == null })
    }

    /** One target failing on the server (approval refused) doesn't stop the others. */
    @Test
    fun aFailedTargetIsSummarizedAndTheOthersSignIn() {
        fake.refuseApprovalFor = setOf("sam@lake")
        val run = pair(servers())
        val completed = assertIs<CompanionPairingResult.Completed>(run.phone)
        assertEquals(3, completed.serverCount)
        assertEquals(
            listOf(EnglishOutcome("Lake House", "sam", "Lake House couldn't approve the sign-in. Try again.")),
            completed.failures.map { it.inEnglish() },
        )
        assertEquals(3, assertIs<PairingReceiverStatus.Completed>(run.tvEnd).accounts.size)
        // The unapproved request was withdrawn when the session ended.
        assertTrue(fake.starts.last().canceled)
    }

    /** One account: the TV opens its picker, as before. */
    @Test
    fun oneAccountOpensItsPicker() {
        val run = pair(servers(), choose = { it.initialSelection() })
        assertEquals(CompanionPairingResult.Completed(serverCount = 1), run.phone)
        val tv = assertIs<PairingReceiverStatus.Completed>(run.tvEnd)
        assertEquals(TvEndOfSession.Picker, TvEndOfSession.of(tv.accounts))
        assertNull(run.compared.single().takeIf { it.isFollowUp })
    }

    private fun sha256(secret: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(secret)))
}

/**
 * The TV app's end-of-session rule (contract §B.7), restated here so this
 * module's test can check the receiver's [PairingReceiverStatus.Completed]
 * against it. The TV app implements it in `pairedSessionDestination`.
 */
private sealed interface TvEndOfSession {
    data object Picker : TvEndOfSession
    data class ChangeAccountOn(val first: PairingCommittedAccount) : TvEndOfSession

    companion object {
        fun of(accounts: List<PairingCommittedAccount>): TvEndOfSession =
            if (accounts.distinct().size >= 2) ChangeAccountOn(accounts.first()) else Picker
    }
}

private object NoCleartext : org.siloserver.silo.common.network.CleartextConsentStore {
    override suspend fun isApproved(origin: String) = false
    override suspend fun approve(origin: String) = Unit
}

/** The TV's account store: the same server user lands in the same account. */
private class RecordingAuthPort : PairingAuthPort {
    override suspend fun captureExpectedIdentity() = AccountSessionExpectation(0, null, "https://example.invalid")

    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
    ) = error("the receiver commits through commitApprovedSession")

    override suspend fun commitApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: AccountSessionExpectation?,
        verifiedServerId: String?,
        user: User?,
    ): PairingCommittedAccount = PairingCommittedAccount(serverUrl, requireNotNull(user).id)
}

/**
 * Two Silo servers implementing device login with session binding as the
 * contract (§A) describes: start stores the binding, lookup returns it, and
 * poll mints tokens only for the matching secret. Shared by the TV's API and
 * the phone's approver; guarded by its own monitor (two threads use it).
 */
private class FakeSiloServers {
    @Volatile
    var binds: Map<String, Boolean> = emptyMap()

    @Volatile
    var refuseApprovalFor: Set<String> = emptySet()

    class Request(
        val server: String,
        val deviceCode: String,
        val userCode: String,
        val matchCode: String,
        val binding: String?,
        /** What the TV sent, whether or not this server stored it. */
        val sentBinding: String?,
    ) {
        var approvedBy: User? = null
        var consumed = false
        var canceled = false
    }

    private val lock = Any()
    private val requests = mutableListOf<Request>()
    private val pollLog = mutableListOf<Pair<String, String?>>()
    private val approvalLog = mutableListOf<User>()

    val starts: List<Request> get() = synchronized(lock) { requests.toList() }
    val polls: List<Pair<String, String?>> get() = synchronized(lock) { pollLog.toList() }
    val approvals: List<User> get() = synchronized(lock) { approvalLog.toList() }

    private fun prefix(server: String) = server.substringAfter("://").substringBefore('.')

    val tvApi = object : DeviceLoginApi {
        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("candidate servers only")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("candidate servers only")
        override suspend fun lookupDeviceLogin(token: String?, code: String?) = error("phone side")
        override suspend fun approveDeviceLogin(token: String?, code: String?) = error("phone side")
        override suspend fun denyDeviceLogin(token: String?, code: String?) = error("phone side")

        override suspend fun deviceLoginCapabilityAt(serverUrl: String): ApiResult<DeviceLoginCapabilityResponse> =
            ApiResult.Success(DeviceLoginCapabilityResponse(cancel = true, sessionBinding = binds[serverUrl] == true))

        override suspend fun startDeviceLoginAt(serverUrl: String, deviceName: String?, devicePlatform: String?) =
            startDeviceLoginAt(serverUrl, deviceName, devicePlatform, binding = null)

        override suspend fun startDeviceLoginAt(
            serverUrl: String,
            deviceName: String?,
            devicePlatform: String?,
            binding: String?,
        ): ApiResult<DeviceLoginStartResponse> = synchronized(lock) {
            // A server without binding support ignores the field; one with it stores it.
            val n = requests.size + 1
            val request = Request(
                server = serverUrl,
                deviceCode = "${prefix(serverUrl)}-device-$n",
                userCode = "CODE-$n",
                matchCode = "match-$n",
                binding = binding.takeIf { binds[serverUrl] == true },
                sentBinding = binding,
            )
            requests += request
            ApiResult.Success(
                DeviceLoginStartResponse(
                    deviceCode = request.deviceCode,
                    userCode = request.userCode,
                    matchCode = request.matchCode,
                    verificationUri = "$serverUrl/activate",
                    verificationUriComplete = "$serverUrl/activate?code=${request.userCode}",
                    expiresAt = "2099-01-01T00:00:00Z",
                    expiresIn = 600,
                    interval = 1,
                    deviceName = deviceName.orEmpty(),
                    devicePlatform = devicePlatform.orEmpty(),
                ),
            )
        }

        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String) =
            pollDeviceLoginAt(serverUrl, deviceCode, bindingSecret = null)

        override suspend fun pollDeviceLoginAt(
            serverUrl: String,
            deviceCode: String,
            bindingSecret: String?,
        ): ApiResult<DeviceLoginPollResponse> = synchronized(lock) {
            pollLog += deviceCode to bindingSecret
            val request = requests.firstOrNull { it.server == serverUrl && it.deviceCode == deviceCode && !it.canceled }
                ?: return@synchronized ApiResult.Error(404, "not_found", "not found")
            val binding = request.binding
            if (binding != null && (bindingSecret == null || hash(bindingSecret) != binding)) {
                return@synchronized ApiResult.Error(403, "device_binding_mismatch", "binding mismatch")
            }
            val user = request.approvedBy
            if (user != null && !request.consumed) {
                request.consumed = true
                ApiResult.Success(
                    DeviceLoginPollResponse(
                        status = "approved",
                        accessToken = "access-${user.id}",
                        refreshToken = "refresh-${user.id}",
                        expiresIn = 3600,
                        user = user,
                    ),
                )
            } else {
                ApiResult.Success(DeviceLoginPollResponse(status = "pending", pollAfter = 1))
            }
        }

        override suspend fun cancelDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginCancelResponse> =
            synchronized(lock) {
                requests.firstOrNull { it.server == serverUrl && it.deviceCode == deviceCode }?.canceled = true
                ApiResult.Success(DeviceLoginCancelResponse("canceled"))
            }
    }

    fun phoneApprover(lookupBindingOverride: (String) -> String?) = object : CompanionDeviceLoginApprover {
        override suspend fun accountName(destination: CompanionPairingDestination) = destination.account.username

        override suspend fun lookup(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginLookupResponse> =
            synchronized(lock) {
                val request = requests.firstOrNull { it.server == destination.server.url && it.userCode == code && !it.canceled }
                    ?: return@synchronized ApiResult.Error(404, "not_found", "not found")
                ApiResult.Success(
                    DeviceLoginLookupResponse(
                        status = "pending",
                        userCode = request.userCode,
                        matchCode = request.matchCode,
                        binding = lookupBindingOverride(code) ?: request.binding,
                    ),
                )
            }

        override suspend fun approve(destination: CompanionPairingDestination, code: String): ApiResult<DeviceLoginDecisionResponse> =
            synchronized(lock) {
                val request = requests.firstOrNull { it.server == destination.server.url && it.userCode == code && !it.canceled }
                    ?: return@synchronized ApiResult.Error(404, "not_found", "not found")
                if (destination.account.id in refuseApprovalFor) return@synchronized ApiResult.Error(403, "forbidden", "no")
                if (request.approvedBy != null) return@synchronized ApiResult.Error(409, "conflict", "already approved")
                val user = User(
                    id = requireNotNull(destination.account.id),
                    username = destination.account.username.orEmpty(),
                    email = "",
                    role = "user",
                )
                request.approvedBy = user
                approvalLog += user
                ApiResult.Success(DeviceLoginDecisionResponse("approved"))
            }

        override suspend fun deny(destination: CompanionPairingDestination, code: String) =
            ApiResult.Success(DeviceLoginDecisionResponse("denied"))
    }

    private fun hash(secret: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(secret)))
}
