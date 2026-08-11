package org.siloserver.silo.tv.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Key on the state kind: per-second CountingDown ticks then recompose this
    // slot instead of recreating the subtree (which would restart the drain).
    val slot = when (state) {
        IntroAutoSkipState.Hidden -> 0
        IntroAutoSkipState.ShowingButton -> 1
        is IntroAutoSkipState.CountingDown -> 2
    }
    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 200))
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
                TvSkipIntroButton(onClick = onSkipNow)
            }
            else -> {
                TvShrinkingFillButton(
                    secondsRemaining = (state as? IntroAutoSkipState.CountingDown)
                        ?.secondsRemaining ?: totalSeconds,
                    totalSeconds = totalSeconds,
                    onSkipNow = onSkipNow,
                    onCancel = onCancelCountdown,
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
private fun TvSkipIntroButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.Transparent
    val containerScrim = if (isFocused) Color.White.copy(alpha = 0.08f) else Color.Transparent
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color.White, shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .background(containerScrim, shape)
            .focusable(enabled = true, interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Skip Intro",
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Countdown variant: the button's background fill is the timer. A translucent
 * white fill creeps left-to-right over the dark scrim in one continuous
 * animation lasting the countdown's actual remaining seconds; when full, the
 * auto-skip fires. Auto-focuses on entry so D-pad Select skips immediately;
 * Back dismisses the banner for this intro; moving focus off cancels the timer
 * (the controller falls back to the solid manual pill). Fill stays translucent
 * so the label reads at every level.
 */
@Composable
private fun TvShrinkingFillButton(
    secondsRemaining: Int,
    totalSeconds: Int,
    onSkipNow: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Two frames before requesting focus: the request fails silently if it
    // lands before the node is laid out and focusable.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // Losing focus after having it means the user D-padded away: stop the
    // timer, leave the manual pill. Focus never gained (request failed) does
    // nothing.
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isFocused) {
        if (isFocused) hadFocus = true
        else if (hadFocus) onCancel()
    }

    BackHandler(onBack = onDismiss)

    // Duration comes from the countdown's own remaining seconds at entry, not
    // the configured total, so the fill always lands full exactly at the fire.
    val startRemaining = remember { secondsRemaining.coerceAtLeast(1) }
    val fill = remember {
        val initial = if (totalSeconds <= 0) 0f
        else 1f - startRemaining.toFloat() / totalSeconds.toFloat()
        Animatable(initial.coerceIn(0f, 1f))
    }
    LaunchedEffect(Unit) {
        fill.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = startRemaining * 1000,
                easing = LinearEasing,
            ),
        )
    }

    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.Transparent
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.65f), shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .focusRequester(focusRequester)
            .focusable(enabled = true, interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onSkipNow() },
    ) {
        // matchParentSize sizes the fill layer to the pill; plain fillMaxWidth /
        // fillMaxHeight here would grab the screen's max constraints and balloon it.
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fill.value)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = if (isFocused) 0.40f else 0.28f)),
            )
        }
        Text(
            text = "Skip Intro",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
        )
    }
}
