package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import org.siloserver.silo.tv.ui.components.TvSeekIntervalIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import org.siloserver.silo.tv.ui.focus.tvFocusSuppressed

/**
 * Five-button audiobook transport, D-pad navigable. Mirrors the video player's
 * transport focus visuals (white↔black flip, 66 dp circles, KeyUp-driven
 * Select). Up/Down are left to Compose's default focus traversal so focus moves
 * naturally between this row and the secondary chips. Chapter buttons dim +
 * no-op when [chaptersEnabled] is false (single-chapter degrade, spec §8).
 */
@Composable
fun TvAudiobookTransportRow(
    isPlaying: Boolean,
    chaptersEnabled: Boolean,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onPrevChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    playPauseFocus: FocusRequester,
    modifier: Modifier = Modifier,
    focusSuppressed: Boolean = false,
    buttonSize: Dp = 68.dp,
    primaryButtonWidth: Dp = 112.dp,
    primaryButtonHeight: Dp = 58.dp,
    buttonSpacing: Dp = 16.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
    ) {
        TransportIconButton(
            icon = Icons.Filled.SkipPrevious,
            description = "Previous chapter",
            enabled = chaptersEnabled,
            focusSuppressed = focusSuppressed,
            buttonSize = buttonSize,
            onClick = onPrevChapter,
        )
        TransportIconButton(
            icon = null,
            seekGlyph = false to skipBackSeconds,
            description = "Skip back $skipBackSeconds seconds",
            focusSuppressed = focusSuppressed,
            buttonSize = buttonSize,
            onClick = onSkipBack,
        )
        TransportIconButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = if (isPlaying) "Pause" else "Play",
            isPrimary = true,
            focusRequester = playPauseFocus,
            focusSuppressed = focusSuppressed,
            buttonSize = buttonSize,
            primaryButtonWidth = primaryButtonWidth,
            primaryButtonHeight = primaryButtonHeight,
            onClick = onPlayPause,
        )
        TransportIconButton(
            icon = null,
            seekGlyph = true to skipForwardSeconds,
            description = "Skip forward $skipForwardSeconds seconds",
            focusSuppressed = focusSuppressed,
            buttonSize = buttonSize,
            onClick = onSkipForward,
        )
        TransportIconButton(
            icon = Icons.Filled.SkipNext,
            description = "Next chapter",
            enabled = chaptersEnabled,
            focusSuppressed = focusSuppressed,
            buttonSize = buttonSize,
            onClick = onNextChapter,
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: ImageVector?,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null,
    focusSuppressed: Boolean = false,
    buttonSize: Dp = 68.dp,
    primaryButtonWidth: Dp = 112.dp,
    primaryButtonHeight: Dp = 58.dp,
    /** (forward, seconds) for a relative-seek button; drawn with the interval. */
    seekGlyph: Pair<Boolean, Int>? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val buttonWidth = if (isPrimary) primaryButtonWidth else buttonSize
    val buttonHeight = if (isPrimary) primaryButtonHeight else buttonSize
    val buttonShape = RoundedCornerShape(buttonHeight / 2)
    val symbolSize = if (isPrimary) (buttonHeight * 0.44f) else (buttonSize * 0.40f)
    val focusBg = if (isFocused) Color(0xFFE8F5F7) else Color(0xFF173137).copy(alpha = 0.92f)
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.30f)
        isFocused -> Color.Black
        else -> Color(0xFF23A8F2)
    }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.025f else 1f,
        animationSpec = tween(120),
        label = "abTransportScale",
    )

    Box(
        modifier = Modifier
            .size(buttonWidth, buttonHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(buttonShape)
            .background(focusBg)
            .border(
                width = if (isFocused) 0.dp else 1.dp,
                color = if (isFocused) Color.Transparent else Color.White.copy(alpha = 0.06f),
                shape = buttonShape,
            )
            .let { mod -> if (focusRequester != null) mod.focusRequester(focusRequester) else mod }
            // Ahead of the focusable, so it binds to this button's own focus
            // target rather than being inherited from somewhere up the tree.
            .tvFocusSuppressed(focusSuppressed)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (enabled) onClick()
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
        if (seekGlyph != null) {
            TvSeekIntervalIcon(
                forward = seekGlyph.first,
                seconds = seekGlyph.second,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(symbolSize),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(symbolSize),
            )
        }
    }
}
