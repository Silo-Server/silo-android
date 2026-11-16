package org.siloserver.silo.common.audiobook

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Colors sampled from an audiobook cover for the player's ambient backdrop,
 * ported from Apple's `AudioCoverPalette`. The four quadrant averages tint
 * the backdrop's corners, the whole-cover average its middle, and the most
 * vivid quadrant becomes the [accent] for the play button, scrubber and
 * current-chapter line.
 */
@Immutable
data class AudioCoverPalette(
    /** Quadrant averages: top-start, top-end, bottom-start, bottom-end. */
    val corners: List<Color>,
    val center: Color,
    val accent: Color,
) {
    companion object {
        /** Near-black with a faint cool cast, shown until the cover is sampled or when it has none. */
        fun fallback(accent: Color) = AudioCoverPalette(
            corners = listOf(
                Color(0.10f, 0.11f, 0.15f),
                Color(0.07f, 0.08f, 0.12f),
                Color(0.06f, 0.06f, 0.10f),
                Color(0.09f, 0.09f, 0.13f),
            ),
            center = Color(0.07f, 0.08f, 0.11f),
            accent = accent,
        )
    }
}

private val paletteCache = ConcurrentHashMap<String, AudioCoverPalette>()

private const val PaletteCrossfadeMs = 1200

/**
 * The cover's palette, crossfading from [fallbackAccent]'s neutral palette
 * once the 64 px sample resolves. Results are cached per URL, so reopening
 * the player for the same book draws its colors on the first frame.
 */
@Composable
fun rememberAudioCoverPalette(coverUrl: String?, fallbackAccent: Color): AudioCoverPalette {
    val context = LocalContext.current
    val fallback = remember(fallbackAccent) { AudioCoverPalette.fallback(fallbackAccent) }
    var target by remember(coverUrl, fallback) {
        mutableStateOf(coverUrl?.let { paletteCache[it] } ?: fallback)
    }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrBlank()) return@LaunchedEffect
        paletteCache[coverUrl]?.let {
            target = it
            return@LaunchedEffect
        }
        val sampled = runCatching {
            val bitmap = withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(64)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request) as? SuccessResult
                result?.image?.toBitmap()?.let { raw ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && raw.config == Bitmap.Config.HARDWARE) {
                        raw.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        raw
                    }
                }
            }
            bitmap?.let { withContext(Dispatchers.Default) { samplePalette(it, fallbackAccent) } }
        }.getOrNull() ?: return@LaunchedEffect
        paletteCache[coverUrl] = sampled
        target = sampled
    }
    return animatePalette(target)
}

@Composable
private fun animatePalette(target: AudioCoverPalette): AudioCoverPalette {
    val spec = tween<Color>(PaletteCrossfadeMs)
    val c0 by animateColorAsState(target.corners[0], spec, label = "audioPaletteCorner0")
    val c1 by animateColorAsState(target.corners[1], spec, label = "audioPaletteCorner1")
    val c2 by animateColorAsState(target.corners[2], spec, label = "audioPaletteCorner2")
    val c3 by animateColorAsState(target.corners[3], spec, label = "audioPaletteCorner3")
    val center by animateColorAsState(target.center, spec, label = "audioPaletteCenter")
    val accent by animateColorAsState(target.accent, spec, label = "audioPaletteAccent")
    return AudioCoverPalette(listOf(c0, c1, c2, c3), center, accent)
}

private fun samplePalette(bitmap: Bitmap, fallbackAccent: Color): AudioCoverPalette? {
    val width = bitmap.width
    val height = bitmap.height
    if (width < 2 || height < 2) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val halfW = width / 2
    val halfH = height / 2
    val quadrants = listOf(
        average(pixels, width, 0, 0, halfW, halfH),
        average(pixels, width, halfW, 0, width, halfH),
        average(pixels, width, 0, halfH, halfW, height),
        average(pixels, width, halfW, halfH, width, height),
    )
    if (quadrants.any { it == null }) return null
    val corners = quadrants.filterNotNull()
    val whole = average(pixels, width, 0, 0, width, height) ?: return null
    val accentSource = corners.maxByOrNull { it.vividness } ?: whole
    return AudioCoverPalette(
        corners = corners.map { it.dimmedForBackdrop().color },
        center = whole.dimmedForBackdrop().color,
        accent = accentSource.boostedForAccent()?.color ?: fallbackAccent,
    )
}

private fun average(pixels: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Rgb? {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var weight = 0.0
    for (y in y0 until y1) {
        for (x in x0 until x1) {
            val pixel = pixels[y * stride + x]
            val alpha = ((pixel ushr 24) and 0xFF) / 255.0
            if (alpha <= 0.0) continue
            r += ((pixel ushr 16) and 0xFF) / 255.0 * alpha
            g += ((pixel ushr 8) and 0xFF) / 255.0 * alpha
            b += (pixel and 0xFF) / 255.0 * alpha
            weight += alpha
        }
    }
    if (weight <= 0.0) return null
    return Rgb(r / weight, g / weight, b / weight)
}

private data class Rgb(val r: Double, val g: Double, val b: Double) {
    val color: Color get() = Color(r.toFloat(), g.toFloat(), b.toFloat())

    private val luminance: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b

    private val maxChannel: Double get() = maxOf(r, g, b)

    private val saturation: Double
        get() {
            val max = maxChannel
            return if (max <= 0.0) 0.0 else (max - minOf(r, g, b)) / max
        }

    /** Saturation weighted by brightness, so neither dark mud nor washed-out highlights win. */
    val vividness: Double get() = saturation * (0.35 + 0.65 * maxChannel)

    /** Pulled toward the near-black page, with a floor so dark covers still read as tinted. */
    fun dimmedForBackdrop(): Rgb {
        val target = 0.16
        val lum = luminance
        if (lum <= 0.001) return Rgb(0.05, 0.05, 0.07)
        val scale = if (lum > target) target / lum else minOf(1.6, target / maxOf(lum, 0.04))
        return Rgb(minOf(1.0, r * scale), minOf(1.0, g * scale), minOf(1.0, b * scale))
    }

    /**
     * Lifted into a control tint bright enough for dark glyphs on top. A
     * near-grey sample (a black-and-white cover) keeps a light neutral
     * instead of inventing a hue; null only for a black sample.
     */
    fun boostedForAccent(): Rgb? {
        val max = maxChannel
        if (max <= 0.001) return null
        if (saturation <= 0.12) return Rgb(0.93, 0.93, 0.93)
        val scale = 0.92 / max
        val lift = 0.18
        fun lifted(channel: Double): Double {
            val boosted = minOf(1.0, channel * scale)
            return boosted + (1 - boosted) * lift
        }
        return Rgb(lifted(r), lifted(g), lifted(b))
    }
}
