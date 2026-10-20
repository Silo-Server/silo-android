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

/** What a speed test measured: [kbps] from [bytes] over [durationMs], the windows (or whole transfer) the rate came from. */
data class SpeedTestResult(
    val kbps: Int,
    /**
     * The link is at least [kbps]: a window reached the target, the rate
     * passed [SpeedTestMeter.MAX_KBPS], or the rate is a whole-transfer average.
     */
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
 * later bytes are ignored. A link so fast that the whole request arrives
 * before any window after window 0 completes is measured over the whole
 * transfer instead.
 *
 * @param targetKbps `bandwidth_probe_target_kbps`; the accurate test ignores it.
 */
class SpeedTestMeter(val mode: SpeedTestMode, targetKbps: Int?) {
    private val target: Int? = targetKbps.takeIf { mode == SpeedTestMode.QUICK }

    /** Bytes of each window closed so far, by window number. */
    private val closed = mutableListOf<Long>()
    private var openBytes = 0L
    private var totalBytes = 0L

    /** When the transfer ended, if it ended before the test stopped. */
    private var endMs: Long? = null

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
        if (stopped) return
        openBytes += bytes
        totalBytes += bytes
    }

    /** The transfer ended at [atMs], its last byte; a window it cut short does not count. */
    fun onEnd(atMs: Long) {
        advanceTo(atMs)
        if (stopped) return
        stopAtMs = atMs
        endMs = atMs
    }

    /**
     * The target as a lower bound when a window reached it, else the settled
     * rate over the last complete windows after window 0, else the
     * whole-transfer average ([wholeTransfer]). Null when the test measured
     * nothing: no complete window after window 0 and a transfer that ended
     * short of [SpeedTestMode.requestBytes], or under [MIN_KBPS], a target
     * under it included.
     */
    fun result(): SpeedTestResult? {
        targetWindowBytes?.let { bytes ->
            val kbps = checkNotNull(target)
            if (kbps < MIN_KBPS) return null
            return SpeedTestResult(kbps.coerceAtMost(MAX_KBPS), lowerBound = true, bytes = bytes, durationMs = WINDOW_MS.toInt())
        }
        val counted = closed.drop(1).takeLast(mode.settleWindows)
        if (counted.isEmpty()) return wholeTransfer()
        val bytes = counted.sum()
        val kbps = rateKbps(bytes, counted.size)
        val durationMs = (counted.size * WINDOW_MS).toInt()
        return when {
            kbps < MIN_KBPS -> null
            kbps > MAX_KBPS -> SpeedTestResult(MAX_KBPS, lowerBound = true, bytes = bytes, durationMs = durationMs)
            else -> SpeedTestResult(kbps.toInt(), lowerBound = false, bytes = bytes, durationMs = durationMs)
        }
    }

    /**
     * Every requested byte arrived before any window after window 0
     * completed: `floor(bytes × 8 / ms)` from the first byte to the last, the
     * ramp included, so it can only read low. Always a lower bound: the target
     * when a quick test's average reaches it, else the average up to
     * [MAX_KBPS].
     */
    private fun wholeTransfer(): SpeedTestResult? {
        val end = endMs ?: return null
        if (totalBytes < mode.requestBytes) return null
        val average = totalBytes * 8 / end.coerceAtLeast(1)
        val kbps = (if (target != null && average >= target) target.toLong() else average).coerceAtMost(MAX_KBPS.toLong())
        if (kbps < MIN_KBPS) return null
        return SpeedTestResult(kbps.toInt(), lowerBound = true, bytes = totalBytes, durationMs = end.toInt())
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
