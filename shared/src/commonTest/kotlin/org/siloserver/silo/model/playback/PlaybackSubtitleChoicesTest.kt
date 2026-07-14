package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSubtitleChoicesTest {
    @Test
    fun catalogAlternativesSurviveWhenPlanSelectsOnlyOneArtifact() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 3, codec = "srt", language = "en", title = "English"),
                SubtitleTrack(index = 7, codec = "ass", language = "ja", title = "Signs"),
            ),
            plannedTracks = listOf(
                PlayerSubtitleInfo(index = 7, source = "server_artifact", url = "/planned/signs.ass"),
            ),
        )

        assertEquals(listOf(3, 7), choices.map(PlayerSubtitleInfo::index))
        assertEquals("", choices[0].url)
        assertEquals("/planned/signs.ass", choices[1].url)
        assertEquals("Signs", choices[1].label)
        assertEquals("ja", choices[1].language)
    }

    @Test
    fun embeddedBitmapChoiceUsesTheMediaContainerInsteadOfASidecarUrl() {
        val choices = buildPlaybackSubtitleChoices(
            catalogTracks = listOf(
                SubtitleTrack(index = 5, codec = "hdmv_pgs_subtitle", title = "English PGS"),
            ),
            plannedTracks = emptyList(),
        )

        assertEquals("", choices.single().url)
        assertEquals("embedded", choices.single().source)
    }
}
