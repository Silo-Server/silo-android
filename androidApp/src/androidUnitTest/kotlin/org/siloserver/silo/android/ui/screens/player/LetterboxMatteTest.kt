package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WINDOW_WIDTH = 3120
private const val WINDOW_HEIGHT = 1440

/** Landscape width of the S26 Ultra punch-hole, per `dumpsys window displays`. */
private const val CUTOUT_PX = 139

/** The default box: the display less a symmetric inset clear of the camera. */
private const val CLEAR_BOX_WIDTH = WINDOW_WIDTH - 2 * CUTOUT_PX

/**
 * Reference title one: a 2.39:1 scope film in a 3840x2160 frame. Its picture is
 * WIDER than the display, so fitting the content rect binds on width.
 */
private const val SCOPE_CODED_ASPECT = 3840f / 2160f
private const val SCOPE_CONTENT_ASPECT = 2.393f
private const val SCOPE_MATTE = 277.65f / 2160f

/**
 * Reference title two: a 1.90:1 film in a 1920x1080 frame, measured live on the
 * device. Its picture is NARROWER than the display, so fitting the content rect
 * binds on HEIGHT — the case the old fill-the-width rule silently declined.
 */
private const val FLAT_CODED_ASPECT = 1920f / 1080f
private const val FLAT_CONTENT_ASPECT = 1920f / 1009.5f
private const val FLAT_MATTE = 35.25f / 1080f

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

private fun sample(matte: Float) = MatteSample(matte, matte)

class LetterboxMatteTest {

    // ---- measureMatte -------------------------------------------------------

    @Test
    fun measuresBarsOnBothEdges() {
        val measured = measureMatte(frame(matteFraction = 0.125f), width = 8, height = 144)
        assertNotNull(measured)
        assertEquals(18f / 144f, measured.topFraction, 0.001f)
        assertEquals(18f / 144f, measured.bottomFraction, 0.001f)
    }

    @Test
    fun reportsNoBarsForAFullFrameImage() {
        val measured = measureMatte(frame(matteFraction = 0f), width = 8, height = 144)
        assertNotNull(measured)
        assertEquals(0f, measured.topFraction)
        assertEquals(0f, measured.bottomFraction)
    }

    @Test
    fun refusesAFadeToBlack() {
        val black = IntArray(8 * 144) { 0xFF000000.toInt() }
        assertNull(measureMatte(black, width = 8, height = 144))
    }

    @Test
    fun refusesAFrameThatIsMostlyBlack() {
        assertNull(measureMatte(frame(matteFraction = 0.45f), width = 8, height = 144))
    }

    @Test
    fun aBrightPixelKeepsItsRowOutOfTheMatte() {
        val pixels = frame(matteFraction = 0.125f)
        pixels[3 * 8 + 4] = colour(240)
        val measured = measureMatte(pixels, width = 8, height = 144)
        assertNotNull(measured)
        assertEquals(3f / 144f, measured.topFraction, 0.001f)
        assertEquals(18f / 144f, measured.bottomFraction, 0.001f)
    }

    @Test
    fun toleratesCodecRingingInTheBar() {
        val pixels = frame(matteFraction = 0.125f)
        pixels[3 * 8 + 4] = colour(MATTE_BLACK_CHANNEL_MAX)
        val measured = measureMatte(pixels, width = 8, height = 144)
        assertNotNull(measured)
        assertEquals(18f / 144f, measured.topFraction, 0.001f)
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(measureMatte(IntArray(0), width = 0, height = 0))
        assertNull(measureMatte(IntArray(4), width = 8, height = 144))
    }

    // ---- the safety property ------------------------------------------------

