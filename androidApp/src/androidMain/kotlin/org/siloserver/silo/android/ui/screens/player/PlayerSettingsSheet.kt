package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.android.R
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.common.settings.LetterboxExpansion
import org.siloserver.silo.common.player.SleepTimerState

/**
 * Where the sheet currently is. The gear opens [Root] — a flat list of the
 * four things worth reading at a glance (quality, subtitles, audio) plus one
 * door to everything else, the shape Plex uses. Sub-screens replace the sheet
 * content in place rather than stacking new sheets, so there is only ever one
 * surface over the video.
 *
 * Back targets are fixed rather than a stack: [PlaybackOptions] returns to
 * [Root], every leaf returns to [PlaybackOptions].
 */
private enum class SettingsRoute {
    Root,
    PlaybackOptions,
    Speed,
    PictureSize,
    FillScreen,
    SkipIntros,
    Sync,
    ;

    val parent: SettingsRoute?
        get() = when (this) {
            Root -> null
            PlaybackOptions -> Root
            else -> PlaybackOptions
        }

    val title: String
        get() = when (this) {
            Root -> "Settings"
            PlaybackOptions -> "Playback Options"
            Speed -> "Speed"
            PictureSize -> "Picture size"
            FillScreen -> "Fill the screen"
            SkipIntros -> "Skip intros"
            Sync -> "Audio & subtitle sync"
        }
}

