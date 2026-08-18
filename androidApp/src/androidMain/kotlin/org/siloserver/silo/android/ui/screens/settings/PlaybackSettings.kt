package org.siloserver.silo.android.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.R
import org.siloserver.silo.domain.player.IntroSkipMode
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
    introSkipMode: IntroSkipMode,
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
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
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
    val introSkipOptions = IntroSkipMode.entries.map { it to stringResource(introSkipModeLabel(it)) }
    SettingsSection(title = "Playback", modifier = modifier) {
        // A pair no preset covers (set through the API, or left by a legacy
        // compound value) still gets a truthful label rather than a picker
        // silently showing the wrong entry.
        SettingsDropdownRow(
            label = "Preferred quality",
            description = "The quality Silo requests when playback starts.",
            value = QualityPresets.describe(qualityResolution, maxBitrateKbps),
            options = QualityPresets.ALL.map { it.label },
            onOptionSelected = { label ->
                QualityPresets.ALL.firstOrNull { it.label == label }
                    ?.let { onQualityPresetSelected(it.id) }
            },
        )

        SettingsDropdownRow(
            label = "Audio language",
            description = "Choose which spoken language Silo should prefer first.",
            value = LanguageOptions.label(audioLanguage, SettingKeys.PLAYBACK_AUDIO_LANGUAGE),
            options = audioLanguageOptions.map { it.second },
            onOptionSelected = { label ->
                onAudioLanguageChanged(LanguageOptions.wireValue(label, audioLanguageOptions))
            },
        )

        // Three-way, not a switch: the boolean this replaced could not say
        // "never". Labels and semantics are fixed by the contract.
        SettingsDropdownRow(
            label = stringResource(R.string.settings_intro_skip_title),
            description = "What happens when a detected intro starts: leave it alone, " +
                "offer a Skip Intro button, or skip it and offer an undo.",
            value = stringResource(introSkipModeLabel(introSkipMode)),
            options = introSkipOptions.map { it.second },
            onOptionSelected = { label ->
                introSkipOptions.firstOrNull { it.second == label }?.let { onIntroSkipModeChanged(it.first) }
            },
        )

        SettingsSwitchRow(
            label = "Auto-skip credits",
            description = "Move through end credits automatically when a skip is available.",
            checked = autoSkipCredits,
            onCheckedChange = onAutoSkipCreditsChanged,
        )

        // iOS PlaybackSettingsView parity: Dolby Vision (off plays the HDR10
        // base layer) with the Profile 7 fallback nested under it — the P7 row
        // only shows while Dolby Vision is on.
        SettingsSwitchRow(
            label = "Dolby Vision",
            description = "Allow Dolby Vision output on this device.",
            checked = dolbyVisionEnabled,
            onCheckedChange = onDolbyVisionEnabledChanged,
        )
        if (dolbyVisionEnabled) {
            SettingsSwitchRow(
                label = "Profile 7 HDR10 fallback",
                description = "Play Profile 7 sources as HDR10 when this device cannot decode them natively.",
                checked = dvProfile7HDR10Fallback,
                onCheckedChange = onDvProfile7HDR10FallbackChanged,
            )
        }

        SettingsSwitchRow(
            label = "Picture-in-picture",
            description = "Keep playing in a floating window when you leave the player.",
            checked = pictureInPictureEnabled,
            onCheckedChange = onPictureInPictureEnabledChanged,
        )

        SettingsSwitchRow(
            label = "Auto-play next episode",
            description = "Continue to the next episode automatically.",
            checked = autoPlayNext,
            onCheckedChange = onAutoPlayNextChanged,
        )

        SettingsDropdownRow(
            label = "Next up prompt",
            description = "How long before the end of an episode the next-up prompt appears.",
            value = nextUpPromptLabel(nextUpPromptSeconds),
            options = nextUpPromptOptions.map(::nextUpPromptLabel),
            onOptionSelected = { label ->
                onNextUpPromptSecondsChanged(nextUpPromptOptions.first { nextUpPromptLabel(it) == label })
            },
        )

        SettingsDropdownRow(
            label = "Rewind on resume",
            description = "Skip back this far when resuming a partly watched item.",
            value = resumeRewindLabel(resumeRewindSeconds),
            options = resumeRewindOptions.map(::resumeRewindLabel),
            onOptionSelected = { label ->
                onResumeRewindSecondsChanged(resumeRewindOptions.first { resumeRewindLabel(it) == label })
            },
        )

        SettingsDropdownRow(
            label = "Still watching prompt",
            description = "How many episodes auto-play before Silo asks whether you are still watching.",
            value = passOutThresholdLabel(passOutThreshold),
            options = passOutThresholdOptions.map(::passOutThresholdLabel),
            onOptionSelected = { label ->
                onPassOutThresholdChanged(passOutThresholdOptions.first { passOutThresholdLabel(it) == label })
            },
        )

        SettingsDestructiveRow(
            label = "Reset playback settings",
            description = "Return this device's playback settings to their defaults.",
            onClick = onResetPlaybackOverrides,
        )
    }
}

/** The label each intro-skip mode is offered under; the copy is contract-fixed. */
@StringRes
private fun introSkipModeLabel(mode: IntroSkipMode): Int = when (mode) {
    IntroSkipMode.NEVER -> R.string.settings_intro_skip_never
    IntroSkipMode.ASK -> R.string.settings_intro_skip_ask
    IntroSkipMode.ALWAYS -> R.string.settings_intro_skip_always
}
