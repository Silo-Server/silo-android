package org.siloserver.silo.common.pairing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.siloserver.silo.pairing.PairingFrame
import org.siloserver.silo.pairing.PairingFrameBuffer
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingMessageCodec
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector

class TlsPskPairingClientTransport private constructor(
    private val socket: Socket,
    private val protocol: TlsClientProtocol,
) : PairingTransport {
    private val writeMutex = Mutex()

    override val incoming: Flow<PairingMessage> = callbackFlow {
        val input = protocol.inputStream
        val buffer = PairingFrameBuffer()
        val chunk = ByteArray(64 * 1024)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                buffer.append(chunk.copyOf(read)).forEach { payload ->
                    trySend(PairingMessageCodec.decode(payload.toString(Charsets.UTF_8)))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pairing client read failed", t)
        } finally {
            this@TlsPskPairingClientTransport.close()
            this@callbackFlow.close()
        }
        awaitClose { close() }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(message: PairingMessage) {
        val payload = PairingMessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        val framed = PairingFrame.encode(payload)
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                protocol.outputStream.write(framed)
                protocol.outputStream.flush()
            }
        }
    }

    override fun close() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "TlsPskPairingClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        suspend fun connect(host: String, port: Int): TlsPskPairingClientTransport =
            withContext(Dispatchers.IO) {
                connectBlocking(host, port)
            }

        private fun connectBlocking(host: String, port: Int): TlsPskPairingClientTransport {
            val socket = Socket()
            try {
                Log.i(TAG, "Connecting to $host:$port")
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val previousSoTimeout = socket.soTimeout
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
                protocol.connect(
                    SiloPskTlsClient(
                        BcTlsCrypto(SecureRandom()),
                        BasicTlsPSKIdentity(PairingPsk.identity, PairingPsk.key),
                    ),
                )
                runCatching { socket.soTimeout = previousSoTimeout }
                Log.i(TAG, "TLS pairing connection established to $host:$port")
                return TlsPskPairingClientTransport(socket = socket, protocol = protocol)
            } catch (t: Throwable) {
                Log.e(TAG, "TLS pairing connection failed to $host:$port", t)
                runCatching { socket.close() }
                throw t
            }
        }
    }
}

private class SiloPskTlsClient(
    private val bcCrypto: BcTlsCrypto,
    identity: BasicTlsPSKIdentity,
) : PSKTlsClient(bcCrypto, identity) {
    override fun getSupportedCipherSuites(): IntArray = intArrayOf(
        PairingPsk.tls13CipherSuite,
        PairingPsk.tls12CipherSuite,
    )

    override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(
        ProtocolVersion.TLSv13,
        ProtocolVersion.TLSv12,
    )

    override fun getExternalPSKs(): Vector<TlsPSKExternal> =
        Vector<TlsPSKExternal>().apply {
            add(
                BasicTlsPSKExternal(
                    PairingPsk.identity,
                    bcCrypto.createSecret(PairingPsk.key),
                    PRFAlgorithm.tls13_hkdf_sha256,
                ),
            )
        }
}
