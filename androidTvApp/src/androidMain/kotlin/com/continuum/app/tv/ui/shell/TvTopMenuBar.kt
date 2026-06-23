package com.continuum.app.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.common.ui.components.profileAvatarDisplayText
import com.continuum.app.tv.ui.theme.ChromeSelectedBorder
import com.continuum.app.tv.ui.theme.ChromeSelectedFill
import com.continuum.app.tv.ui.theme.ContinuumOnSurface
import com.continuum.app.tv.ui.theme.DarkBackground
import com.continuum.app.tv.ui.theme.TvSkyline
import com.continuum.app.tv.ui.theme.navRailLabel

private const val TopMenuInitialPreviewDelayMillis = 180L
private const val TopMenuPanelSwitchDelayMillis = 80L

/**
 * Layout constants for the top menu band. Vertical-clearance / anchor tokens
 * here are consumed by every root screen (`contentTopInset`) and by the shell's
 * profile-menu overlay (`profileMenuTopInset` / `trailingInset`). The bar's own
 * chrome metrics live in [TvSkyline]; this object only retains the tokens other
 * call sites reference so they keep compiling.
 */
object TvTopMenuLayout {
    /** Top bar offset from the screen's top edge — see [TvSkyline.barTopInset]. */
    val barTopInset: Dp = TvSkyline.barTopInset

    /** Top bar row height — see [TvSkyline.barHeight]. */
    val barHeight: Dp = TvSkyline.barHeight

    /** Horizontal chrome inset — see [TvSkyline.safeAreaX]. */
    val leadingInset: Dp = TvSkyline.safeAreaX
    val trailingInset: Dp = TvSkyline.safeAreaX

    /** Top offset for anchored panels — tvOS `dropdownTopInset`. */
    val profileMenuTopInset: Dp = TvSkyline.dropdownTopInset

    /**
     * Vertical clearance reserved on root pages so content doesn't draw under
     * the menu band (bar top inset + bar height + breathing room).
     */
    val contentTopInset: Dp = 94.dp
}

/**
 * Identifies which menu button currently holds focus. Library-type tabs share
 * the [Tab] case keyed by [TvLibraryTabType]; the remaining cases are the
 * fixed Home/Calendar tabs plus the trailing Search icon and profile avatar.
 */
private sealed class TvTopMenuFocus {
    data object Home : TvTopMenuFocus()
    data class Tab(val type: TvLibraryTabType) : TvTopMenuFocus()
    data object Calendar : TvTopMenuFocus()
    data object Search : TvTopMenuFocus()
    data object Profile : TvTopMenuFocus()
}

