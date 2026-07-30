package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SettingKeys

// Quality is two settings behind one picker: playback.preferred_quality (a
// resolution cap) and playback.max_bitrate_kbps (a bandwidth cap, null =
// uncapped). The preset table is shared with the TV app and mirrors the web
// client's, so the same choice reads back with the same label everywhere.

// Discrete choices for the two behavior settings (0 = off). Dropdown idiom
// matches the rest of this section; the label↔value maps below convert.
private val resumeRewindOptions = listOf(0, 3, 5, 7, 10, 15, 20, 30)
private val passOutThresholdOptions = listOf(0, 2, 3, 4, 5)
// Up-Next prompt timing (seconds before end; 0 = at end). Mirrors TV/tvOS.
private val nextUpPromptOptions = listOf(0, 10, 30, 60, 120)
private fun resumeRewindLabel(seconds: Int) = if (seconds <= 0) "Off" else "${seconds}s"
private fun passOutThresholdLabel(count: Int) = if (count <= 0) "Off" else count.toString()
private fun nextUpPromptLabel(seconds: Int): String = when {
    seconds <= 0 -> "At end"
    seconds < 60 -> "$seconds seconds before end"
    seconds == 60 -> "1 minute before end"
    else -> "${seconds / 60} minutes before end"
}

/**
 * Playback settings section with quality preference, audio language,
 * and auto-skip toggles.
 */
@Composable
fun PlaybackSettings(
    qualityResolution: String,
    maxBitrateKbps: Int?,
    audioLanguage: String,
    audioLanguageSuggestions: List<String> = emptyList(),
    autoSkipIntro: Boolean,
    autoSkipCredits: Boolean,
    pictureInPictureEnabled: Boolean,
    dolbyVisionEnabled: Boolean,
    dvProfile7HDR10Fallback: Boolean,
    autoPlayNext: Boolean,
    nextUpPromptSeconds: Int,
    resumeRewindSeconds: Int,
    passOutThreshold: Int,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val audioLanguageOptions = remember(audioLanguage, audioLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
            currentValue = audioLanguage,
            runtimeValues = audioLanguageSuggestions,
        )
    }
    SettingsSectionCard(modifier = modifier) {
        SettingsSectionHeader("Playback")

        // A pair no preset covers (set through the API, or left by a legacy
        // compound value) still gets a truthful label rather than a picker
        // silently showing the wrong entry.
        SettingsDropdownRow(
            label = "Default Quality",
            value = QualityPresets.describe(qualityResolution, maxBitrateKbps),
            options = QualityPresets.ALL.map { it.label },
            onOptionSelected = { label ->
                QualityPresets.ALL.firstOrNull { it.label == label }
                    ?.let { onQualityPresetSelected(it.id) }
            },
        )

        SettingsDropdownRow(
            label = "Audio Language",
            value = LanguageOptions.label(audioLanguage, SettingKeys.PLAYBACK_AUDIO_LANGUAGE),
            options = audioLanguageOptions.map { it.second },
            onOptionSelected = { label ->
                onAudioLanguageChanged(LanguageOptions.wireValue(label, audioLanguageOptions))
            },
        )

        SettingsSwitchRow(
            label = "Auto-Skip Intros",
            checked = autoSkipIntro,
            onCheckedChange = onAutoSkipIntroChanged,
        )

        SettingsSwitchRow(
            label = "Auto-Skip Credits",
            checked = autoSkipCredits,
            onCheckedChange = onAutoSkipCreditsChanged,
        )

        // iOS PlaybackSettingsView parity: Dolby Vision (off plays the HDR10
        // base layer) with the Profile 7 fallback nested under it — the P7 row
        // only shows while Dolby Vision is on.
        SettingsSwitchRow(
            label = "Dolby Vision",
            checked = dolbyVisionEnabled,
            onCheckedChange = onDolbyVisionEnabledChanged,
        )
        if (dolbyVisionEnabled) {
            SettingsSwitchRow(
                label = "Profile 7 HDR10 Fallback",
                checked = dvProfile7HDR10Fallback,
                onCheckedChange = onDvProfile7HDR10FallbackChanged,
            )
        }

        SettingsSwitchRow(
            label = "Picture-in-Picture",
            checked = pictureInPictureEnabled,
            onCheckedChange = onPictureInPictureEnabledChanged,
        )

        SettingsSwitchRow(
            label = "Auto-Play Next Episode",
            checked = autoPlayNext,
            onCheckedChange = onAutoPlayNextChanged,
        )

        SettingsDropdownRow(
            label = "Show Next Up",
            value = nextUpPromptLabel(nextUpPromptSeconds),
            options = nextUpPromptOptions.map(::nextUpPromptLabel),
            onOptionSelected = { label ->
                onNextUpPromptSecondsChanged(nextUpPromptOptions.first { nextUpPromptLabel(it) == label })
            },
        )

        SettingsDropdownRow(
            label = "Resume Skip-Back",
            value = resumeRewindLabel(resumeRewindSeconds),
            options = resumeRewindOptions.map(::resumeRewindLabel),
            onOptionSelected = { label ->
                onResumeRewindSecondsChanged(resumeRewindOptions.first { resumeRewindLabel(it) == label })
            },
        )

        SettingsDropdownRow(
            label = "Still-Watching Prompt After",
            value = passOutThresholdLabel(passOutThreshold),
            options = passOutThresholdOptions.map(::passOutThresholdLabel),
            onOptionSelected = { label ->
                onPassOutThresholdChanged(passOutThresholdOptions.first { passOutThresholdLabel(it) == label })
            },
        )

        SettingsActionRow(
            label = "Reset Playback Overrides",
            onClick = onResetPlaybackOverrides,
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS renders this as a destructive (red) button row.
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A settings row with a dropdown menu for selecting from a list of options.
 */
@Composable
fun SettingsDropdownRow(
    label: String,
    value: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SettingsRow(
            label = label,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
