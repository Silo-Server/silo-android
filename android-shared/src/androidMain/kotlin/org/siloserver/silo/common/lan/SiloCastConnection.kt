package org.siloserver.silo.common.lan

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.siloserver.silo.common.cast.SiloCastFrame
import org.siloserver.silo.common.cast.SiloCastFrameBuffer

/**
 * A dialed SiloCast control channel that carries whole frame payloads, so the
 * phone's controller is the same over TLS-PSK, plain TCP, and `sealed`.
 * Blocking; call on IO. Reads have a single consumer (the read loop), and
 * callers serialize writes.
 */
interface SiloCastConnection {
    val isConnected: Boolean

    fun writeFrame(payload: ByteArray)

    /** The next frame payload, or null once the peer has closed. */
    fun readFrame(): ByteArray?

    fun close()
}

/** Length-prefixed frames over a byte stream: plain TCP or TLS application data. */
internal class StreamSiloCastConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val isOpen: () -> Boolean,
    private val onClose: () -> Unit,
) : SiloCastConnection {
    private val frames = SiloCastFrameBuffer()
    private val ready = ArrayDeque<ByteArray>()
    private val chunk = ByteArray(8 * 1024)

    override val isConnected: Boolean get() = isOpen()

    override fun writeFrame(payload: ByteArray) {
        output.write(SiloCastFrame.encode(payload))
        output.flush()
    }

    override fun readFrame(): ByteArray? {
        while (ready.isEmpty()) {
            val read = input.read(chunk)
            if (read < 0) return null
            if (read > 0) ready.addAll(frames.append(chunk.copyOf(read)))
        }
        return ready.removeFirst()
    }

    override fun close() = onClose()
}

/**
 * Level `sealed` over a keyed [inner] connection: every frame is sealed with
 * the next sequence number for its direction, and a frame that fails to open
 * ends the connection (the Roku does the same, without answering).
 */
internal class SealedSiloCastConnection(
    private val inner: SiloCastConnection,
    private val keys: SiloCastSealed.SessionKeys,
) : SiloCastConnection {
    private val writeLock = Any()
    private var sendSeq = 0L
    private var recvSeq = 0L

    override val isConnected: Boolean get() = inner.isConnected

    override fun writeFrame(payload: ByteArray) {
        // The sequence number must follow wire order.
        synchronized(writeLock) {
            inner.writeFrame(SiloCastSealed.seal(keys, SiloCastSealed.FROM_PHONE, sendSeq, payload))
            sendSeq += 1
        }
    }

    override fun readFrame(): ByteArray? {
        val body = inner.readFrame() ?: return null
        val opened = SiloCastSealed.open(keys, SiloCastSealed.FROM_TV, recvSeq, body)
            ?: throw IOException("A sealed SiloCast frame did not open.")
        recvSeq += 1
        return opened
    }

    override fun close() = inner.close()
}

fun SiloCastTlsClientSession.asConnection(): SiloCastConnection =
    StreamSiloCastConnection(input = input, output = output, isOpen = { isConnected }, onClose = ::close)
