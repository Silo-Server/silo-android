package org.siloserver.silo.android.cast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SeekIntervalIcon
import coil3.compose.AsyncImage

/**
 * Full-screen overlay shown in place of the local video surface AND the local
 * player controls while a Google Cast session is active. Compact "casting to
 * <device>" panel with the live remote position, play/pause + stop controls
 * (driven from [SiloCastState]), and a back arrow ([onBack]) to leave the
 * player screen while the cast keeps running.
 */
@Composable
fun SiloCastOverlay(
    castState: SiloCastState,
    posterUrl: String?,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSelectSubtitle: (Long?) -> Unit,
    onStopCasting: () -> Unit,
    onBack: () -> Unit,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    // Intervals [onSkipBack]/[onSkipForward] actually use, for the glyphs.
    skipBackSeconds: Int = 30,
    skipForwardSeconds: Int = 30,
) {
    BackHandler(onBack = onBack)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow taps so they never reach the local player's gesture layer
            // underneath (tap-to-toggle chrome, double-tap seek).
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (posterUrl != null) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }

            Icon(
                imageVector = Icons.Default.CastConnected,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )

            Text(
                text = "Casting to ${castState.deviceName ?: "device"}",
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            if (castState.title.isNotBlank()) {
                Text(
                    text = castState.title,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            if (castState.duration > 0.0) {
                // Real seek bar, not a passive indicator: drag to scrub, seek is
                // sent to the receiver on release. While dragging, the thumb and
                // the time readout follow the finger instead of the 1s remote
                // progress ticks.
                var scrubFraction by remember { mutableStateOf<Float?>(null) }
                val liveFraction = (castState.position / castState.duration).toFloat().coerceIn(0f, 1f)
                Slider(
                    value = scrubFraction ?: liveFraction,
                    onValueChange = { scrubFraction = it },
                    onValueChangeFinished = {
                        scrubFraction?.let { onSeek(it.toDouble() * castState.duration) }
                        scrubFraction = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val shownPosition = scrubFraction?.let { it.toDouble() * castState.duration }
                    ?: castState.position
                Text(
                    text = "${formatTime(shownPosition)} / ${formatTime(castState.duration)}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }

            // Icon-only transport row: five labeled buttons overflow the width
            // on phones and wrap grotesquely (a one-letter-per-line "Stop"
            // pill), so every control is an icon with a content description.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSkipBack) {
                    SeekIntervalIcon(
                        forward = false,
                        seconds = skipBackSeconds,
                        contentDescription = "Back $skipBackSeconds seconds",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }

                IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(
                        imageVector = if (castState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (castState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }

                IconButton(onClick = onSkipForward) {
                    SeekIntervalIcon(
                        forward = true,
                        seconds = skipForwardSeconds,
                        contentDescription = "Forward $skipForwardSeconds seconds",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }

                CastSubtitleMenuButton(
                    options = castState.subtitleOptions,
                    activeId = castState.activeSubtitleId,
                    onSelect = onSelectSubtitle,
                    tint = Color.White,
                    iconSize = 28.dp,
                )

                IconButton(onClick = onStopCasting) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop casting",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

private fun formatTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
