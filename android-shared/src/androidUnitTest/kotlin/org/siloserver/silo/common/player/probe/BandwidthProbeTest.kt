package org.siloserver.silo.common.player.probe

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The web probe's windowing, ported: `web/src/player/bandwidth-probe.ts` in the server repo. */
class BandwidthProbeTest {

    // --- Window arithmetic ---

    @Test
    fun aSteadyTransferIsTimedAfterTheWarmupFromTheFirstByte() {
        val window = ProbeWindow()
        // The first chunk opens the window at 1000; its bytes arrived before it.
        assertEquals(false, window.onChunk(1_000, 5_000_000))
        var done = false
        var at = 1_000L
        while (!done) {
            at += 100
            done = window.onChunk(at, 1_000_000)
        }
        assertEquals(3_000L, at, "the budget ends the window 2 s after the first byte")
        // Warm at 1300 with 3 MB; 17 MB in the 1700 ms that followed.
        assertEquals(BandwidthProbeResult.Measured(kbps = 80_000, bytes = 17_000_000, durationMs = 1_700), window.result())
    }

    @Test
    fun theWholeWindowIsUsedWhenLessTimeFollowedTheWarmup() {
        val window = ProbeWindow()
        window.onChunk(0, 1)
        window.onChunk(300, 600_000) // warm at 300
        window.onChunk(500, 400_000) // the body ends here: only 200 ms followed the warmup
        assertEquals(BandwidthProbeResult.Measured(kbps = 16_000, bytes = 1_000_000, durationMs = 500), window.result())
    }

    @Test
    fun aShortWindowNeedsAMegabyte() {
        val short = ProbeWindow().apply {
            onChunk(0, 1)
            onChunk(150, 999_999)
        }
        assertEquals(BandwidthProbeResult.TooLittleData, short.result())

        val enough = ProbeWindow().apply {
            onChunk(0, 1)
            onChunk(150, 1_000_000)
        }
        assertEquals(BandwidthProbeResult.Measured(kbps = 53_333, bytes = 1_000_000, durationMs = 150), enough.result())
    }

    @Test
    fun onlyTheFirstChunkIsNoMeasurement() {
        assertEquals(BandwidthProbeResult.TooLittleData, ProbeWindow().result())
        assertEquals(BandwidthProbeResult.TooLittleData, ProbeWindow().apply { onChunk(0, 40_000_000) }.result())
    }

    @Test
    fun ratesAreClampedToTheContractsRange() {
        val slow = ProbeWindow().apply {
            onChunk(0, 1)
            onChunk(2_000, 10_000)
        }
        assertEquals(BandwidthProbeResult.TooLittleData, slow.result(), "40 kbps is below the floor")
        assertEquals(1_000_000, clampKbps(5_000_000.0))
        assertEquals(100, clampKbps(100.0))
        assertEquals(null, clampKbps(99.9))
        assertEquals(null, clampKbps(Double.NaN))
    }

    @Test
    fun theDownloadUrlAsksForFortyMegabytes() {
        assertEquals(
            "https://silo.example/api/v2/playback/bandwidth-probe?media_path=true&size=40000000",
            bandwidthProbeDownloadUrl("https://silo.example", "https://silo.example/api/v2/playback/bandwidth-probe?media_path=true"),
        )
        assertEquals(
            "https://silo.example:8096/api/v2/playback/bandwidth-probe?size=40000000",
            bandwidthProbeDownloadUrl("https://silo.example:8096", "/api/v2/playback/bandwidth-probe"),
        )
    }

    // --- The timed download ---

    private fun TestScope.probe() = BandwidthProbe(clock = { currentTime })

    /** A body whose reads follow [chunks]: each is a delay and a byte count; then it hangs or ends. */
    private fun body(chunks: List<Pair<Long, Int>>, hangAfter: Boolean = false): BandwidthProbeResponseV2.Body {
        val iterator = chunks.iterator()
        return BandwidthProbeResponseV2.Body {
            if (iterator.hasNext()) {
                val (wait, count) = iterator.next()
                delay(wait)
                count
            } else if (hangAfter) {
                awaitCancellation()
            } else {
                -1
            }
        }
    }

    @Test
    fun aStalledReadIsCutOffAndItsSilenceCountsAgainstTheRate() = runTest {
        // First byte at 100, then 1 MB every 100 ms until 1000, then nothing.
        val chunks = listOf(100L to 1) + List(9) { 100L to 1_000_000 }
        val result = probe().measure { read -> read(body(chunks, hangAfter = true)) }
        // Cut off at 100 + 2000 + 500. Warm at 400 with 3 MB; 6 MB in the 2200 ms after.
        assertEquals(2_600L, currentTime)
        assertEquals(BandwidthProbeResult.Measured(kbps = 21_818, bytes = 6_000_000, durationMs = 2_200), result)
    }

    @Test
    fun theBudgetEndsTheTransfer() = runTest {
        var reads = 0
        val endless = BandwidthProbeResponseV2.Body {
            reads += 1
            delay(100)
            1_000_000
        }
        val result = probe().measure { read -> read(endless) }
        assertEquals(2_100L, currentTime, "2 s after the first byte at 100")
        assertEquals(21, reads)
        assertEquals(BandwidthProbeResult.Measured(kbps = 80_000, bytes = 17_000_000, durationMs = 1_700), result)
    }

    @Test
    fun aProbeWithoutAFirstByteIsAbandonedAtTheDeadline() = runTest {
        val result = probe().measure { _ -> awaitCancellation() }
        assertEquals(PROBE_FIRST_BYTE_DEADLINE_MS, currentTime)
        assertIs<ProbeFirstByteTimeout>(assertIs<BandwidthProbeResult.NetworkError>(result).cause)
    }

    @Test
    fun aBodyThatNeverStartsIsAbandonedAtTheDeadline() = runTest {
        val result = probe().measure { read -> read(body(emptyList(), hangAfter = true)) }
        assertEquals(PROBE_FIRST_BYTE_DEADLINE_MS, currentTime)
        assertIs<BandwidthProbeResult.NetworkError>(result)
    }

    @Test
    fun aRateLimitIsTypedWithItsRetryAfter() = runTest {
        val result = probe().measure { read -> read(BandwidthProbeResponseV2.RateLimited(42)) }
        assertEquals(BandwidthProbeResult.RateLimited(42), result)
    }

    @Test
    fun aRefusalIsUnavailable() = runTest {
        assertEquals(
            BandwidthProbeResult.Unavailable(503),
            probe().measure { read -> read(BandwidthProbeResponseV2.Unavailable(503)) },
        )
    }

    @Test
    fun aFailedRequestIsANetworkError() = runTest {
        val failure = IOException("reset")
        val result = probe().measure { _ -> throw failure }
        assertEquals(BandwidthProbeResult.NetworkError(failure), result)
    }
}
