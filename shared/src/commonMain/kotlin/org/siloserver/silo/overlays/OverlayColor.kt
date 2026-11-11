package org.siloserver.silo.overlays

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A straight-alpha sRGB colour, channels 0–1. The web presets are written as
 * CSS colour expressions (`color-mix`, contrast checks); this does the same
 * maths so a badge is the same colour on every client. The Compose layer maps
 * it to `Color(red, green, blue, alpha)` without further conversion.
 */
data class OverlayColor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double = 1.0,
) {
    /** This colour with [alpha] replaced, as CSS `rgb(r g b / alpha)` writes it. */
    fun withAlpha(alpha: Double): OverlayColor = copy(alpha = alpha)

    /**
     * CSS `color-mix(in srgb, this share, other)`: [share] (0–1) of this colour
     * and the rest of [other]. As CSS Color 5 defines it, the channels are
     * interpolated premultiplied by alpha and the result un-premultiplied; the
     * two percentages sum to 100%, so no alpha multiplier applies.
     */
    fun mix(share: Double, other: OverlayColor): OverlayColor {
        val rest = 1.0 - share
        val mixedAlpha = alpha * share + other.alpha * rest
        if (mixedAlpha <= 0.0) return Transparent
        fun channel(mine: Double, theirs: Double) = (mine * alpha * share + theirs * other.alpha * rest) / mixedAlpha
        return OverlayColor(
            red = channel(red, other.red),
            green = channel(green, other.green),
            blue = channel(blue, other.blue),
            alpha = mixedAlpha,
        )
    }

    /** WCAG 2 relative luminance, ignoring alpha (web `luminanceOf`). */
    val luminance: Double
        get() = 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)

    /** WCAG contrast ratio against [other], from 1 to 21 (web `contrastRatio`). */
    fun contrast(other: OverlayColor): Double {
        val a = luminance
        val b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * Black or white, whichever reads better on this colour as a fill (web
     * `readableTextOn`): black above a luminance of 0.4. Used for the plate
     * preset's rating source chips and for text on a solid style fill.
     */
    val readableText: OverlayColor
        get() = if (luminance > 0.4) Black else White

    companion object {
        val White = OverlayColor(1.0, 1.0, 1.0)
        val Black = OverlayColor(0.0, 0.0, 0.0)
        val Transparent = OverlayColor(0.0, 0.0, 0.0, 0.0)

        /** Channels as CSS writes them, 0–255, with alpha 0–1: `rgba(r, g, b, alpha)`. */
        fun rgb(red: Int, green: Int, blue: Int, alpha: Double = 1.0): OverlayColor =
            OverlayColor(red / 255.0, green / 255.0, blue / 255.0, alpha)

        /**
         * `#RRGGBB` or `#RRGGBBAA` with the alpha LAST, as CSS and the
         * card-overlays contract write it; `null` for anything else. (Android's
         * `0xAARRGGBB` convention puts alpha first; this does not.)
         */
        fun parse(hex: String?): OverlayColor? {
            if (hex == null || !hex.startsWith("#")) return null
            val digits = hex.substring(1)
            if (digits.length != 6 && digits.length != 8) return null
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            fun byte(index: Int) = digits.substring(index, index + 2).toInt(16)
            return OverlayColor(
                red = byte(0) / 255.0,
                green = byte(2) / 255.0,
                blue = byte(4) / 255.0,
                alpha = if (digits.length == 8) byte(6) / 255.0 else 1.0,
            )
        }

        private fun linear(channel: Double): Double =
            if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
}
