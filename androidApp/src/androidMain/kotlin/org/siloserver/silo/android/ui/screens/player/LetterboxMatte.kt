package org.siloserver.silo.android.ui.screens.player

/**
 * Geometry for auto-filling video whose black bars are baked into the picture.
 *
 * Scope films are almost always distributed inside a 16:9 coded frame — a
 * 2.39:1 image with the matte encoded as real pixels (Blu-ray and UHD only
 * allow 16:9 frame sizes, so every scope title from disc is hard-matted).
 * Nothing in the container, the bitstream, or the server's ffprobe metadata
 * describes that inner image: `display_aspect_ratio` reports the coded 16:9, so
 * the only way to know where the picture actually starts is to look at pixels.
 *
 * `RESIZE_MODE_FIT` therefore fits the *coded* frame, and on a 2.17:1 phone a
 * 16:9 frame is pillarboxed to 2560x1440 with the encoded matte inside that —
 * bars on all four sides even though the image itself is wider than the
 * display. `RESIZE_MODE_ZOOM` scales the coded frame to cover the window and
 * clips the overflow, which removes the pillarbox; whether that clip lands in
 * the encoded matte (free) or in the picture (unacceptable) depends entirely on
 * how thick the matte is.
 *
 * So the decision is a measurement. [measureMatte] reads one sampled frame and
 * [LetterboxFillEstimator] only engages ZOOM once several consecutive frames
 * have proven the matte is thicker than the clip. Every approximation here
 * leans toward under-cropping: leaving a bar is a cosmetic loss, cutting
 * picture is not.
 *
 * Frames are sampled with `PixelCopy` off the video SurfaceView, which reads
 * that surface's own buffer — the decoded frame — rather than the composited,
 * clipped region on screen. Measurements are therefore always in coded-frame
 * terms and do not shift when the crop this file decides on is applied.
 */

/** Top and bottom black bars of one sampled frame, as fractions of its height. */
internal data class MatteSample(
    val topFraction: Float,
    val bottomFraction: Float,
)

/** A channel value at or below this counts as black. PQ and SDR both encode a
 *  true matte at ~0; the headroom absorbs codec ringing at the matte edge. */
internal const val MATTE_BLACK_CHANNEL_MAX = 20

/** Beyond this much black the frame is a fade or a night scene, not evidence. */
private const val MAX_CREDIBLE_BLACK_FRACTION = 0.6f

/** Matte required beyond the clip before engaging, as a fraction of coded
 *  height (~43px of 2160) — headroom for sampling error and codec ringing. */
internal const val MATTE_ENGAGE_MARGIN = 0.02f

/** Once engaged, evidence may decay this far before the crop is surrendered. */
internal const val MATTE_REVERT_MARGIN = 0.005f

/** Consecutive clearing frames required before the crop is applied. */
internal const val MATTE_SAMPLES_TO_ENGAGE = 4

/** Engage/revert cycles tolerated per item before auto-fill gives up on it. */
internal const val MATTE_MAX_FLAPS = 2

/**
 * Measures the black bars in a sampled frame laid out as [width] x [height]
 * ARGB pixels.
 *
 * A row counts as black only when its BRIGHTEST pixel is black, so a caption or
 * a studio logo sitting in the bar keeps that row out of the matte. Returns
 * null when the frame is too black to carry evidence — a fade must not read as
 * a very wide matte, and null neither engages nor reverts.
 */
internal fun measureMatte(
    pixels: IntArray,
    width: Int,
    height: Int,
    channelMax: Int = MATTE_BLACK_CHANNEL_MAX,
): MatteSample? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null

    fun rowIsBlack(row: Int): Boolean {
        val start = row * width
        for (i in start until start + width) {
            val pixel = pixels[i]
            if (((pixel shr 16) and 0xFF) > channelMax) return false
            if (((pixel shr 8) and 0xFF) > channelMax) return false
            if ((pixel and 0xFF) > channelMax) return false
        }
        return true
    }

    var top = 0
    while (top < height && rowIsBlack(top)) top++
    // A fully black frame exits that loop at `height`; stop before the second
    // walks back over the same rows and counts them twice.
    if (top >= height) return null
    var bottom = 0
    while (bottom < height - top && rowIsBlack(height - 1 - bottom)) bottom++

    if ((top + bottom).toFloat() / height > MAX_CREDIBLE_BLACK_FRACTION) return null

    return MatteSample(
        topFraction = top.toFloat() / height,
        bottomFraction = bottom.toFloat() / height,
    )
}

