package org.siloserver.silo.common.player

import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picks the best display mode for a given target refresh rate. Shared between
 * [HdrDisplayController] (TV — uses it as one input to an
 * HDMI-resolution-first selection) and [RefreshRateMatcher] (phone — uses it
 * as the *only* selection criterion, since the phone panel resolution is
 * fixed and changing it would downgrade the panel pipeline).
 */
internal object RefreshRateSelector {

    /** Overload that filters candidates first (e.g. by resolution on a TV panel). */
    fun pickByRefreshRateAmong(
        candidates: List<Display.Mode>,
        frameRateHz: Float,
    ): Display.Mode? {
        if (candidates.isEmpty() || frameRateHz <= 0f) return null
        return candidates.minByOrNull { scoreMode(it, frameRateHz) }
    }

    /**
     * Overload where modes scoring within [TIE_TOLERANCE_HZ] of the best count
     * as tied and [tieBreak] picks among them. Displays can report the same
     * nominal rate slightly differently per resolution (pixel-clock rounding);
     * that noise must not decide the pick.
     */
    fun pickByRefreshRateAmong(
        candidates: List<Display.Mode>,
        frameRateHz: Float,
        tieBreak: Comparator<Display.Mode>,
    ): Display.Mode? {
        if (candidates.isEmpty() || frameRateHz <= 0f) return null
        val best = candidates.minBy { scoreMode(it, frameRateHz) }
        val bestIsIntegerMultiple = isIntegerMultiple(best, frameRateHz)
        val bestDelta = abs(best.refreshRate - frameRateHz)
        // Compare the penalty class and the raw delta apart: near the 10_000
        // penalty a Float score is too coarse to resolve the tolerance.
        return candidates
            .filter {
                isIntegerMultiple(it, frameRateHz) == bestIsIntegerMultiple &&
                    abs(it.refreshRate - frameRateHz) - bestDelta <= TIE_TOLERANCE_HZ
            }
            .minWithOrNull(tieBreak)
    }

    private const val TIE_TOLERANCE_HZ = 0.005f

    /**
     * Judder-free pacing requires the display refresh to be an INTEGER multiple of
     * the content fps (24fps → 24/48/120 = clean; 60 = 2.5x = 3:2 judder). So
     * integer-multiple modes always beat non-integer ones, regardless of raw delta
     * — otherwise 60Hz (delta 36) wrongly beats 120Hz (delta 96) for 24fps content.
     * Among integer multiples, the closest refresh wins (24 over 48 over 120).
     */
    private fun scoreMode(mode: Display.Mode, frameRateHz: Float): Float {
        val rateDelta = abs(mode.refreshRate - frameRateHz)
        return if (isIntegerMultiple(mode, frameRateHz)) rateDelta else rateDelta + 10_000f
    }

    private fun isIntegerMultiple(mode: Display.Mode, frameRateHz: Float): Boolean {
        val ratio = mode.refreshRate / frameRateHz
        return ratio >= 0.99f && abs(ratio - ratio.roundToInt()) < 0.05f
    }
}
