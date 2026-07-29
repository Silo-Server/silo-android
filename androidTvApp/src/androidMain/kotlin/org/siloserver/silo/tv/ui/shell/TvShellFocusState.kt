package org.siloserver.silo.tv.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.siloserver.silo.common.diagnostics.DiagnosticsFocusLogger

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

    /** The profile dropdown was open; it is now closed and focus returns to the appropriate bar item. */
    data object CloseProfileMenu : TvShellBackAction

    /**
     * Focus was on the menu bar: on Home the caller lets the activity finish
     * (exit); on any other section the caller navigates Home keeping the bar
     * focused. (QA back-stack model, 2026-07-08.)
     */
    data object MenuBack : TvShellBackAction

    /** Content on a tab root pressed Back: the caller focuses the bar's selected tab. */
    data object MoveFocusToMenu : TvShellBackAction

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
    onTabRoot: Boolean,
): TvShellBackAction = when {
    panelOpen -> TvShellBackAction.ClosePanel
    profileMenuOpen -> TvShellBackAction.CloseProfileMenu
    // Back-stack model (QA 2026-07-08): content Back on a tab root climbs to
    // the bar; Back on the bar goes Home (or exits from Home). Secondary
    // screens (Settings, Search, …) still pop navigation.
    menuFocused -> TvShellBackAction.MenuBack
    onTabRoot -> TvShellBackAction.MoveFocusToMenu
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

    /**
     * Optional explicit bar element for [menuFocusRequest]. A panel Back-close
     * returns to its own anchor and lets the bar suppress that anchor's dwell
     * preview; ordinary content-to-bar moves leave this null and use the
     * selected tab normally.
     */
    var menuFocusTarget by mutableStateOf<TvTopMenuPanel?>(null)
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
    var profileMenuEntered by mutableStateOf(false)
        private set
    var profileMenuFocusEntryToken by mutableIntStateOf(0)
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
        get() = profileMenuEntered

    // --- Menu-bar focus signals -------------------------------------------------

    /** Route focus to the bar's selected tab (content → bar Up, or panel close). */
    fun requestMenuFocus(target: TvTopMenuPanel? = null) {
        DiagnosticsFocusLogger.transition(target?.diagnosticsTarget() ?: "menu", "request")
        menuFocusTarget = target
        menuFocusRequest++
    }

    /**
     * Route content focus back to the bar only when the shell has a concrete root
     * target, or when a route-specific owner intentionally handles a null target
     * (currently Search). Other secondary routes must not fall through to Home.
     */
    fun requestMenuFocusIfAvailable(target: TvTopMenuPanel?, allowNullTarget: Boolean = false) {
        if (target == null && !allowNullTarget) return
        requestMenuFocus(target)
    }

    /**
     * Record whether a bar button holds focus. Focus on the bar means we are not
     * inside a panel, so clear any stale entered flag — otherwise a geometric
     * d-pad escape out of an entered panel leaves the preview frozen under the
     * previously-entered tab while moving along the bar.
     */
    fun updateMenuFocused(focused: Boolean) {
        if (isMenuFocused != focused) {
            DiagnosticsFocusLogger.transition("menu", if (focused) "focused" else "blurred")
        }
        isMenuFocused = focused
        if (focused) {
            panelEntersFocus = false
            if (profileMenuOpen && !profileMenuEntered) profileMenuOpen = false
        }
    }

    // --- Profile dropdown -------------------------------------------------------

    /** Open the profile dropdown from avatar dwell without toggle semantics. */
    fun previewProfileMenu() {
        DiagnosticsFocusLogger.transition("profile_menu", "preview")
        openPanel = null
        panelEntersFocus = false
        profileMenuOpen = true
        profileMenuEntered = false
    }

    fun enterProfileMenu() {
        DiagnosticsFocusLogger.transition("profile_menu", "enter")
        profileMenuOpen = true
        profileMenuEntered = true
        profileMenuFocusEntryToken++
    }

    fun closeProfilePreview() {
        DiagnosticsFocusLogger.transition("profile_menu", "close_preview")
        if (!profileMenuEntered) profileMenuOpen = false
    }

    /**
     * Close the dropdown without returning focus to the avatar — used when focus
     * is about to move into content anyway (a menu action, or a screen's initial
     * content focus).
     */
    fun closeProfileMenuForContent() {
        DiagnosticsFocusLogger.transition("content", "focus")
        profileMenuOpen = false
        profileMenuEntered = false
    }

    /** Close the dropdown and return focus to the avatar that opened it (Back / dismiss). */
    fun dismissProfileMenu() {
        DiagnosticsFocusLogger.transition("profile_menu", "dismiss")
        profileMenuOpen = false
        profileMenuEntered = false
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
            DiagnosticsFocusLogger.transition(
                panel?.diagnosticsTarget() ?: "panel",
                if (panel == null) "close_preview" else "preview",
            )
            openPanel = panel
        }
    }

    /** Commit to entering [panel]: focus moves into it and the entry effect re-fires. */
    fun enterPanel(panel: TvTopMenuPanel) {
        DiagnosticsFocusLogger.transition(panel.diagnosticsTarget(), "enter")
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
        val closingPanel = openPanel
        openPanel = null
        panelEntersFocus = false
        DiagnosticsFocusLogger.transition(closingPanel?.diagnosticsTarget() ?: "panel", "close")
        if (returnFocusToBar && closingPanel != null) {
            requestMenuFocus(closingPanel)
        }
    }

    // --- Back routing -----------------------------------------------------------

    /**
     * Apply the state half of a shell Back press and report what the caller
     * should do for the side-effecting cases ([TvShellBackAction.MoveFocusToContent]
     * and [TvShellBackAction.DelegateToNav] are left to the composable, which owns
     * the focus manager and nav controller).
     */
    fun onBack(
        onTabRoot: Boolean,
        menuFocusTarget: TvTopMenuPanel? = null,
    ): TvShellBackAction {
        val action = tvShellBackAction(
            panelOpen = openPanel != null,
            profileMenuOpen = profileMenuOpen,
            menuFocused = isMenuFocused,
            onTabRoot = onTabRoot,
        )
        when (action) {
            TvShellBackAction.ClosePanel -> closePanel(returnFocusToBar = true)
            TvShellBackAction.CloseProfileMenu -> dismissProfileMenu()
            TvShellBackAction.MoveFocusToMenu -> requestMenuFocus(menuFocusTarget)
            TvShellBackAction.MenuBack,
            TvShellBackAction.DelegateToNav -> Unit
        }
        return action
    }
}

/** Remembers a [TvShellFocusState] for the lifetime of the shell composition. */
@Composable
fun rememberTvShellFocusState(): TvShellFocusState = remember { TvShellFocusState() }

private fun TvTopMenuPanel.diagnosticsTarget(): String = when (this) {
    TvTopMenuPanel.Profile -> "profile_menu"
    is TvTopMenuPanel.Root -> "root_panel"
}
