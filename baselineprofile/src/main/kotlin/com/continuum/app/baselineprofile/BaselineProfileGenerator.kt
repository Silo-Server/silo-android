package com.continuum.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test

/**
 * Generates the Baseline Profile consumed by :androidApp. Run on a device or the
 * managed device declared in this module's build script:
 *
 *   ./gradlew :baselineprofile:generateBaselineProfile
 *
 * Records a cold start to first frame plus a best-effort vertical scroll of the
 * first scrollable surface. A richer logged-in Home-scroll journey needs a
 * configured server + test account — extend [generate] to sign in and scroll the
 * real Home feed once that fixture exists; the cold-start capture alone already
 * covers process init, Compose/Koin warm-up, and first-frame layout.
 */
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Best-effort scroll: exercise whatever first surface the app lands on
        // (login or, if a session is restored, Home) so list/grid composition and
        // image decode paths are recorded too.
        device.findObject(By.scrollable(true))?.let { surface ->
            repeat(SCROLL_ITERATIONS) {
                surface.fling(Direction.DOWN)
                device.waitForIdle()
            }
            surface.fling(Direction.UP)
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.continuum.app"
        const val SCROLL_ITERATIONS = 3
    }
}
