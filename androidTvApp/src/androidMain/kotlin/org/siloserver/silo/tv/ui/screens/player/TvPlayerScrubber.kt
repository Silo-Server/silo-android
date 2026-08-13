package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Glass-capsule scrubber matching `iosApp/.../tvOS/TVPlayerScrubber.swift`.
 *
 * The Compose remote-key model differs from tvOS's `MoveCommand` /
 * `PressCapture` split: ACTION_DOWN repeats fire while a key is held, so we
 * derive "tap" vs "hold" from press duration (a single short press without
 * a long-press timer firing = tap; a held press triggers timeline auto-seek).
 *
 * - **Tap left/right** (idle): ±10 s quick skip via [onSkipBack] / [onSkipForward].
 * - **Tap left/right** (timeline scrub): ±10 s nudge of the in-flight preview.
 * - **Hold left/right**: enter timeline auto-seek at ±2x, doubling every
 *   900 ms up to a ceiling derived from the item's runtime — ±256x for a
 *   22-minute episode, ±1024x for a feature — or tap again to bump the rate
 *   by hand. See [TvSeekRateLadder.maxRateFor].
 * - **OK / Select**: commit an in-flight preview (or enter timeline scrub).
 * - **Back / Down**: cancel the preview / move focus to transport.
 *
 * Visual layout follows the spec: 7 dp track unfocused → 12 dp during
 * timeline scrub, 30 dp puck unfocused → 42 dp focused/scrubbing, chapter
 * ticks rendered as 3 dp white verticals on top of the played fill.
 */
/**
 * Lightweight chapter marker. Mirrors `PlayerCore.ChapterInfo` on tvOS — name
 * + start time in seconds. Defined locally so the scrubber stays decoupled
 * from any specific ExoPlayer/Media3 chapter representation.
 */
data class ChapterInfo(
    val timeSec: Double,
    val title: String? = null,
)

internal fun playerScrubberLabelPosition(
    positionSec: Double,
    scrubPreviewSec: Double,
    isScrubbing: Boolean,
): Double = if (isScrubbing) scrubPreviewSec else positionSec

