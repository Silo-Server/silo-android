package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import org.siloserver.silo.android.ui.components.SeekIntervalIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.layout.resolveAudiobookTransportLayout

/**
 * Five-control audiobook transport: prev-chapter · skip-back · play/pause ·
 * skip-forward · next-chapter. Chapter buttons are hidden (not just disabled)
 * when the book has no chapters, so the layout collapses to the classic
 * skip-back / play / skip-forward triple.
 */
@Composable
fun AudiobookTransport(
    isPlaying: Boolean,
    enabled: Boolean,
    hasChapters: Boolean,
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    onPrevChapter: () -> Unit,
    onSkipBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipForward: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS AudioTransportControls: HStack spacing 28, onSurface-tinted glyphs;
    // outer chapter buttons ~24dp (.title3), skip buttons ~32dp (.title), and
    // an 82dp accent play circle with a 32dp heavy glyph.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = resolveAudiobookTransportLayout(
            availableWidthDp = maxWidth.value,
            hasChapters = hasChapters,
        )
        val scrollState = rememberScrollState()
        val rowModifier = if (layout.requiresHorizontalScroll) {
            Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        } else {
            Modifier.fillMaxWidth()
        }

        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(
                layout.spacingDp.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasChapters) {
                IconButton(
                    onClick = onPrevChapter,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous Chapter",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            IconButton(
                onClick = onSkipBack,
                enabled = enabled,
                modifier = Modifier.size(50.dp),
            ) {
                SeekIntervalIcon(
                    forward = false,
                    seconds = skipBackSeconds,
                    contentDescription = "Back $skipBackSeconds seconds",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    )
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = enabled, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(
                onClick = onSkipForward,
                enabled = enabled,
                modifier = Modifier.size(50.dp),
            ) {
                SeekIntervalIcon(
                    forward = true,
                    seconds = skipForwardSeconds,
                    contentDescription = "Forward $skipForwardSeconds seconds",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (hasChapters) {
                IconButton(
                    onClick = onNextChapter,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next Chapter",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
