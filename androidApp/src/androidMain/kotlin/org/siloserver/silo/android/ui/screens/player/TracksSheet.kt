package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel
import kotlinx.coroutines.launch

/**
 * Combined audio + subtitle picker bottom sheet. Mirrors iOS phone's
 * `TrackSelectionSheet` phoneList variant
 * (`iosApp/Screens/Player/Sheets/TrackSelectionSheet.swift:145-164`): a single
 * sheet with an "Audio" section and a "Subtitles" section, each rendering
 * compact rows with a trailing checkmark on the active selection.
 *
 * Subtitles always include an "Off" entry (`-1`) per tvOS / iOS contract.
 * Audio section is hidden entirely when there are no audio tracks (the
 * server already filters down to playable ones).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    ModalBottomSheet(
        onDismissRequest = {
            scope.launch { sheetState.hide() }
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Cap below the top edge + keep content flings from
                // dismissing the sheet — see PlayerSheetSupport.
                .heightIn(max = playerSheetMaxHeight())
                .nestedScroll(PlayerSheetFlingGuard)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2937).copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                )
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Audio and Subtitles",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            )

            if (audioTracks.isNotEmpty()) {
                SectionHeader("Audio")
                audioTracks.forEachIndexed { index, track ->
                    TrackRow(
                        label = audioTrackName(track, index),
                        attributes = audioTrackAttributes(track),
                        isSelected = index == selectedAudioIndex,
                        onClick = {
                            onSelectAudio(index)
                            scope.launch { sheetState.hide() }
                            onDismiss()
                        },
                    )
                }
            }

            SectionHeader("Subtitles")
            // "Off" is the canonical first entry — iOS / tvOS pattern.
            TrackRow(
                label = "Off",
                isSelected = selectedSubtitleIndex == -1,
                onClick = {
                    onSelectSubtitle(-1)
                    scope.launch { sheetState.hide() }
                    onDismiss()
                },
            )
            subtitles.forEachIndexed { index, sub ->
                TrackRow(
                    label = subtitleTrackLabel(sub, index),
                    isSelected = index == selectedSubtitleIndex,
                    onClick = {
                        onSelectSubtitle(index)
                        scope.launch { sheetState.hide() }
                        onDismiss()
                    },
                )
            }


            // Non-selecting action rows (web SubtitleMenu parity). Each
            // dismisses this sheet first — Material 3 sheets can't nest —
            // then PlayerOverlay opens the target sheet.
            if (showSearchAction) {
                ActionRow(
                    icon = Icons.Filled.Search,
                    label = "Search subtitles…",
                    onClick = {
                        scope.launch { sheetState.hide() }
                        onDismiss()
                        onSearchSubtitles()
                    },
                )
            }
            if (showTranslateAction) {
                ActionRow(
                    icon = Icons.Filled.Translate,
                    label = "Translate with AI…",
                    onClick = {
                        scope.launch { sheetState.hide() }
                        onDismiss()
                        onTranslateWithAi()
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrackRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    attributes: String? = null,
    enabled: Boolean = true,
) {
    // iOS phone TrackRow: a Button with VStack(name, optional attributes
    // caption) and a trailing tint checkmark when selected.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (attributes != null && attributes.isNotBlank()) {
                Text(
                    text = attributes,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        }
        if (isSelected) {
            // iOS uses `.tint` (accent) for the selection checkmark.
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        } else {
            Box(modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun audioTrackName(track: AudioTrack, index: Int): String =
    listOfNotNull(
        track.title?.takeIf { it.isNotBlank() },
        track.language?.takeIf { it.isNotBlank() }?.uppercase(),
    ).joinToString(" · ").ifBlank { "Audio ${index + 1}" }

private fun audioTrackAttributes(track: AudioTrack): String =
    listOfNotNull(
        track.codec?.takeIf { it.isNotBlank() }?.uppercase(),
        track.channels?.let { "${it}ch" },
    ).joinToString(" · ")

internal fun subtitleTrackLabel(sub: PlayerSubtitleInfo, index: Int): String =
    formatSubtitleTrackDisplayLabel(
        rawLabel = sub.label,
        language = sub.language,
        codecOrMime = sub.codec,
        isForced = sub.forced == true,
        index = index,
    )

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
