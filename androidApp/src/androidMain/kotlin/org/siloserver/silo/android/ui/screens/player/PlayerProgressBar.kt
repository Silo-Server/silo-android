package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter

/**
 * Seek bar with current/total time and a buffered-ahead track, mirroring
 * iOS `MobilePlayerControls.progressSlider`:
 *
 * - three track regions — played, buffered (safe to seek into), base;
 * - the intro range tinted cyan (credits is deliberately NOT drawn — iOS
 *   only tints the intro);
 * - a 2dp chapter tick per chapter, drawn under the played fill;
 * - while scrubbing, a preview bubble above the thumb with the target time
 *   and the chapter title at that point (text only — iOS has no thumbnail
 *   trickplay either).
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
    chapters: List<VersionChapter> = emptyList(),
    intro: TimeRange? = null,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }

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
        // Scrub preview bubble — pinned above the bar at the drag position,
        // clamped so it never runs off-screen (iOS clamps to [80, width-80]pt).
        Box(modifier = Modifier.fillMaxWidth()) {
            if (isSeeking) {
                val density = LocalDensity.current
                val barWidthDp = with(density) { barWidthPx.toDp() }
                val clampPad = 80.dp
                val bubbleX = (barWidthDp * playedFraction)
                    .coerceIn(clampPad, (barWidthDp - clampPad).coerceAtLeast(clampPad))
                val chapterTitle = chapterTitleAt(chapters, seekPosition.toDouble())
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .offset(x = bubbleX - clampPad)
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.82f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = formatClockTime(seekPosition.toDouble()),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                    )
                    chapterTitle?.let { title ->
                        Text(
                            text = title,
                            modifier = Modifier.widthIn(max = 160.dp),
                            fontSize = 12.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

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
            // iOS-style dot instead of Material's chunky pill: a small circle
            // that grows slightly while scrubbing (the target time shows in the
            // floating preview bubble above). Ignores the SliderState param.
            thumb = {
                Box(
                    modifier = Modifier
                        .size(if (isSeeking) 20.dp else 14.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f))
                        .onSizeChanged { barWidthPx = it.width.toFloat() },
                ) {
                    // Buffered-ahead: downloaded and safe to seek into.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufferedFraction)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.52f)),
                    )
                    // Intro tint — iOS draws the intro range cyan at 0.4.
                    intro?.let { range ->
                        val startFraction = (range.start / maxDuration).toFloat().coerceIn(0f, 1f)
                        val endFraction = (range.end / maxDuration).toFloat().coerceIn(startFraction, 1f)
                        if (endFraction > startFraction) {
                            val density = LocalDensity.current
                            val barWidthDp = with(density) { barWidthPx.toDp() }
                            Box(
                                modifier = Modifier
                                    .offset(x = barWidthDp * startFraction)
                                    .width(barWidthDp * (endFraction - startFraction))
                                    .fillMaxHeight()
                                    .background(Color.Cyan.copy(alpha = 0.4f)),
                            )
                        }
                    }
                    // Chapter ticks, under the played fill (iOS: the fill
                    // covers ticks in played territory).
                    if (chapters.isNotEmpty()) {
                        val density = LocalDensity.current
                        val barWidthDp = with(density) { barWidthPx.toDp() }
                        chapters.forEach { chapter ->
                            val fraction = (chapter.startSeconds / maxDuration).toFloat()
                            if (fraction > 0f && fraction < 1f) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = barWidthDp * fraction - 1.dp)
                                        .width(2.dp)
                                        .fillMaxHeight()
                                        .background(Color.White.copy(alpha = 0.72f)),
                                )
                            }
                        }
                    }
                    // Played.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(playedFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            },
            // Keep a generous invisible touch target around the visual track.
            // This makes fine seeking practical on a phone without turning the
            // timeline itself into a chunky Material slider.
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(48.dp),
        )

        // Current + remaining is more useful during playback than current +
        // total, especially when the controls are separated from the video in
        // tabletop posture.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatClockTime(displayPosition.toDouble()),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.86f),
            )
            Text(
                text = remainingTimeLabel(displayPosition.toDouble(), duration),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.86f),
            )
        }
    }
}

internal fun remainingTimeLabel(position: Double, duration: Double): String =
    "−${formatClockTime((duration - position).coerceAtLeast(0.0))}"

/** iOS `chapterTitle(at:)`: the last chapter starting at or before [seconds],
 *  falling back to "Chapter N" when the chapter is untitled. */
internal fun chapterTitleAt(chapters: List<VersionChapter>, seconds: Double): String? {
    val chapter = chapters.lastOrNull { it.startSeconds <= seconds } ?: return null
    return chapter.title.ifBlank { "Chapter ${chapter.index + 1}" }
}
