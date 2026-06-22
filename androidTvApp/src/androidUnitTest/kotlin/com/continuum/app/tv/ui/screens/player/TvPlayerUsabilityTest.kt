package com.continuum.app.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvPlayerUsabilityTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/player/TvPlayerScreen.kt",
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
    fun playerUsesSurfaceViewSoHdrAndDolbyVisionCanEngage() {
        // HDR10/HDR10+/HLG metadata and Dolby Vision tunneling can only be
        // carried by a SurfaceView; a TextureView silently forces SDR. Compose
        // controls/HUD still render above the video because they are Compose
        // overlays in a sibling layer, independent of the surface type.
        assertTrue(source.contains("R.layout.tv_player_view"))
        assertTrue(playerLayout.contains("app:surface_type=\"surface_view\""))
    }
}
