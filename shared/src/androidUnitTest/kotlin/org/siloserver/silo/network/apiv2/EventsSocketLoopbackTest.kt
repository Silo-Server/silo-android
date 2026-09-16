package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.network.*
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.test.*

class EventsSocketLoopbackTest {
    @Test fun actualUpgradeSelectsProtocolAndReconnectUsesFreshProof(): Unit = runBlocking {
        val server = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
        val served = CompletableFuture<Unit>()
        thread(isDaemon = true) {
            try {
                repeat(2) { attempt ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        assertEquals("POST /api/v2/events/ws-ticket HTTP/1.1",input.readLine())
                        while (!input.readLine().isNullOrEmpty()) { }
                        val body = """{"ticket":"proof-$attempt","expires_in":30,"max_connection_seconds":300,"protocol":"silo.events.v2"}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        socket.getOutputStream().flush()
                    }
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val first = input.readLine()
                        assertTrue(first.startsWith("GET /api/v2/events/ws?channels=catalog "))
                        val headers = mutableMapOf<String,String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        assertEquals("silo.events.v2, silo.ticket.proof-$attempt",headers["sec-websocket-protocol"])
                        assertNull(headers["authorization"]); assertNull(headers["x-profile-token"])
                        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers.getValue("sec-websocket-key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: silo.events.v2\r\n\r\n".toByteArray())
                        val text = """{"type":"subscribed","channels":["catalog"]}""".toByteArray()
                        output.write(byteArrayOf(0x81.toByte(),text.size.toByte())); output.write(text); output.flush()
                        // Read the client's subscribe bytes before ending the socket.
                        socket.getInputStream().read(ByteArray(512))
                    }
                }
                served.complete(Unit)
            } catch (error: Throwable) { served.completeExceptionally(error) }
        }
        val origin = "http://127.0.0.1:${server.localPort}"
        val owner = AuthScopeSnapshot("server","profile",origin,null,identityGeneration=1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
        val client = HttpClient(OkHttp) { install(WebSockets); defaultRequest { url(origin) } }
        try {
            val api = EventsSocketV2Api(client,tokens, ApiV2Gate.Unrestricted)
            repeat(2) {
                val frame = withTimeout(5000) { api.frames(listOf("catalog")).take(1).toList().single() }
                assertTrue(frame.contains("subscribed"))
            }
            served.get(5,java.util.concurrent.TimeUnit.SECONDS)
        } finally { client.close(); server.close() }
    }
}
