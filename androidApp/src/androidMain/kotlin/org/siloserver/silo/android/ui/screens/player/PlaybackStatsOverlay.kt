@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Compact diagnostics card (top-leading) showing live playback statistics —
 * route, formats, decoders, bitrate, buffer health, drop counters. Modeled on
 * Apple's `PlaybackStatsPanel.swift`; data comes from [PlayerStatsSnapshot]
 * (PlaybackAnalyticsListener events) plus session fields on the UI state.
 *
 * Opened from the settings sheet's Diagnostics row; stays visible regardless
 * of controls visibility and carries its own collapse + close affordances.
 */
@Composable
fun PlaybackStatsOverlay(
    state: PlayerViewModel.PlayerUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 320.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PLAYBACK STATS",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse stats" else "Expand stats",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { expanded = !expanded },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close stats",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onClose),
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.size(6.dp))
            statsRows(state).forEach { (label, value) ->
                Row {
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.66f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(
                        text = value,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Non-null rows only, mirroring the TV HUD Stats pane's hudRows(). */
private fun statsRows(state: PlayerViewModel.PlayerUiState): List<Pair<String, String>> {
    val stats = state.stats
    return buildList {
        routeLabel(state)?.let { add("Route" to it) }
        videoLabel(state)?.let { add("Video" to it) }
        stats.audioCodec?.let { add("Audio" to it) }
        stats.videoDecoderName?.let { add("V decoder" to it) }
        stats.audioDecoderName?.let { add("A decoder" to it) }
        stats.bitrateBps?.let { add("Bitrate" to formatMbps(it)) }
        bufferLabel(state)?.let { add("Buffer" to it) }
        if (stats.droppedFrames > 0) add("Dropped" to stats.droppedFrames.toString())
        if (stats.audioUnderruns > 0) add("Underruns" to stats.audioUnderruns.toString())
    }
}

/** e.g. "Transcode · server_transcode_hls" or "Direct" (local playback has no delivery). */
private fun routeLabel(state: PlayerViewModel.PlayerUiState): String? {
    val method = state.playMethod ?: return null
    val methodLabel = method.name.lowercase(Locale.ROOT)
        .replaceFirstChar { it.titlecase(Locale.ROOT) }
    val delivery = state.delivery?.name?.lowercase(Locale.ROOT)
    return if (delivery != null) "$methodLabel · $delivery" else methodLabel
}

private fun videoLabel(state: PlayerViewModel.PlayerUiState): String? {
    val stats = state.stats
    val parts = listOfNotNull(
        stats.videoCodec,
        stats.resolution,
        stats.frameRate?.let { String.format(Locale.ROOT, "%.3f fps", it) },
        stats.hdrMode,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** Live buffered-ahead seconds from the position ticker. */
private fun bufferLabel(state: PlayerViewModel.PlayerUiState): String? {
    val ahead = state.bufferedPosition - state.position
    if (ahead <= 0.0) return null
    return String.format(Locale.ROOT, "%.1f s", ahead)
}

private fun formatMbps(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(Locale.ROOT, "%.0f Kbps", bps / 1_000.0)
    else -> "$bps bps"
}
