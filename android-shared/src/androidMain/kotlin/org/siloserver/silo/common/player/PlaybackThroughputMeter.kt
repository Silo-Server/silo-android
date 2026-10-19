package org.siloserver.silo.common.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Collections
import java.util.IdentityHashMap

/**
 * `throughput_kbps` for a plan's `plan_summary`: the rate the player's media
 * requests received data, counted only while a request was receiving
 * (*Playback samples* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`). The server keeps the
 * highest as this device's `playback_sample`, a lower bound on the link.
 *
 * Listens to the player's media data sources; only network transfers count.
 * A request's clock starts at its first read, so the bytes of that read, which
 * arrived before anything timed them, do not count. Time counts while at least
 * one request has had its first byte and not ended, and each such stretch ends
 * at its last byte, so a request left open after its last byte adds nothing.
 * Pauses between one request's bytes do count: a progressive request held open
 * while the buffer is full reads low, which keeps the figure a lower bound.
 */
@UnstableApi
class PlaybackThroughputMeter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : TransferListener {
    private val lock = Any()

    /** Requests that have had their first byte and not ended. */
    private val receiving: MutableSet<DataSource> = Collections.newSetFromMap(IdentityHashMap())
    private var stretchStartMs: Long? = null
    private var lastByteMs = 0L
    private var bytes = 0L
    private var receivingMs = 0L

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = clock()
        synchronized(lock) {
            if (receiving.add(source)) {
                if (stretchStartMs == null) stretchStartMs = now
            } else {
                bytes += bytesTransferred
            }
            lastByteMs = now
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        synchronized(lock) {
            if (receiving.remove(source) && receiving.isEmpty()) closeStretch()
        }
    }

    /**
     * The rate since the last call, then starts over: kbps, at most
     * [MAX_THROUGHPUT_KBPS], or null after less than [MIN_RECEIVING_MS] of
     * receiving.
     */
    fun takeKbps(): Int? = synchronized(lock) {
        closeStretch()
        // Requests still open carry on into the next count from their last byte.
        if (receiving.isNotEmpty()) stretchStartMs = lastByteMs
        val kbps = if (receivingMs < MIN_RECEIVING_MS) null else bytes * 8 / receivingMs
        bytes = 0
        receivingMs = 0
        kbps?.takeIf { it >= 1 }?.coerceAtMost(MAX_THROUGHPUT_KBPS.toLong())?.toInt()
    }

    private fun closeStretch() {
        stretchStartMs?.let { receivingMs += lastByteMs - it }
        stretchStartMs = null
    }

    companion object {
        /** Less receiving than this gives no figure. */
        const val MIN_RECEIVING_MS = 1_000L

        /** The highest rate the server accepts, as for a speed test. */
        const val MAX_THROUGHPUT_KBPS = 1_000_000
    }
}
