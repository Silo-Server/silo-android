package org.siloserver.silo.android.ui.screens.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloInterFamily
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.ui.components.rememberNoticeCountdownFill
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
 * A thin line along the pill's foot fills left to right and lands full exactly
 * as the timer ends. Tap is Select; a tap outside the pill is not Back. Back
 * itself is handled by the player overlay, not here.
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
    /** Bumped by the controller when the timer (re)starts; re-anchors the countdown line. */
    countdownRun: Int = 0,
    /** False while the timer is frozen by a pause — the line holds where it is. */
    timerRunning: Boolean = true,
) {
    val fill = rememberNoticeCountdownFill(state.secondsRemainingOrNull, totalSeconds, countdownRun, timerRunning)

    // Keyed on the state kind so per-second ticks recompose the slot rather than
    // recreating the subtree, which would restart the countdown line.
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
                label = stringResource(CommonR.string.introSkip_pill_skip),
                icon = Icons.Filled.SkipNext,
                progress = fill.floatValue,
                onClick = onSelect,
            )
            else -> IntroPromptPill(
                label = stringResource(CommonR.string.introSkip_pill_undo),
                caption = stringResource(CommonR.string.introSkip_pill_undoCaption),
                icon = Icons.Filled.Replay,
                progress = fill.floatValue,
                onClick = onSelect,
            )
        }
    }
}

/**
 * The capsule both copies share: a near-opaque plate with a hairline, a white
 * label, and a countdown line along its foot that tracks the timer. A plain
 * button — hover and tap are Select. An optional [caption] sits above the
 * capsule, outside the tap target, so the confirmation never reads as part of
 * the action.
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
                fontFamily = SiloInterFamily,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 10.dp, bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .clip(shape)
                // Near-opaque with a hairline, so the capsule keeps its shape
                // over black bars as well as over the picture.
                .background(PlayerChrome.Plate.copy(alpha = 0.9f), shape)
                .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
                .clickable(onClick = onClick),
        ) {
            PlayerCountdownLine(progress, horizontalInset = 18.dp)
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
                    fontFamily = SiloInterFamily,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * "Up next" during the closing credits: the episode that follows and whether it
 * starts by itself. With [onPlayNext] the card carries the actions — "Play next
 * episode" lit, "Not now" quiet — as on the TVs; without it (a room) Skip
 * Credits stays a pill below the card.
 */
@Composable
fun CreditsUpNextCard(
    next: PlayerViewModel.NextEpisodeInfo,
    /** The auto-play line under the episode; null leaves it out. */
    @StringRes autoPlayLine: Int?,
    /** "Not now": this episode won't start the next by itself; the card goes. */
    onNotNow: (() -> Unit)? = null,
    /** Plays the named episode now; the card's primary action. */
    onPlayNext: (() -> Unit)? = null,
    /** Narrower beside the landscape transport, where Not now wraps under Play next episode. */
    maxWidth: Dp = CreditsUpNextCardMaxWidth,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(PlayerChrome.Plate.copy(alpha = 0.92f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The details read as one; the actions below stay their own buttons.
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                // Capitals are styling, as on the Up Next screen's heading.
                text = stringResource(CommonR.string.upNext_heading).uppercase(),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontFamily = SiloInterFamily,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = listOfNotNull(
                    stringResource(
                        CommonR.string.catalog_episodeCaption,
                        LocaleFormat.codeNumber(next.seasonNumber),
                        LocaleFormat.codeNumber(next.episodeNumber),
                    ),
                    next.title?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = SiloInterFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (autoPlayLine != null) {
                Text(
                    text = stringResource(autoPlayLine),
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    fontFamily = SiloInterFamily,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (onPlayNext != null || onNotNow != null) FlowRow(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onPlayNext != null) {
                Text(
                    text = stringResource(CommonR.string.marker_playNextEpisode),
                    color = PlayerChrome.Ink,
                    fontSize = 14.sp,
                    fontFamily = SiloInterFamily,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(PlayerChrome.Paper)
                        .clickable(role = Role.Button, onClick = onPlayNext)
                        .heightIn(min = PlayerChrome.RowHeight)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 16.dp),
                )
            }
            if (onNotNow != null) {
                Text(
                    text = stringResource(CommonR.string.marker_creditsCard_notNow),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = SiloInterFamily,
                    fontWeight = FontWeight.SemiBold,
                    // A faint capsule, so it reads as a button beside the primary.
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onNotNow)
                        .heightIn(min = PlayerChrome.RowHeight)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 14.dp),
                )
            }
        }
    }
}

/** Room for both of the credits card's actions side by side. */
internal val CreditsUpNextCardMaxWidth = 360.dp

/** Manual recap/credits action uses the intro pill's existing visual treatment. */
@Composable
fun MarkerSkipPill(label: String, onClick: () -> Unit) {
    IntroPromptPill(label = label, icon = Icons.Filled.SkipNext, progress = 0f, onClick = onClick)
}
