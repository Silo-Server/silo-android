package org.siloserver.silo.tv.ui.screens.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSeekRateLadderTest {

    /**
     * The defect this ladder exists to prevent: the chip said "8×" while the
     * scrubber advanced 2.0s every 100ms, which is 160× real time. A rate must
     * mean its own multiple of real time and nothing else.
     */
    @Test
    fun aRateAdvancesExactlyThatMultipleOfRealTime() {
        val tickSeconds = TvSeekRateLadder.TICK_MILLIS / 1000.0
        listOf(2, 4, 8, 16, 32).forEach { rate ->
            val advancedPerSecond = TvSeekRateLadder.tickSeconds(rate) / tickSeconds
            assertEquals(
                rate.toDouble(),
                advancedPerSecond,
                0.0001,
                "rate ${rate}x must advance ${rate}x real time",
            )
        }
    }

    @Test
    fun reverseRatesMirrorForwardOnes() {
        listOf(2, 4, 8, 16, 32).forEach { rate ->
            assertEquals(
                -TvSeekRateLadder.tickSeconds(rate),
                TvSeekRateLadder.tickSeconds(-rate),
                0.0001,
            )
        }
    }

    /**
     * A hold used to reach the top speed in three seconds, so a press meant to
     * nudge forward a few seconds crossed a scene. Reaching the sustained
     * ceiling must take a deliberately long hold.
     */
    @Test
    fun aSustainedHoldClimbsGraduallyAndStopsBelowTheManualCeiling() {
        val steps = TvSeekRateLadder.holdRampMillis.indices.map {
            TvSeekRateLadder.sustainedRate(it, 1)
        }

        assertEquals(listOf(4, 8, 16), steps)
        assertTrue(
            steps.last() <= TvSeekRateLadder.SUSTAINED_MAX_RATE,
            "a hold must not reach beyond the sustained ceiling on its own",
        )
        assertTrue(
            TvSeekRateLadder.SUSTAINED_MAX_RATE < TvSeekRateLadder.MANUAL_MAX_RATE,
            "the fastest speed must require a deliberate press, not just waiting",
        )
        assertTrue(
            TvSeekRateLadder.holdRampMillis.first() >= 1_500L,
            "a short hold must stay at the base rate",
        )
    }

    @Test
    fun sustainedRateFollowsTheHeldDirection() {
        TvSeekRateLadder.holdRampMillis.indices.forEach { step ->
            assertEquals(
                -TvSeekRateLadder.sustainedRate(step, 1),
                TvSeekRateLadder.sustainedRate(step, -1),
            )
        }
    }

    @Test
    fun bumpsWalkTheLadderAndClampAtBothEnds() {
        assertEquals(4, TvSeekRateLadder.bumped(2, 1))
        assertEquals(2, TvSeekRateLadder.bumped(4, -1))
        assertEquals(
            TvSeekRateLadder.MANUAL_MAX_RATE,
            TvSeekRateLadder.bumped(TvSeekRateLadder.MANUAL_MAX_RATE, 1),
        )
        assertEquals(
            -TvSeekRateLadder.MANUAL_MAX_RATE,
            TvSeekRateLadder.bumped(-TvSeekRateLadder.MANUAL_MAX_RATE, -1),
        )
    }

    /**
     * 1× scans at exactly playback speed, so a press would look like nothing
     * happened. Every rate must be faster than watching.
     */
    @Test
    fun everyRateIsFasterThanPlayback() {
        TvSeekRateLadder.rates.forEach { rate ->
            assertTrue(abs(rate) >= 2, "rate $rate is not usefully faster than playback")
        }
    }

    /**
     * The number the viewer aims with has to stay aimable: one tick must not
     * cover more than a scene even at the ceiling.
     */
    @Test
    fun theFastestRateStillMovesInAimableSteps() {
        val perTick = TvSeekRateLadder.tickSeconds(TvSeekRateLadder.MANUAL_MAX_RATE)
        assertTrue(perTick <= 5.0, "one tick at the ceiling jumps ${perTick}s, which cannot be aimed")
    }
}
