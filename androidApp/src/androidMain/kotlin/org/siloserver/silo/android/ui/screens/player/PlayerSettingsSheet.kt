package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.settings.LetterboxExpansion
import org.siloserver.silo.domain.player.IntroSkipMode

/**
 * Where the settings menu currently is. [Root] is one grouped list — Picture,
 * Audio & Subtitles, This session, Advanced — with every current value in a
 * right-aligned column, so the menu answers "what am I watching with?" without
 * opening anything. Value pickers replace the list in place; menus with their
 * own surface (quality, version, tracks, style, sleep timer, stats) hand over to it.
 */
private enum class SettingsRoute {
    Root,
    Speed,
    PictureSize,
    FillScreen,
    SkipIntros,
    Sync,
}

@Composable
private fun SettingsRoute.title(): String = stringResource(
    when (this) {
        SettingsRoute.Root -> CommonR.string.player_settings_title
        SettingsRoute.Speed -> CommonR.string.player_settings_speed
        SettingsRoute.PictureSize -> CommonR.string.player_settings_pictureSize
        SettingsRoute.FillScreen -> CommonR.string.player_settings_blackBars
        SettingsRoute.SkipIntros -> CommonR.string.settings_introSkip_title
        SettingsRoute.Sync -> CommonR.string.player_settings_syncTitle
    },
)

