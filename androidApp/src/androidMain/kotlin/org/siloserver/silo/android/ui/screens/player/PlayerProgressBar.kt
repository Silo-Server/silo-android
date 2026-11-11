package org.siloserver.silo.android.ui.screens.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.siloserver.silo.android.ui.components.LeftToRight
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.common.R as CommonR

/**
 * The seek bar. Like iOS, the whole bar is the handle: it grows from 5 to
 * 11dp under the finger, a bubble above shows the target time, the chapter,
 * and how far the scrub has moved, and a tick marks where it started. Chapters
 * show as gaps in the bar rather than tick marks, and a light haptic fires as
 * the scrub crosses into a new chapter. When the server publishes seek-bar
 * previews, [thumbnail] (the picture at [onScrubTarget]'s time) tops the
 * bubble, as on iOS, and the bubble grows upward from where the text-only
 * one sits.
 *
 * Track regions: played (Paper), buffered (safe to seek into), base. Detected
 * markers are tinted bands under the played fill (intro cyan, recap green,
 * credits orange, preview purple).
 */
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
    credits: TimeRange? = null,
    recap: TimeRange? = null,
    preview: TimeRange? = null,
    showTimes: Boolean = true,
    onScrubbingChange: (Boolean) -> Unit = {},
    thumbnail: ImageBitmap? = null,
    onScrubTarget: (Double?) -> Unit = {},
) {
    // A timeline runs left to right in every language: the bar is drawn and
    // hit-tested from the left edge, so its times sit the same way round.
    LeftToRight {
        ProgressBarContent(
            position, duration, onSeek, modifier, bufferedPosition, enabled, chapters, intro, credits, recap,
            preview, showTimes, onScrubbingChange, thumbnail, onScrubTarget,
        )
    }
}

