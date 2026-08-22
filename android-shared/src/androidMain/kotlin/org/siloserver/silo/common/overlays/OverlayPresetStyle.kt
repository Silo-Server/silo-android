package org.siloserver.silo.common.overlays

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.overlays.PresetId

/**
 * Compose visual recipe for a badge preset. This is the Android port of
 * Apple's `OverlayPreset` (iosApp/Overlays/OverlayPresets.swift). Each
 * preset owns shape + typography + density; per-badge variation is
 * limited to the icon (from the registry) and the accent color (from the
 * user). Stay in visual sync with the web/iOS sides.
 *
 * Color closures take the resolved user accent ([Color.Unspecified] when
 * the badge has no accent) and return the painted color. Returning
 * [Color.Unspecified] from [border] means "no border".
 */
internal data class OverlayPresetStyle(
    val id: PresetId,
    val fontSize: TextUnit,
    val fontWeight: FontWeight,
    val uppercase: Boolean,
    val letterSpacing: TextUnit,
    val horizontalPadding: androidx.compose.ui.unit.Dp,
    val verticalPadding: androidx.compose.ui.unit.Dp,
    val cornerStyle: CornerStyle,
    val iconSize: androidx.compose.ui.unit.Dp,
    val preferIcon: Boolean,
    val gap: androidx.compose.ui.unit.Dp,
    /** Adds a translucent blur scrim behind the badge fill (pill preset). */
    val backdrop: Boolean,
    val textShadow: Boolean,
    val background: (accent: Color) -> Color,
    val foreground: (accent: Color) -> Color,
    val border: (accent: Color) -> Color,
    /** The actual card-width multiplier applied to fixed renderer details. */
    val scale: Float = 1f,
) {
    sealed interface CornerStyle {
        /** Fully rounded capsule (clamped at half the height). */
        data object Capsule : CornerStyle

        data class Rounded(val radius: androidx.compose.ui.unit.Dp) : CornerStyle
    }

    fun shape(): Shape =
        when (cornerStyle) {
            is CornerStyle.Capsule -> RoundedCornerShape(percent = 50)
            is CornerStyle.Rounded -> RoundedCornerShape(cornerStyle.radius)
        }

    fun scaled(scale: Float): OverlayPresetStyle {
        val safeScale = scale.coerceAtLeast(0.1f)
        if (safeScale == 1f) return this
        return copy(
            fontSize = fontSize * safeScale,
            letterSpacing = letterSpacing * safeScale,
            horizontalPadding = horizontalPadding * safeScale,
            verticalPadding = verticalPadding * safeScale,
            cornerStyle = when (cornerStyle) {
                CornerStyle.Capsule -> CornerStyle.Capsule
                is CornerStyle.Rounded -> CornerStyle.Rounded(cornerStyle.radius * safeScale)
            },
            iconSize = iconSize * safeScale,
            gap = gap * safeScale,
            scale = safeScale,
        )
    }
}

/**
 * Linear blend matching Apple's `Color.mix(with:amount:)` and CSS
 * `color-mix(in srgb, accent X%, base Y%)`. `amount = 1` returns
 * [this]; `amount = 0` returns [other]. Alpha is blended too.
 */
private fun Color.mix(other: Color, amount: Float): Color {
    val a = amount.coerceIn(0f, 1f)
    return Color(
        red = red * a + other.red * (1 - a),
        green = green * a + other.green * (1 - a),
        blue = blue * a + other.blue * (1 - a),
        alpha = alpha * a + other.alpha * (1 - a),
    )
}

private fun Color.orNull(): Boolean = this != Color.Unspecified

internal object OverlayPresetStyles {

    fun style(id: PresetId): OverlayPresetStyle =
        when (id) {
            PresetId.Minimal -> minimal
            PresetId.Classic -> classic
            PresetId.Vibrant -> vibrant
            PresetId.Pill -> pill
            PresetId.Square -> square
        }

    private val minimal = OverlayPresetStyle(
        id = PresetId.Minimal,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        uppercase = true,
        letterSpacing = 1.2.sp,
        horizontalPadding = 4.dp,
        verticalPadding = 1.dp,
        cornerStyle = OverlayPresetStyle.CornerStyle.Rounded(2.dp),
        iconSize = 10.dp,
        preferIcon = false,
        gap = 2.dp,
        backdrop = false,
        textShadow = true,
        background = { Color.Transparent },
        foreground = { accent -> if (accent.orNull()) accent else Color.White.copy(alpha = 0.85f) },
        border = { Color.Unspecified },
    )

    private val classic = OverlayPresetStyle(
        id = PresetId.Classic,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        uppercase = true,
        letterSpacing = 0.6.sp,
        horizontalPadding = 8.dp,
        verticalPadding = 3.dp,
        cornerStyle = OverlayPresetStyle.CornerStyle.Capsule,
        iconSize = 11.dp,
        preferIcon = false,
        gap = 4.dp,
        backdrop = false,
        textShadow = false,
        background = { accent ->
            val base = Color.Black.copy(alpha = 0.6f)
            if (accent.orNull()) accent.mix(base, 0.72f) else base
        },
        foreground = { Color.White },
        border = { Color.White.copy(alpha = 0.15f) },
    )

    private val vibrant = OverlayPresetStyle(
        id = PresetId.Vibrant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        uppercase = true,
        letterSpacing = 0.6.sp,
        horizontalPadding = 8.dp,
        verticalPadding = 3.dp,
        cornerStyle = OverlayPresetStyle.CornerStyle.Rounded(6.dp),
        iconSize = 12.dp,
        preferIcon = true,
        gap = 4.dp,
        backdrop = false,
        textShadow = false,
        background = { accent -> if (accent.orNull()) accent else Color(0xFFDBDBDB) },
        foreground = { accent -> if (accent.orNull()) Color.White else Color.Black },
        border = { Color.Unspecified },
    )

    private val pill = OverlayPresetStyle(
        id = PresetId.Pill,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        uppercase = true,
        letterSpacing = 0.6.sp,
        horizontalPadding = 10.dp,
        verticalPadding = 4.dp,
        cornerStyle = OverlayPresetStyle.CornerStyle.Capsule,
        iconSize = 12.dp,
        preferIcon = true,
        gap = 4.dp,
        backdrop = true,
        textShadow = false,
        background = { accent ->
            val base = Color(red = 20 / 255f, green = 20 / 255f, blue = 30 / 255f, alpha = 0.7f)
            if (accent.orNull()) accent.mix(base, 0.8f) else base
        },
        foreground = { Color.White },
        border = { Color.White.copy(alpha = 0.15f) },
    )

    private val square = OverlayPresetStyle(
        id = PresetId.Square,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        uppercase = true,
        letterSpacing = 1.2.sp,
        horizontalPadding = 6.dp,
        verticalPadding = 2.dp,
        cornerStyle = OverlayPresetStyle.CornerStyle.Rounded(2.dp),
        iconSize = 10.dp,
        preferIcon = false,
        gap = 2.dp,
        backdrop = false,
        textShadow = false,
        background = { Color.Black.copy(alpha = 0.8f) },
        foreground = { accent -> if (accent.orNull()) accent else Color.White },
        border = { accent -> if (accent.orNull()) accent else Color.Unspecified },
    )
}
