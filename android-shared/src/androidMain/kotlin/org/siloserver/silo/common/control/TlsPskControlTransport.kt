package org.siloserver.silo.common.control

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.tls.AlertLevel
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.siloserver.silo.control.SiloControlMessage
import org.siloserver.silo.control.SiloControlMessageCodec
import org.siloserver.silo.pairing.PairingFrame
import org.siloserver.silo.pairing.PairingFrameBuffer
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector

/**
 * The fixed (non-secret) pre-shared key + identity for the SiloControl channel.
 * As with pairing, this is NOT an authentication boundary — it merely lets the
 * tvOS/iOS Network.framework peers and Android sockets negotiate TLS without
 * certificate management. Must match silo-apple's
 * `SiloControlSession.tlsParameters()` byte-for-byte.
 */
internal object ControlPsk {
    val key: ByteArray = "silo-cast-v1".toByteArray(Charsets.UTF_8)
    val identity: ByteArray = "silo-cast".toByteArray(Charsets.UTF_8)

    const val tls12CipherSuite: Int = CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256
    const val tls13CipherSuite: Int = CipherSuite.TLS_AES_128_GCM_SHA256
}

/**
 * Server side of an accepted SiloControl connection: TLS-PSK over the raw
 * socket, then 4-byte big-endian length-prefixed JSON frames (identical to the
 * pairing channel's [PairingFrame]) carrying [SiloControlMessage]s.
 *
 * Deliberately a sibling of
 * [org.siloserver.silo.common.pairing.TlsPskPairingTransport] rather than a
 * refactor of it — same non-blocking BouncyCastle plumbing (see that class for
 * why blocking accept deadlocks against iOS TLS 1.3 PSK clients), different
 * PSK/identity and message codec. Kept separate so the control channel can
 * evolve without destabilizing pairing.
 */
class TlsPskControlTransport private constructor(
    private val socket: Socket,
    private val protocol: TlsServerProtocol,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
) {
    private val protocolMutex = Mutex()

    val incoming: Flow<SiloControlMessage> = callbackFlow {
        val buffer = PairingFrameBuffer()
        val chunk = ByteArray(64 * 1024)
        try {
            for (message in drainIncoming(buffer)) {
                trySend(message)
            }
            while (true) {
                val read = rawIn.read(chunk)
                if (read < 0) break // clean EOF
                if (read == 0) continue
                val messages = protocolMutex.withLock {
                    protocol.offerInput(chunk, 0, read)
                    flushOutputLocked()
                    drainIncomingLocked(buffer)
                }
                for (message in messages) {
                    trySend(message)
                }
            }
            close()
        } catch (t: Throwable) {
            close(t)
        }
        awaitClose { closeQuietly() }
    }.flowOn(Dispatchers.IO)

    suspend fun send(message: SiloControlMessage) {
        val payload = SiloControlMessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        val framed = PairingFrame.encode(payload)
        protocolMutex.withLock {
            protocol.writeApplicationData(framed, 0, framed.size)
            flushOutputLocked()
        }
    }

    fun close() = closeQuietly()

    private fun closeQuietly() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }

    private suspend fun drainIncoming(buffer: PairingFrameBuffer): List<SiloControlMessage> =
        protocolMutex.withLock { drainIncomingLocked(buffer) }

    private fun drainIncomingLocked(buffer: PairingFrameBuffer): List<SiloControlMessage> {
        val messages = mutableListOf<SiloControlMessage>()
        val plain = ByteArray(64 * 1024)
        while (protocol.getAvailableInputBytes() > 0) {
            val read = protocol.readInput(plain, 0, plain.size)
            if (read <= 0) break
            val received = plain.copyOf(read)
            for (payload in buffer.append(received)) {
                messages += SiloControlMessageCodec.decode(payload.toString(Charsets.UTF_8))
            }
        }
        return messages
    }

    private fun flushOutputLocked() {
        val out = ByteArray(64 * 1024)
        while (protocol.getAvailableOutputBytes() > 0) {
            val read = protocol.readOutput(out, 0, out.size)
            if (read <= 0) break
            rawOut.write(out, 0, read)
        }
        rawOut.flush()
    }

    companion object {
        private const val TAG = "TlsPskControl"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /**
         * Perform the TLS-PSK server handshake over [socket] and return a ready
         * transport. Blocking — call off the main thread (the advertiser's
         * accept loop runs on [Dispatchers.IO]).
         */
        fun accept(socket: Socket): TlsPskControlTransport {
            Log.i(TAG, "starting TLS-PSK accept")
            val previousSoTimeout = socket.soTimeout
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val crypto = BcTlsCrypto(SecureRandom())
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()
            val protocol = TlsServerProtocol()
            val server = SiloControlPskTlsServer(crypto)
            try {
                protocol.accept(server)
                flushOutput(protocol, rawOut)
                val chunk = ByteArray(64 * 1024)
                while (!protocol.isConnected) {
                    val read = rawIn.read(chunk)
                    if (read < 0) {
                        throw EOFException("Control TLS handshake closed before completion")
                    }
                    if (read == 0) continue
                    protocol.offerInput(chunk, 0, read)
                    flushOutput(protocol, rawOut)
                }
                flushOutput(protocol, rawOut)
            } finally {
                runCatching { socket.soTimeout = previousSoTimeout }
            }
            Log.i(TAG, "TLS-PSK accept connected")
            return TlsPskControlTransport(
                socket = socket,
                protocol = protocol,
                rawIn = rawIn,
                rawOut = rawOut,
            )
        }

        private fun flushOutput(protocol: TlsServerProtocol, rawOut: OutputStream) {
            val out = ByteArray(64 * 1024)
            while (protocol.getAvailableOutputBytes() > 0) {
                val read = protocol.readOutput(out, 0, out.size)
                if (read <= 0) break
                rawOut.write(out, 0, read)
            }
            rawOut.flush()
        }
    }
}

/**
 * TLS server pinned to the fixed control PSK and the AES-128-GCM PSK suites
 * used by the Apple control stack. See
 * [org.siloserver.silo.common.pairing.TlsPskPairingTransport]'s
 * `SiloPskTlsServer` for the TLS 1.2 / 1.3 negotiation notes — this is the
 * same shape with the "silo-cast-v1" key.
 */
private class SiloControlPskTlsServer(
    private val bcCrypto: BcTlsCrypto,
) : PSKTlsServer(
    bcCrypto,
    object : TlsPSKIdentityManager {
        override fun getHint(): ByteArray? = null

        override fun getPSK(identity: ByteArray?): ByteArray = ControlPsk.key
    },
) {

    override fun getCipherSuites(): IntArray = intArrayOf(
        ControlPsk.tls13CipherSuite,
        ControlPsk.tls12CipherSuite,
    )

    override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(
        ProtocolVersion.TLSv13,
        ProtocolVersion.TLSv12,
    )

    override fun allowCertificateStatus(): Boolean = false

    override fun getExternalPSK(identities: Vector<*>?): TlsPSKExternal =
        BasicTlsPSKExternal(
            ControlPsk.identity,
            bcCrypto.createSecret(ControlPsk.key),
            PRFAlgorithm.tls13_hkdf_sha256,
        )

    override fun notifyAlertRaised(
        alertLevel: Short,
        alertDescription: Short,
        message: String?,
        cause: Throwable?,
    ) {
        if (alertLevel == AlertLevel.fatal) {
            // Swallow — the transport surfaces the failure via the stream closing.
        }
    }
}
