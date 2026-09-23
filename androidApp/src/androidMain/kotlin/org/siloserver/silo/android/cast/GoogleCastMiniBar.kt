package org.siloserver.silo.android.cast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SeekIntervalIcon

/**
 * App-wide Google Cast mini controller, shown on every screen except the
 * player while a Cast session is live. Sibling of the NSD SiloCast mini bar
 * (which remotes Silo's own TV app); this one controls a Chromecast receiver.
 */
@Composable
fun GoogleCastMiniBar(
    castState: SiloCastState,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSelectSubtitle: (Long?) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    // Intervals [onSkipBack]/[onSkipForward] actually use, for the glyphs.
    skipBackSeconds: Int = 30,
    skipForwardSeconds: Int = 30,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CastConnected,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = castState.title.ifBlank { "Casting" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = castState.deviceName ?: "Chromecast",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSkipBack) {
                SeekIntervalIcon(
                    forward = false,
                    seconds = skipBackSeconds,
                    contentDescription = "Back $skipBackSeconds seconds",
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (castState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (castState.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onSkipForward) {
                SeekIntervalIcon(
                    forward = true,
                    seconds = skipForwardSeconds,
                    contentDescription = "Forward $skipForwardSeconds seconds",
                    modifier = Modifier.size(24.dp),
                )
            }
            CastSubtitleMenuButton(
                options = castState.subtitleOptions,
                activeId = castState.activeSubtitleId,
                onSelect = onSelectSubtitle,
            )
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop casting",
                )
            }
        }
    }
}
