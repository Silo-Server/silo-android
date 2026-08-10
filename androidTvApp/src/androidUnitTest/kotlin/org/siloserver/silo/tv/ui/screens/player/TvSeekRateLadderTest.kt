package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSeekRateLadderTest {

    private companion object {
        const val EPISODE = 2_700.0      // 45 min
        const val SHORT_EPISODE = 1_320.0 // 22 min
        const val LONG_FILM = 10_800.0    // 3 h
    }

    /**
     * The defect this ladder exists to prevent: the chip said "8×" while the
     * scrubber advanced 2.0s every 100ms, which is 160× real time. A rate must
     * mean its own multiple of real time and nothing else.
     */
    @Test
    fun aRateAdvancesExactlyThatMultipleOfRealTime() {
        val tickSeconds = TvSeekRateLadder.TICK_MILLIS / 1000.0
        TvSeekRateLadder.rates.forEach { rate ->
            val advancedPerSecond = TvSeekRateLadder.tickSeconds(rate) / tickSeconds
            assertEquals(rate.toDouble(), advancedPerSecond, 0.0001, "rate ${rate}x")
        }
    }

    @Test
    fun reverseRatesMirrorForwardOnes() {
        TvSeekRateLadder.rates.forEach { rate ->
            assertEquals(
                -TvSeekRateLadder.tickSeconds(rate),
                TvSeekRateLadder.tickSeconds(-rate),
                0.0001,
            )
        }
    }

    /**
     * The point of deriving the ceiling from runtime: holding to the end costs
     * about the same whether the item is twenty minutes or three hours. A fixed
     * 32× ceiling took 41s for a short episode and 338s for a long film.
     */
    @Test
    fun holdingToTheEndCostsAboutTheSameAtAnyRuntime() {
        listOf(SHORT_EPISODE, EPISODE, 5_400.0, LONG_FILM).forEach { duration ->
            val top = TvSeekRateLadder.maxRateFor(duration)
            val traverseSeconds = duration / top
            assertTrue(
                traverseSeconds <= TvSeekRateLadder.TRAVERSE_TARGET_SECONDS * 1.5,
                "a ${duration}s item takes ${traverseSeconds}s to cross at ${top}x",
            )
        }
    }

    @Test
    fun aLongerItemGetsAFasterTopGear() {
        assertTrue(
            TvSeekRateLadder.maxRateFor(LONG_FILM) > TvSeekRateLadder.maxRateFor(SHORT_EPISODE),
            "a three-hour film must reach further than a twenty-minute episode",
        )
    }

    /**
     * An unknown runtime must not produce a guessed ceiling; live content and
     * un-probed files both arrive as zero.
     */
    @Test
    fun anUnknownRuntimeFallsBackRatherThanGuessing() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertEquals(TvSeekRateLadder.MIN_TOP_RATE, TvSeekRateLadder.maxRateFor(bad))
        }
    }

    /**
     * The first second of holding stays slow enough to aim with — that is the
     * half of the control used to skip an intro, and the half the old ramp
     * destroyed by reaching its top speed in three seconds.
     */
    @Test
    fun theFirstSecondOfHoldingStaysAimable() {
        assertEquals(TvSeekRateLadder.BASE_RATE, 2)
        assertTrue(TvSeekRateLadder.RAMP_STEP_MILLIS >= 750L)
        val afterFirstStep = TvSeekRateLadder.sustainedRate(0, 1, EPISODE)
        assertTrue(
            afterFirstStep <= TvSeekRateLadder.AIMABLE_MAX_RATE,
            "one second of holding jumped to ${afterFirstStep}x",
        )
    }

    @Test
    fun aSustainedHoldClimbsToTheItemsCeilingAndStops() {
        val ceiling = TvSeekRateLadder.maxRateFor(EPISODE)
        val reached = (0 until TvSeekRateLadder.rampSteps(EPISODE)).map {
            TvSeekRateLadder.sustainedRate(it, 1, EPISODE)
        }
        assertEquals(ceiling, reached.last())
        assertTrue(reached.zipWithNext().all { (a, b) -> b >= a }, "the ramp must not go backwards")
    }

    @Test
    fun sustainedRateFollowsTheHeldDirection() {
        (0 until TvSeekRateLadder.rampSteps(EPISODE)).forEach { step ->
            assertEquals(
                -TvSeekRateLadder.sustainedRate(step, 1, EPISODE),
                TvSeekRateLadder.sustainedRate(step, -1, EPISODE),
            )
        }
    }

    @Test
    fun bumpsWalkTheLadderAndClampToTheItemsCeiling() {
        assertEquals(4, TvSeekRateLadder.bumped(2, 1, EPISODE))
        assertEquals(2, TvSeekRateLadder.bumped(4, -1, EPISODE))

        val ceiling = TvSeekRateLadder.maxRateFor(EPISODE)
        assertEquals(ceiling, TvSeekRateLadder.bumped(ceiling, 1, EPISODE))
    }

    /**
     * delta is a direction along the signed ladder as the key handlers use it,
     * so -1 is leftwards: faster when already seeking backwards. The property
     * that matters is that a bump never crosses zero and flips direction.
     */
    @Test
    fun bumpingWhileSeekingBackwardsKeepsTheDirection() {
        assertEquals(-4, TvSeekRateLadder.bumped(-2, -1, EPISODE))
        assertEquals(-2, TvSeekRateLadder.bumped(-4, 1, EPISODE))
        assertEquals(-2, TvSeekRateLadder.bumped(-2, 1, EPISODE))
        listOf(-2, -4, -32).forEach { rate ->
            listOf(-1, 1).forEach { delta ->
                assertTrue(
                    TvSeekRateLadder.bumped(rate, delta, EPISODE) < 0,
                    "bumping $rate by $delta flipped direction",
                )
            }
        }
    }
}
