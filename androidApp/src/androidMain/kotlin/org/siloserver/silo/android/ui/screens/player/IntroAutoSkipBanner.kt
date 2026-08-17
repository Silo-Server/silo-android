package org.siloserver.silo.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.R
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.player.IntroAutoSkipState

/**
 * The phone's intro-skip pill — the same state machine and copy the TV renders,
 * with pointer rules instead of focus rules.
 *
 *  - [IntroAutoSkipState.Hidden]: takes no space (caller can leave the slot composed).
 *  - [IntroAutoSkipState.Asking]: "Skip Intro"; tap seeks past the intro.
 *  - [IntroAutoSkipState.Skipped]: a small "Intro skipped" caption over a
 *    "Watch Intro" button; tap plays it after all.
 *
 * A fill creeps left-to-right behind the label and lands full exactly as the
 * timer ends. Tap is Select; a tap outside the pill is not Back. Back itself is
 * handled by the player overlay, not here.
 *
 * The component never positions itself; the parent should anchor it (typically
 * bottom-end of the player overlay).
 */
@Composable
fun IntroAutoSkipBanner(
    state: IntroAutoSkipState,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    totalSeconds: Int = IntroAutoSkipController.DEFAULT_COUNTDOWN_SECONDS,
    /** Bumped by the controller when the timer (re)starts; re-anchors the fill. */
    countdownRun: Int = 0,
    /** False while the timer is frozen by a pause — the fill holds where it is. */
    timerRunning: Boolean = true,
) {
    // The fill shows time remaining, so it runs off the frame clock rather than
    // an AnimationSpec, which the system animator duration scale would stretch.
    val fill = remember { mutableFloatStateOf(0f) }
    val secondsRemaining = state.secondsRemainingOrNull
    // Deliberately not keyed on `secondsRemaining`: a tick must not restart the
    // sweep. `countdownRun` is what says the clock moved.
    LaunchedEffect(countdownRun, timerRunning, totalSeconds, secondsRemaining == null) {
        if (secondsRemaining == null || totalSeconds <= 0) {
            fill.floatValue = 0f
            return@LaunchedEffect
        }
        val remaining = secondsRemaining.coerceAtLeast(1)
        val from = (1f - remaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        fill.floatValue = from
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

    // Keyed on the state kind so per-second ticks recompose the slot rather than
    // recreating the subtree, which would restart the fill.
    val slot = when (state) {
        IntroAutoSkipState.Hidden -> 0
        is IntroAutoSkipState.Asking -> 1
        is IntroAutoSkipState.Skipped -> 2
    }
    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 180)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 180))
        },
        label = "introAutoSkipBanner",
        modifier = modifier,
    ) { current ->
        when (current) {
            0 -> {
                // Render nothing but stay in the layout slot so AnimatedContent can fade in/out.
                Spacer(Modifier.size(0.dp))
            }
            1 -> IntroPromptPill(
                label = stringResource(R.string.intro_skip_pill_skip),
                icon = Icons.Filled.SkipNext,
                progress = fill.floatValue,
                onClick = onSelect,
            )
            else -> IntroPromptPill(
                label = stringResource(R.string.intro_skip_pill_undo),
                caption = stringResource(R.string.intro_skip_pill_undo_caption),
                icon = Icons.Filled.Replay,
                progress = fill.floatValue,
                onClick = onSelect,
            )
        }
    }
}

/**
 * The capsule both copies share: black scrim, white label, and a fill that
 * tracks the timer behind it. A plain button — hover and tap are Select. An
 * optional [caption] sits above the capsule, outside the tap target, so the
 * confirmation never reads as part of the action.
 */
@Composable
private fun IntroPromptPill(
    label: String,
    icon: ImageVector,
    progress: Float,
    onClick: () -> Unit,
    caption: String? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    Column(horizontalAlignment = Alignment.End) {
        if (caption != null) {
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 10.dp, bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.65f), shape)
                .clickable(onClick = onClick),
        ) {
            // matchParentSize so the fill takes the pill's bounds, not the screen's.
            Box(Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.22f)),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
