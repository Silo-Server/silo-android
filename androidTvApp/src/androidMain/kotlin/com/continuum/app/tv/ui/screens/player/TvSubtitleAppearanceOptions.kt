package com.continuum.app.tv.ui.screens.player

import com.continuum.app.model.settings.SubtitleAppearance
import com.continuum.app.model.settings.SubtitleBackgroundStylePreset
import com.continuum.app.model.settings.SubtitleFontSizePreset
import com.continuum.app.model.settings.SubtitlePositionPreset

/**
 * Canonical subtitle-appearance option sets + labels shared by the player HUD
 * Subtitles pane ([TvPlayerHud]) and the Settings → Subtitles Appearance block
 * ([com.continuum.app.tv.ui.screens.settings.TvSettingsScreen]). The HUD and
 * Settings must offer identical choices, so both lift these from here rather
 * than defining their own. Hex palettes match the silo-apple
 * `SubtitleAppearance.fontColors` / `backgroundColors` / `outlineColors`.
 */
object TvSubtitleAppearanceOptions {
    val FONT_SIZES: List<Pair<SubtitleFontSizePreset, String>> = listOf(
        SubtitleFontSizePreset.Small to "Small",
        SubtitleFontSizePreset.Medium to "Medium",
        SubtitleFontSizePreset.Large to "Large",
        SubtitleFontSizePreset.XLarge to "X-Large",
        SubtitleFontSizePreset.XXLarge to "XX-Large",
    )

    val FONT_FAMILIES: List<Pair<String, String>> = listOf(
        SubtitleAppearance.SANS_SERIF to "Sans-serif",
        SubtitleAppearance.SERIF to "Serif",
        SubtitleAppearance.MONOSPACE to "Monospace",
    )

    val BACKGROUND_STYLES: List<Pair<SubtitleBackgroundStylePreset, String>> = listOf(
        SubtitleBackgroundStylePreset.None to "No background",
        SubtitleBackgroundStylePreset.Box to "Box",
        SubtitleBackgroundStylePreset.Shadow to "Drop Shadow",
        SubtitleBackgroundStylePreset.Outline to "Outline",
    )

    val POSITIONS: List<Pair<SubtitlePositionPreset, String>> = listOf(
        SubtitlePositionPreset.Bottom to "Bottom",
        SubtitlePositionPreset.LowerThird to "Lower Third",
        SubtitlePositionPreset.Top to "Top",
    )

    /** Background-opacity percentage steps for the HUD quick overlay (coarse). */
    val OPACITY_STEPS: List<Int> = listOf(0, 25, 50, 75, 100)

    /** Fine-grained opacity steps for the Settings Appearance block — 0–100 by
     *  5, matching silo-apple `TVSettingsOptions.backgroundOpacity`. */
    val OPACITY_PERCENT_STEPS: List<Int> = (0..100 step 5).toList()

    /** Font / outline color palette: (hex, label). Matches Apple `fontColors`. */
    val FONT_COLORS: List<Pair<String, String>> = listOf(
        "#ffffff" to "White",
        "#facc15" to "Yellow",
        "#22c55e" to "Green",
        "#06b6d4" to "Cyan",
        "#d946ef" to "Magenta",
        "#ef4444" to "Red",
        "#3b82f6" to "Blue",
        "#000000" to "Black",
    )

    /** Background / outline color palette: (hex, label). Matches Apple `backgroundColors`. */
    val BACKGROUND_COLORS: List<Pair<String, String>> = listOf(
        "#000000" to "Black",
        "#374151" to "Dark Gray",
        "#1e3a5f" to "Navy",
        "#7f1d1d" to "Dark Red",
        "#14532d" to "Dark Green",
    )

    /** Outline color palette: matches silo-apple `SubtitleAppearance.outlineColors`,
     *  which is defined as `= backgroundColors` (the 5 dark tones), NOT the font
     *  palette. Both the HUD and Settings outline pickers must use this. */
    val OUTLINE_COLORS: List<Pair<String, String>> = BACKGROUND_COLORS

    /** Hex-only swatch lists used by the HUD's inline color swatch rows. */
    val TEXT_COLOR_SWATCHES: List<String> = FONT_COLORS.map { it.first }
    val BACKGROUND_COLOR_SWATCHES: List<String> = BACKGROUND_COLORS.map { it.first }
    val OUTLINE_COLOR_SWATCHES: List<String> = OUTLINE_COLORS.map { it.first }

    fun fontSizeLabel(value: SubtitleFontSizePreset): String =
        FONT_SIZES.firstOrNull { it.first == value }?.second ?: value.name

    fun fontFamilyLabel(value: String): String =
        FONT_FAMILIES.firstOrNull { it.first == value }?.second ?: value

    fun backgroundStyleLabel(value: SubtitleBackgroundStylePreset): String =
        BACKGROUND_STYLES.firstOrNull { it.first == value }?.second ?: value.name

    fun positionLabel(value: SubtitlePositionPreset): String =
        POSITIONS.firstOrNull { it.first == value }?.second ?: value.name

    fun fontColorLabel(hex: String): String =
        FONT_COLORS.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: hex

    fun backgroundColorLabel(hex: String): String =
        BACKGROUND_COLORS.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: hex

    fun outlineColorLabel(hex: String): String =
        OUTLINE_COLORS.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: hex
}
