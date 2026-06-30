package com.continuum.app.tv.ui.screens.servers

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvServerListFocusSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/servers/TvServerListScreen.kt",
    ).readText()

    @Test
    fun serverRowsAnchorFocusSafelyWhenListMaterializes() {
        assertTrue(source.contains("if (state.servers.isNotEmpty()) runCatching { firstFocus.requestFocus() }"))
    }
}