    @Test
    fun fittingTheContentRectNeverClipsMoreThanTheMatte() {
        // The proof in LetterboxMatte.kt, executed: across coded aspects, matte
        // thicknesses and box shapes — including boxes narrower and wider than
        // the content — the clip never exceeds the black that defined the rect.
        val codedAspects = listOf(4f / 3f, 1.5f, FLAT_CODED_ASPECT, 2.0f, 2.39f)
        val mattes = listOf(0.001f, 0.01f, FLAT_MATTE, 0.08f, SCOPE_MATTE, 0.24f)
        val boxAspects = listOf(0.6f, 1f, 1.6f, 1.9736f, 2.1667f, 3.2f)
        for (coded in codedAspects) {
            for (matte in mattes) {
                val safe = safeMatteFraction(matte)
                val fitted = contentAspect(coded, safe)
                for (box in boxAspects) {
                    val clip = verticalClipFraction(coded, fitted, box)
                    assertTrue(
                        clip <= safe + 1e-5f,
                        "clip $clip exceeded safe matte $safe (coded=$coded box=$box)",
                    )
                    assertTrue(
                        clip <= matte,
                        "clip $clip exceeded measured matte $matte (coded=$coded box=$box)",
                    )
                }
            }
        }
    }

    @Test
    fun holdsBackHeadroomProportionalToTheMatte() {
        // A flat fraction of frame height would be a rounding error on a scope
        // matte and two thirds of a 1.90:1 one, which is why this scales.
        assertEquals(SCOPE_MATTE * (1f - MATTE_MARGIN_FRACTION), safeMatteFraction(SCOPE_MATTE), 1e-5f)
        // Below the crossover the floor governs, covering row quantisation.
        assertEquals(FLAT_MATTE - MATTE_MARGIN_FLOOR, safeMatteFraction(FLAT_MATTE), 1e-5f)
        // A matte thinner than the floor is not worth acting on at all.
        assertEquals(0f, safeMatteFraction(MATTE_MARGIN_FLOOR / 2f))
        assertEquals(0f, safeMatteFraction(0f))
    }

    @Test
    fun contentWithNoStoredBarsIsLeftExactlyAlone() {
        // A 16:9 episode on this panel: nothing to discount, so the content rect
        // IS the coded frame, the scale is a plain fit and nothing moves.
        assertEquals(FLAT_CODED_ASPECT, contentAspect(FLAT_CODED_ASPECT, safeMatteFraction(0f)))
        val estimator = LetterboxFillEstimator()
        repeat(MATTE_SAMPLES_TO_SETTLE * 10) { estimator.onSample(sample(0f), FLAT_CODED_ASPECT) }
        assertEquals(FLAT_CODED_ASPECT, estimator.contentAspectFor(FLAT_CODED_ASPECT))
    }

    // ---- reference geometry -------------------------------------------------

    @Test
    fun scopeFilmBindsOnWidthAndKeepsGenuineLetterbox() {
        val fitted = contentAspect(SCOPE_CODED_ASPECT, safeMatteFraction(SCOPE_MATTE))
        val image = expandedImageSize(
            boxWidth = CLEAR_BOX_WIDTH,
            boxHeight = WINDOW_HEIGHT,
            contentAspect = fitted,
            trueContentAspect = SCOPE_CONTENT_ASPECT,
        )
        assertNotNull(image)
        assertEquals(2842, image.width)
        assertEquals(1188, image.height)
        assertEquals(CUTOUT_PX, (WINDOW_WIDTH - image.width) / 2)
    }

    @Test
    fun scopeFilmAtFullWidthReachesBothEdges() {
        val fitted = contentAspect(SCOPE_CODED_ASPECT, safeMatteFraction(SCOPE_MATTE))
        val image = expandedImageSize(
            boxWidth = WINDOW_WIDTH,
            boxHeight = WINDOW_HEIGHT,
            contentAspect = fitted,
            trueContentAspect = SCOPE_CONTENT_ASPECT,
        )
        assertNotNull(image)
        assertEquals(WINDOW_WIDTH, image.width)
        assertEquals(1304, image.height)
    }

