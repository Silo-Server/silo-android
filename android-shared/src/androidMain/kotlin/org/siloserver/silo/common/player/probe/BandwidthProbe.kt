package org.siloserver.silo.common.player.probe

import android.os.SystemClock
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2

/*
 * The bandwidth probe, ported from the web player's `bandwidth-probe.ts` with
 * its constants and windowing unchanged. Spec: the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`, *Probe on Android and Apple*.
 */

/** Bytes requested (`?size=`); the time budget usually ends the transfer first. */
const val PROBE_BYTES = 40_000_000

/** How long to keep timing once bytes flow. Enough to see past TCP ramp-up. */
const val PROBE_TIME_BUDGET_MS = 2_000L

/** A probe that has not delivered its first byte by then is abandoned. */
const val PROBE_FIRST_BYTE_DEADLINE_MS = 10_000L

/**
 * Slack past the time budget before a read that has stopped returning is cut
 * off; the rate is then taken from the bytes that did arrive.
 */
const val PROBE_STALLED_READ_GRACE_MS = 500L

/** Ramp-up excluded from the rate when the probe ran long enough to spare it. */
const val PROBE_WARMUP_MS = 300L

/** Shortest timed window, or smallest byte count, that yields a usable rate. */
const val PROBE_MIN_WINDOW_MS = 200L
const val PROBE_MIN_BYTES = 1_000_000L

/** Let the first screen settle before spending bandwidth on a probe. */
const val PROBE_START_DELAY_MS = 5_000L

/**
 * How long a reading stands for its server and network: playback starts send
 * it, and nothing measures that connection again, until it is this old.
 */
const val BANDWIDTH_READING_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000L

/** The contract bounds `bandwidth_estimate_kbps` to this range. */
const val MIN_ESTIMATE_KBPS = 100
const val MAX_ESTIMATE_KBPS = 1_000_000

/** How much one read asks for. */
private const val READ_BUFFER_BYTES = 64 * 1024

/** One probe download, timed: a rate or why there is none. */
sealed interface BandwidthProbeResult {
    /** [kbps] measured from [bytes] over [durationMs], the window the rate was taken over. */
    data class Measured(val kbps: Int, val bytes: Long, val durationMs: Int) : BandwidthProbeResult

    /** HTTP 429: the server allows another probe after [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Int) : BandwidthProbeResult

    /**
     * The server refused the download with [status] (0 when no exchange
     * happened), for example when a proxy-only policy has no proxy that
     * serves probes. It never falls back to measuring another path.
     */
    data class Unavailable(val status: Int) : BandwidthProbeResult

    /** The transfer moved too little data, in too short a window, to mean anything. */
    data object TooLittleData : BandwidthProbeResult

    /** The request failed, or no byte arrived by [PROBE_FIRST_BYTE_DEADLINE_MS]. */
    data class NetworkError(val cause: Throwable?) : BandwidthProbeResult
}

/** Thrown into a probe that missed [PROBE_FIRST_BYTE_DEADLINE_MS]. */
class ProbeFirstByteTimeout : Exception("No probe byte arrived in time.")

/**
 * The window arithmetic of one probe, apart from any I/O so it can be checked
 * on its own. Feed it each chunk with the time it arrived ([onChunk]), or the
 * time a stalled read was cut off ([cutOff]), then read [result].
 *
 * The clock starts at the first chunk, so request latency and server setup do
 * not count against the link; that chunk's bytes are not counted either,
 * since they arrived before the window opened.
 */
internal class ProbeWindow {
    @Volatile var start: Long? = null
        private set
    private var warmAt: Long? = null
    private var warmBytes = 0L
    private var bytes = 0L
    private var now = 0L

    /** Records [size] bytes arriving at [at]. True once the time budget is spent. */
    fun onChunk(at: Long, size: Int): Boolean {
        now = at
        val begun = start
        if (begun == null) {
            start = at
            return false
        }
        bytes += size
        if (warmAt == null && at - begun >= PROBE_WARMUP_MS) {
            warmAt = at
            warmBytes = bytes
        }
        return at - begun >= PROBE_TIME_BUDGET_MS
    }

    /** Ends the window at [at]: the silent stretch of a stalled read counts against the rate. */
    fun cutOff(at: Long) {
        now = at
    }

