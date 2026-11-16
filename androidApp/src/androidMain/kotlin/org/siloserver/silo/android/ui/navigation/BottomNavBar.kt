package org.siloserver.silo.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import kotlin.math.min
import kotlin.math.roundToInt
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloNavPillContent
import org.siloserver.silo.android.ui.theme.SiloNavPillSelected
import org.siloserver.silo.android.ui.theme.SiloNavPillSelectedContent
import org.siloserver.silo.android.ui.theme.SiloNavPillSurface

/**
 * Bottom padding for scrolling tab content: the height of the translucent
 * bottom chrome (accessory bar + nav bar + gesture inset). Tab content scrolls
 * edge-to-edge underneath the chrome (iOS glass tab bar behavior), so
 * scrollable screens add this to their bottom content padding to keep their
 * last items reachable above it. Zero outside the tab scaffold.
 *
 * It never shrinks while the tab is on screen (see [rememberNonShrinking]):
 * the chrome gets shorter as the bar minimizes with its accessory inline, or
 * when a now-playing bar ends, and shrinking the padding then would pull a
 * list resting at its end downward. Anything that floats just above the
 * chrome uses [LocalBottomChromeHeight] instead.
 */
val LocalBottomChromeInset = compositionLocalOf { 0.dp }

/**
 * The bottom chrome's live height, following it as it minimizes and expands.
 * For elements pinned just above the chrome (the Libraries dock while a
 * now-playing bar holds the tab bar's accessory slot), not for scroll padding.
 * It changes every frame while the bar moves, so read it only at placement
 * time (`Modifier.offset {}`), never in composition. Zero outside the tab
 * scaffold.
 */
val LocalBottomChromeHeight = staticCompositionLocalOf<() -> Dp> { { 0.dp } }

/**
 * Tell the tab shell that the page moved itself to its top without a user
 * scroll (scroll-to-top on reselect), so the minimized tab bar expands as it
 * does when the user scrolls there. A no-op outside the tab scaffold.
 */
val LocalBottomNavScrolledToTop = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Tell the tab shell that the page is about to move itself somewhere other
 * than its top (a jump to a day or a letter), so the shell stops trusting the
 * position it has followed until the page is next at its top. A no-op outside
 * the tab scaffold.
 */
val LocalBottomNavPageMovedItself = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Reports to the tab shell when [state], a tab page's vertical scrollable,
 * arrives at its top, from the scrollable's own position. The shell's
 * nested-scroll observer cannot see this on its own: pull-to-refresh consumes
 * the leftover drag at the top, and programmatic scrolls bypass nested scroll.
 *
 * Only arrivals are reported. The value at first composition is skipped: a
 * restored list reads as at its top until it is first measured.
 */
@Composable
fun ReportBottomNavPageTop(state: ScrollableState) {
    val scrolledToTop = LocalBottomNavScrolledToTop.current
    LaunchedEffect(state, scrolledToTop) {
        snapshotFlow { state.canScrollBackward }
            .drop(1)
            .collect { canScrollBackward -> if (!canScrollBackward) scrolledToTop() }
    }
}

/**
 * Bottom navigation tabs for the main scaffold.
 */