    @Test
    fun flatFilmBindsOnHeightAndFillsTopToBottom() {
        // The regression this rule was generalised for: 1.90:1 is NARROWER than
        // the 2.167:1 display, so filling the width is impossible but filling
        // the HEIGHT is free — and the old rule only ever asked about width.
        val fitted = contentAspect(FLAT_CODED_ASPECT, safeMatteFraction(FLAT_MATTE))
        val image = expandedImageSize(
            boxWidth = CLEAR_BOX_WIDTH,
            boxHeight = WINDOW_HEIGHT,
            contentAspect = fitted,
            trueContentAspect = FLAT_CONTENT_ASPECT,
        )
        assertNotNull(image)
        assertEquals(2679, image.width)
        assertEquals(1409, image.height)
        // Comfortably taller than the 1346 a plain fit of the coded frame gives.
        assertTrue(image.height > 1346)
    }

    @Test
    fun flatFilmIsUnaffectedByTheCameraInset() {
        // It binds on height, so the width it wants is well inside the inset
        // box — the default costs this title nothing at all.
        val fitted = contentAspect(FLAT_CODED_ASPECT, safeMatteFraction(FLAT_MATTE))
        val clear = expandedImageSize(
            CLEAR_BOX_WIDTH, WINDOW_HEIGHT, fitted, FLAT_CONTENT_ASPECT,
        )
        val full = expandedImageSize(
            WINDOW_WIDTH, WINDOW_HEIGHT, fitted, FLAT_CONTENT_ASPECT,
        )
        assertNotNull(clear)
        assertNotNull(full)
        assertEquals(clear, full)
        assertTrue(clear.width < CLEAR_BOX_WIDTH)
    }

    // ---- cutout -------------------------------------------------------------

    @Test
    fun insetsSymmetricallyForEitherLandscapeRotation() {
        // The punch-hole lands against the left edge at ROTATION_90 and the
        // right at ROTATION_270. Both inset the same, or flipping the phone end
        // for end would shift the picture sideways.
        assertEquals(CUTOUT_PX, cutoutSafeHorizontalInset(CUTOUT_PX, 0))
        assertEquals(CUTOUT_PX, cutoutSafeHorizontalInset(0, CUTOUT_PX))
    }

    @Test
    fun leavesAScreenWithoutASideCutoutAlone() {
        // Portrait reports the cutout on the top edge, which this ignores: the
        // video is nowhere near it and must not be pushed down.
        assertEquals(0, cutoutSafeHorizontalInset(0, 0))
    }

    // ---- estimator ----------------------------------------------------------

    private fun feed(
        estimator: LetterboxFillEstimator,
        matte: Float?,
        times: Int = 1,
        codedAspect: Float = SCOPE_CODED_ASPECT,
    ): Float {
        var aspect = codedAspect
        repeat(times) {
            aspect = estimator.onSample(matte?.let(::sample), codedAspect)
        }
        return aspect
    }

    @Test
    fun appliesNothingUntilEnoughFramesAgree() {
        val estimator = LetterboxFillEstimator()
        assertEquals(
            SCOPE_CODED_ASPECT,
            feed(estimator, SCOPE_MATTE, times = MATTE_SAMPLES_TO_SETTLE - 1),
        )
        assertTrue(feed(estimator, SCOPE_MATTE) > SCOPE_CODED_ASPECT)
        assertTrue(estimator.isSettled)
    }

    @Test
    fun narrowsOnTheVeryFirstFrameThatDisagreesAndStaysNarrow() {
        val estimator = LetterboxFillEstimator()
        val expanded = feed(estimator, SCOPE_MATTE, times = MATTE_SAMPLES_TO_SETTLE)
        // An IMAX sequence opening up: one frame, and the crop is given back.
        val narrowed = feed(estimator, FLAT_MATTE)
        assertTrue(narrowed < expanded)
        // A monotone minimum cannot oscillate, so the picture never breathes —
        // this is what instant revert and latch-off both reduce to.
        assertEquals(narrowed, feed(estimator, SCOPE_MATTE, times = 40))
    }

