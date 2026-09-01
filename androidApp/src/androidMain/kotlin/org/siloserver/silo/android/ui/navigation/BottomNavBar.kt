package org.siloserver.silo.android.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import org.siloserver.silo.android.ui.theme.SiloDetailActionControlActive

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
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home(Route.Home.route, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Libraries(Route.Libraries.route, "Libraries", Icons.Outlined.GridView, Icons.Filled.GridView),
    ForYou(Route.Recommendations.route, "For You", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Calendar(Route.Calendar.route, "Calendar", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Downloads(
        Route.Downloads.route,
        "Downloads",
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

/**
 * Floating pill tab bar, matching the iOS app's detached bottom capsule.
 *
 * The bar draws no full-width scrim: tab content scrolls edge-to-edge and
 * shows around the faint artwork-tinted capsule used by detail's X/remote
 * buttons. Bright white glyphs keep the tab symbols legible without turning
 * the material into a pale solid bar.
 */
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
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = PillHorizontalMargin,
                end = PillHorizontalMargin,
                top = PillTopMargin,
                bottom = PillBottomMargin,
            ),
    ) {
        val isExpandedWindow = maxWidth >= PillExpandedWindowBreakpoint
        val pillWidth by animateDpAsState(
            targetValue = if (minimizedToCurrentTab) {
                PillHeight
            } else {
                maxWidth.coerceAtMost(PillExpandedMaxWidth)
            },
            animationSpec = tween(durationMillis = 260),
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
                .background(SiloDetailActionControlActive)
                .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
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
}

@Composable
private fun PillTabItem(
    tab: Tab,
    selected: Boolean,
    showSelectedChip: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chip by animateColorAsState(
        targetValue = if (selected && showSelectedChip) {
            Color.White.copy(alpha = 0.28f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "tabChip",
    )
    val chipBorder by animateColorAsState(
        targetValue = if (selected && showSelectedChip) {
            Color.White.copy(alpha = 0.46f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "tabChipBorder",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) {
            Color.White
        } else {
            Color.White.copy(alpha = 0.96f)
        },
        animationSpec = tween(durationMillis = 220),
        label = "tabTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(CircleShape)
            .background(chip)
            .border(1.dp, chipBorder, CircleShape)
            // selectable (not clickable) so TalkBack announces which tab is
            // active — the chip and filled icon alone are not perceivable.
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = ripple(bounded = true, color = Color.White),
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(25.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = tint,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
