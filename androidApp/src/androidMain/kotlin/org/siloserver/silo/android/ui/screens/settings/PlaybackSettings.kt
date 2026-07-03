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

private val qualityOptions = listOf("Auto", "Original", "4K", "1080p", "720p", "480p")
private val languageOptions = listOf("Default", "English", "Spanish", "French", "German", "Japanese", "Korean", "Chinese", "Portuguese", "Italian", "Russian")

// Discrete choices for the two behavior settings (0 = off). Dropdown idiom
// matches the rest of this section; the label↔value maps below convert.
private val resumeRewindOptions = listOf(0, 3, 5, 7, 10, 15, 20, 30)
private val passOutThresholdOptions = listOf(0, 2, 3, 4, 5)
private fun resumeRewindLabel(seconds: Int) = if (seconds <= 0) "Off" else "${seconds}s"
private fun passOutThresholdLabel(count: Int) = if (count <= 0) "Off" else count.toString()

/**
 * Playback settings section with quality preference, audio language,
 * and auto-skip toggles.
 */
@Composable
fun PlaybackSettings(
    defaultQuality: String,
    audioLanguage: String,
    autoSkipIntro: Boolean,
    autoSkipCredits: Boolean,
    pipEnabled: Boolean,
    // Row is hidden entirely on devices without PiP (API < 26 / no feature).
    pipAvailable: Boolean,
    resumeRewindSeconds: Int,
    passOutThreshold: Int,
    onQualityChanged: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onAutoSkipIntroChanged: (Boolean) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onPipEnabledChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    onResetPlaybackOverrides: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSectionCard(modifier = modifier) {
        SettingsSectionHeader("Playback")

        SettingsDropdownRow(
            label = "Default Quality",
            value = defaultQuality,
            options = qualityOptions,
            onOptionSelected = onQualityChanged,
        )

        SettingsDropdownRow(
            label = "Audio Language",
            value = audioLanguage,
            options = languageOptions,
            onOptionSelected = onAudioLanguageChanged,
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

        if (pipAvailable) {
            SettingsSwitchRow(
                label = "Picture-in-Picture",
                checked = pipEnabled,
                onCheckedChange = onPipEnabledChanged,
            )
        }

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
