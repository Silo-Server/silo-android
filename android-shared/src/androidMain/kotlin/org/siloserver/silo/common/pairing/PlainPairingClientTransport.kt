package org.siloserver.silo.common.pairing

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.siloserver.silo.pairing.PairingFrame
import org.siloserver.silo.pairing.PairingFrameBuffer
import org.siloserver.silo.pairing.PairingMessage
import org.siloserver.silo.pairing.PairingMessageCodec

/**
 * The pairing protocol over plain TCP, for a Roku (`_silopair-roku._tcp`,
 * silo-roku `docs/companion-pairing.md` section 2): Roku has no TLS on a
 * listening socket. The frames and messages are those [TlsPskPairingClientTransport]
 * carries. Nothing secret crosses it: the server hands the TV its tokens over
 * HTTPS, and the person compares the code the TV shows with the server's.
 */
class PlainPairingClientTransport private constructor(
    private val socket: Socket,
) : PairingTransport {
    private val writeMutex = Mutex()

    override val incoming: Flow<PairingMessage> = callbackFlow {
        val input = socket.getInputStream()
        val buffer = PairingFrameBuffer()
        val chunk = ByteArray(16 * 1024)
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
            this@PlainPairingClientTransport.close()
            this@callbackFlow.close()
        }
        awaitClose { close() }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(message: PairingMessage) {
        val framed = PairingFrame.encode(PairingMessageCodec.encode(message).toByteArray(Charsets.UTF_8))
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                socket.getOutputStream().apply {
                    write(framed)
                    flush()
                }
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "PlainPairingClient"
        private const val CONNECT_TIMEOUT_MS = 10_000

        suspend fun connect(host: String, port: Int): PlainPairingClientTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    Log.i(TAG, "Connecting to $host:$port")
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                    PlainPairingClientTransport(socket)
                } catch (t: Throwable) {
                    Log.e(TAG, "Plain pairing connection failed to $host:$port", t)
                    runCatching { socket.close() }
                    throw t
                }
            }
    }
}

/** How the phone connects to a pairing TV. The browsed service type decides; nothing falls back. */
enum class CompanionPairingTransportKind {
    /** Apple TV and Android TV (`_silopair._tcp`). */
    TlsPsk,

    /** Roku (`_silopair-roku._tcp`). */
    Plain,
    ;

    companion object {
        fun of(target: CompanionPairingTarget): CompanionPairingTransportKind = if (target.isRoku) Plain else TlsPsk
    }
}

/** The app's [CompanionPairingTransportFactory]: TLS-PSK to Apple TV and Android TV, plain TCP to a Roku. */
object CompanionPairingTransports : CompanionPairingTransportFactory {
    override suspend fun connect(target: CompanionPairingTarget): PairingTransport =
        when (CompanionPairingTransportKind.of(target)) {
            CompanionPairingTransportKind.TlsPsk -> TlsPskPairingClientTransport.connect(target.host, target.port)
            CompanionPairingTransportKind.Plain -> PlainPairingClientTransport.connect(target.host, target.port)
        }
}
