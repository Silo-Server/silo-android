package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvPlayerUsabilityTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()
    private val playerLayout = File(
        "src/androidMain/res/layout/tv_player_view.xml",
    ).takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun embeddedPlayerViewDoesNotStealRemoteFocusFromComposeControls() {
        assertTrue(source.contains("isFocusable = false"))
        assertTrue(source.contains("isFocusableInTouchMode = false"))
        assertTrue(source.contains("descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS"))
    }

    @Test
    fun playerUsesSurfaceViewSoHdrFramesReachTheDisplay() {
        assertTrue(source.contains("R.layout.tv_player_view"))
        assertTrue(playerLayout.contains("app:surface_type=\"surface_view\""))
    }
}
