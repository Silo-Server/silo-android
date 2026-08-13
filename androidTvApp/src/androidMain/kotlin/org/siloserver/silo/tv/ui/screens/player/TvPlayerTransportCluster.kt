package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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

/**
 * Bottom transport row mirroring `iosApp/.../tvOS/TVPlayerTransportCluster.swift`.
 *
 * Primary group (skipBack / playPause / skipForward) pinned left; secondary
 * group (options / close) pushed right. Uniform circular buttons that flip
 * white-on-black ↔ black-on-white when focused (white-fill inversion only — no
 * focus scale). Skip back is 10s; skip forward is 30s. There is no Back button
 * and no separate subtitles button — `options` (⋯) opens the floating HUD,
 * whose Subtitles tab now owns the track/style/delay controls; `close` (xmark)
 * exits the player. Up returns focus to the scrubber.
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
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Primary group — pinned left.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportIconButton(
                icon = Icons.Filled.Replay10,
                description = "Skip back 10 seconds",
                onClick = onSkipBack,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
                focusRequester = playPauseFocus,
                isPrimary = true,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = Icons.Filled.Forward30,
                description = "Skip forward 30 seconds",
                onClick = onSkipForward,
                onMoveUp = onMoveUpToScrubber,
            )
        }

        // Secondary group — pushed right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            onUpNext?.let { showUpNext ->
                TransportIconButton(
                    icon = Icons.Filled.SkipNext,
                    description = "Up Next",
                    onClick = showUpNext,
                    onMoveUp = onMoveUpToScrubber,
                )
                DockGap()
            }
            TransportIconButton(
                icon = Icons.Filled.ClosedCaption,
                description = "Subtitles",
                onClick = onOpenQuickSubtitles,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = Icons.Filled.Tune,
                description = "Info and options",
                onClick = onOpenHUD,
                onMoveUp = onMoveUpToScrubber,
            )
            DockGap()
            TransportIconButton(
                icon = Icons.Filled.Close,
                description = "Close player",
                onClick = onClose,
                onMoveUp = onMoveUpToScrubber,
            )
        }
    }
}

@Composable
private fun DockGap() {
    Spacer(modifier = Modifier.size(width = 5.dp, height = 1.dp))
}

@Composable
private fun TransportIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    isPrimary: Boolean = false,
    onMoveUp: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Uniform sizes across all buttons so the row reads as one transport group.
    val metrics = tvTransportControlMetrics(isPrimary)
    val buttonSize = metrics.buttonSizeDp.dp
    val symbolSize = metrics.symbolSizeDp.dp

    // Focus is signaled by filling the circle white — no scale transform so the
    // buttons never cross the bounds of their circular hit target.
    val focusBg by animateColorAsState(
        targetValue = if (isFocused) Color.White else Color.Black.copy(alpha = 0.35f),
        animationSpec = tween(120),
        label = "transportBg",
    )
    val iconTint = if (isFocused) Color.Black else Color.White

    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(focusBg)
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
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(symbolSize),
        )
    }
}
