package org.siloserver.silo.common.pairing

import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.pairing.PairingEndpoint
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingReceiverState
import org.siloserver.silo.pairing.PairingServerStatus
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** In-memory transport: inbound via a channel, outbound recorded in a list. */
private class FakeTransport : PairingTransport {
    private val inbound = Channel<PairingMessage>(Channel.UNLIMITED)
    val sent = mutableListOf<PairingMessage>()
    var closed = false
        private set

    override val incoming: Flow<PairingMessage> = inbound.consumeAsFlow()

    override suspend fun send(message: PairingMessage) {
        sent += message
    }

    override fun close() {
        closed = true
        inbound.close()
    }

    /** Push an inbound message from the "phone". */
    suspend fun deliver(message: PairingMessage) {
        inbound.send(message)
    }
}

/** Fake auth port recording approved-session commits. */
private class FakeAuthPort : PairingAuthPort {
    override suspend fun captureExpectedIdentity() = org.siloserver.silo.network.AccountSessionExpectation(0, null, "https://example.invalid")
    data class CommittedSession(
        val serverUrl: String,
        val serverName: String?,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
    )

    val committedSessions = mutableListOf<CommittedSession>()
    val verifiedIds = mutableListOf<String?>()
    var failPersist = false

    override suspend fun persistApprovedSession(
        serverUrl: String,
        serverName: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        expectedIdentity: org.siloserver.silo.network.AccountSessionExpectation?,
        verifiedServerId: String?,
    ) {
        if (failPersist) throw IllegalStateException("disk full")
        verifiedIds += verifiedServerId
        committedSessions += CommittedSession(
            serverUrl = serverUrl,
            serverName = serverName,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
        )
    }
}

/**
 * Fake device-login port driven by the test. [begin] records its args, then
 * suspends until the test advances the [state] flow to a terminal value.
 */
private class FakeDeviceLogin : DeviceLoginPort {
    private val _state =
        MutableStateFlow<DeviceLoginRepository.DeviceLoginState>(DeviceLoginRepository.DeviceLoginState.Idle)
    override val state: StateFlow<DeviceLoginRepository.DeviceLoginState> = _state
    data class BeginCall(val serverUrl: String, val deviceName: String?, val devicePlatform: String?)

    var beganWith: BeginCall? = null
    var resetCalled = false

    override suspend fun begin(serverUrl: String, deviceName: String?, devicePlatform: String?) {
        beganWith = BeginCall(serverUrl, deviceName, devicePlatform)
        // Drive Initiating → Awaiting; the terminal transition is pushed by the
        // test so the receiver's observer reliably sees each state.
        _state.value = DeviceLoginRepository.DeviceLoginState.Initiating
        _state.value = DeviceLoginRepository.DeviceLoginState.Awaiting(START_RESPONSE)
        // Suspend until the test drives a terminal state.
        while (_state.value is DeviceLoginRepository.DeviceLoginState.Awaiting ||
            _state.value is DeviceLoginRepository.DeviceLoginState.Initiating
        ) {
            yield()
        }
    }

    override fun reset() {
        resetCalled = true
        _state.value = DeviceLoginRepository.DeviceLoginState.Idle
    }

    fun approve() {
        _state.value = DeviceLoginRepository.DeviceLoginState.Approved(APPROVED_RESPONSE)
    }

    fun fail(message: String) {
        _state.value = DeviceLoginRepository.DeviceLoginState.Failed(
            DeviceLoginRepository.FailureReason.Denied,
            message,
        )
    }

    companion object {
        val START_RESPONSE = DeviceLoginStartResponse(
            deviceCode = "dev-code",
            userCode = "USER-CODE",
            matchCode = "MATCH-42",
            verificationUri = "https://example.test/activate",
            verificationUriComplete = "https://example.test/activate?code=USER-CODE",
            expiresAt = "2099-01-01T00:00:00Z",
            expiresIn = 600,
            interval = 5,
            deviceName = "Test TV",
            devicePlatform = "android-tv",
        )
        val APPROVED_RESPONSE = DeviceLoginPollResponse(
            status = "approved",
            accessToken = "access",
            refreshToken = "refresh",
            expiresIn = 3600L,
        )
    }
}

class PairingReceiverTest {

    private fun receiver(
        auth: FakeAuthPort,
        login: FakeDeviceLogin,
        state: PairingReceiverState = PairingReceiverState.Setup,
        identities: Map<String, ServerIdentityProbe> = emptyMap(),
    ) = PairingReceiver(
        authPort = auth,
        deviceLogin = login,
        identityProvider = { PairingDeviceIdentity(name = "Test TV", deviceId = "device-1") },
        identityProbe = { url -> identities[url] ?: ServerIdentityProbe.Unreachable },
    ).also { it.advertisement = PairingAdvertisement(state) }

