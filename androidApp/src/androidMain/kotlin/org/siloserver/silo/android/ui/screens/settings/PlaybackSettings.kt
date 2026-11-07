package org.siloserver.silo.android.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.common.settings.UseProfileSettingsCopy
import org.siloserver.silo.common.settings.bufferingQualityModeLabel
import org.siloserver.silo.common.settings.learnedLimitLabel
import org.siloserver.silo.domain.player.BufferingQualityMode
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.CellularQuality
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.QualityChoices
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.network.apiv2.PlaybackLearnedLimitV2

// Quality is two independent pickers, as on the web: playback.preferred_quality
// (a resolution cap) and playback.max_bitrate_kbps (a bandwidth cap, null =
// uncapped). Cellular quality is one preset picker over its own pair.

// Discrete choices for the two behavior settings (0 = off). The pickers map a
// position in these lists back to the value.
private val resumeRewindOptions = listOf(0, 3, 5, 7, 10, 15, 20, 30)
private val passOutThresholdOptions = listOf(0, 2, 3, 4, 5)
// Up-Next prompt timing (seconds before end; 0 = at end). Mirrors TV/tvOS.
private val nextUpPromptOptions = listOf(0, 10, 30, 60, 120)

@Composable
private fun resumeRewindLabel(seconds: Int) =
    if (seconds <= 0) stringResource(CommonR.string.settings_off) else stringResource(CommonR.string.settings_resumeRewind_seconds, seconds)

@Composable
private fun passOutThresholdLabel(count: Int) = if (count <= 0) stringResource(CommonR.string.settings_off) else LocaleFormat.integer(count, currentNumberLocale())

@Composable
private fun nextUpPromptLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(CommonR.string.settings_nextUp_atEnd)
    seconds < 60 -> pluralStringResource(CommonR.plurals.settings_nextUp_secondsBeforeEnd, seconds, seconds)
    else -> pluralStringResource(CommonR.plurals.settings_nextUp_minutesBeforeEnd, seconds / 60, seconds / 60)
}

/**
 * The menu for a profile-layered picker: "Use profile setting" first, then
 * [options]. Routes that first option to [onUseProfileSetting] and checks it
 * while the device has no value of its own; otherwise checks [selectedIndex]
 * of [options] (none when out of range). [onIndexSelected] gets a position in
 * [options].
 */
@Composable
private fun ProfileLayeredDropdownRow(
    label: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    overridden: Boolean,
    onIndexSelected: (Int) -> Unit,
    onUseProfileSetting: () -> Unit,
    description: String? = null,
) {
    SettingsIndexedDropdownRow(
        label = label,
        value = value,
        options = listOf(UseProfileSettingsCopy.option) + options,
        selectedIndex = when {
            !overridden -> 0
            selectedIndex in options.indices -> selectedIndex + 1
            else -> -1
        },
        onIndexSelected = { index -> if (index == 0) onUseProfileSetting() else onIndexSelected(index - 1) },
        description = description,
    )
}

/**
 * A switch for a setting the profile also holds. A switch has no menu to put
 * "Use profile setting" in, so while this device holds its own value the
 * action sits in its own row right under the switch, as Cards & Posters'
 * "Use Profile Default" does.
 */
@Composable
internal fun ProfileLayeredSwitchRow(
    label: String,
    checked: Boolean,
    overridden: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onUseProfileSetting: () -> Unit,
) {
    SettingsSwitchRow(label = label, checked = checked, onCheckedChange = onCheckedChange)
    if (overridden) {
        val rowDescription = UseProfileSettingsCopy.rowDescription(label)
        SettingsNavigationRow(
            label = UseProfileSettingsCopy.row,
            onClick = onUseProfileSetting,
            showChevron = false,
            modifier = Modifier.semantics { contentDescription = rowDescription },
        )
    }
}

