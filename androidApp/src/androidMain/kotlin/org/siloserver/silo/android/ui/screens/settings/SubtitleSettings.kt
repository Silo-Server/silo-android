package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.pointSize

/**
 * Subtitles → Profile (Apple `SubtitleSettingsView` "Profile"): language,
 * behavior, and forced subtitles, with the Apple footer.
 */
@Composable
fun SubtitleProfileSection(
    subtitleLanguage: String,
    subtitleLanguageSuggestions: List<String>,
    subtitleMode: SubtitleMode,
    showForcedSubtitles: Boolean,
    onLanguageChanged: (String) -> Unit,
    onModeChanged: (SubtitleMode) -> Unit,
    onForcedSubtitlesChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitleLanguageOptions = remember(subtitleLanguage, subtitleLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = subtitleLanguage,
            runtimeValues = subtitleLanguageSuggestions,
        )
    }
    SettingsSection(
        title = "Profile",
        footer = "Used to pick a matching track when one is available. Forced subtitles cover " +
            "foreign-language dialogue even when subtitles are off or set to auto.",
        modifier = modifier,
    ) {
        SettingsDropdownRow(
            label = "Language",
            value = LanguageOptions.label(subtitleLanguage, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
            options = subtitleLanguageOptions.map { it.second },
            onOptionSelected = { label ->
                onLanguageChanged(LanguageOptions.wireValue(label, subtitleLanguageOptions))
            },
        )

        SettingsDropdownRow(
            label = "Behavior",
            value = subtitleMode.label,
            options = SubtitleMode.entries.map { it.label },
            onOptionSelected = { label ->
                SubtitleMode.entries.find { it.label == label }?.let(onModeChanged)
            },
        )

        SettingsSwitchRow(
            label = "Show Forced Subtitles",
            checked = showForcedSubtitles,
            onCheckedChange = onForcedSubtitlesChanged,
        )
    }
}

/**
 * Subtitles → Metadata, shown while the server's Metadata AI is on. The footer
 * is the settings contract's own description of the key.
 */
@Composable
fun SubtitleMetadataSection(
    metadataLanguage: String,
    metadataLanguageSuggestions: List<String>,
    onMetadataLanguageChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadataLanguageOptions = remember(metadataLanguage, metadataLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.CATALOG_METADATA_LANGUAGE,
            currentValue = metadataLanguage,
            runtimeValues = metadataLanguageSuggestions,
        )
    }
    SettingsSection(
        title = "Metadata",
        footer = "Fallback language Silo prefers for titles, descriptions, and artwork.",
        modifier = modifier,
    ) {
        SettingsDropdownRow(
            label = "Metadata Language",
            value = LanguageOptions.label(metadataLanguage, SettingKeys.CATALOG_METADATA_LANGUAGE),
            options = metadataLanguageOptions.map { it.second },
            onOptionSelected = { label ->
                onMetadataLanguageChanged(LanguageOptions.wireValue(label, metadataLanguageOptions))
            },
        )
    }
}

/**
 * Subtitles → Appearance: the live preview, then "Use Device Settings", whose
 * footer says where the style comes from. The Text, Background, and Layout
 * groups below edit the same appearance the player's Subtitle Style sheet does.
 */
@Composable
fun SubtitleAppearanceSection(
    appearance: SubtitleAppearance,
    subtitleMatchesDevice: Boolean,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSectionHeader("Appearance")
        SubtitleAppearancePreview(appearance)
        Spacer(modifier = Modifier.height(SettingsDimens.sectionGap))
        SettingsSection(
            title = null,
            footer = if (subtitleMatchesDevice) {
                "Subtitles follow this device's caption style from Android's accessibility settings."
            } else {
                "Saved on the server for this profile on this device."
            },
        ) {
            SettingsSwitchRow(
                label = "Use Device Settings",
                checked = subtitleMatchesDevice,
                onCheckedChange = onSubtitleMatchesDeviceChanged,
            )
        }
    }
}

/**
 * Subtitles → Text. Dimmed and disabled while the device caption style is in
 * use, as on the Apple page.
 */
