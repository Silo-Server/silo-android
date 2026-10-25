package org.siloserver.silo.android.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.SubtitleTrack

class SiloCastRokuTracksTest {
    // Catalog order mixes embedded and external tracks; the server's combined
    // order puts the externals first.
    private val version = FileVersion(
        fileId = 7,
        subtitleTracks = listOf(
            SubtitleTrack(index = 2, language = "eng"),
            SubtitleTrack(index = 0, language = "fre", external = true),
            SubtitleTrack(index = 3, language = "spa"),
            SubtitleTrack(index = 1, language = "ger", external = true),
        ),
    )
    private val other = FileVersion(fileId = 8, subtitleTracks = listOf(SubtitleTrack(index = 0, language = "eng")))

    private fun launch(subtitle: Int?, fileId: Int? = 7, audio: Int? = 1) = SiloCastPlaybackRequest(
        contentId = "movie-1",
        fileId = fileId,
        audioTrackIndex = audio,
        subtitleTrackIndex = subtitle,
        startFromBeginning = true,
    )

    @Test
    fun sendsTheCombinedSubtitleOrdinalOfTheLaunchedVersion() {
        val versions = listOf(other, version)
        assertEquals(listOf(2, 0, 3, 1), (0..3).map { SiloCastRokuTracks.forRoku(launch(it), versions).subtitleTrackIndex })
        // The audio ordinal is the picker's position already.
        assertEquals(1, SiloCastRokuTracks.forRoku(launch(1), versions).audioTrackIndex)
    }

    @Test
    fun keepsOffAndNoChoice() {
        assertEquals(-1, SiloCastRokuTracks.forRoku(launch(-1), listOf(version)).subtitleTrackIndex)
        assertNull(SiloCastRokuTracks.forRoku(launch(null), listOf(version)).subtitleTrackIndex)
    }

    @Test
    fun usesTheOnlyVersionWithoutAFileIdAndDropsWhatItCannotMap() {
        assertEquals(2, SiloCastRokuTracks.forRoku(launch(0, fileId = null), listOf(version)).subtitleTrackIndex)
        assertNull(SiloCastRokuTracks.forRoku(launch(0, fileId = null), listOf(other, version)).subtitleTrackIndex)
        assertNull(SiloCastRokuTracks.forRoku(launch(9), listOf(version)).subtitleTrackIndex)
        assertNull(SiloCastRokuTracks.forRoku(launch(0), emptyList()).subtitleTrackIndex)
    }
}
