package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.stringResource
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
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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

/**
 * Page edge margin for top controls. Home, the shared tab bar, Libraries'
 * selector row, and the back buttons on item/person detail all use it, so the
 * controls do not shift horizontally when moving between pages.
 */
val TopBarEdgeMargin = 16.dp

/**
 * Extra inset below the status bar for a page's top controls — deliberately
 * zero. Every page's controls sit on the same line, directly under the status
 * bar. Pages used to disagree by 0, 4, or 8dp, so the buttons jumped a few
 * pixels on every navigation.
 */
val TopBarRowTopInset = 0.dp

/*
 * Vertical grid shared by the phone tabs (Home, Libraries, For You,
 * Downloads), measured down from the bottom of the status bar:
 *
 *   header row   0..48   title or wordmark, actions centred in it
 *   pill row    56..96   Libraries' subtabs, For You's Watchlist/Favorites
 *   content     120..    first row (Home reserves the pill band empty)
 *
 * Every tab's first row starts at [TabContentTop], so rows do not jump
 * vertically when switching tabs.
 */

/** Height of the row that holds the page title and the trailing actions. */
val TabHeaderRowHeight = 48.dp

/** Gap between the header row and the pill row. */
val TabHeaderPillGap = 8.dp

/** Height of the pill row under the header. */
val TabPillRowHeight = 40.dp

/** Gap between the pill row and the first content row. */
val TabContentGap = 24.dp

/** Scroll distance over which a tab header's glass fades in. */
val TopBarGlassFadeDistance = 80.dp

/**
 * Where a tab's scrolling content reports how far it sits under the shared
 * [MainAppTopBar], from 0 (at rest) to 1 (glass fully on). Only For You and
 * Downloads provide it; elsewhere the reporters below do nothing.
 */
val LocalTabHeaderScrim = staticCompositionLocalOf<MutableFloatState?> { null }

/** Reports [state]'s scroll position to [LocalTabHeaderScrim]. */
@Composable
fun ReportTabHeaderScrim(state: LazyListState) {
    ReportTabHeaderScrim(state) { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
}

/** Reports [state]'s scroll position to [LocalTabHeaderScrim]. */
@Composable
fun ReportTabHeaderScrim(state: LazyGridState) {
    ReportTabHeaderScrim(state) { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
}

/**
 * Derived from the list's own position, like Libraries' chrome, so the glass
 * is right after a restored scroll position (returning to the tab) as well as
 * while scrolling. Clears the glass when the list leaves composition.
 */
@Composable
private fun ReportTabHeaderScrim(key: Any, position: () -> Pair<Int, Int>) {
    val sink = LocalTabHeaderScrim.current ?: return
    val fadePx = with(LocalDensity.current) { TopBarGlassFadeDistance.toPx() }
    LaunchedEffect(key, sink, fadePx) {
        snapshotFlow {
            val (index, offset) = position()
            if (index > 0) 1f else (offset / fadePx).coerceIn(0f, 1f)
        }.collect { sink.floatValue = it }
    }
    DisposableEffect(key, sink) {
        onDispose { sink.floatValue = 0f }
    }
}

/** Top of the first content row, below the status bar. */
val TabContentTop = TabHeaderRowHeight + TabHeaderPillGap + TabPillRowHeight + TabContentGap

/**
 * A plain 40dp hit target: the glyph only, no disc, no border, no shadow.
 * The one exception is [isActive], where a filled disc is carrying state
 * (the remote button while it is controlling a TV) rather than decoration.
 *
 * Header buttons used to draw a translucent white disc in imitation of iOS
 * glass. Without a live blur behind it that is just a light wash sitting on
 * the page, and it read as out of place on Android.
 */
@Composable
fun TopBarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (isActive) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
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
    onWatchPartyClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onChangeAccountClick: () -> Unit,
    onSignOutClick: (removeServer: Boolean) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Box {
        TopBarIconButton(onClick = { menuExpanded = true }) {
            if (activeProfile != null) {
                ProfileAvatar(
                    avatar = activeProfile.avatarRef(),
                    name = activeProfile.name,
                    size = 36.dp,
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
                        contentDescription = stringResource(CommonR.string.topBar_accountMenu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        ProfileMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            onRequestsClick = onRequestsClick,
            onWatchPartyClick = onWatchPartyClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onChangeAccountClick = onChangeAccountClick,
            onSignOutClick = onSignOutClick,
            activeProfile = activeProfile,
        )
    }
}

/**
 * State and actions for the header's remote-control button, which every tab
 * header shows beside search.
 */
@Immutable
class TopBarRemoteControl(
    /** True while this phone is controlling a TV. */
    val isActive: Boolean,
    /** Opens the remote, or the TV picker when nothing is connected. */
    val onOpen: () -> Unit,
    val onChooseTv: () -> Unit,
    val onDisconnect: () -> Unit,
)

/**
 * Mirrors Apple's SiloControlModeButton: chrome-free at rest, a filled disc
 * while controlling a TV. In that state it opens a menu instead of jumping
 * straight to the remote.
 */
@Composable
private fun TopBarRemoteControlButton(remoteControl: TopBarRemoteControl) {
    Box {
        var menuExpanded by remember { mutableStateOf(false) }
        TopBarIconButton(
            onClick = {
                if (remoteControl.isActive) menuExpanded = true else remoteControl.onOpen()
            },
            isActive = remoteControl.isActive,
        ) {
            Icon(
                imageVector = Icons.Outlined.SettingsRemote,
                contentDescription = stringResource(CommonR.string.topBar_remote_title),
            )
        }
        SiloDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            SiloDropdownMenuItem(
                text = { Text(stringResource(CommonR.string.topBar_remote_title)) },
                onClick = {
                    menuExpanded = false
                    remoteControl.onOpen()
                },
            )
            SiloDropdownMenuItem(
                text = { Text(stringResource(CommonR.string.topBar_remote_chooseTv)) },
                onClick = {
                    menuExpanded = false
                    remoteControl.onChooseTv()
                },
            )
            HorizontalDivider()
            SiloDropdownMenuItem(
                text = {
                    Text(
                        stringResource(CommonR.string.topBar_remote_turnOff),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuExpanded = false
                    remoteControl.onDisconnect()
                },
            )
        }
    }
}

/**
 * iOS `TabTopBarActions`: remote control, search, then the profile avatar
 * menu. [remoteControl] is required so no tab shows search without the remote.
 */
@Composable
fun TabTopBarActions(
    activeProfile: Profile?,
    remoteControl: TopBarRemoteControl,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)?,
    onWatchPartyClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onChangeAccountClick: () -> Unit,
    onSignOutClick: (removeServer: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TopBarActionSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarRemoteControlButton(remoteControl)
        TopBarIconButton(onClick = onSearchClick) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(CommonR.string.topBar_search),
            )
        }
        TopBarProfileMenu(
            activeProfile = activeProfile,
            onRequestsClick = onRequestsClick,
            onWatchPartyClick = onWatchPartyClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onChangeAccountClick = onChangeAccountClick,
            onSignOutClick = onSignOutClick,
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
