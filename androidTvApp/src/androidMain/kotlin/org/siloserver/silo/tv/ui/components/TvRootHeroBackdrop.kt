package org.siloserver.silo.tv.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.SectionItem
import kotlin.math.floor

/**
 * Page-level Skyline backdrop for tvOS-style root screens. Faithful Android
 * port of the reworked tvOS `TVRootHeroBackdrop` (§5.4): crisp (un-blurred)
 * artwork anchored in the top-RIGHT corner at ~0.64w × 0.70h, faded out toward
 * the leading edge and the bottom by a two-axis corner mask into a color
 * sampled from the art itself, which is carried (dimmed) diagonally
 * topEnd → bottomStart across the page so the metadata and rows sit on the same
 * tint. The shell owns the single shared top-navigation shadow so this artwork
 * does not double-darken Home relative to other root pages.
 *
 * Driven by whichever row card holds focus — pass that item's marquee
 * [content]; when null the page renders flat on the app background.
 */
@Composable
fun TvRootHeroBackdrop(
    content: TvMarqueeContent?,
    modifier: Modifier = Modifier,
    emptyWashColor: Color? = null,
    animateTransition: Boolean = true,
) {
    val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
    val tintState = LocalAmbientBackdropTint.current
    val ambientAccent = tintState.accent

    // tvOS first-frame parity: the FIRST artwork/tint to arrive snaps in with
    // no animation — a cold entry paints the finished hero instead of fading
    // it up from the black background. Only content-to-content swaps (normal
    // D-pad browsing) keep the ambient crossfade.
    var hasDisplayedArtwork by remember { mutableStateOf(false) }
    val snapInitialArtwork = content != null && !hasDisplayedArtwork
    LaunchedEffect(content != null) {
        if (content != null) hasDisplayedArtwork = true
    }
    var hasDisplayedTint by remember { mutableStateOf(false) }
    val snapInitialTint = ambientAccent != null && !hasDisplayedTint
    LaunchedEffect(ambientAccent != null) {
        if (ambientAccent != null) hasDisplayedTint = true
    }

    val targetAccent = ambientAccent ?: emptyWashColor ?: MaterialTheme.colorScheme.background
    // Kept as State and read only inside the Canvas draw lambda below: reading
    // the animating colour here would recompose this whole backdrop (and its
    // Crossfade subtree) on every frame of the 500ms tint tween.
    val animatedTint = animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(
            durationMillis = if (snapInitialTint) 0 else TvMarqueeCrossfadeMs,
            easing = TvMarqueeEasing,
        ),
        label = "tvRootHeroBackdropTint",
    )

    val isVisible = content != null
    val hasTintOnlyWash = !isVisible && ambientAccent != null
    val hasEmptyWash = !isVisible && ambientAccent == null && emptyWashColor != null
    val leadingWashAlpha = when {
        isVisible -> 1.0f
        hasTintOnlyWash -> 0.34f
        hasEmptyWash -> 0.30f
        else -> 0.0f
    }
    val midWashAlpha = when {
        isVisible -> 0.5f
        hasTintOnlyWash -> 0.18f
        hasEmptyWash -> 0.15f
        else -> 0.0f
    }
    val trailingWashAlpha = when {
        isVisible -> 0.18f
        hasTintOnlyWash -> 0.08f
        hasEmptyWash -> 0.07f
        else -> 0.0f
    }

    val background = MaterialTheme.colorScheme.background
    // Read only while drawing, so the 500ms tint tween redraws the wash
    // without recomposing the backdrop.
    val washStops = {
        smoothedWashStops(
            tint = if (animateTransition) animatedTint.value else targetAccent,
            background = background,
            leadingAlpha = leadingWashAlpha,
            midAlpha = midWashAlpha,
            trailingAlpha = trailingWashAlpha,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        // Diagonal sampled-tint wash: richest in the top-right behind the art,
        // carried dimmed to the bottom-left (tvOS stops 1.0 / 0.5 / 0.18).
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                ShaderBrush(
                    washShader(
                        stops = washStops(),
                        from = Offset(size.width, 0f),
                        to = Offset(0f, size.height),
                    ),
                ),
            )
        }

        if (animateTransition) {
            Crossfade(
                targetState = content,
                animationSpec = tween(
                    if (snapInitialArtwork) 0 else TvMarqueeCrossfadeMs,
                    easing = TvMarqueeEasing,
                ),
                label = "tvRootHeroBackdropArt",
            ) { value ->
                val art = when {
                    value == null -> null
                    // Protection turning on for the item still shown hides
                    // its still at once instead of fading the sharp one out.
                    content != null && value.id == content.id ->
                        if (content.hidesStills && !value.hidesStills) content else value
                    // A departing item follows the current preferences too.
                    else -> value.underPrefs(spoilerPrefs)
                }
                if (art?.heroBackdropUrl != null) {
                    CornerAnchoredArt(
                        url = art.heroBackdropUrl,
                        thumbhash = art.heroBackdropThumbhash,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        } else if (content?.heroBackdropUrl != null) {
            CornerAnchoredArt(
                url = content.heroBackdropUrl,
                thumbhash = content.heroBackdropThumbhash,
            )
        }

        // Once over every art slot, so a crossfade blends the raw art as
        // before and an incoming slot whose image hasn't loaded doesn't
        // paint wash over the outgoing one. Pages that never show art
        // (Calendar) skip it.
        if (content != null || hasDisplayedArtwork) {
            CornerFade(washStops = washStops)
        }

        // From Android 9 the wash is dithered in its shader; this white grain
        // would only lift an OLED's black.
        if ((hasTintOnlyWash || hasEmptyWash) && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            DitheredWashOverlay(
                alpha = if (hasEmptyWash) EmptyWashDitherAlpha else TintOnlyDitherAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Crisp art block pinned to the top-right corner, drawn whole. [CornerFade]
 * fades it into the wash toward the leading edge and the bottom.
 */
@Composable
private fun CornerAnchoredArt(
    url: String?,
    thumbhash: String?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val artWidth = maxWidth * ArtWidthFraction
        val artHeight = maxHeight * ArtHeightFraction

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(modifier = Modifier.size(artWidth, artHeight)) {
                ThumbhashImage(
                    url = url,
                    thumbhash = thumbhash,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    // The Skyline feed preloads this frame and owns the one
                    // shared art + copy crossfade. A second Coil fade would
                    // make the image visibly lag behind the text.
                    crossfadeMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Fades the corner art into the wash by painting the page's own wash over the
 * art box through a corner-fade mask. Each pixel comes out as
 * art × fade + wash × (1 − fade), worked out in one draw and rounded once.
 *
 * Masking the art in an offscreen layer instead (two DstIn ramps) rounded
 * art × fade to 8 bits with no dithering, since the GPU dithers colour but
 * never alpha. On a dark backdrop that drew contour lines across the whole
 * fade, beside the title logo.
 */
@Composable
private fun CornerFade(washStops: () -> Array<WashStop>) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        val pageWidth = constraints.maxWidth.toFloat()
        val pageHeight = constraints.maxHeight.toFloat()
        Box(
            modifier = Modifier
                .size(maxWidth * ArtWidthFraction, maxHeight * ArtHeightFraction)
                .drawWithCache {
                    // This box sits in the page's top-right corner, so the
                    // page's top-left is here.
                    val pageOrigin = Offset(size.width - pageWidth, 0f)
                    val mask: android.graphics.Shader
                    val blend: PorterDuff.Mode
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // How much of the art shows: a horizontal ramp times a
                        // vertical one. Two-stop gradients are worked out
                        // exactly on every GPU; the wash covers the rest.
                        mask = ComposeShader(
                            LinearGradientShader(
                                from = Offset.Zero,
                                to = Offset(size.width * ArtOpaqueFromX, 0f),
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                            LinearGradientShader(
                                from = Offset(0f, size.height * ArtFadesFromY),
                                to = Offset(0f, size.height),
                                colors = listOf(Color.Black, Color.Transparent),
                            ),
                            PorterDuff.Mode.MULTIPLY,
                        )
                        blend = PorterDuff.Mode.DST_OUT
                    } else {
                        // Below Android 9 a shader can't compose a composed
                        // one, so the coverage comes from a bitmap.
                        mask = smoothBitmapShader(CornerFadeMask).apply {
                            // First and last samples land on the box's edges.
                            setLocalMatrix(
                                Matrix().apply {
                                    setScale(
                                        size.width / (CornerFadeMask.width - 1),
                                        size.height / (CornerFadeMask.height - 1),
                                    )
                                    preTranslate(-0.5f, -0.5f)
                                },
                            )
                        }
                        blend = PorterDuff.Mode.DST_IN
                    }
                    onDrawBehind {
                        // Read per draw so the tint tween animates it; the
                        // ramp is the one the page wash just built.
                        val wash = washShader(
                            stops = washStops(),
                            from = pageOrigin + Offset(pageWidth, 0f),
                            to = pageOrigin + Offset(0f, pageHeight),
                        )
                        drawRect(ShaderBrush(ComposeShader(wash, mask, blend)))
                    }
                },
        )
    }
}

/**
 * The wash's diagonal ramp from [from] to [to]. The wash spreads a dark tint
 * across the whole screen in few 8-bit levels, so it bands unless something
 * dithers it. Android 14 dithers gradients itself; Android 9 doesn't, and it
 * also interpolates multi-stop gradients through an 8-bit colour table, which
 * rounds them before any dither could help.
 *
 * So on Android 9 and later the ramp is worked out here ([washRamp]) and drawn
 * from two 8-bit strips the GPU interpolates in floating point: whole levels,
 * and the fraction of a level scaled up by 255. The fraction and
 * [WashDitherNoise] are scaled back down in the shader and added before the
 * result is rounded to 8 bits, so every level is dithered. 8-bit strips sample
 * the same on every GPU and need no colour conversion; half-float textures
 * don't (Android 9 reads them as linear light, and GLES 2 TVs may not filter
 * them at all).
 *
 * Where the GPU dithers too (Android 14), the two add up to about one level of
 * grain. The noise is anchored at [from], the page's top-right corner for both
 * callers, so the page and the art's fade, which paint the same wash, get the
 * same pixels. A flat wash (no tint yet, or none at all) gets no noise, which
 * would lift an OLED's black.
 */
private fun washShader(
    stops: Array<WashStop>,
    from: Offset,
    to: Offset,
): Shader {
    val flat = stops.all { it.sameColourAs(stops.first()) }
    // The art's fade masks this shader, and composing composed shaders needs
    // the Android 9 renderer.
    if (flat || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return LinearGradientShader(
            from = from,
            to = to,
            colors = stops.map { it.color },
            colorStops = stops.map { it.position },
        )
    }
    // Texel i's centre lands at i / (size - 1) of the way from [from] to [to];
    // the strips' one row runs across the ramp.
    val along = (to - from) * (1f / (WashRampSize - 1))
    val rampMatrix = Matrix().apply {
        setValues(
            floatArrayOf(
                along.x, -(to.y - from.y), from.x - along.x * 0.5f,
                along.y, to.x - from.x, from.y - along.y * 0.5f,
                0f, 0f, 1f,
            ),
        )
    }
    val ramp = washRamp(stops)
    val whole = smoothBitmapShader(ramp.whole).apply { setLocalMatrix(rampMatrix) }
    val fraction = smoothBitmapShader(ramp.fraction).apply { setLocalMatrix(rampMatrix) }
    val noise = ImageShader(WashDitherNoise, TileMode.Repeated, TileMode.Repeated).apply {
        setLocalMatrix(Matrix().apply { setTranslate(from.x, from.y) })
    }
    val oneLevel = LinearGradientShader(
        from = Offset.Zero,
        to = Offset(1f, 0f),
        colors = listOf(OneLevelGrey, OneLevelGrey),
    )
    val belowOneLevel = ComposeShader(
        ComposeShader(fraction, oneLevel, PorterDuff.Mode.MULTIPLY),
        ComposeShader(noise, oneLevel, PorterDuff.Mode.MULTIPLY),
        PorterDuff.Mode.ADD,
    )
    return ComposeShader(whole, belowOneLevel, PorterDuff.Mode.ADD)
}

/** The wash's ramp, split for [washShader]: whole 8-bit levels and the fraction of a level × 255. */
private class WashRamp(val stops: Array<WashStop>, val whole: ImageBitmap, val fraction: ImageBitmap)

/**
 * The wash's stops interpolated the way a gradient interpolates them (between
 * gamma-encoded colours), in floating point, half a level low: the noise adds
 * 0 to 1 level, so the dithered result averages to the wash's own tone.
 */
private fun washRamp(stops: Array<WashStop>): WashRamp {
    // The page and the art's fade draw the same stops in the same frame.
    cachedWashRamp?.let { if (it.stops.contentEquals(stops)) return it }
    val whole = IntArray(WashRampSize)
    val fraction = IntArray(WashRampSize)
    var segment = 0
    for (i in 0 until WashRampSize) {
        val t = i / (WashRampSize - 1f)
        while (segment < stops.size - 2 && t > stops[segment + 1].position) segment++
        val start = stops[segment]
        val end = stops[segment + 1]
        val f = ((t - start.position) / (end.position - start.position)).coerceIn(0f, 1f)
        fun level(startChannel: Float, endChannel: Float): Float =
            ((startChannel + (endChannel - startChannel) * f) * 255f - 0.5f).coerceAtLeast(0f)
        val r = level(start.red, end.red)
        val g = level(start.green, end.green)
        val b = level(start.blue, end.blue)
        whole[i] = opaqueGrey(floor(r), floor(g), floor(b))
        fraction[i] = opaqueGrey((r - floor(r)) * 255f, (g - floor(g)) * 255f, (b - floor(b)) * 255f)
    }
    return WashRamp(stops, opaqueStrip(whole), opaqueStrip(fraction)).also { cachedWashRamp = it }
}

// The last ramp built. Used on the main thread only.
private var cachedWashRamp: WashRamp? = null

// About one texel per pixel along a 1080p diagonal, so a GPU that filters
// 8-bit textures coarsely can't widen a whole-level step past a pixel.
private const val WashRampSize = 2048

/** One 8-bit level, opaque: multiplies a strip's 0..1 down to 0..1 level. */
private val OneLevelGrey = Color(red = 1f / 255f, green = 1f / 255f, blue = 1f / 255f)

/**
 * Noise from 0 up to just under one level, stored × 255 like the ramp's
 * fraction (0..254, so it never adds a whole level). Interleaved gradient
 * noise, so neighbouring pixels differ evenly instead of clumping.
 */
private val WashDitherNoise: ImageBitmap by lazy {
    val dimension = 64
    val pixels = IntArray(dimension * dimension) { index ->
        val x = index % dimension
        val y = index / dimension
        val n = (fract(52.982918f * fract(0.06711056f * x + 0.00583715f * y)) * 255f).toInt().toFloat()
        opaqueGrey(n, n, n)
    }
    Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun opaqueGrey(red: Float, green: Float, blue: Float): Int =
    (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)

private fun channel(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

private fun opaqueStrip(pixels: IntArray): ImageBitmap =
    Bitmap.createBitmap(pixels, pixels.size, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * A clamped bitmap shader sampled bilinearly where Android lets us ask. From
 * Android 12 a bitmap shader inside a ComposeShader doesn't take filtering
 * from the paint and samples the nearest pixel; Android 13 added the setting
 * that asks for bilinear. On 12 the wash strips, about one texel per pixel,
 * stay within a pixel of the ramp anyway.
 */
private fun smoothBitmapShader(image: ImageBitmap): BitmapShader {
    val clamp = android.graphics.Shader.TileMode.CLAMP
    return BitmapShader(image.asAndroidBitmap(), clamp, clamp).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
    }
}

private fun fract(value: Float): Float = value - floor(value)

/**
 * Backward-compatible entry point for surfaces that still drive the backdrop
 * from a raw [SectionItem] (the library Browse landing, Sweep 3). Adapts the
 * item into the Skyline [TvMarqueeContent] payload and renders the corner-
 * anchored treatment.
 */
@Composable
fun TvRootHeroBackdrop(
    item: SectionItem?,
    modifier: Modifier = Modifier,
) {
    val spoilers = LocalEpisodeSpoilerPrefs.current
    // Keyed on the whole item: a watch-state change re-decides spoiler hiding.
    val content = remember(item, spoilers) {
        item?.let { TvMarqueeContent.from(it, rowTitle = "", spoilers = spoilers) }
    }
    TvRootHeroBackdrop(content = content, modifier = modifier)
}

private const val ArtWidthFraction = 0.64f
private const val ArtHeightFraction = 0.70f
private const val TintOnlyDitherAlpha = 0.28f
private const val EmptyWashDitherAlpha = 0.34f

/**
 * One stop of the wash: an opaque gamma-encoded colour, in floating point. A
 * Compose [Color] would round each channel to 8 bits, and the dark wash's
 * stops sit only a few levels apart.
 */
private data class WashStop(val position: Float, val red: Float, val green: Float, val blue: Float) {
    /** The stop rounded to 8 bits, for a plain gradient. */
    val color: Color get() = Color(red, green, blue)

    fun sameColourAs(other: WashStop): Boolean =
        red == other.red && green == other.green && blue == other.blue
}

/**
 * The wash's stops as opaque colours: the tint at each stop's strength over
 * [background], blended in floating point. Opaque, so the art's fade can
 * paint the same wash over the art.
 */
private fun smoothedWashStops(
    tint: Color,
    background: Color,
    leadingAlpha: Float,
    midAlpha: Float,
    trailingAlpha: Float,
): Array<WashStop> = arrayOf(
    0.00f to leadingAlpha,
    0.12f to lerpAlpha(leadingAlpha, midAlpha, 0.18f),
    0.26f to lerpAlpha(leadingAlpha, midAlpha, 0.48f),
    0.45f to midAlpha,
    0.62f to lerpAlpha(midAlpha, trailingAlpha, 0.36f),
    0.80f to lerpAlpha(midAlpha, trailingAlpha, 0.68f),
    1.00f to trailingAlpha,
).map { (position, alpha) ->
    WashStop(
        position = position,
        red = tint.red * alpha + background.red * (1f - alpha),
        green = tint.green * alpha + background.green * (1f - alpha),
        blue = tint.blue * alpha + background.blue * (1f - alpha),
    )
}.toTypedArray()

/** The art turns fully opaque this far across its box from the leading edge. */
private const val ArtOpaqueFromX = 0.68f

/** The art starts fading out this far down its box. */
private const val ArtFadesFromY = 0.58f

/**
 * For [CornerFade] below Android 9: how much of the wash covers the art at
 * each point of its box, none at the top-right and all of it at the leading
 * edge and the bottom. Drawn bilinearly scaled, which follows the two ramps'
 * product closely between samples. Samples run edge to edge.
 */
private val CornerFadeMask: ImageBitmap by lazy {
    val dimension = 256
    val pixels = IntArray(dimension * dimension)
    for (y in 0 until dimension) {
        val v = y / (dimension - 1f)
        val vertical = if (v <= ArtFadesFromY) 1f else (1f - v) / (1f - ArtFadesFromY)
        for (x in 0 until dimension) {
            val u = x / (dimension - 1f)
            val horizontal = (u / ArtOpaqueFromX).coerceAtMost(1f)
            val washCover = 1f - horizontal * vertical
            pixels[y * dimension + x] = (washCover * 255f + 0.5f).toInt() shl 24
        }
    }
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, dimension, 0, 0, dimension, dimension) }
        .asImageBitmap()
}

private fun lerpAlpha(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

@Composable
private fun DitheredWashOverlay(
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val noise = remember { createBackdropNoiseBitmap() }
    Canvas(modifier = modifier) {
        val tileWidth = noise.width.toFloat()
        val tileHeight = noise.height.toFloat()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawImage(
                    image = noise,
                    topLeft = Offset(x, y),
                    alpha = alpha,
                )
                x += tileWidth
            }
            y += tileHeight
        }
    }
}

private fun createBackdropNoiseBitmap(): ImageBitmap {
    val dimension = 96
    val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(dimension * dimension)
    var state = 0x4F1BBCDC

    for (index in pixels.indices) {
        state = state * 1664525 + 1013904223
        val sample = state ushr 24
        val pixelAlpha = when {
            sample < 16 -> 14
            sample < 42 -> 8
            else -> 0
        }
        pixels[index] = (pixelAlpha shl 24) or 0x00FFFFFF
    }

    bitmap.setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
    return bitmap.asImageBitmap()
}
