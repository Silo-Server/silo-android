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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus

/**
 * TV variant of the phone's `IntroAutoSkipBanner` — the single intro-skip pill.
 *
 * Two copies, one treatment: "Skip Intro" while the `ask` offer is up, and a
 * small "Intro skipped" caption over a "Watch Intro" button while `always`'s
 * undo is — the confirmation and the action are separate lines so neither
 * has to read as the other. No countdown is drawn; the pill goes when its
 * timer ends. Select, Back and D-pad are handled by the player screen's root
 * key handler, not here, because the pill is not reliably in the focus tree.
 *
 * The component itself never positions itself; the parent should anchor it
 * (typically bottom-end above the transport cluster).
 */
@Composable
fun TvIntroAutoSkipBanner(
    state: IntroAutoSkipState,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
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
    // Keyed on the state kind so the per-second ticks don't recreate the pill
    // (and replay its fade-in).
    val slot = when (state) {
        IntroAutoSkipState.Hidden -> 0
        is IntroAutoSkipState.Asking -> 1
        is IntroAutoSkipState.Skipped -> 2
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
                    label = stringResource(CommonR.string.introSkip_pill_skip),
                    onSelect = onSelect,
                    autoFocus = mayTakeFocus,
                )
            }
            else -> {
                TvIntroPromptPill(
                    label = stringResource(CommonR.string.introSkip_pill_undo),
                    caption = stringResource(CommonR.string.introSkip_pill_undoCaption),
                    onSelect = onSelect,
                    autoFocus = mayTakeFocus,
                )
            }
        }
    }
}

/**
 * The pill both copies share: black capsule with an outline, inverted to white
 * when focused (or [emphasized]). An optional [caption] sits above the
 * capsule, end-aligned and outside the focusable, so it never competes with
 * the action for the viewer's read.
 *
 * Select and Back live in the player screen's root key handler, because this
 * pill is not reliably in the focus tree.
 */
@Composable
private fun TvIntroPromptPill(
    label: String,
    onSelect: () -> Unit,
    autoFocus: Boolean,
    caption: String? = null,
    /** Drawn lit (white) at rest: the card's primary action, which OK reaches. */
    emphasized: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hasFocus by interactionSource.collectIsFocusedAsState()
    val isFocused = hasFocus || emphasized
    val focusRequester = remember { FocusRequester() }
    // Captured once: a later recomposition must not re-claim focus the viewer
    // has since moved elsewhere.
    val shouldFocus = remember { autoFocus }
    val initialFocusModifier = rememberTvContentInitialFocus(
        target = focusRequester,
        contentKey = if (shouldFocus) Unit else null,
    )

    // OK reaches the pill whether or not it holds focus, so it never looks
    // disabled: full-white label and a clear outline, and focus inverts it.
    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isFocused) Color.White else Color.White.copy(alpha = 0.6f)
    Column(horizontalAlignment = Alignment.End) {
        if (caption != null) {
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.85f),
                style = TvPlayerType.Row.copy(shadow = Shadow(Color.Black.copy(alpha = 0.75f), blurRadius = 8f)),
                modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .then(initialFocusModifier)
                .clip(shape)
                .background(if (isFocused) Color.White else Color.Black.copy(alpha = 0.72f), shape)
                .border(BorderStroke(2.dp, borderColor), shape)
                .focusRequester(focusRequester)
                .clickable(interactionSource = interactionSource, indication = null) { onSelect() },
        ) {
            // No countdown drawn: a fill read as a glitch and a line under the
            // label as unexplained; the pill simply goes when its time is up.
            Text(
                text = label,
                color = if (isFocused) Color.Black else Color.White,
                style = TvPlayerType.Prompt,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
}

/**
 * The recap/credits skip. With [upNext] (the episode after the credits) it sits
 * in an opaque card that names that episode, so the context reads over any
 * footage instead of floating on the picture.
 */
@Composable
fun TvMarkerSkipPill(
    label: String,
    onSelect: () -> Unit,
    upNext: String? = null,
    /** The countdown's lead, before [creditsLeft]: "Credits end in". */
    upNextTiming: String? = null,
    /** What the credits have left ("1:59"), the countdown the card leads with. */
    creditsLeft: String? = null,
    /** What happens then, after [creditsLeft]: "then it plays". */
    upNextAfter: String? = null,
    /**
     * "Not now", beside the primary action: this episode won't start the next
     * by itself (the auto-play setting stays). Reached with the controls up;
     * OK with them hidden runs the primary action.
     */
    onNotNow: (() -> Unit)? = null,
) {
    // When the card turns into the plain pill while one of its actions has
    // focus ("Not now"), the pill takes that focus, so the control that has
    // it stays obvious. Read when the pill is first composed, before the
    // card's nodes leave the focus tree.
    var cardHasFocus by remember { mutableStateOf(false) }
    if (upNext == null) {
        TvIntroPromptPill(label = label, onSelect = onSelect, autoFocus = cardHasFocus)
        return
    }
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier
            .onFocusChanged { cardHasFocus = it.hasFocus }
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF0101013))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(20.dp))
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Text(
            text = stringResource(CommonR.string.upNext_heading).uppercase(),
            style = TvPlayerType.ButtonLabel,
            color = Color.White.copy(alpha = 0.7f),
            letterSpacing = 1.4.sp,
        )
        Text(
            text = upNext,
            color = Color.White,
            style = TvPlayerType.Prompt,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp).widthIn(max = 380.dp),
        )
        if (upNextTiming != null) {
            // One sentence with the countdown set in it: when the credits end,
            // and what happens then.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(upNextTiming, color = Color.White.copy(alpha = 0.85f), style = TvPlayerType.Body)
                if (creditsLeft != null) {
                    Text(
                        text = creditsLeft,
                        color = Color.White,
                        // Tabular, so the chip keeps its width as it counts down.
                        style = TvPlayerType.Prompt.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (upNextAfter != null) {
                    Text(
                        text = "· $upNextAfter",
                        color = Color.White.copy(alpha = 0.85f),
                        style = TvPlayerType.Body,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // The primary action is lit; the auto-play switch is a quiet text action.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvIntroPromptPill(label = label, onSelect = onSelect, autoFocus = false, emphasized = true)
            if (onNotNow != null) {
                Spacer(Modifier.width(10.dp))
                TvQuietCardAction(
                    label = stringResource(CommonR.string.marker_creditsCard_notNow),
                    onSelect = onNotNow,
                )
            }
        }
    }
}

/** A secondary card action: a faint capsule at rest, so it reads as a button; focus fills and outlines it. */
@Composable
private fun TvQuietCardAction(label: String, onSelect: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    Text(
        text = label,
        // Focus shows as a soft fill, never the primary's white, so the lit
        // pill beside it stays the one primary action.
        color = Color.White.copy(alpha = if (isFocused) 1f else 0.85f),
        // The pill's type, so the two actions stand the same height.
        style = TvPlayerType.Prompt.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (isFocused) 0.22f else 0.08f), shape)
            .border(BorderStroke(if (isFocused) 2.dp else 1.dp, Color.White.copy(alpha = if (isFocused) 1f else 0.3f)), shape)
            .clickable(interactionSource = interactionSource, indication = null) { onSelect() }
            .padding(horizontal = 24.dp, vertical = 18.dp),
    )
}
