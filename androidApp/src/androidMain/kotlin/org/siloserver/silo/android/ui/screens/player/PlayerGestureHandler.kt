package org.siloserver.silo.android.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.siloserver.silo.android.ui.components.SeekIntervalIcon
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Gesture handler overlay for the video player.
 *
 * Supported gestures:
 * - Single tap center: toggle controls visibility
 * - Double-tap left 35% zone: skip back by the video interval (with a flash badge)
 * - Double-tap right 35% zone: skip forward by the video interval (with a flash badge)
 * - Hold: temporary 2x playback while held
 * - Two-finger pinch: step video gravity — pinch-out steps Fit -> Fill ->
 *   Stretch, pinch-in steps back, clamped at both ends (iOS parity)
 * - Vertical swipe in the left edge zone: reserved; system brightness remains authoritative
 * - Vertical swipe in the right edge zone: volume adjustment, anchored to the
 *   level at touch-down, shown in a level HUD at the top (clear of the thumb)
 *   and ticked on every volume step
 * - Vertical swipe down in the center: dismiss the player (iOS
 *   MobilePlayerGestureLayer parity — evaluated on release, mostly-vertical
 *   drags over 140dp only; no velocity check). A "Pull down to close" hint
 *   turns into "Release to close" once releasing would dismiss.
 * Each gesture also answers with a haptic when it takes effect.
 * Large seeks are intentionally handled only by the visible timeline. This
 * matches Silo Apple and avoids an invisible full-screen gesture committing a
 * surprising seek while the user intended to dismiss or adjust an edge.
 */
