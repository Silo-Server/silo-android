package org.siloserver.silo.common.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.IdentityHashMap
import org.siloserver.silo.domain.player.SpeedTestMeter

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
 *
 * A rate belongs to one network: the server files it under the connection of
 * the request that reports it. So when the default network ([networkId])
 * changes while a count runs, as seen at a transfer or at [takeKbps], that
 * count reports nothing.
 *
 * It also keeps when a media request last received bytes, which the
 * buffering-quality adapter reads to tell a dead link from a slow one.
 */
@OptIn(UnstableApi::class)
class PlaybackThroughputMeter(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** The default network, `PlaybackNetworkSnapshot.networkId`. */
    private val networkId: () -> Long? = { null },
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

    /** When a request last received bytes, on [clock]; see [lastByteAtMs]. */
    @Volatile private var lastByteAt: Long? = null

    /** The network this count started on, and whether another was seen since. */
    private var countNetwork: Long? = networkId()
    private var networkChanged = false

    private fun noteNetwork() {
        if (networkId() != countNetwork) networkChanged = true
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = clock()
        lastByteAt = now
        synchronized(lock) {
            noteNetwork()
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

    /** When any media request last received bytes, on [clock]; null before the first. Any thread. */
    fun lastByteAtMs(): Long? = lastByteAt

    /**
     * The rate since the last call, then starts over: kbps, at most
     * [SpeedTestMeter.MAX_KBPS], or null when no request received for
     * [MIN_RECEIVING_MS], the rate is under [SpeedTestMeter.MIN_KBPS] (the
     * bounds the server accepts, as for a speed test), or the network changed.
     */
    fun takeKbps(): Int? = synchronized(lock) {
        noteNetwork()
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
        val changed = networkChanged
        countNetwork = networkId()
        networkChanged = false
        if (changed) return@synchronized null
        kbps?.takeIf { it >= SpeedTestMeter.MIN_KBPS }?.coerceAtMost(SpeedTestMeter.MAX_KBPS.toLong())?.toInt()
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