/**
 * The custom top menu bar — the Skyline grammar from tvOS `TVTopMenuBar.swift`.
 *
 * Layout (three zones):
 * - Leading: the **SILO** wordmark (heavy, tracked).
 * - Center: `Home` · one inverted-capsule tab per visible library-type ·
 *   `Calendar`, derived from [destinations] (the shell's `visibleRoots`).
 * - Trailing: a Search icon button + the profile avatar (with unread badge).
 *
 * Tab chrome (§5.1): a focused tab inverts to a solid white capsule with
 * background-colored text; a selected-but-unfocused tab carries a low-alpha
 * chrome fill; a resting tab is a bare label. The default TV focus scale/halo
 * is disabled so the capsule is the only focus cue.
 *
 * Focus model:
 * - `isMenuFocused` is updated as soon as any button gains/loses focus so the
 *   parent shell can react.
 * - The whole bar dims to [TvSkyline.barDimmedOpacity] whenever focus is down
 *   in the content zone (`!isMenuFocused`) and returns to full opacity when the
 *   bar is focused. This is composed (multiplied) with the scroll-driven
 *   `visibility` alpha so the hide-on-scroll transition still works.
 * - `focusRequest` is an integer counter — incrementing it requests focus on
 *   the currently-selected destination.
 * - When `isFocusSuppressed` is true the bar drops any current focus so D-pad
 *   navigation in the content area below cannot pull focus back into the menu.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TvTopMenuBar(
    selectedRoot: TvRootDestination?,
    destinations: List<TvRootDestination>,
    accountState: TvAccountState,
    onSelectRoot: (TvRootDestination) -> Unit,
    onSelectTab: (TvLibraryTabType) -> Unit = {},
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    onMoveDown: () -> Unit,
    isMenuFocused: Boolean,
    onMenuFocusChange: (Boolean) -> Unit,
    isFocusSuppressed: Boolean,
    focusRequest: Int,
    profileFocusRequest: Int = 0,
    isSearchActive: Boolean = false,
    visibility: Float = 1f,
    openPanel: TvTopMenuPanel? = null,
    onDwell: (TvTopMenuPanel?) -> Unit = {},
    onEnterPanel: (TvTopMenuPanel) -> Unit = {},
    onTabAnchor: (TvTopMenuPanel, androidx.compose.ui.layout.LayoutCoordinates) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val homeFocusRequester = remember { FocusRequester() }
    val calendarFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val profileFocusRequester = remember { FocusRequester() }
    // One requester per library-type tab; stable across recompositions so a
    // focus request still lands after the visible-tab set changes.
    val tabFocusRequesters = remember {
        TvLibraryTabType.entries.associateWith { FocusRequester() }
    }

    // Track which button currently holds focus so we can shape the capsule
    // chrome directly, independent of the surface's focused-state propagation
    // (which is unreliable while the parent suppresses focus mid-animation).
    var focusedButton by remember { mutableStateOf<TvTopMenuFocus?>(null) }

    fun selectedEntryRequester(): FocusRequester = when (val root = selectedRoot) {
        TvRootDestination.Home -> homeFocusRequester
        TvRootDestination.Calendar -> calendarFocusRequester
        is TvRootDestination.LibraryType -> tabFocusRequesters[root.type] ?: homeFocusRequester
        null -> if (isSearchActive) searchFocusRequester else homeFocusRequester
    }

    // Focus the selected tab when `focusRequest` actually changes (the
    // content→bar Up path). We track the last-handled value so a bare
    // suppression lift (e.g. closing the profile dropdown, which flips
    // isFocusSuppressed false) does NOT also re-grab the selected tab and fight
    // the dedicated profile-avatar focus path below.
    var lastHandledFocusRequest by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequest, isFocusSuppressed) {
        if (isFocusSuppressed) return@LaunchedEffect
        if (focusRequest == lastHandledFocusRequest) return@LaunchedEffect
        lastHandledFocusRequest = focusRequest
        runCatching { selectedEntryRequester().requestFocus() }
    }

    LaunchedEffect(focusedButton) {
        onMenuFocusChange(focusedButton != null)
    }

    // Dedicated focus path for closing the profile dropdown: return focus to the
    // profile avatar that opened it, rather than the selected tab (which is what
    // `focusRequest`/`menuFocusRequest` targets). Guarded on >0 so it never runs
    // on first compose.
    LaunchedEffect(profileFocusRequest) {
        if (profileFocusRequest > 0) {
            runCatching { profileFocusRequester.requestFocus() }
        }
    }

    // Dwell-to-preview (tvOS `TVTopMenuBar` per-tab dwell timer): focus resting
    // on a library-type tab opens its cascade panel in preview; moving focus
    // re-keys this effect (auto-cancelling the pending delay). The first open
    // waits briefly to avoid flashing panels during casual bar traversal, but
    // once a panel is already visible tab-to-tab switching should feel direct.
    // Landing on any non-panel button (or losing focus) closes the preview
    // immediately so a stale panel never lingers under the wrong tab.
    LaunchedEffect(focusedButton) {
        val focus = focusedButton
        if (focus is TvTopMenuFocus.Tab) {
            delay(if (openPanel == null) TopMenuInitialPreviewDelayMillis else TopMenuPanelSwitchDelayMillis)
            onDwell(TvTopMenuPanel.Root(TvRootDestination.LibraryType(focus.type)))
        } else {
            onDwell(null)
        }
    }

    // Focus-driven dim (§5.1): full opacity while the bar is focused, dimmed
    // otherwise. Composed (multiplied) with the scroll-visibility alpha below
    // so the hide-on-scroll slide still fades the bar all the way out.
    val dimAlpha by animateFloatAsState(
        targetValue = if (isMenuFocused) 1f else TvSkyline.barDimmedOpacity,
        animationSpec = tween(durationMillis = 200),
        label = "tvTopMenuBarDim",
    )

    // Where focus lands when it enters the bar from content (UP) — always the
    // currently-selected tab, not whatever the geometric focus search would
    // pick (which, with the old align-zoned layout, wrongly landed in the
    // trailing cluster). On non-tab routes (Search) we enter the search icon.
    val barEntryRequester = selectedEntryRequester()

    // Single full-width Row (wordmark · flexible gap · centered tabs · flexible
    // gap · trailing search+profile) so D-pad Left/Right traverse the whole bar
    // in one ordered focus group — the three-zone `align` layout couldn't be
    // crossed by Compose's 2D focus search (Right off the last tab, or Left off
    // search, escaped into content instead of moving along the bar).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TvSkyline.barTopInset + TvSkyline.barHeight)
            .graphicsLayer {
                // Slide the menu up by its own height as visibility drops to 0
                // and fade alpha in lockstep (scroll-hide). Compose the dim
                // with the scroll-visibility alpha so the two stack: the bar
                // dims when content owns focus AND slides out on scroll.
                translationY = -size.height * (1f - visibility)
                alpha = visibility * dimAlpha
            }
            .background(
                Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.84f),
                    0.58f to Color.Black.copy(alpha = 0.72f),
                    0.88f to Color.Black.copy(alpha = 0.36f),
                    1.00f to Color.Transparent,
                ),
            )
            .focusGroup()
            .focusProperties { enter = { barEntryRequester } }
            .onPreviewKeyEvent { event ->
                val focus = focusedButton
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                        // On a library-type tab, d-pad-down opens that tab's cascade
                        // panel (and focuses into it) instead of diving to content.
                        // Home/Calendar/Search keep the move-to-content behavior.
                        if (focus is TvTopMenuFocus.Tab) {
                            onEnterPanel(TvTopMenuPanel.Root(TvRootDestination.LibraryType(focus.type)))
                        } else {
                            onMoveDown()
                        }
                        true
                    }
                    // Center/Enter on a focused library-type tab jumps straight to
                    // that tab's content (Recommended) instead of just previewing.
                    // Commit on key-UP and swallow BOTH phases so the trailing
                    // key-up can't bleed into the newly-focused content card and
                    // open whatever item it lands on (the "commit key bleed" the
                    // cascade rows guard against the same way).
                    (event.key == Key.DirectionCenter || event.key == Key.Enter) &&
                        focus is TvTopMenuFocus.Tab -> {
                        if (event.type == KeyEventType.KeyUp) onSelectTab(focus.type)
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.Bottom,
    ) {
        // Leading: SILO wordmark.
        Box(
            modifier = Modifier
                .padding(start = TvSkyline.safeAreaX)
                .height(TvSkyline.barHeight),
            contentAlignment = Alignment.Center,
        ) {
            TvSiloWordmark()
        }

        Spacer(modifier = Modifier.weight(1f))

        // Center cluster: Home · library-type tabs · Calendar.
        Row(
            modifier = Modifier.height(TvSkyline.barHeight),
            horizontalArrangement = Arrangement.spacedBy(TvSkyline.tabSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                when (destination) {
                    TvRootDestination.Home -> TvTopMenuTab(
                        label = "Home",
                        isSelected = selectedRoot == TvRootDestination.Home,
                        isFocused = focusedButton == TvTopMenuFocus.Home,
                        focusRequester = homeFocusRequester,
                        onFocusChanged = { hasFocus ->
                            focusedButton = if (hasFocus) {
                                TvTopMenuFocus.Home
                            } else {
                                focusedButton.takeUnless { it == TvTopMenuFocus.Home }
                            }
                        },
                        onClick = { onSelectRoot(TvRootDestination.Home) },
                    )

                    is TvRootDestination.LibraryType -> {
                        val type = destination.type
                        val panel = TvTopMenuPanel.Root(destination)
                        TvTopMenuTab(
                            label = type.title,
                            isSelected = selectedRoot == destination,
                            isFocused = focusedButton == TvTopMenuFocus.Tab(type),
                            focusRequester = tabFocusRequesters[type] ?: homeFocusRequester,
                            onFocusChanged = { hasFocus ->
                                focusedButton = if (hasFocus) {
                                    TvTopMenuFocus.Tab(type)
                                } else {
                                    focusedButton.takeUnless { it == TvTopMenuFocus.Tab(type) }
                                }
                            },
                            onClick = { onSelectTab(type) },
                            // Library-type tabs publish their anchor so the shell
                            // can position the cascade panel under them. The
                            // d-pad-down → enter-panel intercept lives on the
                            // bar's own preview key handler (ancestor of the tab),
                            // which fires before any tab-level handler would.
                            modifier = Modifier
                                .onGloballyPositioned { onTabAnchor(panel, it) },
                        )
                    }

                    TvRootDestination.Calendar -> TvTopMenuTab(
                        label = "Calendar",
                        isSelected = selectedRoot == TvRootDestination.Calendar,
                        isFocused = focusedButton == TvTopMenuFocus.Calendar,
                        focusRequester = calendarFocusRequester,
                        onFocusChanged = { hasFocus ->
                            focusedButton = if (hasFocus) {
                                TvTopMenuFocus.Calendar
                            } else {
                                focusedButton.takeUnless { it == TvTopMenuFocus.Calendar }
                            }
                        },
                        onClick = { onSelectRoot(TvRootDestination.Calendar) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Trailing cluster: Search icon + profile avatar.
        Row(
            modifier = Modifier
                .padding(end = TvSkyline.safeAreaX)
                .height(TvSkyline.barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSkyline.barTrailingSpacing),
        ) {
            TvTopMenuIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "Search",
                isFocused = focusedButton == TvTopMenuFocus.Search,
                focusRequester = searchFocusRequester,
                onFocusChanged = { hasFocus ->
                    focusedButton = if (hasFocus) {
                        TvTopMenuFocus.Search
                    } else {
                        focusedButton.takeUnless { it == TvTopMenuFocus.Search }
                    }
                },
                onClick = onSearchClick,
            )

            TvTopMenuProfileButton(
                accountState = accountState,
                isFocused = focusedButton == TvTopMenuFocus.Profile,
                focusRequester = profileFocusRequester,
                onFocusChanged = { hasFocus ->
                    focusedButton = if (hasFocus) TvTopMenuFocus.Profile else focusedButton.takeUnless { it == TvTopMenuFocus.Profile }
                },
                onClick = onProfileClick,
            )
        }
    }
}

/** Minimal account view-data the menu bar + profile dropdown render. */
data class TvAccountState(
    val displayName: String = "Profile",
    val avatar: String? = null,
    val avatarUrl: String? = null,
    /** Secondary line under the name in the dropdown header (role / username). */
    val subtitle: String = "",
    /** Active server display name, shown in the dropdown header. */
    val serverName: String = "",
    /** Whether the signed-in user is an acting admin (gates the Admin row). */
    val isAdmin: Boolean = false,
)