enum class Tab(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home(Route.Home.route, CommonR.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    Libraries(Route.Libraries.route, CommonR.string.shell_tab_libraries, Icons.Outlined.GridView, Icons.Filled.GridView),
    ForYou(Route.Recommendations.route, CommonR.string.topBar_forYou, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Calendar(Route.Calendar.route, CommonR.string.topBar_calendar, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Downloads(
        Route.Downloads.route,
        CommonR.string.tab_downloads,
        Icons.Outlined.Download,
        Icons.Filled.Download,
    ),
}

/**
 * The tab destination sitting lowest on the back stack — the anchor tab
 * switching pops to.
 *
 * Derived from the live stack rather than remembered: `MainScreen` is composed
 * per tab destination, so a remembered anchor gave every tab its own copy, and
 * the graph's declared start destination keeps naming a tab even after that tab
 * is removed. Popping to a route that is not on the stack pops nothing, so
 * every tab tap stacked and Back walked back through previously visited tabs.
 *
 * This finds the oldest tab entry deterministically; what needs care is turning
 * it back into a route string, because `popUpTo(route)` resolves to the NEWEST
 * matching entry. The result is therefore unambiguous only while a tab route
 * appears at most once. Every path in this build that can add a tab entry keeps
 * that true: tab switching and the disappearing-tab cleanup both collapse to
 * the anchor before pushing, external tab links switch rather than push, and the
 * legacy-route aliases pop themselves inclusively before navigating, so their
 * `launchSingleTop` sees Home on top when Home sat immediately below them.
 * Duplicate tab routes are otherwise unsupported — a back stack restored from an
 * older build could arrive holding them, and this does not repair that, so older
 * tab entries may be left underneath.
 */
internal fun NavHostController.bottomMostTabRoute(): String? {
    val tabRoutes = Tab.entries.mapTo(mutableSetOf()) { it.route }
    return currentBackStack.value
        .firstOrNull { entry -> entry.destination.route in tabRoutes }
        ?.destination
        ?.route
}

/** The route's tab, if it is one. */
internal fun tabForRoute(route: String): Tab? = Tab.entries.firstOrNull { it.route == route }

/**
 * Standard tab-switch options: replace the current tab rather than stack it,
 * preserving each tab's own state.
 *
 * External links to a tab use these too, so `silo://downloads` behaves exactly
 * like tapping Downloads — one definition of what entering a tab means, rather
 * than two that drift.
 */
internal fun NavOptionsBuilder.tabSwitchNavOptions(anchorRoute: String?) {
    anchorRoute?.let { popUpTo(it) { saveState = true } }
    launchSingleTop = true
    restoreState = true
}


private val PillHeight = 60.dp
// The tab items' inset inside the capsule.
private val PillItemInset = 6.dp
// Keep foldable/tablet chrome at the same physical footprint as the phone
// capsule instead of stretching one tab item across the expanded window.
private val PillExpandedMaxWidth = 380.dp
// BoxWithConstraints measures after the bar's 20dp side margins, so 560dp
// corresponds to the app's 600dp expanded-window breakpoint.
private val PillExpandedWindowBreakpoint = 560.dp
private val PillHorizontalMargin = 20.dp
private val PillBottomMargin = 10.dp
private val PillTopMargin = 8.dp
private val InlineAccessoryGap = 10.dp

// One spring drives the capsule resize, the accessory card's move and their
// fades, so they read as a single motion. A spring rather than a fixed-length
// tween: the bar flips with scroll direction, and a reversal mid-flight
// carries the current velocity into the new target instead of restarting a
// curve from rest. Critically damped, so the capsule never dips below its
// one-control width; it settles in about the old 320ms tween's time.
private fun <T> chromeMotion(): FiniteAnimationSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * What holds the tab bar's accessory slot (iOS `tabViewBottomAccessory`): a
 * now-playing bar, or the page dock of the tab on screen.
 *
 * The bar draws the one card, moving and resizing it between its resting
 * place above the capsule and the row beside the minimized control; [content]
 * draws only what goes inside, for the placement `inline` names.
 */
@Immutable
class BottomNavAccessory(
    /** Who holds the slot; a new holder takes over the same card (see [AccessoryOccupant]). */
    val key: Any,
    val style: BottomNavAccessoryStyle,
    val content: @Composable (inline: Boolean) -> Unit,
)

enum class BottomNavAccessoryStyle {
    /** Remote Control or the audiobook: a rounded card above the capsule. */
    NowPlaying,

    /** A page's dock: the capsule's own glass, as a capsule. */
    PageDock,
}

/**
 * Floating pill tab bar, matching the iOS app's detached bottom capsule.
 *
 * The bar draws no full-width scrim: tab content scrolls edge-to-edge and
 * shows around the faint artwork-tinted capsule used by detail's X/remote
 * buttons. Bright white glyphs keep the tab symbols legible without turning
 * the material into a pale solid bar.
 *
 * One progress value, sprung between expanded (0) and minimized (1), drives
 * every moving part. The tabs keep their expanded layout throughout and the
 * shrinking capsule clips them, so no frame lays them out narrower than at
 * rest; the current tab's item slides to the minimized control while the
 * others fade. The accessory card is a single shape whose bounds and corner
 * radius interpolate between its two placements; its two contents crossfade
 * inside it.
 */
@Composable
fun SiloBottomNavBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    /** iOS `tabBarMinimizeBehavior(.onScrollDown)`: the capsule collapses into
     *  one still-tappable control for the current tab. The caller decides when
     *  (MainScreen drives it from scrolling on every tab; see
     *  [BottomNavMinimizeTracker]); tapping the control should expand it. */
    minimizedToCurrentTab: Boolean = false,
    // Caller decides which tabs to render — used to hide the Downloads tab
    // when the user has no downloads in flight or on disk. Defaults to all
    // tabs for backwards-compat.
    tabs: List<Tab> = Tab.entries.toList(),
    /** What holds the accessory slot, if anything. It rests above the capsule,
     *  and moves into the row beside the minimized control (iOS inline tab
     *  bar accessory). Expanded windows keep it above, centred and no wider
     *  than the capsule. */
    accessory: BottomNavAccessory? = null,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        val isExpandedWindow = maxWidth - PillHorizontalMargin * 2 >= PillExpandedWindowBreakpoint
        val minimizeProgress = animateFloatAsState(
            targetValue = if (minimizedToCurrentTab) 1f else 0f,
            animationSpec = chromeMotion(),
            label = "bottomNavMinimize",
        )
        // Expanded windows keep the card above the capsule.
        val inlineProgress = if (isExpandedWindow) ZeroState else minimizeProgress
        val inlineTarget = minimizedToCurrentTab && !isExpandedWindow

        // The last holder stays drawn while the card leaves.
        val lastAccessory = remember { arrayOfNulls<BottomNavAccessory>(1) }
        val presenceAnimation = animateFloatAsState(
            targetValue = if (accessory != null) 1f else 0f,
            animationSpec = chromeMotion(),
            label = "bottomNavAccessoryPresence",
        )
        // A page dock arriving in an empty slot is part of the page, not an
        // event: it is there as the page appears (entering Libraries, Back from
        // a title) instead of springing in each time. The page offers it from an
        // effect, a frame after the bar first composes. Now-playing bars still
        // spring in when a session starts; a holder already there when the bar
        // first composes starts in place either way.
        val arrival = remember { BooleanArray(2) } // [hadHolder, appearsInstantly]
        when {
            accessory == null -> arrival[1] = false
            accessory.style != BottomNavAccessoryStyle.PageDock -> arrival[1] = false
            !arrival[0] -> arrival[1] = lastAccessory[0] == null || presenceAnimation.value < 0.001f
        }
        arrival[0] = accessory != null
        if (accessory != null) lastAccessory[0] = accessory
        val appearsInstantly = arrival[1]
        val presence: () -> Float = { if (appearsInstantly) 1f else presenceAnimation.value }
        val shownAccessory = accessory ?: lastAccessory[0]?.takeIf { presenceAnimation.value > 0.001f }

        val style = shownAccessory?.style ?: BottomNavAccessoryStyle.NowPlaying
        val look = style.look()
        val sideMargin by animateDpAsState(look.sideMargin, chromeMotion(), label = "accessoryMargin")
        val topPad by animateDpAsState(look.topPadding, chromeMotion(), label = "accessoryTop")
        val bottomPad by animateDpAsState(look.bottomPadding, chromeMotion(), label = "accessoryBottom")
        val aboveWidth = if (isExpandedWindow) {
            (maxWidth - sideMargin * 2).coerceAtMost(PillExpandedMaxWidth)
        } else {
            maxWidth - sideMargin * 2
        }
        val inlineWidth = maxWidth - PillHorizontalMargin * 2 - PillHeight - InlineAccessoryGap
        val fullWidthPx = constraints.maxWidth

        Layout(
            content = {
                PillRow(
                    currentTab = currentTab,
                    onTabSelected = onTabSelected,
                    minimized = minimizedToCurrentTab,
                    progress = minimizeProgress,
                    tabs = tabs,
                    isExpandedWindow = isExpandedWindow,
                )
                if (shownAccessory != null) {
                    AccessoryCard(
                        accessory = shownAccessory,
                        look = look,
                        aboveWidth = aboveWidth,
                        inlineWidth = inlineWidth,
                        inlineProgress = inlineProgress,
                        inlineTarget = inlineTarget,
                        presence = presence,
                        // A leaving holder (its session ended) takes no touches.
                        inert = accessory == null,
                    )
                }
            },
        ) { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else fullWidthPx
            val pill = measurables[0].measure(Constraints(minWidth = width, maxWidth = width))
            val cardMeasurable = measurables.getOrNull(1)
            if (cardMeasurable == null) {
                return@Layout layout(width, pill.height) { pill.placeRelative(0, 0) }
            }
            val card = cardMeasurable.measure(Constraints(maxWidth = width))
            val aboveHeight = card[AboveContentHeight].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified }
                ?: card.height
            val q = inlineProgress.value
            val a = presence()
            val top = topPad.roundToPx()
            val bottom = bottomPad.roundToPx()
            // Room above the capsule: the card's resting place, giving way as
            // the card moves inline and as it arrives or leaves.
            val aboveBlock = ((aboveHeight + top + bottom) * a * (1f - q)).roundToInt()
            layout(width, aboveBlock + pill.height) {
                pill.placeRelative(0, aboveBlock)
                val restX = (width - aboveWidth.roundToPx()) / 2
                val restY = aboveBlock - bottom - aboveHeight
                val inlineX = (PillHorizontalMargin + PillHeight + InlineAccessoryGap).roundToPx()
                val inlineY = aboveBlock + PillTopMargin.roundToPx()
                card.placeRelative(
                    x = lerp(restX.toFloat(), inlineX.toFloat(), q).roundToInt(),
                    y = lerp(restY.toFloat(), inlineY.toFloat(), q).roundToInt(),
                )
            }
        }
    }
}

