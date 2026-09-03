package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ladder mirrors the Apple clients (`PlayerViewModel.seekRates`,
 * `TVPlayerScrubber.timelineAutoSeekRates`, `startHoldSeekAutoRamp`), so these
 * pin the numbers a viewer who uses both would notice drifting apart.
 */
class TvSeekRateLadderTest {

    @Test
    fun ladderMatchesApple() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32), TvSeekRateLadder.rates)
        assertEquals(1, TvSeekRateLadder.BASE_RATE)
        assertEquals(listOf(2, 4, 8), TvSeekRateLadder.rampRates)
        assertEquals(1_200L, TvSeekRateLadder.RAMP_STEP_MILLIS)
        assertEquals(100L, TvSeekRateLadder.TICK_MILLIS)
        assertEquals(2.0, TvSeekRateLadder.SECONDS_PER_TICK_AT_1X)
    }

    /** Apple's `holdSeekBaseStep * rate` per 100ms tick: 8× covers 16s a tick. */
    @Test
    fun aTickCoversAppleBaseStepTimesRate() {
        assertEquals(2.0, TvSeekRateLadder.tickSeconds(1), 0.0001)
        assertEquals(16.0, TvSeekRateLadder.tickSeconds(8), 0.0001)
        assertEquals(-64.0, TvSeekRateLadder.tickSeconds(-32), 0.0001)
    }

    /**
     * Advancing by measured elapsed time is what keeps a busy box honest: a
     * tick that lands 50ms late covers 50% more, not the same amount.
     */
    @Test
    fun elapsedSecondsScalesWithWallClock() {
        assertEquals(TvSeekRateLadder.tickSeconds(8), TvSeekRateLadder.elapsedSeconds(8, 100L), 0.0001)
        assertEquals(TvSeekRateLadder.tickSeconds(8) * 1.5, TvSeekRateLadder.elapsedSeconds(8, 150L), 0.0001)
        assertEquals(0.0, TvSeekRateLadder.elapsedSeconds(8, -20L), 0.0001)
    }

    @Test
    fun bumpsWalkTheSignedLadderAndClampAtTheEnds() {
        assertEquals(2, TvSeekRateLadder.bumped(1, 1))
        assertEquals(1, TvSeekRateLadder.bumped(2, -1))
        assertEquals(32, TvSeekRateLadder.bumped(32, 1))
        assertEquals(-32, TvSeekRateLadder.bumped(-32, -1))
        assertEquals(-4, TvSeekRateLadder.bumped(-2, -1))
        assertEquals(-2, TvSeekRateLadder.bumped(-4, 1))
    }

    /** As on tvOS: stepping past 1× in the opposite direction reverses. */
    @Test
    fun steppingPastOneReversesLikeApple() {
        assertEquals(-1, TvSeekRateLadder.bumped(1, -1))
        assertEquals(1, TvSeekRateLadder.bumped(-1, 1))
    }

    @Test
    fun anUnknownRateIsLeftAlone() {
        assertEquals(3, TvSeekRateLadder.bumped(3, 1))
        assertTrue(TvSeekRateLadder.rates.none { it == 3 })
    }
}