/**
 * The fraction of coded height `RESIZE_MODE_ZOOM` would clip from each edge.
 *
 * Zero when the video is at least as wide as its container: ZOOM then crops
 * horizontally instead, which this vertical check says nothing about, so
 * auto-fill declines rather than guessing.
 */
internal fun zoomVerticalCropFraction(videoAspect: Float, containerAspect: Float): Float {
    if (videoAspect <= 0f || containerAspect <= 0f) return 0f
    if (videoAspect >= containerAspect) return 0f
    return (1f - videoAspect / containerAspect) / 2f
}

/**
 * Decides whether the encoded matte is thick enough to zoom into.
 *
 * Engaging is deliberate and reverting is instant. Engaging needs
 * [MATTE_SAMPLES_TO_ENGAGE] consecutive frames clearing the clip by
 * [MATTE_ENGAGE_MARGIN]; a single frame that fails to clear it by
 * [MATTE_REVERT_MARGIN] gives the crop straight back. That asymmetry is what
 * bounds a mid-film aspect change — an IMAX sequence opening up, an ad break,
 * burned-in subtitles reaching into the bar — to one sample interval of
 * over-crop rather than the rest of the reel.
 *
 * If the crop keeps being taken and given back, the evidence is not stable
 * enough to act on and a viewer would see the picture breathing. After
 * [MATTE_MAX_FLAPS] cycles this latches off for the rest of the item: quietly
 * doing nothing is the correct failure mode.
 */
internal class LetterboxFillEstimator(
    private val engageMargin: Float = MATTE_ENGAGE_MARGIN,
    private val revertMargin: Float = MATTE_REVERT_MARGIN,
    private val samplesToEngage: Int = MATTE_SAMPLES_TO_ENGAGE,
    private val maxFlaps: Int = MATTE_MAX_FLAPS,
) {
    private var clearingSamples = 0
    private var flaps = 0

    var isEngaged: Boolean = false
        private set

    /** True once this item is written off and sampling can stop entirely. */
    var isAbandoned: Boolean = false
        private set

    /** Forgets all evidence, including the flap count — a new media mount. */
    fun reset() {
        clearingSamples = 0
        flaps = 0
        isEngaged = false
        isAbandoned = false
    }

    /**
     * Feeds one measurement and returns the crop state to render with.
     *
     * A null [sample] is absence of evidence, not evidence of absence: it holds
     * the current state, so a fade to black neither engages nor reverts.
     */
    fun onSample(sample: MatteSample?, videoAspect: Float, containerAspect: Float): Boolean {
        if (isAbandoned) return false
        val crop = zoomVerticalCropFraction(videoAspect, containerAspect)
        if (crop <= 0f) {
            // Nothing to win, or the clip would be horizontal — which this does
            // not measure. Never engage, and drop a crop that no longer applies.
            clearingSamples = 0
            isEngaged = false
            return false
        }
        if (sample == null) return isEngaged

        // The thinner edge governs: the crop is only safe if BOTH edges clear it.
        val matte = minOf(sample.topFraction, sample.bottomFraction)

        if (isEngaged) {
            if (matte < crop + revertMargin) {
                isEngaged = false
                clearingSamples = 0
                flaps++
                if (flaps > maxFlaps) isAbandoned = true
            }
            return isEngaged
        }

        if (matte >= crop + engageMargin) clearingSamples++ else clearingSamples = 0
        if (clearingSamples >= samplesToEngage) isEngaged = true
        return isEngaged
    }
}
