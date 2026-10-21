package org.siloserver.silo.common.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis

/**
 * A countdown pill's fill, the intro and buffering pills' alike: it creeps
 * from 0 to 1 as [secondsRemaining] of [totalSeconds] run out, lands full as
 * the controller's timer ends, and holds while [timerRunning] is false (a
 * pause froze the timer). A null [secondsRemaining], no pill, resets it.
 *
 * The fill shows time remaining, so it runs off the frame clock rather than
 * an AnimationSpec, whose duration the device's animator scale would stretch
 * until the bar disagreed with the timer. It is deliberately not keyed on
 * [secondsRemaining]: a tick must not restart the sweep. [countdownRun],
 * bumped when the controller's timer (re)starts, is what says the clock moved.
 */
@Composable
fun rememberNoticeCountdownFill(
    secondsRemaining: Int?,
    totalSeconds: Int,
    countdownRun: Int,
    timerRunning: Boolean,
): FloatState {
    val fill = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(countdownRun, timerRunning, totalSeconds, secondsRemaining == null) {
        if (secondsRemaining == null || totalSeconds <= 0) {
            fill.floatValue = 0f
            return@LaunchedEffect
        }
        val remaining = secondsRemaining.coerceAtLeast(1)
        val from = (1f - remaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        fill.floatValue = from
        // Frozen: the bar sits at the fraction the frozen number describes.
        if (!timerRunning) return@LaunchedEffect
        val durationMs = remaining * 1000f
        val startedAt = withFrameMillis { it }
        var progressed = 0f
        while (progressed < 1f) {
            val frameMs = withFrameMillis { it }
            progressed = ((frameMs - startedAt) / durationMs).coerceIn(0f, 1f)
            fill.floatValue = from + (1f - from) * progressed
        }
    }
    return fill
}
