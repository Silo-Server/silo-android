package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleTrackSelectionTest {

    private fun track(index: Int, source: String? = null) = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "srt",
        label = "T$index",
        source = source,
        forced = null,
        url = "/stream/s/subtitles/$index.vtt",
    )

    private fun dl(id: Int) = DownloadedSubtitle(
        id = id,
        mediaFileId = 7,
        provider = "opensubtitles",
        language = "en",
        format = "srt",
        releaseName = "Rel.$id",
    )

    @Test
    fun findsDownloadedTrackAfterExistingTracks() {
        // merged = 2 session tracks + 2 downloaded appended in listing order
        val merged = listOf(track(0), track(1), track(2, "downloaded"), track(3, "downloaded"))
        val downloaded = listOf(dl(11), dl(22))
        assertEquals(2, downloadedTrackIndex(merged, downloaded, subtitleId = 11))
        assertEquals(3, downloadedTrackIndex(merged, downloaded, subtitleId = 22))
    }

    @Test
    fun worksWhenAllTracksAreDownloaded() {
        val merged = listOf(track(0, "downloaded"), track(1, "downloaded"))
        val downloaded = listOf(dl(5), dl(6))
        assertEquals(0, downloadedTrackIndex(merged, downloaded, subtitleId = 5))
    }

    @Test
    fun unknownIdReturnsNull() {
        val merged = listOf(track(0), track(1, "downloaded"))
        val downloaded = listOf(dl(9))
        assertNull(downloadedTrackIndex(merged, downloaded, subtitleId = 999))
    }

    @Test
    fun emptyDownloadListReturnsNull() {
        assertNull(downloadedTrackIndex(listOf(track(0)), emptyList(), subtitleId = 1))
    }

    @Test
    fun replanSelectionUsesStableServerTrackIndex() {
        val tracks = listOf(track(3), track(7))

        assertEquals(7, selectedServerSubtitleTrackIndex(1, tracks))
        assertEquals(-1, selectedServerSubtitleTrackIndex(-1, tracks))
        assertNull(selectedServerSubtitleTrackIndex(2, tracks))
    }

    @Test
    fun missingSessionRenewalPreservesAuthoritativeDownloadedInventorySelection() {
        val mounted = listOf(
            track(0),
            track(1),
            track(4, "downloaded"),
        )
        assertEquals(
            2,
            authoritativePlaybackSubtitleOrdinal(
                serverIndex = 4,
                playbackTracks = mounted,
            ),
        )
    }
}
