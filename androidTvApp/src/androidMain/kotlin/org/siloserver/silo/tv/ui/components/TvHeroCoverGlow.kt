package org.siloserver.silo.tv.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A soft glow of the cover behind it (tvOS `TVHeroAmbientWash`'s cover glow):
 * the cover scaled up 12% and blurred, drawn at 55%. `Modifier.blur` does
 * nothing below API 31, so the blur runs on a small software bitmap made from
 * the 128 px decode the ambient tint sampler already requests. [aspect] is the
 * cover's width over height.
 */
internal fun Modifier.coverGlow(url: String, aspect: Float): Modifier = composed {
    val context = LocalContext.current
    val glow by produceState<ImageBitmap?>(initialValue = null, url, aspect) {
        value = withContext(Dispatchers.Default) { blurredCover(context, url, aspect) }
    }
    drawBehind {
        val image = glow ?: return@drawBehind
        // Screen pixels per layer pixel, from the cover's height.
        val unit = size.height / LayerCoverHeight
        val width = image.width * unit
        val height = image.height * unit
        drawImage(
            image = image,
            // The layer is centered on the cover, so its blur reaches past
            // every edge equally.
            dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            alpha = GlowOpacity,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The cover's height in layer pixels; the glow is soft, so a small layer is enough. */
private const val LayerCoverHeight = 64f
private const val GlowScale = 1.12f
/** tvOS blurs a 380 pt cover's glow with a 36 pt standard deviation. */
private const val GlowSigmaPerCoverHeight = 36f / 380f
private const val GlowOpacity = 0.55f

private suspend fun blurredCover(context: Context, url: String, aspect: Float): ImageBitmap? {
    // A failed load comes back as an error result; cancellation propagates.
    val result = SingletonImageLoader.get(context).execute(
        ImageRequest.Builder(context).data(url).allowHardware(false).size(128).build(),
    )
    val thumbnail = (result as? SuccessResult)?.image?.toBitmap() ?: return null

    val sigma = GlowSigmaPerCoverHeight * LayerCoverHeight
    val padding = ceil(sigma * 3).toInt()
    val innerHeight = LayerCoverHeight * GlowScale
    val innerWidth = innerHeight * aspect
    // The blur reaches three standard deviations past the scaled cover.
    val width = ceil(innerWidth).toInt() + padding * 2
    val height = ceil(innerHeight).toInt() + padding * 2
    val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(layer).drawBitmap(
        thumbnail,
        centerCrop(thumbnail.width, thumbnail.height, aspect),
        RectF(
            (width - innerWidth) / 2,
            (height - innerHeight) / 2,
            (width + innerWidth) / 2,
            (height + innerHeight) / 2,
        ),
        Paint(Paint.FILTER_BITMAP_FLAG),
    )
    val pixels = IntArray(width * height)
    layer.getPixels(pixels, 0, width, 0, 0, width, height)
    blurArgb(pixels, width, height, boxRadiusFor(sigma))
    layer.setPixels(pixels, 0, width, 0, 0, width, height)
    return layer.asImageBitmap()
}

/** The source rect that fills [aspect] the way the cover's center crop does. */
private fun centerCrop(sourceWidth: Int, sourceHeight: Int, aspect: Float): Rect {
    val sourceAspect = sourceWidth.toFloat() / sourceHeight
    return if (sourceAspect > aspect) {
        val cropWidth = (sourceHeight * aspect).roundToInt()
        val left = (sourceWidth - cropWidth) / 2
        Rect(left, 0, left + cropWidth, sourceHeight)
    } else {
        val cropHeight = (sourceWidth / aspect).roundToInt()
        val top = (sourceHeight - cropHeight) / 2
        Rect(0, top, sourceWidth, top + cropHeight)
    }
}

/** Radius of the box whose three passes approximate a Gaussian of [sigma]. */
private fun boxRadiusFor(sigma: Float): Int =
    ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)

/**
 * Approximates a Gaussian blur of premultiplied ARGB [pixels] in place with
 * three box blurs per axis. Outside the layer counts as clear.
 */
private fun blurArgb(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val channels = Array(4) { FloatArray(pixels.size) }
    for (i in pixels.indices) {
        val p = pixels[i]
        channels[0][i] = (p ushr 24).toFloat()
        channels[1][i] = ((p shr 16) and 0xFF).toFloat()
        channels[2][i] = ((p shr 8) and 0xFF).toFloat()
        channels[3][i] = (p and 0xFF).toFloat()
    }
    // Bitmap pixels are unpremultiplied; blur premultiplied so the clear
    // padding fades the cover's colors instead of darkening them.
    for (i in pixels.indices) {
        val alpha = channels[0][i] / 255f
        for (c in 1..3) channels[c][i] *= alpha
    }
    val scratch = FloatArray(pixels.size)
    for (channel in channels) {
        repeat(3) {
            boxBlur(channel, scratch, width, height, radius, horizontal = true)
            boxBlur(scratch, channel, width, height, radius, horizontal = false)
        }
    }
    for (i in pixels.indices) {
        val alpha = channels[0][i]
        val unpremultiply = if (alpha > 0f) 255f / alpha else 0f
        fun byte(value: Float) = value.roundToInt().coerceIn(0, 255)
        pixels[i] = (byte(alpha) shl 24) or
            (byte(channels[1][i] * unpremultiply) shl 16) or
            (byte(channels[2][i] * unpremultiply) shl 8) or
            byte(channels[3][i] * unpremultiply)
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
