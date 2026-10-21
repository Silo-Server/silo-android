package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.player.NoticeTone
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.domain.player.BufferingQualityController
import org.siloserver.silo.domain.player.BufferingQualityPill
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus

/** The buffering-pill action that has focus, so the screen's root Select can act on it. */
enum class TvBufferingPillAction { Lower, Keep, Restore }

/**
 * Top-start transient toast surface for the TV player. Larger paddings and
 * font than the phone variant so the message reads at TV viewing distance.
 *
 * Driven by [org.siloserver.silo.common.player.PlaybackSessionLifecycle.notice].
 * Visibility tracks `notice != null`; when the lifecycle clears the notice
 * (recovery succeeds or fails) the surface fades + slides out. The notice is
 * not user-dismissable — its lifetime is owned by the lifecycle.
 *
 * The buffering-quality pill (server docs/design/2026-10-17-buffering-quality.md)
 * shares this anchor, under any notice. Pass [BufferingQualityPill.Hidden]
 * while a menu is open. Its focus follows [TvIntroAutoSkipBanner]: it takes
 * focus once when it appears, only if [bufferingPillMayTakeFocus]. Select and
 * Back are handled by the player screen's root key handling, which reads the
 * focused action from [onBufferingPillFocusChanged].
 *
 * Anchoring is the parent's responsibility (typically a Box with top-start
 * alignment and 32dp inset).
 */
@Composable
fun TvPlayerNoticeOverlay(
    notice: PlayerNotice?,
    modifier: Modifier = Modifier,
    bufferingPill: BufferingQualityPill = BufferingQualityPill.Hidden,
    /** Bumps when the pill's timer (re)starts, so its fill re-anchors. */
    bufferingCountdownRun: Int = 0,
    /** False while the pill is up but its timer is frozen by a pause. */
    bufferingTimerRunning: Boolean = false,
    /** False while a scrub owns focus — see TvIntroAutoSkipBanner.mayTakeFocus. */
    bufferingPillMayTakeFocus: Boolean = true,
    onBufferingLower: () -> Unit = {},
    onBufferingKeep: () -> Unit = {},
    onBufferingRestore: () -> Unit = {},
    onBufferingPillFocusChanged: (TvBufferingPillAction?) -> Unit = {},
) {
    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut(),
        ) {
            // Crossfade when transitioning between two non-null notices (e.g.
            // Reconnecting → some future Info banner) instead of replaying the
            // slide-in. AnimatedVisibility above only triggers on null↔non-null
            // transitions, so this handles the in-place case.
            AnimatedContent(
                targetState = notice,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "TvPlayerNoticeContent",
            ) { current ->
                if (current != null) {
                    TvNoticePill(notice = current)
                }
            }
        }
        TvBufferingQualityPill(
            pill = bufferingPill,
            countdownRun = bufferingCountdownRun,
            timerRunning = bufferingTimerRunning,
            totalSeconds = BufferingQualityController.PROMPT_SECONDS,
            mayTakeFocus = bufferingPillMayTakeFocus,
            onLower = onBufferingLower,
            onKeep = onBufferingKeep,
            onRestore = onBufferingRestore,
            onFocusChanged = onBufferingPillFocusChanged,
            modifier = if (notice != null) Modifier.padding(top = 12.dp) else Modifier,
        )
    }
}

@Composable
private fun TvNoticePill(notice: PlayerNotice) {
    val backgroundColor = when (notice.tone) {
        NoticeTone.Info -> Color(0xFF1E40AF).copy(alpha = 0.92f)
        NoticeTone.Warning -> Color(0xFFB45309).copy(alpha = 0.92f)
    }
    val icon: ImageVector = when (notice.tone) {
        NoticeTone.Info -> Icons.Outlined.Info
        NoticeTone.Warning -> Icons.Outlined.Warning
    }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(max = 480.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = notice.message,
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            )
        }
    }
}

/**
 * The buffering pill's two copies. The prompt titles itself "Playback keeps
 * buffering" over "Lower quality" and "Keep quality"; the undo puts the
 * caption "Quality lowered to stop buffering" over "Restore quality". The
 * title or caption and each action are separate elements. The fill tracks
 * the time left, the same frame-clock sweep as [TvIntroAutoSkipBanner].
 */
