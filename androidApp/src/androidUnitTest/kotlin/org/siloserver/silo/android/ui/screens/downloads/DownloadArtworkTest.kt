package org.siloserver.silo.android.ui.screens.downloads

import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.repository.port.LocalContentState
import org.siloserver.silo.repository.port.LocalPlaybackProgress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadArtworkTest {
    private val prefs = EpisodeSpoilerPrefs(hideImages = true)
    private val episode = DownloadItem(
        id = "download", contentId = "episode", title = "Episode", posterUrl = "still",
        mediaType = DownloadMediaType.TvShow, posterIsEpisodeStill = true,
    )

    @Test
    fun unwatchedStillsAndLegacyUnknownArtAreProtected() {
        assertTrue(downloadItemHidesArtwork(episode, prefs))
        assertTrue(downloadItemHidesArtwork(episode.copy(posterIsEpisodeStill = null), prefs))
        assertFalse(downloadItemHidesArtwork(episode.copy(posterIsEpisodeStill = false), prefs))
        assertFalse(downloadItemHidesArtwork(episode, EpisodeSpoilerPrefs.NONE))
        assertFalse(downloadItemHidesArtwork(episode.copy(mediaType = DownloadMediaType.Movie), prefs))
    }

    @Test
    fun watchedAndStartedEpisodeStillsRemainVisible() {
        for (state in listOf(
            LeafItemUserData(played = true),
            LeafItemUserData(isInProgress = true),
            LeafItemUserData(positionSeconds = 1.0),
        )) {
            assertFalse(downloadItemHidesArtwork(episode.copy(episodeUserData = state), prefs))
        }
    }

    @Test
    fun markingUnwatchedClearsTheSavedProgressUntilPlaybackStartsAgain() {
        val saved = LeafItemUserData(played = true, isInProgress = true, positionSeconds = 30.0)
        val reset = overlayDownloadEpisodeUserData(saved, LocalContentState(watched = false, favorite = null), null)
        assertTrue(downloadItemHidesArtwork(episode.copy(episodeUserData = reset), prefs))
        val resumed = overlayDownloadEpisodeUserData(saved,
            LocalContentState(watched = false, favorite = null), LocalPlaybackProgress(1, 5.0, 60.0))
        assertFalse(downloadItemHidesArtwork(episode.copy(episodeUserData = resumed), prefs))
    }

    @Test
    fun currentServerWatchStateReplacesSavedAndAcknowledgedLocalState() {
        val watched = LeafItemUserData(played = true, isInProgress = true, positionSeconds = 30.0)
        val unwatched = LeafItemUserData(played = false, isInProgress = false, positionSeconds = 0.0)
        val reset = resolveDownloadEpisodeUserData(watched, unwatched,
            LocalContentState(watched = true, favorite = null), LocalPlaybackProgress(1, 30.0, 60.0), false)
        assertTrue(downloadItemHidesArtwork(episode.copy(episodeUserData = reset), prefs))
        val remoteWatched = resolveDownloadEpisodeUserData(unwatched, watched, null, null, false)
        assertFalse(downloadItemHidesArtwork(episode.copy(episodeUserData = remoteWatched), prefs))
        val localReset = resolveDownloadEpisodeUserData(watched, watched,
            LocalContentState(watched = false, favorite = null), null, true)
        assertTrue(downloadItemHidesArtwork(episode.copy(episodeUserData = localReset), prefs))
    }

    @Test
    fun groupedHeadersProtectTheEpisodeWhosePosterTheyUse() {
        val season = DownloadEntry.Season(
            "series", "Series", 1, listOf(DownloadEntry.Single(episode)), "still", null,
        )
        val series = DownloadEntry.Series("series", "Series", listOf(season), "still", null)
        assertTrue(downloadEntryHidesArtwork(season, prefs))
        assertTrue(downloadEntryHidesArtwork(series, prefs))
        assertFalse(downloadEntryHidesArtwork(series.copy(posterUrl = "series-art"), prefs))
        val safeFirst = season.copy(episodes = listOf(
            DownloadEntry.Single(episode.copy(posterIsEpisodeStill = false)),
            DownloadEntry.Single(episode.copy(id = "legacy", posterIsEpisodeStill = null)),
        ))
        assertFalse(downloadEntryHidesArtwork(safeFirst, prefs))
    }
}
