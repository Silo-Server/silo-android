package org.siloserver.silo.android.ui.screens.player

import kotlin.math.roundToInt

/**
 * Geometry for expanding video whose black bars are baked into the picture.
 *
 * Scope films are almost always distributed inside a 16:9 coded frame — a
 * 2.39:1 image with the matte encoded as real pixels (Blu-ray and UHD only
 * allow 16:9 frame sizes, so every scope title from disc is hard-matted).
 * Nothing in the container, the bitstream, or the server's ffprobe metadata
 * describes that inner image: `display_aspect_ratio` reports the coded 16:9, so
 * the only way to know where the picture actually starts is to look at pixels.
 *
 * Once the matte is measured the rule is simply **fit the content rect**: scale
 * the coded frame so the picture inside it exactly fills whichever axis of the
 * available box binds first, and let the residual black fall wherever the
 * aspects genuinely differ. One rule covers every case:
 *
 *  - content wider than the box (2.39 into 2.17) — width binds, a real
 *    letterbox remains top and bottom;
 *  - content narrower than the box (1.90 into 2.17) — height binds, a real
 *    pillarbox remains left and right;
 *  - content with no matte at all — the content rect *is* the coded frame, the
 *    scale is unchanged from a plain fit, and nothing moves.
 *
 * **Why this can never cut picture.** Let the coded frame be `Wc x Hc` holding
 * content `Wc x Hn` with matte `M = (Hc - Hn) / 2` per edge, fitted into a box
 * `Bw x Bh` at `s = min(Bw/Wc, Bh/Hn)`. Horizontally the frame is `Wc*s <= Bw`
 * by the definition of that minimum, so nothing is ever clipped from the sides.
 * Vertically the clip is `(Hc*s - Bh) / 2 = (Hn*s - Bh) / 2 + M*s`. When height
 * binds, `Hn*s = Bh` and the clip is exactly `M*s`; when width binds,
 * `Hn*s < Bh` and the clip is strictly less. So the clip is bounded by the
 * scaled matte in every case — the crop lands in encoded black by construction,
 * not by a threshold that has to be checked.
 *
 * [safeMatteFraction] still holds back a slice of the measured matte, because
 * the measurement itself is approximate; that is the only guard the rule needs.
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

/** Rows in a sampled frame. Sets the resolution of every measurement here. */
internal const val MATTE_SAMPLE_ROWS = 144

/** A channel value at or below this counts as black. PQ and SDR both encode a
 *  true matte at ~0; the headroom absorbs codec ringing at the matte edge. */
internal const val MATTE_BLACK_CHANNEL_MAX = 20

/** Beyond this much black the frame is a fade or a night scene, not evidence. */
private const val MAX_CREDIBLE_BLACK_FRACTION = 0.6f

/**
 * Share of the measured matte left uncropped as confidence headroom.
 *
 * Proportional rather than a flat fraction of frame height, because the rule
 * this guards is proportional. A flat 2% of coded height is a rounding error
 * against a scope film's 12.9% matte but eats two thirds of the 3.3% matte on a
 * 1.90:1 title — which would have declined to expand exactly the content that
 * most wants it.
 */
internal const val MATTE_MARGIN_FRACTION = 0.15f

/**
 * Floor for that headroom, in fractions of coded height. A sampled row covers
 * `Hc / 144` of the frame, so the matte edge can only be located to about one
 * row; holding back one and a half of them keeps quantisation on the safe side
 * of the picture even when the proportional share is smaller.
 */
internal const val MATTE_MARGIN_FLOOR = 1.5f / MATTE_SAMPLE_ROWS

/** Consecutive usable frames required before any expansion is applied. */
internal const val MATTE_SAMPLES_TO_SETTLE = 4

/**
 * Measures the black bars in a sampled frame laid out as [width] x [height]
 * ARGB pixels.
 *
 * A row counts as black only when its BRIGHTEST pixel is black, so a caption or
 * a studio logo sitting in the bar keeps that row out of the matte. Returns
 * null when the frame is too black to carry evidence — a fade must not read as
 * a very wide matte, and null neither expands nor contracts.
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

/** The part of a measured matte that may be cropped, after headroom. */
internal fun safeMatteFraction(measured: Float): Float {
    if (measured <= 0f) return 0f
    val margin = maxOf(measured * MATTE_MARGIN_FRACTION, MATTE_MARGIN_FLOOR)
    return (measured - margin).coerceAtLeast(0f)
}

/**
 * Aspect of the content rect once [matteFraction] is discounted from each edge.
 * Falls back to the coded aspect for a matte that is absent or not credible, so
 * the caller renders an ordinary fit.
 */
internal fun contentAspect(codedAspect: Float, matteFraction: Float): Float {
    if (codedAspect <= 0f || matteFraction <= 0f) return codedAspect
    val contentHeight = 1f - 2f * matteFraction
    if (contentHeight <= 0f) return codedAspect
    return codedAspect / contentHeight
}

/**
 * Fraction of coded height clipped from each edge when a content rect of
 * [contentAspect] is fitted into a box of [boxAspect]. Exists to make the
 * safety proof in the file header executable — it must never exceed the matte
 * that produced [contentAspect].
 */
internal fun verticalClipFraction(
    codedAspect: Float,
    contentAspect: Float,
    boxAspect: Float,
): Float {
    if (codedAspect <= 0f || contentAspect <= 0f || boxAspect <= 0f) return 0f
    // Coded height is one unit, so the coded frame is `codedAspect` wide and the
    // content rect is `codedAspect / contentAspect` tall.
    val contentHeight = codedAspect / contentAspect
    if (contentHeight <= 0f) return 0f
    // Box height is one unit too, so `s` is the scale that fits the content rect.
    val s = minOf(boxAspect / codedAspect, 1f / contentHeight)
    if (s <= 1f) return 0f
    return (s - 1f) / (2f * s)
}