/** Playback settings menu shared by regular phones and tabletop mode. */
@Composable
fun PlayerSettingsSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    playbackSpeed: Double,
    onSetPlaybackSpeed: (Double) -> Unit,
    videoGravity: String,
    onSetVideoGravity: (String) -> Unit,
    letterboxExpansion: String = LetterboxExpansion.Default,
    onSetLetterboxExpansion: (String) -> Unit = {},
    introSkipMode: IntroSkipMode,
    onSetIntroSkipMode: (IntroSkipMode) -> Unit,
    autoPlayNextEnabled: Boolean,
    onSetAutoPlayNext: (Boolean) -> Unit,
    hdrEnabled: Boolean,
    onSetHdrEnabled: (Boolean) -> Unit,
    dolbyVisionEnabled: Boolean,
    onSetDolbyVisionEnabled: (Boolean) -> Unit,
    // False in a Watch Party: speed is a session-only 1x.
    showPlaybackSpeed: Boolean = true,
    // Shown whenever there is a playback plan, in a Watch Party too.
    showQuality: Boolean = true,
    // Shown for a title with several files, except in a Watch Party, which
    // plays exactly the room's file.
    showVersion: Boolean = false,
    qualityLabel: String = "",
    onOpenQuality: () -> Unit = {},
    versionLabel: String = "",
    onOpenVersion: () -> Unit = {},
    audioLabel: String = "",
    subtitleLabel: String = "",
    subtitleStyleLabel: String = "",
    onOpenTracks: (TracksTab) -> Unit = {},
    onOpenSubtitleStyle: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    stats: PlayerStatsSnapshot = PlayerStatsSnapshot(),
    onOpenPlaybackStats: () -> Unit = {},
    audioDelayMs: Int = 0,
    audioDelayEnabled: Boolean = true,
    onSetAudioDelay: (Int) -> Unit = {},
    subtitleDelayMs: Int = 0,
    subtitleDelaySavedToProfile: Boolean = true,
    onSetSubtitleDelay: (Int) -> Unit = {},
    sleepTimerState: SleepTimerState = SleepTimerState.Idle,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val controller = rememberPlayerMenuController(onDismiss)
    var routeOrdinal by rememberSaveable { mutableIntStateOf(SettingsRoute.Root.ordinal) }
    val route = SettingsRoute.entries[routeOrdinal]
    val toRoot = { routeOrdinal = SettingsRoute.Root.ordinal }
    val handOff = { next: () -> Unit -> controller.dismiss(then = next) }

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(
            title = route.title(),
            onClose = { controller.dismiss() },
            onBack = if (route == SettingsRoute.Root) null else toRoot,
            backDescription = stringResource(CommonR.string.player_menu_backToSettings),
        )
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            when (route) {
                SettingsRoute.Root -> {
                    // Quality, version and speed live here too: "Video", not "Picture".
                    PlayerSectionHeader(stringResource(CommonR.string.options_tabVideo))
                    PlayerGroup {
                        if (showQuality) {
                            PlayerValueRow(stringResource(CommonR.string.options_quality), qualityLabel, onClick = { handOff(onOpenQuality) })
                        }
                        if (showVersion) {
                            PlayerValueRow(
                                label = stringResource(CommonR.string.detail_version),
                                value = versionLabel,
                                onClick = { handOff(onOpenVersion) },
                                separator = showQuality,
                            )
                        }
                        if (showPlaybackSpeed) {
                            PlayerValueRow(
                                label = stringResource(CommonR.string.player_settings_speed),
                                value = speedLabel(playbackSpeed),
                                onClick = { routeOrdinal = SettingsRoute.Speed.ordinal },
                                separator = showQuality || showVersion,
                            )
                        }
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_settings_pictureSize),
                            value = pictureSizeLabel(videoGravity),
                            onClick = { routeOrdinal = SettingsRoute.PictureSize.ordinal },
                            separator = showQuality || showVersion || showPlaybackSpeed,
                        )
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_settings_blackBars),
                            value = letterboxExpansionLabel(letterboxExpansion),
                            onClick = { routeOrdinal = SettingsRoute.FillScreen.ordinal },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader(stringResource(CommonR.string.player_settings_sectionTracks))
                    PlayerGroup {
                        PlayerValueRow(stringResource(CommonR.string.player_tracks_subtitles), subtitleLabel, onClick = { handOff { onOpenTracks(TracksTab.Subtitles) } })
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_tracks_audio),
                            value = audioLabel,
                            onClick = { handOff { onOpenTracks(TracksTab.Audio) } },
                            separator = true,
                        )
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_subtitles_style_title),
                            value = subtitleStyleLabel,
                            onClick = { handOff(onOpenSubtitleStyle) },
                            separator = true,
                        )
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_settings_sync),
                            value = "${formatDelayMs(audioDelayMs)} · ${formatDelayMs(subtitleDelayMs)}",
                            onClick = { routeOrdinal = SettingsRoute.Sync.ordinal },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader(stringResource(CommonR.string.player_settings_sectionSession))
                    PlayerGroup {
                        PlayerValueRow(
                            label = stringResource(CommonR.string.settings_introSkip_title),
                            value = introSkipLabel(introSkipMode),
                            onClick = { routeOrdinal = SettingsRoute.SkipIntros.ordinal },
                        )
                        PlayerSwitchRow(
                            label = stringResource(CommonR.string.options_autoplayNext),
                            checked = autoPlayNextEnabled,
                            onCheckedChange = onSetAutoPlayNext,
                            separator = true,
                        )
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_sleepTimer_title),
                            value = formatSleepTimerSubtitle(sleepTimerState),
                            onClick = { handOff(onOpenSleepTimer) },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader(stringResource(CommonR.string.player_settings_sectionAdvanced))
                    PlayerGroup {
                        PlayerSwitchRow(label = "HDR", checked = hdrEnabled, onCheckedChange = onSetHdrEnabled)
                        PlayerSwitchRow(
                            label = "Dolby Vision",
                            checked = dolbyVisionEnabled,
                            onCheckedChange = onSetDolbyVisionEnabled,
                            separator = true,
                        )
                        PlayerValueRow(
                            label = stringResource(CommonR.string.player_stats_title),
                            value = stats.summaryLabel(),
                            onClick = { handOff(onOpenPlaybackStats) },
                            separator = true,
                        )
                    }
                }

                SettingsRoute.Speed -> if (showPlaybackSpeed) {
                    val normalSpeed = stringResource(CommonR.string.player_settings_speedNormal)
                    ChoiceGroup(
                        options = SPEEDS,
                        isSelected = { isSameSpeed(it, playbackSpeed) },
                        label = { speedLabel(it) },
                        detail = { if (it == 1.0) normalSpeed else null },
                        onSelect = onSetPlaybackSpeed,
                    )
                }

                SettingsRoute.PictureSize -> {
                    val labels = listOf("fit", "fill", "stretch").map { it to pictureSizeLabel(it) }
                    ChoiceGroup(
                        options = labels,
                        isSelected = { videoGravity == it.first },
                        label = { it.second },
                        onSelect = { onSetVideoGravity(it.first) },
                    )
                }

                SettingsRoute.FillScreen -> {
                    val labels = listOf(
                        LetterboxExpansion.ClearOfCamera,
                        LetterboxExpansion.FullWidth,
                        LetterboxExpansion.Off,
                    ).map { it to letterboxExpansionLabel(it) }
                    ChoiceGroup(
                        options = labels,
                        isSelected = { letterboxExpansion == it.first },
                        label = { it.second },
                        onSelect = { onSetLetterboxExpansion(it.first) },
                    )
                    PlayerFootnote(stringResource(CommonR.string.player_settings_fillScreenNote))
                }

                SettingsRoute.SkipIntros -> {
                    val labels = listOf(
                        IntroSkipMode.NEVER to stringResource(CommonR.string.settings_introSkip_never),
                        IntroSkipMode.ASK to stringResource(CommonR.string.settings_introSkip_ask),
                        IntroSkipMode.ALWAYS to stringResource(CommonR.string.settings_introSkip_always),
                    )
                    ChoiceGroup(
                        options = labels,
                        isSelected = { introSkipMode == it.first },
                        label = { it.second },
                        onSelect = { onSetIntroSkipMode(it.first) },
                    )
                    PlayerFootnote(stringResource(CommonR.string.player_settings_skipIntrosNote))
                }

                SettingsRoute.Sync -> {
                    Spacer(Modifier.height(8.dp))
                    PlayerGroup {
                        PlayerStepperRow(
                            label = stringResource(CommonR.string.player_settings_audioDelay),
                            // Audio delay is always this device's own (its output path).
                            detail = stringResource(
                                if (audioDelayEnabled) {
                                    CommonR.string.player_settings_audioDelayDetail
                                } else {
                                    CommonR.string.player_settings_audioDelayPassthrough
                                },
                            ),
                            value = formatDelayMs(audioDelayMs),
                            enabled = audioDelayEnabled,
                            onDecrease = { onSetAudioDelay((audioDelayMs - 50).coerceIn(-5000, 5000)) },
                            onIncrease = { onSetAudioDelay((audioDelayMs + 50).coerceIn(-5000, 5000)) },
                        )
                        PlayerStepperRow(
                            label = stringResource(CommonR.string.player_settings_subtitleDelay),
                            detail = stringResource(
                                if (subtitleDelaySavedToProfile) {
                                    CommonR.string.player_settings_subtitleDelaySaved
                                } else {
                                    CommonR.string.player_settings_subtitleDelayDevice
                                },
                            ),
                            value = formatDelayMs(subtitleDelayMs),
                            onDecrease = { onSetSubtitleDelay((subtitleDelayMs - 50).coerceIn(-10000, 10000)) },
                            onIncrease = { onSetSubtitleDelay((subtitleDelayMs + 50).coerceIn(-10000, 10000)) },
                            separator = true,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun <T> ChoiceGroup(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    detail: (T) -> String? = { null },
) {
    Spacer(Modifier.height(8.dp))
    PlayerGroup {
        options.forEachIndexed { index, option ->
            PlayerCheckRow(
                label = label(option),
                detail = detail(option),
                selected = isSelected(option),
                onClick = { onSelect(option) },
                separator = index > 0,
            )
        }
    }
}

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)

private fun speedLabel(speed: Double): String = "${formatPlaybackSpeed(speed)}×"

@Composable
private fun pictureSizeLabel(value: String): String = stringResource(mobileVideoGravityLabel(value))

@Composable
private fun letterboxExpansionLabel(value: String): String = stringResource(
    when (value) {
        LetterboxExpansion.FullWidth -> CommonR.string.player_settings_blackBarsFillFullWidth
        LetterboxExpansion.Off -> CommonR.string.player_settings_blackBarsKeep
        else -> CommonR.string.player_settings_blackBarsAvoidCamera
    },
)

@Composable
private fun introSkipLabel(mode: IntroSkipMode): String = when (mode) {
    IntroSkipMode.NEVER -> stringResource(CommonR.string.settings_introSkip_never)
    IntroSkipMode.ASK -> stringResource(CommonR.string.settings_introSkip_ask)
    IntroSkipMode.ALWAYS -> stringResource(CommonR.string.settings_introSkip_always)
}

@Composable
private fun formatSleepTimerSubtitle(state: SleepTimerState): String = when (state) {
    is SleepTimerState.Idle -> stringResource(CommonR.string.player_sleepTimer_off)
    is SleepTimerState.Active ->
        stringResource(CommonR.string.player_sleepTimer_pausingIn, formatRemaining(state.remainingSeconds))
}

@Composable
private fun PlayerStatsSnapshot.summaryLabel(): String {
    val route = backendRoute ?: backendDisplayName
    val waiting = stringResource(CommonR.string.player_stats_waiting)
    return listOfNotNull(resolution, route, bitrateBps?.let(::formatStatsBitrate))
        .take(2)
        .joinToString(" · ")
        .ifBlank { waiting }
}

private fun isSameSpeed(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

private fun formatDelayMs(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> "+$ms ms"
    else -> "−${-ms} ms"
}
