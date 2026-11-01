package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.url
import io.ktor.client.request.prepareRequest
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

/** Resolved Ktor/OkHttp bytecode, a stub factory/listener and synthetic HTTP responses. No sockets. */
class EventsUpgradeOkHttpTest {
    private class NoSockets : SocketFactory() {
        override fun createSocket(): Socket = error("socket use forbidden")
        override fun createSocket(host: String, port: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("socket use forbidden")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("socket use forbidden")
    }

    private suspend fun refusal(status: Int, retryAfter: String?, check: (NotificationRealtimeEvent.Closed) -> Unit) {
        val mints = AtomicInteger()
        val upgrades = AtomicInteger()
        val failures = AtomicInteger()
        val transport = OkHttpClient.Builder()
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = error("DNS use forbidden") }).socketFactory(NoSockets())
            .eventsUpgradeResponses()
            // Terminal in-process transport: never calls proceed, DNS, connect or a network interceptor.
            .addInterceptor { chain ->
                val request = chain.request()
                val mint = request.url.encodedPath.endsWith("ws-ticket")
                val code = if (mint) 200 else status
                val raw = if (mint) {
                    mints.incrementAndGet()
                    """{"ticket":"fake-proof","expires_in":30,"max_connection_seconds":300,"protocol":"silo.events.v2"}"""
                } else {
                    upgrades.incrementAndGet()
                    "plain refusal"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .header("Content-Type", "application/json")
                    .apply { if (!mint && retryAfter != null) header("Retry-After", retryAfter) }
                    .body(raw.toResponseBody()).build()
            }.build()
        val factory = WebSocket.Factory { request, listener ->
            transport.newWebSocket(request, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failures.incrementAndGet()
                    listener.onFailure(webSocket, t, response)
                }
            })
        }
        val client = HttpClient(OkHttp) {
            engine { preconfigured = transport; webSocketFactory = factory }
            install(WebSockets)
            defaultRequest { url("https://example.invalid") }
        }
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin")
        }
        try {
            withTimeout(5_000) {
                val socket = EventsSocketV2Api(client, tokens, ApiV2Gate.Unrestricted)
                check(assertIs<NotificationRealtimeEvent.Closed>(DefaultNotificationsRealtimeClient(socket).connect().toList().last()))
                assertEquals(1, mints.get(), "one ticket per actual attempt")
                assertEquals(1, upgrades.get(), "no engine or transport retry")
                assertEquals(1, failures.get(), "one failed handshake callback")
            }
        } finally {
            client.close()
            withTimeout(5_000) { client.engine.coroutineContext[Job]?.join() }
            transport.dispatcher.executorService.shutdown()
            assertTrue(transport.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
            transport.connectionPool.evictAll() // This pool belongs only to this fixture.
        }
    }

    @Test fun pendingUpgradeCancellationJoinsEngineWithoutTerminalDeliveryOrSecondAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = HttpClient(OkHttp) {
            engine {
                webSocketFactory = WebSocket.Factory { request, _ ->
                    calls.incrementAndGet()
                    started.complete(Unit)
                    object : WebSocket {
                        override fun request() = request
                        override fun queueSize() = 0L
                        override fun send(text: String) = error("unexpected send")
                        override fun send(bytes: ByteString) = error("unexpected send")
                        override fun close(code: Int, reason: String?): Boolean { cancelled.complete(Unit); return true }
                        override fun cancel() { cancelled.complete(Unit) }
                    }
                }
            }
            install(WebSockets)
        }
        try {
            withTimeout(5_000) {
                val collection = launch {
                    client.prepareRequest { url("wss://example.invalid/api/v2/events/ws") }.execute {
                        error("pending handshake must not deliver a response")
                    }
                }
                started.await()
                collection.cancelAndJoin()
                cancelled.await()
                assertEquals(1, calls.get())
            }
        } finally {
            client.close()
            withTimeout(5_000) { client.engine.coroutineContext[Job]?.join() }
        }
    }

    @Test fun hugeUpgradeRetryAfterSaturatesSafely() = runBlocking {
        refusal(503, Long.MAX_VALUE.toString()) { close ->
            assertEquals(Long.MAX_VALUE / 1000 * 1000, close.recovery.retryAfterMs)
            assertEquals(Long.MAX_VALUE, GenericEventsBackoff { 1.0 }.disconnected(close.recovery))
        }
    }

    private fun longRetryAfter(status: Int) = runBlocking {
        refusal(status, "45") { close ->
            assertEquals(45_000, close.recovery.retryAfterMs)
            assertEquals(67_500, GenericEventsBackoff { 1.0 }.disconnected(close.recovery))
            assertEquals("socket_error", close.reason)
        }
    }

    private fun upgradeAuth(status: Int) = runBlocking {
        refusal(status, null) { close ->
            assertEquals("socket_error", close.reason)
            assertEquals(5_000, GenericEventsBackoff { 0.0 }.disconnected(close.recovery))
        }
    }

    @Test fun realEngine429PreservesRetryAfterBeforeExceptionalResponseCompletion() = longRetryAfter(429)
    @Test fun realEngine503PreservesRetryAfterBeforeExceptionalResponseCompletion() = longRetryAfter(503)
    @Test fun upgrade401IsRetryableSocketFailureRatherThanMintAuthFailure() = upgradeAuth(401)
    @Test fun upgrade403IsRetryableSocketFailureRatherThanMintAuthFailure() = upgradeAuth(403)
}
