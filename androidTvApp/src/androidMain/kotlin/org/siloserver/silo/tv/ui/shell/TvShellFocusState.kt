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

    /**
     * A dwell PREVIEW was showing while focus stayed on the bar. Back dismisses
     * the preview and nothing else: the viewer never left the chrome, so moving
     * them into content would be a jump they did not ask for, and would cost
     * them the trip back up to reach Home.
     */
    data object ClosePanelPreview : TvShellBackAction

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
 * commits: an open cascade panel is dismissed first — a preview
 * without moving focus, an entered panel by handing focus to content — then the
 * profile dropdown, then a focused menu bar; only with nothing to dismiss does
 * Back fall through to navigation.
 */
internal fun tvShellBackAction(
    panelOpen: Boolean,
    profileMenuOpen: Boolean,
    menuFocused: Boolean,
    onTabRoot: Boolean,
    panelEntered: Boolean = true,
    barHandoffAttempted: Boolean = false,
    onHome: Boolean = false,
): TvShellBackAction = when {
    // A panel the viewer never entered is a dwell preview: focus is still on
    // the bar, so dismissing it must leave focus there. Routing this through
    // ClosePanel sent them into content from a menu they were still browsing.
    panelOpen && !panelEntered -> TvShellBackAction.ClosePanelPreview
    panelOpen -> TvShellBackAction.ClosePanel
    profileMenuOpen -> TvShellBackAction.CloseProfileMenu
    // Back-stack model (QA 2026-07-08): content Back on a tab root climbs to
    // the bar; Back on the bar goes Home (or exits from Home). Secondary
    // screens (Settings, Search, …) still pop navigation.
    menuFocused -> TvShellBackAction.MenuBack
    // The handoff to the bar was already asked for and the bar never reported
    // taking focus. Asking again is what strands the viewer: every Back
    // re-evaluates to MoveFocusToMenu, MenuBack is never reached, and Home and
    // exit become unreachable — observed on a Google TV Streamer as four
    // consecutive "focus request -> menu" with no "focused -> menu" between
    // them. Progress matters more than tidiness here, so the second Back goes
    // Home regardless of where focus actually is.
    // Escalate only where MenuBack actually NAVIGATES. On Home, MenuBack means
    // "exit the app", so escalating there turns a Back the viewer expected to
    // move focus into quitting Silo — which is worse than the loop it fixes.
    onTabRoot && barHandoffAttempted && !onHome -> TvShellBackAction.MenuBack
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

    /**
     * Whether [menuFocusRequest] should also suppress its target's dwell
     * preview.
     *
     * Only a panel Back-close wants that: reopening the panel the user just
     * dismissed is the thing being prevented. An ordinary content-to-bar Up
     * carries a target too — it decides which tab to land on — and arming
     * suppression from that left the tab you came back to unable to reopen its
     * own cascade at all, which is what testers hit on Movies and TV alike.
     */
    var menuFocusSuppressesDwell by mutableStateOf(false)
        private set

    /**
     * Whether anything inside the open panel actually holds focus, reported by
     * the selector as its rows and pills gain and lose it.
     *
     * Back routing asks this rather than [panelEntersFocus], because entry
     * INTENT is not entry: an empty panel, an unattached requester, or a claim
     * that silently failed all leave focus on the bar while the intent flag
     * says otherwise — and Back would then throw the viewer into content from a
     * bar they never left.
     */
    var panelHasFocus by mutableStateOf(false)
        private set

    fun onPanelFocusChanged(focused: Boolean) {
        panelHasFocus = focused
    }

    /** Nudge the menu bar to return focus to the profile avatar. */
    var profileFocusRequest by mutableIntStateOf(0)
        private set

    /**
     * True once Back has asked the bar to take focus and the bar has not yet
     * reported doing so.
     *
     * [requestMenuFocus] only bumps a token — it cannot know whether the claim
     * landed, and nothing corrected it when it did not. This records the
     * attempt so a second Back can escalate instead of repeating a request that
     * is evidently not working.
     */
    var barHandoffAttempted by mutableStateOf(false)
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

    /**
     * True once the user has committed to *entering* [openPanel] (vs previewing).
     *
     * Intent, not arrival: this is set before the selector's asynchronous focus
     * request runs. Use [panelHasFocus] for questions about where focus IS.
     */
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

    /**
     * Moves bar focus to a panel's anchor RIGHT NOW, installed by the bar.
     *
     * Closing a cascade removes the focused node, and Compose recovers focus a
     * frame before any request of ours can land — it picks the bar's first
     * child, so Back visibly flashed through the search icon on its way to the
     * anchor. Nothing written to state can win that frame; the fix is to move
     * focus while the panel is still composed, leaving no recovery to lose.
     * Returns whether focus actually moved.
     */
    var focusBarAnchorNow: ((TvTopMenuPanel?) -> Boolean)? = null

    /** Route focus to the bar's selected tab (content → bar Up, or panel close). */
    fun requestMenuFocus(target: TvTopMenuPanel? = null, suppressDwellPreview: Boolean = false) {
        DiagnosticsFocusLogger.transition(target?.diagnosticsTarget() ?: "menu", "request")
        menuFocusTarget = target
        menuFocusSuppressesDwell = suppressDwellPreview
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
            // The bar answered, so the outstanding handoff is settled.
            barHandoffAttempted = false
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
     * Close any open panel, leaving focus where it is. Callers that want focus
     * moved do it themselves, so a commit's own content-focus move is never
     * raced back to the bar by a focus bump from here.
     */
    fun closePanel() {
        // Closing hands focus somewhere deliberate, so any stale unanswered
        // handoff no longer describes the current situation.
        barHandoffAttempted = false
        val closingPanel = openPanel
        openPanel = null
        panelEntersFocus = false
        panelHasFocus = false
        DiagnosticsFocusLogger.transition(closingPanel?.diagnosticsTarget() ?: "panel", "close")
    }

    // --- Back routing -----------------------------------------------------------

    /**
     * Apply the state half of a shell Back press and report what the caller
     * should do for the side-effecting cases ([TvShellBackAction.ClosePanel] and
     * [TvShellBackAction.DelegateToNav] are left to the composable, which owns
     * the focus manager and nav controller).
     */
    fun onBack(
        onTabRoot: Boolean,
        menuFocusTarget: TvTopMenuPanel? = null,
        onHome: Boolean = false,
    ): TvShellBackAction {
        val action = tvShellBackAction(
            panelOpen = openPanel != null,
            profileMenuOpen = profileMenuOpen,
            menuFocused = isMenuFocused,
            onTabRoot = onTabRoot,
            panelEntered = panelHasFocus,
            barHandoffAttempted = barHandoffAttempted,
            onHome = onHome,
        )
        when (action) {
            // Back out of a cascade the viewer ENTERED hands focus to content,
            // not back to the anchor tab. Parking them one level up in the
            // chrome is what "back doesn't exit the menus" meant.
            TvShellBackAction.ClosePanel -> closePanelOntoAnchor()
            // The preview case looks like focus never left the bar, but it is
            // not necessarily on the ANCHOR: with no explicit target the bar
            // falls back to selectedEntryRequester(), which is the selected tab
            // — Home, or the search icon on the Search route. Backing out of
            // Movies' cascade therefore landed on Home.
            TvShellBackAction.ClosePanelPreview -> closePanelOntoAnchor()
            TvShellBackAction.CloseProfileMenu -> dismissProfileMenu()
            // Back from content climbs to the bar, and must NOT pop that tab's
            // cascade on arrival: the viewer is leaving, not browsing. Without
            // this the next Back sees an open panel and closes it back to
            // content, so Back ping-pongs and never reaches MenuBack — Home and
            // exit become unreachable. Up from content is the browsing case and
            // stays unsuppressed, which is what makes the cascade openable.
            TvShellBackAction.MoveFocusToMenu -> {
                barHandoffAttempted = true
                requestMenuFocus(menuFocusTarget, suppressDwellPreview = true)
            }
            TvShellBackAction.MenuBack,
            TvShellBackAction.DelegateToNav -> Unit
        }
        return action
    }

    /**
     * Close the open panel and leave focus on ITS anchor tab, with that tab's
     * dwell preview suppressed.
     *
     * Order matters: focus moves first, while the panel is still composed, so
     * removing it never triggers Compose's focus recovery. The state request is
     * the fallback for when the bar has not installed its hook, or the anchor is
     * not focusable yet — it lands a frame later, which is exactly the window
     * that made Back flash through the search icon.
     *
     * The suppression is the other half: without it the anchor re-previews its
     * cascade ~250ms later and the next Back is spent closing that preview
     * instead of reaching MenuBack, so Home stays unreachable.
     */
    private fun closePanelOntoAnchor() {
        val anchor = openPanel
        val moved = focusBarAnchorNow?.invoke(anchor) ?: false
        closePanel()
        if (!moved) requestMenuFocus(anchor, suppressDwellPreview = true)
    }
}

/** Remembers a [TvShellFocusState] for the lifetime of the shell composition. */
@Composable
fun rememberTvShellFocusState(): TvShellFocusState = remember { TvShellFocusState() }

private fun TvTopMenuPanel.diagnosticsTarget(): String = when (this) {
    TvTopMenuPanel.Profile -> "profile_menu"
    is TvTopMenuPanel.Root -> "root_panel"
}
