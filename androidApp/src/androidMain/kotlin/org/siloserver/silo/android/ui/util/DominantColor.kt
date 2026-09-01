package org.siloserver.silo.android.ui.util

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide cache of dominant colors keyed by image URL. Palette
 * extraction is expensive enough that re-running it as the user scrolls
 * back to a previously-seen hero would jank — and the result is purely a
 * function of the URL, so a simple in-memory map is the right shape.
 */
private val dominantColorCache = mutableMapOf<String, Color>()

private const val CrossfadeDurationMs = 300

/**
 * Resolve the artwork's average tint and crossfade to it over 300ms. When a
 * [thumbhash] is available its average-colour coefficients resolve locally on
 * the opening frame; otherwise [imageUrl] is sampled as a fallback. Once a URL
 * has been resolved, later recompositions hit the in-memory cache immediately.
 *
 * Mirrors iOS `HeroBackdropPalette`: average the small decoded image, then
 * normalize its luminance to 0.22. Avoiding a "most vibrant" swatch matters
 * here — it was turning a small red/orange detail in the artwork into a flat,
 * saturated page colour that did not resemble Apple's faded opaque wash.
 */
@Composable
fun rememberDominantColor(
    imageUrl: String?,
    fallback: Color,
    thumbhash: String? = null,
): State<Color> {
    val context = LocalContext.current
    val instantThumbhashTint = remember(thumbhash) { averageTintFromThumbhash(thumbhash) }
    var sampled by remember(imageUrl, instantThumbhashTint, fallback) {
        mutableStateOf(
            imageUrl?.let { dominantColorCache[it] }
                ?: instantThumbhashTint
                ?: fallback,
        )
    }

    LaunchedEffect(imageUrl, instantThumbhashTint) {
        if (imageUrl.isNullOrBlank()) {
            sampled = instantThumbhashTint ?: fallback
            return@LaunchedEffect
        }
        dominantColorCache[imageUrl]?.let {
            sampled = it
            return@LaunchedEffect
        }
        // The payload already carries a ThumbHash generated from this same
        // artwork. Its DC coefficients are the average tint, so use them on
        // the opening frame instead of starting a second backdrop download
        // beside the full-size image request.
        instantThumbhashTint?.let {
            dominantColorCache[imageUrl] = it
            sampled = it
            return@LaunchedEffect
        }
        try {
            val bitmap = withContext(Dispatchers.IO) {
                val loader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(100)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val raw = result.image.toBitmap()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        raw.config == Bitmap.Config.HARDWARE
                    ) {
                        raw.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        raw
                    }
                } else {
                    null
                }
            } ?: return@LaunchedEffect

            val color = withContext(Dispatchers.Default) {
                try {
                    sampleAverageTint(bitmap)
                } finally {
                    bitmap.recycle()
                }
            } ?: return@LaunchedEffect

            dominantColorCache[imageUrl] = color
            sampled = color
        } catch (_: Exception) {
            // Leave fallback in place — gradient just won't be tinted.
        }
    }

    return animateColorAsState(
        targetValue = sampled,
        animationSpec = tween(CrossfadeDurationMs),
        label = "dominantColor",
    )
}

private fun sampleAverageTint(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var totalWeight = 0.0
    pixels.forEach { pixel ->
        val alpha = ((pixel ushr 24) and 0xFF) / 255.0
        if (alpha <= 0.0) return@forEach
        red += ((pixel ushr 16) and 0xFF) * alpha
        green += ((pixel ushr 8) and 0xFF) * alpha
        blue += (pixel and 0xFF) * alpha
        totalWeight += alpha
    }
    if (totalWeight <= 0.0) return null

    return normalizeAverageTint(
        red = red / totalWeight / 255.0,
        green = green / totalWeight / 255.0,
        blue = blue / totalWeight / 255.0,
    )
}

/** Decode ThumbHash's average-colour coefficients without expanding its AC image. */
internal fun averageTintFromThumbhash(thumbhash: String?): Color? {
    val value = thumbhash?.takeIf { it.isNotBlank() } ?: return null
    val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
    if (bytes.size < 3) return null

    fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF
    val header24 = byteAt(0) or (byteAt(1) shl 8) or (byteAt(2) shl 16)
    val luminanceDc = (header24 and 63) / 63.0
    val pDc = ((header24 shr 6) and 63) / 31.5 - 1.0
    val qDc = ((header24 shr 12) and 63) / 31.5 - 1.0
    val blue = luminanceDc - 2.0 / 3.0 * pDc
    val red = (3.0 * luminanceDc - blue + qDc) / 2.0
    val green = red - qDc
    return normalizeAverageTint(red, green, blue)
}

private fun normalizeAverageTint(red: Double, green: Double, blue: Double): Color {
    val r = red.coerceIn(0.0, 1.0)
    val g = green.coerceIn(0.0, 1.0)
    val b = blue.coerceIn(0.0, 1.0)
    val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val targetLuminance = 0.22
    val scale = when {
        luminance <= 0.001 -> 0.0
        luminance > targetLuminance -> targetLuminance / luminance
        else -> maxOf(1.0, targetLuminance / maxOf(luminance, 0.05))
    }
    return Color(
        red = (r * scale).coerceIn(0.0, 1.0).toFloat(),
        green = (g * scale).coerceIn(0.0, 1.0).toFloat(),
        blue = (b * scale).coerceIn(0.0, 1.0).toFloat(),
        alpha = 1f,
    )
}
