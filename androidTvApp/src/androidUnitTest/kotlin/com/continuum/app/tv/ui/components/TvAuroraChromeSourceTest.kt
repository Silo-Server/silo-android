package com.continuum.app.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvAuroraChromeSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/components/TvAuroraChrome.kt",
    ).readText()

    @Test
    fun ghostButtonAnimatesTextColorWithFocusedBackground() {
        assertTrue(source.contains("val content by animateColorAsState("))
        assertTrue(source.contains("Text(text = label, color = content"))
    }
}
