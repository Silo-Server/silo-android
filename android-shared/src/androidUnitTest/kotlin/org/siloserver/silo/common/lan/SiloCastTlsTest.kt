package org.siloserver.silo.common.lan

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SiloCastTlsTest {
    @Test
    fun androidClientAndServerCompletePskHandshake() {
        val listener = ServerSocket(0)
        val accepted = CompletableFuture<SiloCastTlsSession>()
        thread(isDaemon = true, name = "silocast-tls-test-server") {
            runCatching { SiloCastTls.accept(listener.accept()) }
                .onSuccess(accepted::complete)
                .onFailure(accepted::completeExceptionally)
        }

        val client = SiloCastTls.connect("127.0.0.1", listener.localPort, 5_000)
        val server = accepted.get(10, TimeUnit.SECONDS)
        try {
            val request = "ping".encodeToByteArray()
            client.output.write(request)
            client.output.flush()
            assertContentEquals(request, server.input.readBytes(request.size))

            val response = "pong".encodeToByteArray()
            server.output.write(response)
            server.output.flush()
            assertContentEquals(response, client.input.readBytes(response.size))
        } finally {
            client.close()
            server.close()
            listener.close()
        }
    }

    private fun java.io.InputStream.readBytes(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            check(read >= 0) { "TLS stream closed after $offset of $count bytes." }
            offset += read
        }
        return result
    }
}
