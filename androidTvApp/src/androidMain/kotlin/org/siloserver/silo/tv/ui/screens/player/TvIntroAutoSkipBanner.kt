package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.domain.player.IntroAutoSkipState

/**
 * TV variant of the phone's `IntroAutoSkipBanner`. Larger touch targets (TV
 * scale), focus-driven instead of touch-driven, and a focus ring on the
 * actionable controls.
 *
 * The Cancel button auto-focuses the moment we enter [IntroAutoSkipState.CountingDown]
 * so the user can press D-pad Select to cancel without first navigating to the
 * banner. While Cancel is focused, scrubber / transport focus is unaffected
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
                TvCountingDownPanel(
                    secondsRemaining = current.secondsRemaining,
                    totalSeconds = totalSeconds,
                    onCancel = onCancelCountdown,
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
 * Countdown panel — countdown ring + label + focusable Cancel. Cancel auto-focuses
 * on first emission so D-pad Select cancels immediately. Cancel uses a transparent
 * background with a white border for contrast against the dimmed gradient scrim.
 */
@Composable
private fun TvCountingDownPanel(
    secondsRemaining: Int,
    totalSeconds: Int,
    onCancel: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }

    // Auto-focus Cancel on the first frame this state is shown, so a D-pad
    // Select press cancels without user navigation. Re-fires whenever the
    // banner re-enters CountingDown after a cancel (the AnimatedContent
    // recomposes with a fresh subtree on every state transition — exactly when
    // the tree is least settled, which is why acquisition must be OBSERVED
    // rather than inferred from requestFocus() not throwing).
    val cancelFocusModifier = rememberTvContentInitialFocus(
        target = cancelFocus,
        contentKey = Unit,
    )

    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(28.dp),
    ) {
        Row(
            modifier = Modifier
                .then(cancelFocusModifier)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvCountdownRing(
                secondsRemaining = secondsRemaining,
                totalSeconds = totalSeconds,
            )
            Text(
                text = "Skipping intro",
                color = Color.White,
                fontSize = 16.sp,
            )
            TvCancelButton(
                focusRequester = cancelFocus,
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun TvCancelButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val shape = RoundedCornerShape(20.dp)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.6f)
    val containerScrim = if (isFocused) Color.White.copy(alpha = 0.10f) else Color.Transparent
    Box(
        modifier = Modifier
            .clip(shape)
            .background(containerScrim, shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .focusRequester(focusRequester)
            .focusable(enabled = true, interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Cancel",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 32dp circular countdown ring with the remaining digit centered. Drawn from
 * 0° (top) sweeping clockwise so the ring shrinks as the countdown progresses.
 */
@Composable
private fun TvCountdownRing(
    secondsRemaining: Int,
    totalSeconds: Int,
) {
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val strokeWidth = 3.dp.toPx()
            val sweep = if (totalSeconds <= 0) 0f
            else (secondsRemaining.coerceAtLeast(0).toFloat() / totalSeconds.toFloat()) * 360f
            val inset = strokeWidth / 2f
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = secondsRemaining.coerceAtLeast(0).toString(),
            color = Color.White,
            fontSize = 16.sp,
        )
    }
}
