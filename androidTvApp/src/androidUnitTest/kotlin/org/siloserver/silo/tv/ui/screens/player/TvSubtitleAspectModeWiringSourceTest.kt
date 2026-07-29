package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvSubtitleAspectModeWiringSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidTvApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private fun playerViewUpdateBlock(source: String): String {
        val factoryAnchor = ") as PlayerView).apply {"
        val updateAnchor = "update = { view ->"
        val endAnchor = "if (!isInPictureInPictureMode"
        val factoryIndex = source.indexOf(factoryAnchor)
        require(factoryIndex >= 0) { "PlayerView factory anchor is missing" }
        val androidViewIndex = source.lastIndexOf("AndroidView(", factoryIndex)
        require(androidViewIndex >= 0) { "Enclosing AndroidView is missing" }
        val updateIndex = source.indexOf(updateAnchor, factoryIndex)
        require(updateIndex > factoryIndex) { "PlayerView update lambda is missing or misordered" }
        val endIndex = source.indexOf(endAnchor, updateIndex)
        require(endIndex > updateIndex) { "PlayerView update lambda terminator is missing or misordered" }
        return source.substring(updateIndex, endIndex)
    }

    @Test
    fun playerViewReconcilesSubtitlesAfterFillModeUpdate() {
        val source = source(
            "org/siloserver/silo/tv/ui/screens/player/TvPlayerScreen.kt"
        )
        val update = playerViewUpdateBlock(source)

        val aspectCall = "applyPlayerViewVideoFillMode(view, state.videoFillMode)"
        val subtitleCall = "subtitleManager.syncSubtitleVideoBounds(view)"
        assertTrue(update.contains(aspectCall))
        assertTrue(update.contains(subtitleCall))
        assertTrue(update.indexOf(aspectCall) < update.indexOf(subtitleCall))
    }
}
