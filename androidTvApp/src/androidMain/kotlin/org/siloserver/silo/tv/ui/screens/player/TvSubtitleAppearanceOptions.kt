package org.siloserver.silo.tv.ui.screens.player

import android.content.res.Resources
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.pointSize

/**
 * Canonical subtitle-appearance option sets + labels shared by the player HUD
 * Subtitles pane ([TvPlayerHud]) and the Settings → Subtitles Appearance block
 * ([org.siloserver.silo.tv.ui.screens.settings.TvSettingsScreen]). The HUD and
 * Settings must offer identical choices, so both lift these from here rather
 * than defining their own. Hex palettes match the silo-apple
 * `SubtitleAppearance.fontColors` / `backgroundColors` / `outlineColors`.
 * Labels are string resource ids; the functions taking [Resources] resolve
 * them, since some pickers build their rows outside composition.
 */
object TvSubtitleAppearanceOptions {
    data class PreviewDecoration(val outline: Boolean, val shadow: Boolean)
    private val FONT_SIZES: List<Pair<SubtitleFontSizePreset, Int>> = listOf(
        SubtitleFontSizePreset.Small to CommonR.string.player_subtitles_size_small,
        SubtitleFontSizePreset.Medium to CommonR.string.player_subtitles_size_medium,
        SubtitleFontSizePreset.Large to CommonR.string.player_subtitles_size_large,
        SubtitleFontSizePreset.XLarge to CommonR.string.player_subtitles_size_xLarge,
        SubtitleFontSizePreset.XXLarge to CommonR.string.player_subtitles_size_xxLarge,
    )

    private val FONT_FAMILIES: List<Pair<String, Int>> = listOf(
        SubtitleAppearance.SANS_SERIF to CommonR.string.player_subtitles_font_sansSerif,
        SubtitleAppearance.SERIF to CommonR.string.player_subtitles_font_serif,
        SubtitleAppearance.MONOSPACE to CommonR.string.player_subtitles_font_monospace,
    )

    private val BACKGROUND_STYLES: List<Pair<SubtitleBackgroundStylePreset, Int>> = listOf(
        SubtitleBackgroundStylePreset.None to CommonR.string.player_subtitles_background_noBackground,
        SubtitleBackgroundStylePreset.Box to CommonR.string.player_subtitles_background_box,
        SubtitleBackgroundStylePreset.Shadow to CommonR.string.player_subtitles_background_dropShadow,
        SubtitleBackgroundStylePreset.Outline to CommonR.string.player_subtitles_background_outline,
    )

    private val POSITIONS: List<Pair<SubtitlePositionPreset, Int>> = listOf(
        SubtitlePositionPreset.Bottom to CommonR.string.player_subtitles_position_bottom,
        SubtitlePositionPreset.LowerThird to CommonR.string.player_subtitles_position_lowerThirdTitleCase,
        SubtitlePositionPreset.Top to CommonR.string.player_subtitles_position_top,
    )

    fun fontSizes(resources: Resources): List<Pair<SubtitleFontSizePreset, String>> = FONT_SIZES.resolve(resources)
    fun fontFamilies(resources: Resources): List<Pair<String, String>> = FONT_FAMILIES.resolve(resources)
    fun backgroundStyles(resources: Resources): List<Pair<SubtitleBackgroundStylePreset, String>> =
        BACKGROUND_STYLES.resolve(resources)
    fun positions(resources: Resources): List<Pair<SubtitlePositionPreset, String>> = POSITIONS.resolve(resources)
    fun fontColors(resources: Resources): List<Pair<String, String>> = FONT_COLORS.resolve(resources)
    fun backgroundColors(resources: Resources): List<Pair<String, String>> = BACKGROUND_COLORS.resolve(resources)
    fun outlineColors(resources: Resources): List<Pair<String, String>> = OUTLINE_COLORS.resolve(resources)

    private fun <T> List<Pair<T, Int>>.resolve(resources: Resources): List<Pair<T, String>> =
        map { (value, label) -> value to resources.getString(label) }

    /** Background-opacity percentage steps for the HUD quick overlay (coarse). */
    val OPACITY_STEPS: List<Int> = listOf(0, 25, 50, 75, 100)

    /** Text-opacity percentage steps for the HUD quick overlay (coarse). Starts
     *  at 25: 1% is the schema floor but reads as no subtitles at all. Matches
     *  silo-apple's tvOS HUD. */
    val TEXT_OPACITY_STEPS: List<Int> = listOf(25, 50, 75, 100)

    /** Fine-grained opacity steps for the Settings Appearance block — 0–100 by
     *  5, matching silo-apple `TVSettingsOptions.backgroundOpacity`. */
    val OPACITY_PERCENT_STEPS: List<Int> = (0..100 step 5).toList()

    /** Fine-grained text-opacity steps for the Settings Appearance block — same
     *  5-point cadence as [OPACITY_PERCENT_STEPS], but starting at 5 rather than
     *  0 since fully transparent text has no legible affordance to pick it back
     *  up from. Matches silo-apple `TVSettingsOptions.textOpacity`. */
    val TEXT_OPACITY_PERCENT_STEPS: List<Int> = (5..100 step 5).toList()

