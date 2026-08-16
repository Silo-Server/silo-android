package org.siloserver.silo.android.ui.screens.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reference case measured on a Galaxy S26 Ultra: a 2.39:1 film encoded
 * inside a 3840x2160 frame, played landscape on a 3120x1440 window.
 */
private const val CODED_ASPECT = 3840f / 2160f
private const val CONTAINER_ASPECT = 3120f / 1440f

/** Matte per edge for 2.39:1 content in a 16:9 frame, as a fraction of height. */
private const val SCOPE_MATTE = 0.1281f

/** Matte per edge for 1.85:1 content in the same frame — far thinner. */
private const val FLAT_MATTE = 0.0195f

private fun frame(
    matteFraction: Float,
    width: Int = 8,
    height: Int = 144,
    pictureChannel: Int = 200,
): IntArray {
    val bar = (matteFraction * height).toInt()
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val black = row < bar || row >= height - bar
        val value = if (black) 0xFF000000.toInt() else colour(pictureChannel)
        for (column in 0 until width) pixels[row * width + column] = value
    }
    return pixels
}

private fun colour(channel: Int): Int =
    (0xFF shl 24) or (channel shl 16) or (channel shl 8) or channel

private fun scopeSample() = MatteSample(SCOPE_MATTE, SCOPE_MATTE)

class LetterboxMatteTest {

    // ---- measureMatte -------------------------------------------------------

    @Test
    fun measuresBarsOnBothEdges() {
        val sample = measureMatte(frame(matteFraction = 0.125f), width = 8, height = 144)
        requireNotNull(sample)
        assertEquals(18f / 144f, sample.topFraction, 0.001f)
        assertEquals(18f / 144f, sample.bottomFraction, 0.001f)
    }

    @Test
    fun reportsNoBarsForAFullFrameImage() {
        val sample = measureMatte(frame(matteFraction = 0f), width = 8, height = 144)
        requireNotNull(sample)
        assertEquals(0f, sample.topFraction)
        assertEquals(0f, sample.bottomFraction)
    }

    @Test
    fun refusesAFadeToBlack() {
        val black = IntArray(8 * 144) { 0xFF000000.toInt() }
        assertNull(measureMatte(black, width = 8, height = 144))
    }

    @Test
    fun refusesAFrameThatIsMostlyBlack() {
        // A near-black night scene must not read as an enormous matte.
        assertNull(measureMatte(frame(matteFraction = 0.45f), width = 8, height = 144))
    }

    @Test
    fun aBrightPixelKeepsItsRowOutOfTheMatte() {
        // One caption pixel in the bar is enough: the row is picture, not matte.
        val pixels = frame(matteFraction = 0.125f)
        pixels[3 * 8 + 4] = colour(240)
        val sample = measureMatte(pixels, width = 8, height = 144)
        requireNotNull(sample)
        assertEquals(3f / 144f, sample.topFraction, 0.001f)
        // The far edge is untouched, and the estimator takes the thinner one.
        assertEquals(18f / 144f, sample.bottomFraction, 0.001f)
    }

    @Test
    fun toleratesCodecRingingInTheBar() {
        val pixels = frame(matteFraction = 0.125f)
        pixels[3 * 8 + 4] = colour(MATTE_BLACK_CHANNEL_MAX)
        val sample = measureMatte(pixels, width = 8, height = 144)
        requireNotNull(sample)
        assertEquals(18f / 144f, sample.topFraction, 0.001f)
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(measureMatte(IntArray(0), width = 0, height = 0))
        assertNull(measureMatte(IntArray(4), width = 8, height = 144))
    }

    // ---- crop geometry ------------------------------------------------------

    @Test
    fun zoomClipMatchesTheMeasuredDevice() {
        // 3840x2160 covering 3120x1440 scales by 0.8125, so 315 of 1755 rows
        // fall outside the window: 157.5 per edge of 2160 coded rows.
        val crop = zoomVerticalCropFraction(CODED_ASPECT, CONTAINER_ASPECT)
        assertEquals(193.8f / 2160f, crop, 0.001f)
        assertTrue(abs(crop - 0.0897f) < 0.001f)
    }

