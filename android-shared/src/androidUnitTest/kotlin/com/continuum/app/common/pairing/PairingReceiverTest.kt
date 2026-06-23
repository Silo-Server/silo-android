package com.continuum.app.common.pairing

import com.continuum.app.model.auth.DeviceLoginStartResponse
import com.continuum.app.model.auth.DeviceLoginPollResponse
import com.continuum.app.pairing.PairingMessage
import com.continuum.app.pairing.PairingReceiverState
import com.continuum.app.pairing.PairingServerStatus
import com.continuum.app.repository.DeviceLoginRepository
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

/** Fake auth port recording setServerUrl + persisted-token calls. */
private class FakeAuthPort : PairingAuthPort {
    val setUrls = mutableListOf<String>()
    val persistedTokens = mutableListOf<Triple<String, String, Long>>()
    override suspend fun setServerUrl(url: String) {
        setUrls += url
    }
    override suspend fun persistApprovedTokens(
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) {
        persistedTokens += Triple(accessToken, refreshToken, expiresIn)
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
    var beganWith: Pair<String?, String?>? = null
    var resetCalled = false

    override suspend fun begin(deviceName: String?, devicePlatform: String?) {
        beganWith = deviceName to devicePlatform
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
            devicePlatform = "Android TV",
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
    ) = PairingReceiver(
        authPort = auth,
        deviceLogin = login,
        identityProvider = { PairingDeviceIdentity(name = "Test TV", deviceId = "device-1") },
        receiverStateProvider = { state },
    )

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
        // Let the receiver run setServerUrl + begin + observe Awaiting.
        repeat(10) { yield() }

        assertEquals(listOf("https://srv.test"), auth.setUrls)
        assertEquals("Test TV" to "Android TV", login.beganWith)

        val deviceStarted = transport.sent.filterIsInstance<PairingMessage.DeviceStarted>().single()
        assertEquals("https://srv.test", deviceStarted.serverURL)
        assertEquals("USER-CODE", deviceStarted.userCode)
        assertEquals("MATCH-42", deviceStarted.matchCode)

        val status = recv.status.value
        assertIs<PairingReceiverStatus.AwaitingApproval>(status)
        assertEquals("Srv", status.serverName)
        assertEquals("MATCH-42", status.matchCode)

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
        assertEquals(Triple("access", "refresh", 3600L), auth.persistedTokens.single())

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
        repeat(10) { yield() }

        login.fail("denied-by-user")
        repeat(10) { yield() }

        val result = transport.sent.filterIsInstance<PairingMessage.ServerResult>().single()
        assertEquals(PairingServerStatus.Failed, result.status)
        assertEquals("denied-by-user", result.error)
        val failed = recv.status.value
        assertIs<PairingReceiverStatus.Failed>(failed)
        assertEquals("https://srv.test", failed.serverName)

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
}
