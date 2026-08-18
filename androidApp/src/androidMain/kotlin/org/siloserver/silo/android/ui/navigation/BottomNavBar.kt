package org.siloserver.silo.android.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

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
private val PillHorizontalMargin = 20.dp
private val PillBottomMargin = 10.dp
private val PillTopMargin = 8.dp
// Glass recipe: content beneath is blurred by Haze, then tinted with this
// wash so labels stay legible over bright posters. On API < 31 Haze cannot
// blur and paints only the tint, so the fallback fill is heavier.
private val PillGlassTint = Color(0xFF1C1C1E).copy(alpha = 0.72f)
private val PillFallbackFill = Color(0xFF1C1C1E).copy(alpha = 0.96f)
private val PillBlurRadius = 24.dp
private val PillHairline = Color.White.copy(alpha = 0.12f)
private val SelectedChipFill = Color.White.copy(alpha = 0.14f)

/**
 * Floating pill tab bar, matching the iOS app's detached bottom capsule.
 *
 * The bar draws no full-width scrim: tab content scrolls edge-to-edge and
 * shows around the capsule. The capsule itself is real glass — [hazeState]
 * must be the state the tab content is registered on via `hazeSource`, so
 * the pill blurs whatever scrolls beneath it and tints the result. The selected tab
 * carries a soft chip highlight and a filled icon; unselected tabs are
 * outlined and muted. Colors animate on switch so the highlight reads as
 * moving rather than popping.
 */
@Composable
fun SiloBottomNavBar(
    currentTab: Tab,
    onTabSelected: (Tab) -> Unit,
    hazeState: HazeState,
    // Caller decides which tabs to render — used to hide the Downloads tab
    // when the user has no downloads in flight or on disk. Defaults to all
    // tabs for backwards-compat.
    tabs: List<Tab> = Tab.entries.toList(),
) {
    Box(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PillHeight)
                .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .hazeEffect(state = hazeState) {
                    blurRadius = PillBlurRadius
                    noiseFactor = 0f
                    backgroundColor = PillFallbackFill
                    tints = listOf(HazeTint(PillGlassTint))
                    fallbackTint = HazeTint(PillFallbackFill)
                }
                .border(0.75.dp, PillHairline, CircleShape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                PillTabItem(
                    tab = tab,
                    selected = tab == currentTab,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chip by animateColorAsState(
        targetValue = if (selected) SelectedChipFill else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "tabChip",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "tabTint",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(CircleShape)
            .background(chip)
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
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            color = tint,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
