package org.siloserver.silo.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.R
import org.siloserver.silo.common.player.NoticeTone
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.domain.player.BufferingQualityController
import org.siloserver.silo.domain.player.BufferingQualityPill

/**
 * Top-left transient toast surface for the phone player.
 *
 * Driven by [org.siloserver.silo.common.player.PlaybackSessionLifecycle.notice]. Visibility
 * tracks `notice != null`; when the underlying lifecycle clears the notice (recovery
 * succeeds or fails) the surface fades+slides out. The notice itself is not user-dismissable
 * — its lifetime is owned by the lifecycle.
 *
 * The buffering-quality pill ([bufferingPill]) shares the anchor, under any
 * notice: the prompt ("Playback keeps buffering" with "Lower quality" and
 * "Keep quality") or the undo ("Quality lowered to stop buffering" over
 * "Restore quality"). Tap is the action; Back is handled by the player overlay.
 *
 * Anchoring is the parent's responsibility (see [PlayerOverlay]).
 */
@Composable
fun PlayerNoticeOverlay(
    notice: PlayerNotice?,
    modifier: Modifier = Modifier,
    bufferingPill: BufferingQualityPill = BufferingQualityPill.Hidden,
    /** Bumped by the controller when the pill's timer (re)starts; re-anchors the fill. */
    bufferingCountdownRun: Int = 0,
    /** False while the timer is frozen by a pause — the fill holds where it is. */
    bufferingTimerRunning: Boolean = true,
    onBufferingLower: () -> Unit = {},
    onBufferingKeep: () -> Unit = {},
    onBufferingRestore: () -> Unit = {},
) {
    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut(),
        ) {
            // Crossfade when transitioning between two non-null notices (e.g. Reconnecting → some
            // future Info banner) instead of replaying the slide-in. AnimatedVisibility above only
            // triggers on null↔non-null transitions, so this handles the in-place case.
            AnimatedContent(
                targetState = notice,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "PlayerNoticeContent",
            ) { current ->
                if (current != null) {
                    NoticePill(notice = current)
                }
            }
        }
        BufferingQualityPillSlot(
            pill = bufferingPill,
            totalSeconds = BufferingQualityController.PROMPT_SECONDS,
            countdownRun = bufferingCountdownRun,
            timerRunning = bufferingTimerRunning,
            onLower = onBufferingLower,
            onKeep = onBufferingKeep,
            onRestore = onBufferingRestore,
            modifier = Modifier.padding(top = if (notice != null) 8.dp else 0.dp),
        )
    }
}

@Composable
private fun NoticePill(notice: PlayerNotice) {
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
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = notice.message,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

/**
 * The buffering pill, with the intro pill's countdown fill: it creeps left to
 * right on the frame clock, lands full as the controller's timer ends, and
 * holds while a pause freezes the timer.
 */
@Composable
private fun BufferingQualityPillSlot(
    pill: BufferingQualityPill,
    totalSeconds: Int,
    countdownRun: Int,
    timerRunning: Boolean,
    onLower: () -> Unit,
    onKeep: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = remember { mutableFloatStateOf(0f) }
    val secondsRemaining = pill.secondsRemainingOrNull
    // As IntroAutoSkipBanner: not keyed on `secondsRemaining`, so a tick does
    // not restart the sweep. `countdownRun` is what says the clock moved.
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

    // Keyed on the pill kind so per-second ticks recompose the slot rather
    // than recreating the subtree, which would restart the fill.
    val slot = when (pill) {
        BufferingQualityPill.Hidden -> 0
        is BufferingQualityPill.Prompt -> 1
        is BufferingQualityPill.Undo -> 2
    }
    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 180)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 180))
        },
        label = "bufferingQualityPill",
        modifier = modifier,
    ) { current ->
        when (current) {
            0 -> Spacer(Modifier.size(0.dp))
            1 -> BufferingPillCard(progress = fill.floatValue) {
                Text(
                    text = stringResource(R.string.buffering_quality_prompt_title),
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BufferingPillAction(
                        label = stringResource(R.string.buffering_quality_prompt_lower),
                        primary = true,
                        onClick = onLower,
                    )
                    BufferingPillAction(
                        label = stringResource(R.string.buffering_quality_prompt_keep),
                        primary = false,
                        onClick = onKeep,
                    )
                }
            }
            else -> BufferingPillCard(progress = fill.floatValue) {
                // The caption is its own element, outside the action.
                Text(
                    text = stringResource(R.string.buffering_quality_lowered),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                BufferingPillAction(
                    label = stringResource(R.string.buffering_quality_restore),
                    primary = true,
                    onClick = onRestore,
                )
            }
        }
    }
}

/** The intro pill's dark scrim and fill, in the notice pill's shape. */
@Composable
private fun BufferingPillCard(
    progress: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.65f), shape),
    ) {
        // matchParentSize so the fill takes the card's bounds, not the screen's.
        Box(Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.14f)),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            content = content,
        )
    }
}

/** One action: the primary one solid white, the other a quiet outline-free chip. */
@Composable
private fun BufferingPillAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (primary) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