/**
 * Playback → Streaming (Apple `PlaybackSettingsView` "Streaming"): resolution,
 * maximum bitrate, audio language, Dolby Vision, and — where the Apple list
 * ends with Background Playback — Android's picture-in-picture. The footer
 * says what the two quality choices do.
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
    bufferingQualityMode: BufferingQualityMode,
    /** Whether the server has the setting; without it the row hides and Silo lowers automatically. */
    bufferingQualityModeSupported: Boolean,
    /** Shown, with its Reset action, only while the server reports one. */
    learnedLimit: PlaybackLearnedLimitV2?,
    learnedLimitResetFailed: Boolean,
    /** Keys this device holds its own value for. */
    deviceOverrides: Set<String>,
    /** Receives a [QualityChoices.RESOLUTIONS] value. */
    onResolutionSelected: (String) -> Unit,
    /** Receives a [QualityChoices.bitrateChoices] value; null is no limit. */
    onMaxBitrateSelected: (Int?) -> Unit,
    onAudioLanguageChanged: (String) -> Unit,
    onDolbyVisionEnabledChanged: (Boolean) -> Unit,
    onDvProfile7HDR10FallbackChanged: (Boolean) -> Unit,
    onPictureInPictureEnabledChanged: (Boolean) -> Unit,
    onBufferingQualityModeChanged: (BufferingQualityMode) -> Unit,
    onResetLearnedLimit: () -> Unit,
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
    // A stored cap that is not one of the choices (set through the API or
    // another client) is listed in its place, so it stays visible and selected.
    val bitrateChoices = remember(maxBitrateKbps) { QualityChoices.bitrateChoices(maxBitrateKbps) }
    val bufferingQualityOptions =
        BufferingQualityMode.entries.map { it to stringResource(bufferingQualityModeLabel(it)) }
    SettingsSection(
        title = stringResource(CommonR.string.settings_section_streaming),
        footer = QualityChoices.description(qualityResolution, maxBitrateKbps),
        modifier = modifier,
    ) {
        val resolutionValue = QualityChoices.resolutionLabel(qualityResolution)
        val resolutionOptions = QualityChoices.RESOLUTIONS.map { it.label }
        ProfileLayeredDropdownRow(
            label = stringResource(CommonR.string.settings_control_resolution),
            value = resolutionValue,
            options = resolutionOptions,
            selectedIndex = resolutionOptions.indexOf(resolutionValue),
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.PreferredQuality),
            onIndexSelected = { onResolutionSelected(QualityChoices.RESOLUTIONS[it].value) },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.PreferredQuality) },
        )

        val bitrateValue = QualityChoices.bitrateLabel(maxBitrateKbps)
        val bitrateOptions = bitrateChoices.map { it.label }
        ProfileLayeredDropdownRow(
            label = stringResource(CommonR.string.settings_control_bitrate),
            value = bitrateValue,
            options = bitrateOptions,
            selectedIndex = bitrateOptions.indexOf(bitrateValue),
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.MaxBitrateKbps),
            onIndexSelected = { onMaxBitrateSelected(bitrateChoices[it].value) },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.MaxBitrateKbps) },
        )

        // Next to Maximum Bitrate, the manual cap on Auto it complements. The
        // copy is the spec's.
        if (bufferingQualityModeSupported) {
            ProfileLayeredDropdownRow(
                label = stringResource(CommonR.string.settings_bufferingQuality_title),
                value = stringResource(bufferingQualityModeLabel(bufferingQualityMode)),
                options = bufferingQualityOptions.map { it.second },
                selectedIndex = bufferingQualityOptions.indexOfFirst { it.first == bufferingQualityMode },
                overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.BufferingQualityMode),
                onIndexSelected = { onBufferingQualityModeChanged(bufferingQualityOptions[it].first) },
                onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.BufferingQualityMode) },
                description = stringResource(CommonR.string.settings_bufferingQuality_description),
            )
        }

        if (learnedLimit != null) {
            SettingsRow(label = learnedLimitLabel(learnedLimit)) {
                TextButton(onClick = onResetLearnedLimit) {
                    Text(stringResource(CommonR.string.settings_learnedLimit_reset))
                }
            }
            if (learnedLimitResetFailed) {
                SettingsProse(body = stringResource(CommonR.string.settings_learnedLimit_resetFailed))
            }
        }

        val audioLanguageValue = LanguageOptions.label(audioLanguage, SettingKeys.PLAYBACK_AUDIO_LANGUAGE)
        val audioLanguageLabels = audioLanguageOptions.map { it.second }
        ProfileLayeredDropdownRow(
            label = stringResource(CommonR.string.settings_control_audioLanguage),
            value = audioLanguageValue,
            options = audioLanguageLabels,
            selectedIndex = audioLanguageLabels.indexOf(audioLanguageValue),
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.AudioLanguage),
            onIndexSelected = { onAudioLanguageChanged(audioLanguageOptions[it].first) },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AudioLanguage) },
        )

        // Dolby Vision (off plays the HDR10 base layer) with the Profile 7
        // fallback under it — the P7 row only shows while Dolby Vision is on.
        SettingsSwitchRow(
            label = stringResource(CommonR.string.settingsManifest_player_dolby_vision_enabled_label),
            checked = dolbyVisionEnabled,
            onCheckedChange = onDolbyVisionEnabledChanged,
        )
        if (dolbyVisionEnabled) {
            SettingsSwitchRow(
                label = stringResource(CommonR.string.settings_video_dvProfile7Fallback),
                checked = dvProfile7HDR10Fallback,
                onCheckedChange = onDvProfile7HDR10FallbackChanged,
            )
        }

        SettingsSwitchRow(
            label = stringResource(CommonR.string.settings_playback_pictureInPicture),
            checked = pictureInPictureEnabled,
            onCheckedChange = onPictureInPictureEnabledChanged,
        )
    }
}

/**
 * Playback → Cellular, phone only: the quality used on mobile data and metered
 * networks, picked as one preset because bitrate is what matters on a data
 * plan. A stored pair no preset covers is listed under its own label, so it
 * stays visible and selected.
 */
