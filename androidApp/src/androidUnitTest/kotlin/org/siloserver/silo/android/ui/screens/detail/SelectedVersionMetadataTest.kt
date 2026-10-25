package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.catalog.VideoTrack
import org.siloserver.silo.model.catalog.selectedMediaRuntimeMinutes
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectedVersionMetadataTest {
    @Test
    fun phoneMetadataAndQualityLabelsFollowOnlySelectedVersion() {
        val theatrical = FileVersion(
            fileId = 1,
            duration = 13_740.0,
            resolution = "1080p",
            codecVideo = "h264",
            codecAudio = "aac",
            audioTracks = listOf(AudioTrack(codec = "aac", channels = 2, isDefault = true)),
        )
        val directorsCut = FileVersion(
            fileId = 2,
            duration = 15_060.0,
            resolution = "2160p",
            codecVideo = "hevc",
            codecAudio = "truehd",
            hdr = true,
            videoTracks = listOf(VideoTrack(codec = "hevc", dolbyVision = "Profile 8", hdr = true)),
            audioTracks = listOf(
                AudioTrack(codec = "truehd", channelLayout = "7.1", channels = 8, isDefault = true),
            ),
            subtitleTracks = listOf(SubtitleTrack(language = "en")),
        )
        val detail = ItemDetail(
            contentId = "movie",
            type = "movie",
            title = "Once Upon a Time in America",
            runtime = 229,
            versions = listOf(theatrical, directorsCut),
        )

        val theatricalRuntime = selectedMediaRuntimeMinutes(detail, theatrical)
        assertEquals(listOf("3h 49m"), HeroMetadata.movieFactsLine(detail, theatricalRuntime))
        assertEquals("1080p · H.264 · AAC Stereo", formatVersionMenuLabel(theatrical))
        assertEquals("Auto - 2ch", formatAudioValueLabel(theatrical.audioTracks.orEmpty(), null))
        assertEquals("Auto", formatSubtitleValueLabel(theatrical.subtitleTracks.orEmpty(), null))

        val directorsRuntime = selectedMediaRuntimeMinutes(detail, directorsCut)
        assertEquals(listOf("4h 11m"), HeroMetadata.movieFactsLine(detail, directorsRuntime))
        assertEquals("4K · HDR · HEVC · TrueHD 7.1", formatVersionMenuLabel(directorsCut))
        assertEquals("Auto - 7.1", formatAudioValueLabel(directorsCut.audioTracks.orEmpty(), null))
        assertEquals("Auto - English", formatSubtitleValueLabel(directorsCut.subtitleTracks.orEmpty(), null))
    }

    @Test
    fun trackHeadlineKeepsWhatTheTitleAddsAndDropsCodecNames() {
        val cases = listOf(
            Triple("eng", "AAC (Advanced Audio Coding)", "English"),
            Triple("eng", "SUBRIP", "English"),
            Triple("eng", "Director's Commentary (AC3)", "English \u00B7 Director's Commentary"),
            Triple("eng", "English SDH (SRT)", "English \u00B7 SDH"),
            Triple("eng", "Signs & Songs (ASS)", "English \u00B7 Signs & Songs"),
            Triple("jpn", "E-AC-3 5.1 / Stereo Mix", "Japanese \u00B7 5.1 / Stereo Mix"),
            Triple("spa", null, "Spanish"),
            Triple("und", "Commentary", "Commentary"),
        )
        cases.forEach { (language, title, expected) ->
            assertEquals(expected, trackHeadline(language, title), "language=$language title=$title")
        }
    }
}
