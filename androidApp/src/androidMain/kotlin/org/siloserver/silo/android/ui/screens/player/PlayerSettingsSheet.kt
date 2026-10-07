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
import org.siloserver.silo.android.R
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.settings.LetterboxExpansion
import org.siloserver.silo.domain.player.IntroSkipMode

/**
 * Where the settings menu currently is. [Root] is one grouped list — Picture,
 * Audio & Subtitles, This session, Advanced — with every current value in a
 * right-aligned column, so the menu answers "what am I watching with?" without
 * opening anything. Value pickers replace the list in place; menus with their
 * own surface (quality, tracks, style, sleep timer, stats) hand over to it.
 */
private enum class SettingsRoute {
    Root,
    Speed,
    PictureSize,
    FillScreen,
    SkipIntros,
    Sync,
    ;

    val title: String
        get() = when (this) {
            Root -> "Settings"
            Speed -> "Speed"
            PictureSize -> "Picture size"
            FillScreen -> "Fill the screen"
            SkipIntros -> "Skip intros"
            Sync -> "Audio & subtitle sync"
        }
}

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
    // False in a Watch Party: speed is a session-only 1x and the version
    // picker is off, since the room plays exactly its own file.
    showPlaybackSpeed: Boolean = true,
    showQuality: Boolean = true,
    qualityLabel: String = "",
    onOpenQuality: () -> Unit = {},
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
            title = route.title,
            onClose = { controller.dismiss() },
            onBack = if (route == SettingsRoute.Root) null else toRoot,
            backDescription = "Back to settings",
        )
        PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
            when (route) {
                SettingsRoute.Root -> {
                    PlayerSectionHeader("Picture")
                    PlayerGroup {
                        if (showQuality) {
                            PlayerValueRow("Quality", qualityLabel, onClick = { handOff(onOpenQuality) })
                        }
                        if (showPlaybackSpeed) {
                            PlayerValueRow(
                                label = "Speed",
                                value = speedLabel(playbackSpeed),
                                onClick = { routeOrdinal = SettingsRoute.Speed.ordinal },
                                separator = showQuality,
                            )
                        }
                        PlayerValueRow(
                            label = "Picture size",
                            value = pictureSizeLabel(videoGravity),
                            onClick = { routeOrdinal = SettingsRoute.PictureSize.ordinal },
                            separator = showQuality || showPlaybackSpeed,
                        )
                        PlayerValueRow(
                            label = "Fill the screen",
                            value = letterboxExpansionLabel(letterboxExpansion),
                            onClick = { routeOrdinal = SettingsRoute.FillScreen.ordinal },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader("Audio & subtitles")
                    PlayerGroup {
                        PlayerValueRow("Subtitles", subtitleLabel, onClick = { handOff { onOpenTracks(TracksTab.Subtitles) } })
                        PlayerValueRow(
                            label = "Audio",
                            value = audioLabel,
                            onClick = { handOff { onOpenTracks(TracksTab.Audio) } },
                            separator = true,
                        )
                        PlayerValueRow(
                            label = "Subtitle style",
                            value = subtitleStyleLabel,
                            onClick = { handOff(onOpenSubtitleStyle) },
                            separator = true,
                        )
                        PlayerValueRow(
                            label = "Sync",
                            value = "${formatDelayMs(audioDelayMs)} · ${formatDelayMs(subtitleDelayMs)}",
                            onClick = { routeOrdinal = SettingsRoute.Sync.ordinal },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader("This session")
                    PlayerGroup {
                        PlayerValueRow(
                            label = stringResource(R.string.settings_intro_skip_title),
                            value = introSkipLabel(introSkipMode),
                            onClick = { routeOrdinal = SettingsRoute.SkipIntros.ordinal },
                        )
                        PlayerSwitchRow(
                            label = "Auto-play next",
                            checked = autoPlayNextEnabled,
                            onCheckedChange = onSetAutoPlayNext,
                            separator = true,
                        )
                        PlayerValueRow(
                            label = "Sleep timer",
                            value = formatSleepTimerSubtitle(sleepTimerState),
                            onClick = { handOff(onOpenSleepTimer) },
                            separator = true,
                        )
                    }

                    PlayerSectionHeader("Advanced")
                    PlayerGroup {
                        PlayerSwitchRow(label = "HDR", checked = hdrEnabled, onCheckedChange = onSetHdrEnabled)
                        PlayerSwitchRow(
                            label = "Dolby Vision",
                            checked = dolbyVisionEnabled,
                            onCheckedChange = onSetDolbyVisionEnabled,
                            separator = true,
                        )
                        PlayerValueRow(
                            label = "Playback stats",
                            value = stats.summaryLabel(),
                            onClick = { handOff(onOpenPlaybackStats) },
                            separator = true,
                        )
                    }
                }

                SettingsRoute.Speed -> if (showPlaybackSpeed) {
                    ChoiceGroup(
                        options = SPEEDS,
                        isSelected = { isSameSpeed(it, playbackSpeed) },
                        label = { speedLabel(it) },
                        detail = { if (it == 1.0) "Normal" else null },
                        onSelect = onSetPlaybackSpeed,
                    )
                }

                SettingsRoute.PictureSize -> ChoiceGroup(
                    options = listOf("fit", "fill", "stretch"),
                    isSelected = { videoGravity == it },
                    label = ::pictureSizeLabel,
                    onSelect = onSetVideoGravity,
                )

                SettingsRoute.FillScreen -> {
                    ChoiceGroup(
                        options = listOf(
                            LetterboxExpansion.ClearOfCamera,
                            LetterboxExpansion.FullWidth,
                            LetterboxExpansion.Off,
                        ),
                        isSelected = { letterboxExpansion == it },
                        label = ::letterboxExpansionLabel,
                        onSelect = onSetLetterboxExpansion,
                    )
                    PlayerFootnote(
                        "Widescreen films are expanded past the black bars stored in the " +
                            "file, never into the picture itself. Full width uses the whole " +
                            "display and lets the camera sit on the image. Video without " +
                            "stored bars already fits and does not change.",
                    )
                }

                SettingsRoute.SkipIntros -> {
                    val labels = listOf(
                        IntroSkipMode.NEVER to stringResource(R.string.settings_intro_skip_never),
                        IntroSkipMode.ASK to stringResource(R.string.settings_intro_skip_ask),
                        IntroSkipMode.ALWAYS to stringResource(R.string.settings_intro_skip_always),
                    )
                    ChoiceGroup(
                        options = labels,
                        isSelected = { introSkipMode == it.first },
                        label = { it.second },
                        onSelect = { onSetIntroSkipMode(it.first) },
                    )
                    PlayerFootnote(
                        "What happens when a detected intro starts: leave it alone, " +
                            "offer a Skip Intro button, or skip it and offer an undo.",
                    )
                }

                SettingsRoute.Sync -> {
                    Spacer(Modifier.height(8.dp))
                    PlayerGroup {
                        PlayerStepperRow(
                            label = "Audio delay",
                            detail = if (audioDelayEnabled) "PCM audio only" else "Off for passthrough audio",
                            value = formatDelayMs(audioDelayMs),
                            enabled = audioDelayEnabled,
                            onDecrease = { onSetAudioDelay((audioDelayMs - 50).coerceIn(-5000, 5000)) },
                            onIncrease = { onSetAudioDelay((audioDelayMs + 50).coerceIn(-5000, 5000)) },
                        )
                        PlayerStepperRow(
                            label = "Subtitle delay",
                            detail = "Move captions earlier or later",
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

private fun pictureSizeLabel(value: String): String = when (value) {
    "fill" -> "Fill"
    "stretch" -> "Stretch"
    else -> "Fit"
}

private fun letterboxExpansionLabel(value: String): String = when (value) {
    LetterboxExpansion.FullWidth -> "Full width"
    LetterboxExpansion.Off -> "Off"
    else -> "Clear of camera"
}

@Composable
private fun introSkipLabel(mode: IntroSkipMode): String = when (mode) {
    IntroSkipMode.NEVER -> stringResource(R.string.settings_intro_skip_never)
    IntroSkipMode.ASK -> stringResource(R.string.settings_intro_skip_ask)
    IntroSkipMode.ALWAYS -> stringResource(R.string.settings_intro_skip_always)
}

private fun formatSleepTimerSubtitle(state: SleepTimerState): String = when (state) {
    is SleepTimerState.Idle -> "Off"
    is SleepTimerState.Active -> "Pausing in ${formatRemaining(state.remainingSeconds)}"
}

private fun PlayerStatsSnapshot.summaryLabel(): String {
    val route = backendRoute ?: backendDisplayName
    return listOfNotNull(resolution, route, bitrateBps?.let(::formatStatsBitrate))
        .take(2)
        .joinToString(" · ")
        .ifBlank { "Waiting for player data" }
}

private fun isSameSpeed(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

private fun formatDelayMs(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> "+$ms ms"
    else -> "−${-ms} ms"
}
