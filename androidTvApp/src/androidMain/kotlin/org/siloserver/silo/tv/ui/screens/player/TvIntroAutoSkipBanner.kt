package org.siloserver.silo.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.domain.player.IntroAutoSkipState

/**
 * TV variant of the phone's `IntroAutoSkipBanner`. Larger touch targets (TV
 * scale), focus-driven instead of touch-driven, and a focus ring on the
 * actionable controls.
 *
 * The countdown button's background fill creeps left-to-right while we are in
 * [IntroAutoSkipState.CountingDown]; it auto-focuses (after two frames, so the
 * focus system is ready) so D-pad Select skips immediately. Back dismisses the
 * banner for this intro; moving focus off the button cancels the timer and
 * leaves the solid manual pill in place.
 *
 * The component itself never positions itself; the parent should anchor it
 * (typically bottom-end above the transport cluster).
 */
@Composable
fun TvIntroAutoSkipBanner(
    state: IntroAutoSkipState,
    onSkipNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    onDismissCountdown: () -> Unit,
    modifier: Modifier = Modifier,
    totalSeconds: Int = 5,
) {
    // Diagnostic for the fill-sweep timing bug: proves whether this whole
    // banner (and its remembered `fill` Animatable below) leaves and
    // re-enters composition mid-countdown, e.g. via the caller's
    // `!hudOpen && !showNextUp` gate flickering.
    // Key on the state kind: per-second CountingDown ticks then recompose this
    // slot instead of recreating the subtree (which would restart the drain).
    val slot = when (state) {
        IntroAutoSkipState.Hidden -> 0
        IntroAutoSkipState.ShowingButton -> 1
        is IntroAutoSkipState.CountingDown -> 2
    }
    // The manual pill auto-focuses when it appears fresh, but not when it
    // appears because the countdown was cancelled — that would yank focus
    // straight back from wherever the user just moved it.
    val previousSlot = remember { mutableStateOf(-1) }
    LaunchedEffect(slot) { previousSlot.value = slot }
    // Driven off the frame clock, not an AnimationSpec, on purpose: this bar
    // reports wall-clock time remaining before the auto-skip fires, and Compose
    // scales spec durations by the device's animator_duration_scale via
    // MotionDurationScale. Measured on a Shield set to 0.5x, a 5s tween finished
    // in 2508ms and the fill sat full for the last 2.5s; at scale 0 it would
    // jump straight to full. withFrameMillis is unscaled, so progress here
    // always matches real elapsed time. Decorative transitions below still
    // honor the setting.
    val fill = remember { mutableFloatStateOf(0f) }
    val secondsRemaining = (state as? IntroAutoSkipState.CountingDown)?.secondsRemaining
    val countdownStart = remember(secondsRemaining == null) { secondsRemaining }
    LaunchedEffect(countdownStart, totalSeconds) {
        if (countdownStart == null || totalSeconds <= 0) {
            fill.floatValue = 0f
            return@LaunchedEffect
        }
        val remaining = countdownStart.coerceAtLeast(1)
        val from = (1f - remaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        val durationMs = remaining * 1000f
        fill.floatValue = from
        val startedAt = withFrameMillis { it }
        var progressed = 0f
        while (progressed < 1f) {
            val frameMs = withFrameMillis { it }
            progressed = ((frameMs - startedAt) / durationMs).coerceIn(0f, 1f)
            fill.floatValue = from + (1f - from) * progressed
        }
    }

    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            // Instant exit and no SizeTransform: the default shrinks the pill
            // into nothing, which reads as a "minimize" after a skip press.
            fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                ExitTransition.None using null
        },
        label = "tvIntroAutoSkipBanner",
        modifier = modifier,
    ) { currentSlot ->
        when (currentSlot) {
            0 -> {
                // Render nothing but stay in the layout slot so AnimatedContent can fade in/out.
                Spacer(Modifier.size(0.dp))
            }
            1 -> {
                TvSkipIntroButton(
                    onClick = onSkipNow,
                    autoFocus = previousSlot.value != 2,
                )
            }
            else -> {
                TvShrinkingFillButton(
                    progress = fill.floatValue,
                    onSkipNow = onSkipNow,
                    onDismiss = onDismissCountdown,
                )
            }
        }
    }
}

/**
 * The "Skip Intro" pill — focusable, white background, black text. Focus ring:
 * 2dp white border + 8% white scrim wash to read clearly against the dimmed
 * gradient scrim of the player overlay.
 */
@Composable
private fun TvSkipIntroButton(
    onClick: () -> Unit,
    autoFocus: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    val shouldFocus = remember { autoFocus }
    LaunchedEffect(Unit) {
        if (!shouldFocus) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.25f)
    val containerColor = if (isFocused) Color.White else Color.Black.copy(alpha = 0.65f)
    val labelColor = if (isFocused) Color.Black else Color.White.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(containerColor, shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 32.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Skip Intro",
            color = labelColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Countdown variant: the button's background fill is the timer, creeping
 * left-to-right and landing full as the auto-skip fires. [progress] is driven by
 * the banner so it tracks the live countdown even if this button composes late.
 * Select and D-pad handling live in the player screen's root key handler,
 * because this button is not reliably in the focus tree; Back still works here
 * since it routes through the activity back dispatcher rather than focus.
 */
@Composable
private fun TvShrinkingFillButton(
    progress: Float,
    onSkipNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    BackHandler(onBack = onDismiss)

    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.65f), shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interactionSource, indication = null) { onSkipNow() },
    ) {
        // matchParentSize sizes the fill layer to the pill; plain fillMaxWidth /
        // fillMaxHeight here would grab the screen's max constraints and balloon it.
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        if (isFocused) {
                            Color.White.copy(alpha = 0.40f)
                        } else {
                            Color.White.copy(alpha = 0.14f)
                        },
                    ),
            )
        }
        Text(
            text = "Skip Intro",
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.55f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
        )
    }
}