private val ZeroState: State<Float> = object : State<Float> {
    override val value: Float = 0f
}

/** How a slot holder's card looks above the capsule. */
private class CardLook(
    val color: Color,
    val border: Color?,
    val shadow: Dp,
    val sideMargin: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp,
    /** Corner radius at rest above the capsule; a capsule when larger than half its height. */
    val restingCorner: Dp,
)

@Composable
private fun BottomNavAccessoryStyle.look(): CardLook = when (this) {
    // The now-playing bars' own card, as they drew it before the bar took it over.
    BottomNavAccessoryStyle.NowPlaying -> CardLook(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = null,
        shadow = 8.dp,
        sideMargin = 16.dp,
        topPadding = 8.dp,
        bottomPadding = 8.dp,
        restingCorner = 18.dp,
    )
    // The floating Libraries dock's capsule and its gap above the tab bar.
    BottomNavAccessoryStyle.PageDock -> CardLook(
        color = SiloNavPillSurface,
        border = SiloNavPillBorder,
        shadow = 16.dp,
        sideMargin = 20.dp,
        topPadding = 0.dp,
        bottomPadding = 8.dp,
        restingCorner = 999.dp,
    )
}

/** The card's height at rest above the capsule, reported to the bar's layout. */
private val AboveContentHeight = HorizontalAlignmentLine { a, _ -> a }

