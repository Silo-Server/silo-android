package com.continuum.app.tv.ui.shell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source guard for the TV shell focus wiring that lives in Compose modifiers and
 * therefore can't be exercised by [TvShellFocusStateTest]'s pure assertions. Each
 * check pins a guard that a specific "fix focus" commit added, so the phase-7
 * state-holder refactor — and anything after it — can't silently drop one:
 *
 *  - b823d1f  D-pad Up double-move + hidden-menu focus
 *  - 253b6ee  Back handled at shell level so menu-focused Back doesn't exit
 *  - be6f1f3  UP-exit guard + cascade commit key-bleed
 *  - 79fa8a5  UP → selected tab, dropdown-close → avatar
 */
class TvShellFocusSourceTest {
    private val shell = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/shell/TvMainShell.kt",
    ).readText()

    private val topMenu = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/shell/TvTopMenuBar.kt",
    ).readText()

    @Test
    fun shellRoutesFocusThroughTheSingleStateHolder() {
        // One owner: the shell holds a TvShellFocusState and drives the bar/panel
        // from it, rather than a bag of loose counters + booleans.
        assertTrue(shell.contains("val focusState = rememberTvShellFocusState()"))
        assertTrue(shell.contains("onMenuFocusChange = focusState::updateMenuFocused"))
        assertTrue(shell.contains("onDwell = focusState::previewPanel"))
        assertTrue(shell.contains("onEnterPanel = focusState::enterPanel"))
        assertTrue(shell.contains("isFocusSuppressed = focusState.isMenuFocusSuppressed"))
        assertTrue(shell.contains("focusRequest = focusState.menuFocusRequest"))
    }

    @Test
    fun backIsCentralizedAtTheShellAndRoutedByTheHolder() {
        // 253b6ee: Back/Escape is caught on the outer shell Box (an ancestor of
        // both content and the bar) and routed by the holder's 4-way decision, so
        // a menu-focused Back returns to content instead of exiting the app and
        // can never be double-handled.
        assertTrue(shell.contains("ev.key == Key.Back || ev.key == Key.Escape"))
        assertTrue(shell.contains("when (focusState.onBack())"))
        assertTrue(shell.contains("TvShellBackAction.MoveFocusToContent ->"))
        assertTrue(shell.contains("TvShellBackAction.DelegateToNav ->"))
        assertTrue(shell.contains("nestedNav.popBackStack()"))
    }

    @Test
    fun contentUpGuardsAgainstDoubleMoveAndGeometricEscape() {
        // be6f1f3: cancel any geometric focus escape upward out of content so the
        // manual moveFocus(Up) controls the bar handoff…
        assertTrue(shell.contains("if (direction == FocusDirection.Up)"))
        assertTrue(shell.contains("FocusRequester.Cancel"))
        // …b823d1f: do the up-move ourselves and route to the bar only when we're
        // already on the top row, consuming the event so the default focus system
        // can't run a second moveFocus(Up) and skip a row.
        assertTrue(shell.contains("var contentUpFallback by remember"))
        assertTrue(shell.contains("val contentHandledUp = contentUpFallback?.invoke()"))
        assertTrue(shell.indexOf("val contentHandledUp = contentUpFallback?.invoke()") < shell.indexOf("val moved = focusManager.moveFocus(FocusDirection.Up)"))
        assertTrue(shell.contains("val moved = focusManager.moveFocus(FocusDirection.Up)"))
        assertTrue(shell.contains("focusState.requestMenuFocus()"))
    }

    @Test
    fun focusRequestsAreCrashGuardedAndVisibilityIsRestored() {
        // Every imperative requestFocus() is wrapped (the requester may be detached
        // mid-transition), and a bar refocus first snaps the scroll-hidden bar back
        // into view (guarded > 0 so it never fires on first compose).
        assertTrue(shell.contains("runCatching { contentFocusRequester.requestFocus() }"))
        assertTrue(shell.contains("runCatching { searchInputFocusRequester.requestFocus() }"))
        assertTrue(shell.contains("if (focusState.menuFocusRequest > 0 && menuVisibility.value < 1f)"))
    }

    @Test
    fun topBarPreservesItsSelectedTabAndAvatarFocusPaths() {
        // 79fa8a5: a bare suppression lift must not re-grab the selected tab (so it
        // doesn't fight the dedicated avatar path), the avatar refocus is > 0
        // guarded, focus entering the bar lands on the selected tab, and every
        // requestFocus() stays crash-guarded.
        assertTrue(topMenu.contains("if (focusRequest == lastHandledFocusRequest) return@LaunchedEffect"))
        assertTrue(topMenu.contains("if (profileFocusRequest > 0)"))
        assertTrue(topMenu.contains("enter = { barEntryRequester }"))
        assertTrue(topMenu.contains("runCatching { selectedEntryRequester().requestFocus() }"))
        assertTrue(!topMenu.contains("selectedEntryFocus()"))
    }
}
