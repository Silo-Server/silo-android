package org.siloserver.silo.common.ui.marquee

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Silo's mark colors and the first-run ink, matching the Apple apps. */
object MarqueeColors {
    val BrandBlue = Color(0xFF0034FB)
    val BrandRed = Color(0xFFF50B4F)
    val BrandOrange = Color(0xFFFD7403)
    val Ink = Color(0xFFEDEDED)
    val InkSecondary = Ink.copy(alpha = 0.62f)
    val InkTertiary = Ink.copy(alpha = 0.40f)
    val Error = Color(0xFFFF6961)
    val Live = Color(0xFF30D158)
    val Warning = Color(0xFFF4C869)
    val Hairline = Color.White.copy(alpha = 0.14f)
    val Separator = Color.White.copy(alpha = 0.08f)
    val GlassFill = Color.White.copy(alpha = 0.08f)
}

/** True when the system asks for no animation (animator duration scale 0). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Brand light plus the server or profile tint. Place it once behind the
 * first-run navigation and keep screens transparent over it.
 *
 * @param mirrored TV copy sits on the left under the darkest scrim, so the
 *   light pools on the right, behind the card.
 */
@Composable
fun MarqueeBackdrop(modifier: Modifier = Modifier, mirrored: Boolean = false) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val drifts = remember(context, reduceMotion) { !reduceMotion && !isLowRam(context) }

    // Moving forward reads as the light moving, not a crossfade.
    val stage = remember { Animatable(MarqueeScene.stage) }
    LaunchedEffect(MarqueeScene.stage) {
        stage.animateTo(MarqueeScene.stage, tween(if (reduceMotion) 150 else 1400))
    }
    val tint by animateColorAsState(
        targetValue = MarqueeScene.personalTint ?: MarqueeScene.accent ?: Color.Transparent,
        animationSpec = tween(600),
        label = "marqueeTint",
    )

    var phaseSeconds by remember { mutableFloatStateOf(0f) }
    if (drifts) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    // About 30 frames a second is plenty for light this slow.
                    if (now - last >= 33) {
                        last = now
                        phaseSeconds = (now - start) / 1000f
                    }
                }
            }
        }
    }

    val light = remember { BrandLight() }
    val grain = remember { ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated)) }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black)
            val image = light.render(stage.value, phaseSeconds)
            if (mirrored) {
                scale(scaleX = -1f, scaleY = 1f) {
                    drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
                }
            } else {
                drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            }
            if (tint.alpha > 0f) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tint.copy(alpha = tint.alpha * 0.32f), tint.copy(alpha = 0f)),
                        center = Offset(size.width * 0.5f, size.height * 0.16f),
                        radius = max(size.width, size.height) * 0.7f,
                    ),
                )
            }
            // Fine noise hides banding in near-black ramps and gives the light
            // a filmic texture.
            drawRect(grain, alpha = 0.045f)
        }
    }
}

