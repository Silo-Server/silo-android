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
        assertTrue(
            source.contains("state.preferredQuality,"),
            "Auto display resolution must observe the same preferred quality used by playback startup.",
        )
        assertTrue(
            source.contains("preferredQuality = state.preferredQuality"),
            "Auto display resolution must pass the user's preferred quality into version selection.",
        )
        assertTrue(source.contains("val selectedFileId = selectedVersion?.fileId"))
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
