package org.siloserver.silo.network.apiv2

import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.siloserver.silo.network.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.test.*

/** Exercises the production factory and its default WebSocket factory, without network I/O. */
class EventsUpgradeProductionWiringTest {
    private class NoSockets : SocketFactory() {
        override fun createSocket(): Socket = error("socket use forbidden")
        override fun createSocket(host: String, port: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("socket use forbidden")
    }

    private suspend fun fixture(status: Int = 429, block: suspend (io.ktor.client.HttpClient, AtomicInteger, AtomicInteger) -> Unit) {
        val mints = AtomicInteger()
        val upgrades = AtomicInteger()
        val pool = ConnectionPool()
        val dispatcher = Dispatcher()
        val platform = createPlatformHttpClient(retryOnConnectionFailure = false)
        // Append only transport stubs to the actual engine config before its lazy clients exist.
        // Do not install eventsUpgradeResponses here: deleting its production installer must fail.
        val engine = assertIs<OkHttpEngine>(platform.engine)
        engine.config.config {
            connectionPool(pool)
            dispatcher(dispatcher)
            dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = error("DNS use forbidden") })
            socketFactory(NoSockets())
            addInterceptor { chain ->
                val request = chain.request()
                val mint = request.url.encodedPath.endsWith("ws-ticket")
                val raw = if (mint) {
                    mints.incrementAndGet()
                    """{"ticket":"fixture-proof","expires_in":30,"max_connection_seconds":300,"protocol":"silo.events.v2"}"""
                } else { upgrades.incrementAndGet(); "plain refusal" }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (mint) 200 else status)
                    .message("fixture").header("Content-Type", "application/json").header("Retry-After", "45")
                    .body(raw.toResponseBody()).build() // Terminal transport: never proceed.
            }
        }
        val client = platform.config {
            install(WebSockets)
            defaultRequest { url("https://example.invalid") }
        }
        try {
            withTimeout(5_000) { block(client, mints, upgrades) }
        } finally {
            client.close()
            platform.close()
            withTimeout(5_000) { engine.coroutineContext[Job]?.join() }
            dispatcher.executorService.shutdown()
            assertTrue(dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            pool.evictAll() // Only the fixture pool was ever installed in derived clients.
        }
    }

    private fun refused(status: Int) = runBlocking {
        fixture(status) { client, mints, upgrades ->
            val tokens = object : TokenManager by TokenManagerImpl() {
                override suspend fun snapshotCurrentScope() = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin")
            }
            val socket = EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val close = assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last())
            assertEquals("socket_error", close.reason)
            assertEquals(45_000L, close.recovery.retryAfterMs)
            assertEquals(67_500L, GenericEventsBackoff { 1.0 }.disconnected(close.recovery))
            assertEquals(1, mints.get())
            assertEquals(1, upgrades.get())
        }
    }

    @Test fun productionFactoryPreserves429RetryAfterAndMintsOnlyOnce() = refused(429)
    @Test fun productionFactoryPreserves503RetryAfter() = refused(503)

    @Test fun productionFactoryPendingAttemptCancelsWithoutSecondProof() = runBlocking {
        fixture { client, mints, upgrades ->
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pendingCalls = AtomicInteger()
            assertIs<OkHttpEngine>(client.engine).config.webSocketFactory = WebSocket.Factory { request, _ ->
                pendingCalls.incrementAndGet()
                started.complete(Unit)
                object : WebSocket {
                    override fun request() = request
                    override fun queueSize() = 0L
                    override fun send(text: String) = error("unexpected send")
                    override fun send(bytes: ByteString) = error("unexpected send")
                    override fun close(code: Int, reason: String?): Boolean { stopped.complete(Unit); return true }
                    override fun cancel() { stopped.complete(Unit) }
                }
            }
            val tokens = object : TokenManager by TokenManagerImpl() {
                override suspend fun snapshotCurrentScope() = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin")
            }
            val job = launch {
                DefaultNotificationsRealtimeClient(EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted))
                    .connect().collect { error("pending attempt must not deliver") }
            }
            started.await()
            job.cancelAndJoin()
            stopped.await()
            assertEquals(1, mints.get())
            assertEquals(1, pendingCalls.get())
            assertEquals(0, upgrades.get(), "Pending factory never calls the terminal HTTP transport")
        }
    }

    @Test fun productionFactoryLeavesRoomUpgradeOutsideEventsObserver() = runBlocking {
        fixture(503) { client, _, upgrades ->
            val failure = runCatching {
                client.prepareRequest {
                    url("wss://example.invalid/api/v2/rooms/fixture/ws?status=503")
                    headers.append(HttpHeaders.SecWebSocketProtocol, "silo.room.v2")
                }.execute { error("refused room must fail") }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure is EventsUpgradeFailure)
            assertEquals(1, upgrades.get())
        }
    }
}
