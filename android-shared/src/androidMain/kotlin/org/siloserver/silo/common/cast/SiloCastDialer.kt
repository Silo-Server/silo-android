package org.siloserver.silo.common.cast

import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import org.siloserver.silo.cast.SiloCastMessage
import org.siloserver.silo.cast.SiloCastPeerRole
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.lan.SealedSiloCastConnection
import org.siloserver.silo.common.lan.SiloCastConnection
import org.siloserver.silo.common.lan.SiloCastSealed
import org.siloserver.silo.common.lan.SiloCastTls
import org.siloserver.silo.common.lan.StreamSiloCastConnection
import org.siloserver.silo.common.lan.asConnection

/** A sealed Roku refused this phone's pairing key: it was reset, or never matched. */
class SiloCastPairingRefusedException : IOException("The TV refused the pairing key.")

/**
 * Opens a receiver's control channel over the transport its discovery record
 * fixed ([SiloCastTarget.transport]). There is no fallback between them: a
 * TLS-PSK TV that fails the handshake is never retried in plain text.
 * Blocking; call on IO.
 */
object SiloCastDialer {
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private const val PROBE_TIMEOUT_MS = 3_000
    private val json = Json { ignoreUnknownKeys = true }

    fun dial(target: SiloCastTarget, pairingKey: ByteArray?, connectTimeoutMs: Int): SiloCastConnection =
        when (target.transport) {
            SiloCastTransport.TlsPsk -> SiloCastTls.connect(target.host, target.port, connectTimeoutMs).asConnection()
            SiloCastTransport.Plain -> plain(target.host, target.port, connectTimeoutMs).second
            SiloCastTransport.Sealed -> sealed(
                target.host,
                target.port,
                connectTimeoutMs,
                requireNotNull(pairingKey) { "A sealed TV needs its pairing key." },
            )
        }

    /**
     * The hello a Roku at level `plain` writes as soon as it accepts a
     * connection, read to add a Roku by typed address. Null when none comes
     * (a sealed Roku waits for the phone's `sealed_hello`). The connection
     * closes without a phone hello, so the Roku never counts it as a controller.
     */
    fun probeRoku(host: String, port: Int, connectTimeoutMs: Int): SiloCastTarget? {
        val (socket, connection) = plain(host, port, connectTimeoutMs)
        try {
            socket.soTimeout = PROBE_TIMEOUT_MS
            val payload = connection.readFrame() ?: return null
            val hello = (SiloCastMessage.decodeOrNull(json, payload.decodeToString()) as? SiloCastMessage.Hello)?.hello
                ?: return null
            if (hello.role != SiloCastPeerRole.Tv || hello.deviceId.isBlank()) return null
            return SiloCastTarget(
                deviceId = hello.deviceId,
                name = hello.deviceName,
                host = host,
                port = port,
                version = SiloCastProtocol.negotiatedVersion(hello.supportedVersions) ?: 1,
                serviceName = hello.deviceName,
                serverId = hello.serverId,
                serverName = hello.serverName,
                serverIdentity = hello.serverIdentity,
                transport = SiloCastTransport.Plain,
            )
        } finally {
            connection.close()
        }
    }

    private fun plain(host: String, port: Int, connectTimeoutMs: Int): Pair<Socket, SiloCastConnection> {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.tcpNoDelay = true
            return socket to StreamSiloCastConnection(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                isOpen = { socket.isConnected && !socket.isClosed },
                onClose = { runCatching { socket.close() } },
            )
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    /**
     * The `sealed_hello` exchange (silo-roku `docs/silocontrol.md` section
     * 9.2): the phone's plain frame first, then the Roku's. A Roku that does
     * not know the key answers `close unauthorized` in plain text instead.
     */
    private fun sealed(host: String, port: Int, connectTimeoutMs: Int, key: ByteArray): SiloCastConnection {
        val (socket, plain) = plain(host, port, connectTimeoutMs)
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val keyId = SiloCastSealed.keyId(key)
            val nonce = SiloCastSealed.newNonce()
            plain.writeFrame(SiloCastSealed.helloText(keyId, nonce).encodeToByteArray())
            val reply = plain.readFrame() ?: throw EOFException("The TV closed the connection during pairing.")
            val hello = SiloCastSealed.readHello(reply.decodeToString())
            if (hello == null || hello.keyId != keyId) throw SiloCastPairingRefusedException()
            socket.soTimeout = 0
            return SealedSiloCastConnection(plain, SiloCastSealed.sessionKeys(key, nonce, hello.nonce))
        } catch (t: Throwable) {
            plain.close()
            throw t
        }
    }
}
