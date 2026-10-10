package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.watchtogether.Suggestion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartySpoilersTest {
    private val enabled = EpisodeSpoilerPrefs(hideImages = true, hideOverviews = true)
    private val episode = ItemDetail(contentId = "episode-1", type = "episode", title = "Pilot", backdropUrl = "still.jpg")

    @Test
    fun anUnstartedEpisodeHidesItsArtworkAndOverview() {
        val spoilers = WatchPartySpoilers.of(episode)

        assertTrue(spoilers.hidesPoster(enabled))
        assertTrue(spoilers.hidesBackdrop(enabled, episode.backdropUrl))
        assertTrue(spoilers.hidesOverview(enabled))
        assertFalse(spoilers.hidesPoster(EpisodeSpoilerPrefs.NONE))
        assertFalse(spoilers.hidesOverview(EpisodeSpoilerPrefs.NONE))
    }

    @Test
    fun aStartedEpisodeStaysVisible() {
        listOf(
            LeafItemUserData(played = true),
            LeafItemUserData(isInProgress = true),
            LeafItemUserData(positionSeconds = 30.0),
        ).forEach { state ->
            val spoilers = WatchPartySpoilers.of(episode.copy(userData = state))
            assertFalse(spoilers.hidesPoster(enabled))
            assertFalse(spoilers.hidesBackdrop(enabled, episode.backdropUrl))
            assertFalse(spoilers.hidesOverview(enabled))
        }
    }

    @Test
    fun eachArtworkSlotUsesItsOwnProvenance() {
        val spoilers = WatchPartySpoilers.of(episode.copy(posterIsEpisodeStill = false, backdropIsEpisodeStill = true))

        assertFalse(spoilers.hidesPoster(enabled))
        assertTrue(spoilers.hidesBackdrop(enabled, "still.jpg"))
        // Without a backdrop the slot shows the poster, series art here.
        assertFalse(spoilers.hidesBackdrop(enabled, null))
    }

    @Test
    fun aBrowseItemStartedByThisProfileStaysVisible() {
        val item = BrowseItem(contentId = "episode-1", type = "episode", title = "Pilot")

        assertTrue(WatchPartySpoilers.of(item).hidesPoster(enabled))
        assertFalse(WatchPartySpoilers.of(item.copy(positionSeconds = 12.0)).hidesPoster(enabled))
        assertFalse(WatchPartySpoilers.of(item.copy(userState = MediaItemUserState(played = true))).hidesPoster(enabled))
    }

    @Test
    fun onlyEpisodesAreEverProtected() {
        val movie = ItemDetail(contentId = "movie-1", type = "movie", title = "Film")

        assertFalse(WatchPartySpoilers.of(movie).hidesPoster(enabled))
        assertFalse(WatchPartySpoilers.of(movie).hidesOverview(enabled))
        assertTrue(WatchPartySpoilers.of(suggestion("episode")).hidesPoster(enabled))
        assertFalse(WatchPartySpoilers.of(suggestion("movie")).hidesPoster(enabled))
    }

    @Test
    fun anItemWithoutKnownStateIsProtectedByType() {
        assertTrue(WatchPartyItem("episode-1", "episode", "Pilot").spoilers.hidesPoster(enabled))
        assertFalse(WatchPartyItem("movie-1", "movie", "Film").spoilers.hidesPoster(enabled))
    }

    private fun suggestion(type: String) = Suggestion(
        id = type, roomId = "room", contentId = type, contentType = type, title = "Pick", createdAt = "2026-10-10T00:00:00Z",
    )
}