@Composable
fun PlaybackCellularSection(
    resolution: String,
    maxBitrateKbps: Int?,
    /** Keys this device holds its own value for. */
    deviceOverrides: Set<String>,
    /** Receives a [QualityPresets] preset id. */
    onPresetSelected: (String) -> Unit,
    /** Receives the key of the control going back to the profile's value. */
    onUseProfileSetting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = CellularQuality.label(resolution, maxBitrateKbps)
    val custom = label.takeIf { QualityPresets.presetFor(resolution, maxBitrateKbps) == null }
    val options = QualityPresets.ALL.map { it.label } + listOfNotNull(custom)
    SettingsSection(title = stringResource(CommonR.string.settings_playback_cellular), footer = CellularQuality.FOOTER, modifier = modifier) {
        ProfileLayeredDropdownRow(
            label = stringResource(CommonR.string.settings_playback_cellularQuality),
            value = label,
            options = options,
            selectedIndex = options.indexOf(label),
            overridden = PlaybackSettingsKeys.hasDeviceOverride(deviceOverrides, PlaybackSettingsKeys.CellularPreferredQuality),
            onIndexSelected = { index -> QualityPresets.ALL.getOrNull(index)?.let { onPresetSelected(it.id) } },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.CellularPreferredQuality) },
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
        title = stringResource(CommonR.string.settings_section_episodes),
        footer = stringResource(CommonR.string.settings_episodes_footer),
        modifier = modifier,
    ) {
        ProfileLayeredSwitchRow(
            label = stringResource(CommonR.string.settings_control_autoplay),
            checked = autoPlayNext,
            overridden = overridden(PlaybackSettingsKeys.AutoPlayNext),
            onCheckedChange = onAutoPlayNextChanged,
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AutoPlayNext) },
        )

        ProfileLayeredDropdownRow(
        label = stringResource(CommonR.string.settings_nextUp_title),
        value = nextUpPromptLabel(nextUpPromptSeconds),
        options = nextUpPromptOptions.map { nextUpPromptLabel(it) },
        selectedIndex = nextUpPromptOptions.indexOf(nextUpPromptSeconds.coerceAtLeast(0)),
        overridden = overridden(PlaybackSettingsKeys.NextUpPromptSeconds),
        onIndexSelected = { onNextUpPromptSecondsChanged(nextUpPromptOptions[it]) },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.NextUpPromptSeconds) },
        )

        // Three-way, not a switch: the boolean this replaced could not say
        // "never". Option labels and semantics are fixed by the contract.
        ProfileLayeredDropdownRow(
        label = stringResource(CommonR.string.settings_control_intros),
        value = stringResource(introSkipModeLabel(introSkipMode)),
        options = introSkipOptions.map { it.second },
        selectedIndex = introSkipOptions.indexOfFirst { it.first == introSkipMode },
        overridden = overridden(PlaybackSettingsKeys.IntroSkipMode),
        onIndexSelected = { onIntroSkipModeChanged(introSkipOptions[it].first) },
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.IntroSkipMode) },
        )

        ProfileLayeredSwitchRow(
            label = stringResource(CommonR.string.settings_control_credits),
            checked = autoSkipCredits,
            overridden = overridden(PlaybackSettingsKeys.AutoSkipCredits),
            onCheckedChange = onAutoSkipCreditsChanged,
            onUseProfileSetting = { onUseProfileSetting(PlaybackSettingsKeys.AutoSkipCredits) },
        )

        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_resumeRewind_title),
            value = resumeRewindLabel(resumeRewindSeconds),
            options = resumeRewindOptions.map { resumeRewindLabel(it) },
            selectedIndex = resumeRewindOptions.indexOf(resumeRewindSeconds.coerceAtLeast(0)),
            onIndexSelected = { onResumeRewindSecondsChanged(resumeRewindOptions[it]) },
        )

        SettingsIndexedDropdownRow(
            label = stringResource(CommonR.string.settings_stillWatching_title),
            value = passOutThresholdLabel(passOutThreshold),
            options = passOutThresholdOptions.map { passOutThresholdLabel(it) },
            selectedIndex = passOutThresholdOptions.indexOf(passOutThreshold.coerceAtLeast(0)),
            onIndexSelected = { onPassOutThresholdChanged(passOutThresholdOptions[it]) },
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
        footer = UseProfileSettingsCopy.footer,
        modifier = modifier,
    ) {
        SettingsDestructiveRow(
            label = UseProfileSettingsCopy.resetAllRow,
            onClick = { confirming = true },
        )
    }
    if (confirming) {
        SiloConfirmDialog(
            title = UseProfileSettingsCopy.confirmTitle,
            body = UseProfileSettingsCopy.confirmMessage,
            confirmLabel = UseProfileSettingsCopy.confirmButton,
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
    IntroSkipMode.NEVER -> CommonR.string.settings_introSkip_never
    IntroSkipMode.ASK -> CommonR.string.settings_introSkip_ask
    IntroSkipMode.ALWAYS -> CommonR.string.settings_introSkip_always
}
