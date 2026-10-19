package org.siloserver.silo.common.player.probe

import android.os.SystemClock
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.siloserver.silo.domain.player.SpeedTestMeter
import org.siloserver.silo.domain.player.SpeedTestMode
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2

/*
 * The connection speed test: *Connection speed test* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`. [SpeedTestMeter] holds its
 * arithmetic; this file times one download over one connection.
 */

/** Let the first screen settle before spending bandwidth on a probe. */
const val PROBE_START_DELAY_MS = 5_000L

/**
 * How long a reading stands when the server names no
 * `bandwidth_probe_max_age_seconds`: playback starts send it, and nothing
 * measures that connection again, until it is this old.
 */
const val BANDWIDTH_READING_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000L

/** How much one read asks for. */
private const val READ_BUFFER_BYTES = 64 * 1024

/** One speed test, timed: a rate or why there is none. */
sealed interface BandwidthProbeResult {
    /**
     * [kbps] measured from [bytes] over [durationMs], the windows the rate came
     * from. With [lowerBound] the link is at least [kbps].
     */
    data class Measured(
        val kbps: Int,
        val bytes: Long,
        val durationMs: Int,
        val lowerBound: Boolean = false,
    ) : BandwidthProbeResult

    /** HTTP 429: the server allows another probe after [retryAfterSeconds]. */
    data class RateLimited(val retryAfterSeconds: Int) : BandwidthProbeResult

    /**
     * The server refused the download with [status] (0 when no exchange
     * happened), for example when a proxy-only policy has no proxy that
     * serves probes. It never falls back to measuring another path.
     */
    data class Unavailable(val status: Int) : BandwidthProbeResult

    /**
     * No complete window after the ramp-up window and fewer bytes than the
     * test asked for, or under 100 kbps.
     */
    data object TooLittleData : BandwidthProbeResult

    /** The request failed, or no byte arrived by [SpeedTestMeter.FIRST_BYTE_DEADLINE_MS]. */
    data class NetworkError(val cause: Throwable?) : BandwidthProbeResult
}

/** Thrown into a probe that missed [SpeedTestMeter.FIRST_BYTE_DEADLINE_MS]. */
class ProbeFirstByteTimeout : Exception("No probe byte arrived in time.")

/** The advertised `bandwidth_probe_url`, resolved against [serverUrl] when it is relative. */
fun resolveBandwidthProbeUrl(serverUrl: String, advertised: String): String =
    URLBuilder(serverUrl).apply { takeFrom(advertised) }.buildString()

/** [probeUrl] asking for the bytes [mode] reads at most. */
fun bandwidthProbeDownloadUrl(probeUrl: String, mode: SpeedTestMode): String =
    URLBuilder(probeUrl).apply { parameters["size"] = mode.requestBytes.toString() }.buildString()

/**
 * Times one speed test download. [clock] is a monotonic millisecond clock.
 *
 * A test that has not delivered its first byte within
 * [SpeedTestMeter.FIRST_BYTE_DEADLINE_MS] of being asked for is abandoned. From
 * the first byte on, each read waits at most until the current window ends, so
 * a window with no bytes still closes and counts. Returning from the body ends
 * the transfer: the download closes, and the server stops at its next write.
 */
class BandwidthProbe(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {

    /**
     * Runs [download], which issues the request for [mode] and hands its answer
     * to the reader it is given, returning what that reader returns.
     * [targetKbps] is `bandwidth_probe_target_kbps`; the accurate test ignores it.
     */
    suspend fun measure(
        mode: SpeedTestMode,
        targetKbps: Int?,
        download: suspend (read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult) -> BandwidthProbeResult,
    ): BandwidthProbeResult = coroutineScope {
        val firstByte = AtomicBoolean(false)
        val missedFirstByte = AtomicBoolean(false)
        val transfer = async {
            try {
                download { response -> read(response, SpeedTestMeter(mode, targetKbps), firstByte) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                BandwidthProbeResult.NetworkError(failure)
            }
        }
        // The deadline covers the request as well as the body: a server that
        // never answers is abandoned the same way as one that never sends.
        val deadline = launch {
            delay(SpeedTestMeter.FIRST_BYTE_DEADLINE_MS)
            if (!firstByte.get()) {
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

    private suspend fun read(
        response: BandwidthProbeResponseV2,
        meter: SpeedTestMeter,
        firstByte: AtomicBoolean,
    ): BandwidthProbeResult = when (response) {
        is BandwidthProbeResponseV2.RateLimited -> BandwidthProbeResult.RateLimited(response.retryAfterSeconds)
        is BandwidthProbeResponseV2.Unavailable -> BandwidthProbeResult.Unavailable(response.status)
        is BandwidthProbeResponseV2.Body -> {
            time(response.read, meter, firstByte)
            meter.result()?.let { BandwidthProbeResult.Measured(it.kbps, it.bytes, it.durationMs, it.lowerBound) }
                ?: BandwidthProbeResult.TooLittleData
        }
    }

    /**
     * Reads the body into [meter] until it stops. A reader takes bytes as they
     * come while a ticker closes each window on the clock, so a silent window
     * still counts, and cancels the reader once the meter stops. Both run on
     * the caller's dispatcher, which the scheduler keeps single-threaded, so
     * they share [meter] without a lock.
     */
    private suspend fun time(read: suspend (ByteArray) -> Int, meter: SpeedTestMeter, firstByte: AtomicBoolean) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var first: Int
        do {
            first = read(buffer)
        } while (first == 0)
        if (first < 0) return
        // The clock starts at the first byte, so request latency does not count.
        val origin = clock()
        firstByte.set(true)
        meter.onBytes(0, first.toLong())
        coroutineScope {
            val reader = launch {
                while (!meter.stopped) {
                    val count = read(buffer)
                    val at = clock() - origin
                    if (count < 0) meter.onEnd(at) else meter.onBytes(at, count.toLong())
                }
            }
            val ticker = launch {
                while (!meter.stopped) {
                    val now = clock() - origin
                    delay((now / SpeedTestMeter.WINDOW_MS + 1) * SpeedTestMeter.WINDOW_MS - now)
                    meter.advanceTo(clock() - origin)
                }
                reader.cancel()
            }
            reader.join()
            ticker.cancel()
        }
    }
}
