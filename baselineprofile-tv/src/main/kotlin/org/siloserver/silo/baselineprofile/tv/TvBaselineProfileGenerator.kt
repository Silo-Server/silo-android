package org.siloserver.silo.baselineprofile.tv

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Records the TV app's hot paths: cold start to first frame, then a d-pad
 * browse of whatever surface it lands on. On a signed-in device that is Home —
 * vertical moves across rows (row composition, card rails, hero crossfade) and
 * horizontal moves within a rail (card focus, pinning scroll) — which is
 * exactly the code that JIT-stalls on a fresh install. Runs three iterations so
 * the profile keeps only methods hot on every pass.
 */
class TvBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE_NAME,
        maxIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // Let the feed and hero artwork settle before browsing.
        Thread.sleep(SETTLE_MS)

        // Down through the rows, a short right/left within each, then back up.
        repeat(ROWS) {
            device.pressDPadDown()
            device.waitForIdle()
            repeat(CARDS) { device.pressDPadRight(); device.waitForIdle() }
            repeat(CARDS) { device.pressDPadLeft(); device.waitForIdle() }
        }
        repeat(ROWS) { device.pressDPadUp(); device.waitForIdle() }
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val PACKAGE_NAME = "org.siloserver.silo"
        const val ROWS = 5
        const val CARDS = 4
        const val SETTLE_MS = 1_500L
    }
}
