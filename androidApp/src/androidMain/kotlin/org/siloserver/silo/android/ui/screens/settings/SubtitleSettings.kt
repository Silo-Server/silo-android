package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.SettingKeys

/**
 * Subtitle settings section with language, display mode, and forced subtitles toggle.
 */
@Composable
fun SubtitleSettings(
    subtitleLanguage: String,
    subtitleLanguageSuggestions: List<String> = emptyList(),
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
    metadataLanguageSuggestions: List<String> = emptyList(),
    onMetadataLanguageChanged: (String) -> Unit = {},
) {
    val subtitleLanguageOptions = remember(subtitleLanguage, subtitleLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
            currentValue = subtitleLanguage,
            runtimeValues = subtitleLanguageSuggestions,
        )
    }
    val metadataLanguageOptions = remember(metadataLanguage, metadataLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.CATALOG_METADATA_LANGUAGE,
            currentValue = metadataLanguage,
            runtimeValues = metadataLanguageSuggestions,
        )
    }
    SettingsSection(title = "Subtitles", modifier = modifier) {
        SettingsDropdownRow(
            label = "Subtitle language",
            description = "Choose which subtitle language Silo should prefer first.",
            value = LanguageOptions.label(subtitleLanguage, SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE),
            options = subtitleLanguageOptions.map { it.second },
            onOptionSelected = { label ->
                onLanguageChanged(LanguageOptions.wireValue(label, subtitleLanguageOptions))
            },
        )

        SettingsDropdownRow(
            label = "Subtitle behavior",
            description = "When Silo should turn subtitles on.",
            value = subtitleMode.label,
            options = SubtitleMode.entries.map { it.label },
            onOptionSelected = { label ->
                SubtitleMode.entries.find { it.label == label }?.let(onModeChanged)
            },
        )

        SettingsSwitchRow(
            label = "Show forced subtitles",
            description = "Show subtitles for foreign-language dialogue even when subtitles are off.",
            checked = showForcedSubtitles,
            onCheckedChange = onForcedSubtitlesChanged,
        )

        // iOS SubtitleSettingsView APPEARANCE parity: Match Device Settings
        // (appearance follows the OS captioning preferences) + the custom
        // appearance editor (the same sheet the player uses).
        SettingsSwitchRow(
            label = "Match device caption settings",
            description = "Use the operating system's caption style instead of Silo's.",
            checked = subtitleMatchesDevice,
            onCheckedChange = onSubtitleMatchesDeviceChanged,
        )
        if (!subtitleMatchesDevice) {
            SettingsNavigationRow(
                label = "Subtitle appearance",
                description = "How subtitles are drawn during playback.",
                onClick = onOpenSubtitleAppearance,
            )
        }

        if (metadataLanguageEnabled) {
            SettingsDropdownRow(
                label = "Metadata language",
                description = "Fallback language Silo prefers for titles, descriptions, and artwork.",
                value = LanguageOptions.label(metadataLanguage, SettingKeys.CATALOG_METADATA_LANGUAGE),
                options = metadataLanguageOptions.map { it.second },
                onOptionSelected = { label ->
                    onMetadataLanguageChanged(LanguageOptions.wireValue(label, metadataLanguageOptions))
                },
            )
        }
    }
}
