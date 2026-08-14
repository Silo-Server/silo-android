package org.siloserver.silo.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A single-line Compose text field consumes vertical D-pad for cursor moves it
 * cannot make, so focus that lands in one never leaves — on a remote there is
 * no tap to break out with, and the screen becomes uncompletable.
 * `Modifier.tvShowImeOnSelect()` is what routes those keys back to focus
 * search, so every field in the auth flow needs it, not just the one its
 * screen focuses first.
 *
 * This is what shipped broken twice: the setup and signup screens carried the
 * modifier on USERNAME only, which stranded the remote on the next field down
 * (verified on an emulator 2026-08-14 — the first-run admin account could not
 * be created at all).
 *
 * Being a source check, it pins the modifier's presence per field, not the
 * runtime behavior — the emulator walk covers that.
 */
class TvAuthFieldEscapePolicyTest {

    @Test
    fun `every auth-flow text field routes the d-pad back out`() {
        val authScreens = File("src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertTrue(authScreens.isNotEmpty(), "auth screen sources not found — did the package move?")

        val offenders = authScreens.flatMap { file ->
            fieldDeclarations(file.readText())
                .filterNot { it.contains("tvShowImeOnSelect()") }
                .map { "${file.name}: ${fieldLabel(it)}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "these auth text fields trap D-pad focus (add Modifier.tvShowImeOnSelect()): $offenders",
        )
    }

    /**
     * Slices the source at each `OutlinedTextField(` and returns the balanced
     * argument list, so a field's modifier chain is checked against that field
     * rather than against anything else in the file.
     */
    private fun fieldDeclarations(source: String): List<String> =
        Regex("""OutlinedTextField\(""").findAll(source).map { match ->
            var depth = 0
            var index = match.range.last
            while (index < source.length) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return@map source.substring(match.range.last, index + 1)
                    }
                }
                index++
            }
            source.substring(match.range.last)
        }.toList()

    /** Best-effort identifier for the failure message. */
    private fun fieldLabel(declaration: String): String =
        Regex("""value\s*=\s*([\w.]+)""").find(declaration)?.groupValues?.get(1)
            ?: declaration.take(40).replace('\n', ' ')
}