@Composable
fun PlayerGestureHandler(
    onToggleControls: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    // When false (a Watch Party guest without seek authority), the outer
    // double-tap skip bands fall through to a controls toggle rather than
    // firing a gated no-op skip and flashing a "+10s" badge that lies about a
    // position that never moves. Mirrors PlayerControls' seekEnabled gating.
    seekEnabled: Boolean = true,
    // Resolved video intervals, shown in the double-tap flash badge.
    skipBackSeconds: Int = PlayerViewModel.LEGACY_VIDEO_SEEK_INTERVALS.backSeconds,
    skipForwardSeconds: Int = PlayerViewModel.LEGACY_VIDEO_SEEK_INTERVALS.forwardSeconds,
    onFastForwardHold: (Boolean) -> Unit = {},
    // Called once per completed pinch: true = pinch-out (step toward
    // fill/stretch), false = pinch-in (step back toward fit).
    onPinchVideoGravity: (Boolean) -> Unit = {},
    onDismiss: () -> Unit = {},
    // Swipe-down-to-dismiss is suppressed until playback is actually established.
    // During the initial open the media is still loading, and a downward edge
    // swipe that drifts inward would otherwise be read as "close the
    // player" — which then strands the user (Jim, Fold).
    dismissEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val currentSeekEnabled by rememberUpdatedState(seekEnabled)
    val currentSkipBackSeconds by rememberUpdatedState(skipBackSeconds)
    val currentSkipForwardSeconds by rememberUpdatedState(skipForwardSeconds)
    // The tap detector is created once (keyed on Unit): read the latest skip
    // actions, or a double-tap runs one built from an old composition.
    val currentOnSkipForward by rememberUpdatedState(onSkipForward)
    val currentOnSkipBackward by rememberUpdatedState(onSkipBackward)
    // Same reason as seekEnabled: the vertical-drag coroutine is created once
    // (keyed on Unit), so read the latest dismiss gate through rememberUpdatedState
    // — otherwise a value captured while buffering would stick after playback.
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)

    var suppressTapAfterFastForwardHold by remember { mutableStateOf(false) }

    // Double-tap skip feedback (iOS MobilePlayerGestureLayer skipFlash parity):
    // the badge appears instantly, holds 700ms, then fades out. The nonce makes
    // rapid re-taps restart the hold timer; the retained copy keeps content
    // rendered through the exit fade (same pattern as the Up Next card).
    var skipFlash by remember { mutableStateOf<SkipFlash?>(null) }
    var retainedSkipFlash by remember { mutableStateOf<SkipFlash?>(null) }
    var skipFlashNonce by remember { mutableLongStateOf(0L) }
    LaunchedEffect(skipFlash) {
        if (skipFlash != null) {
            retainedSkipFlash = skipFlash
            delay(SkipFlashHoldMs)
            skipFlash = null
        } else {
            delay(220)
            retainedSkipFlash = null
        }
    }

    // Volume HUD: shown while a right-edge drag is live, lingering briefly
    // after release so the final level can be read.
    var volumeDragActive by remember { mutableStateOf(false) }
    var volumeHudVisible by remember { mutableStateOf(false) }
    var volumeHudLevel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(volumeDragActive) {
        if (!volumeDragActive) {
            delay(VolumeHudLingerMs)
            volumeHudVisible = false
        }
    }

    // Swipe-down-to-dismiss hint: drag progress toward the threshold (0 = no
    // hint) and whether releasing now would close the player.
    var dismissProgress by remember { mutableFloatStateOf(0f) }
    var dismissArmed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // iOS videoGravityPinchGesture parity: accumulate the scale
                // over the whole gesture and evaluate ONCE on release — one
                // directional step per pinch, with a dead zone between the
                // pinch-in and pinch-out thresholds.
                awaitEachGesture {
                    var zoomAccumulator = 1f
                    var isPinch = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            isPinch = true
                            val first = pressed[0]
                            val second = pressed[1]
                            val previousDistance = pointerDistance(first.previousPosition, second.previousPosition)
                            val currentDistance = pointerDistance(first.position, second.position)
                            if (previousDistance > 0f && currentDistance > 0f) {
                                zoomAccumulator *= currentDistance / previousDistance
                            }
                            // Keep two-finger events away from the seek /
                            // brightness drag detectors below.
                            pressed.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (isPinch) {
                        val expand = when {
                            zoomAccumulator > PinchGravityThreshold -> true
                            zoomAccumulator < 1f / PinchGravityThreshold -> false
                            else -> null
                        }
                        if (expand != null) {
                            view.performGestureHaptic(GestureHaptic.PinchStep)
                            onPinchVideoGravity(expand)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        var holdTriggered = false
                        coroutineScope {
                            val holdJob = launch {
                                delay(500)
                                holdTriggered = true
                                view.performGestureHaptic(GestureHaptic.HoldStart)
                                onFastForwardHold(true)
                            }
                            try {
                                tryAwaitRelease()
                            } finally {
                                holdJob.cancel()
                                if (holdTriggered) {
                                    suppressTapAfterFastForwardHold = true
                                    onFastForwardHold(false)
                                }
                            }
                        }
                    },
                    onTap = {
                        if (suppressTapAfterFastForwardHold) {
                            suppressTapAfterFastForwardHold = false
                        } else {
                            onToggleControls()
                        }
                    },
                    onDoubleTap = { offset ->
                        suppressTapAfterFastForwardHold = false
                        // iOS skipZoneFraction (0.35): outer bands skip by the interval
                        // with a flash badge; the middle band keeps the
                        // controls toggle.
                        val skipZoneWidth = size.width * SkipZoneFraction
                        when {
                            // Guest without seek authority: no gated no-op skip,
                            // no lying flash — behave like the center band.
                            !currentSeekEnabled -> onToggleControls()
                            offset.x < skipZoneWidth -> {
                                currentOnSkipBackward()
                                view.performGestureHaptic(GestureHaptic.Skip)
                                skipFlash = SkipFlash(forward = false, seconds = currentSkipBackSeconds, nonce = ++skipFlashNonce)
                            }
                            offset.x > size.width - skipZoneWidth -> {
                                currentOnSkipForward()
                                view.performGestureHaptic(GestureHaptic.Skip)
                                skipFlash = SkipFlash(forward = true, seconds = currentSkipForwardSeconds, nonce = ++skipFlashNonce)
                            }
                            else -> onToggleControls()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // The start x picks the mode once: the left 88dp edge is
                // reserved so Android brightness stays authoritative, the
                // right edge controls volume, and the center is a dismiss
                // candidate judged on release.
                var mode = VerticalDragMode.None
                var totalDrag = Offset.Zero
                var volumeStartIndex = 0
                var volumeIndex = 0
                val edgeZonePx = EdgeZoneWidthDp.dp.toPx()
                val dismissThresholdPx = DismissDragThresholdDp.dp.toPx()
                fun endDrag() {
                    mode = VerticalDragMode.None
                    volumeDragActive = false
                    dismissProgress = 0f
                    dismissArmed = false
                }
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        totalDrag = Offset.Zero
                        mode = verticalDragMode(
                            startX = start.x,
                            width = size.width.toFloat(),
                            edgeZonePx = edgeZonePx,
                        )
                        if (mode == VerticalDragMode.Volume) {
                            volumeStartIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            volumeIndex = volumeStartIndex
                        }
                    },
                    onDragEnd = {
                        if (currentDismissEnabled && mode == VerticalDragMode.DismissCandidate &&
                            isDismissDrag(totalDrag.x, totalDrag.y, dismissThresholdPx)
                        ) {
                            onDismiss()
                        }
                        endDrag()
                    },
                    onDragCancel = { endDrag() },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        totalDrag += change.position - change.previousPosition
                        when (mode) {
                            VerticalDragMode.Volume -> {
                                // Map the whole drag (not each event) onto the
                                // volume range, so slow drags still move it and
                                // fast ones cannot jump.
                                val fullRangePx = minOf(
                                    size.height * VolumeFullRangeHeightFraction,
                                    VolumeFullRangeMaxDp.dp.toPx(),
                                )
                                val maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val newIndex = setVolumeForDrag(
                                    audioManager = audioManager,
                                    startIndex = volumeStartIndex,
                                    maxIndex = maxIndex,
                                    dragUpPx = -totalDrag.y,
                                    fullRangePx = fullRangePx,
                                )
                                if (newIndex != volumeIndex) {
                                    view.performGestureHaptic(
                                        if (newIndex == 0 || newIndex == maxIndex) GestureHaptic.VolumeLimit else GestureHaptic.VolumeStep,
                                    )
                                    volumeIndex = newIndex
                                }
                                volumeHudLevel = if (maxIndex > 0) newIndex.toFloat() / maxIndex else 0f
                                volumeDragActive = true
                                volumeHudVisible = true
                            }
                            VerticalDragMode.DismissCandidate -> if (currentDismissEnabled) {
                                val dy = totalDrag.y
                                val mostlyVertical = abs(totalDrag.x) < dy * DismissMaxHorizontalRatio
                                dismissProgress = if (dy > 0f && mostlyVertical) dy / dismissThresholdPx else 0f
                                val armed = isDismissDrag(totalDrag.x, dy, dismissThresholdPx)
                                if (armed != dismissArmed) {
                                    view.performGestureHaptic(
                                        if (armed) GestureHaptic.ThresholdActivate else GestureHaptic.ThresholdDeactivate,
                                    )
                                }
                                dismissArmed = armed
                            }
                            else -> Unit
                        }
                    },
                )
            },
    ) {
        AnimatedVisibility(
            visible = volumeHudVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200)),
            modifier = Modifier
                // Top center: the right-edge drag puts a thumb over anything
                // drawn on that side. Shares the slot with the dismiss hint,
                // which never shows during a volume drag.
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 24.dp),
        ) {
            VolumeHud(level = volumeHudLevel)
        }

        // Fades in over the first part of the drag so a short, stray swipe
        // stays quiet while a deliberate one shows what it is about to do.
        if (dismissProgress > DismissHintStartProgress) {
            DismissHint(
                armed = dismissArmed,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 24.dp)
                    .graphicsLayer {
                        alpha = ((dismissProgress - DismissHintStartProgress) / DismissHintFadeProgress).coerceIn(0f, 1f)
                    },
            )
        }

        // Double-tap skip flash badge — vertically centered in the tapped
        // half (iOS positions it at 18% / 82% of the width; the edge padding
        // approximates that). Non-interactive: it sits under no pointer
        // handlers of its own and gestures keep flowing to this Box.
        val flash = retainedSkipFlash
        if (flash != null) {
            AnimatedVisibility(
                visible = skipFlash != null,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(durationMillis = 200)),
                modifier = Modifier
                    .align(if (flash.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 56.dp),
            ) {
                SkipFlashBadge(forward = flash.forward, seconds = flash.seconds)
            }
        }
    }
}

