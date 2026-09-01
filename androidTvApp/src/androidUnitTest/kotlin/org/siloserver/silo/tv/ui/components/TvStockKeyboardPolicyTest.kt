package org.siloserver.silo.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvStockKeyboardPolicyTest {
    @Test
    fun customTvKeyboardImplementationIsRemovedFromProductionSources() {
        val removedFiles = listOf(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvAnsiKeyboard.kt",
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvCredentialKeyboard.kt",
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/auth/TvServerUrlKeyboard.kt",
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchKeyboard.kt",
        )

        removedFiles.forEach { path ->
            assertFalse(File(path).exists(), "$path should not remain as a production text-entry path")
        }
    }

    @Test
    fun everySurfaceThatRaisesTheStockKeyboardAlsoDismissesIt() {
        // Android TV leaves the IME up when the surface that raised it goes
        // away, so it floats over the next screen and keeps eating the D-pad.
        // Two implementations copied the focus-and-show half of the policy and
        // omitted the disposal half.
        //
        // Being a source check, this catches the copy — a file that takes the
        // keyboard controller and shows it — not every conceivable way of
        // raising the IME. It is also per file rather than per composable, so
        // one helper call blesses everything in that file.
        val sources = File("src/androidMain/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }

        val showsTheKeyboard = Regex("""\w+\??\.show\(\)""")
        val offenders = sources
            .map { it to it.readText() }
            .filter { (_, text) ->
                text.contains("LocalSoftwareKeyboardController") &&
                    showsTheKeyboard.containsMatchIn(text)
            }
            .filterNot { (_, text) -> text.contains("TvHideStockImeOnDispose()") }
            .map { (file, _) -> file.path }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "these raise the stock TV keyboard without dismissing it on disposal: $offenders",
        )
    }

    @Test
    fun escapeUsesNormalBackDispatchSoPopupsCloseBeforeThePage() {
        val source = File("src/androidMain/kotlin/org/siloserver/silo/tv/MainTvActivity.kt").readText()
        val dispatch = source
            .substringAfter("override fun dispatchKeyEvent(event: KeyEvent): Boolean")
            .substringBefore("private fun handleIntent")

        assertTrue(dispatch.contains("KeyEvent.KEYCODE_BACK"))
        assertTrue(dispatch.contains("super.dispatchKeyEvent(translatedEvent)"))
        assertFalse(dispatch.contains("onBackPressedDispatcher.onBackPressed()"))
    }
}