@Composable
fun TvPlayerScrubber(
    positionSec: Double,
    durationSec: Double,
    bufferedAheadSec: Double,
    isScrubbing: Boolean,
    scrubPreviewSec: Double,
    chapters: List<ChapterInfo>,
    cancelOnBlur: Boolean,
    // Detected marker bands [startSec, endSec] drawn on the track when known.
    // Null = no band. Mirrors tvOS TVPlayerScrubber.introRegion.
    introRangeSec: ClosedRange<Double>? = null,
    creditsRangeSec: ClosedRange<Double>? = null,
    recapRangeSec: ClosedRange<Double>? = null,
    previewRangeSec: ClosedRange<Double>? = null,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onBeginScrub: () -> Unit,
    onUpdateScrub: (Double) -> Unit,
    onCommitScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    onRequestFocus: FocusRequester,
    /**
     * Toggle play/pause. Center on the bar is bound to this, not to entering a
     * scrub: the Google TV remote has no dedicated play/pause key, so Center
     * with the overlay up is the only one-press pause a viewer has — and it is
     * what every other TV player does. Scrubbing does not need it; Left/Right
     * skip and long-press engages auto-seek.
     */
    onPlayPause: () -> Unit,
    /** See TvPlayerIdleOverlay.canToggleAfterCommit. */
    canToggleAfterCommit: Boolean = true,
    onMoveDownToTransport: () -> Unit,
    onExitWhenIdle: () -> Unit,
    onRateChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Live preview value. The auto-seek loop below runs across recompositions, so
    // it must read the CURRENT preview (which onBeginScrub seeds from the playback
    // position and each tick advances) instead of the closure-captured parameter
    // value frozen at launch — otherwise continuous scanning never advances and a
    // commit seeks to ~0.
    val currentPreviewSec by rememberUpdatedState(scrubPreviewSec)

    // Trailing-edge auto-seek state. tvOS owns this in the scrubber view too.
    var isTimelineScrubbing by remember { mutableStateOf(false) }
    var autoSeekRate by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var autoSeekJob by remember { mutableStateOf<Job?>(null) }
    // Speeds and ramp live in TvSeekRateLadder so the chip's number and the
    // distance actually travelled cannot drift apart again.
    var holdRampJob by remember { mutableStateOf<Job?>(null) }

    fun setRate(rate: Int) {
        autoSeekRate = rate
        onRateChanged(rate)
    }

    fun stopAutoSeek() {
        autoSeekJob?.cancel()
        autoSeekJob = null
        holdRampJob?.cancel()
        holdRampJob = null
        setRate(0)
    }

    fun beginAutoSeek(direction: Int) {
        if (!isScrubbing) onBeginScrub()
        isTimelineScrubbing = true
        val sign = if (direction < 0) -1 else 1
        setRate(TvSeekRateLadder.BASE_RATE * sign)
        autoSeekJob?.cancel()
        autoSeekJob = scope.launch {
            while (isActive) {
                // Delay first so onBeginScrub's position seed lands in
                // currentPreviewSec before the first tick reads it (otherwise the
                // first update would overwrite the seed and scanning starts at ~0).
                delay(TvSeekRateLadder.TICK_MILLIS)
                val rate = autoSeekRate
                if (rate == 0) break
                val base = currentPreviewSec + TvSeekRateLadder.tickSeconds(rate)
                onUpdateScrub(base)
            }
        }
        // Sustained progression through the ladder. Each step only fires if the
        // viewer is still holding the same direction at the rate the previous
        // step left — otherwise a release and a fresh press the other way would
        // be overwritten by a timer from the abandoned hold.
        holdRampJob?.cancel()
        holdRampJob = scope.launch {
            var previous = TvSeekRateLadder.BASE_RATE * sign
            repeat(TvSeekRateLadder.rampSteps(durationSec)) { step ->
                delay(TvSeekRateLadder.RAMP_STEP_MILLIS)
                // Only continue while the viewer is still holding at the rate
                // the previous step left; a release and a fresh press the other
                // way must not be overwritten by this hold's timer.
                if (autoSeekRate != previous) return@launch
                val next = TvSeekRateLadder.sustainedRate(step, sign, durationSec)
                if (next == previous) return@launch
                setRate(next)
                previous = next
            }
        }
    }

    fun bumpRate(delta: Int) {
        val next = TvSeekRateLadder.bumped(autoSeekRate, delta, durationSec)
        if (next == autoSeekRate) return
        // User-driven rate change cancels the time-based ramp so it doesn't
        // overwrite the manual pick a beat later.
        holdRampJob?.cancel()
        holdRampJob = null
        setRate(next)
    }

    // Cancel any in-flight scrub on focus loss when the shell asks us to
    // (HUD opens, screen exit). Otherwise treat blur as commit.
    LaunchedEffect(isFocused) {
        if (!isFocused && (isScrubbing || isTimelineScrubbing)) {
            isTimelineScrubbing = false
            stopAutoSeek()
            if (cancelOnBlur) onCancelScrub() else onCommitScrub()
        }
    }

    // An external cancel (remote-key bridge Back, setControlsVisible(false))
    // clears the VM's isScrubbing without any key event or blur reaching this
    // composable — resync the local timeline state and kill the auto-seek loop
    // so it can't keep advancing a preview the VM already dropped.
    LaunchedEffect(isScrubbing) {
        if (!isScrubbing && isTimelineScrubbing) {
            isTimelineScrubbing = false
            stopAutoSeek()
        }
    }

    val totalProgress = if (durationSec > 0) {
        ((if (isScrubbing) scrubPreviewSec else positionSec) / durationSec)
            .toFloat().coerceIn(0f, 1f)
    } else 0f
    val labelPositionSec = playerScrubberLabelPosition(positionSec, scrubPreviewSec, isScrubbing)
    val bufferedFrac = if (durationSec > 0) {
        ((positionSec + bufferedAheadSec) / durationSec).toFloat().coerceIn(0f, 1f)
    } else 0f

    val trackHeight by animateDpAsState(
        targetValue = if (isTimelineScrubbing) 6.dp else 3.5.dp,
        animationSpec = tween(120),
        label = "scrubberTrackHeight",
    )
    val puckSize by animateDpAsState(
        targetValue = when {
            isTimelineScrubbing -> 21.dp
            isFocused -> 21.dp
            else -> 15.dp
        },
        animationSpec = tween(120),
        label = "scrubberPuckSize",
    )

    Column(
        modifier = modifier.height(41.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatScrubberTime(labelPositionSec),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = formatRemainingTime(durationSec - labelPositionSec),
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            // Auto-seek visualization is owned by [TvHoldSeekIndicator] rendered
            // by the parent overlay so it can float top-center above the
            // transport instead of crowding the scrubber.

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .height(28.dp)
                .focusRequester(onRequestFocus)
                .onFocusChanged { /* state collected via interactionSource */ }
                .focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { event ->
                    val isDown = event.type == KeyEventType.KeyDown
                    val isUp = event.type == KeyEventType.KeyUp
                    val nativeRepeat = event.nativeKeyEvent.repeatCount
                    when (event.key) {
                        Key.DirectionLeft -> {
                            // KeyUp cancels the time-based ramp coroutine —
                            // user has released the D-pad, so further rate
                            // climbs would be unsolicited. The auto-seek loop
                            // itself stays alive until commit/cancel.
                            if (isUp) {
                                holdRampJob?.cancel()
                                holdRampJob = null
                                return@onPreviewKeyEvent false
                            }
                            if (!isDown) return@onPreviewKeyEvent false
                            // First repeat (count==1) marks "long-press" —
                            // engage timeline auto-seek mode. Standalone
                            // taps (repeat==0 followed by KeyUp) fall back
                            // to ±10s skip in idle, or chip rate-bump in
                            // auto-seek.
                            if (nativeRepeat == 1) {
                                if (autoSeekRate == 0) beginAutoSeek(-1)
                                return@onPreviewKeyEvent true
                            }
                            if (nativeRepeat > 1) return@onPreviewKeyEvent true
                            if (autoSeekRate != 0) {
                                bumpRate(-1)
                            } else if (isTimelineScrubbing) {
                                onUpdateScrub(scrubPreviewSec - 10.0)
                            } else {
                                onSkipBack()
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (isUp) {
                                holdRampJob?.cancel()
                                holdRampJob = null
                                return@onPreviewKeyEvent false
                            }
                            if (!isDown) return@onPreviewKeyEvent false
                            if (nativeRepeat == 1) {
                                if (autoSeekRate == 0) beginAutoSeek(1)
                                return@onPreviewKeyEvent true
                            }
                            if (nativeRepeat > 1) return@onPreviewKeyEvent true
                            if (autoSeekRate != 0) {
                                bumpRate(1)
                            } else if (isTimelineScrubbing) {
                                // Forward nudge matches the 30s transport skip
                                // (back nudge stays 10s), mirroring tvOS
                                // scrubForwardStep/scrubBackwardStep.
                                onUpdateScrub(scrubPreviewSec + 30.0)
                            } else {
                                onSkipForward()
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (isDown) {
                                onMoveDownToTransport()
                                true
                            } else false
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (isUp) {
                                stopAutoSeek()
                                // Center means "here": land any scrub in
                                // flight, then flip playback. Racing forward at
                                // 32x it stops on the frame you asked for;
                                // hunting a spot while paused it plays on from
                                // it. Entering a scrub MODE here — what this
                                // used to do — spent the viewer's only
                                // one-press pause on something Left/Right
                                // already do, and the Google TV remote has no
                                // dedicated play/pause key to fall back on.
                                val committed = isTimelineScrubbing || isScrubbing
                                if (committed) {
                                    isTimelineScrubbing = false
                                    onCommitScrub()
                                }
                                if (!committed || canToggleAfterCommit) onPlayPause()
                                true
                            } else if (isDown) true else false
                        }
                        Key.Back, Key.Escape -> {
                            if (isDown) {
                                if (isTimelineScrubbing || isScrubbing) {
                                    isTimelineScrubbing = false
                                    stopAutoSeek()
                                    onCancelScrub()
                                    true
                                } else {
                                    onExitWhenIdle()
                                    true
                                }
                            } else false
                        }
                        else -> false
                    }
                },
        ) {
            val barWidthDp = maxWidth
            val density = LocalDensity.current
            val barWidthPx = with(density) { barWidthDp.toPx() }
            val puckSizePx = with(density) { puckSize.toPx() }

            // Track (unfocused 0.24, focused 0.35, scrubbing 0.48 — spec).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Color.White.copy(
                            alpha = when {
                                isTimelineScrubbing -> 0.48f
                                isFocused -> 0.35f
                                else -> 0.24f
                            },
                        ),
                    ),
            )

            // Marker bands — intro/recap/credits/preview, each a tinted band on
            // the track. Drawn above the bare track but below the played fill /
            // ticks so the playhead still reads clearly over it.
            if (durationSec > 0) {
                val bandAlpha = if (isTimelineScrubbing || isFocused) 0.45f else 0.34f
                val markers = listOfNotNull(
                    introRangeSec?.let { it to Color.Cyan },
                    recapRangeSec?.let { it to Color(0xFF8BC34A) },
                    creditsRangeSec?.let { it to Color(0xFFFFB74D) },
                    previewRangeSec?.let { it to Color(0xFFBA68C8) },
                )
                for ((range, color) in markers) {
                    val start = (range.start / durationSec).toFloat().coerceIn(0f, 1f)
                    val end = (range.endInclusive / durationSec).toFloat().coerceIn(0f, 1f)
                    if (end > start) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = barWidthDp * start)
                                .fillMaxWidth(end - start)
                                .height(trackHeight)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(color.copy(alpha = bandAlpha)),
                        )
                    }
                }
            }

            // Played fill — pure white, follows the preview while scrubbing.
            Box(
                modifier = Modifier
                    .fillMaxWidth(totalProgress)
                    .align(Alignment.CenterStart)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White),
            )

            // Buffered-ahead sliver: only the region between playhead and
            // end-of-buffer. Width can be 0 (live HLS, transcode start).
            val bufferedAheadFrac = (bufferedFrac - totalProgress).coerceAtLeast(0f)
            if (bufferedAheadFrac > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = barWidthDp * totalProgress)
                        .fillMaxWidth(bufferedAheadFrac)
                        .height(trackHeight)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.28f)),
                )
            }

            // Chapter marker ticks — skip the chapter-0 tick at x≈0 so it doesn't
            // sit under the capsule endcap. Iterate with `for` (rather than
            // `forEach`) so the lambda body keeps composable scope.
            if (durationSec > 0) {
                for (ch in chapters) {
                    val frac = (ch.timeSec / durationSec).toFloat().coerceIn(0f, 1f)
                    if (frac > 0.001f) {
                        Box(
                            modifier = Modifier
                                // CenterStart, not Center: `offset` is anchor-relative,
                                // so Center adds half the bar width to every tick.
                                .align(Alignment.CenterStart)
                                .offset(x = barWidthDp * frac - 1.5.dp)
                                .width(if (isTimelineScrubbing) 3.dp else 2.dp)
                                .height(trackHeight + 8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color.White.copy(alpha = 0.45f)),
                        )
                    }
                }
            }

            // Puck — only while focused so the bar stays passive when
            // attention is elsewhere on the overlay.
            if (isFocused || isTimelineScrubbing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                x = (barWidthPx * totalProgress - puckSizePx / 2f).roundToInt(),
                                y = 0,
                            )
                        }
                        .size(puckSize)
                        .shadow(8.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

}

private fun formatScrubberTime(seconds: Double): String = formatTimelineClock(seconds)

private fun formatRemainingTime(secondsRemaining: Double): String =
    "-${formatTimelineClock(secondsRemaining.coerceAtLeast(0.0))}"

private fun formatTimelineClock(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
