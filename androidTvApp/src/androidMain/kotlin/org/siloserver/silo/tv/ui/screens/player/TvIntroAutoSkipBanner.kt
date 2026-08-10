package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.remember
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
 * The countdown button's background fill drains to empty the moment we enter
 * [IntroAutoSkipState.CountingDown]; it auto-focuses so the user can press
 * D-pad Select to skip without first navigating to the banner. While the
 * button is focused, scrubber / transport focus is unaffected
 * because the banner participates in the same focus tree as the rest of the
 * idle overlay — pressing arrow keys away will move focus back to scrubber /
 * play-pause.
 *
 * The component itself never positions itself; the parent should anchor it
 * (typically bottom-end above the transport cluster).
 */
@Composable
fun TvIntroAutoSkipBanner(
    state: IntroAutoSkipState,
    onSkipNow: () -> Unit,
    onCancelCountdown: () -> Unit,
    modifier: Modifier = Modifier,
    totalSeconds: Int = 5,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 200))
        },
        label = "tvIntroAutoSkipBanner",
        modifier = modifier,
    ) { current ->
        when (current) {
            IntroAutoSkipState.Hidden -> {
                // Render nothing but stay in the layout slot so AnimatedContent can fade in/out.
                Spacer(Modifier.size(0.dp))
            }
            IntroAutoSkipState.ShowingButton -> {
                TvSkipIntroButton(onClick = onSkipNow)
            }
            is IntroAutoSkipState.CountingDown -> {
                TvShrinkingFillButton(
                    secondsRemaining = current.secondsRemaining,
                    totalSeconds = totalSeconds,
                    onSkipNow = onSkipNow,
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
 * white fill drains left-to-right over the dark scrim; when it empties the
 * auto-skip fires. Auto-focuses on entry so D-pad Select skips immediately.
 * The fill animates smoothly between the controller's whole-second ticks via
 * [animateFloatAsState]. Fill stays translucent so the label reads at every
 * drain level.
 */
@Composable
private fun TvShrinkingFillButton(
    secondsRemaining: Int,
    totalSeconds: Int,
    onSkipNow: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val progress by animateFloatAsState(
        targetValue = if (totalSeconds <= 0) 0f
        else secondsRemaining.coerceIn(0, totalSeconds).toFloat() / totalSeconds.toFloat(),
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "tvIntroSkipFill",
    )

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
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = if (isFocused) 0.40f else 0.28f)),
        )
        Text(
            text = "Skip Intro",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
        )
    }
}
