package org.siloserver.silo.tv.ui.focus

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ratchet against silently-failing focus claims in TV screens.
 *
 * `requestFocus()` THROWS when its node has not attached yet, rather than
 * returning false. Wrapping it in `runCatching` therefore does not handle the
 * failure — it hides it: focus goes nowhere, no exception surfaces, and the
 * viewer is left on a screen with nothing focused and no evidence in any log.
 * A leanback app takes no touch input, so there is no fallback either.
 *
 * That is the first of the six recurring causes in
 * `docs/superpowers/specs/2026-08-04-whole-application-focus-hardening-design.md`
 * ("a focus request executing without exception is treated as focus
 * acquisition"), and it is the mechanism behind both #199 (content focus entry
 * found nothing to focus) and #202 (crash-report prompt unreachable, so crash
 * reports were never sendable from a television).
 *
 * The fix already exists: [requestFocusUntilObserved] retries against OBSERVED
 * focus and reports when a claim never lands. At the time this ratchet was
 * added it was used by 8 files while 44 still called `requestFocus()` directly
 * — 18% adoption, which is why the audit's cause #1 was still producing new
 * defects four months on.
 *
 * It began at 78 sites and stopped the 79th. Those 78 have since been migrated
 * on this branch, so [BASELINE] is what remains rather than what it started at.
 *
 * **When you migrate a site, lower [BASELINE] in the same commit.** The
 * assertion is equality on purpose: a `<=` ratchet leaves slack that the next
 * silent claim quietly fills.
 *
 * Two limits, both real, both tolerable only because the baseline is at or near
 * zero:
 *
 * 1. The scan is a fixed character window, not a brace-aware parse. It can pair
 *    a `runCatching` with an unrelated `requestFocus` further down — which
 *    happened during the migration, where a `runCatching { scrollToItem() }`
 *    next to a focus claim inflated the count — and conversely it can miss a
 *    claim written more than [WINDOW] characters from its `runCatching`. A
 *    lexer would fix both and is a great deal of machinery for a source test.
 *
 * 2. The assertion compares a total, not a set. While the baseline was
 *    non-zero, adding one claim and migrating another kept the total and passed.
 *    At zero there is nothing to offset against, so any occurrence fails —
 *    which is the only reason a count is sufficient here. **If this baseline is
 *    ever raised above zero again, that hole reopens**, and the fix is to
 *    compare discovered sites against an approved set rather than a number.
 */
class TvSilentFocusClaimSourceTest {

