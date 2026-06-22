package com.continuum.app.tv.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The mutually-exclusive interaction surface that currently owns TV-shell focus.
 *
 * Before this existed, [TvMainShell] tracked the same information as a loose bag
 * of booleans and Int counters (`isMenuFocused`, `profileMenuOpen`, `openPanel`,
 * `panelEntersFocus`, …) mutated from a dozen call sites — the source of a long
 * tail of "fix focus" regressions. [TvShellFocusState] is now the single owner
 * and this enum is its canonical, derived summary.
 */
enum class TvShellMode {
    /** Focus is down in the page content — the resting state. */
    Content,

    /** Focus is on a top-bar button (a tab, search, or the avatar). */
    MenuFocused,

    /** The profile dropdown is open and trapping focus. */
    ProfileMenu,

    /** A cascade panel has been *entered* (focus is inside it, not a mere preview). */
    Panel,
}

/**
 * What a shell-level Back/Escape press should do, decided purely from shell
 * state. The composable performs the matching side effect (focus move / nav pop)
 * for the cases the holder cannot reach on its own.
 */
sealed interface TvShellBackAction {
    /** A cascade panel (preview or entered) was open; it is now closed and focus returned to the bar. */
    data object ClosePanel : TvShellBackAction

    /** The profile dropdown was open; it is now closed and focus returns to the avatar. */
    data object CloseProfileMenu : TvShellBackAction

    /** Focus was on the menu bar; the caller hands it back to content. */
    data object MoveFocusToContent : TvShellBackAction

    /** Nothing to dismiss; the caller pops the nested NavHost or lets the activity finish. */
    data object DelegateToNav : TvShellBackAction
}

/**
 * Pure derivation of [TvShellMode] from the raw focus flags. A cascade *preview*
 * (panelEntered == false) is NOT [TvShellMode.Panel] because focus is still on
 * the bar tab above it — only an *entered* panel owns focus. The profile menu
 * outranks everything because it focus-traps.
 */
internal fun tvShellMode(
    profileMenuOpen: Boolean,
    panelOpen: Boolean,
    panelEntered: Boolean,
    menuFocused: Boolean,
): TvShellMode = when {
    profileMenuOpen -> TvShellMode.ProfileMenu
    panelOpen && panelEntered -> TvShellMode.Panel
    menuFocused -> TvShellMode.MenuFocused
    else -> TvShellMode.Content
}

/**
 * Pure Back/Escape routing, mirroring the historical shell `onPreviewKeyEvent`
 * `when`. The order is load-bearing and was settled across several "fix focus"
 * commits: an open cascade panel (even a mere preview) is dismissed first, then
 * the profile dropdown, then a focused menu bar hands focus back to content;
 * only with nothing to dismiss does Back fall through to navigation.
 */
internal fun tvShellBackAction(
    panelOpen: Boolean,
    profileMenuOpen: Boolean,
    menuFocused: Boolean,
): TvShellBackAction = when {
    panelOpen -> TvShellBackAction.ClosePanel
    profileMenuOpen -> TvShellBackAction.CloseProfileMenu
    menuFocused -> TvShellBackAction.MoveFocusToContent
    else -> TvShellBackAction.DelegateToNav
}

/**
 * Single owner of the TV shell's focus / overlay state. Replaces the prior set
 * of independent `mutableStateOf` flags and Int counters scattered through
 * [TvMainShell] with one holder exposing a derived [mode] plus named transitions,
 * so no call site mutates a raw flag or bumps a bare counter.
 *
 * **Counters retained on purpose.** [menuFocusRequest] / [profileFocusRequest] /
 * [panelFocusEntryToken] are monotonic nudge counters the child [TvTopMenuBar]
 * and the cascade selector observe to re-run their focus effects. They stay
 * counters (rather than collapsing into direct `FocusRequester` calls) because
 * those requesters live inside those children; routing focus to them from here
 * would mean hoisting the requesters up — a larger change that needs on-device
 * D-pad verification, tracked as a follow-up. Centralizing them behind named
 * methods is the safe, behavior-preserving step this refactor takes.
 */
@Stable
class TvShellFocusState {
    /** Nudge the menu bar to re-request focus on its currently-selected tab. */
    var menuFocusRequest by mutableIntStateOf(0)
        private set

    /** Nudge the menu bar to return focus to the profile avatar. */
    var profileFocusRequest by mutableIntStateOf(0)
        private set

