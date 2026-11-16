package org.siloserver.silo.tv.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The root hero's diagonal tint wash, and a framed cover's glow and drop
 * shadow, drawn as one dithered bitmap (tvOS `TVHeroAmbientWash`). The wash
 * ramps a dark tint across the screen and the glow fades a cover into it, so
 * each spans only a few 8-bit levels and a GPU gradient rounds them into
 * visible bands. Here they are computed in floating point and dithered when
 * converted to 8 bits, which keeps the ramps smooth without changing their
 * tone.
 *
 * Draw it over the plain gradient wash, which shows for the moment before the
 * first bitmap is ready. A new bitmap crossfades in over [crossfadeMillis].
 */
@Composable
internal fun TvHeroAmbientWash(
    background: Color,
    tint: Color,
    stops: Array<Pair<Float, Float>>,
    cover: TvHeroCover?,
    crossfadeMillis: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var wash by remember { mutableStateOf<WashBitmap?>(null) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvas = IntSize(ceil(maxWidth.value).toInt(), ceil(maxHeight.value).toInt())
        val request = WashRequest(background, tint, stops.toList(), cover, canvas)
        LaunchedEffect(request) {
            if (canvas.width <= 0 || canvas.height <= 0) return@LaunchedEffect
            val thumbnail = cover?.let { loadGlowThumbnail(context, it.url) }
            val image = withContext(Dispatchers.Default) {
                TvHeroAmbientRenderer.render(request, thumbnail)
            } ?: return@LaunchedEffect
            wash = WashBitmap((wash?.generation ?: 0) + 1, image.asImageBitmap())
        }
        Crossfade(
            targetState = wash,
            animationSpec = tween(crossfadeMillis, easing = TvMarqueeEasing),
            label = "tvHeroAmbientWash",
        ) { value ->
            if (value != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(
                        image = value.image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        // Each bitmap pixel keeps its dithered value; filtering
                        // would blend neighbors and round them again.
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }
    }
}

/** A framed cover the wash draws a glow and shadow around, top-right on the canvas, in dp. */
internal data class TvHeroCover(
    val url: String,
    val width: Float,
    val height: Float,
    val topInset: Float,
    val trailingInset: Float,
) {
    fun frame(canvasWidth: Float): Rect {
        val right = canvasWidth - trailingInset
        return Rect(right - width, topInset, right, topInset + height)
    }
}

private data class WashRequest(
    val background: Color,
    val tint: Color,
    val stops: List<Pair<Float, Float>>,
    val cover: TvHeroCover?,
    /** The canvas in dp; the bitmap has one pixel per dp. */
    val canvas: IntSize,
)

private class WashBitmap(val generation: Int, val image: ImageBitmap)

/** The 128 px software decode the ambient tint sampler already cached. */
private suspend fun loadGlowThumbnail(context: android.content.Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val result = SingletonImageLoader.get(context).execute(
                ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(128)
                    .build(),
            )
            (result as? SuccessResult)?.image?.toBitmap()
        }.getOrNull()
    }

private object TvHeroAmbientRenderer {
    /** Glow layer resolution, in dp per pixel; it is blurred before drawing. */
    private const val GlowDpPerPixel = 2f
    private const val GlowScale = 1.12f
    /** Gaussian standard deviation of the glow, in dp (tvOS 36 pt). */
    private const val GlowSigma = 18f
    private const val GlowOpacity = 0.55f
    private const val ShadowOpacity = 0.6f
    /** Gaussian standard deviation of the shadow, in dp (tvOS 70 pt blur). */
    private const val ShadowSigma = 17.5f
    private const val ShadowOffsetY = 9f
    /** Entries in the wash's alpha lookup along its diagonal. */
    private const val AlphaSteps = 1024

    suspend fun render(request: WashRequest, thumbnail: Bitmap?): Bitmap? {
        val width = request.canvas.width
        val height = request.canvas.height
        val wash = Wash(request)
        val pixels = IntArray(width * height)
        val frame = request.cover?.frame(width.toFloat())
        // Only the cover's surroundings need float buffers for the glow and
        // shadow; everything else goes straight from the wash to 8 bits.
        val effects = frame?.let { EffectRegion.around(it, width, height) }

        for (y in 0 until height) {
            if (y % 64 == 0) coroutineContext.ensureActive()
            val row = y * width
            for (x in 0 until width) {
                if (effects != null && effects.contains(x, y)) continue
                val alpha = wash.alphaAt(x, y)
                pixels[row + x] = dithered(wash.red(alpha), wash.green(alpha), wash.blue(alpha), x, y)
            }
        }

        if (effects != null && frame != null) {
            coroutineContext.ensureActive()
            effects.fill(wash)
            if (thumbnail != null) drawGlow(thumbnail, frame, effects)
            coroutineContext.ensureActive()
            drawShadow(frame, effects)
            effects.ditherInto(pixels, width)
        }
        coroutineContext.ensureActive()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The diagonal tint, top-right to bottom-left, over the background. */
    private class Wash(request: WashRequest) {
        private val width = request.canvas.width.toFloat()
        private val height = request.canvas.height.toFloat()
        private val lengthSquared = width * width + height * height
        private val bgRed = request.background.red
        private val bgGreen = request.background.green
        private val bgBlue = request.background.blue
        private val dRed = request.tint.red - bgRed
        private val dGreen = request.tint.green - bgGreen
        private val dBlue = request.tint.blue - bgBlue
        private val alphas = FloatArray(AlphaSteps + 1) { step ->
            interpolate(request.stops, step.toFloat() / AlphaSteps)
        }

        fun alphaAt(x: Int, y: Int): Float {
            // Projection of the pixel center onto the line from (w, 0) to (0, h).
            val t = ((width - x - 0.5f) * width + (y + 0.5f) * height) / lengthSquared
            return alphas[(t.coerceIn(0f, 1f) * AlphaSteps).toInt()]
        }

        fun red(alpha: Float) = bgRed + dRed * alpha
        fun green(alpha: Float) = bgGreen + dGreen * alpha
        fun blue(alpha: Float) = bgBlue + dBlue * alpha

        private fun interpolate(stops: List<Pair<Float, Float>>, t: Float): Float {
            if (t <= stops.first().first) return stops.first().second
            for (index in 1 until stops.size) {
                val (end, endAlpha) = stops[index]
                if (t <= end) {
                    val (start, startAlpha) = stops[index - 1]
                    val fraction = if (end > start) (t - start) / (end - start) else 1f
                    return startAlpha + (endAlpha - startAlpha) * fraction
                }
            }
            return stops.last().second
        }
    }

    /** Float color buffers for the canvas pixels a cover's glow and shadow reach. */
    private class EffectRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
        val red = FloatArray(width * height)
        val green = FloatArray(width * height)
        val blue = FloatArray(width * height)

        fun contains(x: Int, y: Int) = x >= left && x < left + width && y >= top && y < top + height

        fun fill(wash: Wash) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val alpha = wash.alphaAt(left + x, top + y)
                    val i = y * width + x
                    red[i] = wash.red(alpha)
                    green[i] = wash.green(alpha)
                    blue[i] = wash.blue(alpha)
                }
            }
        }

        fun ditherInto(pixels: IntArray, canvasWidth: Int) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    pixels[(top + y) * canvasWidth + left + x] =
                        dithered(red[i], green[i], blue[i], left + x, top + y)
                }
            }
        }

        companion object {
            fun around(cover: Rect, canvasWidth: Int, canvasHeight: Int): EffectRegion? {
                val glowReach = cover.width * (GlowScale - 1) / 2 + GlowSigma * 3
                val shadowReach = ShadowSigma * 3
                val left = floor(cover.left - maxOf(glowReach, shadowReach)).toInt().coerceAtLeast(0)
                val right = ceil(cover.right + maxOf(glowReach, shadowReach)).toInt().coerceAtMost(canvasWidth)
                val top = floor(cover.top - maxOf(glowReach, shadowReach)).toInt().coerceAtLeast(0)
                val bottom = ceil(cover.bottom + maxOf(glowReach, shadowReach + ShadowOffsetY)).toInt()
                    .coerceAtMost(canvasHeight)
                if (left >= right || top >= bottom) return null
                return EffectRegion(left, top, right - left, bottom - top)
            }
        }
    }

    /** The cover scaled up 12% around its frame and blurred, composited at [GlowOpacity]. */
    private fun drawGlow(thumbnail: Bitmap, cover: Rect, region: EffectRegion) {
        val inner = cover.scaledAroundCenter(GlowScale)
        val padding = GlowSigma * 3
        val outer = inner.inflate(padding)
        val layerWidth = ceil(outer.width / GlowDpPerPixel).toInt()
        val layerHeight = ceil(outer.height / GlowDpPerPixel).toInt()
        if (layerWidth <= 0 || layerHeight <= 0) return

        val layer = Bitmap.createBitmap(layerWidth, layerHeight, Bitmap.Config.ARGB_8888)
        AndroidCanvas(layer).drawBitmap(
            thumbnail,
            null,
            RectF(
                padding / GlowDpPerPixel,
                padding / GlowDpPerPixel,
                (padding + inner.width) / GlowDpPerPixel,
                (padding + inner.height) / GlowDpPerPixel,
            ),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val pixels = IntArray(layerWidth * layerHeight)
        layer.getPixels(pixels, 0, layerWidth, 0, 0, layerWidth, layerHeight)
        layer.recycle()

        // Premultiplied channels: the opaque cover inside, clear padding
        // around it, so the blur fades the cover's colors to nothing.
        val count = layerWidth * layerHeight
        val a = FloatArray(count)
        val r = FloatArray(count)
        val g = FloatArray(count)
        val b = FloatArray(count)
        for (i in 0 until count) {
            val p = pixels[i]
            val alpha = (p ushr 24) / 255f
            a[i] = alpha
            r[i] = ((p shr 16) and 0xFF) / 255f * alpha
            g[i] = ((p shr 8) and 0xFF) / 255f * alpha
            b[i] = (p and 0xFF) / 255f * alpha
        }
        val radius = boxRadiusFor(GlowSigma / GlowDpPerPixel)
        val scratch = FloatArray(count)
        for (channel in arrayOf(a, r, g, b)) gaussianBlur(channel, scratch, layerWidth, layerHeight, radius)

        for (y in 0 until region.height) {
            val ly = (region.top + y + 0.5f - outer.top) / GlowDpPerPixel - 0.5f
            if (ly < -1 || ly > layerHeight) continue
            for (x in 0 until region.width) {
                val lx = (region.left + x + 0.5f - outer.left) / GlowDpPerPixel - 0.5f
                if (lx < -1 || lx > layerWidth) continue
                val alpha = sample(a, layerWidth, layerHeight, lx, ly) * GlowOpacity
                if (alpha <= 0f) continue
                val i = y * region.width + x
                val keep = 1 - alpha
                region.red[i] = region.red[i] * keep + sample(r, layerWidth, layerHeight, lx, ly) * GlowOpacity
                region.green[i] = region.green[i] * keep + sample(g, layerWidth, layerHeight, lx, ly) * GlowOpacity
                region.blue[i] = region.blue[i] * keep + sample(b, layerWidth, layerHeight, lx, ly) * GlowOpacity
            }
        }
    }

    /**
     * The cover's drop shadow: a blurred rectangle offset down. That blur is
     * separable, so each axis is one row of error functions. The cover frame
     * itself stays clear, so a wash that outlives its cover never shows a dark
     * tile where it was.
     */
    private fun drawShadow(cover: Rect, region: EffectRegion) {
        val shadow = cover.translate(0f, ShadowOffsetY)
        val scale = 1f / (ShadowSigma * sqrt(2f))
        val columns = FloatArray(region.width) { x ->
            val px = region.left + x + 0.5f
            0.5f * (erf((px - shadow.left) * scale) - erf((px - shadow.right) * scale))
        }
        for (y in 0 until region.height) {
            val py = region.top + y + 0.5f
            val row = 0.5f * (erf((py - shadow.top) * scale) - erf((py - shadow.bottom) * scale))
            if (row <= 0f) continue
            val insideRows = py >= cover.top && py < cover.bottom
            for (x in 0 until region.width) {
                if (insideRows) {
                    val px = region.left + x + 0.5f
                    if (px >= cover.left && px < cover.right) continue
                }
                val darken = 1 - ShadowOpacity * row * columns[x]
                val i = y * region.width + x
                region.red[i] *= darken
                region.green[i] *= darken
                region.blue[i] *= darken
            }
        }
    }

    /**
     * One opaque 8-bit sRGB pixel, ordered-dithered with interleaved gradient
     * noise: reproducible, and without the cross-hatch a Bayer matrix shows.
     */
    private fun dithered(red: Float, green: Float, blue: Float, x: Int, y: Int): Int {
        val threshold = fract(52.982918f * fract(0.06711056f * x + 0.00583715f * y))
        return (0xFF shl 24) or
            (quantize(red, threshold) shl 16) or
            (quantize(green, threshold) shl 8) or
            quantize(blue, threshold)
    }

    private fun quantize(value: Float, threshold: Float): Int =
        (value * 255f + threshold).toInt().coerceIn(0, 255)

    private fun fract(value: Float): Float = value - floor(value)

    /** Radius of the box whose three passes approximate a Gaussian of [sigma]. */
    private fun boxRadiusFor(sigma: Float): Int =
        ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)

    /** Three box blurs per axis, in place; outside the layer counts as clear. */
    private fun gaussianBlur(channel: FloatArray, scratch: FloatArray, width: Int, height: Int, radius: Int) {
        repeat(3) {
            boxBlur(channel, scratch, width, height, radius, horizontal = true)
            boxBlur(scratch, channel, width, height, radius, horizontal = false)
        }
    }

    /** A running-sum box blur of [source] into [target] along one axis. */
    private fun boxBlur(
        source: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val scale = 1f / (radius * 2 + 1)
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var sum = 0f
            for (k in 0..minOf(radius, length - 1)) sum += source[start + k * step]
            for (position in 0 until length) {
                target[start + position * step] = sum * scale
                val enter = position + radius + 1
                val leave = position - radius
                if (enter < length) sum += source[start + enter * step]
                if (leave >= 0) sum -= source[start + leave * step]
            }
        }
    }

    /** Bilinear sample at layer pixel coordinates; outside the layer is clear. */
    private fun sample(channel: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(px: Int, py: Int): Float =
            if (px in 0 until width && py in 0 until height) channel[py * width + px] else 0f
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }

    /** Abramowitz and Stegun 7.1.26; absolute error under 1.5e-7. */
    private fun erf(value: Float): Float {
        val x = abs(value)
        val t = 1f / (1f + 0.3275911f * x)
        val y = 1f - (((((1.0614054f * t - 1.4531521f) * t) + 1.4214138f) * t - 0.28449672f) * t + 0.2548296f) *
            t * exp(-x * x)
        return if (value >= 0) y else -y
    }

    /** This rect scaled by [scale] around its center. */
    private fun Rect.scaledAroundCenter(scale: Float): Rect {
        val dx = width * (scale - 1) / 2
        val dy = height * (scale - 1) / 2
        return Rect(left - dx, top - dy, right + dx, bottom + dy)
    }
}
