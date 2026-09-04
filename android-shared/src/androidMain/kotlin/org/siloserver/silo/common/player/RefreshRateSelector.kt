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
     * Judder-free pacing requires the display refresh to be an INTEGER multiple of
     * the content fps (24fps → 24/48/120 = clean; 60 = 2.5x = 3:2 judder). So
     * integer-multiple modes always beat non-integer ones, regardless of raw delta
     * — otherwise 60Hz (delta 36) wrongly beats 120Hz (delta 96) for 24fps content.
     * Among integer multiples, the closest refresh wins (24 over 48 over 120).
     */
    private fun scoreMode(mode: Display.Mode, frameRateHz: Float): Float {
        val rateDelta = abs(mode.refreshRate - frameRateHz)
        val ratio = mode.refreshRate / frameRateHz
        val integerMultiple = ratio >= 0.99f && abs(ratio - ratio.roundToInt()) < 0.05f
        return if (integerMultiple) rateDelta else rateDelta + 10_000f
    }
}
