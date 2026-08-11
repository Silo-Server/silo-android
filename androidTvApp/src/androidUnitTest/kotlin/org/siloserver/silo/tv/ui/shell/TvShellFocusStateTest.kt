package org.siloserver.silo.tv.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral characterization of the TV shell focus state machine. The focus
 * *dispatch* (FocusRequester / moveFocus) still needs on-device verification, but
 * the routing + mode logic that a long tail of "fix focus" commits kept getting
 * wrong is pure here, so it is locked down with real assertions — not just a
 * source guard. Any future change to [TvShellFocusState] that regresses the
 * historical D-pad/Back behavior breaks one of these.
 */
class TvShellFocusStateTest {

    private val moviesPanel =
        TvTopMenuPanel.Root(TvRootDestination.LibraryType(TvLibraryTabType.Movies))
    private val seriesPanel =
        TvTopMenuPanel.Root(TvRootDestination.LibraryType(TvLibraryTabType.Series))

    // ---- pure mode derivation -------------------------------------------------

    @Test
    fun modeIsContentWhenNothingOwnsFocus() {
        assertEquals(
            TvShellMode.Content,
            tvShellMode(profileMenuOpen = false, panelOpen = false, panelEntered = false, menuFocused = false),
        )
    }

    @Test
    fun aMerePanelPreviewIsStillMenuFocusedNotPanelMode() {
        // Dwell preview keeps focus on the bar tab above the panel, so the mode is
        // MenuFocused — only an *entered* panel owns focus.
        assertEquals(
            TvShellMode.MenuFocused,
            tvShellMode(profileMenuOpen = false, panelOpen = true, panelEntered = false, menuFocused = true),
        )
    }

    @Test
    fun anEnteredPanelIsPanelMode() {
        assertEquals(
            TvShellMode.Panel,
            tvShellMode(profileMenuOpen = false, panelOpen = true, panelEntered = true, menuFocused = false),
        )
    }

    @Test
    fun profileMenuOutranksEveryOtherSurface() {
        assertEquals(
            TvShellMode.ProfileMenu,
            tvShellMode(profileMenuOpen = true, panelOpen = true, panelEntered = true, menuFocused = true),
        )
    }

    // ---- pure Back routing (the historical 4-way precedence) ------------------

    @Test
    fun backClosesAnOpenPanelFirstEvenAsAPreview() {
        assertEquals(
            TvShellBackAction.ClosePanel,
            tvShellBackAction(panelOpen = true, profileMenuOpen = false, menuFocused = false, onTabRoot = true),
        )
        // Panel outranks the profile menu and a focused bar.
        assertEquals(
            TvShellBackAction.ClosePanel,
            tvShellBackAction(panelOpen = true, profileMenuOpen = true, menuFocused = true, onTabRoot = true),
        )
    }

    @Test
    fun backClosesProfileMenuBeforeHandingTheBarBackToContent() {
        assertEquals(
            TvShellBackAction.CloseProfileMenu,
            tvShellBackAction(panelOpen = false, profileMenuOpen = true, menuFocused = true, onTabRoot = true),
        )
    }

    @Test
    fun backFromAFocusedBarIsMenuBackHomeOrExit() {
        // QA back-stack model: Back on the bar goes Home (or exits from Home);
        // the composable decides which using the current section.
        assertEquals(
            TvShellBackAction.MenuBack,
            tvShellBackAction(panelOpen = false, profileMenuOpen = false, menuFocused = true, onTabRoot = true),
        )
    }

    @Test
    fun backFromTabRootContentClimbsToTheMenu() {
        assertEquals(
            TvShellBackAction.MoveFocusToMenu,
            tvShellBackAction(panelOpen = false, profileMenuOpen = false, menuFocused = false, onTabRoot = true),
        )
    }

