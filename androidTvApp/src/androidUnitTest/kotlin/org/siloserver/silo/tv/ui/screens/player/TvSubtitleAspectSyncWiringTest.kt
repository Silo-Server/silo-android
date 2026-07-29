package org.siloserver.silo.tv.ui.screens.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvSubtitleAspectSyncWiringTest {
    @Test
    fun televisionSubtitleManagerUsesTelevisionPresentation() {
        val source = source("org/siloserver/silo/tv/di/AndroidTvModule.kt")

        assertTrue(source.contains("SubtitleManager(\n            libassBridge = get(),\n            presentation = AndroidSubtitlePresentation.Television,"))
    }

    private fun source(path: String): String {
        val moduleRelative = File("src/androidMain/kotlin/$path")
        val projectRelative = File("androidTvApp/src/androidMain/kotlin/$path")
        return (moduleRelative.takeIf(File::exists) ?: projectRelative).readText()
    }
}
