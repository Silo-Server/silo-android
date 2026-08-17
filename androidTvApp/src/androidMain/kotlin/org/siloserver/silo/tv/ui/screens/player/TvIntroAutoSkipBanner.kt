package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus

/**
 * TV variant of the phone's `IntroAutoSkipBanner` — the single intro-skip pill.
 *
 * Two copies, one treatment: "Skip Intro" while the `ask` offer is up, and a
 * small "Intro skipped" caption over a "Watch Intro" button while `always`'s
 * undo is — the confirmation and the action are separate lines so neither
 * has to read as the other. The fill tracks the
 * time left and lands full exactly as the timer ends. Select, Back and D-pad
 * are handled by the player screen's root key handler, not here, because the
 * pill is not reliably in the focus tree.
 *
 * The component itself never positions itself; the parent should anchor it
 * (typically bottom-end above the transport cluster).
 */
@Composable
fun TvIntroAutoSkipBanner(
    state: IntroAutoSkipState,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    totalSeconds: Int = IntroAutoSkipController.DEFAULT_COUNTDOWN_SECONDS,
    /**
     * Bumped by the controller whenever the timer (re)starts — a fresh offer,
     * or a resume after a pause froze it. The fill re-anchors its frame clock
     * on it, since [state] alone cannot tell a tick from a restart.
     */
    countdownRun: Int = 0,
    /**
     * False while the pill is up but the timer is frozen by a pause. The fill
     * holds where it is rather than continuing to creep — it is a promise about
     * when something happens, and while paused nothing is going to.
     */
    timerRunning: Boolean = true,
    /**
     * False while something else owns focus for a reason the viewer would not
     * want interrupted — a timeline scrub in particular.
     *
     * The scrubber treats losing focus as COMMIT, not cancel, so a prompt that
     * appears mid-scrub and claims focus commits a seek the viewer never
     * confirmed. The pill still appears and is still reachable; it simply
     * does not take focus out from under them.
     */
    mayTakeFocus: Boolean = true,
) {
    // Keyed on the state kind so the per-second ticks recompose this slot rather
    // than recreating the subtree, which would restart the fill.
    val slot = when (state) {
        IntroAutoSkipState.Hidden -> 0
        is IntroAutoSkipState.Asking -> 1
        is IntroAutoSkipState.Skipped -> 2
    }
    // The fill shows time remaining, so it runs off the frame clock: Compose
    // scales AnimationSpec durations by the device animation setting, which would
    // let the bar disagree with the timer. Transitions below still honor it.
    val fill = remember { mutableFloatStateOf(0f) }
    val secondsRemaining = state.secondsRemainingOrNull
    // Deliberately not keyed on `secondsRemaining`: a plain tick must not
    // restart the sweep. `countdownRun` is what says the clock moved.
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

    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            // Instant exit, no SizeTransform: the default shrink reads as the
            // button minimizing away after a skip press.
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
                TvIntroPromptPill(
                    label = stringResource(R.string.intro_skip_pill_skip),
                    progress = fill.floatValue,
                    onSelect = onSelect,
                    autoFocus = mayTakeFocus,
                )
            }
            else -> {
                TvIntroPromptPill(
                    label = stringResource(R.string.intro_skip_pill_undo),
                    caption = stringResource(R.string.intro_skip_pill_undo_caption),
                    progress = fill.floatValue,
                    onSelect = onSelect,
                    autoFocus = mayTakeFocus,
                )
            }
        }
    }
}

/**
 * The pill both copies share: black capsule, white focus ring, and a fill that
 * creeps left-to-right as the timer runs out. [progress] is driven by the
 * banner so it tracks the live timer even if this pill composes late. Dimmed
 * when unfocused, lit when focused. An optional [caption] sits above the
 * capsule, end-aligned and outside the focusable, so it never competes with
 * the action for the viewer's read.
 *
 * Select and Back live in the player screen's root key handler, because this
 * pill is not reliably in the focus tree.
 */
@Composable
private fun TvIntroPromptPill(
    label: String,
    progress: Float,
    onSelect: () -> Unit,
    autoFocus: Boolean,
    caption: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    // Captured once: a later recomposition must not re-claim focus the viewer
    // has since moved elsewhere.
    val shouldFocus = remember { autoFocus }
    val initialFocusModifier = rememberTvContentInitialFocus(
        target = focusRequester,
        contentKey = if (shouldFocus) Unit else null,
    )

    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.25f)
    Column(horizontalAlignment = Alignment.End) {
        if (caption != null) {
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 12.dp, bottom = 6.dp),
            )
        }
        Box(
            modifier = Modifier
                .then(initialFocusModifier)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.65f), shape)
                .border(BorderStroke(2.dp, borderColor), shape)
                .focusRequester(focusRequester)
                .clickable(interactionSource = interactionSource, indication = null) { onSelect() },
        ) {
            // Sized to the pill via matchParentSize; plain fillMaxWidth/Height would
            // take the screen's constraints instead.
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
                text = label,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.55f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
}
