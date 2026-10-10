package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TvHudPickerFocusWiringSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt",
    ).readText()
    private val pickerDialog = composableBody(
        start = "internal fun HudPickerDialog(",
        end = "@Composable\nprivate fun HudPickerOptionRow(",
    )
    private val pickerOptionRow = composableBody(
        start = "private fun HudPickerOptionRow(",
        end = "private fun formatTime",
    )

    @Test
    fun focusedPickerRowsBringTheirOwnFocusedRowIntoView() {
        assertContains(pickerOptionRow, "val bringIntoViewRequester = remember { BringIntoViewRequester() }")
        assertContains(pickerOptionRow, ".bringIntoViewRequester(bringIntoViewRequester)")

        val focusHandler = pickerOptionRow.substringAfter(".onFocusChanged { state ->")
            .substringBefore(".clickable(")
        assertContains(focusHandler, "if (state.isFocused)")

        val focusedBranch = focusHandler.substringAfter("if (state.isFocused)")
        assertContains(focusedBranch, "onFocused()")
        assertContains(focusedBranch, "scope.launch { bringIntoViewRequester.bringIntoView() }")
    }

    @Test
    fun pickerKeepsTheEagerFocusGraph() {
        assertContains(pickerDialog, "Column(")
        assertContains(pickerDialog, ".verticalScroll(")
        assertFalse(Regex("\\bLazyColumn\\s*\\(").containsMatchIn(pickerDialog.withoutComments()))
    }

    private fun composableBody(start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)

    private fun String.withoutComments(): String =
        replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("//.*$", setOf(RegexOption.MULTILINE)), "")
}