private const val AboveSlot = "above"
private const val InlineSlot = "inline"

/**
 * The one accessory card. Its size and corner radius follow [inlineProgress]
 * from the resting card to the inline capsule; the two contents sit inside at
 * their own sizes (so neither is squeezed), clipped by the card and
 * crossfading. Only the placement being moved to takes touches and TalkBack
 * focus.
 */
@Composable
private fun AccessoryCard(
    accessory: BottomNavAccessory,
    look: CardLook,
    aboveWidth: Dp,
    inlineWidth: Dp,
    inlineProgress: State<Float>,
    inlineTarget: Boolean,
    presence: () -> Float,
    inert: Boolean,
) {
    val color by animateColorAsState(look.color, chromeMotion(), label = "accessoryColor")
    val borderColor by animateColorAsState(look.border ?: Color.Transparent, chromeMotion(), label = "accessoryBorder")
    val shadow by animateDpAsState(look.shadow, chromeMotion(), label = "accessoryShadow")
    val restingCorner by animateDpAsState(look.restingCorner, chromeMotion(), label = "accessoryCorner")
    val density = LocalDensity.current
    val borderPx = with(density) { 1.dp.toPx() }
    // Contents are composed only while they can be seen or are being moved to.
    val showsAbove by remember(inlineTarget) { derivedStateOf { !inlineTarget || inlineProgress.value < 0.999f } }
    val showsInline by remember(inlineTarget) { derivedStateOf { inlineTarget || inlineProgress.value > 0.001f } }
    val lastAboveHeight = remember { IntArray(1) }

    fun cornerPx(height: Float, q: Float): Float {
        val half = height / 2f
        return lerp(min(with(density) { restingCorner.toPx() }, half), half, q)
    }

    Layout(
        modifier = Modifier
            .graphicsLayer {
                alpha = presence()
                shadowElevation = shadow.toPx()
                shape = RoundedCornerShape(CornerSize(cornerPx(size.height, inlineProgress.value)))
                clip = true
            }
            .drawBehind {
                val radius = CornerRadius(cornerPx(size.height, inlineProgress.value))
                drawRoundRect(color = color, cornerRadius = radius)
                if (borderColor.alpha > 0f) {
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(borderPx / 2, borderPx / 2),
                        size = Size(size.width - borderPx, size.height - borderPx),
                        cornerRadius = CornerRadius((radius.x - borderPx / 2).coerceAtLeast(0f)),
                        style = Stroke(borderPx),
                    )
                }
            }
            .then(if (inert) InertModifier else Modifier),
        content = {
            // A fixed composition order keeps each placement's state across
            // toggles; the one being moved to draws over the other and is
            // hit-tested first.
            listOf(AboveSlot, InlineSlot).forEach { slot ->
                val inline = slot == InlineSlot
                if (if (inline) showsInline else showsAbove) {
                    val isTarget = inline == inlineTarget
                    Box(
                        modifier = Modifier
                            .layoutId(slot)
                            .zIndex(if (isTarget) 1f else 0f)
                            .graphicsLayer {
                                val q = inlineProgress.value
                                alpha = if (inline) ((q - 0.4f) / 0.6f).coerceIn(0f, 1f) else (1f - q / 0.6f).coerceIn(0f, 1f)
                            }
                            .then(if (isTarget) Modifier else InertModifier),
                    ) {
                        AccessoryOccupant(accessory, inline)
                    }
                }
            }
        },
    ) { measurables, _ ->
        val q = inlineProgress.value
        val aboveW = aboveWidth.roundToPx()
        val inlineW = inlineWidth.roundToPx().coerceAtLeast(0)
        val inlineH = PillHeight.roundToPx()
        val above = measurables.firstOrNull { it.layoutId == AboveSlot }
            ?.measure(Constraints(minWidth = aboveW, maxWidth = aboveW))
        if (above != null) lastAboveHeight[0] = above.height
        val aboveH = lastAboveHeight[0].takeIf { it > 0 } ?: inlineH
        val inlinePlaceable = measurables.firstOrNull { it.layoutId == InlineSlot }
            ?.measure(Constraints.fixed(inlineW, inlineH))
        val w = lerp(aboveW.toFloat(), inlineW.toFloat(), q).roundToInt()
        val h = lerp(aboveH.toFloat(), inlineH.toFloat(), q).roundToInt()
        layout(w, h, mapOf(AboveContentHeight to aboveH)) {
            listOf(above, inlinePlaceable).forEach { placeable ->
                placeable?.placeRelative(0, (h - placeable.height) / 2)
            }
        }
    }
}

