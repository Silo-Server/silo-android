package org.siloserver.silo.android.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withTimeoutOrNull

/** Press must be held this long before the 2x hold-speed engages. */
private const val HOLD_SPEED_THRESHOLD_MS = 500L

/**
 * Gesture handler overlay for the video player.
 *
 * Supported gestures:
 * - Single tap center: toggle controls visibility
 * - Double-tap left third: skip back 10 seconds
 * - Double-tap right third: skip forward 10 seconds
 * - Press-and-hold (≥0.5s): temporary 2x playback speed until release
 * - Vertical swipe on left half: brightness adjustment
 * - Vertical swipe on right half: volume adjustment
 * - Horizontal swipe: seek through the video
 * - Two-finger pinch: cycle video gravity (fit / fill / stretch)
 */
@Composable
fun PlayerGestureHandler(
    position: Double,
    duration: Double,
    onToggleControls: () -> Unit,
    onSeek: (Double) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onHoldSpeedStart: () -> Unit,
    onHoldSpeedEnd: () -> Unit,
    onPinchVideoGravity: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Keep latest position/duration available inside long-lived gesture coroutines
    // without re-keying pointerInput (re-keying on every position tick — ~every 500ms —
    // tears down the coroutine and drops in-flight taps and double-taps).
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val currentOnHoldSpeedStart by rememberUpdatedState(onHoldSpeedStart)
    val currentOnHoldSpeedEnd by rememberUpdatedState(onHoldSpeedEnd)
    val currentOnPinchVideoGravity by rememberUpdatedState(onPinchVideoGravity)

    var seekDragStartPosition by remember { mutableDoubleStateOf(0.0) }
    var seekDragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        val thirdWidth = size.width / 3f
                        when {
                            offset.x < thirdWidth -> onSkipBackward()
                            offset.x > thirdWidth * 2 -> onSkipForward()
                            else -> onToggleControls()
                        }
                    },
                    // Hold-to-2x: a press that outlives the threshold (without
                    // being cancelled by a drag) engages temporary 2x playback
                    // until the finger lifts. onPress doesn't consume, so taps,
                    // double-taps, and the drag detectors are unaffected.
                    onPress = {
                        val released = withTimeoutOrNull(HOLD_SPEED_THRESHOLD_MS) { tryAwaitRelease() }
                        if (released == null) {
                            currentOnHoldSpeedStart()
                            try {
                                tryAwaitRelease()
                            } finally {
                                // Runs on release AND on cancellation (a drag
                                // stealing the press), so speed always restores.
                                currentOnHoldSpeedEnd()
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    val isLeftHalf = change.position.x < size.width / 2f
                    val sensitivity = 0.01f

                    if (isLeftHalf) {
                        adjustBrightness(context, -dragAmount * sensitivity)
                    } else {
                        adjustVolume(audioManager, -dragAmount * sensitivity)
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        seekDragStartPosition = currentPosition
                        seekDragAccumulator = 0f
                    },
                    onDragEnd = {
                        val dur = currentDuration
                        val seekAmount = (seekDragAccumulator / size.width.toFloat()) * dur.toFloat() * 0.5f
                        val newPosition = (seekDragStartPosition + seekAmount).coerceIn(0.0, dur)
                        onSeek(newPosition)
                        seekDragAccumulator = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        seekDragAccumulator += dragAmount
                    },
                )
            }
            .pointerInput(Unit) {
                // Pinch to cycle video gravity. Only multi-touch frames are
                // consumed, so single-finger taps/double-taps/swipes pass
                // through to the detectors above untouched.
                awaitEachGesture {
                    var zoom = 1f
                    var sawPinch = false
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            sawPinch = true
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                    if (sawPinch) {
                        when {
                            zoom > 1.25f -> currentOnPinchVideoGravity(1)
                            zoom < 0.8f -> currentOnPinchVideoGravity(-1)
                        }
                    }
                }
            }
    )
}

/**
 * Adjusts the screen brightness. Values are clamped to [0.01, 1.0].
 * Uses the window's layout params for per-activity brightness control.
 */
private fun adjustBrightness(context: Context, delta: Float) {
    val activity = context as? android.app.Activity ?: return
    val window: Window = activity.window
    val layoutParams = window.attributes
    val currentBrightness = if (layoutParams.screenBrightness < 0) 0.5f else layoutParams.screenBrightness
    val newBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
    layoutParams.screenBrightness = newBrightness
    window.attributes = layoutParams
}

/**
 * Adjusts the media volume. Delta is normalized, so we scale to the max volume.
 */
private fun adjustVolume(audioManager: AudioManager, delta: Float) {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val volumeStep = (delta * maxVolume).toInt()
    val newVolume = (currentVolume + volumeStep).coerceIn(0, maxVolume)
    if (newVolume != currentVolume) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
    }
}