    @Test
    fun holdsTheEstimateWhileThereIsNoEvidence() {
        val estimator = LetterboxFillEstimator()
        val expanded = feed(estimator, SCOPE_MATTE, times = MATTE_SAMPLES_TO_SETTLE)
        assertEquals(expanded, feed(estimator, null, times = 10))

        // And an unusable frame is not progress towards settling either.
        val cold = LetterboxFillEstimator()
        assertEquals(SCOPE_CODED_ASPECT, feed(cold, null, times = 10))
    }

    @Test
    fun theThinnerEdgeGoverns() {
        // An off-centre image is not a letterbox; cropping to the thicker edge
        // would cut the picture on the thinner one.
        val estimator = LetterboxFillEstimator()
        repeat(MATTE_SAMPLES_TO_SETTLE) {
            estimator.onSample(MatteSample(SCOPE_MATTE, 0f), SCOPE_CODED_ASPECT)
        }
        assertEquals(SCOPE_CODED_ASPECT, estimator.contentAspectFor(SCOPE_CODED_ASPECT))
    }

    @Test
    fun aRememberedMatteAppliesBeforeAnyFrameArrives() {
        val estimator = LetterboxFillEstimator()
        estimator.seed(SCOPE_MATTE)
        // The point of the cache: expanded on the first presented frame.
        assertTrue(estimator.contentAspectFor(SCOPE_CODED_ASPECT) > SCOPE_CODED_ASPECT)
    }

    @Test
    fun liveFramesReplaceARememberedMatteEntirely() {
        val estimator = LetterboxFillEstimator()
        estimator.seed(SCOPE_MATTE)
        // A stale entry claiming a thick matte is corrected by measurement
        // rather than governing the session — and never written back.
        val settled = feed(estimator, FLAT_MATTE, times = MATTE_SAMPLES_TO_SETTLE)
        assertEquals(contentAspect(SCOPE_CODED_ASPECT, safeMatteFraction(FLAT_MATTE)), settled, 1e-5f)
        assertEquals(FLAT_MATTE, estimator.observedMatte)
    }

    @Test
    fun onlyLiveFramesAreEverRememberedBack() {
        val estimator = LetterboxFillEstimator()
        estimator.seed(SCOPE_MATTE)
        assertNull(estimator.observedMatte)
    }

    @Test
    fun resetClearsEverythingForTheNextItem() {
        val estimator = LetterboxFillEstimator()
        estimator.seed(SCOPE_MATTE)
        feed(estimator, SCOPE_MATTE, times = MATTE_SAMPLES_TO_SETTLE)
        estimator.reset()
        assertNull(estimator.observedMatte)
        assertTrue(!estimator.isSettled)
        assertEquals(SCOPE_CODED_ASPECT, estimator.contentAspectFor(SCOPE_CODED_ASPECT))
    }

    // ---- cache key ----------------------------------------------------------

    @Test
    fun cacheKeyNamesTheExactStreamNotTheTitle() {
        val base = letterboxMatteCacheKey("https://silo", "movie-1", 42, 3840, 2160)
        assertNotNull(base)
        // A different cut, or the same file arriving transcoded at another
        // resolution, must not inherit a crop measured from this one.
        assertTrue(base != letterboxMatteCacheKey("https://silo", "movie-1", 43, 3840, 2160))
        assertTrue(base != letterboxMatteCacheKey("https://silo", "movie-1", 42, 1920, 1080))
        assertTrue(base != letterboxMatteCacheKey("https://other", "movie-1", 42, 3840, 2160))
    }

    @Test
    fun cacheKeyRefusesMediaItCannotNamePrecisely() {
        assertNull(letterboxMatteCacheKey("https://silo", "movie-1", null, 3840, 2160))
        assertNull(letterboxMatteCacheKey("https://silo", null, 42, 3840, 2160))
        assertNull(letterboxMatteCacheKey("https://silo", "movie-1", 42, 0, 0))
        // Content and file ids are scoped to whoever issued them, so without an
        // origin the tuple names nothing in particular — two servers' downloads
        // would share it.
        assertNull(letterboxMatteCacheKey("", "movie-1", 42, 3840, 2160))
        assertNull(letterboxMatteCacheKey(null, "movie-1", 42, 3840, 2160))
    }
}
