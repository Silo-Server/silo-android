package org.siloserver.silo.overlays

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.catalog.withWatched
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.repository.port.LocalContentState
import org.siloserver.silo.viewmodel.applyLocalStateOverlay
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the status corner to web's `remainingEpisodes` in `CardWatchStatus.tsx`. */
class CardWatchStatusTest {

    @Test
    fun startedUnfinishedSeries_showsEpisodesLeft() {
        assertEquals(CardWatchStatus.EpisodesLeft(6), CardWatchStatus.of(MediaItemUserState(watchedCount = 4, unplayedCount = 6)))
    }

    @Test
    fun untouchedFinishedOrUnknown_showsNothing() {
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(null))
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(MediaItemUserState()))
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(MediaItemUserState(watchedCount = 0, unplayedCount = 10)))
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(MediaItemUserState(unplayedCount = 10)))
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(MediaItemUserState(watchedCount = 10, unplayedCount = 0)))
    }

    @Test
    fun watched_showsTheCheck_evenWithCounts() {
        assertEquals(CardWatchStatus.Watched, CardWatchStatus.of(MediaItemUserState(played = true)))
        assertEquals(CardWatchStatus.Watched, CardWatchStatus.of(MediaItemUserState(played = true, watchedCount = 4, unplayedCount = 6)))
    }

    private val userStateJson =
        """{"played":false,"is_favorite":false,"in_watchlist":true,"not_interested":false,"watched_count":2,"unplayed_count":73}"""

    @Test
    fun counts_surviveDecodingIntoCardModels() {
        val browse = SiloJson.decodeFromString(
            BrowseItem.serializer(),
            """{"content_id":"s1","type":"series","title":"Show","user_state":$userStateJson}""",
        )
        val section = SiloJson.decodeFromString(
            SectionItem.serializer(),
            """{"content_id":"s1","type":"series","title":"Show","user_state":$userStateJson}""",
        )
        val expected = MediaItemUserState(inWatchlist = true, watchedCount = 2, unplayedCount = 73)
        assertEquals(expected, browse.userState)
        assertEquals(expected, section.userState)
        assertEquals(CardWatchStatus.EpisodesLeft(73), CardWatchStatus.of(section.userState))
        // Absent counts (a movie, or a finished series) decode as null.
        val movie = SiloJson.decodeFromString(
            BrowseItem.serializer(),
            """{"content_id":"m1","type":"movie","title":"Film","user_state":{"played":true,"is_favorite":false,"in_watchlist":false,"not_interested":false}}""",
        )
        assertEquals(MediaItemUserState(played = true), movie.userState)
    }

    @Test
    fun optimisticWatchedChange_clearsStaleCounts() {
        val started = MediaItemUserState(watchedCount = 4, unplayedCount = 6)
        val item = BrowseItem(contentId = "s1", type = "series", title = "Show", userState = started)
        assertEquals(CardWatchStatus.Watched, CardWatchStatus.of(item.withWatched(true).userState))
        assertEquals(CardWatchStatus.None, CardWatchStatus.of(item.withWatched(false).userState))

        val section = ResolvedSection(
            id = "row",
            sectionType = "recently_added",
            title = "Row",
            items = listOf(SectionItem(contentId = "s1", type = "series", title = "Show", userState = started)),
        )
        fun overlaid(state: LocalContentState) =
            applyLocalStateOverlay(listOf(section), mapOf("s1" to state), emptyMap()).single().items.single().userState
        // An unacknowledged local change hides the counts; an acknowledged one leaves the server's.
        assertEquals(
            CardWatchStatus.None,
            CardWatchStatus.of(overlaid(LocalContentState(watched = false, favorite = null, watchedPending = true))),
        )
        assertEquals(
            CardWatchStatus.EpisodesLeft(6),
            CardWatchStatus.of(overlaid(LocalContentState(watched = false, favorite = null))),
        )
        // A favorite-only change leaves the counts alone.
        val favorite = overlaid(LocalContentState(watched = null, favorite = true))
        assertEquals(started.copy(isFavorite = true), favorite)
    }
}
