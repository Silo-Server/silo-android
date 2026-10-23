package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Ktor engine and sessions are in memory: no listener, loopback, or network client. */
@OptIn(ExperimentalCoroutinesApi::class, io.ktor.utils.io.InternalAPI::class)
class GenericEventsTransportTest {
    private class Session(context: CoroutineContext) : DefaultWebSocketSession {
        override val coroutineContext = context + Job(context[Job])
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 0L
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions = emptyList<WebSocketExtension<*>>()
        override val closeReason = CompletableDeferred<CloseReason?>()
        val sent = mutableListOf<String>()
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit
        override suspend fun send(frame: Frame) {
            if (frame is Frame.Text) sent += frame.readText()
            if (frame is Frame.Close) finish(frame.readReason() ?: CloseReason(1000, ""))
        }
        fun finish(reason: CloseReason = CloseReason(1000, "")) {
            closeReason.complete(reason)
            incoming.close()
        }
        override suspend fun flush() = Unit
        @Deprecated("Legacy Ktor API")
        override fun terminate() { coroutineContext.cancel() }
    }

    private class Engine(
        override val dispatcher: CoroutineDispatcher,
        private val script: suspend Session.() -> Unit,
    ) : HttpClientEngineBase("C31 in-memory events") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(WebSocketCapability)
        val proofs = mutableListOf<String>()
        val sessions = mutableListOf<Session>()
        var mintStatus = HttpStatusCode.OK
        var upgradeStatus = HttpStatusCode.SwitchingProtocols
        var retryAfter: String? = null
        var mints = 0
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val context = callContext()
            val mint = data.url.encodedPath.endsWith("ws-ticket")
            val status = if (mint) mintStatus else upgradeStatus
            val headers = Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.SecWebSocketProtocol, EVENTS_PROTOCOL)
                retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
            }
            val body = if (mint) {
                mints++
                ByteReadChannel("""{"ticket":"proof-$mints","expires_in":30,"max_connection_seconds":300,"protocol":"silo.events.v2"}""")
            } else {
                proofs += data.headers[HttpHeaders.SecWebSocketProtocol]!!
                assertTrue(data.attributes[SingleAttemptAttributeKey])
                assertNull(data.headers[HttpHeaders.Authorization])
                if (status == HttpStatusCode.SwitchingProtocols) Session(context).also { session ->
                    sessions += session
                    session.launch { session.script() }
                } else ByteReadChannel("capacity refused")
            }
            return HttpResponseData(status, GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, body, context)
        }
    }

    private var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }

    private suspend fun TestScope.withSocket(
        script: suspend Session.() -> Unit,
        check: suspend (EventsSocketV2Api, Engine) -> Unit,
    ) {
        val engine = Engine(StandardTestDispatcher(testScheduler), script)
        val client = HttpClient(engine) { install(WebSockets) }
        try {
            check(EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted, nowMs = { testScheduler.currentTime }), engine)
        } finally {
            client.close()
            engine.sessions.forEach { it.coroutineContext.cancel() }
            engine.close()
            engine.sessions.forEach { it.coroutineContext[Job]?.join() }
            engine.coroutineContext[Job]?.join()
        }
        assertTrue(engine.sessions.all { !it.coroutineContext.isActive })
    }

    @Test fun bothCallersMeasureFromFirstAckAndKeepFreshSingleUseProofs() = runTest {
        withSocket(script = {
            delay(60_000) // Time since open must not count.
            incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            delay(20_000)
            incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            delay(10_000)
            finish()
        }) { socket, engine ->
            repeat(2) {
                val home = DefaultHomeRealtimeClient(socket).connect().toList()
                assertEquals(30_000, assertIs<HomeRealtimeEvent.Closed>(home.last()).recovery.acceptedForMs)
                val notifications = DefaultNotificationsRealtimeClient(socket).connect().toList()
                assertEquals(30_000, assertIs<NotificationRealtimeEvent.Closed>(notifications.last()).recovery.acceptedForMs)
            }
            assertEquals(4, engine.mints)
            assertEquals(4, engine.proofs.distinct().size)
            assertEquals(4, engine.sessions.size)
            assertTrue(engine.sessions.all { it.sent.size == 1 })
        }
    }

    @Test fun noAckAtFullLifetimeIsUnhealthyButAcceptedLifetimeIsHealthy() = runTest {
        for (ack in listOf(false, true)) withSocket(script = {
            if (ack) incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            awaitCancellation()
        }) { socket, engine ->
            val close = assertIs<HomeRealtimeEvent.Closed>(DefaultHomeRealtimeClient(socket).connect().toList().last())
            assertEquals(if (ack) 300_000L else null, close.recovery.acceptedForMs)
            assertEquals(if (ack) 1_000L else 5_000L, GenericEventsBackoff { 0.0 }.disconnected(close.recovery))
            assertEquals(1, engine.mints)
        }
    }

    @Test fun shortAcceptedConnectionsStayOnTheFailureLadder() = runTest {
        withSocket(script = {
            incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            delay(29_999)
            finish()
        }) { socket, _ ->
            val home = assertIs<HomeRealtimeEvent.Closed>(DefaultHomeRealtimeClient(socket).connect().toList().last())
            val notifications = assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last())
            val recovery = GenericEventsBackoff { 0.0 }
            assertEquals(29_999, home.recovery.acceptedForMs)
            assertEquals(29_999, notifications.recovery.acceptedForMs)
            assertEquals(5_000, recovery.disconnected(home.recovery))
            assertEquals(10_000, recovery.disconnected(notifications.recovery))
        }
    }

    @Test fun bothCallersRejectUnacceptedAcksAndMeasureLateAcceptance() = runTest {
        for (ack in listOf(
            """{"type":"subscribed"}""",
            """{"type":"subscribed","channels":[]}""",
            """{"type":"subscribed","channels":[],"rejected":[{"channel":"notifications","code":"forbidden"}]}""",
        )) for (lateAcceptance in listOf(false, true)) withSocket(script = {
            incoming.send(Frame.Text(ack))
            delay(29_000)
            if (lateAcceptance) incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            delay(1_000)
            finish()
        }) { socket, engine ->
            val home = assertIs<HomeRealtimeEvent.Closed>(DefaultHomeRealtimeClient(socket).connect().toList().last())
            val notifications = assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last())
            assertEquals(if (lateAcceptance) 1_000L else null, home.recovery.acceptedForMs, ack)
            assertEquals(home.recovery, notifications.recovery)
            val backoff = GenericEventsBackoff { 0.0 }
            assertEquals(5_000, backoff.disconnected(home.recovery))
            assertEquals(10_000, backoff.disconnected(notifications.recovery))
            assertEquals(2, engine.mints)
        }
    }

    @Test fun backgroundCancellationClosesActiveTransportWithoutTerminalDeliveryOrRetry() = runTest {
        withSocket(script = { awaitCancellation() }) { socket, engine ->
            val events = mutableListOf<HomeRealtimeEvent>()
            val job = backgroundScope.launch { DefaultHomeRealtimeClient(socket).connect().collect { events += it } }
            runCurrent()
            assertEquals(1, engine.sessions.size)
            job.cancelAndJoin()
            advanceTimeBy(300_000)
            assertTrue(events.isEmpty())
            assertTrue(engine.sessions.all { !it.coroutineContext.isActive })
            assertEquals(1, engine.mints)
        }
    }

    @Test fun plain429And503ReadRetryAfterAtMintAndUpgradeWithoutReplay() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            for (mintRefused in listOf(false, true)) withSocket(script = { error("refused upgrades have no session") }) { socket, engine ->
                if (mintRefused) engine.mintStatus = status else engine.upgradeStatus = status
                engine.retryAfter = "45"
                val home = assertIs<HomeRealtimeEvent.Closed>(DefaultHomeRealtimeClient(socket).connect().toList().last())
                val notifications = assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last())
                assertEquals(45_000, home.recovery.retryAfterMs)
                assertEquals(home.recovery, notifications.recovery)
                assertEquals(67_500, GenericEventsBackoff { 1.0 }.disconnected(home.recovery))
                assertEquals(2, engine.mints)
                assertEquals(if (mintRefused) 0 else 2, engine.proofs.size)
                assertTrue(engine.sessions.isEmpty())
            }
        }
    }

    @Test fun upgradeAuthRefusalsRetryFreshProofInActualNotificationRepository() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            withSocket(script = { error("refused upgrade must not create a session") }) { socket, engine ->
                engine.upgradeStatus = status
                val repository = org.siloserver.silo.repository.NotificationsRepository(
                    org.siloserver.silo.network.UnusedNotificationsApi,
                    realtimeFactory = { DefaultNotificationsRealtimeClient(socket) }, tokens = tokens,
                    recoveryFactory = { GenericEventsBackoff { 0.0 } },
                )
                val job = repository.connectRealtime(backgroundScope)
                runCurrent()
                assertEquals(1, engine.mints)
                assertFalse(repository.realtimeFatal.value, "upgrade refusal is not mint authentication failure")
                advanceTimeBy(4_999)
                runCurrent()
                assertEquals(1, engine.mints)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, engine.mints, "a new proof must be minted at the 5s failed-attempt floor")
                assertEquals(2, engine.proofs.distinct().size, "the spent proof must never be replayed")
                val previous = owner
                owner = owner.copy(profileId = "replacement", identityGeneration = owner.identityGeneration + 1)
                advanceTimeBy(10_001)
                runCurrent()
                assertEquals(2, engine.mints, "stale authority must not mint again")
                job.cancelAndJoin()
                advanceTimeBy(300_000)
                assertEquals(2, engine.mints)
                owner = previous
            }
        }
    }

    @Test fun mintAuthRefusalsStillParkNotificationRepository() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            withSocket(script = { error("mint refusal has no upgrade") }) { socket, engine ->
                engine.mintStatus = status
                val repository = org.siloserver.silo.repository.NotificationsRepository(
                    org.siloserver.silo.network.UnusedNotificationsApi,
                    realtimeFactory = { DefaultNotificationsRealtimeClient(socket) }, tokens = tokens,
                    recoveryFactory = { GenericEventsBackoff { 0.0 } },
                )
                val job = repository.connectRealtime(backgroundScope)
                runCurrent()
                assertTrue(repository.realtimeFatal.value)
                advanceTimeBy(300_000)
                runCurrent()
                assertEquals(1, engine.mints)
                assertTrue(engine.proofs.isEmpty())
                job.cancelAndJoin()
            }
        }
    }

    @Test fun frameAndCloseAccessChangeRemainsImmediate() = runTest {
        for (frame in listOf(false, true)) withSocket(script = {
            if (frame) incoming.send(Frame.Text("""{"type":"access_changed"}"""))
            finish(CloseReason(4001, "access_changed"))
        }) { socket, _ ->
            val close = assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last())
            assertTrue(close.recovery.accessChanged)
            assertEquals(0, GenericEventsBackoff { 1.0 }.disconnected(close.recovery))
        }
    }

    @Test fun cancellationAndIdentityReplacementReleaseSessionAndFenceOldFrames() = runTest {
        withSocket(script = { awaitCancellation() }) { socket, engine ->
            val events = mutableListOf<HomeRealtimeEvent>()
            val job = backgroundScope.launch { DefaultHomeRealtimeClient(socket).connect().collect { events += it } }
            runCurrent()
            assertEquals(1, engine.sessions.size)
            owner = owner.copy(profileId = "replacement", identityGeneration = 2)
            engine.sessions.single().incoming.send(Frame.Text("""{"type":"subscribed","channels":["notifications"]}"""))
            runCurrent()
            assertTrue(events.none { it is HomeRealtimeEvent.Connected })
            job.cancelAndJoin()
            assertTrue(engine.sessions.all { !it.coroutineContext.isActive })
            assertEquals(1, engine.mints)
        }
    }
}
