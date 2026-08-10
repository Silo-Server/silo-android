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
 * This test does not fix those 78 sites. It stops the 79th.
 *
 * **When you migrate a site, lower [BASELINE] in the same commit.** The
 * assertion is equality on purpose: a `<=` ratchet leaves slack that the next
 * silent claim quietly fills.
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
         */
        const val BASELINE = 70

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