    @Test
    fun backFromRootContentRetainsTheActiveRootAsItsMenuTarget() {
        val state = TvShellFocusState()

        assertEquals(
            TvShellBackAction.MoveFocusToMenu,
            state.onBack(
                onTabRoot = true,
                menuFocusTarget = moviesPanel,
            ),
        )

        assertEquals(moviesPanel, state.menuFocusTarget)
    }

    @Test
    fun contentUpOnSecondaryRoutesDoesNotFallbackToHomeFocus() {
        val state = TvShellFocusState()
        val before = state.menuFocusRequest

        state.requestMenuFocusIfAvailable(target = null)

        assertEquals(before, state.menuFocusRequest)
        assertNull(state.menuFocusTarget)
    }

    @Test
    fun contentUpOnSearchMayUseTheSearchOwnedNullTarget() {
        val state = TvShellFocusState()
        val before = state.menuFocusRequest

        state.requestMenuFocusIfAvailable(target = null, allowNullTarget = true)

        assertEquals(before + 1, state.menuFocusRequest)
        assertNull(state.menuFocusTarget)
    }

    @Test
    fun backOnSecondaryScreensStillDelegatesToNav() {
        assertEquals(
            TvShellBackAction.DelegateToNav,
            tvShellBackAction(panelOpen = false, profileMenuOpen = false, menuFocused = false, onTabRoot = false),
        )
    }

    // ---- holder transitions ---------------------------------------------------

    @Test
    fun enteringThenClosingAPanelReturnsFocusToTheBar() {
        val s = TvShellFocusState()
        val tokenBefore = s.panelFocusEntryToken
        val menuBefore = s.menuFocusRequest

        s.enterPanel(moviesPanel)
        assertEquals(moviesPanel, s.openPanel)
        assertTrue(s.panelEntersFocus)
        assertEquals(TvShellMode.Panel, s.mode)
        assertEquals(tokenBefore + 1, s.panelFocusEntryToken)
        // Entering does NOT nudge the bar — only a Back-close does.
        assertEquals(menuBefore, s.menuFocusRequest)

        // Closing never moves focus itself: the caller owns that, so a commit's
        // own content-focus move cannot be raced back to the bar from here.
        s.closePanel()
        assertNull(s.openPanel)
        assertFalse(s.panelEntersFocus)
        assertEquals(menuBefore, s.menuFocusRequest)
        assertNull(s.menuFocusTarget)
    }

