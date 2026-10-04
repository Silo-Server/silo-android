package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.theme.DarkBackground

/** Which capture mode the dialog is in — availability comes from AiStatus. */
private enum class TvAiTranslateMode(val label: String) {
    Subtitles("From subtitles"),
    Audio("From audio"),
}

/**
 * D-pad AI translate/transcribe dialog (TvOptionDialog panel idiom). Pure
 * pickers — no text input. Mode row appears only when both modes are
 * available; otherwise the single available mode is fixed. Submitting flips
 * the dialog body to an in-dialog progress view (percent + progress_message
 * + Cancel). Completion: the VM refreshes the track list, auto-selects
 * `result_subtitle_id`, and bumps `completedNonce` — observed here to dismiss.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAiTranslateDialog(
    aiState: AiTranslateUiState,
    /** Text-based session subtitle tracks (PGS/DVD filtered out by the caller). */
    subtitleSources: List<PlayerSubtitleInfo>,
    /** ExoPlayer audio track entries (ordinal index = server audio track index). */
    audioSources: List<PlayerTrackEntry>,
    defaultTargetLanguage: String,
    onSubmit: (kind: String, sourceIndex: Int, sourceLanguage: String?, targetLanguage: String) -> Unit,
    onCancelJob: () -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val subtitlesAvailable = aiState.status.enabled && subtitleSources.isNotEmpty()
    val audioAvailable = aiState.status.transcribeEnabled && audioSources.isNotEmpty()

    var mode by remember(subtitlesAvailable, audioAvailable) {
        mutableStateOf(if (subtitlesAvailable) TvAiTranslateMode.Subtitles else TvAiTranslateMode.Audio)
    }
    var subtitleSourcePos by remember { mutableIntStateOf(0) }
    var audioSourcePos by remember {
        mutableIntStateOf(audioSources.indexOfFirst { it.isSelected }.coerceAtLeast(0))
    }
    var targetPos by remember {
        mutableIntStateOf(tvSubtitleLanguageIndex(defaultTargetLanguage))
    }
    val firstRowFocus = remember { FocusRequester() }
    val initialNonce = remember { aiState.completedNonce }

    // Retry-until-focused, re-keyed on the phase: a one-shot grab is not enough
    // here. After a failed submit the form rows leave composition during
    // Submitting (which renders zero focusables), so focus must be re-acquired
    // when the Failed/Idle form (or the Running Cancel row) comes back —
    // otherwise the dialog is dead to the d-pad. Submitting itself has nothing
    // to focus, so it is skipped.
    //
    // Bounded, via the shared adapter. The loop this replaces was `while
    // (!overlayHasFocus)` with no exit: on the empty state, where nothing at all
    // was focusable, it re-requested a target that was not in the tree every
    // 60 ms for as long as the dialog stayed open. Bounding it alone would not
    // have saved that state — with no focus target in the tree the traversal
    // fallback has nothing to find either. The Close row below is what makes
    // the empty state recoverable; the bound is what stops the spinning.
    //
    // Keyed on the shape of the focus graph, not on the phase value. Two
    // separate reasons:
    //   - Running carries a progress percentage that changes several times a
    //     second, so keying on the phase itself would restart acquisition
    //     throughout the job.
    //   - Phase alone is not enough. Track availability is derived from the
    //     player's session tracks and can change under an open dialog, which
    //     swaps the empty state for the picker form (or back) without the phase
    //     moving at all. That removes the focused row and composes new ones, so
    //     it has to re-key or the dialog goes dead in place.
    // Row *enablement* deliberately does not appear here: a quota-exhausted
    // submit row stays focusable and only refuses to act, so it never strands
    // focus.
    val bodyKey = when {
        aiState.phase is AiJobPhase.Running -> "running"
        aiState.phase == AiJobPhase.Submitting -> "submitting"
        !subtitlesAvailable && !audioAvailable -> "form-empty"
        // Both modes available adds the Mode row, which is where firstRowFocus
        // attaches; with one mode it moves to the source row instead.
        subtitlesAvailable && audioAvailable -> "form-both-modes"
        else -> "form-single-mode"
    }
    val initialFocusModifier = rememberTvDialogInitialFocus(
        target = firstRowFocus,
        reacquireKey = bodyKey,
        enabled = aiState.phase !is AiJobPhase.Submitting,
    )
    LaunchedEffect(aiState.completedNonce) {
        if (aiState.completedNonce != initialNonce) onDismiss()
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .background(color = DarkBackground.copy(alpha = 0.68f), shape = panelShape)
                    .border(0.6.dp, Color.White.copy(alpha = 0.20f), panelShape)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .then(initialFocusModifier),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "TRANSLATE WITH AI",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 16.sp,
                        letterSpacing = 1.1.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                when (val phase = aiState.phase) {
                    is AiJobPhase.Running -> {
                        TvAiJobProgress(
                            progress = phase.progress,
                            message = phase.message,
                            onCancel = onCancelJob,
                            cancelFocus = firstRowFocus,
                        )
                    }
                    AiJobPhase.Submitting -> {
                        Text(
                            text = "Submitting…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                    is AiJobPhase.Failed, AiJobPhase.Idle -> {
                        if (!subtitlesAvailable && !audioAvailable) {
                            // Neither mode usable: AI configured but no
                            // translatable text tracks and transcription
                            // unavailable — explanatory empty state.
                            Text(
                                text = "No translatable subtitle tracks, and audio " +
                                    "transcription is not available on this server.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.66f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            )
                            // The empty state used to render explanatory text and
                            // nothing else: no focusable, so `firstRowFocus` was
                            // attached to nothing and the dialog opened with the
                            // d-pad dead and no visible way out. Back dismissed it,
                            // but nothing on screen said so.
                            TvDialogActionRow(
                                title = "Close",
                                onClick = onDismiss,
                                modifier = Modifier.focusRequester(firstRowFocus),
                            )
                        } else {
                            if (subtitlesAvailable && audioAvailable) {
                                TvDialogCyclerRow(
                                    title = "Mode",
                                    value = mode.label,
                                    onPrevious = {
                                        mode = if (mode == TvAiTranslateMode.Subtitles) {
                                            TvAiTranslateMode.Audio
                                        } else {
                                            TvAiTranslateMode.Subtitles
                                        }
                                    },
                                    onNext = {
                                        mode = if (mode == TvAiTranslateMode.Subtitles) {
                                            TvAiTranslateMode.Audio
                                        } else {
                                            TvAiTranslateMode.Subtitles
                                        }
                                    },
                                    modifier = Modifier
                                        .focusRequester(firstRowFocus)
                                        .semantics { contentDescription = "Mode, ${mode.label}" },
                                )
                            }

                            val sourceFocusModifier =
                                if (!(subtitlesAvailable && audioAvailable)) {
                                    Modifier.focusRequester(firstRowFocus)
                                } else {
                                    Modifier
                                }
                            if (mode == TvAiTranslateMode.Subtitles) {
                                val pos = subtitleSourcePos.coerceIn(0, subtitleSources.lastIndex)
                                val sourceLabel = subtitleSourceLabel(subtitleSources[pos], pos)
                                TvDialogCyclerRow(
                                    title = "Source subtitle",
                                    value = sourceLabel,
                                    onPrevious = {
                                        subtitleSourcePos =
                                            (pos - 1 + subtitleSources.size) % subtitleSources.size
                                    },
                                    onNext = {
                                        subtitleSourcePos = (pos + 1) % subtitleSources.size
                                    },
                                    modifier = sourceFocusModifier.semantics {
                                        contentDescription = "Source subtitle, $sourceLabel"
                                    },
                                )
                            } else {
                                val pos = audioSourcePos.coerceIn(0, audioSources.lastIndex)
                                val sourceLabel = audioChoiceLabel(audioSources[pos], pos)
                                TvDialogCyclerRow(
                                    title = "Source audio",
                                    value = sourceLabel,
                                    onPrevious = {
                                        audioSourcePos =
                                            (pos - 1 + audioSources.size) % audioSources.size
                                    },
                                    onNext = { audioSourcePos = (pos + 1) % audioSources.size },
                                    modifier = sourceFocusModifier.semantics {
                                        contentDescription = "Source audio, $sourceLabel"
                                    },
                                )
                            }

                            val targetLanguageLabel =
                                tvLanguageDisplayName(TvSubtitleLanguageOptions[targetPos])
                            TvDialogCyclerRow(
                                title = "Target language",
                                value = targetLanguageLabel,
                                onPrevious = {
                                    targetPos = (targetPos - 1 + TvSubtitleLanguageOptions.size) %
                                        TvSubtitleLanguageOptions.size
                                },
                                onNext = {
                                    targetPos = (targetPos + 1) % TvSubtitleLanguageOptions.size
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Target language, $targetLanguageLabel"
                                },
                            )

                            // Quota applies to transcribe kinds only; admins are
                            // exempt (limited=false → no line).
                            val quotaExhausted = mode == TvAiTranslateMode.Audio &&
                                aiState.quota?.limited == true &&
                                (aiState.quota.remaining) <= 0
                            if (mode == TvAiTranslateMode.Audio &&
                                aiState.quota?.limited == true
                            ) {
                                val q = aiState.quota
                                Text(
                                    text = if (quotaExhausted) {
                                        "Transcription quota exhausted (${q.used} of ${q.limit} used ${quotaPeriodText(q.period)})"
                                    } else {
                                        "${q.remaining} of ${q.limit} transcriptions left ${quotaPeriodText(q.period)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (quotaExhausted) {
                                        Color(0xFFF59E0B)
                                    } else {
                                        Color.White.copy(alpha = 0.66f)
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }

                            if (phase is AiJobPhase.Failed) {
                                Text(
                                    text = phase.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFEF4444),
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }

                            TvDialogActionRow(
                                title = if (mode == TvAiTranslateMode.Subtitles) {
                                    "Translate"
                                } else {
                                    "Transcribe"
                                },
                                enabled = !quotaExhausted,
                                onClick = {
                                    if (phase is AiJobPhase.Failed) onClearError()
                                    val targetLanguage = TvSubtitleLanguageOptions[targetPos]
                                    if (mode == TvAiTranslateMode.Subtitles) {
                                        val src = subtitleSources[
                                            subtitleSourcePos.coerceIn(0, subtitleSources.lastIndex),
                                        ]
                                        // source_index = the session's combined
                                        // subtitle index (PlayerSubtitleInfo.index),
                                        // NOT the ExoPlayer text-group ordinal.
                                        onSubmit("translate", src.index, src.language, targetLanguage)
                                    } else {
                                        val src = audioSources[
                                            audioSourcePos.coerceIn(0, audioSources.lastIndex),
                                        ]
                                        val sameLanguage = src.language
                                            ?.take(2)
                                            ?.equals(targetLanguage.take(2), ignoreCase = true) == true
                                        onSubmit(
                                            if (sameLanguage) "transcribe" else "transcribe_translate",
                                            src.index,
                                            src.language,
                                            targetLanguage,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** In-dialog job progress: percent bar + server progress_message + Cancel row. */
@Composable
private fun TvAiJobProgress(
    progress: Double,
    message: String?,
    onCancel: () -> Unit,
    cancelFocus: FocusRequester,
) {
    val fraction = progress.coerceIn(0.0, 1.0).toFloat()

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Translating… ${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
        )
        // Determinate bar — plain Boxes to keep the TV dialog idiom (no
        // Material phone widgets beyond what the player already uses).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.14f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.92f)),
            )
        }
        message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.66f),
            )
        }
        TvDialogActionRow(
            title = "Cancel",
            onClick = onCancel,
            modifier = Modifier.focusRequester(cancelFocus),
        )
    }
}

// Same label builder as the HUD/quick pickers ("Danish SRT (External)") so
// the translate dialog names tracks consistently; position is the list
// position, not the server combined index (which numbered fallbacks wrongly).
private fun subtitleSourceLabel(info: PlayerSubtitleInfo, position: Int): String =
    subtitleChoiceLabel(info, position)

private fun quotaPeriodText(period: String): String = when (period.lowercase()) {
    "day" -> "today"
    "week" -> "this week"
    "month" -> "this month"
    else -> "this $period"
}
