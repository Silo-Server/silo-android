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
     * roughly the same whether the item is twenty minutes or three hours. A
     * fixed 32x ceiling took 41s for a short episode and 338s for a long film.
     *
     * Asserted against [TvSeekRateLadder.traverseSeconds], which models the
     * ramp the implementation actually performs. The previous version computed
     * `duration / topRate` — steady-state arithmetic the code never does — and
     * so reported 10.55s for a three-hour film that really takes 17.75s,
     * passing a 15s tolerance it should have failed.
     */
    @Test
    fun holdingToTheEndCostsAboutTheSameAtAnyRuntime() {
        val durations = listOf(SHORT_EPISODE, EPISODE, 5_400.0, LONG_FILM)
        val costs = durations.map { TvSeekRateLadder.traverseSeconds(it) }

        costs.forEachIndexed { index, seconds ->
            assertTrue(
                seconds <= 20.0,
                "a ${durations[index]}s item takes ${seconds}s to cross",
            )
        }
        assertTrue(
            costs.max() / costs.min() <= 2.0,
            "runtimes should cost within 2x of each other, got $costs",
        )
    }

    /**
     * The honest envelope, pinned. If a ladder or cadence change moves these,
     * the numbers in TRAVERSE_TARGET_SECONDS' documentation are wrong too.
     */
    @Test
    fun traversalCostIncludesTheRampNotJustTheCeiling() {
        assertEquals(10.56, TvSeekRateLadder.traverseSeconds(SHORT_EPISODE), 0.01)
        assertEquals(17.75, TvSeekRateLadder.traverseSeconds(LONG_FILM), 0.01)
        assertTrue(
            TvSeekRateLadder.traverseSeconds(LONG_FILM) >
                LONG_FILM / TvSeekRateLadder.maxRateFor(LONG_FILM),
            "the ramp must cost something; steady-state division understates it",
        )
    }

    /**
     * Protocol v3 declares duration server-side and deliberately does not fall
     * back to Media3/catalog, so an omitted duration reaches the ladder as 0.
     * That lands on the MIN_TOP_RATE floor rather than a derived ceiling —
     * slow for a long item, but the alternative is guessing a ceiling for
     * content of unknown length.
     */
    @Test
    fun anUnknownDurationFallsBackToTheFloorRatherThanGuessing() {
        assertEquals(TvSeekRateLadder.MIN_TOP_RATE, TvSeekRateLadder.maxRateFor(0.0))
        assertEquals(TvSeekRateLadder.MIN_TOP_RATE, TvSeekRateLadder.maxRateFor(Double.NaN))
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
