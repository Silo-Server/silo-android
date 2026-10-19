package org.siloserver.silo.common.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.IdentityHashMap

/**
 * `throughput_kbps` for a plan's `plan_summary`: the rate the player's long
 * media requests received data, counted only while a request was receiving
 * (*Playback samples* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`). The server keeps the
 * highest as this device's `playback_sample`, a lower bound on the link.
 *
 * Listens to the player's media data sources; only network transfers count. A
 * request receives from its first read to its last, and the bytes of the first
 * read, which arrived before anything timed them, do not count. Only a request
 * that received for at least [MIN_RECEIVING_MS] counts, in bytes and in time:
 * a short one on a warm connection, such as one HLS segment, arrives at the
 * connection's burst rate, which can be twice what the link sustains. Time in
 * which several counted requests were receiving counts once. Pauses inside a
 * request do count: a progressive request held open while the buffer is full
 * reads low, which keeps the figure a lower bound.
 */
@OptIn(UnstableApi::class)
class PlaybackThroughputMeter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : TransferListener {
    private class Request(var firstMs: Long) {
        var lastMs = firstMs
        var bytes = 0L
    }

    private val lock = Any()

    /** Requests that have had their first byte and not ended. */
    private val receiving = IdentityHashMap<DataSource, Request>()

    /** The receiving spans of the requests counted since the last [takeKbps], and their bytes. */
    private val counted = mutableListOf<LongRange>()
    private var countedBytes = 0L

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = clock()
        synchronized(lock) {
            val request = receiving[source]
            if (request == null) {
                receiving[source] = Request(now)
            } else {
                request.bytes += bytesTransferred
                request.lastMs = now
            }
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        synchronized(lock) {
            receiving.remove(source)?.let(::count)
        }
    }

    /**
     * The rate since the last call, then starts over: kbps, at most
     * [MAX_THROUGHPUT_KBPS], or null when no request received for
     * [MIN_RECEIVING_MS] or the rate is under [MIN_THROUGHPUT_KBPS].
     */
    fun takeKbps(): Int? = synchronized(lock) {
        // A long request still open counts what it has so far and carries on
        // into the next count from its last byte.
        for (request in receiving.values) {
            if (count(request)) {
                request.firstMs = request.lastMs
                request.bytes = 0
            }
        }
        val ms = unionMs(counted)
        val kbps = if (ms < MIN_RECEIVING_MS) null else countedBytes * 8 / ms
        counted.clear()
        countedBytes = 0
        kbps?.takeIf { it >= MIN_THROUGHPUT_KBPS }?.coerceAtMost(MAX_THROUGHPUT_KBPS.toLong())?.toInt()
    }

    /** Counts [request] if it received for [MIN_RECEIVING_MS]; true when it did. */
    private fun count(request: Request): Boolean {
        if (request.lastMs - request.firstMs < MIN_RECEIVING_MS) return false
        counted += request.firstMs..request.lastMs
        countedBytes += request.bytes
        return true
    }

    companion object {
        /** A request that received for less than this does not count, and neither does less than this in all. */
        const val MIN_RECEIVING_MS = 1_000L

        /** The bounds the server accepts, as for a speed test and `bandwidth_estimate_kbps`. */
        const val MIN_THROUGHPUT_KBPS = 100
        const val MAX_THROUGHPUT_KBPS = 1_000_000

        /** How long [spans] cover between them, overlaps once. */
        private fun unionMs(spans: List<LongRange>): Long {
            var total = 0L
            var end = Long.MIN_VALUE
            for (span in spans.sortedBy { it.first }) {
                if (span.last > end) {
                    total += span.last - maxOf(span.first, end)
                    end = span.last
                }
            }
            return total
        }
    }
}