/** Heavy, tracked SILO wordmark at the bar's leading edge (§5.1). */
@Composable
private fun TvSiloWordmark() {
    Text(
        text = "SILO",
        color = ContinuumOnSurface,
        fontWeight = FontWeight.Black,
        fontSize = TvSkyline.wordmarkSize,
        letterSpacing = TvSkyline.wordmarkTracking,
        maxLines = 1,
    )
}

/**
 * A center-cluster type tab with inverted-capsule chrome (§5.1):
 * - focused → solid `ContinuumOnSurface` capsule, text in the background color;
 * - selected (not focused) → low-alpha [ChromeSelectedFill] capsule;
 * - resting → bare label at reduced opacity.
 *
 * The TV surface's own focus scale/halo is disabled (`focusedScale = 1f`, no
 * focused border) so the capsule inversion is the sole focus affordance.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopMenuTab(
    label: String,
    isSelected: Boolean,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isInteractionFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isInteractionFocused) { onFocusChanged(isInteractionFocused) }

    val shape = RoundedCornerShape(percent = 50)
    val containerColor = when {
        isFocused -> ContinuumOnSurface
        isSelected -> ChromeSelectedFill
        else -> Color.Transparent
    }
    val borderColor = when {
        isFocused -> Color.Transparent
        isSelected -> ChromeSelectedBorder
        else -> Color.Transparent
    }
    val textColor = when {
        isFocused -> DarkBackground
        isSelected -> ContinuumOnSurface
        else -> ContinuumOnSurface.copy(alpha = 0.62f)
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = textColor,
            // Keep the inverted look while focused/pressed; the capsule is the
            // only focus cue, so the focused container matches our own fill.
            focusedContainerColor = ContinuumOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = ContinuumOnSurface,
            pressedContentColor = DarkBackground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, borderColor), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = modifier
            .focusRequester(focusRequester)
            .height(TvSkyline.barHeight),
    ) {
        Box(
            modifier = Modifier
                .padding(
                    horizontal = TvSkyline.tabPaddingHorizontal,
                    vertical = TvSkyline.tabPaddingVertical,
                )
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = navRailLabel,
                fontSize = TvSkyline.tabLabelSize,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Trailing search icon. Mirrors the tab capsule's inverted focus chrome: a
 * solid `ContinuumOnSurface` circle with a background-colored glyph while
 * focused, bare otherwise.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopMenuIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isInteractionFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isInteractionFocused) { onFocusChanged(isInteractionFocused) }

    val shape = CircleShape
    val containerColor = if (isFocused) ContinuumOnSurface else Color.Transparent
    val iconColor = if (isFocused) DarkBackground else ContinuumOnSurface.copy(alpha = 0.62f)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = iconColor,
            focusedContainerColor = ContinuumOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = ContinuumOnSurface,
            pressedContentColor = DarkBackground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(TvSkyline.barIconSize),
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Trailing profile avatar. The avatar keeps its unread badge; the focus
 * affordance is a solid ring around the circle (mirroring the tvOS avatar's
 * focused stroke), so it stays visually distinct from the capsule tabs.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopMenuProfileButton(
    accountState: TvAccountState,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isInteractionFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isInteractionFocused) { onFocusChanged(isInteractionFocused) }

    val shape = CircleShape

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = ContinuumOnSurface,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = ContinuumOnSurface,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = ContinuumOnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(TvSkyline.barIconSize),
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
            TvTopMenuAvatar(
                accountState = accountState,
                isFocused = isFocused,
            )
        }
    }
}

@Composable
private fun TvTopMenuAvatar(
    accountState: TvAccountState,
    isFocused: Boolean,
) {
    val avatarText = remember(accountState.avatar, accountState.displayName) {
        profileAvatarDisplayText(accountState.avatar, accountState.displayName)
    }
    // The avatar circle plus a decorative unread badge anchored to its top-end
    // corner. The badge is purely informational — the profile Surface stays the
    // sole focus target, so the focus model is unchanged.
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(TvSkyline.barIconSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) ContinuumOnSurface else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (accountState.avatarUrl != null) {
                ThumbhashImage(
                    url = accountState.avatarUrl,
                    thumbhash = null,
                    contentDescription = accountState.displayName,
                    modifier = Modifier.fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
                )
            } else {
                Text(
                    text = avatarText,
                    color = ContinuumOnSurface,
                    fontWeight = FontWeight.Bold,
                    style = navRailLabel,
                )
            }
        }
    }
}
