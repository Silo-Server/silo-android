package org.siloserver.silo.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Call-site guard. [org.siloserver.silo.tv.ui.focus.TvControlSemanticsTest] proves the
 * primitive behaves; this proves the screens still USE it, which is the part a
 * Compose harness cannot reach without composing every screen.
 *
 * Scope matters as much as the pattern. Every assertion below is anchored to
 * the single composable that owns the wiring, not to the file: a whole-file
 * search passes as long as *some* control in the file is wired, so deleting the
 * wiring from one of six controls in the same file would go unnoticed. Comments
 * are stripped first for the same reason — prose describing a rule must not be
 * able to satisfy it.
 *
 * Matching is whitespace-insensitive on purpose: these assert the rule, not a
 * formatting snapshot, so reformatting the sources never fails the build.
 */
class TvControlWiringCallSiteTest {

    /**
     * Structurally unavailable controls hand `enabled` to the real interactive
     * primitive, so they drop out of D-pad traversal instead of sitting in it
     * as dead stops. Asserted as a count, so a second control appearing in the
     * same composable cannot stand in for the one that lost its wiring.
     */
    @Test
    fun structurallyDisabledControlsLeaveTheFocusGraph() {
        listOf(
            StructuralControl(
                path = "ui/components/TvOptionDialog.kt",
                composable = "TvOptionDialogRow",
                primitive = "Surface(",
                wiring = "enabled = enabled",
            ),
            StructuralControl(
                path = "ui/components/TvAuroraChrome.kt",
                composable = "AuroraPrimaryButton",
                primitive = ".clickable(",
                wiring = "enabled = enabled",
            ),
            StructuralControl(
                path = "ui/components/TvSquaredButtons.kt",
                composable = "SquaredPillSurface",
                primitive = ".clickable(",
                wiring = "enabled = enabled",
            ),
        ).forEach { control ->
            val body = source(control.path).declarationBody(control.composable)
            val label = "${control.path}:${control.composable}"

            assertTrue(
                body.containsLoosely(control.primitive),
                "$label should reach its interactive primitive ${control.primitive}",
            )
            // Scoped to the primitive's own argument list, not the whole
            // declaration: an `enabled = enabled` on some unrelated child call
            // must not stand in for the primitive having lost its wiring.
            assertEquals(
                1,
                body.argumentsOf(control.primitive).countLoosely(control.wiring),
                "$label should pass its disabled state to ${control.primitive} itself",
            )
        }
    }

    private data class StructuralControl(
        val path: String,
        val composable: String,
        val primitive: String,
        val wiring: String,
    )

    /**
     * The inverse rule, and the one that actually strands viewers: a control
     * gated by work in flight must NOT leave the focus graph. Android TV does
     * not re-home focus when the focused node stops being focusable, and every
     * initial-focus policy here is one-shot.
     *
     * So the gate composable builds a transient state, and the key composable
     * feeds the focus graph `controlState.focusable` — constant `true` for a
     * transient state — while reporting the real state to accessibility.
     */
    @Test
    fun transientlyGatedControlsStayFocusableAndAnnounceDisabled() {
        listOf(
            Triple("ui/components/TvPinEntryDialog.kt", "PinKeypad", "PinKey"),
            Triple(
                "ui/screens/watchtogether/TvJoinCodeDialog.kt",
                "TvJoinCodeDialog",
                "JoinCodeKey",
            ),
        ).forEach { (path, gate, control) ->
            val text = source(path)

            assertTrue(
                text.declarationBody(gate).containsLoosely("TvControlState.transient("),
                "$path:$gate gates on work in flight and must build a transient state",
            )

            val controlBody = text.declarationBody(control)
            assertEquals(
                1,
                controlBody.countLoosely("enabled = controlState.focusable"),
                "$path:$control must stay focusable while gated",
            )
            assertTrue(
                controlBody.containsLoosely(".tvControlSemantics(controlState)"),
                "$path:$control must still report the gated state to accessibility",
            )
            assertFalse(
                controlBody.containsLoosely("enabled = enabled"),
                "$path:$control must not hand in-flight gating to the focus graph",
            )
        }
    }

    /**
     * The selector pill is the one deliberate exception to both rules above,
     * and it is an exception because it is not a control.
     *
     * A pill with a single real choice — one version, one audio track — is not
     * a disabled button; it is Apple's `TVSelectorValue`, a value display that
     * happens to sit in a row of buttons. `SquaredPillSurface` routes `enabled`
     * into `Modifier.clickable`, and a disabled clickable is also unfocusable,
     * so wiring `interactive` into it would delete the pill from the focus
     * graph. Most titles have exactly one version and one audio track, so that
     * is not a rare edge: the common case would draw three pills and let the
     * viewer reach none of them, with Down from the action row skipping the
     * cluster outright.
     *
     * So this pill stays focusable and no-ops on Select, and the chevron —
     * hidden when the pill will not open — is what carries the signal.
     */
    @Test
    fun singleChoiceSelectorPillStaysFocusableAndNoOps() {
        val selector = source("ui/components/TvAnchoredSelectorMenu.kt")
            .declarationBody("TvAnchoredSelectorMenu")
        val pillArguments = selector.argumentsOf("SquaredPillSurface(")

        assertFalse(
            pillArguments.containsLoosely("enabled ="),
            "the selector pill must stay focusable, so it must not hand its trigger an enabled flag",
        )
        assertEquals(
            1,
            pillArguments.countLoosely("onClick = { if (interactive) expansionRequested = true }"),
            "a non-interactive selector pill must swallow Select rather than leave the focus graph",
        )
        assertEquals(
            1,
            selector.countLoosely("if (interactive) {"),
            "the chevron must be hidden when the pill will not open",
        )
    }

