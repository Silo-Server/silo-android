package org.siloserver.silo.tv.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.tv.R
import org.siloserver.silo.common.ui.components.ProfileAvatarRef
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.tv.ui.theme.ChromeSelectedBorder
import org.siloserver.silo.tv.ui.theme.ChromeSelectedFill
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.tv.ui.theme.navRailLabel

private const val TopMenuInitialPreviewDelayMillis = 180L
private const val TopMenuPanelSwitchDelayMillis = 80L

/**
 * How long a non-anchor focus must hold before it disarms dwell suppression.
 * Shorter than [TopMenuInitialPreviewDelayMillis] so a real move still previews
 * promptly, long enough to outlast the one-frame focus blip Compose emits while
 * an explicit bar focus request is being applied.
 */
private const val TopMenuSuppressionHandoffGraceMillis = 120L

/** Upper bound on the single-focusable handoff window. */
private const val TopMenuHandoffTimeoutMillis = 500L


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
 * fixed Home/Calendar tabs plus the Search icon and trailing profile avatar.
 */
private sealed class TvTopMenuFocus {
    data object Home : TvTopMenuFocus()
    data class Tab(val type: TvLibraryTabType) : TvTopMenuFocus()
    data object ForYou : TvTopMenuFocus()
    data object Calendar : TvTopMenuFocus()
    data object Search : TvTopMenuFocus()
    data object Profile : TvTopMenuFocus()
}

