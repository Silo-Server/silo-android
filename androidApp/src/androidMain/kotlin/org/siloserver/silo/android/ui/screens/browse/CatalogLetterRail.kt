package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.siloserver.silo.common.R as CommonR
import kotlin.math.abs
import kotlin.math.roundToInt

/** `#` holds titles that start with a digit, a symbol or another script. */
internal val LetterRailEntries: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

/** Room the grid leaves on its trailing edge for the rail, which takes touches across all of it. */
internal val LetterRailReservedWidth = 28.dp
private val RailWidth = 20.dp
private val MaxRowHeight = 17.dp
/** Below this, rows alternate letters with dots while touches still reach every letter. */
private val MinRowHeight = 13.dp
private val BubbleSize = 64.dp
private val BubbleGap = 18.dp
/** How far from the finger letters still swell. */
private val LensRadius = 54.dp
private val LensLean = 10.dp
/** How long the finger rests on a letter before the grid jumps there. */
internal const val LetterRailRestMillis = 220L

/**
 * The letter a touch on entry [entry] of [LetterRailEntries] goes to: itself
 * when it has titles, otherwise the next letter that does, or the last one
 * before it when none after it does. Null when no letter has titles.
 */
internal fun letterRailTarget(entry: Int, available: Set<String>): String? {
    val index = entry.coerceIn(0, LetterRailEntries.lastIndex)
    return LetterRailEntries.subList(index, LetterRailEntries.size).firstOrNull { it in available }
        ?: LetterRailEntries.subList(0, index).lastOrNull { it in available }
}

/** One row of the rail: the entry it stands for, drawn as a dot when the rail is short. */
private data class RailRow(val id: Int, val entry: Int, val isDot: Boolean)

/**
 * Every entry when the rail is tall enough; otherwise an odd number of rows
 * alternating letters and dots, each standing for the entry its centre maps
 * to. Entries in [shown] take over the row they fall in, so the touched and
 * jumped letters never hide behind a dot.
 */
private fun railRows(rowCapacity: Int, shown: List<Int>): List<RailRow> {
    val count = LetterRailEntries.size
    if (rowCapacity >= count) return List(count) { RailRow(it, it, isDot = false) }
    var rowCount = rowCapacity.coerceAtLeast(3)
    if (rowCount % 2 == 0) rowCount -= 1
    val rows = MutableList(rowCount) { row ->
        val entry = minOf(count - 1, ((row + 0.5f) / rowCount * count).toInt())
        RailRow(row, entry, isDot = row % 2 != 0)
    }
    for (entry in shown) {
        val row = minOf(rowCount - 1, ((entry + 0.5f) / count * rowCount).toInt())
        rows[row] = RailRow(row, entry, isDot = false)
    }
    return rows
}

/**
 * A–Z index on the trailing edge of a title-ordered library grid, like the
 * section index in Contacts.
 *
 * Dragging along the rail previews letters with a bubble, a lens that swells
 * the letters near the finger, and a tick per letter. The grid jumps when the
 * finger rests on a letter for [LetterRailRestMillis] or lifts, so a drag
 * across the alphabet scrolls the grid once rather than once per letter.
 * Letters without titles are dimmed; a touch on one goes to the next letter
 * that has titles. [jumped] is drawn with a soft disc until the grid clears it.
 */