    /**
     * A step list plus the currently saved value, so a value set elsewhere
     * (the phone's free-typed percent field) that doesn't fall on this
     * picker's cadence is still a selectable option. Without this, opening
     * the picker focuses the first step and pressing Select silently
     * overwrites the real value with it.
     */
    fun percentOptions(steps: List<Int>, current: Int): List<Int> =
        (steps + current).toSortedSet().toList()

    /** Font / outline color palette: (hex, label). Matches Apple `fontColors`. */
    private val FONT_COLORS: List<Pair<String, Int>> = listOf(
        "#ffffff" to CommonR.string.player_subtitles_color_white,
        "#facc15" to CommonR.string.player_subtitles_color_yellow,
        "#22c55e" to CommonR.string.player_subtitles_color_green,
        "#06b6d4" to CommonR.string.player_subtitles_color_cyan,
        "#d946ef" to CommonR.string.player_subtitles_color_magenta,
        "#ef4444" to CommonR.string.player_subtitles_color_red,
        "#3b82f6" to CommonR.string.player_subtitles_color_blue,
        "#9ca3af" to CommonR.string.player_subtitles_color_gray,
        "#000000" to CommonR.string.player_subtitles_color_black,
    )

    /** Background / outline color palette: (hex, label). Matches Apple `backgroundColors`. */
    private val BACKGROUND_COLORS: List<Pair<String, Int>> = listOf(
        "#000000" to CommonR.string.player_subtitles_color_black,
        "#374151" to CommonR.string.player_subtitles_color_darkGrayTitleCase,
        "#1e3a5f" to CommonR.string.player_subtitles_color_navy,
        "#7f1d1d" to CommonR.string.player_subtitles_color_darkRedTitleCase,
        "#14532d" to CommonR.string.player_subtitles_color_darkGreenTitleCase,
    )

    /** Outline color palette: matches silo-apple `SubtitleAppearance.outlineColors`,
     *  which is defined as `= backgroundColors` (the 5 dark tones), NOT the font
     *  palette. Both the HUD and Settings outline pickers must use this. */
    private val OUTLINE_COLORS: List<Pair<String, Int>> = BACKGROUND_COLORS

    /** Hex-only swatch lists used by the HUD's inline color swatch rows. */
    val TEXT_COLOR_SWATCHES: List<String> = FONT_COLORS.map { it.first }
    val BACKGROUND_COLOR_SWATCHES: List<String> = BACKGROUND_COLORS.map { it.first }
    val OUTLINE_COLOR_SWATCHES: List<String> = OUTLINE_COLORS.map { it.first }

    fun fontSizeLabel(value: SubtitleFontSizePreset, resources: Resources): String =
        FONT_SIZES.labelFor(resources) { it == value } ?: value.name

    fun fontFamilyLabel(value: String, resources: Resources): String =
        FONT_FAMILIES.labelFor(resources) { it == value } ?: value

    fun backgroundStyleLabel(value: SubtitleBackgroundStylePreset, resources: Resources): String =
        BACKGROUND_STYLES.labelFor(resources) { it == value } ?: value.name

    fun positionLabel(value: SubtitlePositionPreset, resources: Resources): String =
        POSITIONS.labelFor(resources) { it == value } ?: value.name

    fun fontColorLabel(hex: String, resources: Resources): String =
        FONT_COLORS.labelFor(resources) { it.equals(hex, ignoreCase = true) } ?: hex

    fun backgroundColorLabel(hex: String, resources: Resources): String =
        BACKGROUND_COLORS.labelFor(resources) { it.equals(hex, ignoreCase = true) } ?: hex

    fun outlineColorLabel(hex: String, resources: Resources): String =
        OUTLINE_COLORS.labelFor(resources) { it.equals(hex, ignoreCase = true) } ?: hex

    private inline fun <T> List<Pair<T, Int>>.labelFor(resources: Resources, matches: (T) -> Boolean): String? =
        firstOrNull { matches(it.first) }?.let { resources.getString(it.second) }

    fun previewFontSizeSp(value: SubtitleFontSizePreset): Float =
        (value.pointSize * 0.36).toFloat()

    fun previewFontFamily(value: String): FontFamily = when (value.lowercase()) {
        SubtitleAppearance.SERIF -> FontFamily.Serif
        SubtitleAppearance.MONOSPACE -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }

    /**
     * Converts a stored opacity percent (0-100 for background, 1-100 for
     * text) into the alpha channel the TV preview composables paint with.
     * Shared by both the Settings and player HUD previews so the two never
     * drift apart on rounding or clamping.
     */
    fun previewOpacityAlpha(percent: Int, floor: Int): Float = percent.coerceIn(floor, 100) / 100f

    fun previewAlignment(value: SubtitlePositionPreset): Alignment = when (value) {
        SubtitlePositionPreset.Top -> Alignment.TopCenter
        SubtitlePositionPreset.LowerThird -> Alignment.Center
        SubtitlePositionPreset.Bottom -> Alignment.BottomCenter
    }

    fun previewDecoration(appearance: SubtitleAppearance): PreviewDecoration = PreviewDecoration(
        outline = appearance.backgroundStyle != SubtitleBackgroundStylePreset.Shadow &&
            (appearance.textOutline || appearance.backgroundStyle == SubtitleBackgroundStylePreset.Outline),
        shadow = appearance.backgroundStyle == SubtitleBackgroundStylePreset.Shadow,
    )
}
