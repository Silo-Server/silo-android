package org.siloserver.silo.android.ui.screens.settings

import org.siloserver.silo.common.i18n.text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.common.R as CommonR
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
        title = stringResource(CommonR.string.settings_subtitles_profile),
        footer = stringResource(CommonR.string.settings_subtitles_languageFooter),
        modifier = modifier,
    ) {
        val languageValue = LanguageOptions.label(subtitleLanguage, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE)
        val languageLabels = subtitleLanguageOptions.map { it.second }
        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_control_language),
            value = languageValue.text(),
            options = languageLabels.map { it.text() },
            selectedIndex = languageLabels.indexOf(languageValue),
            onIndexSelected = { onLanguageChanged(subtitleLanguageOptions[it].first) },
        )

        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_control_behavior),
            value = stringResource(subtitleMode.label),
            options = SubtitleMode.entries.map { stringResource(it.label) },
            selectedIndex = subtitleMode.ordinal,
            onIndexSelected = { onModeChanged(SubtitleMode.entries[it]) },
        )

        SettingsSwitchRow(
            label = stringResource(CommonR.string.settings_control_forced),
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
        title = stringResource(CommonR.string.settings_section_metadata),
        footer = stringResource(CommonR.string.settingsManifest_catalog_metadata_language_description),
        modifier = modifier,
    ) {
        val metadataValue = LanguageOptions.label(metadataLanguage, SettingKeys.CATALOG_METADATA_LANGUAGE)
        val metadataLabels = metadataLanguageOptions.map { it.second }
        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_control_metadataLanguage),
            value = metadataValue.text(),
            options = metadataLabels.map { it.text() },
            selectedIndex = metadataLabels.indexOf(metadataValue),
            onIndexSelected = { onMetadataLanguageChanged(metadataLanguageOptions[it].first) },
        )
    }
}

/**
 * Subtitles → Appearance: the live preview, then "Use Device Settings" and
 * "Only on This Device", whose footer says where the style comes from and
 * where edits are saved. The preview draws [previewAppearance], the style
 * playback uses, so it shows the device caption style while Use Device
 * Settings is on. The Text, Background, and Layout groups below edit the same
 * appearance the player's Subtitle Style sheet does — the profile's, unless
 * Only on This Device is on.
 */
