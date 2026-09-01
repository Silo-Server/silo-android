package org.siloserver.silo.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

private const val DismissFraction = 0.35f
private val FlingMinTravel = 24.dp
private const val FlingVelocityPxPerSec = 1800f
private const val MinScale = 0.96f
private val CornerRadius = 24.dp

class SwipeDownDismissState internal constructor() {
    // Gesture callbacks update this synchronously on the UI thread. The old
    // implementation launched one snap coroutine per pointer delta, which
    // could queue updates and make the card trail the finger.
    internal var offsetPx by mutableFloatStateOf(0f)
    internal var dismissing by mutableStateOf(false)

    /**
     * Pop immediately so Navigation restores the real source page and moves
     * the outgoing card itself. Keeping no separate local exit animation
     * avoids leaving an invisible, touch-blocking destination after the card
     * has visibly cleared the screen.
     */
    fun dismiss(onDismiss: () -> Unit) {
        if (dismissing) return
        dismissing = true
        onDismiss()
    }
}

@Composable
fun rememberSwipeDownDismissState(): SwipeDownDismissState =
    remember { SwipeDownDismissState() }

/**
 * iOS-style interactive "swipe back": a rightward drag on the page moves it
 * with the finger (slight shrink, corners rounding as it lifts), and
 * releasing past [DismissFraction] of the width — or flicking fast — calls
 * [onDismiss]; anything short springs back.
 *
 * Attach to the page root. It is a horizontal draggable, so it only receives
 * drags that no child consumed: vertical lists scroll as usual, and
 * horizontal rails / pagers keep their own swipes. On gesture-nav devices the
 * far-left edge still belongs to the system back gesture; this covers the
 * rest of the page.
 */
@Composable
fun Modifier.swipeBackToDismiss(
    onDismiss: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    val flingMinTravelPx = with(density) { FlingMinTravel.toPx() }
    val cornerPx = with(density) { CornerRadius.toPx() }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    var dismissing by remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    val dragState = rememberDraggableState { delta ->
        if (dismissing) return@rememberDraggableState
        // Only ever move right; a leftward drag past home is ignored.
        val next = (offset.value + delta).coerceAtLeast(0f)
        scope.launch { offset.snapTo(next) }
    }

    return this
        .onSizeChanged { widthPx = it.width }
        .draggable(
            state = dragState,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStopped = { velocity ->
                if (dismissing || offset.value <= 0f) return@draggable
                val threshold = widthPx * DismissFraction
                val flick = velocity > FlingVelocityPxPerSec && offset.value > flingMinTravelPx
                if (offset.value >= threshold || flick) {
                    dismissing = true
                    // Finish in the composable's scope, not this suspend
                    // callback: a new touch during the slide-off cancels
                    // onDragStopped, which used to strand the page mid-way
                    // with the dismiss never delivered. The pop is called
                    // even if the animation is interrupted.
                    scope.launch {
                        try {
                            offset.animateTo(widthPx.toFloat(), tween(durationMillis = 180))
                        } finally {
                            currentOnDismiss()
                        }
                    }
                } else {
                    offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                }
            },
        )
        .graphicsLayer {
            // Reads happen in the draw phase, so dragging redraws the layer
            // without recomposing the page.
            val progress = if (widthPx > 0) (offset.value / widthPx).coerceIn(0f, 1f) else 0f
            translationX = offset.value
            val scale = 1f - (1f - MinScale) * progress
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0.5f)
            clip = progress > 0f
            shape = RoundedCornerShape(cornerPx * (progress * 4f).coerceAtMost(1f))
        }
}

/**
 * Sheet-style downward dismissal that activates only from unconsumed downward
 * scroll (normally when the detail list is already at its top). This preserves
 * ordinary vertical scrolling in the page while matching the mobile detail
 * card's pull-down-to-close interaction.
 */
@Composable
fun Modifier.swipeDownToDismiss(
    state: SwipeDownDismissState,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val thresholdPx = with(density) { 140.dp.toPx() }
    val flingMinTravelPx = with(density) { 24.dp.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val connection = remember(state, thresholdPx, flingMinTravelPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                if (state.dismissing || state.offsetPx <= 0f || available.y >= 0f) return Offset.Zero
                val consumed = maxOf(available.y, -state.offsetPx)
                state.offsetPx = (state.offsetPx + consumed).coerceAtLeast(0f)
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                // Only a real finger pull on the detail page may arm its
                // dismissal. Modal picker sheets also animate downward when an
                // option is selected; accepting their side-effect scroll here
                // popped the entire detail route as the selector closed.
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput ||
                    available.y <= 0f || state.dismissing
                ) {
                    return Offset.Zero
                }
                state.offsetPx = (state.offsetPx + available.y * 0.72f)
                    .coerceAtMost(thresholdPx * 1.4f)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val intentionalFlick = state.offsetPx >= flingMinTravelPx &&
                    available.y > FlingVelocityPxPerSec
                if (state.offsetPx >= thresholdPx || intentionalFlick) {
                    state.dismiss(onDismiss = currentOnDismiss)
                } else if (state.offsetPx > 0f) {
                    Animatable(state.offsetPx).animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    ) {
                        state.offsetPx = value
                    }
                }
                return Velocity.Zero
            }
        }
    }
    return this
        .nestedScroll(connection)
        .graphicsLayer {
            translationY = state.offsetPx
            val progress = (state.offsetPx / thresholdPx).coerceIn(0f, 1f)
            val scale = 1f - progress * 0.025f
            scaleX = scale
            scaleY = scale
        }
}
