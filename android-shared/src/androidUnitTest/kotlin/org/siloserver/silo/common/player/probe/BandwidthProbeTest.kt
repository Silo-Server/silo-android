package org.siloserver.silo.common.player.probe

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.domain.player.SpeedTestMeter
import org.siloserver.silo.domain.player.SpeedTestMode
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The timed download. The window arithmetic itself is the spec's
 * `probe_cases`, which `BandwidthProbeVectorsTest` replays.
 */
class BandwidthProbeTest {

    @Test
    fun theDownloadUrlAsksForTheTestsSize() {
        val probeUrl = resolveBandwidthProbeUrl(
            "https://silo.example",
            "https://silo.example/api/v2/playback/bandwidth-probe?media_path=true",
        )
        assertEquals(
            "https://silo.example/api/v2/playback/bandwidth-probe?media_path=true&size=100000000",
            bandwidthProbeDownloadUrl(probeUrl, SpeedTestMode.QUICK),
        )
        assertEquals(
            "https://silo.example:8096/api/v2/playback/bandwidth-probe?size=1000000000",
            bandwidthProbeDownloadUrl(
                resolveBandwidthProbeUrl("https://silo.example:8096", "/api/v2/playback/bandwidth-probe"),
                SpeedTestMode.ACCURATE,
            ),
        )
    }

    private fun TestScope.probe() = BandwidthProbe(clock = { currentTime })

    /** 1 MB every 100 ms, 80 Mbps, from a first byte at 100. */
    private val steady = BandwidthProbeResponseV2.Body {
        delay(100)
        1_000_000
    }

    /** A body whose reads follow [chunks]: each is a delay and a byte count; then it hangs or ends. */
    private fun body(chunks: List<Pair<Long, Int>>, hangAfter: Boolean): BandwidthProbeResponseV2.Body {
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
    fun aQuickTestStopsAtTheEndOfTheFirstWindowThatReachesTheTarget() = runTest {
        val result = probe().measure(SpeedTestMode.QUICK, targetKbps = 50_000) { read -> read(steady) }
        assertEquals(1_100L, currentTime, "window 1 ends 1000 ms after the first byte at 100")
        assertEquals(BandwidthProbeResult.Measured(50_000, 5_000_000, 500, lowerBound = true), result)
    }

    @Test
    fun aQuickTestWithoutATargetReadsThreeSecondsAndSettlesOverTheLastSecond() = runTest {
        val result = probe().measure(SpeedTestMode.QUICK, targetKbps = null) { read -> read(steady) }
        assertEquals(3_100L, currentTime)
        assertEquals(BandwidthProbeResult.Measured(80_000, 10_000_000, 1_000), result)
    }

    @Test
    fun aSilentWindowClosesOnTheClockAndCountsAsNothing() = runTest {
        // 25 MB to 2400 ms after the first byte, then nothing.
        val chunks = List(25) { 100L to 1_000_000 }
        val result = probe().measure(SpeedTestMode.QUICK, targetKbps = null) { read ->
            read(body(chunks, hangAfter = true))
        }
        assertEquals(3_100L, currentTime)
        // Window 4 had 5 MB; window 5 was silent.
        assertEquals(BandwidthProbeResult.Measured(40_000, 5_000_000, 1_000), result)
    }

    @Test
    fun aTransferThatEndsEarlyCountsOnlyCompleteWindows() = runTest {
        // Chunks to 1200 ms after the first byte, then the body ends.
        val chunks = List(13) { 100L to 1_000_000 }
        val result = probe().measure(SpeedTestMode.QUICK, targetKbps = null) { read ->
            read(body(chunks, hangAfter = false))
        }
        assertEquals(BandwidthProbeResult.Measured(80_000, 5_000_000, 500), result)
    }

    @Test
    fun anAccurateTestReadsTenSecondsAndSettlesOverTheLastThreeSeconds() = runTest {
        val result = probe().measure(SpeedTestMode.ACCURATE, targetKbps = 50_000) { read -> read(steady) }
        assertEquals(10_100L, currentTime, "the target does not stop the accurate test")
        assertEquals(BandwidthProbeResult.Measured(80_000, 30_000_000, 3_000), result)
    }

    @Test
    fun aProbeWithoutAFirstByteIsAbandonedAtTheDeadline() = runTest {
        val result = probe().measure(SpeedTestMode.QUICK, null) { _ -> awaitCancellation() }
        assertEquals(SpeedTestMeter.FIRST_BYTE_DEADLINE_MS, currentTime)
        assertIs<ProbeFirstByteTimeout>(assertIs<BandwidthProbeResult.NetworkError>(result).cause)
    }

    @Test
    fun aBodyThatNeverStartsIsAbandonedAtTheDeadline() = runTest {
        val result = probe().measure(SpeedTestMode.QUICK, null) { read -> read(body(emptyList(), hangAfter = true)) }
        assertEquals(SpeedTestMeter.FIRST_BYTE_DEADLINE_MS, currentTime)
        assertIs<BandwidthProbeResult.NetworkError>(result)
    }

    @Test
    fun aRateLimitIsTypedWithItsRetryAfter() = runTest {
        val result = probe().measure(SpeedTestMode.QUICK, null) { read -> read(BandwidthProbeResponseV2.RateLimited(42)) }
        assertEquals(BandwidthProbeResult.RateLimited(42), result)
    }

    @Test
    fun aRefusalIsUnavailable() = runTest {
        assertEquals(
            BandwidthProbeResult.Unavailable(503),
            probe().measure(SpeedTestMode.QUICK, null) { read -> read(BandwidthProbeResponseV2.Unavailable(503)) },
        )
    }

    @Test
    fun aFailedRequestIsANetworkError() = runTest {
        val failure = IOException("reset")
        val result = probe().measure(SpeedTestMode.ACCURATE, null) { _ -> throw failure }
        assertEquals(BandwidthProbeResult.NetworkError(failure), result)
    }
}
