package org.siloserver.silo.model.settings

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.withWatched
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.section.SectionItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpisodeSpoilersTest {

    @Test
    fun browsePostersAndDetailHandoffsUseSelectedArtworkProvenance() {
        val prefs = EpisodeSpoilerPrefs(hideImages = true)
        val episode = BrowseItem("episode", "episode", "Episode", posterUrl = "still",
            posterIsEpisodeStill = true, backdropUrl = "series", backdropIsEpisodeStill = false)
        assertTrue(EpisodeSpoilers.hidesBrowseArtwork(episode, prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode, prefs, selectBackdrop = true))
        assertTrue(EpisodeSpoilers.hidesBrowseArtwork(episode.copy(backdropIsEpisodeStill = true), prefs, selectBackdrop = true))
        assertTrue(EpisodeSpoilers.hidesBrowseArtwork(episode.copy(posterIsEpisodeStill = null), prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode.copy(posterIsEpisodeStill = false), prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode, prefs, played = true))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode.copy(positionSeconds = 2.0), prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode.copy(type = "series"), prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(episode, EpisodeSpoilerPrefs.NONE))
    }

    @Test
    fun optimisticUnwatchedResetProtectsStartedBrowseArtwork() {
        val prefs = EpisodeSpoilerPrefs(hideImages = true)
        val original = BrowseItem("episode", "episode", "Episode", posterIsEpisodeStill = true,
            backdropUrl = "still", backdropIsEpisodeStill = true, positionSeconds = 20.0)
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(original, prefs))
        val reset = original.withWatched(false)
        assertTrue(EpisodeSpoilers.hidesBrowseArtwork(original, prefs, played = reset.userState?.played,
            positionSeconds = reset.positionSeconds))
        assertTrue(EpisodeSpoilers.hidesBrowseArtwork(original, prefs, played = reset.userState?.played,
            selectBackdrop = true, positionSeconds = reset.positionSeconds))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(original, prefs))
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(original.withWatched(true), prefs))
    }

    @Test
    fun browseWireProgressKeepsStartedEpisodeArtworkVisible() {
        val item = Json.decodeFromString<BrowseItem>("""{"content_id":"episode","type":"episode","title":"Episode","position_seconds":5,"poster_is_episode_still":true}""")
        assertFalse(EpisodeSpoilers.hidesBrowseArtwork(item, EpisodeSpoilerPrefs(hideImages = true)))
    }

    @Test
    fun missingWatchStateCountsAsUnwatched() {
        assertTrue(EpisodeSpoilers.isUnwatched(userData = null))
        assertTrue(EpisodeSpoilers.isUnwatched(LeafItemUserData()))
        assertTrue(EpisodeSpoilers.isUnwatched(LeafItemUserData(positionSeconds = 0.0)))
    }

    @Test
    fun playedOrStartedEpisodesAreWatched() {
        assertFalse(EpisodeSpoilers.isUnwatched(LeafItemUserData(played = true)))
        assertFalse(EpisodeSpoilers.isUnwatched(LeafItemUserData(isInProgress = true)))
        assertFalse(EpisodeSpoilers.isUnwatched(LeafItemUserData(positionSeconds = 12.0)))
        // A played episode whose position was reset stays revealed.
        assertFalse(
            EpisodeSpoilers.isUnwatched(LeafItemUserData(played = true, positionSeconds = 0.0)),
        )
    }

    @Test
    fun sectionItemsUsePlayedAndPosition() {
        assertTrue(EpisodeSpoilers.isUnwatched(sectionItem()))
        assertTrue(EpisodeSpoilers.isUnwatched(sectionItem(userState = MediaItemUserState())))
        assertFalse(EpisodeSpoilers.isUnwatched(sectionItem(positionSeconds = 30.0)))
        assertFalse(
            EpisodeSpoilers.isUnwatched(sectionItem(userState = MediaItemUserState(played = true))),
        )
    }

    @Test
    fun supportNeedsRevision16AndBatchedEffective() {
        fun caps(revision: Int, batched: Boolean = true, apiVersion: Int = 1) =
            SettingsContractCapabilities(
                apiVersion = apiVersion,
                manifestRevision = revision,
                supportsBatchedEffective = batched,
            )
        assertTrue(EpisodeSpoilers.isSupported(caps(16)))
        assertTrue(EpisodeSpoilers.isSupported(caps(17)))
        assertFalse(EpisodeSpoilers.isSupported(caps(15)))
        assertFalse(EpisodeSpoilers.isSupported(caps(16, batched = false)))
        assertFalse(EpisodeSpoilers.isSupported(caps(16, apiVersion = 2)))
    }

    @Test
    fun imageProvenanceFollowsFallbackAndPreservesSeriesArt() {
        val prefs = EpisodeSpoilerPrefs(hideImages = true)
        val still = sectionItem().copy(backdropUrl = "https://example.invalid/still", backdropIsEpisodeStill = true)
        assertTrue(prefs.hidesImage(EpisodeSpoilers.isUnwatched(still), EpisodeSpoilers.selectedImageIsStill(still)))
        val seriesArt = still.copy(backdropIsEpisodeStill = false)
        assertFalse(prefs.hidesImage(EpisodeSpoilers.isUnwatched(seriesArt), EpisodeSpoilers.selectedImageIsStill(seriesArt)))
        val fallback = still.copy(backdropUrl = "", posterUrl = "https://example.invalid/poster", posterIsEpisodeStill = false)
        assertFalse(prefs.hidesImage(true, EpisodeSpoilers.selectedImageIsStill(fallback)))
        assertTrue(prefs.hidesImage(true, null))
    }

    private fun sectionItem(
        userState: MediaItemUserState? = null,
        positionSeconds: Double? = null,
    ) = SectionItem(
        contentId = "ep-1",
        type = "episode",
        title = "Pilot",
        positionSeconds = positionSeconds,
        userState = userState,
    )
}
