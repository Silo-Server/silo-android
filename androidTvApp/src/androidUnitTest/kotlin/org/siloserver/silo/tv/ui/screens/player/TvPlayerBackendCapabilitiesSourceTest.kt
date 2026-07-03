package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertTrue

class TvPlayerBackendCapabilitiesSourceTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()
    private val hudSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerHud.kt",
    ).readText()
    private val screenSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt",
    ).readText()
    // PlayerStatsSnapshot lives in android-shared (shared with the phone
    // player's stats overlay); the TV VM feeds it via onBackendCapabilities.
    private val statsSource = java.io.File(
        "../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/player/PlayerStats.kt",
    ).readText()

    @Test
    fun backendCapabilitiesReachStatsHud() {
        assertTrue(viewModelSource.contains("import org.siloserver.silo.common.player.backend.VideoBackendCapabilities"))
        assertTrue(statsSource.contains("val backendKind: String? = null"))
        assertTrue(statsSource.contains("val backendDisplayName: String? = null"))
        assertTrue(statsSource.contains("val backendRoute: String? = null"))
        assertTrue(statsSource.contains("val subtitleRendering: String? = null"))
        assertTrue(statsSource.contains("val hardContainers: String? = null"))
        assertTrue(viewModelSource.contains("fun onBackendCapabilities(capabilities: VideoBackendCapabilities)"))
        assertTrue(hudSource.contains("backendDisplayName?.let { add(\"Backend\" to it) }"))
        assertTrue(hudSource.contains("backendRoute?.let { add(\"Route\" to it) }"))
        assertTrue(hudSource.contains("subtitleRendering?.let { add(\"Subtitles\" to it) }"))
        assertTrue(hudSource.contains("hardContainers?.let { add(\"Hard containers\" to it) }"))
        assertTrue(screenSource.contains("viewModel.onBackendCapabilities(backend.capabilities)"))
    }
}
