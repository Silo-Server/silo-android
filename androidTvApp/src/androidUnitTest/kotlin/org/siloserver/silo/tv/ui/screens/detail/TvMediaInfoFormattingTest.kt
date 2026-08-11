package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvMediaInfoFormattingTest {
    @Test
    fun subtitleSummaryUsesHumanLabelsInsteadOfRawFilenames() {
        val summary = tvMediaInfoSubtitleSummary(
            SubtitleTrack(
                index = 4,
                codec = "srt",
                language = "ar",
                title = "The Day of the Jackal (2024) - S01E01 [Bluray-1080p Remux 8-bit AVC DTS-HD MA 5.1]-SiCFoI.ar.sdh.srt",
                external = true,
            ),
        )

        assertEquals("Arabic", summary.primary)
        assertEquals("SRT · SDH · External", summary.secondary)
        assertFalse(summary.primary.contains("SiCFoI"))
        assertFalse(summary.secondary.orEmpty().contains("The Day of the Jackal"))
    }

    @Test
    fun audioSummarySplitsPrimaryAndAttributes() {
        val summary = tvMediaInfoAudioSummary(
            AudioTrack(
                index = 1,
                codec = "dts",
                channels = 6,
                channelLayout = null,
                language = "en",
                title = "DTS-HD MA 5.1",
                isDefault = true,
            ),
        )

        assertEquals("DTS-HD MA 5.1", summary.primary)
        assertEquals("English · 5.1 · DTS · Default", summary.secondary)
        assertTrue(summary.secondary.orEmpty().contains("5.1"))
    }

    @Test
    fun subtitleSummaryUsesTheSharedAccessibilityClassifier() {
        val closedCaptions = tvMediaInfoSubtitleSummary(
            SubtitleTrack(index = 1, codec = "srt", language = "en", title = "English CC"),
        )
        val unrelatedHearingText = tvMediaInfoSubtitleSummary(
            SubtitleTrack(index = 2, codec = "srt", language = "en", title = "Hearing Aid Commentary"),
        )

        assertTrue(closedCaptions.secondary.orEmpty().contains("SDH"))
        assertFalse(unrelatedHearingText.secondary.orEmpty().contains("SDH"))
    }
}