    /** Re-fire the cascade selector's focus-entry effect when a panel is entered. */
    var panelFocusEntryToken by mutableIntStateOf(0)
        private set

    /** True while focus is on any top-bar button. */
    var isMenuFocused by mutableStateOf(false)
        private set

    /** True while the profile dropdown is open. */
    var profileMenuOpen by mutableStateOf(false)
        private set

    /** The cascade panel currently previewed or entered, or null. */
    var openPanel by mutableStateOf<TvTopMenuPanel?>(null)
        private set

    /** True once the user has committed to *entering* [openPanel] (vs previewing). */
    var panelEntersFocus by mutableStateOf(false)
        private set

    /** Canonical, derived interaction surface. */
    val mode: TvShellMode
        get() = tvShellMode(
            profileMenuOpen = profileMenuOpen,
            panelOpen = openPanel != null,
            panelEntered = panelEntersFocus,
            menuFocused = isMenuFocused,
        )

    /**
     * Whether the bar should suppress its own focus — true while the profile
     * dropdown owns focus, so D-pad input below cannot pull focus back up.
     */
    val isMenuFocusSuppressed: Boolean
        get() = profileMenuOpen

    // --- Menu-bar focus signals -------------------------------------------------

    /** Route focus to the bar's selected tab (content → bar Up, or panel close). */
    fun requestMenuFocus() {
        menuFocusRequest++
    }

    /**
     * Record whether a bar button holds focus. Focus on the bar means we are not
     * inside a panel, so clear any stale entered flag — otherwise a geometric
     * d-pad escape out of an entered panel leaves the preview frozen under the
     * previously-entered tab while moving along the bar.
     */
    fun updateMenuFocused(focused: Boolean) {
        isMenuFocused = focused
        if (focused) panelEntersFocus = false
    }

    // --- Profile dropdown -------------------------------------------------------

    /** Toggle the profile dropdown (avatar tap). */
    fun toggleProfileMenu() {
        profileMenuOpen = !profileMenuOpen
    }

    /**
     * Close the dropdown without returning focus to the avatar — used when focus
     * is about to move into content anyway (a menu action, or a screen's initial
     * content focus).
     */
    fun closeProfileMenuForContent() {
        profileMenuOpen = false
    }

    /** Close the dropdown and return focus to the avatar that opened it (Back / dismiss). */
    fun dismissProfileMenu() {
        profileMenuOpen = false
        profileFocusRequest++
    }

    // --- Cascade panel ----------------------------------------------------------

    /**
     * Dwell preview: show [panel] (or clear it when null). An already-entered
     * panel is immune — only Back or a commit closes it, never a dwell — so a
     * preview can never steal an entered panel out from under the user.
     */
    fun previewPanel(panel: TvTopMenuPanel?) {
        if (!panelEntersFocus) {
            openPanel = panel
        }
    }

    /** Commit to entering [panel]: focus moves into it and the entry effect re-fires. */
    fun enterPanel(panel: TvTopMenuPanel) {
        openPanel = panel
        panelEntersFocus = true
        panelFocusEntryToken++
    }

    /**
     * Close any open panel. [returnFocusToBar] re-focuses the originating tab on
     * a Back-close; a commit passes false so its own content-focus move is not
     * raced back to the bar by the focus bump.
     */
    fun closePanel(returnFocusToBar: Boolean) {
        openPanel = null
        panelEntersFocus = false
        if (returnFocusToBar) {
            menuFocusRequest++
        }
    }

    // --- Back routing -----------------------------------------------------------

    /**
     * Apply the state half of a shell Back press and report what the caller
     * should do for the side-effecting cases ([TvShellBackAction.MoveFocusToContent]
     * and [TvShellBackAction.DelegateToNav] are left to the composable, which owns
     * the focus manager and nav controller).
     */
    fun onBack(): TvShellBackAction {
        val action = tvShellBackAction(
            panelOpen = openPanel != null,
            profileMenuOpen = profileMenuOpen,
            menuFocused = isMenuFocused,
        )
        when (action) {
            TvShellBackAction.ClosePanel -> closePanel(returnFocusToBar = true)
            TvShellBackAction.CloseProfileMenu -> dismissProfileMenu()
            TvShellBackAction.MoveFocusToContent,
            TvShellBackAction.DelegateToNav -> Unit
        }
        return action
    }
}

/** Remembers a [TvShellFocusState] for the lifetime of the shell composition. */
@Composable
fun rememberTvShellFocusState(): TvShellFocusState = remember { TvShellFocusState() }
