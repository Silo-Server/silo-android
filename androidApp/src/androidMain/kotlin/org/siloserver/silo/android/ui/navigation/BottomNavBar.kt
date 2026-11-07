package org.siloserver.silo.android.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.annotation.StringRes
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloNavPillContent
import org.siloserver.silo.android.ui.theme.SiloNavPillSelected
import org.siloserver.silo.android.ui.theme.SiloNavPillSelectedContent
import org.siloserver.silo.android.ui.theme.SiloNavPillSurface

/**
 * Total height of the translucent bottom chrome (cast mini bar + nav bar +
 * gesture inset) as measured by the main Scaffold. Tab content scrolls
 * edge-to-edge underneath the chrome (iOS glass tab bar behavior), so
 * scrollable screens add this to their bottom content padding to keep their
 * last items reachable above it. Zero outside the tab scaffold.
 */
val LocalBottomChromeInset = compositionLocalOf { 0.dp }

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
// One duration for the capsule resize and the accessory's move so the two
// read as a single motion.
private const val ChromeMotionMillis = 320
private fun <T> chromeTween() = tween<T>(durationMillis = ChromeMotionMillis, easing = FastOutSlowInEasing)
private const val AccessorySharedKey = "bottomNavAccessory"

/**
 * Floating pill tab bar, matching the iOS app's detached bottom capsule.
 *
 * The bar draws no full-width scrim: tab content scrolls edge-to-edge and
 * shows around the faint artwork-tinted capsule used by detail's X/remote
 * buttons. Bright white glyphs keep the tab symbols legible without turning
 * the material into a pale solid bar.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SiloBottomNavBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    /** iOS `tabBarMinimizeBehavior(.onScrollDown)`: Home collapses the full
     *  capsule into one still-tappable Home control until it is reselected or
     *  the feed returns to its top. Other tabs always render expanded. */
    minimizedToCurrentTab: Boolean = false,
    // Caller decides which tabs to render — used to hide the Downloads tab
    // when the user has no downloads in flight or on disk. Defaults to all
    // tabs for backwards-compat.
    tabs: List<Tab> = Tab.entries.toList(),
    /** Optional bar (the cast remote) that rests above the capsule, and moves
     *  into the row beside the minimized Home control while the capsule is
     *  minimized (iOS inline tab bar accessory). `inline` tells it which
     *  placement it is rendering. Expanded windows keep it above, since their
     *  capsule stays centered. The accessory applies `barModifier` to its
     *  visible card; the card then glides and resizes between the two
     *  placements instead of fading out of one and into the other. */
    accessory: (@Composable (inline: Boolean, barModifier: Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        val isExpandedWindow = maxWidth - PillHorizontalMargin * 2 >= PillExpandedWindowBreakpoint
        val accessoryInline = accessory != null && minimizedToCurrentTab && !isExpandedWindow
        SharedTransitionLayout {
            val sharedScope = this
            Column {
                if (accessory != null) {
                    AnimatedVisibility(
                        visible = !accessoryInline,
                        // The size change lets the content inset follow; the
                        // card itself moves in the shared-bounds overlay.
                        enter = expandVertically(chromeTween()) + fadeIn(chromeTween()),
                        exit = shrinkVertically(chromeTween()) + fadeOut(chromeTween()),
                    ) {
                        accessory(false, sharedScope.accessoryBounds(this))
                    }
                }
                PillRow(
                    currentTab = currentTab,
                    onTabSelected = onTabSelected,
                    minimizedToCurrentTab = minimizedToCurrentTab,
                    tabs = tabs,
                    isExpandedWindow = isExpandedWindow,
                    inlineAccessory = if (accessory != null) {
                        { visibility -> accessory(true, sharedScope.accessoryBounds(visibility)) }
                    } else {
                        null
                    },
                    accessoryInline = accessoryInline,
                )
            }
        }
    }
}

