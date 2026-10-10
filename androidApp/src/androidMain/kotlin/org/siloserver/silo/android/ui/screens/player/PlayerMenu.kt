package org.siloserver.silo.android.ui.screens.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How player menus present themselves.
 *
 * [Docked] is the landscape treatment: the menu docks as a smoked panel on the
 * right edge and the picture stays live beside it, so a subtitle or style
 * change is visible as it is made. [Sheet] is the portrait (and tabletop)
 * bottom sheet. The content of every menu is identical in both.
 */
internal enum class PlayerMenuPresentation { Sheet, Docked }

internal val LocalPlayerMenuPresentation = staticCompositionLocalOf { PlayerMenuPresentation.Sheet }

/**
 * Reports the left edge (root px) of an open docked panel, or null once it
 * closes, so the subtitle canvas can re-center in the part of the picture that
 * is still visible.
 */
internal val LocalPlayerMenuDockReporter = staticCompositionLocalOf<(Float?) -> Unit> { {} }

/**
 * Lets one docked menu hand over to the next without the panel sliding out and
 * back in: "Settings → Subtitle style" swaps the content in place.
 */
@Stable
internal class PlayerMenuHandoff {
    internal var handedOffAtMs: Long = 0L

    internal fun consumeRecent(): Boolean {
        val recent = SystemClock.uptimeMillis() - handedOffAtMs < HANDOFF_WINDOW_MS
        handedOffAtMs = 0L
        return recent
    }

    private companion object {
        const val HANDOFF_WINDOW_MS = 400L
    }
}

internal val LocalPlayerMenuHandoff = staticCompositionLocalOf { PlayerMenuHandoff() }

/** Landscape windows wide enough to keep a useful picture beside the panel. */
internal fun playerMenuPresentationFor(
    widthDp: Float,
    heightDp: Float,
    tabletopMode: Boolean,
): PlayerMenuPresentation =
    if (!tabletopMode && widthDp > heightDp && widthDp >= 560f) {
        PlayerMenuPresentation.Docked
    } else {
        PlayerMenuPresentation.Sheet
    }

@OptIn(ExperimentalMaterial3Api::class)
@Stable
internal class PlayerMenuController internal constructor(
    val presentation: PlayerMenuPresentation,
    val sheetState: SheetState,
    val panelState: MutableTransitionState<Boolean>,
    private val scope: CoroutineScope,
    private val handoff: PlayerMenuHandoff,
    private val onDismiss: State<() -> Unit>,
) {
    /**
     * Closes the menu, then runs [then]. A docked menu that hands over to
     * another menu (a non-null [then]) swaps in place instead of animating out.
     */
    fun dismiss(then: (() -> Unit)? = null) {
        when (presentation) {
            PlayerMenuPresentation.Sheet -> scope.launch {
                sheetState.hide()
                onDismiss.value()
                then?.invoke()
            }
            PlayerMenuPresentation.Docked -> if (then != null) {
                handoff.handedOffAtMs = SystemClock.uptimeMillis()
                onDismiss.value()
                then()
            } else {
                panelState.targetState = false
                scope.launch {
                    snapshotFlow { panelState.isIdle && !panelState.currentState }.first { it }
                    onDismiss.value()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberPlayerMenuController(onDismiss: () -> Unit): PlayerMenuController {
    val presentation = LocalPlayerMenuPresentation.current
    val handoff = LocalPlayerMenuHandoff.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val latestDismiss = rememberUpdatedState(onDismiss)
    val panelState = remember {
        MutableTransitionState(handoff.consumeRecent()).apply { targetState = true }
    }
    return remember(presentation, sheetState, panelState) {
        PlayerMenuController(presentation, sheetState, panelState, scope, handoff, latestDismiss)
    }
}

/**
 * The container every player menu renders into. Content is a column; menus
 * that scroll put their list in [PlayerMenuScrollColumn] so it takes the room
 * left under the header without pushing a footer off screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerMenu(
    controller: PlayerMenuController,
    tabletopPaneHeight: Dp?,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (controller.presentation) {
        PlayerMenuPresentation.Sheet -> {
            LaunchedEffect(Unit) { controller.sheetState.show() }
            PlayerModalBottomSheet(
                onDismissRequest = { controller.dismiss() },
                sheetState = controller.sheetState,
                tabletopPaneHeight = tabletopPaneHeight,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .playerSheetContent(tabletopPaneHeight)
                        .nestedScroll(PlayerSheetFlingGuard)
                        .padding(bottom = 12.dp),
                    content = content,
                )
            }
        }
        PlayerMenuPresentation.Docked -> PlayerDockedPanel(controller, content)
    }
}

@Composable
private fun PlayerDockedPanel(
    controller: PlayerMenuController,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reportDockEdge = LocalPlayerMenuDockReporter.current
    BackHandler { controller.dismiss() }
    DisposableEffect(Unit) {
        onDispose { reportDockEdge(null) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(20f)) {
        val panelWidth = minOf(PlayerChrome.PanelWidth, maxWidth * 0.46f).coerceAtLeast(320.dp)
        // The camera cutout can sit on the right in one landscape and not the
        // other; clear it so the panel never runs under the camera.
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val cutoutEnd = with(density) {
            WindowInsets.displayCutout.getRight(this, layoutDirection).toDp()
        }

        AnimatedVisibility(
            visibleState = controller.panelState,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160)),
        ) {
            // Darken only the panel's side; the picture on the left stays as
            // bright as it plays. A tap anywhere outside the panel closes it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.40f to Color.Transparent,
                            0.62f to Color.Black.copy(alpha = 0.45f),
                        ),
                    )
                    .pointerInput(Unit) { detectTapGestures { controller.dismiss() } },
            )
        }

        AnimatedVisibility(
            visibleState = controller.panelState,
            enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 3 } +
                fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 3 } +
                fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val shape = RoundedCornerShape(PlayerChrome.PanelRadius)
            Column(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 10.dp, end = 10.dp + cutoutEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .onGloballyPositioned { reportDockEdge(it.boundsInRoot().left) }
                    .clip(shape)
                    .background(PlayerChrome.Smoke)
                    .border(PlayerPanelBorder, shape)
                    // Taps on the panel's own empty space must not fall through
                    // to the dismiss scrim underneath.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(bottom = 6.dp),
                content = content,
            )
        }
    }
}

/**
 * The scrolling body of a menu: fills what the header and footer leave, wraps
 * when shorter, and fades its bottom edge instead of cutting a row in half.
 */
@Composable
internal fun ColumnScope.PlayerMenuScrollColumn(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .weight(1f, fill = false)
            .fillMaxWidth()
            .playerFadingEdges(scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = horizontalPadding),
        content = content,
    )
}

/**
 * Fades the top and bottom edges of a scrolling list while there is more to
 * scroll in that direction, so the last visible row reads as "continues"
 * rather than as clipped.
 */
internal fun Modifier.playerFadingEdges(
    state: ScrollableState,
    top: Dp = 16.dp,
    bottom: Dp = 40.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (state.canScrollBackward) {
            val height = top.toPx()
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = height),
                size = Size(size.width, height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            val height = bottom.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    1f to Color.Transparent,
                    startY = size.height - height,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - height),
                size = Size(size.width, height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
