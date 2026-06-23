package com.continuum.app.common.pairing

import android.util.Log
import com.continuum.app.pairing.PairingFrame
import com.continuum.app.pairing.PairingFrameBuffer
import com.continuum.app.pairing.PairingMessage
import com.continuum.app.pairing.PairingMessageCodec
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
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector

/**
 * The fixed (non-secret) pre-shared key + identity compiled into the app. This
 * is NOT an authentication boundary — it merely lets the tvOS NWListener and
 * Android sockets negotiate TLS without certificate management. Must match the
 * silo-apple [PairingSession.tlsParameters] byte-for-byte.
 */
internal object PairingPsk {
    val key: ByteArray = "silo-companion-pairing-v1".toByteArray(Charsets.UTF_8)
    val identity: ByteArray = "silo-pairing".toByteArray(Charsets.UTF_8)

    const val tls12CipherSuite: Int = CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256
    const val tls13CipherSuite: Int = CipherSuite.TLS_AES_128_GCM_SHA256
}

/**
 * A [PairingTransport] over a TLS-PSK server handshake on an accepted socket,
 * matching the iOS receiver exactly (PSK = "silo-companion-pairing-v1", PSK
 * identity = "silo-pairing", and the PSK cipher suites Apple offers for the
 * same `PairingSession.tlsParameters()` used by iOS and tvOS.
 *
 * BouncyCastle's blocking stream mode waits for the peer's first application
 * record before returning from TLS 1.3 PSK accept with iOS clients. That
 * deadlocks the pairing protocol because the phone waits for TV `hello` first.
 * This transport therefore drives [TlsServerProtocol] in non-blocking mode over
 * the raw socket, returns as soon as TLS is connected, then writes the TV's
 * first framed message immediately like tvOS does.
 */
class TlsPskPairingTransport private constructor(
    private val socket: Socket,
    private val protocol: TlsServerProtocol,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
) : PairingTransport {
    private val protocolMutex = Mutex()

    override val incoming: Flow<PairingMessage> = callbackFlow {
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

    override suspend fun send(message: PairingMessage) {
        val payload = PairingMessageCodec.encode(message).toByteArray(Charsets.UTF_8)
        val framed = PairingFrame.encode(payload)
        protocolMutex.withLock {
            protocol.writeApplicationData(framed, 0, framed.size)
            flushOutputLocked()
        }
    }

    override fun close() = closeQuietly()

    private fun closeQuietly() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }

    private suspend fun drainIncoming(buffer: PairingFrameBuffer): List<PairingMessage> =
        protocolMutex.withLock { drainIncomingLocked(buffer) }

    private fun drainIncomingLocked(buffer: PairingFrameBuffer): List<PairingMessage> {
        val messages = mutableListOf<PairingMessage>()
        val plain = ByteArray(64 * 1024)
        while (protocol.getAvailableInputBytes() > 0) {
            val read = protocol.readInput(plain, 0, plain.size)
            if (read <= 0) break
            val received = plain.copyOf(read)
            for (payload in buffer.append(received)) {
                messages += PairingMessageCodec.decode(payload.toString(Charsets.UTF_8))
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
        private const val TAG = "TlsPskPairing"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000

        /**
         * Perform the TLS-PSK server handshake over [socket] and return a ready
         * transport. Blocking — call off the main thread (the advertiser's
         * accept loop runs on [Dispatchers.IO]).
         */
        fun accept(socket: Socket): TlsPskPairingTransport {
            Log.i(TAG, "starting TLS-PSK accept")
            val previousSoTimeout = socket.soTimeout
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val crypto = BcTlsCrypto(SecureRandom())
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()
            val protocol = TlsServerProtocol()
            val server = SiloPskTlsServer(crypto)
            try {
                protocol.accept(server)
                flushOutput(protocol, rawOut)
                val chunk = ByteArray(64 * 1024)
                while (!protocol.isConnected) {
                    val read = rawIn.read(chunk)
                    if (read < 0) {
                        throw EOFException("Pairing TLS handshake closed before completion")
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
            return TlsPskPairingTransport(
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
 * TLS server pinned to the fixed pairing PSK and the AES-128-GCM PSK suites
 * used by the Apple pairing stack.
 *
 * Network.framework with `sec_protocol_options_add_pre_shared_key` sends a TLS
 * 1.2 PSK ClientHello with `TLS_PSK_WITH_AES_128_GCM_SHA256` and no
 * `supported_versions` extension. Keeping the TLS 1.3 external-PSK hook lets
 * command-line probes and future Apple stacks negotiate TLS 1.3 when they
 * actually offer it, but the Apple app path relies on the TLS 1.2 identity
 * manager passed to [PSKTlsServer].
 */
private class SiloPskTlsServer(
    private val bcCrypto: BcTlsCrypto,
) : PSKTlsServer(
    bcCrypto,
    object : TlsPSKIdentityManager {
        override fun getHint(): ByteArray? = null

        override fun getPSK(identity: ByteArray?): ByteArray = PairingPsk.key
    },
) {

    override fun getCipherSuites(): IntArray = intArrayOf(
        PairingPsk.tls13CipherSuite,
        PairingPsk.tls12CipherSuite,
    )

    override fun getSupportedVersions(): Array<ProtocolVersion> = arrayOf(
        ProtocolVersion.TLSv13,
        ProtocolVersion.TLSv12,
    )

    override fun allowCertificateStatus(): Boolean = false

    /**
     * TLS 1.3 external-PSK hook: the client offers identities in its
     * pre_shared_key extension; return the fixed pairing PSK for our known
     * identity (and, defensively, regardless of the offered identity — the key
     * is fixed and non-secret). Returning a [TlsPSKExternal] selects PSK-only
     * key exchange with no certificate, mirroring iOS.
     */
    override fun getExternalPSK(identities: Vector<*>?): TlsPSKExternal =
        BasicTlsPSKExternal(
            PairingPsk.identity,
            bcCrypto.createSecret(PairingPsk.key),
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