private fun isLowRam(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

/**
 * The mark's three colors as slow pools of light over black: one cool pool
 * while choosing a server, all three once it answers, and a warm room that
 * reaches lower behind the profiles. A 3x3 color mesh (as on Apple) drawn
 * into a small bitmap and scaled up with filtering.
 */
private class BrandLight {
    private val bitmap = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
    private val image: ImageBitmap = bitmap.asImageBitmap()
    private val pixels = IntArray(W * H)

    /** The nine mesh colors for this frame, as r, g, b in 0..1. */
    private val mesh = FloatArray(27)

    fun render(stage: Float, phase: Float): ImageBitmap {
        val a = sin(phase / 13f)
        val b = cos(phase / 19f)
        val middle = staged(floatArrayOf(0.42f, 0.5f, 0.6f), stage)
        val centerX = staged(floatArrayOf(0.3f, 0.55f, 0.5f), stage)
        // Nine Color lerps a frame; the per-pixel blend below is plain floats.
        for (i in 0 until 9) {
            val color = stagedColor(i, stage)
            mesh[i * 3] = color.red
            mesh[i * 3 + 1] = color.green
            mesh[i * 3 + 2] = color.blue
        }

        // Mesh control points that move: the middle row and the middle column.
        val topX = 0.5f + 0.12f * a
        val bottomX = 0.5f - 0.1f * b
        val leftY = middle + 0.06f * b
        val rightY = middle - 0.06f * a
        val cX = centerX + 0.1f * b
        val cY = middle - 0.08f * a

        for (py in 0 until H) {
            val y = (py + 0.5f) / H
            for (px in 0 until W) {
                val x = (px + 0.5f) / W
                // Where the middle row and column cross this pixel.
                val rowY = if (x < cX) lerp(leftY, cY, x / cX) else lerp(cY, rightY, (x - cX) / (1f - cX))
                val colX = if (y < rowY) lerp(topX, cX, y / rowY) else lerp(cX, bottomX, (y - rowY) / (1f - rowY))
                val u = if (x < colX) 0.5f * x / colX else 0.5f + 0.5f * (x - colX) / (1f - colX)
                val v = if (y < rowY) 0.5f * y / rowY else 0.5f + 0.5f * (y - rowY) / (1f - rowY)
                pixels[py * W + px] = sample(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
            }
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H)
        bitmap.prepareToDraw()
        return image
    }

    /** Smoothed bilinear blend of the 3x3 mesh at (u, v) in 0..1, as opaque ARGB. */
    private fun sample(u: Float, v: Float): Int {
        val gx = (u * 2f).coerceAtMost(1.9999f)
        val gy = (v * 2f).coerceAtMost(1.9999f)
        val cx = gx.toInt()
        val cy = gy.toInt()
        val fx = smooth(gx - cx)
        val fy = smooth(gy - cy)
        val topLeft = (cy * 3 + cx) * 3
        val bottomLeft = topLeft + 9
        var argb = 0xFF shl 24
        for (channel in 0 until 3) {
            val top = lerp(mesh[topLeft + channel], mesh[topLeft + 3 + channel], fx)
            val bottom = lerp(mesh[bottomLeft + channel], mesh[bottomLeft + 3 + channel], fx)
            val value = (lerp(top, bottom, fy) * 255f + 0.5f).toInt().coerceIn(0, 255)
            argb = argb or (value shl (16 - channel * 8))
        }
        return argb
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun stagedColor(index: Int, stage: Float): Color {
        val s = stage.coerceIn(0f, 2f)
        val i = s.toInt().coerceAtMost(1)
        return lerp(PALETTES[i][index], PALETTES[i + 1][index], s - i)
    }

    private fun staged(values: FloatArray, stage: Float): Float {
        val s = stage.coerceIn(0f, 2f)
        val i = s.toInt().coerceAtMost(1)
        return lerp(values[i], values[i + 1], s - i)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        const val W = 48
        const val H = 32

        private fun Color.dimmed(amount: Float) = lerp(this, Color.Black, amount)
        private val blue = MarqueeColors.BrandBlue
        private val red = MarqueeColors.BrandRed
        private val orange = MarqueeColors.BrandOrange
        private val black = Color.Black

        /** Row-major 3x3 mesh colors per stage. */
        val PALETTES = arrayOf(
            arrayOf(
                blue.dimmed(0.46f), blue.dimmed(0.7f), red.dimmed(0.8f),
                blue.dimmed(0.8f), blue.dimmed(0.86f), red.dimmed(0.92f),
                black, black, black,
            ),
            arrayOf(
                blue.dimmed(0.5f), red.dimmed(0.58f), orange.dimmed(0.58f),
                blue.dimmed(0.76f), red.dimmed(0.74f), orange.dimmed(0.82f),
                black, black, black,
            ),
            arrayOf(
                red.dimmed(0.56f), orange.dimmed(0.52f), blue.dimmed(0.56f),
                orange.dimmed(0.7f), red.dimmed(0.66f), blue.dimmed(0.72f),
                blue.dimmed(0.9f), red.dimmed(0.9f), orange.dimmed(0.92f),
            ),
        )
    }
}

/** 96x96 premultiplied white noise, tiled over the light. */
private fun grainTile(): ImageBitmap {
    val side = 96
    val pixels = IntArray(side * side)
    var state = 0x9E3779B9.toInt()
    for (i in pixels.indices) {
        state = state * 1_664_525 + 1_013_904_223
        val value = (state ushr 24) and 0xFF
        pixels[i] = (value shl 24) or 0x00FFFFFF
    }
    return android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Darkening each screen lays over the backdrop so text always sits on at least 86% black. */
enum class MarqueeScrimStyle {
    /** Phone: content anchored to the bottom. */
    Bottom,

    /** Same, darker higher up, for screens with more controls. */
    BottomDeep,

    /** Profiles and PIN: lighter, ambient. */
    Ambient,

    /** TV: copy on the left, the card on the right. */
    Leading,
}

/**
 * @param frostStart where an extra soft darkening band begins behind the
 *   controls, as a fraction of the height; null for none.
 */
@Composable
fun MarqueeScrim(style: MarqueeScrimStyle, modifier: Modifier = Modifier, frostStart: Float? = null) {
    Canvas(modifier.fillMaxSize()) {
        when (style) {
            MarqueeScrimStyle.Bottom -> drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.62f),
                    0.15f to Color.Black.copy(alpha = 0.08f),
                    0.34f to Color.Black.copy(alpha = 0.25f),
                    0.58f to Color.Black.copy(alpha = 0.86f),
                    0.8f to Color.Black,
                ),
            )
            MarqueeScrimStyle.BottomDeep -> drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.62f),
                    0.14f to Color.Black.copy(alpha = 0.2f),
                    0.32f to Color.Black.copy(alpha = 0.55f),
                    0.5f to Color.Black.copy(alpha = 0.92f),
                    0.7f to Color.Black,
                ),
            )
            MarqueeScrimStyle.Ambient -> drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.25f to Color.Black.copy(alpha = 0.2f),
                    0.55f to Color.Black.copy(alpha = 0.75f),
                    0.78f to Color.Black,
                ),
            )
            MarqueeScrimStyle.Leading -> {
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.8f),
                        0.3f to Color.Black.copy(alpha = 0.55f),
                        0.58f to Color.Black.copy(alpha = 0.2f),
                        0.8f to Color.Black.copy(alpha = 0.1f),
                        1f to Color.Black.copy(alpha = 0.3f),
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0.65f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                )
            }
        }
        if (frostStart != null) {
            val end = (frostStart + 130.dpToFraction(size.height, density)).coerceAtMost(1f)
            drawRect(
                Brush.verticalGradient(
                    frostStart to Color.Transparent,
                    end to Color.Black.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

private fun Int.dpToFraction(height: Float, density: Float): Float = if (height <= 0f) 0f else this * density / height