/** Shared bounds for the accessory card, matched across its two placements. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.accessoryBounds(visibility: AnimatedVisibilityScope): Modifier =
    Modifier.sharedBounds(
        sharedContentState = rememberSharedContentState(AccessorySharedKey),
        animatedVisibilityScope = visibility,
        enter = fadeIn(chromeTween()),
        exit = fadeOut(chromeTween()),
        boundsTransform = { _, _ -> chromeTween() },
    )

/** The capsule row, with the fixed inline-accessory slot right of the
 *  minimized control. Its own function so the slot's AnimatedVisibility does
 *  not resolve against the enclosing Column's scope. */
@Composable
private fun PillRow(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    minimizedToCurrentTab: Boolean,
    tabs: List<Tab>,
    isExpandedWindow: Boolean,
    inlineAccessory: (@Composable (AnimatedVisibilityScope) -> Unit)?,
    accessoryInline: Boolean,
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
        if (inlineAccessory != null) {
            // Fixed slot right of the minimized control, which the card glides
            // into while the capsule shrinks, so nothing in the row reflows.
            AnimatedVisibility(
                visible = accessoryInline,
                enter = fadeIn(chromeTween()),
                exit = fadeOut(chromeTween()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PillHeight)
                    .padding(start = PillHeight + InlineAccessoryGap),
            ) {
                inlineAccessory(this)
            }
        }
        NavPill(
            currentTab = currentTab,
            onTabSelected = onTabSelected,
            minimizedToCurrentTab = minimizedToCurrentTab,
            tabs = tabs,
            isExpandedWindow = isExpandedWindow,
            fullWidth = maxWidth,
        )
    }
}

@Composable
private fun BoxScope.NavPill(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    minimizedToCurrentTab: Boolean,
    tabs: List<Tab>,
    isExpandedWindow: Boolean,
    fullWidth: Dp,
) {
    val pillWidth by animateDpAsState(
        targetValue = if (minimizedToCurrentTab) {
            PillHeight
        } else {
            fullWidth.coerceAtMost(PillExpandedMaxWidth)
        },
        animationSpec = chromeTween(),
        label = "bottomNavWidth",
    )
    val displayedTabs = if (minimizedToCurrentTab) listOf(currentTab) else tabs
    Row(
        modifier = Modifier
            // Keep alignment stable for the entire width animation:
            // mobile grows from the left; the already-approved expanded
            // foldable/tablet bar remains centered in both states.
            .align(if (isExpandedWindow) Alignment.Center else Alignment.CenterStart)
            .width(pillWidth)
            .height(PillHeight)
            .shadow(elevation = 20.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(SiloNavPillSurface)
            .border(1.dp, SiloNavPillBorder, CircleShape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        displayedTabs.forEach { tab ->
            PillTabItem(
                tab = tab,
                selected = tab == currentTab,
                showSelectedChip = !minimizedToCurrentTab,
                onClick = { onTabSelected(tab) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PillTabItem(
    tab: Tab,
    selected: Boolean,
    showSelectedChip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Minimized to a lone Home control, there is no chip to sit on, so the
    // selected tab keeps light-on-dark content instead of inverting.
    val onLightChip = selected && showSelectedChip
    val chip by animateColorAsState(
        targetValue = if (onLightChip) SiloNavPillSelected else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "tabChip",
    )
    val tint by animateColorAsState(
        targetValue = when {
            onLightChip -> SiloNavPillSelectedContent
            selected -> Color.White
            else -> SiloNavPillContent
        },
        animationSpec = tween(durationMillis = 220),
        label = "tabTint",
    )
    val interaction = remember { MutableInteractionSource() }
    // selectable (not clickable) so TalkBack announces which tab is active —
    // the chip and filled icon alone are not perceivable.
    Box(
        modifier = modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
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
            // height, at any tab width.
            BasicText(
                text = label,
                modifier = Modifier.padding(horizontal = if (onLightChip) 5.dp else 0.dp),
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
