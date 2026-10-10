package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.playback.SubtitleTimingActions
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel

/** Which list the Audio & Subtitles menu opens on. */
enum class TracksTab { Subtitles, Audio }

/**
 * Audio & Subtitles. A segmented switch picks the list; the subtitle list ends
 * in Find more / Translate / Timing. Docked beside the picture (landscape) a
 * choice applies while the panel stays open, so the viewer sees the new track
 * on screen; as a portrait sheet, which covers the picture, a choice closes it.
 */
@Composable
fun TracksSheet(
    isVisible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    subtitles: List<PlayerSubtitleInfo>,
    selectedSubtitleIndex: Int,
    onSelectAudio: (Int) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onDismiss: () -> Unit,
    showSearchAction: Boolean = false,
    showTranslateAction: Boolean = false,
    onSearchSubtitles: () -> Unit = {},
    onTranslateWithAi: () -> Unit = {},
    subtitleStatus: (PlayerSubtitleInfo) -> String? = { null },
    timingActions: SubtitleTimingActions? = null,
    onSyncSubtitle: (String) -> Unit = {},
    onResetTiming: (String) -> Unit = {},
    initialTab: TracksTab = TracksTab.Subtitles,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val controller = rememberPlayerMenuController(onDismiss)
    val staysOpen = controller.presentation == PlayerMenuPresentation.Docked
    fun choose(select: () -> Unit) {
        select()
        if (!staysOpen) controller.dismiss()
    }
    var tabOrdinal by rememberSaveable {
        mutableIntStateOf(if (audioTracks.isEmpty()) TracksTab.Subtitles.ordinal else initialTab.ordinal)
    }
    var showTiming by rememberSaveable { mutableStateOf(false) }
    val tab = TracksTab.entries[tabOrdinal]

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        if (showTiming && timingActions != null) {
            PlayerPanelHeader(
                title = "Timing",
                onClose = { controller.dismiss() },
                onBack = { showTiming = false },
                backDescription = "Back to Audio & Subtitles",
            )
            PlayerMenuScrollColumn(horizontalPadding = 12.dp) {
                Spacer(Modifier.height(8.dp))
                SubtitleTimingGroup(
                    actions = timingActions,
                    onSync = { onSyncSubtitle(timingActions.key) },
                    onReset = { onResetTiming(timingActions.key) },
                )
            }
            return@PlayerMenu
        }

        PlayerPanelHeader(title = "Audio & Subtitles", onClose = { controller.dismiss() })
        if (audioTracks.isNotEmpty()) {
            PlayerSegmentedControl(
                segments = listOf(
                    PlayerSegment("Subtitles", subtitles.size),
                    PlayerSegment("Audio", audioTracks.size),
                ),
                selectedIndex = tab.ordinal,
                onSelect = { tabOrdinal = it },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
            )
        }

        when (tab) {
            TracksTab.Subtitles -> {
                PlayerMenuScrollColumn {
                    PlayerTrackRow(
                        title = "Off",
                        selected = selectedSubtitleIndex == -1,
                        onClick = { choose { onSelectSubtitle(-1) } },
                    )
                    subtitles.forEachIndexed { index, subtitle ->
                        val presentation = subtitleTrackPresentation(subtitle, index, subtitleStatus(subtitle))
                        PlayerTrackRow(
                            title = presentation.title,
                            chips = presentation.chips,
                            detail = presentation.detail,
                            selected = index == selectedSubtitleIndex,
                            onClick = { choose { onSelectSubtitle(index) } },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (showSearchAction || showTranslateAction || timingActions != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (showSearchAction) {
                            PlayerFooterButton(
                                icon = Icons.Rounded.Search,
                                label = "Find more",
                                onClick = { controller.dismiss(then = onSearchSubtitles) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (showTranslateAction) {
                            PlayerFooterButton(
                                icon = Icons.Rounded.AutoAwesome,
                                label = "Translate",
                                onClick = { controller.dismiss(then = onTranslateWithAi) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (timingActions != null) {
                            PlayerFooterButton(
                                icon = Icons.Rounded.Schedule,
                                label = "Timing",
                                onClick = { showTiming = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            TracksTab.Audio -> PlayerMenuScrollColumn {
                audioTracks.forEachIndexed { index, track ->
                    val presentation = audioTrackPresentation(track, index)
                    PlayerTrackRow(
                        title = presentation.title,
                        detail = presentation.detail,
                        selected = index == selectedAudioIndex,
                        onClick = { choose { onSelectAudio(index) } },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * "Sync to audio" and "Reset timing" for the selected subtitle, stored or a
 * file next to the media, with a running sync's progress and the last result.
 * Anyone who can play the file may retime it; after a refusal (demo mode) the
 * actions give way to a short explanation.
 */
@Composable
private fun SubtitleTimingGroup(
    actions: SubtitleTimingActions,
    onSync: () -> Unit,
    onReset: () -> Unit,
) {
    if (actions.forbidden) {
        PlayerFootnote(SubtitleTimingActions.FORBIDDEN_MESSAGE)
        return
    }
    if (actions.canSync || actions.canReset) {
        PlayerGroup {
            if (actions.canSync) {
                PlayerActionRow(
                    icon = Icons.Rounded.Sync,
                    label = if (actions.inProgress) "Syncing…" else "Sync to audio",
                    onClick = onSync,
                    enabled = actions.actionsEnabled,
                )
            }
            if (actions.canReset) {
                PlayerActionRow(
                    icon = Icons.Rounded.Restore,
                    label = "Reset timing",
                    onClick = onReset,
                    enabled = actions.actionsEnabled,
                    separator = actions.canSync,
                )
            }
        }
    }
    if (actions.inProgress) {
        SyncProgressRow(
            percent = actions.percent ?: 0,
            label = actions.phaseLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    actions.result?.let { result ->
        TimingMessage(result.text, color = if (result.warning) TimingWarningColor else null)
    }
    actions.note?.let { TimingMessage(it, color = PlayerChrome.Faint) }
    actions.error?.let { TimingMessage(it, color = MaterialTheme.colorScheme.error) }
}

private val TimingWarningColor = Color(0xFFFDE68A).copy(alpha = 0.85f)

@Composable
private fun TimingMessage(text: String, color: Color? = null) {
    Text(
        text = text,
        style = PlayerType.Footnote.copy(color = color ?: PlayerChrome.Graphite),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
    )
}

internal fun subtitleTrackLabel(sub: PlayerSubtitleInfo, index: Int): String =
    formatSubtitleTrackDisplayLabel(
        rawLabel = sub.label,
        language = sub.language,
        codecOrMime = sub.codec,
        isForced = sub.forced == true,
        index = index,
    )