@Composable
internal fun CatalogLetterRail(
    available: Set<String>,
    jumped: String?,
    /** Each letter the finger reaches, before it rests or lifts there. */
    onPreview: (String) -> Unit,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentAvailable by rememberUpdatedState(available)
    val currentOnPreview by rememberUpdatedState(onPreview)
    val currentOnJump by rememberUpdatedState(onJump)

    /** Where the finger is along the rail while it is down. */
    var fingerY by remember { mutableStateOf<Float?>(null) }
    /** The finger's last spot, kept while the lens settles after a lift. */
    var lensY by remember { mutableStateOf(0f) }
    val lens = remember { Animatable(0f) }
    /** The letter this touch last jumped to, so a lift there doesn't jump again. */
    var jumpedThisTouch by remember { mutableStateOf<String?>(null) }
    val railLabel = stringResource(CommonR.string.library_direction_az)

    BoxWithConstraints(modifier = modifier.width(LetterRailReservedWidth), contentAlignment = Alignment.Center) {
        val railHeight = min(maxHeight, MaxRowHeight * LetterRailEntries.size)
        val railHeightPx = with(density) { railHeight.toPx() }
        val lensRadiusPx = with(density) { LensRadius.toPx() }
        val leanPx = with(density) { LensLean.toPx() } * (if (rtl) 1f else -1f)

        fun entryAt(y: Float): Int =
            if (railHeightPx <= 0f) 0
            else (y / railHeightPx * LetterRailEntries.size).toInt().coerceIn(0, LetterRailEntries.lastIndex)

        fun entryCenter(entry: Int): Float = (entry + 0.5f) * railHeightPx / LetterRailEntries.size

        fun jump(letter: String) {
            jumpedThisTouch = letter
            currentOnJump(letter)
        }

        val touched = fingerY?.let(::entryAt)
        val target = touched?.let { letterRailTarget(it, available) }

        // A resting finger jumps; a moving one only previews. Restarts with
        // each new letter and ends when the finger lifts.
        LaunchedEffect(target) {
            val letter = target ?: return@LaunchedEffect
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            currentOnPreview(letter)
            delay(LetterRailRestMillis)
            if (letter != jumpedThisTouch) jump(letter)
        }
        LaunchedEffect(fingerY != null) {
            lens.animateTo(
                if (fingerY != null) 1f else 0f,
                spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
            )
        }

        val marked = jumped?.let { LetterRailEntries.indexOf(it) }?.takeIf { it >= 0 }
        val rowCapacity = (railHeight / MinRowHeight).toInt()
        val rows = railRows(rowCapacity, listOfNotNull(marked, touched))
        val rowHeight = railHeight / rows.size
        val rowHeightPx = railHeightPx / rows.size

        Box(
            modifier = Modifier
                .width(LetterRailReservedWidth)
                .height(railHeight)
                // The rail sits in the system back gesture's edge zone; claim
                // it (the system honours up to 200dp of it per edge).
                .systemGestureExclusion()
                .semantics {
                    contentDescription = railLabel
                    jumped?.let { stateDescription = it }
                    // Every letter with titles, including those a short rail
                    // draws as dots, is reachable from TalkBack's actions.
                    customActions = LetterRailEntries.filter { it in available }.map { letter ->
                        // Not jump(): that marks the touch, which would make the
                        // next touch on this letter do nothing.
                        CustomAccessibilityAction(letter) { currentOnJump(letter); true }
                    }
                }
                .pointerInput(railHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var y = down.position.y.coerceIn(0f, railHeightPx)
                        fingerY = y
                        lensY = y
                        var lifted = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                // The release event can carry a last move; the
                                // lift jumps where the finger actually left.
                                y = change.position.y.coerceIn(0f, railHeightPx)
                                if (!change.pressed) {
                                    lifted = true
                                    break
                                }
                                fingerY = y
                                lensY = y
                                change.consume()
                            }
                        } finally {
                            fingerY = null
                        }
                        if (lifted) {
                            val letter = letterRailTarget(entryAt(y), currentAvailable)
                            if (letter != null && letter != jumpedThisTouch) jump(letter)
                        }
                        jumpedThisTouch = null
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // A capsule behind the letters while a finger is on them.
            AnimatedVisibility(visible = touched != null, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .width(RailWidth + 6.dp)
                        .height(railHeight)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)),
                )
            }
            Column(
                modifier = Modifier.width(RailWidth).height(railHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rows.forEach { row ->
                    val letter = LetterRailEntries[row.entry]
                    val hasTitles = letter in available
                    val isTouched = !row.isDot && row.entry == touched
                    val isMarked = !row.isDot && letter == jumped
                    val rowCenter = (row.id + 0.5f) * rowHeightPx
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .graphicsLayer {
                                // Read here, not in composition, so the lens
                                // follows the finger without recomposing.
                                val t = (1f - abs(lensY - rowCenter) / lensRadiusPx).coerceIn(0f, 1f)
                                val swell = t * t * (3f - 2f * t) * lens.value
                                scaleX = 1f + 0.55f * swell
                                scaleY = scaleX
                                translationX = leanPx * swell
                                transformOrigin = TransformOrigin.Center
                            }
                            .then(
                                if (!row.isDot && hasTitles) {
                                    Modifier.semantics {
                                        contentDescription = letter
                                        onClick { currentOnJump(letter); true }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isMarked) {
                            // A soft disc, quieter than the bubble: where the jump went.
                            Box(
                                modifier = Modifier
                                    .size(min(rowHeight + 4.dp, 20.dp))
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                            )
                        }
                        if (row.isDot) {
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        } else {
                            Text(
                                text = letter,
                                color = if (isTouched || isMarked) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = with(density) { minOf(11.5.sp.toPx(), rowHeightPx * 0.74f).toSp() },
                                fontWeight = if (isTouched || isMarked) FontWeight.ExtraBold else FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { alpha = if (hasTitles) 1f else 0.35f },
                            )
                        }
                    }
                }
            }
        }

        // The bubble, beside the rail at the touched letter, toward the grid.
        val bubbleLetter = target
        val bubbleSizePx = with(density) { BubbleSize.toPx() }
        val bubbleShiftPx = with(density) { (RailWidth / 2 + BubbleGap + BubbleSize / 2).toPx() }
        AnimatedVisibility(
            visible = bubbleLetter != null,
            enter = fadeIn() + scaleIn(initialScale = 0.4f, transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)),
            exit = fadeOut() + scaleOut(targetScale = 0.25f, transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    val entry = touched ?: 0
                    val railTop = (constraints.maxHeight - railHeightPx) / 2f
                    IntOffset(
                        // offset {} mirrors x in RTL, so negative is toward the grid either way.
                        x = (-bubbleShiftPx).roundToInt(),
                        y = (railTop + entryCenter(entry) - bubbleSizePx / 2f).roundToInt(),
                    )
                },
        ) {
            var shown by remember { mutableStateOf("") }
            if (bubbleLetter != null) shown = bubbleLetter
            Box(
                modifier = Modifier
                    // Wider than the rail's column; overflows it toward the grid.
                    .requiredSize(BubbleSize)
                    .shadow(14.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = shown,
                    color = MaterialTheme.colorScheme.background,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
