package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvCleanPlaybackSeekTest {
    @Test
    fun hiddenPressCrossingThreeHundredMillisecondsEntersHold() {
        assertEquals(false, shouldEnterCleanPlaybackSeekHold(allowsHold = true, pressDurationMs = 299L))
        assertEquals(true, shouldEnterCleanPlaybackSeekHold(allowsHold = true, pressDurationMs = 300L))
    }

    @Test
    fun quickSkipCapturePressNeverStealsFocusedScrubberHoldMode() {
        assertEquals(false, shouldEnterCleanPlaybackSeekHold(allowsHold = false, pressDurationMs = 900L))
    }

    @Test
    fun onlyShortNonRepeatingPressAdjustsPersistentRate() {
        assertEquals(true, isCleanPlaybackSeekAdjustmentTap(repeated = false, pressDurationMs = 299L))
        assertEquals(false, isCleanPlaybackSeekAdjustmentTap(repeated = false, pressDurationMs = 300L))
        assertEquals(false, isCleanPlaybackSeekAdjustmentTap(repeated = true, pressDurationMs = 100L))
    }

    @Test
    fun manualTapsWalkTheSignedRateLadder() {
        // A 90-minute item; its ladder ceiling is well above these rungs.
        val durationSec = 5_400.0
        assertEquals(4, adjustedCleanPlaybackSeekRate(2, adjustment = 1, durationSec = durationSec))
        assertEquals(2, adjustedCleanPlaybackSeekRate(4, adjustment = -1, durationSec = durationSec))
        assertEquals(-4, adjustedCleanPlaybackSeekRate(-2, adjustment = -1, durationSec = durationSec))
        assertEquals(-2, adjustedCleanPlaybackSeekRate(-4, adjustment = 1, durationSec = durationSec))
        assertEquals(32, adjustedCleanPlaybackSeekRate(16, adjustment = 1, durationSec = durationSec))
    }

    @Test
    fun steppingBelowTheBaseRateStopsRatherThanReversingDirection() {
        // The old signed ladder ran ... -1, 1 ... so stepping "slower" past the
        // bottom silently flipped a forward scan into a backward one.
        val durationSec = 5_400.0
        assertEquals(2, adjustedCleanPlaybackSeekRate(2, adjustment = -1, durationSec = durationSec))
        assertEquals(-2, adjustedCleanPlaybackSeekRate(-2, adjustment = 1, durationSec = durationSec))
    }

    @Test
    fun rateAdjustmentClampsAtTheItemsDerivedCeiling() {
        // 90 minutes needs ceil(5400 / 10) = 540x to cross in the target time,
        // which rounds up to the 1024 rung.
        val durationSec = 5_400.0
        assertEquals(1024, adjustedCleanPlaybackSeekRate(1024, adjustment = 1, durationSec = durationSec))
        assertEquals(-1024, adjustedCleanPlaybackSeekRate(-1024, adjustment = -1, durationSec = durationSec))
    }

    @Test
    fun shortContentGetsALowerCeilingThanAFeature() {
        // A 22-minute episode: ceil(1320 / 10) = 132x, rounded up to 256.
        val episodeSec = 1_320.0
        assertEquals(256, adjustedCleanPlaybackSeekRate(256, adjustment = 1, durationSec = episodeSec))
    }

    @Test
    fun previewAdvancesByExactlyRateTimesRealTime() {
        // 100ms tick, so one tick at 8x covers 0.8s of content — not the 16s
        // the old flat 2s-per-tick base step produced for the same "8x" chip.
        assertEquals(
            100.8,
            advanceCleanPlaybackSeekPreview(previewSec = 100.0, durationSec = 500.0, rate = 8),
        )
        assertEquals(
            99.6,
            advanceCleanPlaybackSeekPreview(previewSec = 100.0, durationSec = 500.0, rate = -4),
        )
    }

    @Test
    fun previewClampsToKnownTimelineBounds() {
        // Rates large enough that a single tick overshoots each end.
        assertEquals(
            0.0,
            advanceCleanPlaybackSeekPreview(previewSec = 1.0, durationSec = 500.0, rate = -64),
        )
        assertEquals(
            500.0,
            advanceCleanPlaybackSeekPreview(previewSec = 499.0, durationSec = 500.0, rate = 64),
        )
    }

    @Test
    fun unknownDurationStillAllowsForwardPreview() {
        assertEquals(
            10.2,
            advanceCleanPlaybackSeekPreview(previewSec = 10.0, durationSec = 0.0, rate = 2),
        )
    }
}
