package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.LeftToRight
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.model.catalog.VersionChapter

/**
 * Current-chapter header + a solid whole-book seek slider. The scrubber
 * represents the entire book so overall progress reads at a glance; the header
 * keeps chapter context. [onSeek] receives absolute book seconds.
 */
@Composable
fun ChapterProgressBar(
    chapters: List<VersionChapter>,
    chapterCountLabel: String,
    positionSeconds: Double,
    durationSeconds: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderMax = durationSeconds.toFloat().coerceAtLeast(1f)
    val sliderValue = positionSeconds.toFloat().coerceIn(0f, sliderMax)
    val remaining = (durationSeconds - positionSeconds).coerceAtLeast(0.0)

    // iOS AudioScrubberSection: VStack(spacing: 10) of the track and a time
    // row showing elapsed (left) and -remaining (right) in caption-weight,
    // monospaced, secondary text. The Material slider stands in for the slim
    // accent-gradient capsule track.
    LeftToRight {
        Column(modifier = modifier.fillMaxWidth()) {
            Slider(
                value = sliderValue,
                valueRange = 0f..sliderMax,
                onValueChange = { onSeek(it.toDouble().coerceIn(0.0, durationSeconds.coerceAtLeast(0.0))) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatClockTime(positionSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "-" + formatClockTime(remaining),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