@Composable
fun SubtitleTextSection(
    appearance: SubtitleAppearance,
    enabled: Boolean,
    showTextOpacity: Boolean,
    onEdit: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = "Text", modifier = modifier) {
        SettingsDropdownRow(
            label = "Font Size",
            value = SubtitleOptionLabels.fontSize(appearance.fontSize),
            options = SubtitleOptionLabels.FONT_SIZES.map { it.second },
            enabled = enabled,
            onOptionSelected = { label ->
                SubtitleOptionLabels.FONT_SIZES.firstOrNull { it.second == label }
                    ?.let { (size, _) -> onEdit { it.copy(fontSize = size) } }
            },
        )
        SettingsDropdownRow(
            label = "Font Family",
            value = SubtitleOptionLabels.fontFamily(appearance.fontFamily),
            options = SubtitleOptionLabels.FONT_FAMILIES.map { it.second },
            enabled = enabled,
            onOptionSelected = { label ->
                SubtitleOptionLabels.FONT_FAMILIES.firstOrNull { it.second == label }
                    ?.let { (family, _) -> onEdit { it.copy(fontFamily = family) } }
            },
        )
        SettingsDropdownRow(
            label = "Font Color",
            value = SubtitleOptionLabels.colorName(appearance.fontColor, SubtitleOptionLabels.FONT_COLORS),
            options = SubtitleOptionLabels.FONT_COLORS.map { it.second },
            enabled = enabled,
            onOptionSelected = { label ->
                SubtitleOptionLabels.FONT_COLORS.firstOrNull { it.second == label }
                    ?.let { (hex, _) -> onEdit { it.copy(fontColor = hex) } }
            },
        )
        if (showTextOpacity) {
            val steps = SubtitleOptionLabels.percentSteps(5, appearance.textOpacity)
            SettingsDropdownRow(
                label = "Opacity",
                value = "${appearance.textOpacity}%",
                options = steps.map { "$it%" },
                enabled = enabled,
                onOptionSelected = { label ->
                    label.removeSuffix("%").toIntOrNull()?.let { percent -> onEdit { it.copy(textOpacity = percent) } }
                },
            )
        }
        SettingsSwitchRow(
            label = "Text Outline",
            checked = appearance.textOutline,
            enabled = enabled,
            onCheckedChange = { outline -> onEdit { it.copy(textOutline = outline) } },
        )
        SettingsDropdownRow(
            label = "Outline Color",
            value = SubtitleOptionLabels.colorName(appearance.textOutlineColor, SubtitleOptionLabels.DARK_COLORS),
            options = SubtitleOptionLabels.DARK_COLORS.map { it.second },
            enabled = enabled && appearance.textOutline,
            onOptionSelected = { label ->
                SubtitleOptionLabels.DARK_COLORS.firstOrNull { it.second == label }
                    ?.let { (hex, _) -> onEdit { it.copy(textOutlineColor = hex) } }
            },
        )
    }
}

/** Subtitles → Background. Opacity and colour apply only to the box style. */
@Composable
fun SubtitleBackgroundSection(
    appearance: SubtitleAppearance,
    enabled: Boolean,
    onEdit: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boxed = appearance.backgroundStyle == SubtitleBackgroundStylePreset.Box
    SettingsSection(title = "Background", modifier = modifier) {
        SettingsDropdownRow(
            label = "Style",
            value = SubtitleOptionLabels.backgroundStyle(appearance.backgroundStyle),
            options = SubtitleOptionLabels.BACKGROUND_STYLES.map { it.second },
            enabled = enabled,
            onOptionSelected = { label ->
                SubtitleOptionLabels.BACKGROUND_STYLES.firstOrNull { it.second == label }
                    ?.let { (style, _) -> onEdit { it.copy(backgroundStyle = style) } }
            },
        )
        val steps = SubtitleOptionLabels.percentSteps(0, appearance.backgroundOpacity)
        SettingsDropdownRow(
            label = "Opacity",
            value = "${appearance.backgroundOpacity}%",
            options = steps.map { "$it%" },
            enabled = enabled && boxed,
            onOptionSelected = { label ->
                label.removeSuffix("%").toIntOrNull()?.let { percent -> onEdit { it.copy(backgroundOpacity = percent) } }
            },
        )
        SettingsDropdownRow(
            label = "Color",
            value = SubtitleOptionLabels.colorName(appearance.backgroundColor, SubtitleOptionLabels.DARK_COLORS),
            options = SubtitleOptionLabels.DARK_COLORS.map { it.second },
            enabled = enabled && boxed,
            onOptionSelected = { label ->
                SubtitleOptionLabels.DARK_COLORS.firstOrNull { it.second == label }
                    ?.let { (hex, _) -> onEdit { it.copy(backgroundColor = hex) } }
            },
        )
    }
}

/** Subtitles → Layout. */
@Composable
fun SubtitleLayoutSection(
    appearance: SubtitleAppearance,
    enabled: Boolean,
    onEdit: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = "Layout", modifier = modifier) {
        SettingsDropdownRow(
            label = "Position",
            value = SubtitleOptionLabels.position(appearance.position),
            options = SubtitleOptionLabels.POSITIONS.map { it.second },
            enabled = enabled,
            onOptionSelected = { label ->
                SubtitleOptionLabels.POSITIONS.firstOrNull { it.second == label }
                    ?.let { (position, _) -> onEdit { it.copy(position = position) } }
            },
        )
    }
}

private const val SubtitlePreviewText = "Subtitles will look like this"

/**
 * The Apple page's live preview: sample text over a dark gradient, drawn with
 * the current font, colour, opacity, outline, background, and position.
 */