/**
 * The custom top menu bar — the Skyline grammar from tvOS `TVTopMenuBar.swift`.
 *
 * Layout (three zones):
 * - Leading: the Silo brand lockup image (see [TvSiloWordmark]).
 * - Center: Search icon · `Home` · one inverted-capsule tab per visible
 *   library-type · `Calendar`, derived from [destinations] (the shell's
 *   `visibleRoots`), with an invisible search-size twin trailing the tabs so
 *   the tab group stays screen-centered (tvOS `tabCluster` parity).
 * - Trailing: the profile avatar (with unread badge).
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
    onProfileDwell: () -> Unit,
    onProfileBlur: () -> Unit,
    onEnterProfileMenu: () -> Unit,
    onMoveDown: () -> Unit,
    isMenuFocused: Boolean,
    onMenuFocusChange: (Boolean) -> Unit,
    isFocusSuppressed: Boolean,
    focusRequest: Int,
    focusRequestTarget: TvTopMenuPanel? = null,
    /**
     * True for a deliberate shell handoff to the bar — Back up from content, or
     * the fallback when content has nothing focusable. See
     * TvShellFocusState.menuFocusSuppressesDwell.
     */
    focusRequestSuppressesDwell: Boolean = false,
    /**
     * Receives a hook that moves bar focus to a panel's anchor synchronously,
     * so a closing cascade never leaves focus for Compose to recover. See
     * TvShellFocusState.focusBarAnchorNow.
     */
    onInstallAnchorFocus: ((TvTopMenuPanel?) -> Boolean) -> Unit = {},
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
    val forYouFocusRequester = remember { FocusRequester() }
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

    // tvOS keeps the tab focused after Back closes its panel, but suppresses
    // that tab's dwell preview until focus leaves it. Android additionally
    // uses the first Down from that state as a direct handoff to content.
    var dwellSuppressedButton by remember { mutableStateOf<TvTopMenuFocus?>(null) }

    // While a Back-close handoff is in flight, the anchor is the ONLY focusable
    // bar element. Compose recovers focus the instant the cascade's node leaves
    // composition and picks the bar's FIRST child — the search icon — a frame
    // before the explicit request lands, so Back visibly flashed through search
    // on its way to the anchor. Taking the other buttons out of focus search for
    // that one window leaves the recovery nowhere to go but the anchor itself.
    val handoffAnchor = dwellSuppressedButton?.takeIf { it != focusedButton }
    fun canFocusButton(focus: TvTopMenuFocus): Boolean =
        !isFocusSuppressed && (handoffAnchor == null || handoffAnchor == focus)

    // The anchor focusing clears handoffAnchor and cancels this. If the request
    // never lands, release the restriction rather than leaving the bar with a
    // single focusable button.
    LaunchedEffect(handoffAnchor) {
        if (handoffAnchor != null) {
            delay(TopMenuHandoffTimeoutMillis)
            if (dwellSuppressedButton == handoffAnchor) dwellSuppressedButton = null
        }
    }

    fun focusForRoot(root: TvRootDestination): TvTopMenuFocus = when (root) {
        TvRootDestination.Home -> TvTopMenuFocus.Home
        TvRootDestination.ForYou -> TvTopMenuFocus.ForYou
        TvRootDestination.Calendar -> TvTopMenuFocus.Calendar
        is TvRootDestination.LibraryType -> TvTopMenuFocus.Tab(root.type)
    }

    fun focusForPanel(panel: TvTopMenuPanel): TvTopMenuFocus? = when (panel) {
        is TvTopMenuPanel.Root -> focusForRoot(panel.dest)
        TvTopMenuPanel.Profile -> TvTopMenuFocus.Profile
    }

    fun requesterForFocus(focus: TvTopMenuFocus): FocusRequester = when (focus) {
        TvTopMenuFocus.Home -> homeFocusRequester
        is TvTopMenuFocus.Tab -> tabFocusRequesters[focus.type] ?: homeFocusRequester
        TvTopMenuFocus.ForYou -> forYouFocusRequester
        TvTopMenuFocus.Calendar -> calendarFocusRequester
        TvTopMenuFocus.Search -> searchFocusRequester
        TvTopMenuFocus.Profile -> profileFocusRequester
    }

    fun panelForFocus(focus: TvTopMenuFocus?): TvTopMenuPanel? = when (focus) {
        is TvTopMenuFocus.Tab ->
            TvTopMenuPanel.Root(TvRootDestination.LibraryType(focus.type))
        TvTopMenuFocus.ForYou -> TvTopMenuPanel.Root(TvRootDestination.ForYou)
        else -> null
    }

    fun selectedEntryRequester(): FocusRequester = when (val root = selectedRoot) {
        TvRootDestination.Home -> homeFocusRequester
        TvRootDestination.Calendar -> calendarFocusRequester
        is TvRootDestination.LibraryType -> tabFocusRequesters[root.type] ?: homeFocusRequester
        TvRootDestination.ForYou -> forYouFocusRequester
        null -> if (isSearchActive) searchFocusRequester else homeFocusRequester
    }

    // Focus the selected tab when `focusRequest` actually changes (the
    // content→bar Up path). We track the last-handled value so a bare
    // suppression lift (e.g. closing the profile dropdown, which flips
    // isFocusSuppressed false) does NOT also re-grab the selected tab and fight
    // the dedicated profile-avatar focus path below.
    val currentFocusRequestTarget by rememberUpdatedState(focusRequestTarget)
    val currentDestinations by rememberUpdatedState(destinations)
    var lastHandledFocusRequest by remember { mutableStateOf<Pair<Int, TvTopMenuPanel?>>(0 to null) }
    val focusRequestIdentity = focusRequest to focusRequestTarget
    val focusRequestTargetAvailable =
        isTopMenuFocusTargetAvailable(focusRequestTarget, destinations)
    LaunchedEffect(
        focusRequest,
        focusRequestTarget,
        isFocusSuppressed,
        focusRequestTargetAvailable,
    ) {
        lastHandledFocusRequest = handleTopMenuFocusRequestIfAvailable(
            requestIdentity = focusRequestIdentity,
            lastHandledRequest = lastHandledFocusRequest,
            isFocusSuppressed = isFocusSuppressed,
            // Availability gates only the EXPLICIT target, never the request
            // itself. Skipping the whole request left nothing focused, so
            // Compose's default search landed on the first bar element — the
            // search icon — and Back out of a cascade appeared to "go to
            // search". Falling back to the selected entry keeps the viewer on
            // the tab they came from.
            isTargetAvailable = true,
            requestFocus = {
                val explicitFocus = focusRequestTarget
                    ?.takeIf { focusRequestTargetAvailable }
                    ?.let(::focusForPanel)
                // The target names which bar element to land on; it does NOT by
                // itself mean the preview should be suppressed. Only a panel
                // Back-close wants that. Arming it for every targeted request
                // meant an ordinary content-to-bar Up — which also carries a
                // target — left that tab unable to reopen its own cascade.
                dwellSuppressedButton = explicitFocus.takeIf { focusRequestSuppressesDwell }
                val requester = explicitFocus?.let(::requesterForFocus) ?: selectedEntryRequester()
                requestTopMenuFocusUntilApplied(
                    awaitFrame = { androidx.compose.runtime.withFrameNanos { } },
                    isTargetCurrent = {
                        currentFocusRequestTarget == focusRequestTarget &&
                            isTopMenuFocusTargetAvailable(focusRequestTarget, currentDestinations)
                    },
                    requestFocus = {
                        runCatching { requester.requestFocus() }.getOrDefault(false)
                    },
                )
            },
        )
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
            dwellSuppressedButton = TvTopMenuFocus.Profile
            runCatching { profileFocusRequester.requestFocus() }
        }
    }

    // Dwell-to-preview (tvOS `TVTopMenuBar` per-tab dwell timer): focus resting
    // on a library-type tab opens its cascade panel in preview; moving focus
    // re-keys this effect (auto-cancelling the pending delay). The first open
    // waits briefly to avoid flashing panels during casual bar traversal, but
    // once a panel is already visible tab-to-tab switching should feel direct.
    // Landing on any non-panel button closes the preview immediately; losing
    // bar focus may mean the user is entering that panel, so the shell owns it.
    LaunchedEffect(focusedButton, dwellSuppressedButton) {
        val focus = focusedButton
        val suppressed = dwellSuppressedButton
        if (suppressed != null) {
            // A transient null is the panel→bar focus handoff itself; keep the
            // suppression armed until the requested anchor actually focuses.
            // A transient null is the panel→bar focus handoff itself; keep the
            // suppression armed until the requested anchor actually focuses,
            // and hold it while that anchor keeps focus so a deliberate handoff
            // to the bar does not flash a panel straight back open. This can no
            // longer wedge the tab: only an explicit requestMenuFocus arms it,
            // so an ordinary content-to-bar Up arrives unsuppressed and opens
            // the cascade.
            if (focus == null || focus == suppressed) return@LaunchedEffect
            // A DIFFERENT button may still be the handoff in flight rather than
            // a real move: while the requested anchor is being applied, Compose
            // briefly focuses the bar's first child (the Search icon). Clearing
            // on that blip disarmed the suppression, so the anchor re-previewed
            // the moment it actually landed — Back out of a cascade reopened it
            // and the viewer was left one Back short of Home. Wait out the blip;
            // this effect is keyed on focusedButton, so the anchor arriving
            // cancels the delay and leaves the suppression armed.
            delay(TopMenuSuppressionHandoffGraceMillis)
            // Moving anywhere else re-arms normal dwell behavior, matching
            // tvOS's dwellSuppressedElement lifecycle.
            dwellSuppressedButton = null
            return@LaunchedEffect
        }
        if (focus is TvTopMenuFocus.Tab) {
            delay(if (openPanel == null) TopMenuInitialPreviewDelayMillis else TopMenuPanelSwitchDelayMillis)
            onDwell(TvTopMenuPanel.Root(TvRootDestination.LibraryType(focus.type)))
        } else if (focus is TvTopMenuFocus.ForYou) {
            delay(if (openPanel == null) TopMenuInitialPreviewDelayMillis else TopMenuPanelSwitchDelayMillis)
            onDwell(TvTopMenuPanel.Root(TvRootDestination.ForYou))
        } else if (focus == TvTopMenuFocus.Profile) {
            delay(TopMenuInitialPreviewDelayMillis)
            onProfileDwell()
        } else if (focus != null) {
            // Moving to a non-panel bar item closes any dwell preview. A null
            // focus can mean focus is entering the open panel, so leave panel
            // ownership to the shell instead of racing its entry transition.
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

    // Publish the synchronous anchor-focus hook. This runs on the composition
    // thread, so a Back handler can move focus BEFORE it removes the panel.
    SideEffect {
        onInstallAnchorFocus { panel ->
            val target = panel?.let(::focusForPanel)
            val requester = target?.let(::requesterForFocus) ?: selectedEntryRequester()
            // requestFocus() RETURNS whether the claim was accepted, so
            // runCatching{}.isSuccess threw the real answer away — it is true
            // for any call that merely did not throw. That made a refused
            // claim look like a move, which armed suppression, closed the
            // panel and skipped the deferred fallback, leaving focus nowhere.
            val accepted = requester.claimFocusOrReport(
                target = "menu_anchor",
                action = "back_close_anchor",
            )
            // Accepted is not arrival — the helper says so itself — but it
            // does separate a claim that took from one that definitely needs
            // the deferred retry. Arm the suppression only on acceptance;
            // otherwise the state-request fallback arms it a frame later.
            if (accepted) dwellSuppressedButton = target
            accepted
        }
    }


    // Single full-width Row (wordmark · flexible gap · search+centered tabs ·
    // flexible gap · trailing profile) so D-pad Left/Right traverse the whole bar
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
            // No background band of its own: the SHELL draws a fixed top
            // scrim behind the bar, so the focus dim below only affects the
            // labels — content stays legibly scrimmed even while the bar is
            // dimmed (QA 2026-07-08).
            .focusGroup()
            .focusProperties {
                // Suppression must remove the bar from focus search, not merely
                // ignore explicit requester bumps. Otherwise Android's initial
                // focus pass can still choose Home while content is composing.
                canFocus = !isFocusSuppressed
                // While a Back-close handoff is in flight, the anchor is the
                // entry point — not the selected tab. Closing a cascade removes
                // the focused node and Compose recovers focus into the bar; if
                // that recovery uses the group's first child it lands on the
                // search icon and Back visibly flashes through search before the
                // explicit request lands.
                enter = {
                    dwellSuppressedButton?.let(::requesterForFocus) ?: barEntryRequester
                }
            }
            .onPreviewKeyEvent { event ->
                val focus = focusedButton
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                        // On a library-type tab, d-pad-down opens that tab's cascade
                        // panel (and focuses into it) instead of diving to content.
                        // Home/Calendar/Search keep the move-to-content behavior.
                        val panel = panelForFocus(focus)
                        if (focus == TvTopMenuFocus.Profile) {
                            onEnterProfileMenu()
                        } else if (panel != null && dwellSuppressedButton == focus) {
                            // Back just dismissed this tab's panel. Treat the
                            // next Down as the user's intent to leave chrome
                            // and enter the first/remembered content row.
                            dwellSuppressedButton = null
                            onMoveDown()
                        } else if (panel != null) {
                            onEnterPanel(panel)
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
        // Leading: the Silo brand lockup.
        Box(
            modifier = Modifier
                .padding(start = TvSkyline.safeAreaX)
                .height(TvSkyline.barHeight),
            contentAlignment = Alignment.Center,
        ) {
            TvSiloWordmark()
        }

        Spacer(modifier = Modifier.weight(1f))

        // Center cluster: Search · Home · library-type tabs · Calendar.
        // tvOS parity (TVTopMenuBar.tabCluster): search sits just left of Home,
        // and an invisible same-size twin at the trailing end keeps the tab
        // group itself screen-centered.
        Row(
            modifier = Modifier.height(TvSkyline.barHeight),
            horizontalArrangement = Arrangement.spacedBy(TvSkyline.tabSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTopMenuIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "Search",
                isFocused = focusedButton == TvTopMenuFocus.Search,
                canFocus = canFocusButton(TvTopMenuFocus.Search),
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

            destinations.forEach { destination ->
                when (destination) {
                    TvRootDestination.Home -> TvTopMenuTab(
                        label = "Home",
                        isSelected = selectedRoot == TvRootDestination.Home,
                        isFocused = focusedButton == TvTopMenuFocus.Home,
                        canFocus = canFocusButton(TvTopMenuFocus.Home),
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
                            canFocus = canFocusButton(TvTopMenuFocus.Tab(type)),
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

                    TvRootDestination.ForYou -> TvTopMenuTab(
                        label = "For You",
                        isSelected = selectedRoot == TvRootDestination.ForYou,
                        isFocused = focusedButton == TvTopMenuFocus.ForYou,
                        canFocus = canFocusButton(TvTopMenuFocus.ForYou),
                        focusRequester = forYouFocusRequester,
                        onFocusChanged = { hasFocus ->
                            focusedButton = if (hasFocus) {
                                TvTopMenuFocus.ForYou
                            } else {
                                focusedButton.takeUnless { it == TvTopMenuFocus.ForYou }
                            }
                        },
                        onClick = { onSelectRoot(TvRootDestination.ForYou) },
                        // Publishes its anchor so the shell can hang the
                        // Watchlist/Favorites dropdown under it (tvOS
                        // TVForYouDropdown).
                        modifier = Modifier.onGloballyPositioned {
                            onTabAnchor(TvTopMenuPanel.Root(TvRootDestination.ForYou), it)
                        },
                    )

                    TvRootDestination.Calendar -> TvTopMenuTab(
                        label = "Calendar",
                        isSelected = selectedRoot == TvRootDestination.Calendar,
                        isFocused = focusedButton == TvTopMenuFocus.Calendar,
                        canFocus = canFocusButton(TvTopMenuFocus.Calendar),
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

            // Invisible twin of the search button so the tab group stays
            // centered on screen with search sitting to its left.
            Spacer(modifier = Modifier.size(TvSkyline.barIconSize))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Trailing cluster: profile avatar.
        Row(
            modifier = Modifier
                .padding(end = TvSkyline.safeAreaX)
                .height(TvSkyline.barHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSkyline.barTrailingSpacing),
        ) {
            TvTopMenuProfileButton(
                accountState = accountState,
                isFocused = focusedButton == TvTopMenuFocus.Profile,
                canFocus = canFocusButton(TvTopMenuFocus.Profile),
                focusRequester = profileFocusRequester,
                onFocusChanged = { hasFocus ->
                    focusedButton = if (hasFocus) TvTopMenuFocus.Profile else focusedButton.takeUnless { it == TvTopMenuFocus.Profile }
                    if (!hasFocus) onProfileBlur()
                },
                onClick = onProfileClick,
            )
        }
    }
}

/** Minimal account view-data the menu bar + profile dropdown render. */
data class TvAccountState(
    val displayName: String = "Profile",
    /** Avatar ref + server-resolved URL, kept together so neither is lost. */
    val avatar: ProfileAvatarRef = ProfileAvatarRef.None,
    /** Secondary line under the name in the dropdown header (role / username). */
    val subtitle: String = "",
    /** Active server display name, shown in the dropdown header. */
    val serverName: String = "",
)

/** The Silo brand lockup image, sized to the bar. */
@Composable
private fun TvSiloWordmark() {
    Image(
        painter = painterResource(id = R.drawable.silo_wordmark),
        contentDescription = "Silo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .height(TvSiloWordmarkHeight)
            .width(TvSiloWordmarkHeight * TvSiloWordmarkAspect),
    )
}

/** Brand lockup height inside the 32dp bar; the PNG is 764×400. */
private val TvSiloWordmarkHeight = 26.dp
private const val TvSiloWordmarkAspect = 764f / 400f

/**
 * A center-cluster type tab with inverted-capsule chrome (§5.1):
 * - focused → solid `SiloOnSurface` capsule, text in the background color;
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
    canFocus: Boolean,
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
        isFocused -> SiloOnSurface
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
        isSelected -> SiloOnSurface
        else -> SiloOnSurface.copy(alpha = 0.62f)
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
            focusedContainerColor = SiloOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = SiloOnSurface,
            pressedContentColor = DarkBackground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, borderColor), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = modifier
            .focusProperties { this.canFocus = canFocus }
            .focusRequester(focusRequester)
            .height(TvSkyline.tabPillHeight),
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
 * Search icon (center cluster, left of Home). Mirrors the tab capsule's inverted focus chrome: a
 * solid `SiloOnSurface` circle with a background-colored glyph while
 * focused, bare otherwise.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopMenuIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isFocused: Boolean,
    canFocus: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isInteractionFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isInteractionFocused) { onFocusChanged(isInteractionFocused) }

    val shape = CircleShape
    val containerColor = if (isFocused) SiloOnSurface else Color.Transparent
    val iconColor = if (isFocused) DarkBackground else SiloOnSurface.copy(alpha = 0.62f)

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = iconColor,
            focusedContainerColor = SiloOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = SiloOnSurface,
            pressedContentColor = DarkBackground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = Modifier
            .focusProperties { this.canFocus = canFocus }
            .focusRequester(focusRequester)
            .size(TvSkyline.barIconSize),
    ) {
        Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(19.dp),
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
    canFocus: Boolean,
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
            contentColor = SiloOnSurface,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = SiloOnSurface,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = SiloOnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        modifier = Modifier
            .focusProperties { this.canFocus = canFocus }
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
    val avatarImage = rememberProfileAvatarImage(accountState.avatar)
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
                    color = if (isFocused) SiloOnSurface else Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImage != null) {
                ThumbhashImage(
                    url = avatarImage.url,
                    thumbhash = null,
                    contentDescription = accountState.displayName,
                    modifier = Modifier.fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    cacheKey = avatarImage.cacheKey,
                    onError = avatarImage.onLoadFailed,
                )
            } else {
                Text(
                    text = avatarText,
                    color = SiloOnSurface,
                    fontWeight = FontWeight.Bold,
                    style = navRailLabel,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
