package org.siloserver.silo.common.player

import android.app.Activity
import android.view.Display
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
// SDK 29: Robolectric's Activity throws from getDisplay() on 30+, and the
// controller reads the display through WindowManager below 30.
@Config(sdk = [29])
class HdrDisplayControllerTest {

    // A 4K TV that lists its smaller modes first, as the enumeration order is
    // up to the platform and must not decide the output resolution. Id 0 is
    // the active home-screen mode Robolectric's display starts in.
    private val tvModes = listOf(
        mode(5, 720, 480, 59.94f),
        mode(1, 1920, 1080, 23.976f),
        mode(2, 1920, 1080, 59.94f),
        mode(3, 3840, 2160, 23.976f),
        mode(4, 3840, 2160, 59.94f),
        mode(6, 4096, 2160, 23.976f),
        mode(0, 3840, 2160, 60f),
    )

    @Test
    fun scopeUhdVideoKeepsUhdOutput() {
        assertEquals(3, appliedModeFor(tvModes, 3840, 1600, 23.976f))
    }

    @Test
    fun smallerVideoKeepsTheLargestModeAtTheSameRefreshRate() {
        assertEquals(3, appliedModeFor(tvModes, 1920, 800, 23.976f))
        assertEquals(3, appliedModeFor(tvModes.reversed(), 1920, 800, 23.976f))
        assertEquals(4, appliedModeFor(tvModes, 720, 404, 59.94f))
    }

    @Test
    fun judderFreeRefreshOutranksResolution() {
        val modes = listOf(
            mode(1, 1920, 1080, 23.976f),
            mode(2, 3840, 2160, 59.94f),
            mode(0, 3840, 2160, 60f),
        )
        assertEquals(1, appliedModeFor(modes, 1920, 800, 23.976f))
    }

    @Test
    fun refreshRateNoiseDoesNotDecideTheResolution() {
        val modes = listOf(
            mode(1, 1920, 1080, 23.97601f),
            mode(2, 3840, 2160, 23.97603f),
            mode(0, 3840, 2160, 60f),
        )
        assertEquals(2, appliedModeFor(modes, 1920, 800, 23.976f))
    }

    @Test
    fun exactResolutionMatchStillWins() {
        assertEquals(1, appliedModeFor(tvModes, 1920, 1080, 23.976f))
        assertEquals(4, appliedModeFor(tvModes, 3840, 2160, 59.94f))
    }

    @Test
    fun videoLargerThanEveryModeUsesTheLargestMode() {
        assertEquals(6, appliedModeFor(tvModes, 5120, 2160, 23.976f))
    }

    @Test
    fun unknownFrameRateLeavesTheModeAlone() {
        assertEquals(4, appliedModeFor(tvModes, 3840, 1600, 0f, preferredBefore = 4))
    }

    private fun appliedModeFor(
        modes: List<Display.Mode>,
        width: Int,
        height: Int,
        frameRate: Float,
        preferredBefore: Int = 0,
    ): Int {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, *modes.toTypedArray())
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = preferredBefore
        }
        val controller = HdrDisplayController()
        controller.attach(activity)
        controller.applyForMedia(width, height, frameRate)
        return activity.window.attributes.preferredDisplayModeId
    }

    // Display.Mode has no public constructor; Robolectric runs the real
    // framework class, so the hidden one is available.
    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float): Display.Mode =
        Display.Mode::class.java
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
            .newInstance(id, width, height, refreshRate)
}