/** On-screen size of the picture itself, with the encoded matte discounted. */
internal data class ExpandedImageSize(val width: Int, val height: Int)

/**
 * Size the picture is drawn at when a content rect of [contentAspect] is fitted
 * into a [boxWidth] x [boxHeight] box, with [trueContentAspect] the aspect the
 * image really has (the fitted rect keeps a sliver of matte as headroom).
 */
internal fun expandedImageSize(
    boxWidth: Int,
    boxHeight: Int,
    contentAspect: Float,
    trueContentAspect: Float = contentAspect,
): ExpandedImageSize? {
    if (boxWidth <= 0 || boxHeight <= 0) return null
    if (contentAspect <= 0f || trueContentAspect <= 0f) return null
    val boxAspect = boxWidth.toFloat() / boxHeight
    // The fitted rect, then the true picture inside it at the same scale.
    val fittedWidth = if (contentAspect >= boxAspect) {
        boxWidth.toFloat()
    } else {
        boxHeight * contentAspect
    }
    val width = fittedWidth.roundToInt().coerceAtMost(boxWidth)
    val height = (fittedWidth / trueContentAspect).roundToInt().coerceAtMost(boxHeight)
    return ExpandedImageSize(width = width, height = height)
}

/**
 * Symmetric horizontal inset that keeps an expanded picture clear of a display
 * cutout, given the cutout insets the platform reports for the CURRENT rotation.
 *
 * Applied symmetrically, which costs twice the cutout width, and that is a
 * deliberate trade. A punch-hole sits on one edge only, so insetting just that
 * edge would buy back the other half — on the reference device 2981px of image
 * instead of 2842px, about 10% more area. It would also leave the picture flush
 * against one bezel with a black stripe down the other, and that stripe reads as
 * a rendering fault rather than a decision. Worse, the two landscape rotations
 * put the cutout on opposite edges (ROTATION_90 left, ROTATION_270 right), so a
 * single-edge inset makes the image jump sideways by the full inset when the
 * phone is flipped end for end. A centred image costs a little width and stays
 * put, which is the right default for something you sit and watch.
 *
 * Insets only horizontally: the player is landscape by default, where the
 * cutout is on a side edge and the picture reaches the sides. In portrait the
 * cutout is on the top edge and the video is nowhere near it, so the reported
 * top inset is deliberately ignored rather than pushing the picture down.
 */
internal fun cutoutSafeHorizontalInset(cutoutLeftPx: Int, cutoutRightPx: Int): Int =
    maxOf(cutoutLeftPx, cutoutRightPx, 0)

/**
 * Tracks the encoded matte across frames and reports the content rect to fit.
 *
 * The estimate is the **thinnest** matte any usable frame has shown, which is
 * what makes this stable in both directions at once. A dark scene reads as more
 * black and cannot widen the crop; a frame whose picture reaches the matte edge
 * — an IMAX sequence opening up, an ad break, a burned-in subtitle — narrows it
 * on the very next sample and it stays narrowed. A monotonically decreasing
 * estimate cannot oscillate, so instant revert and the old latch-off fall out of
 * the same property instead of needing separate thresholds.
 *
 * Nothing is applied until [MATTE_SAMPLES_TO_SETTLE] usable frames agree, so a
 * single fluke frame cannot resize the picture.
 */
internal class LetterboxFillEstimator(
    private val samplesToSettle: Int = MATTE_SAMPLES_TO_SETTLE,
) {
    private var usableSamples = 0
    private var seededMatte: Float? = null

    /**
     * Thinnest matte seen this session, or null before there has been a usable
     * frame. Only live frames land here — never [seed] — so a remembered value
     * is replaced by measurement rather than copied forward for ever.
     */
    var observedMatte: Float? = null
        private set

    /** True once live frames alone are enough to decide with. */
    val isSettled: Boolean
        get() = usableSamples >= samplesToSettle

    /** Forgets all evidence — a new media mount. */
    fun reset() {
        usableSamples = 0
        seededMatte = null
        observedMatte = null
    }

    /**
     * Starts from a matte measured during an earlier play of this exact file, so
     * a rewatch or a resume is already expanded on its first frame instead of
     * visibly growing a moment later.
     *
     * This is remembered evidence rather than a guess, and it is trusted only
     * until live frames replace it: once [MATTE_SAMPLES_TO_SETTLE] have arrived
     * the seed is ignored entirely, so a stale entry corrects itself within a
     * few hundred milliseconds instead of governing the whole session.
     */
    fun seed(matteFraction: Float) {
        if (matteFraction > 0f) seededMatte = matteFraction
    }

    /**
     * Feeds one frame and returns the content aspect to render at.
     *
     * A null [sample] is absence of evidence, not evidence of absence: it holds
     * the current estimate, so a fade to black neither expands nor contracts.
     */
    fun onSample(sample: MatteSample?, codedAspect: Float): Float {
        if (sample != null) {
            // The thinner edge governs: cropping is only safe to the extent
            // BOTH edges are black.
            val matte = minOf(sample.topFraction, sample.bottomFraction)
            observedMatte = observedMatte?.let { minOf(it, matte) } ?: matte
            usableSamples++
        }
        return contentAspectFor(codedAspect)
    }

    /** Content aspect implied by the evidence so far. */
    fun contentAspectFor(codedAspect: Float): Float {
        val matte = if (isSettled) observedMatte else seededMatte
        return contentAspect(codedAspect, safeMatteFraction(matte ?: 0f))
    }
}
