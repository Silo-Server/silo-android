package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.draw.shadow
import androidx.tv.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import org.siloserver.silo.tv.ui.components.TvSeekIntervalIcon

/**
 * Bottom transport row mirroring `iosApp/.../tvOS/TVPlayerTransportCluster.swift`.
 *
 * Primary group (skipBack / playPause / skipForward) pinned left; secondary
 * group (up next / subtitles / options / close) pushed right. At rest every
 * button is a dark disc with a faint ring, so it reads over a letterbox bar as
 * well as over the picture. Focus inverts to Paper with an Ink glyph — no
 * scale — and a secondary button widens into a labeled pill that also says
 * what it is set to ("Subtitles · English"), the tvOS detail-page pattern.
 * The skip buttons use the profile-wide video intervals (10s back / 30s
 * forward on older servers). Up returns focus to the scrubber.
 */
@Composable
fun TvPlayerTransportCluster(
    isPlaying: Boolean,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onOpenQuickSubtitles: () -> Unit,
    /**
     * Non-null only when there is a next episode to show. Mirrors
     * silo-apple#86: the automatic trigger fires at the credits, and this lets
     * a viewer who is already done reach it early.
     */
    onUpNext: (() -> Unit)? = null,
    onOpenHUD: () -> Unit,
    onClose: () -> Unit,
    playPauseFocus: FocusRequester,
    onMoveUpToScrubber: () -> Unit,
    modifier: Modifier = Modifier,
    // Resolved video intervals behind [onSkipBack]/[onSkipForward].
    skipBackSeconds: Int = 10,
    skipForwardSeconds: Int = 30,
    // What the Subtitles button reveals when focused ("English", "Off").
    subtitlesValue: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Primary group — pinned left.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportIconButton(
                icon = null,
                seekGlyph = SeekGlyph(forward = false, seconds = skipBackSeconds),
                description = "Skip back $skipBackSeconds seconds",
                onClick = onSkipBack,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                focusRequester = playPauseFocus,
                isPrimary = true,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = null,
                seekGlyph = SeekGlyph(forward = true, seconds = skipForwardSeconds),
                description = "Skip forward $skipForwardSeconds seconds",
                onClick = onSkipForward,
                onMoveUp = onMoveUpToScrubber,
            )
        }

        // Secondary group — pushed right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            onUpNext?.let { showUpNext ->
                TransportIconButton(
                    icon = Icons.Rounded.SkipNext,
                    description = "Up Next",
                    label = "Up next",
                    onClick = showUpNext,
                    onMoveUp = onMoveUpToScrubber,
                )
                DockGap()
            }
            TransportIconButton(
                icon = Icons.Rounded.Subtitles,
                description = "Subtitles",
                label = "Subtitles",
                value = subtitlesValue,
                onClick = onOpenQuickSubtitles,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = Icons.Rounded.Tune,
                description = "Info and options",
                label = "Options",
                onClick = onOpenHUD,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = Icons.Rounded.Close,
                description = "Close player",
                label = "Close",
                onClick = onClose,
                onMoveUp = onMoveUpToScrubber,
            )
        }
    }
}

@Composable
private fun DockGap() {
    Spacer(modifier = Modifier.size(width = 10.dp, height = 1.dp))
}

@Composable
private fun TransportIconButton(
    icon: ImageVector?,
    description: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    isPrimary: Boolean = false,
    onMoveUp: () -> Unit = {},
    seekGlyph: SeekGlyph? = null,
    // Shown beside the glyph while focused; null keeps the button a disc.
    label: String? = null,
    value: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Uniform sizes across all buttons so the row reads as one transport group.
    val metrics = tvTransportControlMetrics(isPrimary)
    val buttonSize = metrics.buttonSizeDp.dp
    val symbolSize = metrics.symbolSizeDp.dp
    val shape = RoundedCornerShape(percent = 50)

    // Focus inverts the disc to Paper — no scale, so a button never crosses
    // its own hit target or nudges its neighbours.
    val fill by animateColorAsState(
        targetValue = if (isFocused) TvPlayerChrome.Paper else TvPlayerChrome.ButtonRest,
        animationSpec = tween(120),
        label = "transportBg",
    )
    val tint = if (isFocused) TvPlayerChrome.Ink else TvPlayerChrome.Paper
    val expanded = isFocused && label != null

    Row(
        modifier = Modifier
            .height(buttonSize)
            .widthIn(min = buttonSize)
            .then(if (isFocused) Modifier.shadow(10.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(fill)
            .then(if (isFocused) Modifier else Modifier.border(1.dp, TvPlayerChrome.ButtonRing, shape))
            .animateContentSize(animationSpec = tween(160))
            .let { mod -> if (focusRequester != null) mod.focusRequester(focusRequester) else mod }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    Key.DirectionUp -> {
                        onMoveUp()
                        true
                    }
                    else -> false
                }
            }
            .semantics {
                contentDescription = listOfNotNull(description, value).joinToString(", ")
                role = Role.Button
            }
            .padding(horizontal = if (expanded) 13.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (seekGlyph != null) {
            TvSeekIntervalIcon(
                forward = seekGlyph.forward,
                seconds = seekGlyph.seconds,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(symbolSize),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(symbolSize),
            )
        }
        if (expanded) {
            Spacer(Modifier.width(8.dp))
            Text(text = label.orEmpty(), style = TvPlayerType.ButtonLabel.copy(color = TvPlayerChrome.Ink), maxLines = 1)
            if (!value.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = value,
                    style = TvPlayerType.ButtonValue.copy(color = TvPlayerChrome.InkMuted),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(5.dp))
        }
    }
}

/** A relative-seek button's glyph: the interval it actually skips. */
private data class SeekGlyph(val forward: Boolean, val seconds: Int)
