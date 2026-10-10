package org.siloserver.silo.android.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.common.settings.UseProfileSettingsCopy
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
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
 * The menu for a profile-layered picker: "Use profile setting" first, then
 * [options]. Routes that first option to [onUseProfileSetting] and checks it
 * while the device has no value of its own.
 */
@Composable
private fun ProfileLayeredDropdownRow(
    label: String,
    value: String,
    options: List<String>,
    overridden: Boolean,
    onOptionSelected: (String) -> Unit,
    onUseProfileSetting: () -> Unit,
) {
    SettingsDropdownRow(
        label = label,
        value = value,
        options = listOf(UseProfileSettingsCopy.OPTION) + options,
        selectedOption = if (overridden) value else UseProfileSettingsCopy.OPTION,
        onOptionSelected = { option ->
            if (option == UseProfileSettingsCopy.OPTION) onUseProfileSetting() else onOptionSelected(option)
        },
    )
}

/**
 * A switch for a setting the profile also holds. A switch has no menu to put
 * "Use profile setting" in, so while this device holds its own value the
 * action sits in its own row right under the switch, as Cards & Posters'
 * "Use Profile Default" does.
 */
@Composable
private fun ProfileLayeredSwitchRow(
    label: String,
    checked: Boolean,
    overridden: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onUseProfileSetting: () -> Unit,
) {
    SettingsSwitchRow(label = label, checked = checked, onCheckedChange = onCheckedChange)
    if (overridden) {
        SettingsNavigationRow(
            label = UseProfileSettingsCopy.ROW,
            onClick = onUseProfileSetting,
            showChevron = false,
            modifier = Modifier.semantics { contentDescription = UseProfileSettingsCopy.rowDescription(label) },
        )
    }
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
    /** Keys this device holds its own value for. */
    deviceOverrides: Set<String>,
    /** Receives a [QualityPresets] preset id. */
    onQualityPresetSelected: (String) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    /** Receives the key of the control going back to the profile's value. */
    onUseProfileSetting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No "No preference" entry: on a device that means going back to the
    // profile's language, which "Use profile setting" already says.
    val audioLanguageOptions = remember(audioLanguage, audioLanguageSuggestions) {
        LanguageOptions.namedOptions(
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
        ProfileLayeredDropdownRow(
            label = "Quality",
            value = qualityLabel,
            options = QualityPresets.ALL.map { it.label },
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.PreferredQuality),
            onOptionSelected = { label ->
                QualityPresets.ALL.firstOrNull { it.label == label }
                    ?.let { onQualityPresetSelected(it.id) }
            },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.PreferredQuality) },
        )

        ProfileLayeredDropdownRow(
            label = "Audio Language",
            value = LanguageOptions.label(audioLanguage, SettingKeys.PLAYBACK_AUDIO_LANGUAGE),
            options = audioLanguageOptions.map { it.second },
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.AudioLanguage),
            onOptionSelected = { label ->
                audioLanguageOptions.firstOrNull { it.second == label }?.let { onAudioLanguageChanged(it.first) }
            },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AudioLanguage) },
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
    /** Keys this device holds its own value for. */
    deviceOverrides: Set<String>,
    onAutoPlayNextChanged: (Boolean) -> Unit,
    onNextUpPromptSecondsChanged: (Int) -> Unit,
    onIntroSkipModeChanged: (IntroSkipMode) -> Unit,
    onAutoSkipCreditsChanged: (Boolean) -> Unit,
    onResumeRewindSecondsChanged: (Int) -> Unit,
    onPassOutThresholdChanged: (Int) -> Unit,
    /** Receives the key of the control going back to the profile's value. */
    onUseProfileSetting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun overridden(key: String) = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, key)
    val introSkipOptions = IntroSkipMode.entries.map { it to stringResource(introSkipModeLabel(it)) }
    SettingsSection(
        title = "Episodes",
        footer = "Rewind on Resume skips back when you return to a partly watched title. Still Watching " +
            "Prompt sets how many episodes play in a row before Silo asks whether you're still watching.",
        modifier = modifier,
    ) {
        ProfileLayeredSwitchRow(
            label = "Auto-Play Next Episode",
            checked = autoPlayNext,
            overridden = overridden(PlaybackSettingsKeys.AutoPlayNext),
            onCheckedChange = onAutoPlayNextChanged,
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AutoPlayNext) },
        )

        ProfileLayeredDropdownRow(
            label = "Show Next Up",
            value = nextUpPromptLabel(nextUpPromptSeconds),
            options = nextUpPromptOptions.map(::nextUpPromptLabel),
            overridden = overridden(PlaybackSettingsKeys.NextUpPromptSeconds),
            onOptionSelected = { label ->
                nextUpPromptOptions.firstOrNull { nextUpPromptLabel(it) == label }?.let(onNextUpPromptSecondsChanged)
            },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.NextUpPromptSeconds) },
        )

        // Three-way, not a switch: the boolean this replaced could not say
        // "never". Option labels and semantics are fixed by the contract.
        ProfileLayeredDropdownRow(
            label = "Skip Intros",
            value = stringResource(introSkipModeLabel(introSkipMode)),
            options = introSkipOptions.map { it.second },
            overridden = overridden(PlaybackSettingsKeys.IntroSkipMode),
            onOptionSelected = { label ->
                introSkipOptions.firstOrNull { it.second == label }?.let { onIntroSkipModeChanged(it.first) }
            },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.IntroSkipMode) },
        )

        ProfileLayeredSwitchRow(
            label = "Skip Credits",
            checked = autoSkipCredits,
            overridden = overridden(PlaybackSettingsKeys.AutoSkipCredits),
            onCheckedChange = onAutoSkipCreditsChanged,
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AutoSkipCredits) },
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

/**
 * Playback → "Use Profile Settings", the Apple page's last group. Asks first:
 * it clears every playback setting changed on this device at once.
 */
@Composable
fun PlaybackResetSection(onResetPlaybackOverrides: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SettingsSection(
        title = null,
        footer = UseProfileSettingsCopy.FOOTER,
        modifier = modifier,
    ) {
        SettingsDestructiveRow(
            label = UseProfileSettingsCopy.RESET_ALL_ROW,
            onClick = { confirming = true },
        )
    }
    if (confirming) {
        SiloConfirmDialog(
            title = UseProfileSettingsCopy.CONFIRM_TITLE,
            body = UseProfileSettingsCopy.CONFIRM_MESSAGE,
            confirmLabel = UseProfileSettingsCopy.CONFIRM_BUTTON,
            onConfirm = {
                confirming = false
                onResetPlaybackOverrides()
            },
            onDismiss = { confirming = false },
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
