package com.continuum.app.tv.ui.shell

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
            tvShellBackAction(panelOpen = true, profileMenuOpen = false, menuFocused = false),
        )
        // Panel outranks the profile menu and a focused bar.
        assertEquals(
            TvShellBackAction.ClosePanel,
            tvShellBackAction(panelOpen = true, profileMenuOpen = true, menuFocused = true),
        )
    }

    @Test
    fun backClosesProfileMenuBeforeHandingTheBarBackToContent() {
        assertEquals(
            TvShellBackAction.CloseProfileMenu,
            tvShellBackAction(panelOpen = false, profileMenuOpen = true, menuFocused = true),
        )
    }

    @Test
    fun backFromAFocusedBarMovesFocusToContent() {
        assertEquals(
            TvShellBackAction.MoveFocusToContent,
            tvShellBackAction(panelOpen = false, profileMenuOpen = false, menuFocused = true),
        )
    }

    @Test
    fun backWithNothingToDismissDelegatesToNav() {
        assertEquals(
            TvShellBackAction.DelegateToNav,
            tvShellBackAction(panelOpen = false, profileMenuOpen = false, menuFocused = false),
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

        s.closePanel(returnFocusToBar = true)
        assertNull(s.openPanel)
        assertFalse(s.panelEntersFocus)
        assertEquals(menuBefore + 1, s.menuFocusRequest)
    }

    @Test
    fun aCommitCloseDoesNotNudgeTheBarBackOverContentFocus() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        val menuBefore = s.menuFocusRequest
        s.closePanel(returnFocusToBar = false)
        assertEquals(menuBefore, s.menuFocusRequest)
    }

    @Test
    fun dwellPreviewNeverOverridesAnEnteredPanel() {
        val s = TvShellFocusState()
        s.enterPanel(moviesPanel)
        s.previewPanel(seriesPanel) // ignored while a panel is entered
        assertEquals(moviesPanel, s.openPanel)

        s.closePanel(returnFocusToBar = false)
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
        s.toggleProfileMenu()
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
        s.toggleProfileMenu()
        val before = s.profileFocusRequest
        s.closeProfileMenuForContent()
        assertFalse(s.profileMenuOpen)
        assertEquals(before, s.profileFocusRequest)
    }

    @Test
    fun onBackAppliesTheStateHalfAndReportsTheAction() {
        val s = TvShellFocusState()

        // Panel open → ClosePanel, panel cleared, bar nudged.
        s.enterPanel(moviesPanel)
        val menuBefore = s.menuFocusRequest
        assertEquals(TvShellBackAction.ClosePanel, s.onBack())
        assertNull(s.openPanel)
        assertEquals(menuBefore + 1, s.menuFocusRequest)

        // Profile open → CloseProfileMenu, dropdown closed, avatar nudged.
        s.toggleProfileMenu()
        val profileBefore = s.profileFocusRequest
        assertEquals(TvShellBackAction.CloseProfileMenu, s.onBack())
        assertFalse(s.profileMenuOpen)
        assertEquals(profileBefore + 1, s.profileFocusRequest)

        // Bar focused → MoveFocusToContent; the holder leaves the move to the caller.
        s.updateMenuFocused(true)
        assertEquals(TvShellBackAction.MoveFocusToContent, s.onBack())
        assertTrue(s.isMenuFocused)

        // Nothing to dismiss → DelegateToNav.
        s.updateMenuFocused(false)
        assertEquals(TvShellBackAction.DelegateToNav, s.onBack())
    }
}
