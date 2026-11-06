package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.player.AiSubtitleKind
import org.siloserver.silo.player.aiSubtitleKind
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleTrackDisplayLabelTest {

    @Test
    fun mobileTrackSheetUsesFriendlySubtitleLabels() {
        val label = subtitleTrackLabel(
            sub = PlayerSubtitleInfo(
                index = 0,
                language = "eng",
                codec = null,
                label = "SUBRIP",
                forced = null,
                url = "/stream/subtitles/0.srt",
            ),
            index = 0,
        )

        assertEquals("English • SRT", label)
    }

    @Test
    fun sharedFormatterMarksAiTranslations() {
        val label = formatSubtitleTrackDisplayLabel(
            rawLabel = "English → Dutch (AI) (translated)",
            language = "nl",
            codecOrMime = "srt",
            isForced = false,
            index = 1,
        )

        assertEquals("Dutch • AI Translation • SRT", label)
    }

    @Test
    fun sharedFormatterMarksAiTranscriptions() {
        val label = formatSubtitleTrackDisplayLabel(
            rawLabel = "English (AI transcribed) (transcribed)",
            language = "en",
            codecOrMime = "srt",
            isForced = false,
            index = 2,
        )

        assertEquals("English • AI Transcription • SRT", label)
    }

    @Test
    fun trackSheetChipsTellTranslationsFromTranscriptions() {
        fun chips(label: String, language: String) = subtitleTrackPresentation(
            PlayerSubtitleInfo(index = 3, language = language, codec = "srt", label = label, forced = null, url = "/s.vtt"),
            index = 3,
            strings = testTrackLabelStrings,
        ).chips

        assertEquals(listOf("AI Translation"), chips("English → German (AI) (translated)", "de"))
        assertEquals(listOf("AI Transcription"), chips("English (AI transcribed) (transcribed)", "en"))
        assertEquals(listOf("AI Transcription"), chips("English (AI transcribed)", "en"))
        assertEquals(emptyList(), chips("English (opensubtitles)", "en"))
    }

    @Test
    fun onlyTheServerAiMarkerMakesATranslation() {
        assertNull(aiSubtitleKind("AI.srt"))
        assertNull(aiSubtitleKind("Commentary AI"))
        assertEquals(AiSubtitleKind.TRANSLATION, aiSubtitleKind("English → German (AI)"))
    }

    @Test
    fun aiTracksWithoutALanguageKeepTheServerLabel() {
        fun title(label: String) = subtitleTrackPresentation(
            PlayerSubtitleInfo(index = 0, language = null, codec = "srt", label = label, forced = null, url = "/s.vtt"),
            index = 0,
            strings = testTrackLabelStrings,
        ).title

        assertEquals("English → German", title("English → German (AI) (translated)"))
        assertEquals("English", title("English (AI transcribed)"))
        assertEquals("English → Portuguese (Brazil)", title("English → Portuguese (Brazil) (AI) (translated)"))
    }
}

/** Track label words for tests that build presentations outside composition. */
internal val testTrackLabelStrings = TrackLabelStrings(
    audioNumbered = { "Audio $it" },
    subtitleNumbered = { "Subtitle $it" },
    default = "Default",
    forced = "Forced",
    sdh = "SDH",
    embedded = "Embedded",
    external = "External",
    downloaded = "Downloaded",
    server = "Server",
    mono = "Mono",
    stereo = "Stereo",
)