@Composable
fun SubtitleAppearanceSection(
    previewAppearance: SubtitleAppearance,
    subtitleMatchesDevice: Boolean,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit,
    subtitleUsesDeviceOverride: Boolean,
    onSubtitleDeviceOverrideChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(CommonR.string.settings_subtitles_appearance))
        SubtitleAppearancePreview(previewAppearance)
        Spacer(modifier = Modifier.height(SettingsDimens.sectionGap))
        SettingsSection(
            title = null,
            footer = stringResource(
                when {
                    subtitleMatchesDevice -> CommonR.string.settings_subtitles_appearance_followsDevice
                    subtitleUsesDeviceOverride -> CommonR.string.settings_subtitles_scopeDevice
                    else -> CommonR.string.settings_subtitles_scopeProfile
                },
            ),
        ) {
            SettingsSwitchRow(
                label = stringResource(CommonR.string.settings_subtitles_useDeviceSettings),
                checked = subtitleMatchesDevice,
                onCheckedChange = onSubtitleMatchesDeviceChanged,
            )
            SettingsSwitchRow(
                label = stringResource(CommonR.string.settings_subtitles_onlyOnThisDevice),
                checked = subtitleUsesDeviceOverride,
                onCheckedChange = onSubtitleDeviceOverrideChanged,
                enabled = !subtitleMatchesDevice,
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
    SettingsSection(title = stringResource(CommonR.string.settings_subtitles_text), modifier = modifier) {
        SubtitleChoiceRow(
            label = stringResource(CommonR.string.settings_subtitles_fontSize),
            choices = SubtitleOptionLabels.FONT_SIZES,
            current = appearance.fontSize,
            enabled = enabled,
            onSelected = { size -> onEdit { it.copy(fontSize = size) } },
        )
        SubtitleChoiceRow(
            label = stringResource(CommonR.string.settings_subtitles_fontFamily),
            choices = SubtitleOptionLabels.FONT_FAMILIES,
            current = SubtitleOptionLabels.FONT_FAMILIES.firstOrNull { it.first.equals(appearance.fontFamily, ignoreCase = true) }?.first,
            fallback = appearance.fontFamily,
            enabled = enabled,
            onSelected = { family -> onEdit { it.copy(fontFamily = family) } },
        )
        SubtitleColorRow(
            label = stringResource(CommonR.string.settings_subtitles_fontColor),
            palette = SubtitleOptionLabels.FONT_COLORS,
            hex = appearance.fontColor,
            enabled = enabled,
            onSelected = { hex -> onEdit { it.copy(fontColor = hex) } },
        )
        if (showTextOpacity) {
            val steps = SubtitleOptionLabels.percentSteps(5, appearance.textOpacity)
            SubtitlePercentRow(
                steps = steps,
                current = appearance.textOpacity,
                enabled = enabled,
                onSelected = { percent -> onEdit { it.copy(textOpacity = percent) } },
            )
        }
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settings_subtitles_textOutline),
            checked = appearance.textOutline,
            enabled = enabled,
            onCheckedChange = { outline -> onEdit { it.copy(textOutline = outline) } },
        )
        SubtitleColorRow(
            label = stringResource(CommonR.string.settings_subtitles_outlineColor),
            palette = SubtitleOptionLabels.DARK_COLORS,
            hex = appearance.textOutlineColor,
            enabled = enabled && appearance.textOutline,
            onSelected = { hex -> onEdit { it.copy(textOutlineColor = hex) } },
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
    SettingsSection(title = stringResource(CommonR.string.settings_subtitles_background), modifier = modifier) {
        SubtitleChoiceRow(
            label = stringResource(CommonR.string.settings_subtitles_backgroundStyleShort),
            choices = SubtitleOptionLabels.BACKGROUND_STYLES,
            current = appearance.backgroundStyle,
            enabled = enabled,
            onSelected = { style -> onEdit { it.copy(backgroundStyle = style) } },
        )
        val steps = SubtitleOptionLabels.percentSteps(0, appearance.backgroundOpacity)
        SubtitlePercentRow(
            steps = steps,
            current = appearance.backgroundOpacity,
            enabled = enabled && boxed,
            onSelected = { percent -> onEdit { it.copy(backgroundOpacity = percent) } },
        )
        SubtitleColorRow(
            label = stringResource(CommonR.string.settings_subtitles_backgroundColorShort),
            palette = SubtitleOptionLabels.DARK_COLORS,
            hex = appearance.backgroundColor,
            enabled = enabled && boxed,
            onSelected = { hex -> onEdit { it.copy(backgroundColor = hex) } },
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
    SettingsSection(title = stringResource(CommonR.string.settings_subtitles_layout), modifier = modifier) {
        SubtitleChoiceRow(
            label = stringResource(CommonR.string.settings_subtitles_position),
            choices = SubtitleOptionLabels.POSITIONS,
            current = appearance.position,
            enabled = enabled,
            onSelected = { position -> onEdit { it.copy(position = position) } },
        )
    }
}



/**
 * The Apple page's live preview: sample text over a dark gradient, drawn with
 * the current font, colour, opacity, outline, background, and position.
 */
@Composable
private fun SubtitleAppearancePreview(appearance: SubtitleAppearance) {
    val previewText = stringResource(CommonR.string.settings_subtitles_preview)
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
                        text = previewText,
                        color = outline,
                        fontFamily = fontFamily,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x.dp, y.dp),
                    )
                }
            }
            Text(
                text = previewText,
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
 * A picker over [choices] (value to label), checking [current]. A value the
 * palette doesn't name shows as [fallback] with nothing checked.
 */
@Composable
private fun <T> SubtitleChoiceRow(
    label: String,
    choices: List<Pair<T, Int>>,
    current: T?,
    enabled: Boolean,
    onSelected: (T) -> Unit,
    fallback: String = "",
) {
    val index = choices.indexOfFirst { it.first == current }
    SettingsIndexedDropdownRow(
        label = label,
        value = if (index >= 0) stringResource(choices[index].second) else fallback,
        options = choices.map { stringResource(it.second) },
        selectedIndex = index,
        enabled = enabled,
        onIndexSelected = { onSelected(choices[it].first) },
    )
}

/** A colour picker over [palette]; a colour set elsewhere shows as its hex. */
@Composable
private fun SubtitleColorRow(
    label: String,
    palette: List<Pair<String, Int>>,
    hex: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) = SubtitleChoiceRow(
    label = label,
    choices = palette,
    current = palette.firstOrNull { it.first.equals(hex, ignoreCase = true) }?.first,
    fallback = hex,
    enabled = enabled,
    onSelected = onSelected,
)

/** An "Opacity" picker over percent [steps], checking [current]. */
@Composable
private fun SubtitlePercentRow(
    steps: List<Int>,
    current: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    SettingsIndexedDropdownRow(
        label = stringResource(CommonR.string.settings_subtitles_opacity),
        value = stringResource(CommonR.string.settings_subtitles_percent, current),
        options = steps.map { stringResource(CommonR.string.settings_subtitles_percent, it) },
        selectedIndex = steps.indexOf(current),
        enabled = enabled,
        onIndexSelected = { onSelected(steps[it]) },
    )
}

/**
 * Option labels for the Text / Background / Layout groups, in the Apple apps'
 * wording. The palettes match silo-apple `SubtitleAppearance` and the TV app's
 * `TvSubtitleAppearanceOptions`. Android also offers the outline background
 * style, which the Apple apps draw as a text outline.
 */
private object SubtitleOptionLabels {
    val FONT_SIZES = listOf(
        SubtitleFontSizePreset.Small to CommonR.string.settings_subtitles_size_small,
        SubtitleFontSizePreset.Medium to CommonR.string.settings_subtitles_size_medium,
        SubtitleFontSizePreset.Large to CommonR.string.settings_subtitles_size_large,
        SubtitleFontSizePreset.XLarge to CommonR.string.settings_subtitles_size_xLarge,
        SubtitleFontSizePreset.XXLarge to CommonR.string.settings_subtitles_size_xxLarge,
    )

    val FONT_FAMILIES = listOf(
        SubtitleAppearance.SANS_SERIF to CommonR.string.settings_subtitles_font_sansSerif,
        SubtitleAppearance.SERIF to CommonR.string.settings_subtitles_font_serif,
        SubtitleAppearance.MONOSPACE to CommonR.string.settings_subtitles_font_monospace,
    )

    val FONT_COLORS = listOf(
        "#ffffff" to CommonR.string.settings_subtitles_color_white,
        "#facc15" to CommonR.string.settings_subtitles_color_yellow,
        "#22c55e" to CommonR.string.settings_subtitles_color_green,
        "#06b6d4" to CommonR.string.settings_subtitles_color_cyan,
        "#d946ef" to CommonR.string.settings_subtitles_color_magenta,
        "#ef4444" to CommonR.string.settings_subtitles_color_red,
        "#3b82f6" to CommonR.string.settings_subtitles_color_blue,
        "#9ca3af" to CommonR.string.settings_subtitles_color_gray,
        "#000000" to CommonR.string.settings_subtitles_color_black,
    )

    /** Background and outline palette (Apple `backgroundColors` / `outlineColors`). */
    val DARK_COLORS = listOf(
        "#000000" to CommonR.string.settings_subtitles_color_black,
        "#374151" to CommonR.string.settings_subtitles_color_darkGray,
        "#1e3a5f" to CommonR.string.settings_subtitles_color_navy,
        "#7f1d1d" to CommonR.string.settings_subtitles_color_darkRed,
        "#14532d" to CommonR.string.settings_subtitles_color_darkGreen,
    )

    val BACKGROUND_STYLES = listOf(
        SubtitleBackgroundStylePreset.Box to CommonR.string.settings_subtitles_style_box,
        SubtitleBackgroundStylePreset.Shadow to CommonR.string.settings_subtitles_style_dropShadow,
        SubtitleBackgroundStylePreset.Outline to CommonR.string.settings_subtitles_style_outline,
        SubtitleBackgroundStylePreset.None to CommonR.string.settings_subtitles_style_none,
    )

    val POSITIONS = listOf(
        SubtitlePositionPreset.Bottom to CommonR.string.settings_subtitles_position_bottom,
        SubtitlePositionPreset.LowerThird to CommonR.string.settings_subtitles_position_lowerThird,
        SubtitlePositionPreset.Top to CommonR.string.settings_subtitles_position_top,
    )

    /**
     * Percent steps of five from [from] to 100, plus the saved value so one set
     * off the five-point cadence (the player sheet types any percent) is still
     * the checked option rather than silently replaced.
     */
    fun percentSteps(from: Int, current: Int): List<Int> = ((from..100 step 5) + current).toSortedSet().toList()
}