/** A placement fading out: no touches, nothing for TalkBack. */
private val InertModifier = Modifier
    .clearAndSetSemantics {}
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

/**
 * The holder's content; the card resizes to a new holder. Between two
 * now-playing bars the contents crossfade. To or from the page dock they swap
 * at once: the page floats or drops its own dock in that same frame, so a
 * fading copy here would show two docks (or a dead dock) on screen together.
 * Leaving content takes no touches or TalkBack focus: its session has ended.
 */
@Composable
private fun AccessoryOccupant(accessory: BottomNavAccessory, inline: Boolean) {
    AnimatedContent(
        targetState = accessory,
        contentKey = { it.key },
        transitionSpec = {
            val swapsDock = initialState.style == BottomNavAccessoryStyle.PageDock ||
                targetState.style == BottomNavAccessoryStyle.PageDock
            val contents = if (swapsDock) {
                // Snapped fades: the leaving content stays composed while the
                // card resizes, so it must turn invisible at once.
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                fadeIn(chromeMotion()) togetherWith fadeOut(chromeMotion())
            }
            contents.using(SizeTransform(clip = false) { _, _ -> chromeMotion() })
        },
        contentAlignment = Alignment.CenterStart,
        label = "bottomNavAccessoryHolder",
    ) { holder ->
        val leaving = transition.targetState != EnterExitState.Visible
        // The content colour the now-playing bars had on their own card.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(modifier = if (leaving) InertModifier else Modifier) {
                holder.content(inline)
            }
        }
    }
}