@Composable
private fun SubtitleAppearancePreview(appearance: SubtitleAppearance) {
    val safe = appearance.sanitized()
    val fontFamily = when (safe.fontFamily.lowercase()) {
        SubtitleAppearance.SERIF -> FontFamily.Serif
        SubtitleAppearance.MONOSPACE -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val fontSize = (safe.fontSize.pointSize * 0.36).sp
    val foreground = previewColor(safe.fontColor).copy(alpha = safe.textOpacity.coerceIn(1, 100) / 100f)
    val outline = previewColor(safe.textOutlineColor)
    val showOutline = safe.textOutline || safe.backgroundStyle == SubtitleBackgroundStylePreset.Outline
    val boxed = safe.backgroundStyle == SubtitleBackgroundStylePreset.Box
    val boxColor = previewColor(safe.backgroundColor).copy(
        alpha = if (boxed) safe.backgroundOpacity.coerceIn(0, 100) / 100f else 0f,
    )
    val shadow = if (safe.backgroundStyle == SubtitleBackgroundStylePreset.Shadow) {
        Shadow(color = Color.Black, blurRadius = 6f)
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(SettingsDimens.groupRadius))
            .background(Brush.linearGradient(colors = listOf(Color(0xFF55575A), Color(0xFF101113))))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = when (safe.position) {
            SubtitlePositionPreset.Top -> Alignment.TopCenter
            SubtitlePositionPreset.LowerThird -> Alignment.Center
            SubtitlePositionPreset.Bottom -> Alignment.BottomCenter
        },
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(boxColor)
                .padding(horizontal = if (boxed) 8.dp else 0.dp, vertical = if (boxed) 3.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showOutline) {
                listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).forEach { (x, y) ->
                    Text(
                        text = SubtitlePreviewText,
                        color = outline,
                        fontFamily = fontFamily,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x.dp, y.dp),
                    )
                }
            }
            Text(
                text = SubtitlePreviewText,
                color = foreground,
                fontFamily = fontFamily,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = shadow),
            )
        }
    }
}

private fun previewColor(hex: String): Color =
    runCatching { Color(hex.toColorInt()) }.getOrDefault(Color.White)

/**
 * Option labels for the Text / Background / Layout groups, in the Apple apps'
 * wording. The palettes match silo-apple `SubtitleAppearance` and the TV app's
 * `TvSubtitleAppearanceOptions`. Android also offers the outline background
 * style, which the Apple apps draw as a text outline.
 */
private object SubtitleOptionLabels {
    val FONT_SIZES = listOf(
        SubtitleFontSizePreset.Small to "Small",
        SubtitleFontSizePreset.Medium to "Medium",
        SubtitleFontSizePreset.Large to "Large",
        SubtitleFontSizePreset.XLarge to "X-Large",
        SubtitleFontSizePreset.XXLarge to "XX-Large",
    )

    val FONT_FAMILIES = listOf(
        SubtitleAppearance.SANS_SERIF to "Sans-serif",
        SubtitleAppearance.SERIF to "Serif",
        SubtitleAppearance.MONOSPACE to "Monospace",
    )

    val FONT_COLORS = listOf(
        "#ffffff" to "White",
        "#facc15" to "Yellow",
        "#22c55e" to "Green",
        "#06b6d4" to "Cyan",
        "#d946ef" to "Magenta",
        "#ef4444" to "Red",
        "#3b82f6" to "Blue",
        "#9ca3af" to "Gray",
        "#000000" to "Black",
    )

    /** Background and outline palette (Apple `backgroundColors` / `outlineColors`). */
    val DARK_COLORS = listOf(
        "#000000" to "Black",
        "#374151" to "Dark Gray",
        "#1e3a5f" to "Navy",
        "#7f1d1d" to "Dark Red",
        "#14532d" to "Dark Green",
    )

    val BACKGROUND_STYLES = listOf(
        SubtitleBackgroundStylePreset.Box to "Box",
        SubtitleBackgroundStylePreset.Shadow to "Drop Shadow",
        SubtitleBackgroundStylePreset.Outline to "Outline",
        SubtitleBackgroundStylePreset.None to "None",
    )

    val POSITIONS = listOf(
        SubtitlePositionPreset.Bottom to "Bottom",
        SubtitlePositionPreset.LowerThird to "Lower Third",
        SubtitlePositionPreset.Top to "Top",
    )

    fun fontSize(value: SubtitleFontSizePreset) = FONT_SIZES.first { it.first == value }.second

    fun fontFamily(value: String) =
        FONT_FAMILIES.firstOrNull { it.first.equals(value, ignoreCase = true) }?.second ?: value

    fun backgroundStyle(value: SubtitleBackgroundStylePreset) = BACKGROUND_STYLES.first { it.first == value }.second

    fun position(value: SubtitlePositionPreset) = POSITIONS.first { it.first == value }.second

    /** A palette name for [hex], or the hex itself for a colour set elsewhere. */
    fun colorName(hex: String, palette: List<Pair<String, String>>) =
        palette.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.second ?: hex

    /**
     * Percent steps of five from [from] to 100, plus the saved value so one set
     * off the five-point cadence (the player sheet types any percent) is still
     * the checked option rather than silently replaced.
     */
    fun percentSteps(from: Int, current: Int): List<Int> = ((from..100 step 5) + current).toSortedSet().toList()
}
