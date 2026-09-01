package org.siloserver.silo.tv.ui.screens.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection

class TvSeriesEpisodePlaybackLaunchTest {
    @Test
    fun `quick play never carries a previous episode selection`() {
        val previous = EpisodeListItem(
            contentId = "previous",
            seasonNumber = 1,
            episodeNumber = 1,
        )
        val target = EpisodeListItem(
            contentId = "target",
            seasonNumber = 1,
            episodeNumber = 2,
            userData = LeafItemUserData(
                isInProgress = true,
                positionSeconds = 120.0,
                durationSeconds = 1_800.0,
            ),
        )
        val state = TvItemDetailUiState(
            nextUpEpisode = previous,
            nextUpPlaybackDetail = ItemDetail(
                contentId = previous.contentId,
                type = "episode",
                title = "Previous",
                versions = listOf(FileVersion(fileId = 7, resolution = "2160p")),
            ),
            selectedNextUpFileId = 7,
            selectedNextUpAudioIndex = 2,
            nextUpAudioPickedThisSession = true,
            selectedNextUpSubtitleIndex = -1,
        )

        val launch = seriesEpisodePlaybackLaunch(state, target)

        assertNull(launch.fileId)
        assertNull(launch.audioTrackIndex)
        assertFalse(launch.audioPickedThisSession)
        assertNull(launch.subtitleSelection)
        assertEquals(120.0, launch.resumePositionSeconds)
    }

    @Test
    fun `quick play honors selections belonging to the focused episode`() {
        val episode = EpisodeListItem(
            contentId = "target",
            seasonNumber = 3,
            episodeNumber = 1,
        )
        val state = TvItemDetailUiState(
            nextUpEpisode = episode,
            nextUpPlaybackDetail = ItemDetail(
                contentId = episode.contentId,
                type = "episode",
                title = "Target",
                versions = listOf(FileVersion(fileId = 11, resolution = "1080p")),
            ),
            selectedNextUpFileId = 11,
            selectedNextUpAudioIndex = 0,
            nextUpAudioPickedThisSession = true,
            selectedNextUpSubtitleIndex = -1,
        )

        val launch = seriesEpisodePlaybackLaunch(state, episode)

        assertEquals(11, launch.fileId)
        assertEquals(0, launch.audioTrackIndex)
        assertEquals(true, launch.audioPickedThisSession)
        assertEquals(TvSubtitleLaunchSelection(-1, autoResolved = false), launch.subtitleSelection)
    }
}
