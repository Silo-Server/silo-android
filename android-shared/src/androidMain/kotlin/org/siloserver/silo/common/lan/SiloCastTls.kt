package org.siloserver.silo.common.lan

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Vector
import org.bouncycastle.tls.AlertLevel
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/**
 * TLS-PSK for the `_silocast._tcp` control channel, matching silo-apple's
 * `SiloLANTLS.parameters(psk: "silo-cast-v1", identity: "silo-cast")` — the
 * same fixed, non-secret key on every install: opportunistic confidentiality
 * only, NOT an authentication boundary. Trust comes from the hello serverId
 * check layered on top.
 *
 * The BouncyCastle mechanics mirror the pairing transport
 * ([org.siloserver.silo.common.pairing.TlsPskPairingTransport]) which is
 * hardware-validated against Apple's Network.framework: the server side runs
 * non-blocking (blocking accept deadlocks against iOS because the TV writes
 * the first application record), the client side runs blocking-stream mode,
 * and both offer the TLS 1.2 PSK suite Apple actually sends plus the TLS 1.3
 * external-PSK hook.
 */
object SiloCastTls {
    private val KEY = "silo-cast-v1".toByteArray(Charsets.UTF_8)
    private val IDENTITY = "silo-cast".toByteArray(Charsets.UTF_8)
    private const val TLS12_SUITE = CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256
    private const val TLS13_SUITE = CipherSuite.TLS_AES_128_GCM_SHA256
    private const val HANDSHAKE_TIMEOUT_MS = 10_000

    /**
     * Server-side handshake over an accepted socket. Blocking — call on
     * [kotlinx.coroutines.Dispatchers.IO]. Returns app-data streams; the TV
     * must write its first frame (hello) immediately after, like tvOS.
     */
    fun accept(socket: Socket): SiloCastTlsSession {
        val previousSoTimeout = socket.soTimeout
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val rawIn = socket.getInputStream()
        val rawOut = socket.getOutputStream()
        val protocol = TlsServerProtocol()
        try {
            protocol.accept(CastPskTlsServer(BcTlsCrypto(SecureRandom())))
            flushOutput(protocol, rawOut)
            val chunk = ByteArray(64 * 1024)
            while (!protocol.isConnected) {
                val read = rawIn.read(chunk)
                if (read < 0) throw EOFException("SiloCast TLS handshake closed before completion")
                if (read == 0) continue
                protocol.offerInput(chunk, 0, read)
                flushOutput(protocol, rawOut)
            }
            flushOutput(protocol, rawOut)
        } catch (t: Throwable) {
            // A LAN listener accepts arbitrary connections; a failed handshake
            // must not leak the fd or repeated probes exhaust descriptors.
            runCatching { protocol.close() }
            runCatching { socket.close() }
            throw t
        } finally {
            runCatching { socket.soTimeout = previousSoTimeout }
        }
        return SiloCastTlsSession(socket, protocol, rawIn, rawOut)
    }

    /**
     * Client-side handshake. Blocking — call on IO. Returns a session whose
     * streams carry application data; BouncyCastle stays internal to this
     * module.
     */
    fun connect(host: String, port: Int, connectTimeoutMs: Int): SiloCastTlsClientSession {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val previousSoTimeout = socket.soTimeout
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        try {
            protocol.connect(CastPskTlsClient(BcTlsCrypto(SecureRandom())))
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        } finally {
            runCatching { socket.soTimeout = previousSoTimeout }
        }
        return SiloCastTlsClientSession(socket, protocol)
    }

    private fun flushOutput(protocol: TlsServerProtocol, rawOut: OutputStream) {
        val out = ByteArray(64 * 1024)
        while (protocol.availableOutputBytes > 0) {
            val read = protocol.readOutput(out, 0, out.size)
            if (read <= 0) break
            rawOut.write(out, 0, read)
        }
        rawOut.flush()
    }

