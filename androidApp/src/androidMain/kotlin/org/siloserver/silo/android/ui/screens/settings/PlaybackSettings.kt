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

// Discrete choices for the two behavior settings (0 = off). The label↔value
// maps below convert.
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
 * Playback → Streaming (Apple `PlaybackSettingsView` "Streaming"): quality,
 * audio language, Dolby Vision, and — where the Apple list ends with
 * Background Playback — Android's picture-in-picture. The footer describes the
 * chosen quality preset.
 */
@Composable
fun PlaybackStreamingSection(
    qualityResolution: String,
    maxBitrateKbps: Int?,
    audioLanguage: String,
    audioLanguageSuggestions: List<String>,
    dolbyVisionEnabled: Boolean,
    dvProfile7HDR10Fallback: Boolean,
    pictureInPictureEnabled: Boolean,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val audioLanguageOptions = remember(audioLanguage, audioLanguageSuggestions) {
        LanguageOptions.options(
            key = SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
            currentValue = audioLanguage,
            runtimeValues = audioLanguageSuggestions,
        )
    }
    // A pair no preset covers (set through the API, or left by a legacy
    // compound value) still gets a truthful label rather than a picker
    // silently showing the wrong entry.
    val qualityLabel = QualityPresets.describe(qualityResolution, maxBitrateKbps)
    val qualityFooter = QualityPresets.presetFor(qualityResolution, maxBitrateKbps)?.description
        ?: "$qualityLabel."
    SettingsSection(title = "Streaming", footer = qualityFooter, modifier = modifier) {
        SettingsDropdownRow(
            label = "Quality",
            value = qualityLabel,
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

        // Dolby Vision (off plays the HDR10 base layer) with the Profile 7
        // fallback under it — the P7 row only shows while Dolby Vision is on.
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
    }
}

/**
 * Playback → Episodes (Apple "Episodes"), plus Android's two resume and
 * auto-play limits at the end, explained in the footer.
 */
@Composable
fun PlaybackEpisodesSection(
    autoPlayNext: Boolean,
    nextUpPromptSeconds: Int,
    introSkipMode: IntroSkipMode,
    autoSkipCredits: Boolean,
    resumeRewindSeconds: Int,
    passOutThreshold: Int,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val introSkipOptions = IntroSkipMode.entries.map { it to stringResource(introSkipModeLabel(it)) }
    SettingsSection(
        title = "Episodes",
        footer = "Rewind on Resume skips back when you return to a partly watched title. Still Watching " +
            "Prompt sets how many episodes play in a row before Silo asks whether you're still watching.",
        modifier = modifier,
    ) {
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

        // Three-way, not a switch: the boolean this replaced could not say
        // "never". Option labels and semantics are fixed by the contract.
        SettingsDropdownRow(
            label = "Skip Intros",
            value = stringResource(introSkipModeLabel(introSkipMode)),
            options = introSkipOptions.map { it.second },
            onOptionSelected = { label ->
                introSkipOptions.firstOrNull { it.second == label }?.let { onIntroSkipModeChanged(it.first) }
            },
        )

        SettingsSwitchRow(
            label = "Skip Credits",
            checked = autoSkipCredits,
            onCheckedChange = onAutoSkipCreditsChanged,
        )

        SettingsDropdownRow(
            label = "Rewind on Resume",
            value = resumeRewindLabel(resumeRewindSeconds),
            options = resumeRewindOptions.map(::resumeRewindLabel),
            onOptionSelected = { label ->
                onResumeRewindSecondsChanged(resumeRewindOptions.first { resumeRewindLabel(it) == label })
            },
        )

        SettingsDropdownRow(
            label = "Still Watching Prompt",
            value = passOutThresholdLabel(passOutThreshold),
            options = passOutThresholdOptions.map(::passOutThresholdLabel),
            onOptionSelected = { label ->
                onPassOutThresholdChanged(passOutThresholdOptions.first { passOutThresholdLabel(it) == label })
            },
        )
    }
}

/** Playback → reset, the Apple page's last group. */
@Composable
fun PlaybackResetSection(onResetPlaybackOverrides: () -> Unit, modifier: Modifier = Modifier) {
    SettingsSection(
        title = null,
        footer = "Resets playback choices for this device and profile back to the server fallback.",
        modifier = modifier,
    ) {
        SettingsDestructiveRow(
            label = "Reset Playback Overrides",
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
