package org.siloserver.silo.common.pairing

import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.Vector
import kotlin.concurrent.thread
import kotlin.test.Test
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

class TlsPskPairingTransportTest {
    /**
     * BouncyCastle zeroes the PSK buffer after a TLS 1.2 handshake. iPhones and
     * iPads pair over TLS 1.2, so a TV that handed it a shared key array
     * refused every phone after the first one until the app restarted.
     */
    @Test
    fun tls12PhoneCannotWipeTheTvsPairingKey() {
        val listener = ServerSocket(0)
        val servers = Collections.synchronizedList(mutableListOf<TlsPskPairingTransport>())
        val worker = thread(isDaemon = true, name = "pairing-tls-test-server") {
            repeat(3) { runCatching { servers += TlsPskPairingTransport.accept(listener.accept()) } }
        }
        try {
            // Two clients that offer only TLS 1.2, like Apple's
            // Network.framework, so each completed handshake is a TLS 1.2 one.
            repeat(2) { handshake(listener.localPort, Tls12PskClient(BcTlsCrypto(SecureRandom()))) }
            // A TLS 1.3-only client, the version Android phones negotiate,
            // still gets through afterwards.
            handshake(listener.localPort, Tls13PskClient(BcTlsCrypto(SecureRandom())))
        } finally {
            // The last server-side handshake can still be finishing.
            worker.join(5_000)
            listener.close()
            synchronized(servers) { servers.toList() }.forEach { it.close() }
        }
    }

    private fun handshake(port: Int, client: PSKTlsClient) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        protocol.connect(client)
        protocol.close()
        socket.close()
    }

    private class Tls12PskClient(crypto: BcTlsCrypto) : PSKTlsClient(
        crypto,
        BasicTlsPSKIdentity("silo-pairing".toByteArray(), "silo-companion-pairing-v1".toByteArray()),
    ) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv12)
    }

    private class Tls13PskClient(private val crypto: BcTlsCrypto) : PSKTlsClient(
        crypto,
        BasicTlsPSKIdentity("silo-pairing".toByteArray(), "silo-companion-pairing-v1".toByteArray()),
    ) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256)

        override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(ProtocolVersion.TLSv13)

        override fun getExternalPSKs(): Vector<TlsPSKExternal> = Vector<TlsPSKExternal>().apply {
            add(
                BasicTlsPSKExternal(
                    "silo-pairing".toByteArray(),
                    crypto.createSecret("silo-companion-pairing-v1".toByteArray()),
                    PRFAlgorithm.tls13_hkdf_sha256,
                ),
            )
        }
    }
}
