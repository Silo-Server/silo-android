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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.util.LanguageNames
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.SubtitleAiJobKind

private val WarnAmber = Color(0xFFEAB308)
private val ErrorRed = Color(0xFFEF4444)

private enum class AiMode { Subtitles, Audio }

/**
 * AI subtitle generation sheet, mirroring the web's SubtitleTranslateModal:
 * mode tabs (From subtitles / From audio, per availability), source picker,
 * target language dropdown, transcription quota line, and in-sheet job
 * progress with Cancel. Submission goes through PlayerViewModel.startAiJob
 * with start_position = current playhead; completion auto-selects the result
 * track (jobJustCompleted) and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTranslateSheet(
    tools: PlayerViewModel.SubtitleToolsUiState,
    subtitleTracks: List<PlayerSubtitleInfo>,
    audioTracks: List<AudioTrack>,
    defaultTargetLanguage: String,
    onRefreshQuota: () -> Unit,
    onSubmit: (kind: String, sourceIndex: Int, sourceLanguage: String, targetLanguage: String) -> Unit,
    onCancelJob: () -> Unit,
    onDismiss: () -> Unit,
    // Tracks-submenu back affordance: closes this sheet and reopens the parent
    // TracksSheet (wired in PlayerOverlay). Null falls back to a plain dismiss.
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    val aiStatus = tools.aiStatus
    val sourceTracks = remember(subtitleTracks) { subtitleTracks.filter(::isTranslatableSource) }
    val canTranslate = aiStatus?.enabled == true && sourceTracks.isNotEmpty()
    val canTranscribe = aiStatus?.transcribeEnabled == true && audioTracks.isNotEmpty()

    var mode by remember(canTranslate, canTranscribe) {
        mutableStateOf(if (canTranslate) AiMode.Subtitles else AiMode.Audio)
    }
    var sourceTrackPos by remember { mutableIntStateOf(0) } // position in sourceTracks
    var audioPos by remember { mutableIntStateOf(0) } // position in audioTracks (web's audioIndex)
    var targetLanguage by remember { mutableStateOf(defaultTargetLanguage) }

    val quota = tools.quota
    val quotaExhausted = quota != null && quota.remaining <= 0

    // Quota refreshed on every open so the counter is current before submit.
    LaunchedEffect(Unit) {
        if (canTranscribe) onRefreshQuota()
    }
    // On job completion return to the tracks sheet (via onBack) so the freshly
    // generated track is visible, rather than dropping onto bare player controls.
    LaunchedEffect(tools.jobJustCompleted) {
        if (tools.jobJustCompleted) (onBack ?: onDismiss)()
    }

    PlayerModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
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
            PlayerSheetHeader(
                title = "Translate with AI",
                onBack = onBack,
                onDismiss = onDismiss,
            )

            val activeJob = tools.activeJob
            when {
                activeJob != null -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        LinearProgressIndicator(
                            progress = { activeJob.progress.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${(activeJob.progress * 100).toInt()}%" +
                                (activeJob.progressMessage.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        OutlinedButton(
                            onClick = onCancelJob,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                !canTranslate && !canTranscribe -> {
                    Text(
                        text = "AI subtitle generation isn't available for this file — " +
                            "there are no translatable subtitle tracks and audio transcription is not enabled.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }

                else -> {
                    // Mode tabs — only when both paths are available.
                    if (canTranslate && canTranscribe) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ModeTab("From subtitles", mode == AiMode.Subtitles) { mode = AiMode.Subtitles }
                            ModeTab("From audio", mode == AiMode.Audio) { mode = AiMode.Audio }
                        }
                    }

                    if (mode == AiMode.Subtitles) {
                        PickerRow(
                            label = "Source track",
                            options = sourceTracks.map { sub ->
                                listOfNotNull(
                                    sub.label?.takeIf { it.isNotBlank() },
                                    LanguageNames.displayName(sub.language),
                                ).joinToString(" · ")
                            },
                            selected = sourceTrackPos.coerceIn(0, (sourceTracks.size - 1).coerceAtLeast(0)),
                            onSelect = { sourceTrackPos = it },
                        )
                    } else {
                        PickerRow(
                            label = "Audio track",
                            options = audioTracks.map { track ->
                                listOfNotNull(
                                    LanguageNames.displayName(track.language),
                                    track.channelLayout?.takeIf { it.isNotBlank() },
                                    track.title?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                            },
                            selected = audioPos.coerceIn(0, (audioTracks.size - 1).coerceAtLeast(0)),
                            onSelect = { audioPos = it },
                        )
                    }

                    PickerRow(
                        label = "Target language",
                        options = LanguageNames.dropdownOptions.map { it.second },
                        selected = LanguageNames.dropdownOptions
                            .indexOfFirst { it.first == targetLanguage }
                            .coerceAtLeast(0),
                        onSelect = { targetLanguage = LanguageNames.dropdownOptions[it].first },
                    )

                    if (mode == AiMode.Audio && quota != null) {
                        Text(
                            text = quotaLineText(quota),
                            color = if (quotaExhausted) WarnAmber else Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }

                    tools.translateError?.let { error ->
                        Text(
                            text = error,
                            color = ErrorRed,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }

                    Button(
                        onClick = {
                            if (mode == AiMode.Audio) {
                                val audio = audioTracks.getOrNull(audioPos) ?: return@Button
                                val kind = transcribeKindFor(audio.language, targetLanguage)
                                onSubmit(
                                    kind,
                                    audioPos, // audio source_index = list position (web parity)
                                    audio.language.orEmpty(),
                                    if (kind == SubtitleAiJobKind.Transcribe) "" else targetLanguage,
                                )
                            } else {
                                val source = sourceTracks.getOrNull(sourceTrackPos) ?: return@Button
                                onSubmit(
                                    SubtitleAiJobKind.Translate,
                                    source.index, // combined subtitle index from the session track list
                                    source.language.orEmpty(),
                                    targetLanguage,
                                )
                            }
                        },
                        enabled = !tools.translateSubmitting &&
                            !(mode == AiMode.Audio && quotaExhausted) &&
                            (if (mode == AiMode.Audio) audioTracks.isNotEmpty() else sourceTracks.isNotEmpty()),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        if (tools.translateSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text(if (mode == AiMode.Audio) "Generate" else "Translate")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun PickerRow(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = label.uppercase(),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = options.getOrNull(selected) ?: "—",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Choose $label",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