    /**
     * The pre-existing anti-pattern: focusable, but silently inert. Matched on
     * the shape of the guard rather than one spelling of the callee — the
     * swallowed call is rarely named `onClick()`, and pinning the assertion to
     * that literal is how this pattern survived at `OverlayTile`'s call site.
     */
    @Test
    fun noControlFakesDisabledStateInsideItsClickHandler() {
        listOf(
            "ui/components/TvOptionDialog.kt",
            "ui/components/TvAuroraChrome.kt",
            "ui/components/TvPinEntryDialog.kt",
            "ui/screens/watchtogether/TvJoinCodeDialog.kt",
        ).forEach { path ->
            assertFalse(
                source(path).containsLoosely("onClick = { if ("),
                "$path should express disabled state, not swallow the click",
            )
        }
    }

    /**
     * Interactivity loss must collapse the dropdown in the SAME composition, not
     * a frame later via an effect — otherwise the menu is briefly drawn over a
     * trigger that has already left the focus graph.
     */
    @Test
    fun selectorCollapsesSynchronouslyAndClearsItsStoredExpansion() {
        val selector = source("ui/components/TvAnchoredSelectorMenu.kt")
            .declarationBody("TvAnchoredSelectorMenu")

        assertTrue(
            selector.containsLoosely(
                "val expanded = selectorExpansionAfterInteractivityChange(expansionRequested, interactive)",
            ),
            "expansion should be derived at read time",
        )
        assertTrue(
            selector.containsLoosely("LaunchedEffect(interactive) {"),
            "the stored expansion bit should still be cleared",
        )
    }

    /**
     * Cascade rows must carry Compose keys in BOTH list-size branches.
     * `stableIdentityValues` keeps requester ownership stable on its own, but
     * without the keys the row composables still swap identity on a reorder.
     */
    @Test
    fun cascadeLibraryRowsAreKeyedInBothBranches() {
        val cascade = source("ui/components/TvCascadeSelector.kt")

        assertTrue(cascade.containsLoosely("key(library.id) {"), "eager rows need a key")
        assertTrue(
            cascade.containsLoosely("items(libraries, key = { it.id }) { library ->"),
            "lazy rows need a key",
        )
    }

    /** Source with comments stripped, so prose can never satisfy a rule. */
    private fun source(relativePath: String): String = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/$relativePath",
    ).readText().stripComments()

    /**
     * The text of one top-level declaration: from its signature to the start of
     * the next one. Delimiting by signature rather than brace matching keeps
     * this honest around braces inside string templates.
     */
    private fun String.declarationBody(name: String): String {
        val declarations = TOP_LEVEL_FUN.findAll(this).toList()
        val index = declarations.indexOfFirst { it.groupValues[1] == name }
        require(index >= 0) { "no top-level fun named $name" }
        val start = declarations[index].range.first
        val end = declarations.getOrNull(index + 1)?.range?.first ?: length
        return substring(start, end)
    }

    /**
     * The argument list of the first [call] in this text — from its open paren
     * to the matching close paren, nested parens included. Trailing lambdas sit
     * outside the parens and are deliberately excluded: the wiring under test
     * is always a named argument.
     */
    private fun String.argumentsOf(call: String): String {
        val open = indexOf(call).also {
            require(it >= 0) { "no call to $call" }
        } + call.length - 1
        var depth = 0
        for (index in open until length) {
            when (this[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return substring(open + 1, index)
            }
        }
        error("unbalanced parentheses in call to $call")
    }

    private fun String.stripComments(): String = replace(BLOCK_COMMENT, "")
        .lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*")
        }
        .joinToString("\n")

    private fun String.containsLoosely(needle: String): Boolean =
        collapseWhitespace().contains(needle.collapseWhitespace())

    private fun String.countLoosely(needle: String): Int =
        collapseWhitespace().split(needle.collapseWhitespace()).size - 1

    private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        // Column-zero anchored, so indented members and local funs are not
        // mistaken for top-level ones; the modifier list is open-ended so a
        // declaration does not silently drop out of scoping when someone marks
        // it `inline` or `suspend`.
        val TOP_LEVEL_FUN = Regex(
            "(?m)^(?:(?:private|internal|public|inline|suspend|operator|tailrec|infix)\\s+)*fun\\s+(\\w+)",
        )
    }
}