    private companion object {
        /**
         * Known `runCatching { … requestFocus() … }` sites in TV screens.
         *
         * 2026-08-10: 78 at introduction — player 10, detail 8, settings 7,
         * recommendations 7, calendar 6, auth 6, library 6, search 6, people 5,
         * settings/diagnostics 4, requests 3, profiles 3, admin 2,
         * notifications 2, home 1, audiobook 1, browse 1.
         *
         * 2026-08-10: 76 — the intro auto-skip banner and the HUD option popup
         * migrated to rememberTvContentInitialFocus.
         *
         * 2026-08-10: 73 — the card-overlay preview relocation and both inbox
         * claims migrated to requestFocusUntilObserved.
         *
         * 2026-08-10: 70 — person detail's filter-chip acquisition, its
         * post-filter-change relocation, and the full-bio modal.
         *
         * NOTE: not every remaining site can adopt the policy. Person detail's
         * popup-dismiss restore runs in `DisposableEffect { onDispose { … } }`,
         * which is not a suspend context, so a retry loop cannot run there at
         * all. Sites like that need a different answer than migration, and
         * counting them here is a known limitation of this ratchet rather than
         * a debt that can be paid down to zero.
         *
         * 2026-08-10: 66 — first-run setup, signup, and both login-surface
         * claims.
         *
         * 2026-08-10: 63 — the two library grids and collection detail. The
         * library grids also stopped reporting a handover that had not
         * happened; see that commit.
         *
         * 2026-08-10: 60 — calendar's shelf request (same false handover) and
         * its hand-rolled six-attempt day claim, replaced by the shared policy.
         *
         * 2026-08-10: 54 — settings: the four-attempt entry loop and its
         * unconditional handover, the detail request, the picker dialog, the
         * destructive-confirm Cancel, and the Back-to-category claim, which
         * uses claimFocusOrReport because a BackHandler has no suspend point.
         *
         * There is no longer a category of site that cannot be migrated: a
         * caller without a coroutine still gets a reported failure instead of a
         * swallowed one, so this baseline's floor is zero.
         *
         * 2026-08-10: 49 — person detail's onDispose restore and calendar's
         * Up-fallback branch (both via claimFocusOrReport), plus library's
         * clear-filters pill, sort panel and facet panel.
         *
         * 2026-08-10: 43 — all six search claims, including the four-way
         * post-search target and both return restorations.
         *
         * 2026-08-10: 36 — recommendations: six Boolean-returning bridge and
         * key-handler claims via claimFocusOrReport, plus the For You entry
         * claim, which was the fifth false shell handover found this sweep.
         *
         * 2026-08-10: 31 — admin hub and user edit, browse, the audiobook
         * bookmark delete, and home — home being the sixth false handover.
         *
         * 2026-08-10: 25 — profile form's three D-pad-down key handlers, and
         * requests' entry claim (seventh false handover) plus its post-search
         * target.
         *
         * 2026-08-10: 17 — all eight item-detail sites, including the
         * `runCatching{}.isSuccess` pair that treated "did not throw" as
         * "focused".
         *
         * 2026-08-10: 9 — the player: HUD tab seed and picker return, the
         * hidden-overlay root claim, the idle overlay target, both transport
         * handoffs and the up-next primary action.
         *
         * 2026-08-10: 2 — diagnostics settings, server setup, person detail's
         * focusBio, and calendar's NavHost-restore handoff.
         *
         * The two that remain are both in TvDiagnosticsPromptScreen, and they
         * are deliberately NOT migrated here. Retrying that claim cannot work
         * from inside the shell's content Box: its focusRestorer intercepts
         * focus ENTRY and reroutes it, so the retry loops into the same
         * interception forever. The fix is to give the prompt its own Dialog
         * window, which is a separate change; migrating these two here would
         * make the code look correct while the prompt stayed unreachable.
         *
         * Drop this to 0 when that change lands.
         *
         * Everywhere else is zero. Any new `runCatching { requestFocus() }` in
         * a TV screen fails the build, and the two tools between them cover
         * every context: requestFocusUntilObserved where a coroutine exists,
         * claimFocusOrReport where the caller must answer synchronously.
         */
        const val BASELINE = 0

        const val SCREENS_ROOT = "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens"

        /**
         * How far past `runCatching` to look for the call. Wide enough for the
         * multi-line form, narrow enough not to pair a `runCatching` with an
         * unrelated `requestFocus()` further down the file.
         */
        const val WINDOW = 220
    }

    @Test
    fun tvScreensDoNotAddNewSilentFocusClaims() {
        val offenders = mutableListOf<String>()
        var count = 0

        File(SCREENS_ROOT).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val text = file.readText()
                var found = 0
                var index = text.indexOf("runCatching")
                while (index >= 0) {
                    val end = (index + "runCatching".length + WINDOW).coerceAtMost(text.length)
                    if (text.substring(index, end).contains("requestFocus(")) found++
                    index = text.indexOf("runCatching", index + 1)
                }
                if (found > 0) {
                    count += found
                    offenders += "${file.path}: $found"
                }
            }

        assertEquals(
            BASELINE,
            count,
            buildString {
                appendLine("Silent focus claims in TV screens changed: expected $BASELINE, found $count.")
                appendLine()
                if (count > BASELINE) {
                    appendLine("A new `runCatching { ... requestFocus() ... }` was added.")
                    appendLine("requestFocus() throws when its node has not attached, so runCatching")
                    appendLine("hides the failure instead of handling it — focus goes nowhere and")
                    appendLine("nothing is logged. On a television there is no touch fallback.")
                    appendLine()
                    appendLine("Use requestFocusUntilObserved (ui/focus/TvObservedFocusPolicy.kt),")
                    appendLine("which retries against observed focus and reports a claim that never")
                    appendLine("lands.")
                } else {
                    appendLine("Sites were migrated — thank you. Lower BASELINE to $count in this")
                    appendLine("same commit so the ratchet keeps its zero slack.")
                }
                appendLine()
                appendLine("Current sites:")
                offenders.forEach { appendLine("  $it") }
            },
        )
    }
}