/**
 * iOS `skipFlashView` parity: a 74dp circle with the circular-arrow skip
 * glyph above a "+10s"/"−10s" caption.
 */
@Composable
private fun SkipFlashBadge(forward: Boolean, seconds: Int) {
    Column(
        modifier = Modifier
            .size(74.dp)
            .background(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SeekIntervalIcon(
            forward = forward,
            seconds = seconds,
            contentDescription = if (forward) "Skipped forward $seconds seconds" else "Skipped back $seconds seconds",
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = if (forward) "+${seconds}s" else "−${seconds}s",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Horizontal level pill for the right-edge volume drag. */
@Composable
private fun VolumeHud(level: Float) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(PlayerChrome.Disc)
            .border(1.dp, PlayerChrome.DiscStroke, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when {
                level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                level < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = null,
            tint = PlayerChrome.Paper,
            modifier = Modifier.size(18.dp),
        )
        val barWidth = 140.dp
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PlayerChrome.Faint),
        ) {
            Box(
                modifier = Modifier
                    .width(barWidth * level.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(PlayerChrome.Paper),
            )
        }
        Text(
            text = "${(level * 100).roundToInt()}",
            style = PlayerType.Time,
            modifier = Modifier.width(26.dp),
        )
    }
}

@Composable
private fun DismissHint(armed: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (armed) PlayerChrome.Paper else PlayerChrome.Disc)
            .border(1.dp, PlayerChrome.DiscStroke, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (armed) PlayerChrome.Ink else PlayerChrome.Paper
        Icon(
            imageVector = if (armed) Icons.Filled.Close else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (armed) "Release to close" else "Pull down to close",
            style = PlayerType.PillLabel.copy(color = tint),
        )
    }
}

/** iOS pinch dead zone: >1.08 steps out, <1/1.08 steps in, between = no-op. */
private const val PinchGravityThreshold = 1.08f

/** iOS skipZoneFraction: outer double-tap bands (35% each side) skip. */
private const val SkipZoneFraction = 0.35f

/** iOS skip flash hold before the fade-out begins. */
private const val SkipFlashHoldMs = 700L

private data class SkipFlash(val forward: Boolean, val seconds: Int, val nonce: Long)

internal enum class VerticalDragMode { None, Volume, DismissCandidate }

/** Edge-zone width for the reserved left edge and right-edge volume drag. */
private const val EdgeZoneWidthDp = 88

internal fun verticalDragMode(
    startX: Float,
    width: Float,
    edgeZonePx: Float,
): VerticalDragMode = when {
    startX < edgeZonePx -> VerticalDragMode.None
    startX > width - edgeZonePx -> VerticalDragMode.Volume
    else -> VerticalDragMode.DismissCandidate
}

/** iOS dismiss threshold: a mostly-vertical downward drag over 140pt. */
private const val DismissDragThresholdDp = 140

/** Horizontal travel allowed per unit of downward travel for a dismiss. */
private const val DismissMaxHorizontalRatio = 0.6f

/** Fraction of the dismiss threshold travelled before the hint starts to appear. */
private const val DismissHintStartProgress = 0.2f

/** Further fraction of the threshold over which the hint fades fully in. */
private const val DismissHintFadeProgress = 0.3f

internal fun isDismissDrag(dx: Float, dy: Float, thresholdPx: Float): Boolean =
    dy > thresholdPx && abs(dx) < dy * DismissMaxHorizontalRatio

/**
 * A drag of this much of the player height (capped at [VolumeFullRangeMaxDp])
 * sweeps the full volume range.
 */
private const val VolumeFullRangeHeightFraction = 0.6f
private const val VolumeFullRangeMaxDp = 280

/** How long the volume HUD stays up after the finger lifts. */
private const val VolumeHudLingerMs = 700L

/**
 * The volume index a drag of [dragUpPx] (positive = up) reaches from
 * [startIndex], where [fullRangePx] of travel spans the whole 0..[maxIndex] range.
 */
internal fun volumeIndexForDrag(
    startIndex: Int,
    maxIndex: Int,
    dragUpPx: Float,
    fullRangePx: Float,
): Int {
    if (maxIndex <= 0 || fullRangePx <= 0f) return startIndex
    val steps = (dragUpPx / fullRangePx * maxIndex).roundToInt()
    return (startIndex + steps).coerceIn(0, maxIndex)
}

private fun pointerDistance(first: Offset, second: Offset): Float =
    hypot(first.x - second.x, first.y - second.y)

/**
 * Sets the media volume for a drag anchored at [startIndex] and returns the
 * resulting index. It is read back from the system, so the HUD and haptics
 * stay truthful when the system holds volume back (fixed-volume devices, the
 * headphone safe-volume limit).
 */
private fun setVolumeForDrag(
    audioManager: AudioManager,
    startIndex: Int,
    maxIndex: Int,
    dragUpPx: Float,
    fullRangePx: Float,
): Int {
    val target = volumeIndexForDrag(startIndex, maxIndex, dragUpPx, fullRangePx)
    if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
    return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
}

private enum class GestureHaptic {
    VolumeStep,
    VolumeLimit,
    Skip,
    HoldStart,
    PinchStep,
    ThresholdActivate,
    ThresholdDeactivate,
}

/**
 * Plays [haptic] through the view, so the system touch-feedback setting
 * applies. Newer gesture constants fall back to the closest older one.
 */
private fun View.performGestureHaptic(haptic: GestureHaptic) {
    val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val constant = when (haptic) {
        GestureHaptic.VolumeStep ->
            if (modern) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        GestureHaptic.VolumeLimit -> HapticFeedbackConstants.CONTEXT_CLICK
        GestureHaptic.Skip -> HapticFeedbackConstants.VIRTUAL_KEY
        GestureHaptic.HoldStart -> HapticFeedbackConstants.LONG_PRESS
        GestureHaptic.PinchStep -> HapticFeedbackConstants.CONTEXT_CLICK
        GestureHaptic.ThresholdActivate ->
            if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.LONG_PRESS
        GestureHaptic.ThresholdDeactivate ->
            if (modern) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else return
    }
    performHapticFeedback(constant)
}
