package com.continuum.app.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.continuum.app.android.ui.util.formatClockTime

/**
 * Seek bar with current/total time and a buffered-ahead track.
 *
 * The track draws three regions — played, buffered (downloaded, safe to seek
 * into), and not-yet-buffered — so the user can tell where a seek will land
 * instantly vs. where it will stall. While scrubbing, the displayed time follows
 * the thumb rather than the actual playback position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerProgressBar(
    position: Double,
    duration: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    bufferedPosition: Double = 0.0,
    enabled: Boolean = true,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    val maxDuration = duration.toFloat().coerceAtLeast(1f)
    val displayPosition = (if (isSeeking) seekPosition else position.toFloat()).coerceIn(0f, maxDuration)
    val playedFraction = displayPosition / maxDuration
    val bufferedFraction = (bufferedPosition.toFloat().coerceIn(0f, maxDuration) / maxDuration)
        .coerceIn(playedFraction, 1f)

    // iOS bottom bar is VStack(spacing: 8): progress slider, then the time row.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Slider(
            value = displayPosition,
            enabled = enabled,
            onValueChange = { value ->
                isSeeking = true
                seekPosition = value
            },
            onValueChangeFinished = {
                isSeeking = false
                onSeek(seekPosition.toDouble())
            },
            valueRange = 0f..maxDuration,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.24f)),
                ) {
                    // Buffered-ahead: downloaded and safe to seek into.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufferedFraction)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.45f)),
                    )
                    // Played.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(playedFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // iOS time row: current time left, duration right, `.caption` (~12sp) at
        // 0.8 white opacity, monospaced digits.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClockTime(displayPosition.toDouble()),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.8f),
            )
            Text(
                text = formatClockTime(duration),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
