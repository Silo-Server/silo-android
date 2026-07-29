package org.siloserver.silo.android.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SubtitleAspectModeWiringSourceTest {
    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }

    private fun playerViewUpdateBlock(source: String): String {
        val factoryAnchor = "PlayerView(ctx).apply {"
        val updateAnchor = "update = { view ->"
        val endAnchor = "modifier = Modifier"
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
    fun playerViewReconcilesSubtitlesAfterResizeModeUpdate() {
        val source = source(
            "org/siloserver/silo/android/ui/screens/player/PlayerScreen.kt"
        )
        val update = playerViewUpdateBlock(source)

        assertTrue(update.contains("view.resizeMode = resizeMode"))
        assertTrue(update.contains("subtitleManager.syncSubtitleVideoBounds(view)"))
        assertTrue(
            update.indexOf("view.resizeMode = resizeMode") <
                update.indexOf("subtitleManager.syncSubtitleVideoBounds(view)")
        )
    }
}
