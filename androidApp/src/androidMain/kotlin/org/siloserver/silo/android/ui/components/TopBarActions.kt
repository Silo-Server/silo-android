package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.android.ui.theme.SiloDetailActionControlActive
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.model.profile.Profile

/**
 * Shared vocabulary for the phone tab headers, mirroring iOS
 * `TabTopBarActions` / `TopBarIconButton` / `ProfileAvatarMenu`: bare 40dp
 * circular hit targets with no chip fill or border, a 36dp avatar, and a
 * tight trailing cluster. Home, Libraries and the shared [MainAppTopBar] all
 * draw from here so the three headers read as one bar.
 */

/** iOS `topBarIconSpacing`. */
val TopBarActionSpacing = 4.dp

/** iOS `TopBarIconButton`: a plain 40pt hit target, optionally a filled disc when active. */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    opaque: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = when {
            isActive && opaque -> Color.White.copy(alpha = 0.38f)
            isActive -> Color.White.copy(alpha = 0.18f)
            opaque -> SiloDetailActionControlActive
            else -> Color.Transparent
        },
        contentColor = if (opaque) Color.White else MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        tonalElevation = 0.dp,
        shadowElevation = if (opaque) 5.dp else 0.dp,
        border = if (opaque) BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)) else null,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** The 36dp avatar disc that anchors the profile menu (iOS `ProfileAvatarView` size 36). */
@Composable
fun TopBarProfileMenu(
    activeProfile: Profile?,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    opaque: Boolean = false,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Box {
        TopBarIconButton(onClick = { menuExpanded = true }, opaque = opaque) {
            if (activeProfile != null) {
                ProfileAvatar(
                    avatar = activeProfile.avatarRef(),
                    name = activeProfile.name,
                    size = if (opaque) 30.dp else 36.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Account and menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ProfileMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            onRequestsClick = onRequestsClick,
            onWatchTogetherClick = onWatchTogetherClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onSwitchServerClick = onSwitchServerClick,
            onSignOutClick = onSignOutClick,
        )
    }
}

/**
 * iOS `TabTopBarActions`: search then the profile avatar menu. [leadingActions]
 * lets a tab prepend its own button (Home's remote-control button).
 */
@Composable
fun TabTopBarActions(
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
    opaque: Boolean = false,
    leadingActions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TopBarActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingActions()
        TopBarIconButton(onClick = onSearchClick, opaque = opaque) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
            )
        }
        TopBarProfileMenu(
            activeProfile = activeProfile,
            onRequestsClick = onRequestsClick,
            onWatchTogetherClick = onWatchTogetherClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onSwitchServerClick = onSwitchServerClick,
            onSignOutClick = onSignOutClick,
            opaque = opaque,
        )
    }
}

// iOS `siloGlass(tint: black 0.08)`: a light blur with a faint dark wash.
private val TopBarGlassTint = Color.Black.copy(alpha = 0.08f)
private val TopBarGlassBlurRadius = 20.dp
// Below API 31 Haze cannot blur; a heavier flat wash keeps the header
// legible over scrolled content.
private val TopBarGlassFallback = Color(0xFF0A0A0A).copy(alpha = 0.86f)

/**
 * The header glass shared by every tab bar: content registered on [state]
 * via `hazeSource` is blurred and lightly tinted beneath this node. With
 * [progressive] the glass feathers out along its bottom edge. Over an
 * empty top runway the blur is invisible, so pinned bars only "turn on" once
 * content slides beneath — iOS's scroll-edge effect for free. To fade the
 * glass with scroll (Home), put it on a background-only box behind a
 * `graphicsLayer { alpha }` rather than in the Haze block: Haze does not
 * re-run its style block on snapshot reads.
 */
fun Modifier.topBarGlass(state: HazeState, progressive: Boolean = false): Modifier =
    hazeEffect(state = state) {
        blurRadius = TopBarGlassBlurRadius
        noiseFactor = 0f
        tints = listOf(HazeTint(TopBarGlassTint))
        fallbackTint = HazeTint(TopBarGlassFallback)
        if (progressive) {
            // Progressive glass: solid for the top ~80% of the bar, then
            // feathering to clear so content dissolves into the header
            // instead of meeting a hard edge (iOS scroll-edge effect for a
            // taller chrome that carries a pinned selector row).
            mask = Brush.verticalGradient(
                0f to Color.Black,
                ProgressiveGlassSolidFraction to Color.Black,
                1f to Color.Transparent,
            )
        }
    }

private const val ProgressiveGlassSolidFraction = 0.78f
