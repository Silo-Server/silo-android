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
        assertEquals(-2, adjustedCleanPlaybackSeekRate(currentRate = -1, adjustment = -1))
        assertEquals(1, adjustedCleanPlaybackSeekRate(currentRate = -1, adjustment = 1))
        assertEquals(-1, adjustedCleanPlaybackSeekRate(currentRate = 1, adjustment = -1))
        assertEquals(2, adjustedCleanPlaybackSeekRate(currentRate = 1, adjustment = 1))
        assertEquals(32, adjustedCleanPlaybackSeekRate(currentRate = 16, adjustment = 1))
    }

    @Test
    fun rateAdjustmentClampsAtBothEnds() {
        assertEquals(-32, adjustedCleanPlaybackSeekRate(currentRate = -32, adjustment = -1))
        assertEquals(32, adjustedCleanPlaybackSeekRate(currentRate = 32, adjustment = 1))
    }

    @Test
    fun previewAdvancesByAppleParityBaseStepAndRate() {
        assertEquals(
            116.0,
            advanceCleanPlaybackSeekPreview(previewSec = 100.0, durationSec = 500.0, rate = 8),
        )
        assertEquals(
            92.0,
            advanceCleanPlaybackSeekPreview(previewSec = 100.0, durationSec = 500.0, rate = -4),
        )
    }

    @Test
    fun previewClampsToKnownTimelineBounds() {
        assertEquals(
            0.0,
            advanceCleanPlaybackSeekPreview(previewSec = 1.0, durationSec = 500.0, rate = -1),
        )
        assertEquals(
            500.0,
            advanceCleanPlaybackSeekPreview(previewSec = 499.0, durationSec = 500.0, rate = 1),
        )
    }

    @Test
    fun unknownDurationStillAllowsForwardPreview() {
        assertEquals(
            12.0,
            advanceCleanPlaybackSeekPreview(previewSec = 10.0, durationSec = 0.0, rate = 1),
        )
    }
}