    @Test
    fun declinesWhenTheVideoIsNotNarrowerThanTheWindow() {
        // ZOOM would clip horizontally instead, which the bar measurement says
        // nothing about, so there is no safe vertical answer to give.
        assertEquals(0f, zoomVerticalCropFraction(CONTAINER_ASPECT, CONTAINER_ASPECT))
        assertEquals(0f, zoomVerticalCropFraction(2.39f, CONTAINER_ASPECT))
        assertEquals(0f, zoomVerticalCropFraction(0f, CONTAINER_ASPECT))
    }

    // ---- estimator ----------------------------------------------------------

    private fun feed(
        estimator: LetterboxFillEstimator,
        sample: MatteSample?,
        times: Int = 1,
    ): Boolean {
        var engaged = false
        repeat(times) {
            engaged = estimator.onSample(sample, CODED_ASPECT, CONTAINER_ASPECT)
        }
        return engaged
    }

    @Test
    fun engagesOnlyAfterConsecutiveClearingFrames() {
        val estimator = LetterboxFillEstimator()
        assertFalse(feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE - 1))
        assertTrue(feed(estimator, scopeSample()))
    }

    @Test
    fun oneAmbiguousFrameRestartsTheCount() {
        val estimator = LetterboxFillEstimator()
        feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE - 1)
        // A frame whose picture reaches into the bar resets the evidence.
        assertFalse(feed(estimator, MatteSample(FLAT_MATTE, FLAT_MATTE)))
        assertFalse(feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE - 1))
        assertTrue(feed(estimator, scopeSample()))
    }

    @Test
    fun neverEngagesForAThinMatte() {
        // 1.85:1 in a 16:9 frame: ZOOM's clip would reach past the bar and into
        // the picture, so this has to stay on FIT however long it plays.
        val estimator = LetterboxFillEstimator()
        assertFalse(feed(estimator, MatteSample(FLAT_MATTE, FLAT_MATTE), times = 40))
    }

    @Test
    fun neverEngagesWhenOnlyOneEdgeIsMatted() {
        // An off-centre image is not a letterbox; the thinner edge governs.
        val estimator = LetterboxFillEstimator()
        assertFalse(feed(estimator, MatteSample(SCOPE_MATTE * 2f, 0f), times = 40))
    }

    @Test
    fun revertsOnTheVeryFirstFrameThatStopsClearing() {
        val estimator = LetterboxFillEstimator()
        assertTrue(feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE))
        // An IMAX sequence opening up: one sample interval of crop, no more.
        assertFalse(feed(estimator, MatteSample(FLAT_MATTE, FLAT_MATTE)))
    }

    @Test
    fun holdsStateWhileThereIsNoEvidence() {
        val estimator = LetterboxFillEstimator()
        feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE)
        // A fade to black is not a reason to drop the crop, or to take one.
        assertTrue(feed(estimator, null, times = 10))

        val cold = LetterboxFillEstimator()
        assertFalse(feed(cold, null, times = 10))
    }

    @Test
    fun givesUpAfterRepeatedFlapping() {
        val estimator = LetterboxFillEstimator()
        repeat(MATTE_MAX_FLAPS + 1) {
            feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE)
            feed(estimator, MatteSample(FLAT_MATTE, FLAT_MATTE))
        }
        assertTrue(estimator.isAbandoned)
        // Latched off: even perfect evidence no longer moves it.
        assertFalse(feed(estimator, scopeSample(), times = 40))
    }

    @Test
    fun dropsAnEngagedCropWhenTheWindowStopsBeingWider() {
        val estimator = LetterboxFillEstimator()
        assertTrue(feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE))
        // Rotating to portrait leaves nothing for a vertical clip to win.
        assertFalse(estimator.onSample(scopeSample(), CODED_ASPECT, 1440f / 3120f))
    }

    @Test
    fun resetClearsEverythingForTheNextItem() {
        val estimator = LetterboxFillEstimator()
        repeat(MATTE_MAX_FLAPS + 1) {
            feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE)
            feed(estimator, MatteSample(FLAT_MATTE, FLAT_MATTE))
        }
        estimator.reset()
        assertFalse(estimator.isAbandoned)
        assertTrue(feed(estimator, scopeSample(), times = MATTE_SAMPLES_TO_ENGAGE))
    }
}