    private class CastPskTlsServer(
        private val bcCrypto: BcTlsCrypto,
    ) : PSKTlsServer(
        bcCrypto,
        object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray? = null

            override fun getPSK(identity: ByteArray?): ByteArray = KEY
        },
    ) {
        override fun getCipherSuites(): IntArray = intArrayOf(TLS13_SUITE, TLS12_SUITE)

        override fun getSupportedVersions(): Array<ProtocolVersion> =
            arrayOf(ProtocolVersion.TLSv13, ProtocolVersion.TLSv12)

        override fun allowCertificateStatus(): Boolean = false

        override fun getExternalPSK(identities: Vector<*>?): TlsPSKExternal =
            BasicTlsPSKExternal(IDENTITY, bcCrypto.createSecret(KEY), PRFAlgorithm.tls13_hkdf_sha256)

        override fun notifyAlertRaised(
            alertLevel: Short,
            alertDescription: Short,
            message: String?,
            cause: Throwable?,
        ) {
            if (alertLevel == AlertLevel.fatal) {
                // Swallow — the session surfaces the failure via stream close.
            }
        }
    }

    private class CastPskTlsClient(
        private val bcCrypto: BcTlsCrypto,
    ) : PSKTlsClient(
        bcCrypto,
        BasicTlsPSKIdentity(IDENTITY, KEY),
    ) {
        override fun getSupportedCipherSuites(): IntArray = intArrayOf(TLS13_SUITE, TLS12_SUITE)

        override fun getSupportedVersions(): Array<ProtocolVersion> =
            arrayOf(ProtocolVersion.TLSv13, ProtocolVersion.TLSv12)

        override fun getExternalPSKs(): Vector<TlsPSKExternal> =
            Vector<TlsPSKExternal>().apply {
                add(
                    BasicTlsPSKExternal(
                        IDENTITY,
                        bcCrypto.createSecret(KEY),
                        PRFAlgorithm.tls13_hkdf_sha256,
                    ),
                )
            }
    }
}

/** Client-side TLS session: blocking-mode BC protocol exposing app streams. */
class SiloCastTlsClientSession internal constructor(
    private val socket: Socket,
    private val protocol: TlsClientProtocol,
) {
    val input: InputStream get() = protocol.inputStream
    val output: OutputStream get() = protocol.outputStream

    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    fun close() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }
}

/**
 * App-data streams over the non-blocking server-side protocol. The reader
 * pumps raw socket bytes into the TLS engine on demand; the writer emits
 * records straight to the socket. Reads and writes may run on different
 * threads (receiver read loop vs state pusher) — each path synchronizes on
 * [protocol] so BC's engine is never re-entered concurrently.
 */
class SiloCastTlsSession(
    private val socket: Socket,
    private val protocol: TlsServerProtocol,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
) {
    val input: InputStream = object : InputStream() {
        private val one = ByteArray(1)

        // Reused across reads: this stream has a single consumer (the
        // receiver's read loop), so per-call 64KB allocations are pure churn.
        private val chunk = ByteArray(64 * 1024)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                synchronized(protocol) {
                    if (protocol.availableInputBytes > 0) {
                        return protocol.readInput(b, off, len)
                    }
                }
                val read = rawIn.read(chunk)
                if (read < 0) return -1
                if (read == 0) continue
                synchronized(protocol) {
                    protocol.offerInput(chunk, 0, read)
                    drainOutputLocked()
                }
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(protocol) {
                protocol.writeApplicationData(b, off, len)
                drainOutputLocked()
            }
        }

        override fun flush() {
            synchronized(protocol) { drainOutputLocked() }
        }
    }

    fun close() {
        // Close the socket FIRST: Java sockets have no write timeout, so a
        // drainOutputLocked() write blocked on a peer that stopped reading
        // holds the protocol monitor indefinitely. Taking that monitor here
        // would deadlock teardown forever; closing the socket interrupts the
        // blocked write and frees the monitor. Then best-effort close the
        // engine.
        runCatching { socket.close() }
        synchronized(protocol) { runCatching { protocol.close() } }
    }

    // Only touched inside synchronized(protocol) blocks, so a single shared
    // scratch buffer is safe and avoids a 64KB allocation per drain.
    private val drainBuffer = ByteArray(64 * 1024)

    private fun drainOutputLocked() {
        while (protocol.availableOutputBytes > 0) {
            val read = protocol.readOutput(drainBuffer, 0, drainBuffer.size)
            if (read <= 0) break
            rawOut.write(drainBuffer, 0, read)
        }
        rawOut.flush()
    }
}