@Composable
private fun TvBufferingQualityPill(
    pill: BufferingQualityPill,
    countdownRun: Int,
    timerRunning: Boolean,
    totalSeconds: Int,
    mayTakeFocus: Boolean,
    onLower: () -> Unit,
    onKeep: () -> Unit,
    onRestore: () -> Unit,
    onFocusChanged: (TvBufferingPillAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the kind so the per-second ticks recompose this slot rather
    // than recreating the subtree, which would restart the fill and refocus.
    val slot = when (pill) {
        BufferingQualityPill.Hidden -> 0
        is BufferingQualityPill.Prompt -> 1
        is BufferingQualityPill.Undo -> 2
    }
    // Off the frame clock, as on the intro pill: Compose scales animation
    // durations by the device setting, which would let the bar disagree
    // with the 15 s timer.
    val fill = remember { mutableFloatStateOf(0f) }
    val secondsRemaining = pill.secondsRemainingOrNull
    LaunchedEffect(countdownRun, timerRunning, totalSeconds, secondsRemaining == null) {
        if (secondsRemaining == null || totalSeconds <= 0) {
            fill.floatValue = 0f
            return@LaunchedEffect
        }
        val remaining = secondsRemaining.coerceAtLeast(1)
        val from = (1f - remaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        fill.floatValue = from
        // Frozen by a pause: the bar holds at the fraction the frozen number describes.
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
            fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                ExitTransition.None using null
        },
        label = "tvBufferingQualityPill",
        modifier = modifier,
    ) { currentSlot ->
        when (currentSlot) {
            0 -> Spacer(Modifier.size(0.dp))
            1 -> TvBufferingPillCard(
                title = stringResource(R.string.buffering_quality_prompt_title),
                caption = null,
                primaryLabel = stringResource(R.string.buffering_quality_prompt_lower),
                primaryAction = TvBufferingPillAction.Lower,
                onPrimary = onLower,
                secondaryLabel = stringResource(R.string.buffering_quality_prompt_keep),
                secondaryAction = TvBufferingPillAction.Keep,
                onSecondary = onKeep,
                progress = fill.floatValue,
                autoFocus = mayTakeFocus,
                onFocusChanged = onFocusChanged,
            )
            else -> TvBufferingPillCard(
                title = null,
                caption = stringResource(R.string.buffering_quality_undo_caption),
                primaryLabel = stringResource(R.string.buffering_quality_undo_restore),
                primaryAction = TvBufferingPillAction.Restore,
                onPrimary = onRestore,
                secondaryLabel = null,
                secondaryAction = null,
                onSecondary = {},
                progress = fill.floatValue,
                autoFocus = mayTakeFocus,
                onFocusChanged = onFocusChanged,
            )
        }
    }
}

/**
 * Dark translucent card at the notice pill's corner radius, with the
 * countdown fill behind its content. Initial focus lands on the primary
 * action, once; Left and Right move between the actions and never leave
 * the card for controls hidden behind it.
 */
@OptIn(ExperimentalComposeUiApi::class) // focusProperties exit
@Composable
private fun TvBufferingPillCard(
    title: String?,
    caption: String?,
    primaryLabel: String,
    primaryAction: TvBufferingPillAction,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    secondaryAction: TvBufferingPillAction?,
    onSecondary: () -> Unit,
    progress: Float,
    autoFocus: Boolean,
    onFocusChanged: (TvBufferingPillAction?) -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    // Captured once: a later recomposition must not re-claim focus the viewer
    // has since moved elsewhere.
    val shouldFocus = remember { autoFocus }
    val initialFocusModifier = rememberTvContentInitialFocus(
        target = primaryFocus,
        contentKey = if (shouldFocus) Unit else null,
    )
    val latestOnFocusChanged by rememberUpdatedState(onFocusChanged)
    var focusedAction by remember { mutableStateOf<TvBufferingPillAction?>(null) }
    fun reportFocus(action: TvBufferingPillAction, focused: Boolean) {
        if (focused) {
            focusedAction = action
            latestOnFocusChanged(action)
        } else if (focusedAction == action) {
            focusedAction = null
            latestOnFocusChanged(null)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestOnFocusChanged(null) }
    }

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.72f), shape),
    ) {
        // Sized to the card via matchParentSize; plain fillMaxWidth/Height
        // would take the screen's constraints instead.
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.10f)),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (caption != null) {
                Text(
                    text = caption,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .then(initialFocusModifier)
                    .focusProperties {
                        exit = { direction ->
                            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                                FocusRequester.Cancel
                            } else {
                                FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvBufferingPillButton(
                    label = primaryLabel,
                    emphasized = true,
                    focusRequester = primaryFocus,
                    onFocused = { reportFocus(primaryAction, it) },
                    onClick = onPrimary,
                )
                if (secondaryLabel != null && secondaryAction != null) {
                    TvBufferingPillButton(
                        label = secondaryLabel,
                        emphasized = false,
                        focusRequester = null,
                        onFocused = { reportFocus(secondaryAction, it) },
                        onClick = onSecondary,
                    )
                }
            }
        }
    }
}

/**
 * One action capsule. The primary action reads as primary even unfocused (a
 * filled capsule; the secondary is outlined); focus lights either white.
 */
@Composable
private fun TvBufferingPillButton(
    label: String,
    emphasized: Boolean,
    focusRequester: FocusRequester?,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(percent = 50)
    val background = when {
        isFocused -> Color.White
        emphasized -> Color.White.copy(alpha = 0.24f)
        else -> Color.Transparent
    }
    val borderColor = when {
        isFocused -> Color.White
        emphasized -> Color.Transparent
        else -> Color.White.copy(alpha = 0.35f)
    }
    val textColor = when {
        isFocused -> Color.Black
        emphasized -> Color.White
        else -> Color.White.copy(alpha = 0.8f)
    }
    Box(
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { onFocused(it.isFocused) }
            .clip(shape)
            .background(background, shape)
            .border(BorderStroke(2.dp, borderColor), shape)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 18.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