    fun result(): BandwidthProbeResult {
        val begun = start ?: return BandwidthProbeResult.TooLittleData
        if (now <= begun) return BandwidthProbeResult.TooLittleData
        val elapsed = now - begun
        if (elapsed < PROBE_MIN_WINDOW_MS && bytes < PROBE_MIN_BYTES) return BandwidthProbeResult.TooLittleData
        val warm = warmAt
        // Prefer the rate after ramp-up when at least as much time followed it.
        if (warm != null && now - warm >= warm - begun) {
            val timed = bytes - warmBytes
            val kbps = clampKbps(timed * 8.0 / (now - warm)) ?: return BandwidthProbeResult.TooLittleData
            return BandwidthProbeResult.Measured(kbps, timed, (now - warm).toDurationMs())
        }
        // bytes * 8 bits / ms = kbit/s.
        val kbps = clampKbps(bytes * 8.0 / elapsed) ?: return BandwidthProbeResult.TooLittleData
        return BandwidthProbeResult.Measured(kbps, bytes, elapsed.toDurationMs())
    }

    private fun Long.toDurationMs(): Int = coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}

/** [kbps] rounded and capped to the contract's range, or null below its floor. */
internal fun clampKbps(kbps: Double): Int? {
    if (!kbps.isFinite() || kbps < MIN_ESTIMATE_KBPS) return null
    return kbps.roundToLong().coerceAtMost(MAX_ESTIMATE_KBPS.toLong()).toInt()
}

/**
 * The URL a probe downloads: the advertised `bandwidth_probe_url`, resolved
 * against [serverUrl] when it is relative, asking for [PROBE_BYTES].
 */
fun bandwidthProbeDownloadUrl(serverUrl: String, advertised: String): String =
    URLBuilder(serverUrl).apply {
        takeFrom(advertised)
        parameters["size"] = PROBE_BYTES.toString()
    }.buildString()

/**
 * Times one probe download. [clock] is a monotonic millisecond clock.
 *
 * A probe that has not delivered its first byte within
 * [PROBE_FIRST_BYTE_DEADLINE_MS] of being asked for is abandoned. Once bytes
 * flow it is timed for [PROBE_TIME_BUDGET_MS]; a read that stops returning is
 * cut off [PROBE_STALLED_READ_GRACE_MS] after that, and the rate is taken from
 * what arrived. Returning from the body ends the transfer: the download
 * closes, and the server stops at its next write.
 */
class BandwidthProbe(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {

    /**
     * Runs [download], which issues the request and hands its answer to the
     * reader it is given, returning what that reader returns.
     */
    suspend fun measure(
        download: suspend (read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult) -> BandwidthProbeResult,
    ): BandwidthProbeResult = coroutineScope {
        val window = ProbeWindow()
        val missedFirstByte = AtomicBoolean(false)
        val transfer = async {
            try {
                download { response -> read(response, window) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                BandwidthProbeResult.NetworkError(failure)
            }
        }
        // The deadline covers the request as well as the body: a server that
        // never answers is abandoned the same way as one that never sends.
        val deadline = launch {
            delay(PROBE_FIRST_BYTE_DEADLINE_MS)
            if (window.start == null) {
                missedFirstByte.set(true)
                transfer.cancel()
            }
        }
        try {
            transfer.await()
        } catch (cancellation: CancellationException) {
            ensureActive()
            if (!missedFirstByte.get()) throw cancellation
            BandwidthProbeResult.NetworkError(ProbeFirstByteTimeout())
        } finally {
            deadline.cancel()
        }
    }

    private suspend fun read(response: BandwidthProbeResponseV2, window: ProbeWindow): BandwidthProbeResult =
        when (response) {
            is BandwidthProbeResponseV2.RateLimited -> BandwidthProbeResult.RateLimited(response.retryAfterSeconds)
            is BandwidthProbeResponseV2.Unavailable -> BandwidthProbeResult.Unavailable(response.status)
            is BandwidthProbeResponseV2.Body -> {
                time(response.read, window)
                window.result()
            }
        }

    private suspend fun time(read: suspend (ByteArray) -> Int, window: ProbeWindow) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val begun = window.start
            val count = if (begun == null) {
                read(buffer)
            } else {
                // Ends a read that stops returning, which the budget check
                // below would never reach. The silent stretch counts against
                // the rate, so the window ends at the cut-off rather than at
                // the last chunk.
                val cutOffAt = begun + PROBE_TIME_BUDGET_MS + PROBE_STALLED_READ_GRACE_MS
                val remaining = cutOffAt - clock()
                val count = if (remaining > 0) withTimeoutOrNull(remaining) { read(buffer) } else null
                if (count == null) {
                    window.cutOff(clock())
                    return
                }
                count
            }
            if (count < 0) return
            if (window.onChunk(clock(), count)) return
        }
    }
}
