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
        assertEquals(menuBefore, s.menuFocusRequest, "focus was already on the bar; nothing to move")
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

        // Panel open → ClosePanel, panel cleared, and the bar deliberately NOT
        // nudged: Back out of a cascade hands focus to content, so the holder
        // must not claim it for the bar. The caller performs that move.
        s.enterPanel(moviesPanel)
        // Entry INTENT is not entry: routing waits for the panel to report that
        // something inside it actually holds focus.
        s.onPanelFocusChanged(true)
        val menuBefore = s.menuFocusRequest
        assertEquals(TvShellBackAction.ClosePanel, s.onBack(onTabRoot = true))
        assertNull(s.openPanel)
        assertEquals(menuBefore, s.menuFocusRequest)

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
