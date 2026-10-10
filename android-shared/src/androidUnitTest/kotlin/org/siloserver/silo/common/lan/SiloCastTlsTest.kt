package org.siloserver.silo.common.lan

import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

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

    /**
     * BouncyCastle zeroes the PSK buffer after a TLS 1.2 handshake. The server
     * once handed it a shared key array, so one TLS 1.2 client wiped the key
     * for the process and every later handshake failed.
     */
    @Test
    fun tls12ClientCannotWipeTheReceiversKey() {
        val listener = ServerSocket(0)
        val servers = Collections.synchronizedList(mutableListOf<SiloCastTlsSession>())
        val worker = thread(isDaemon = true, name = "silocast-tls-test-server") {
            repeat(3) { runCatching { servers += SiloCastTls.accept(listener.accept()) } }
        }
        try {
            repeat(2) {
                val socket = Socket("127.0.0.1", listener.localPort)
                val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
                // Offers only TLS 1.2, so a completed handshake is a TLS 1.2 one.
                protocol.connect(Tls12PskClient(BcTlsCrypto(SecureRandom())))
                protocol.close()
                socket.close()
            }
            // An ordinary (TLS 1.3) client still gets through afterwards.
            SiloCastTls.connect("127.0.0.1", listener.localPort, 5_000).close()
        } finally {
            // The last server-side handshake can still be finishing.
            worker.join(5_000)
            listener.close()
            synchronized(servers) { servers.toList() }.forEach { it.close() }
        }
    }

    private class Tls12PskClient(crypto: BcTlsCrypto) : PSKTlsClient(
        crypto,
        BasicTlsPSKIdentity("silo-cast".toByteArray(), "silo-cast-v1".toByteArray()),
    ) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv12)
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
