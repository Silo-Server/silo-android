package com.continuum.app.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAnsiKeyboardSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/components/TvAnsiKeyboard.kt",
    ).takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun ansiKeyboardUsesRealShiftedKeyPairs() {
        assertTrue(source.contains("insertKey(\"1\", shifted = \"!\")"))
        assertTrue(source.contains("insertKey(\"2\", shifted = \"@\")"))
        assertTrue(source.contains("insertKey(\",\", shifted = \"<\")"))
        assertTrue(source.contains("insertKey(\".\", shifted = \">\")"))
        assertTrue(source.contains("insertKey(\"/\", shifted = \"?\")"))
        assertTrue(source.contains("insertKey(\"[\", shifted = \"{\")"))
        assertTrue(source.contains("insertKey(\"]\", shifted = \"}\")"))
        assertTrue(source.contains("insertKey(\"\\\\\", shifted = \"|\")"))
    }

    @Test
    fun ansiKeyboardHasTvRemoteFirstRowShapeAndNoSymbolsLayerToggle() {
        assertTrue(source.contains("TvAnsiNumberRow"))
        assertTrue(source.contains("TvAnsiSymbolRow"))
        assertTrue(source.contains("TvAnsiQwertyRow"))
        assertTrue(source.contains("TvAnsiHomeRow"))
        assertTrue(source.contains("TvAnsiBottomLetterRow"))
        assertTrue(source.contains("buildTvAnsiShortcutRow(shortcuts)"))
        assertTrue(source.contains("private val TvAnsiKeyboardKeyHeight = 28.dp"))
        assertTrue(source.contains("private val TvAnsiKeyboardMaxWidth = 600.dp"))
        assertFalse(source.contains("Symbols"))
        assertFalse(source.contains("#+="))
    }

    @Test
    fun ansiKeyboardUsesOneShotShiftForRemoteTyping() {
        assertTrue(source.contains("if (shifted && key.type == TvAnsiKeyType.Insert) shifted = false"))
    }
}
