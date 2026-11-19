package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUp
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
 * elapsed and remaining time. A horizontal drag thickens the track, previews
 * the target in the time labels, ticks the haptics as it crosses a chapter,
 * and seeks once on release; a tap seeks where it lands, and a vertical swipe
 * is left to the scrolling page. With a keyboard or D-pad the track takes
 * focus (drawn thick) and Left and Right skip by the player's intervals, as
 * Apple's adjustable action does. [onSeek] receives whole-book seconds.
 */
@Composable
fun ChapterProgressBar(
    chapters: List<VersionChapter>,
    positionSeconds: Double,
    durationSeconds: Double,
    accent: Color,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var scrubTime by remember { mutableStateOf<Double?>(null) }
    val displayed = scrubTime ?: positionSeconds
    val duration = durationSeconds.coerceAtLeast(0.0)
    val fraction = if (duration > 0) (displayed / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val trackHeight by animateDpAsState(
        targetValue = if (scrubTime == null && !isFocused) 6.dp else 10.dp,
        animationSpec = tween(150),
        label = "audiobookScrubberHeight",
    )
    // The first chapter opens the book, so it gets no tick.
    val tickTimes = remember(chapters) { chapters.drop(1).map { it.startSeconds } }
    val haptics = LocalHapticFeedback.current
    val currentDuration by rememberUpdatedState(duration)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentPosition by rememberUpdatedState(positionSeconds)
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
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || currentDuration <= 0) return@onPreviewKeyEvent false
                        val step = when (event.key) {
                            Key.DirectionLeft -> -skipBackSeconds
                            Key.DirectionRight -> skipForwardSeconds
                            else -> return@onPreviewKeyEvent false
                        }
                        currentOnSeek((currentPosition + step).coerceIn(0.0, currentDuration))
                        true
                    }
                    .focusable(interactionSource = interactionSource)
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
                            // Claim the gesture only once it moves sideways past touch
                            // slop, so a vertical swipe stays with the scrolling page.
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (start == null) {
                                // Lifted before any sideways move: a tap seeks there. A
                                // gesture the page took over ends with no seek.
                                val lift = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (lift != null && lift.changedToUp() && !lift.isConsumed) {
                                    currentOnSeek(timeAt(down.position.x))
                                }
                                return@awaitEachGesture
                            }
                            scrubTime = timeAt(start.position.x)
                            val completed = horizontalDrag(start.id) { change ->
                                change.consume()
                                moveTo(change.position.x)
                            }
                            if (completed) scrubTime?.let(currentOnSeek)
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
