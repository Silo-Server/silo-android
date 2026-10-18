package org.siloserver.silo.domain.player

/** The two connection speed tests. */
enum class SpeedTestMode(
    /** The `size` the test asks the probe for. */
    val requestBytes: Long,
    /** How long after the first byte the test reads at most. */
    val maxMs: Long,
    /** How many complete windows the settled rate is taken over. */
    val settleWindows: Int,
) {
    /** Automatic: stops once a window proves the server's target. */
    QUICK(requestBytes = 100_000_000, maxMs = 3_000, settleWindows = 2),

    /** **Test speed** only: the rate held over the final seconds. */
    ACCURATE(requestBytes = 1_000_000_000, maxMs = 10_000, settleWindows = 6),
}

/** What a speed test measured: [kbps] from [bytes] over [durationMs], the windows the rate came from. */
data class SpeedTestResult(
    val kbps: Int,
    /** The link is at least [kbps]: a window reached the target, or the rate passed [SpeedTestMeter.MAX_KBPS]. */
    val lowerBound: Boolean,
    val bytes: Long,
    val durationMs: Int,
)

/**
 * The connection speed test's arithmetic, apart from any I/O: *Connection speed
 * test → Measuring* in the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`. `BandwidthProbeVectorsTest`
 * replays the spec's `probe_cases` against it.
 *
 * Times are milliseconds after the first byte of the response body. Bytes count
 * in the [WINDOW_MS] window they arrive in, and window 0, TCP ramp-up, never
 * counts. Feed arrivals to [onBytes], move the clock with [advanceTo] so a
 * window with no bytes still closes and counts as 0, and report the end of the
 * transfer with [onEnd]. Once [stopAtMs] is set the test stops reading and
 * later bytes are ignored.
 *
 * @param targetKbps `bandwidth_probe_target_kbps`; the accurate test ignores it.
 */
class SpeedTestMeter(val mode: SpeedTestMode, targetKbps: Int?) {
    private val target: Int? = targetKbps.takeIf { mode == SpeedTestMode.QUICK }

    /** Bytes of each window closed so far, by window number. */
    private val closed = mutableListOf<Long>()
    private var openBytes = 0L

    /** Bytes of the window that reached the target, once one has. */
    private var targetWindowBytes: Long? = null

    /** When the test stops reading, once it has decided to. */
    var stopAtMs: Long? = null
        private set

    val stopped: Boolean get() = stopAtMs != null

    /**
     * Closes every window that ended by [nowMs]. The quick test stops at the
     * end of the first window after window 0 that reaches the target; either
     * test stops at its limit.
     */
    fun advanceTo(nowMs: Long) {
        while (!stopped && (closed.size + 1) * WINDOW_MS <= nowMs) {
            val bytes = openBytes
            closed += bytes
            openBytes = 0
            val endMs = closed.size * WINDOW_MS
            if (closed.size > 1 && target != null && rateKbps(bytes, 1) >= target) {
                targetWindowBytes = bytes
                stopAtMs = endMs
            } else if (endMs >= mode.maxMs) {
                stopAtMs = mode.maxMs
            }
        }
    }

    /** [bytes] arrived at [atMs]. */
    fun onBytes(atMs: Long, bytes: Long) {
        advanceTo(atMs)
        if (!stopped) openBytes += bytes
    }

    /** The transfer ended at [atMs]; a window it cut short does not count. */
    fun onEnd(atMs: Long) {
        advanceTo(atMs)
        if (!stopped) stopAtMs = atMs
    }

    /**
     * The target as a lower bound when a window reached it, else the settled
     * rate over the last complete windows after window 0. Null when the test
     * measured nothing: no complete window after window 0, or under [MIN_KBPS].
     */
    fun result(): SpeedTestResult? {
        targetWindowBytes?.let { bytes ->
            return SpeedTestResult(checkNotNull(target), lowerBound = true, bytes = bytes, durationMs = WINDOW_MS.toInt())
        }
        val counted = closed.drop(1).takeLast(mode.settleWindows)
        if (counted.isEmpty()) return null
        val bytes = counted.sum()
        val kbps = rateKbps(bytes, counted.size)
        val durationMs = (counted.size * WINDOW_MS).toInt()
        return when {
            kbps < MIN_KBPS -> null
            kbps > MAX_KBPS -> SpeedTestResult(MAX_KBPS, lowerBound = true, bytes = bytes, durationMs = durationMs)
            else -> SpeedTestResult(kbps.toInt(), lowerBound = false, bytes = bytes, durationMs = durationMs)
        }
    }

    companion object {
        const val WINDOW_MS: Long = 500

        /** A test that has not had its first byte by now is abandoned. */
        const val FIRST_BYTE_DEADLINE_MS: Long = 10_000
        const val MIN_KBPS: Int = 100
        const val MAX_KBPS: Int = 1_000_000

        /** `floor(bytes × 8 / (windows × 500))`: bits per millisecond is kbit/s. */
        private fun rateKbps(bytes: Long, windows: Int): Long = bytes * 8 / (windows * WINDOW_MS)
    }
}
