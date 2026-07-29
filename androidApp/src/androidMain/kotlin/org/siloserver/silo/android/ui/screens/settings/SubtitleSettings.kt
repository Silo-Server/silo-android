package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.ui.Modifier
import org.siloserver.silo.model.settings.LanguageOptions

// Both language rows store codes, not the labels shown in the picker: the
// profile's subtitle_language and the metadata language are BCP 47 on the wire.
// Hoisted so recomposition doesn't rebuild the label list per frame.
private val languageOptionLabels =
    LanguageOptions.options(unsetLabel = SUBTITLE_UNSET_LABEL).map { it.second }

// "Off" is right for subtitles — no language means no subtitles — but wrong for
// metadata, where unset inherits the library's language rather than disabling
// anything. Same table, different name for the same empty wire value.
private const val SUBTITLE_UNSET_LABEL = "Off"
private const val METADATA_UNSET_LABEL = "Default"

private val metadataLanguageOptionLabels =
    LanguageOptions.options(unsetLabel = METADATA_UNSET_LABEL).map { it.second }

/**
 * Subtitle settings section with language, display mode, and forced subtitles toggle.
 */
@Composable
fun SubtitleSettings(
    subtitleLanguage: String,
    subtitleMode: SubtitleMode,
    showForcedSubtitles: Boolean,
    onLanguageChanged: (String) -> Unit,
    onModeChanged: (SubtitleMode) -> Unit,
    onForcedSubtitlesChanged: (Boolean) -> Unit,
    subtitleMatchesDevice: Boolean = false,
    onSubtitleMatchesDeviceChanged: (Boolean) -> Unit = {},
    onOpenSubtitleAppearance: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Metadata AI description translation (server-gated; row hidden when off).
    metadataLanguageEnabled: Boolean = false,
    metadataLanguage: String = "",
    onMetadataLanguageChanged: (String) -> Unit = {},
) {
    SettingsSectionCard(modifier = modifier) {
        SettingsSectionHeader("Subtitles")

        SettingsDropdownRow(
            label = "Subtitle Language",
            value = LanguageOptions.label(subtitleLanguage, unsetLabel = SUBTITLE_UNSET_LABEL),
            options = languageOptionLabels,
            onOptionSelected = { label ->
                onLanguageChanged(LanguageOptions.wireValue(label))
            },
        )

        SettingsDropdownRow(
            label = "Subtitle Mode",
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

        // iOS SubtitleSettingsView APPEARANCE parity: Match Device Settings
        // (appearance follows the OS captioning preferences) + the custom
        // appearance editor (the same sheet the player uses).
        SettingsSwitchRow(
            label = "Match Device Settings",
            checked = subtitleMatchesDevice,
            onCheckedChange = onSubtitleMatchesDeviceChanged,
        )
        if (!subtitleMatchesDevice) {
            SettingsClickableRow(
                icon = Icons.Filled.ClosedCaption,
                label = "Subtitle Appearance",
                onClick = onOpenSubtitleAppearance,
            )
        }

        if (metadataLanguageEnabled) {
            SettingsDropdownRow(
                label = "Metadata Language",
                value = LanguageOptions.label(metadataLanguage, unsetLabel = METADATA_UNSET_LABEL),
                options = metadataLanguageOptionLabels,
                onOptionSelected = { label ->
                    onMetadataLanguageChanged(LanguageOptions.wireValue(label))
                },
            )
        }
    }
}
