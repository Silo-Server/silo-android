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
    fun playActionKeepsAutoUnpinnedUnlessTrackOverrideIsSelected() {
        assertTrue(source.contains("val selectedFileId = selectorSelectedFileId ?: selectorVersions.firstOrNull()?.fileId"))
        assertTrue(source.contains("val hasTrackOverride = selectorAudioIndex != null || selectorSubtitleIndex != null"))
        assertTrue(source.contains("val playFileId = selectorSelectedFileId ?: selectedFileId.takeIf { hasTrackOverride }"))
        assertTrue(
            source.contains("playContentId, playFileId,"),
            "Track overrides should pin the displayed file id so selected track indexes match the displayed version.",
        )
        assertFalse(
            source.contains("playContentId, selectedFileId,"),
            "The display fallback must not be sent directly; Auto should stay unpinned unless a track override exists.",
        )
    }
}