    @Test
    fun aCommitCloseDoesNotNudgeTheBarBackOverContentFocus() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        val menuBefore = s.menuFocusRequest
        s.closePanel()
        assertEquals(menuBefore, s.menuFocusRequest)
        assertNull(s.menuFocusTarget)
    }

    /**
     * A dwell preview opens while focus is still on the bar. Back must dismiss
     * it and leave the viewer where they are: routing it through ClosePanel
     * threw them into content from a menu they were still browsing, and cost
     * them the trip back up to reach Home.
     */
    @Test
    fun backDismissesADwellPreviewWithoutLeavingTheBar() {
        val s = TvShellFocusState()
        s.previewPanel(moviesPanel)
        assertEquals(moviesPanel, s.openPanel)
        assertFalse(s.panelEntersFocus)
        val menuBefore = s.menuFocusRequest

        val action = s.onBack(onTabRoot = true)

        assertEquals(TvShellBackAction.ClosePanelPreview, action)
        assertNull(s.openPanel)
        // Focus is on the bar, but not necessarily on the ANCHOR: with no
        // target named, the bar falls back to the SELECTED tab — Home, or the
        // search icon on the Search route — so backing out of Movies' cascade
        // landed on Home. Name the anchor, and suppress its dwell so it does
        // not immediately re-preview the cascade Back just dismissed.
        assertEquals(menuBefore + 1, s.menuFocusRequest)
        assertEquals(moviesPanel, s.menuFocusTarget)
        assertTrue(s.menuFocusSuppressesDwell)
    }

    /**
     * When the bar has installed its synchronous hook, focus moves while the
     * panel is still composed and the state request is not needed. Ordering is
     * the whole point: closing first lets Compose recover focus onto the bar's
     * first child (the search icon) a frame before any request of ours lands,
     * which is a visible flash through search on every Back.
     */
    @Test
    fun theSynchronousAnchorHookReplacesTheDeferredFocusRequest() {
        val s = TvShellFocusState()
        val anchors = mutableListOf<TvTopMenuPanel?>()
        var panelStillOpenWhenFocusMoved: TvTopMenuPanel? = null
        s.focusBarAnchorNow = { anchor ->
            anchors += anchor
            panelStillOpenWhenFocusMoved = s.openPanel
            true
        }
        s.previewPanel(moviesPanel)
        val menuBefore = s.menuFocusRequest

        assertEquals(TvShellBackAction.ClosePanelPreview, s.onBack(onTabRoot = true))

        assertEquals(listOf<TvTopMenuPanel?>(moviesPanel), anchors)
        assertEquals(moviesPanel, panelStillOpenWhenFocusMoved)
        assertNull(s.openPanel)
        assertEquals(menuBefore, s.menuFocusRequest, "the hook moved focus; no deferred request needed")
    }

    /** A hook that could not move focus still falls back to the request. */
    @Test
    fun aFailedAnchorHookFallsBackToTheDeferredRequest() {
        val s = TvShellFocusState()
        s.focusBarAnchorNow = { false }
        s.previewPanel(moviesPanel)
        val menuBefore = s.menuFocusRequest

        s.onBack(onTabRoot = true)

        assertEquals(menuBefore + 1, s.menuFocusRequest)
        assertEquals(moviesPanel, s.menuFocusTarget)
        assertTrue(s.menuFocusSuppressesDwell)
    }

    /**
     * The other half: a panel the viewer actually entered still hands focus to
     * content on Back, rather than stranding them in the chrome.
     */
    @Test
    fun backOutOfAnEnteredPanelStillReturnsToContent() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        s.onPanelFocusChanged(true)
        assertTrue(s.panelEntersFocus)

        val action = s.onBack(onTabRoot = true)

        assertEquals(TvShellBackAction.ClosePanel, action)
        assertNull(s.openPanel)
    }

    /**
     * Entry intent that never became focus. An empty panel, an unattached
     * requester or a silently failed claim all leave the viewer on the bar, and
     * Back must return them to the bar's world rather than throwing them into
     * content they never reached.
     */
    @Test
    fun anEnteredPanelThatNeverTookFocusIsStillTreatedAsAPreview() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        assertTrue(s.panelEntersFocus, "intent is recorded")
        assertFalse(s.panelHasFocus, "but nothing inside it ever focused")

        assertEquals(TvShellBackAction.ClosePanelPreview, s.onBack(onTabRoot = true))
        assertNull(s.openPanel)
    }

    @Test
    fun closingAPanelForgetsThatItHadFocus() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        s.onPanelFocusChanged(true)
        s.closePanel()
        assertFalse(s.panelHasFocus)
    }

    @Test
    fun previewAndEnteredRouteDifferentlyFromTheSameOpenPanel() {
        assertEquals(
            TvShellBackAction.ClosePanelPreview,
            tvShellBackAction(
                panelOpen = true,
                profileMenuOpen = false,
                menuFocused = true,
                onTabRoot = true,
                panelEntered = false,
            ),
        )
        assertEquals(
            TvShellBackAction.ClosePanel,
            tvShellBackAction(
                panelOpen = true,
                profileMenuOpen = false,
                menuFocused = false,
                onTabRoot = true,
                panelEntered = true,
            ),
        )
    }

    /**
     * The stranding bug, reproduced on a Google TV Streamer: Back from content
     * asks the bar to take focus, the bar never reports taking it, and every
     * subsequent Back re-evaluates to the same request. Four consecutive
     * "focus request -> menu" with no "focused -> menu" between them, and Home
     * and exit unreachable for as long as it lasts.
     */
    @Test
    fun aSecondBackGoesHomeWhenTheBarNeverTookFocus() {
        val s = TvShellFocusState()

        // First Back climbs toward the bar.
        assertEquals(TvShellBackAction.MoveFocusToMenu, s.onBack(onTabRoot = true))
        assertTrue(s.barHandoffAttempted)

        // The bar never answers — updateMenuFocused(true) never arrives.
        assertEquals(
            TvShellBackAction.MenuBack,
            s.onBack(onTabRoot = true),
            "a repeat request is what stranded the viewer; the second Back must progress",
        )
    }

    /**
     * The escalation must never fire on Home, because MenuBack there means
     * EXIT. An earlier cut of this branch escalated unconditionally, so a bar
     * that never answered on Home turned the second Back into a silent app
     * exit — a worse failure than the stranding it was meant to fix.
     */
    @Test
    fun theEscalationNeverExitsTheAppFromHome() {
        val s = TvShellFocusState()

        assertEquals(TvShellBackAction.MoveFocusToMenu, s.onBack(onTabRoot = true, onHome = true))
        assertTrue(s.barHandoffAttempted, "the handoff is outstanding, exactly as off Home")

        assertEquals(
            TvShellBackAction.MoveFocusToMenu,
            s.onBack(onTabRoot = true, onHome = true),
            "on Home the unanswered handoff repeats rather than escalating to exit",
        )
    }

    /** When the bar DOES answer, the ladder is unchanged. */
    @Test
    fun aBarThatTakesFocusStillGetsTheNormalLadder() {
        val s = TvShellFocusState()

        assertEquals(TvShellBackAction.MoveFocusToMenu, s.onBack(onTabRoot = true))
        s.updateMenuFocused(true)
        assertFalse(s.barHandoffAttempted, "the bar answered, so nothing is outstanding")

        assertEquals(TvShellBackAction.MenuBack, s.onBack(onTabRoot = true))
    }

    @Test
    fun closingAPanelForgetsAnUnansweredHandoff() {
        val s = TvShellFocusState()
        s.onBack(onTabRoot = true)
        assertTrue(s.barHandoffAttempted)

        s.closePanel()

        assertFalse(s.barHandoffAttempted)
        assertEquals(
            TvShellBackAction.MoveFocusToMenu,
            s.onBack(onTabRoot = true),
            "a fresh Back after a deliberate focus move should climb again, not skip to Home",
        )
    }

    @Test
    fun dwellPreviewNeverOverridesAnEnteredPanel() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        s.previewPanel(seriesPanel) // ignored while a panel is entered
        assertEquals(moviesPanel, s.openPanel)

        s.closePanel()
        s.previewPanel(seriesPanel) // honored once nothing is entered
        assertEquals(seriesPanel, s.openPanel)

        s.previewPanel(null) // a non-tab focus drops the preview
        assertNull(s.openPanel)
    }

    @Test
    fun focusingTheBarClearsAStaleEnteredFlag() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        // A geometric d-pad escape back onto a bar button (not via closePanel).
        s.updateMenuFocused(true)
        assertFalse(s.panelEntersFocus)
        assertTrue(s.isMenuFocused)
    }

    @Test
    fun dismissingTheProfileMenuReturnsFocusToTheAvatar() {
        val s = TvShellFocusState()
        val before = s.profileFocusRequest
        s.previewProfileMenu()
        s.enterProfileMenu()
        assertTrue(s.profileMenuOpen)
        assertTrue(s.isMenuFocusSuppressed)
        assertEquals(TvShellMode.ProfileMenu, s.mode)

        s.dismissProfileMenu()
        assertFalse(s.profileMenuOpen)
        assertEquals(before + 1, s.profileFocusRequest)
    }

    @Test
    fun closingTheProfileMenuForContentDoesNotRefocusTheAvatar() {
        val s = TvShellFocusState()
        s.previewProfileMenu()
        s.enterProfileMenu()
        val before = s.profileFocusRequest
        s.closeProfileMenuForContent()
        assertFalse(s.profileMenuOpen)
        assertEquals(before, s.profileFocusRequest)
    }

    @Test
    fun closingMenuForPopupThenDismissingPopupRefocusesAvatar() {
        val state = TvShellFocusState()
        state.previewProfileMenu()
        state.enterProfileMenu()
        val before = state.profileFocusRequest

        state.closeProfileMenuForContent()
        assertEquals(before, state.profileFocusRequest)

        state.dismissProfileMenu()
        assertEquals(before + 1, state.profileFocusRequest)
    }

    @Test
    fun profileDwellPreviewsWithoutStealingFocusAndDownEnters() {
        val s = TvShellFocusState()

        s.previewProfileMenu()
        assertTrue(s.profileMenuOpen)
        assertFalse(s.profileMenuEntered)
        assertFalse(s.isMenuFocusSuppressed)

        val before = s.profileMenuFocusEntryToken
        s.enterProfileMenu()
        assertTrue(s.profileMenuEntered)
        assertTrue(s.isMenuFocusSuppressed)
        assertEquals(before + 1, s.profileMenuFocusEntryToken)
    }

    @Test
    fun backFromProfileHoverPreviewReturnsFocusToAvatar() {
        val s = TvShellFocusState()
        s.previewProfileMenu()
        val before = s.profileFocusRequest

        assertEquals(TvShellBackAction.CloseProfileMenu, s.onBack(onTabRoot = true))
        assertFalse(s.profileMenuOpen)
        assertEquals(before + 1, s.profileFocusRequest)
    }

    @Test
    fun backFromInsideProfileMenuAlsoReturnsFocusToAvatar() {
        val s = TvShellFocusState()
        s.previewProfileMenu()
        s.enterProfileMenu()
        val before = s.profileFocusRequest

        assertEquals(TvShellBackAction.CloseProfileMenu, s.onBack(onTabRoot = true))
        assertFalse(s.profileMenuOpen)
        assertFalse(s.profileMenuEntered)
        assertEquals(before + 1, s.profileFocusRequest)
    }

    @Test
    fun onBackAppliesTheStateHalfAndReportsTheAction() {
        val s = TvShellFocusState()

        // Panel open → ClosePanel, panel cleared, and focus returned to the
        // anchor tab.
        s.enterPanel(moviesPanel)
        // Entry INTENT is not entry: routing waits for the panel to report that
        // something inside it actually holds focus.
        s.onPanelFocusChanged(true)
        val menuBefore = s.menuFocusRequest
        assertEquals(TvShellBackAction.ClosePanel, s.onBack(onTabRoot = true))
        assertNull(s.openPanel)
        // Back out of an ENTERED cascade also returns to the anchor tab rather
        // than diving into content — the tab the viewer was browsing, with its
        // dwell suppressed so the cascade does not spring straight back open.
        assertEquals(menuBefore + 1, s.menuFocusRequest)
        assertEquals(moviesPanel, s.menuFocusTarget)

        // Profile open → CloseProfileMenu, dropdown closed and avatar nudged.
        s.previewProfileMenu()
        s.enterProfileMenu()
        val profileBefore = s.profileFocusRequest
        assertEquals(TvShellBackAction.CloseProfileMenu, s.onBack(onTabRoot = true))
        assertFalse(s.profileMenuOpen)
        assertEquals(profileBefore + 1, s.profileFocusRequest)

        // Bar focused → MenuBack; the holder leaves home-vs-exit to the caller.
        s.updateMenuFocused(true)
        assertEquals(TvShellBackAction.MenuBack, s.onBack(onTabRoot = true))
        assertTrue(s.isMenuFocused)

        // Nothing to dismiss → DelegateToNav.
        s.updateMenuFocused(false)
        assertEquals(TvShellBackAction.DelegateToNav, s.onBack(onTabRoot = false))
    }
}
