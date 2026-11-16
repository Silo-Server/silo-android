package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.LeftToRight
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.catalog.VersionChapter

/**
 * Apple's `AudioScrubberSection`: a slim whole-book capsule with an
 * accent-gradient fill and a tick at each chapter after the first, then
 * elapsed and remaining time. Dragging thickens the track, previews the
 * target in the time labels, ticks the haptics as it crosses a chapter, and
 * seeks once on release. [onSeek] receives whole-book seconds.
 */
@Composable
fun ChapterProgressBar(
    chapters: List<VersionChapter>,
    positionSeconds: Double,
    durationSeconds: Double,
    accent: Color,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubTime by remember { mutableStateOf<Double?>(null) }
    val displayed = scrubTime ?: positionSeconds
    val duration = durationSeconds.coerceAtLeast(0.0)
    val fraction = if (duration > 0) (displayed / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val trackHeight by animateDpAsState(
        targetValue = if (scrubTime == null) 6.dp else 10.dp,
        animationSpec = tween(150),
        label = "audiobookScrubberHeight",
    )
    // The first chapter opens the book, so it gets no tick.
    val tickTimes = remember(chapters) { chapters.drop(1).map { it.startSeconds } }
    val haptics = LocalHapticFeedback.current
    val currentDuration by rememberUpdatedState(duration)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val remaining = (duration - displayed).coerceAtLeast(0.0)
    val positionLabel = stringResource(CommonR.string.player_progress_position)
    val positionState = stringResource(
        CommonR.string.player_progress_positionState,
        formatClockTime(displayed),
        formatClockTime(duration),
    )

    LeftToRight {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = positionLabel
                    stateDescription = positionState
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        currentOnSeek(target.coerceIn(0f, 1f) * currentDuration)
                        true
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(tickTimes) {
                        fun timeAt(x: Float): Double =
                            (x / size.width).coerceIn(0f, 1f) * currentDuration
                        fun moveTo(x: Float) {
                            val previous = scrubTime
                            val next = timeAt(x)
                            if (previous != null && tickTimes.any { (it - previous) * (it - next) < 0 }) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            scrubTime = next
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (currentDuration <= 0) return@awaitEachGesture
                            scrubTime = timeAt(down.position.x)
                            drag(down.id) { change ->
                                change.consume()
                                moveTo(change.position.x)
                            }
                            scrubTime?.let(currentOnSeek)
                            scrubTime = null
                        }
                    },
            ) {
                val height = trackHeight.toPx()
                val top = (size.height - height) / 2
                val radius = CornerRadius(height / 2, height / 2)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, height),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.75f), accent)),
                    topLeft = Offset(0f, top),
                    size = Size(maxOf(height, size.width * fraction), height),
                    cornerRadius = radius,
                )
                if (duration > 0) {
                    val tickWidth = 1.5.dp.toPx()
                    tickTimes.forEach { start ->
                        drawRect(
                            color = Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset((size.width * (start / duration)).toFloat(), top),
                            size = Size(tickWidth, height),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val timeStyle = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = TabularNumbers,
                )
                Text(
                    text = formatClockTime(displayed),
                    style = timeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "-" + formatClockTime(remaining),
                    style = timeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Fixed-width digits, so the time labels don't jitter as they tick. */
internal const val TabularNumbers = "tnum"