    @Test
    fun helloSentFirstWithCurrentState() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login, state = PairingReceiverState.Login)

        val job = launch { recv.run(transport) }
        yield()

        val hello = transport.sent.first()
        assertIs<PairingMessage.Hello>(hello)
        assertEquals("Test TV", hello.tvName)
        assertEquals("device-1", hello.tvDeviceId)
        assertEquals(PairingReceiverState.Login, hello.state)
        assertEquals(listOf(1), hello.supportedVersions)
        assertEquals(PairingReceiverStatus.Connected, recv.status.value)

        transport.deliver(PairingMessage.Done)
        job.join()
    }

    @Test
    fun pushServerStartsLoginAndEmitsDeviceStarted() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)

        val job = launch { recv.run(transport) }
        yield()

        transport.deliver(PairingMessage.PushServer(serverURL = "https://srv.test", serverName = "Srv"))
        repeat(5) { yield() }
        // tvOS-parity consent: the first push waits for the TV user.
        assertEquals(
            PairingReceiverStatus.ConsentRequested("https://srv.test", "Srv"),
            recv.status.value,
        )
        recv.allowPendingServer()
        // Let the receiver begin against the candidate URL and observe Awaiting.
        repeat(10) { yield() }

        // The header spelling, not "Android TV"/"android_tv"/"androidtv": the
        // server's platform classifier buckets anything else as mobile, so all
        // three TV login entry points must report the one string.
        assertEquals(
            FakeDeviceLogin.BeginCall("https://srv.test", "Test TV", "android-tv"),
            login.beganWith,
        )
        assertEquals(emptyList(), auth.committedSessions)

        val deviceStarted = transport.sent.filterIsInstance<PairingMessage.DeviceStarted>().single()
        assertEquals("https://srv.test", deviceStarted.serverURL)
        assertEquals("USER-CODE", deviceStarted.userCode)
        assertEquals("MATCH-42", deviceStarted.matchCode)

        val status = recv.status.value
        assertIs<PairingReceiverStatus.AwaitingApproval>(status)
        assertEquals("Srv", status.serverName)
        // People compare the TV's sign-in code, never the match words.
        assertEquals("USER-CODE", status.userCode)
        // The words ride along for the "Older phones show ..." fallback line.
        assertEquals("MATCH-42", status.matchCode)
        assertFalse(status.automatic)

        // Approve → ServerResult(signedIn).
        login.approve()
        repeat(10) { yield() }

        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals("https://srv.test", result.serverURL)
        assertEquals(PairingServerStatus.SignedIn, result.status)
        assertEquals(null, result.error)
        assertEquals(PairingReceiverStatus.SignedIn(serverCount = 1), recv.status.value)
        assertTrue(login.resetCalled)
        // Tokens from the approved response must be persisted (so the TV is
        // authenticated, not just navigated to profile selection).
        assertEquals(
            FakeAuthPort.CommittedSession(
                serverUrl = "https://srv.test",
                serverName = "Srv",
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 3600L,
            ),
            auth.committedSessions.single(),
        )

        // Done → closed.
        transport.deliver(PairingMessage.Done)
        job.join()
        assertTrue(transport.closed)
        assertEquals(PairingReceiverStatus.Completed(listOf("Srv")), recv.status.value)
    }

    @Test
    fun pushServerFailureEmitsFailedResult() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)

        val job = launch { recv.run(transport) }
        yield()

        transport.deliver(PairingMessage.PushServer(serverURL = "https://srv.test", serverName = null))
        repeat(5) { yield() }
        recv.allowPendingServer()
        repeat(10) { yield() }

        login.fail("denied-by-user")
        repeat(10) { yield() }

        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals(PairingServerStatus.Failed, result.status)
        // A structured code, never human text (iOS reads unknown values as auth_failed).
        assertEquals("denied", result.error)
        val failed = recv.status.value
        assertIs<PairingReceiverStatus.Failed>(failed)
        assertEquals("https://srv.test", failed.serverName)
        assertEquals(PairingFailureCode.Denied, failed.code)
        assertEquals(emptyList(), auth.committedSessions)

        transport.deliver(PairingMessage.Done)
        job.join()
    }

    @Test
    fun cancelClosesTransport() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)

        val job = launch { recv.run(transport) }
        yield()

        transport.deliver(PairingMessage.Cancel(reason = "user-bailed"))
        job.join()
        assertTrue(transport.closed)
    }
    @Test
    fun denyPendingServerCancelsSessionWithoutStartingLogin() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)

        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer(serverURL = "https://srv.test", serverName = "Srv"))
        repeat(5) { yield() }
        assertIs<PairingReceiverStatus.ConsentRequested>(recv.status.value)

        recv.denyPendingServer()
        repeat(10) { yield() }

        // No login was started, no server was configured, the phone got a
        // Cancel and the connection closed.
        assertEquals(emptyList(), auth.committedSessions)
        assertEquals(null, login.beganWith)
        val cancel = transport.sent.filterIsInstance<PairingMessage.Cancel>().single()
        assertEquals("consent_denied", cancel.reason)
        assertTrue(transport.closed)
        job.join()
    }

    @Test
    fun consentIsPerSessionSoSecondPushSkipsTheAsk() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)

        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer(serverURL = "https://one.test", serverName = "One"))
        repeat(5) { yield() }
        recv.allowPendingServer()
        repeat(10) { yield() }
        login.approve()
        repeat(10) { yield() }
        assertEquals(PairingReceiverStatus.SignedIn(serverCount = 1), recv.status.value)

        // Second push in the same session must NOT re-ask for consent.
        transport.deliver(PairingMessage.PushServer(serverURL = "https://two.test", serverName = "Two"))
        repeat(10) { yield() }
        assertEquals(listOf("https://one.test"), auth.committedSessions.map { it.serverUrl })
        assertEquals("https://two.test", login.beganWith?.serverUrl)

        transport.deliver(PairingMessage.Done)
        job.join()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.settle() = repeat(20) { yield() }

    @Test
    fun newerPushSupersedesTheOneInFlight() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer(serverURL = "https://one.test", serverName = "One"))
        settle()
        recv.allowPendingServer()
        settle()
        assertEquals("https://one.test", login.beganWith?.serverUrl)

        transport.deliver(PairingMessage.PushServer(serverURL = "https://two.test", serverName = "Two"))
        settle()
        assertEquals("https://two.test", login.beganWith?.serverUrl)
        val started = transport.sent.filterIsInstance<PairingMessage.DeviceStarted>()
        assertEquals(listOf("https://one.test", "https://two.test"), started.map { it.serverURL })
        login.approve()
        settle()
        assertEquals(listOf("https://two.test"), auth.committedSessions.map { it.serverUrl })
        transport.deliver(PairingMessage.Done)
        job.join()
    }

    @Test
    fun pushedIdentityIsVerifiedAndRecorded() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login, identities = mapOf("https://srv.test" to ServerIdentityProbe.Identity("S1")))
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer("https://srv.test", "Srv", serverIdentity = "S1"))
        settle()
        recv.allowPendingServer()
        settle()
        login.approve()
        settle()
        assertEquals(listOf<String?>("S1"), auth.verifiedIds)
        transport.deliver(PairingMessage.Done)
        job.join()
    }

    @Test
    fun differentIdentityAtThePushedAddressFailsWithIdentityMismatch() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login, identities = mapOf("https://srv.test" to ServerIdentityProbe.Identity("OTHER")))
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer("https://srv.test", "Srv", serverIdentity = "S1"))
        settle()
        recv.allowPendingServer()
        settle()
        assertEquals(null, login.beganWith, "never signs in at a different deployment")
        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals("identity_mismatch", result.error)
        transport.deliver(PairingMessage.Done)
        job.join()
        assertEquals(PairingReceiverStatus.Failed("Srv", PairingFailureCode.IdentityMismatch), recv.status.value)
    }

    @Test
    fun unreachableAddressOffersAVerifiedAlternateAndUsesItOnlyWhenChosen() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val recv = receiver(
            auth,
            login,
            identities = mapOf("https://alt.test" to ServerIdentityProbe.Identity("S1")),
        )
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(
            PairingMessage.PushServer(
                "https://srv.test",
                "Srv",
                serverIdentity = "S1",
                endpoints = listOf(
                    PairingEndpoint.of("https://srv.test", PairingEndpoint.Kind.Provider, "tailscale", "Tailscale"),
                    PairingEndpoint.of("https://alt.test", PairingEndpoint.Kind.Public),
                ),
            ),
        )
        settle()
        recv.allowPendingServer()
        settle()
        assertEquals(
            PairingReceiverStatus.Unreachable("https://srv.test", "Srv", "Tailscale", "https://alt.test"),
            recv.status.value,
        )
        assertEquals(null, login.beganWith)
        recv.useAlternateAddress()
        settle()
        assertEquals("https://alt.test", login.beganWith?.serverUrl)
        // Frames still name the pushed address.
        assertEquals("https://srv.test", transport.sent.filterIsInstance<PairingMessage.DeviceStarted>().single().serverURL)
        transport.deliver(PairingMessage.Cancel("done"))
        job.join()
    }

    /** The sign-in screen's code, lent to the receiver in login mode. */
    private class FakeCodeSource : NearbySignInCodeSource {
        val outcome = kotlinx.coroutines.CompletableDeferred<NearbySignInOutcome>()
        var askedFor = mutableListOf<String>()
        override suspend fun codeForNearbyApproval() = FakeDeviceLogin.START_RESPONSE
        override suspend fun nearbyApprovalOutcome(deviceCode: String): NearbySignInOutcome {
            askedFor += deviceCode
            return outcome.await()
        }
    }

    @Test
    fun signedOutTvLendsItsOwnCodeAndStartsNoSecondRequest() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort()
        val login = FakeDeviceLogin()
        val source = FakeCodeSource()
        val recv = receiver(auth, login)
        recv.advertisement = PairingAdvertisement.login(serverIdentity = "S1", serverUrl = "http://192.168.1.5:8090", source = source)
        val job = launch { recv.run(transport) }
        yield()
        assertEquals(PairingReceiverState.Login, (transport.sent.first() as PairingMessage.Hello).state)
        transport.deliver(PairingMessage.PushServer("https://media.example.com", "Home", serverIdentity = "S1"))
        settle()
        recv.allowPendingServer()
        settle()
        val started = transport.sent.filterIsInstance<PairingMessage.DeviceStarted>().single()
        assertEquals("USER-CODE", started.userCode)
        assertEquals("MATCH-42", started.matchCode)
        assertEquals("https://media.example.com", started.serverURL)
        assertEquals(null, login.beganWith, "no second device request")
        assertEquals(listOf("dev-code"), source.askedFor)

        source.outcome.complete(NearbySignInOutcome.SignedIn)
        settle()
        assertTrue(auth.committedSessions.isEmpty(), "the sign-in screen saves the session")
        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals(PairingServerStatus.SignedIn, result.status)
        assertEquals("https://media.example.com", result.serverURL)
        transport.deliver(PairingMessage.Done)
        job.join()
    }

    @Test
    fun signedOutTvRefusesAnotherServerOrAPushWithoutIdentity() = runTest {
        for (identity in listOf("S2", null)) {
            val transport = FakeTransport()
            val source = FakeCodeSource()
            val recv = receiver(FakeAuthPort(), FakeDeviceLogin(), identities = mapOf("https://media.example.com" to ServerIdentityProbe.Identity("S1")))
            recv.advertisement = PairingAdvertisement.login(serverIdentity = "S1", serverUrl = "http://192.168.1.5:8090", source = source)
            val job = launch { recv.run(transport) }
            yield()
            transport.deliver(PairingMessage.PushServer("https://media.example.com", "Home", serverIdentity = identity))
            settle()
            recv.allowPendingServer()
            settle()
            val refused = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
            assertEquals("identity_mismatch", refused.error, "identity=$identity")
            assertTrue(transport.sent.none { it is PairingMessage.DeviceStarted })
            assertTrue(source.askedFor.isEmpty())
            transport.deliver(PairingMessage.Done)
            job.join()
        }
    }

    @Test
    fun aLoneFailureStaysOnScreenUntilClosedThenAdvertisingResumes() = runTest {
        val transport = FakeTransport()
        val source = FakeCodeSource()
        val recv = receiver(FakeAuthPort(), FakeDeviceLogin())
        recv.advertisement = PairingAdvertisement.login(serverIdentity = "S1", serverUrl = "http://192.168.1.5:8090", source = source)
        recv.setAdvertising()
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer("https://media.example.com", "Home", serverIdentity = "S1"))
        settle()
        recv.allowPendingServer()
        settle()
        source.outcome.complete(NearbySignInOutcome.Failed(PairingFailureCode.Expired))
        settle()
        transport.deliver(PairingMessage.Done)
        job.join()
        // What the advertiser sees when the connection ends: it keeps a
        // Failed status rather than replacing it with Advertising.
        assertEquals(PairingReceiverStatus.Failed("Home", PairingFailureCode.Expired), recv.status.value)
        assertEquals(false, resumesAdvertisingAfterConnection(recv.status.value))
        assertEquals(true, resumesAdvertisingAfterConnection(PairingReceiverStatus.Idle))

        recv.cancelActiveSession()
        assertEquals(PairingReceiverStatus.Advertising, recv.status.value)
    }

    @Test
    fun failedCommitIsReportedInsteadOfStalling() = runTest {
        val transport = FakeTransport()
        val auth = FakeAuthPort().apply { failPersist = true }
        val login = FakeDeviceLogin()
        val recv = receiver(auth, login)
        val job = launch { recv.run(transport) }
        yield()
        transport.deliver(PairingMessage.PushServer("https://srv.test", "Srv"))
        settle()
        recv.allowPendingServer()
        settle()
        login.approve()
        settle()
        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals(PairingServerStatus.Failed, result.status)
        assertEquals("auth_failed", result.error)
        assertEquals(PairingReceiverStatus.Failed("Srv", PairingFailureCode.AuthFailed), recv.status.value)
        transport.deliver(PairingMessage.Done)
        job.join()
    }
}
