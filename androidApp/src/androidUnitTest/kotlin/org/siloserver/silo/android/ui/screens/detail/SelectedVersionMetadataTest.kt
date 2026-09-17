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
        assertEquals("Auto - EN", formatSubtitleValueLabel(directorsCut.subtitleTracks.orEmpty(), null))
    }
}
