package com.continuum.app.tv.ui.screens.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDetailPlaybackSelectionSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/tv/ui/screens/detail/TvItemDetailScreen.kt",
    ).readText()

    @Test
    fun playActionOnlyPinsFileIdAfterExplicitVersionSelection() {
        assertTrue(source.contains("val selectedFileId = selectorSelectedFileId ?: selectorVersions.firstOrNull()?.fileId"))
        assertTrue(
            source.contains("playContentId, selectorSelectedFileId,"),
            "Play should pass the nullable explicit selector id so Auto quality can choose the best version.",
        )
        assertFalse(
            source.contains("playContentId, selectedFileId,"),
            "The display fallback must not be sent as preferredFileId; that pins playback to the server's first file.",
        )
    }
}