/**
 * The largest [value] seen while this composition lives, so content padding
 * derived from the chrome does not pull a list resting at its end downward
 * when the chrome gets shorter (the bar minimizing, a now-playing bar ending,
 * the dock moving into the tab bar). New [keys] (a new window size) start over.
 *
 * [value] may read state that changes every frame (the chrome's live height
 * while the bar moves); readers of the result are invalidated only when it
 * grows, not on each of those frames.
 */
@Composable
internal fun rememberNonShrinking(vararg keys: Any?, value: () -> Dp): State<Dp> {
    val latest = rememberUpdatedState(value)
    return remember(*keys) {
        var held = 0f
        // Structural equality: readers hear only of a new (larger) value.
        derivedStateOf(structuralEqualityPolicy()) {
            // Monotonic, so recomputing it any number of times is harmless.
            held = maxOf(held, latest.value().value)
            Dp(held)
        }
    }
}

/** The capsule row, inside its margins. */
@Composable
private fun PillRow(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    minimized: Boolean,
    progress: State<Float>,
    tabs: List<Tab>,
    isExpandedWindow: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PillHorizontalMargin,
                end = PillHorizontalMargin,
                top = PillTopMargin,
                bottom = PillBottomMargin,
            ),
    ) {
        NavPill(
            currentTab = currentTab,
            onTabSelected = onTabSelected,
            minimized = minimized,
            progress = progress,
            tabs = tabs,
            isExpandedWindow = isExpandedWindow,
            expandedWidth = maxWidth.coerceAtMost(PillExpandedMaxWidth),
        )
    }
}

@Composable
private fun BoxScope.NavPill(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    minimized: Boolean,
    progress: State<Float>,
    tabs: List<Tab>,
    isExpandedWindow: Boolean,
    expandedWidth: Dp,
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val expandedPx = with(density) { expandedWidth.roundToPx() }
    val pillPx = with(density) { PillHeight.roundToPx() }
    val insetPx = with(density) { PillItemInset.toPx() }
    // Mobile grows from the start edge; the expanded foldable/tablet bar stays
    // centred in both states.
    val centered = isExpandedWindow
    val slotPx = (expandedPx - 2 * insetPx) / tabs.size.coerceAtLeast(1)
    // Where the minimized control's centre sits, from the start of the items.
    val minimizedCenterPx = (if (centered) expandedPx / 2f else pillPx / 2f) - insetPx
    val minimizedContentPx = pillPx - 2 * insetPx
    val currentContentWidth = remember { mutableIntStateOf(0) }
    fun pillWidth(p: Float) = lerp(expandedPx.toFloat(), pillPx.toFloat(), p).roundToInt()

    // The tap handler below outlives recompositions; read these when the tap lands.
    val latestOnTabSelected by rememberUpdatedState(onTabSelected)
    val latestCurrentTab by rememberUpdatedState(currentTab)
    Box(
        modifier = Modifier
            .align(if (centered) Alignment.Center else Alignment.CenterStart)
            .layout { measurable, _ ->
                val width = pillWidth(progress.value)
                val placeable = measurable.measure(Constraints.fixed(width, pillPx))
                layout(width, pillPx) { placeable.place(0, 0) }
            }
            .shadow(elevation = 20.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(SiloNavPillSurface)
            .border(1.dp, SiloNavPillBorder, CircleShape)
            // Minimized, the whole 60dp capsule is the control: a tap in the
            // item inset around the current tab's item expands too. The item
            // itself carries the semantics, so this adds none.
            .pointerInput(minimized) {
                if (minimized) detectTapGestures { latestOnTabSelected(latestCurrentTab) }
            },
    ) {
        // The items keep the expanded capsule's layout at every frame; the
        // shrinking capsule clips them rather than squeezing them.
        Row(
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .width(expandedWidth)
                .fillMaxHeight()
                .offset {
                    // A centred capsule shrinks from both ends; hold the items
                    // where the expanded capsule put them.
                    if (centered) IntOffset(-((expandedPx - pillWidth(progress.value)) / 2), 0) else IntOffset.Zero
                }
                .padding(PillItemInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isCurrent = tab == currentTab
                val motion = if (isCurrent) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            val p = progress.value
                            val fromStart = (minimizedCenterPx - slotPx * (index + 0.5f)) * p
                            translationX = if (isRtl) -fromStart else fromStart
                        }
                } else {
                    Modifier.graphicsLayer { alpha = (1f - progress.value * 1.6f).coerceAtLeast(0f) }
                }
                PillTabItem(
                    tab = tab,
                    selected = isCurrent,
                    minimizeProgress = if (isCurrent) progress else null,
                    interactive = isCurrent || !minimized,
                    expandsTabBar = isCurrent && minimized,
                    contentWidth = if (isCurrent) currentContentWidth else null,
                    // Shrink only the glyphs, and only as far as a long label
                    // must to fit the minimized control; the item keeps its
                    // full size as a touch target.
                    contentScale = if (isCurrent) {
                        {
                            val contentWidth = currentContentWidth.intValue
                            val fit = if (contentWidth > 0) min(1f, minimizedContentPx / contentWidth) else 1f
                            lerp(1f, fit, progress.value)
                        }
                    } else {
                        null
                    },
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(motion),
                )
            }
        }
    }
}

