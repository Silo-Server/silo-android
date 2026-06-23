package com.continuum.app.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 * Per-screen Aurora composition — a Compose port of silo-apple's
 * `AuroraBackdrop` (`DesignSystem/Aurora/AuroraBackdrop.swift`). Each first-run
 * screen gets its own variant (where the light band sits, how bright) so the
 * flow feels related but never identical, inside one plum-night palette.
 *
 * The SwiftUI original layers the warm bloom + ribbons with `.screen` and a
 * heavy `.blur(72)`. We can't lean on that: `Modifier.blur` is a RenderEffect
 * that no-ops below API 31 (e.g. the Nvidia Shield on Android 11), which turns
 * hard-edged ribbon capsules into a sharp diagonal stripe. So instead we paint
 * the bands as wide, vertically-squashed radial glows that are *inherently*
 * soft — additive [BlendMode.Plus] over the dark night reads as flowing aurora
 * on every API level, no blur required.
 */
data class TvAuroraVariant(
    val rotationDegrees: Float,
    val centerY: Float,
    val intensity: Float,
    val ribbonIntensity: Float = 1f,
) {
    companion object {
        val Welcome = TvAuroraVariant(-12f, 0.24f, 0.92f)
        val Server = TvAuroraVariant(-9f, 0.32f, 0.62f)
        val SignIn = TvAuroraVariant(-14f, 0.22f, 0.90f)
        val Profile = TvAuroraVariant(-10f, 0.27f, 0.66f, ribbonIntensity = 0f)
    }
}

enum class TvAuroraScrim { Left, Soft, None }

private val NightTop = Color(0xFF1C1329)
private val NightMid = Color(0xFF0D0A17)
private val NightBottom = Color(0xFF070509)

@Composable
fun TvAuroraBackdrop(
    variant: TvAuroraVariant = TvAuroraVariant.SignIn,
    scrim: TvAuroraScrim = TvAuroraScrim.Soft,
    modifier: Modifier = Modifier,
) {
    // Deterministic starfield (so it doesn't reshuffle each recomposition).
    val stars = remember {
        val rng = Random(0x5110)
        List(90) { Triple(rng.nextFloat(), rng.nextFloat() * 0.62f, 0.25f + rng.nextFloat() * 0.55f) }
    }

    Box(modifier = modifier.fillMaxSize().background(NightBottom)) {
        // Glow layer: night gradient + additive bloom + additive aurora glows.
        // Everything here is a smooth gradient, so it stays soft without any
        // RenderEffect blur (which the Shield's Android 11 ignores).
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            // Base night gradient.
            drawRect(Brush.verticalGradient(listOf(NightTop, NightMid, NightBottom)), size = size)

            // Warm key-light bloom (additive).
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFDCA6).copy(alpha = 0.50f * variant.intensity),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.7f, h * (variant.centerY - 0.03f)),
                    radius = max(w, h) * 0.55f,
                ),
                size = size,
                blendMode = BlendMode.Plus,
            )

            // Aurora band: a row of wide, vertically-squashed radial glows in
            // the warm→pink→violet→teal palette, rotated as a group. They
            // overlap into one flowing ribbon of light with no hard edges.
            // Profile selection keeps the bloom but suppresses the band.
            if (variant.ribbonIntensity > 0f) {
                val cy = h * variant.centerY
                val ribbonAlpha = variant.intensity * variant.ribbonIntensity
                val glowColors = listOf(
                    Color(0xFFFFD9A4),
                    Color(0xFFFF90A8),
                    Color(0xFFC490FF),
                    Color(0xFF9B8BFF),
                    Color(0xFF8FE7CF),
                )
                val glowRadius = max(w, h) * 0.34f
                rotate(degrees = variant.rotationDegrees, pivot = Offset(w / 2f, cy)) {
                    glowColors.forEachIndexed { i, c ->
                        val t = i / (glowColors.size - 1f)
                        val gx = w * (0.12f + 0.76f * t)
                        val gy = cy + (if (i % 2 == 0) -1f else 1f) * h * 0.035f
                        val a = ribbonAlpha * if (i == glowColors.lastIndex) 0.34f else 0.46f
                        auroraGlow(Offset(gx, gy), glowRadius, c, a, verticalSquash = 0.24f)
                    }
                }
            }
        }

        // Starfield (sharp, top band).
        Canvas(Modifier.matchParentSize()) {
            stars.forEach { (fx, fy, a) ->
                drawCircle(
                    color = Color.White.copy(alpha = a * 0.65f),
                    radius = 1.5f,
                    center = Offset(fx * size.width, fy * size.height),
                )
            }
        }

        // Vignette (gentle — the glow needs to survive it).
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.height * 0.95f,
                ),
                size = size,
            )
        }

        // Scrim so a centered glass card / left hero text pops.
        when (scrim) {
            TvAuroraScrim.Soft -> Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, NightBottom.copy(alpha = 0.72f)),
                    ),
                ),
            )
            TvAuroraScrim.Left -> Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0.0f to NightBottom.copy(alpha = 0.9f),
                        0.3f to NightBottom.copy(alpha = 0.55f),
                        0.72f to Color.Transparent,
                    ),
                ),
            )
            TvAuroraScrim.None -> Unit
        }

        // Top scrim keeps the wordmark + step chrome legible over the light.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.22f)
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(listOf(NightBottom.copy(alpha = 0.66f), Color.Transparent)),
                ),
        )
    }
}

/**
 * One soft aurora glow — a radial gradient squashed vertically into a wide,
 * flat ellipse so a row of them overlaps into a flowing band of light. Radial
 * gradients fade smoothly to transparent, so the result is soft on every API
 * level without relying on [androidx.compose.ui.draw.blur].
 */
private fun DrawScope.auroraGlow(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    verticalSquash: Float,
) {
    withTransform({ scale(scaleX = 1f, scaleY = verticalSquash, pivot = center) }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            blendMode = BlendMode.Plus,
        )
    }
}