/** Playback settings menu shared by regular phones and tabletop mode. */
@OptIn(ExperimentalMaterial3Api::class)
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
    // Root-list values. The gear now reports the same three things the
    // toolbar buttons open, so the menu answers "what am I watching with?"
    // without opening anything.
    qualityLabel: String = "",
    onOpenQuality: () -> Unit = {},
    audioLabel: String = "",
    subtitleLabel: String = "",
    onOpenTracks: () -> Unit = {},
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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var routeOrdinal by rememberSaveable { mutableIntStateOf(SettingsRoute.Root.ordinal) }
    val route = SettingsRoute.entries[routeOrdinal]
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }
    val leaveFor = { next: () -> Unit ->
        scope.dismissPlayerSheet(sheetState, onDismiss, next)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard),
        ) {
            PlayerSheetHeader(
                title = route.title,
                onBack = route.parent?.let { parent -> { routeOrdinal = parent.ordinal } },
                onDismiss = dismissSheet,
            )
            PlayerSheetDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp, bottom = 18.dp),
            ) {
                when (route) {
                    SettingsRoute.Root -> {
                        ValueRow(
                            label = "Quality",
                            value = qualityLabel,
                            onClick = { leaveFor(onOpenQuality) },
                        )
                        ValueRow(
                            label = "Subtitles",
                            value = subtitleLabel,
                            onClick = { leaveFor(onOpenTracks) },
                        )
                        ValueRow(
                            label = "Audio",
                            value = audioLabel,
                            onClick = { leaveFor(onOpenTracks) },
                        )
                        ValueRow(
                            label = "Playback Options",
                            value = null,
                            onClick = { routeOrdinal = SettingsRoute.PlaybackOptions.ordinal },
                        )
                    }

                    SettingsRoute.PlaybackOptions -> {
                        ValueRow(
                            label = "Speed",
                            value = "${formatPlaybackSpeed(playbackSpeed)}×",
                            onClick = { routeOrdinal = SettingsRoute.Speed.ordinal },
                        )
                        ValueRow(
                            label = "Picture size",
                            value = pictureSizeLabel(videoGravity),
                            onClick = { routeOrdinal = SettingsRoute.PictureSize.ordinal },
                        )
                        ValueRow(
                            label = "Fill the screen",
                            value = letterboxExpansionLabel(letterboxExpansion),
                            onClick = { routeOrdinal = SettingsRoute.FillScreen.ordinal },
                        )
                        ValueRow(
                            label = stringResource(R.string.settings_intro_skip_title),
                            value = introSkipLabel(introSkipMode),
                            onClick = { routeOrdinal = SettingsRoute.SkipIntros.ordinal },
                        )
                        SwitchRow(
                            label = "Auto-play next episode",
                            checked = autoPlayNextEnabled,
                            onCheckedChange = onSetAutoPlayNext,
                        )
                        ValueRow(
                            label = "Audio & subtitle sync",
                            value = "${formatDelayMs(audioDelayMs)} · ${formatDelayMs(subtitleDelayMs)}",
                            onClick = { routeOrdinal = SettingsRoute.Sync.ordinal },
                        )
                        ValueRow(
                            label = "Subtitle style",
                            value = null,
                            onClick = { leaveFor(onOpenSubtitleStyle) },
                        )
                        ValueRow(
                            label = "Sleep timer",
                            value = formatSleepTimerSubtitle(sleepTimerState),
                            onClick = { leaveFor(onOpenSleepTimer) },
                        )
                        SwitchRow(
                            label = "HDR",
                            checked = hdrEnabled,
                            onCheckedChange = onSetHdrEnabled,
                        )
                        SwitchRow(
                            label = "Dolby Vision",
                            checked = dolbyVisionEnabled,
                            onCheckedChange = onSetDolbyVisionEnabled,
                        )
                        ValueRow(
                            label = "Playback stats",
                            value = stats.summaryLabel(),
                            onClick = { leaveFor(onOpenPlaybackStats) },
                        )
                    }

                    SettingsRoute.Speed -> {
                        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0).forEach { value ->
                            CheckRow(
                                label = "${formatPlaybackSpeed(value)}×",
                                isSelected = isSameSpeed(value, playbackSpeed),
                                onClick = { onSetPlaybackSpeed(value) },
                            )
                        }
                    }

                    SettingsRoute.PictureSize -> {
                        listOf("fit", "fill", "stretch").forEach { value ->
                            CheckRow(
                                label = pictureSizeLabel(value),
                                isSelected = videoGravity == value,
                                onClick = { onSetVideoGravity(value) },
                            )
                        }
                    }

                    SettingsRoute.FillScreen -> {
                        listOf(
                            LetterboxExpansion.ClearOfCamera,
                            LetterboxExpansion.FullWidth,
                            LetterboxExpansion.Off,
                        ).forEach { value ->
                            CheckRow(
                                label = letterboxExpansionLabel(value),
                                isSelected = letterboxExpansion == value,
                                onClick = { onSetLetterboxExpansion(value) },
                            )
                        }
                        FootnoteText(
                            "Widescreen films are expanded past the black bars stored in the " +
                                "file, never into the picture itself. Full width uses the whole " +
                                "display and lets the camera sit on the image. Video without " +
                                "stored bars already fits and does not change.",
                        )
                    }

                    SettingsRoute.SkipIntros -> {
                        listOf(
                            IntroSkipMode.NEVER,
                            IntroSkipMode.ASK,
                            IntroSkipMode.ALWAYS,
                        ).forEach { value ->
                            CheckRow(
                                label = introSkipLabel(value),
                                isSelected = introSkipMode == value,
                                onClick = { onSetIntroSkipMode(value) },
                            )
                        }
                        FootnoteText(
                            "What happens when a detected intro starts: leave it alone, " +
                                "offer a Skip Intro button, or skip it and offer an undo.",
                        )
                    }

                    SettingsRoute.Sync -> {
                        DelaySpinnerRow(
                            label = "Audio delay",
                            subtitle = "PCM audio only",
                            valueMs = audioDelayMs,
                            enabled = audioDelayEnabled,
                            stepMs = 50,
                            minMs = -5000,
                            maxMs = 5000,
                            onChange = onSetAudioDelay,
                        )
                        DelaySpinnerRow(
                            label = "Subtitle delay",
                            subtitle = "Move captions earlier or later",
                            valueMs = subtitleDelayMs,
                            stepMs = 50,
                            minMs = -10000,
                            maxMs = 10000,
                            onChange = onSetSubtitleDelay,
                        )
                    }
                }
            }
        }
    }
}

/** Label · value · chevron. The single row shape the whole menu is built from. */
@Composable
private fun ValueRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The value carries the slack so every chevron lands on the same right
        // edge; an over-long track name truncates instead of shoving the label.
        Text(
            text = value.orEmpty(),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 15.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.34f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White.copy(alpha = 0.72f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Leaf-screen option: label on the left, a check on the selected one. */
@Composable
private fun CheckRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FootnoteText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
    )
}

@Composable
private fun DelaySpinnerRow(
    label: String,
    subtitle: String,
    valueMs: Int,
    stepMs: Int,
    minMs: Int,
    maxMs: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            SpinnerButton(
                label = "−",
                enabled = enabled,
                onClick = { onChange((valueMs - stepMs).coerceIn(minMs, maxMs)) },
            )
            Text(
                text = formatDelayMs(valueMs),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp),
            )
            SpinnerButton(
                label = "+",
                enabled = enabled,
                onClick = { onChange((valueMs + stepMs).coerceIn(minMs, maxMs)) },
            )
        }
    }
}

@Composable
private fun SpinnerButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.30f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

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
        .joinToString(" - ")
        .ifBlank { "Waiting for player data" }
}

private fun isSameSpeed(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

private fun formatDelayMs(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> "+$ms ms"
    else -> "−${-ms} ms"
}