@Composable
private fun PillTabItem(
    tab: Tab,
    selected: Boolean,
    /** The current tab's item follows the minimize motion: its chip fades and
     *  its glyphs turn light as it becomes the lone control. */
    minimizeProgress: State<Float>?,
    /** False for the other tabs while minimized: they are fading out under
     *  the current tab's control and must not take touches or TalkBack focus. */
    interactive: Boolean,
    /** The lone control of the minimized bar: activating it expands the bar. */
    expandsTabBar: Boolean,
    contentWidth: MutableIntState?,
    contentScale: (() -> Float)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = minimizeProgress?.value ?: 0f
    val tint = when {
        selected -> lerp(SiloNavPillSelectedContent, Color.White, p)
        else -> SiloNavPillContent
    }
    val chip = if (selected) SiloNavPillSelected.copy(alpha = SiloNavPillSelected.alpha * (1f - p)) else Color.Transparent
    val interaction = remember { MutableInteractionSource() }
    // selectable (not clickable) so TalkBack announces which tab is active —
    // the chip and filled icon alone are not perceivable. Minimized, the
    // control also exposes the standard Expand action, so TalkBack reports it
    // as collapsed and offers expanding the bar in its own localized wording;
    // a double-tap expands it too.
    Box(
        modifier = modifier
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
            .selectable(
                selected = selected,
                enabled = interactive,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .then(
                if (expandsTabBar) {
                    Modifier.semantics {
                        expand {
                            onClick()
                            true
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Only the chip and the ripple are clipped to the pill shape. Clipping
        // the content too cut the ends off a wide label such as "Downloads"
        // or "Bibliotecas" where the shape curves in.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(chip)
                .indication(interaction, ripple(bounded = true, color = tint)),
        )
        Column(
            modifier = Modifier
                .then(if (contentWidth != null) Modifier.onSizeChanged { contentWidth.intValue = it.width } else Modifier)
                .then(
                    if (contentScale != null) {
                        Modifier.graphicsLayer {
                            val scale = contentScale()
                            scaleX = scale
                            scaleY = scale
                        }
                    } else {
                        Modifier
                    },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            val label = stringResource(tab.labelRes)
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(25.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Steps down from 11sp only when a translated label is wider than
            // its tab or, on the chip, than the chip where the label sits: low
            // in the chip, its round ends take about 5dp a side at mid-letter
            // height, at any tab width. The item keeps its expanded width
            // while minimizing, so the size never changes mid-motion.
            BasicText(
                text = label,
                modifier = Modifier.padding(horizontal = if (selected) 5.dp else 0.dp),
                style = LocalTextStyle.current.merge(
                    TextStyle(
                        color = tint,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
            )
        }
    }
}