@Composable
private fun ProgressBarContent(
    position: Double,
    duration: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier,
    bufferedPosition: Double,
    enabled: Boolean,
    chapters: List<VersionChapter>,
    intro: TimeRange?,
    credits: TimeRange?,
    recap: TimeRange?,
    preview: TimeRange?,
    showTimes: Boolean,
    onScrubbingChange: (Boolean) -> Unit,
    thumbnail: ImageBitmap?,
    onScrubTarget: (Double?) -> Unit,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableDoubleStateOf(0.0) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    var lastChapter by remember { mutableIntStateOf(-1) }
    var showTotal by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnScrubbing by rememberUpdatedState(onScrubbingChange)
    val latestOnScrubTarget by rememberUpdatedState(onScrubTarget)
    // The gesture coroutine outlives recompositions, so it reads these
    // through state rather than the values it started with.
    val latestPosition by rememberUpdatedState(position)
    val latestChapters by rememberUpdatedState(chapters)

    val hasKnownDuration = duration.isFinite() && duration > 0.0
    val seekable = enabled && hasKnownDuration
    val displayPosition = (if (isSeeking) seekPosition else position)
        .let { if (hasKnownDuration) it.coerceIn(0.0, duration) else it.coerceAtLeast(0.0) }
    val playedFraction = if (hasKnownDuration) (displayPosition / duration).toFloat() else 0f
    val bufferedFraction = if (hasKnownDuration) {
        (bufferedPosition.coerceIn(0.0, duration) / duration).toFloat().coerceAtLeast(playedFraction)
    } else {
        0f
    }

    val barHeight by animateDpAsState(
        targetValue = if (isSeeking) 11.dp else 5.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "seekBarHeight",
    )
    val timesAlpha by animateFloatAsState(if (isSeeking) 0f else 1f, label = "seekTimesAlpha")

    fun positionAt(x: Float): Double =
        if (barWidthPx <= 0f) 0.0 else (x / barWidthPx).coerceIn(0f, 1f).toDouble() * duration

    fun chapterIndexAt(seconds: Double): Int = latestChapters.indexOfLast { it.startSeconds <= seconds }

    val positionDescription = stringResource(CommonR.string.player_progress_position)
    // Formatted inside semantics, as before, rather than on every position tick.
    val resources = LocalResources.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (showTimes) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(timesAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatClockTime(displayPosition),
                    style = PlayerType.Time,
                    modifier = Modifier.weight(1f),
                )
                // Tap to switch between time left and total length (iOS).
                Text(
                    text = if (showTotal && hasKnownDuration) {
                        formatClockTime(duration)
                    } else {
                        remainingTimeLabel(displayPosition, duration)
                    },
                    style = PlayerType.Time.copy(color = PlayerChrome.Graphite),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(CommonR.string.player_progress_toggleTime),
                    ) { showTotal = !showTotal },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { barWidthPx = it.width.toFloat() }
                .semantics {
                    contentDescription = positionDescription
                    stateDescription = resources.getString(
                        CommonR.string.player_progress_positionState,
                        formatClockTime(displayPosition),
                        formatClockTime(duration),
                    )
                    if (hasKnownDuration) {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = displayPosition.toFloat(),
                            range = 0f..duration.toFloat(),
                        )
                    }
                    if (seekable) {
                        setProgress { target ->
                            latestOnSeek(target.toDouble().coerceIn(0.0, duration))
                            true
                        }
                    }
                }
                .pointerInput(seekable, duration) {
                    if (!seekable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        seekPosition = positionAt(down.position.x)
                        lastChapter = chapterIndexAt(seekPosition)
                        isSeeking = true
                        latestOnScrubbing(true)
                        latestOnScrubTarget(seekPosition)
                        var pointerId = down.id
                        // A restart of this block (seekability or duration
                        // changed mid-scrub) cancels the gesture: end the
                        // scrub without seeking so the chrome comes back.
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()?.also { pointerId = it.id }
                                    ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (change.positionChange() != Offset.Zero) {
                                    change.consume()
                                    seekPosition = positionAt(change.position.x)
                                    latestOnScrubTarget(seekPosition)
                                    val chapter = chapterIndexAt(seekPosition)
                                    if (chapter != lastChapter) {
                                        lastChapter = chapter
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }
                        } finally {
                            isSeeking = false
                            latestOnScrubbing(false)
                            latestOnScrubTarget(null)
                        }
                        latestOnSeek(seekPosition)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val markers = listOfNotNull(
                intro?.let { it to Color(0xFF22D3EE) },
                recap?.let { it to Color(0xFF8BC34A) },
                credits?.let { it to Color(0xFFFFB74D) },
                preview?.let { it to Color(0xFFBA68C8) },
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight + 12.dp),
            ) {
                drawSeekBar(
                    barHeightPx = barHeight.toPx(),
                    playedFraction = playedFraction,
                    bufferedFraction = bufferedFraction,
                    chapterFractions = if (hasKnownDuration) {
                        chapters.map { (it.startSeconds / duration).toFloat() }
                    } else {
                        emptyList()
                    },
                    markers = if (hasKnownDuration) {
                        markers.map { (range, color) ->
                            Triple((range.start / duration).toFloat(), (range.end / duration).toFloat(), color)
                        }
                    } else {
                        emptyList()
                    },
                    // The head stays on the target while scrubbing: one mark
                    // for where playback will land.
                    showHead = seekable,
                    dimmed = !enabled,
                )
            }

            if (isSeeking && hasKnownDuration) {
                val x = barWidthPx * playedFraction
                val half = bubbleWidthPx / 2f
                val clampedX = if (barWidthPx > bubbleWidthPx) x.coerceIn(half, barWidthPx - half) else barWidthPx / 2f
                val chapterTitle = chapterTitleAt(chapters, seekPosition)
                // A preview grows the bubble upward: its bottom edge stays put.
                val lift = if (thumbnail != null) 52.dp + ScrubThumbnailBlockHeight / 2 else 52.dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .onSizeChanged { bubbleWidthPx = it.width }
                        .offset {
                            IntOffset(
                                x = (clampedX - half).roundToInt(),
                                y = -lift.roundToPx(),
                            )
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(PlayerChrome.Smoke)
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                        .padding(
                            if (thumbnail != null) {
                                PaddingValues(ScrubThumbnailInset)
                            } else {
                                PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                            },
                        ),
                ) {
                    // The server's preview of the target, sized and cropped
                    // like the iOS scrub bubble's.
                    thumbnail?.let { image ->
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = ScrubThumbnailGap)
                                .size(ScrubThumbnailWidth, ScrubThumbnailHeight)
                                .clip(RoundedCornerShape(9.dp)),
                        )
                    }
                    Text(text = formatClockTime(seekPosition), style = PlayerType.BubbleTime)
                    chapterTitle?.let { chapter ->
                        Text(
                            text = chapter,
                            style = PlayerType.BubbleDetail,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .widthIn(max = 220.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The scrub bubble's preview, 16:9 as on iOS; other shapes are cropped to it. */
private val ScrubThumbnailWidth = 176.dp
private val ScrubThumbnailHeight = 99.dp
private val ScrubThumbnailGap = 6.dp
/** The bubble's padding while it shows a preview. */
private val ScrubThumbnailInset = 7.dp
/** How much taller a preview makes the bubble: the picture and its gap, less the narrower padding. */
private val ScrubThumbnailBlockHeight = ScrubThumbnailHeight + ScrubThumbnailGap - (9.dp - ScrubThumbnailInset) * 2

private fun DrawScope.drawSeekBar(
    barHeightPx: Float,
    playedFraction: Float,
    bufferedFraction: Float,
    chapterFractions: List<Float>,
    markers: List<Triple<Float, Float, Color>>,
    showHead: Boolean,
    dimmed: Boolean,
) {
    val width = size.width
    val top = (size.height - barHeightPx) / 2f
    val radius = CornerRadius(barHeightPx / 2f)
    val gap = 2.dp.toPx()
    // Chapter boundaries become gaps only while every segment stays long
    // enough to read as a segment; a title with dozens of short chapters
    // falls back to one continuous bar.
    val boundaries = chapterFractions.filter { it > 0.001f && it < 0.999f }.sorted()
    val edges = (listOf(0f) + boundaries + listOf(1f)).map { it * width }
    // A fixed minimum, not one tied to the bar's height: the bar thickens
    // while scrubbing, and the segments vanished just when they help most.
    val minSegment = 6.dp.toPx() + gap
    val useGaps = boundaries.isNotEmpty() &&
        edges.zipWithNext().all { (a, b) -> b - a >= minSegment }
    val segments = if (useGaps) edges.zipWithNext() else listOf(0f to width)
    val alpha = if (dimmed) 0.35f else 1f

    segments.forEachIndexed { index, (rawStart, rawEnd) ->
        val start = if (index == 0) rawStart else rawStart + gap / 2f
        val end = if (index == segments.lastIndex) rawEnd else rawEnd - gap / 2f
        val segmentSize = Size(end - start, barHeightPx)
        val segmentTopLeft = Offset(start, top)
        fun fill(color: Color, untilX: Float, fromX: Float = start) {
            if (untilX <= fromX) return
            clipRect(left = fromX, top = 0f, right = untilX.coerceAtMost(end), bottom = size.height) {
                drawRoundRect(color = color, topLeft = segmentTopLeft, size = segmentSize, cornerRadius = radius)
            }
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f * alpha),
            topLeft = segmentTopLeft,
            size = segmentSize,
            cornerRadius = radius,
        )
        fill(Color.White.copy(alpha = 0.22f * alpha), bufferedFraction * width)
        markers.forEach { (from, to, color) ->
            fill(color.copy(alpha = 0.45f * alpha), untilX = to * width, fromX = maxOf(start, from * width))
        }
        fill(PlayerChrome.Paper.copy(alpha = alpha), playedFraction * width)
    }

    if (showHead) {
        val center = Offset(playedFraction * width, size.height / 2f)
        drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = center)
        drawCircle(color = PlayerChrome.Paper, radius = 6.5.dp.toPx(), center = center)
    }
}

internal fun remainingTimeLabel(position: Double, duration: Double): String =
    if (duration.isFinite() && duration > 0.0) {
        "−${formatClockTime((duration - position).coerceAtLeast(0.0))}"
    } else {
        "−−:−−"
    }

/** iOS `chapterTitle(at:)`: the last chapter starting at or before [seconds],
 *  falling back to "Chapter N" when the chapter is untitled. */
@Composable
internal fun chapterTitleAt(chapters: List<VersionChapter>, seconds: Double): String? {
    val chapter = chapters.lastOrNull { it.startSeconds <= seconds } ?: return null
    return chapter.title.ifBlank {
        stringResource(CommonR.string.options_chapterNumbered, LocaleFormat.integer(chapter.index + 1))
    }
}
