package org.siloserver.silo.tv.ui.screens.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.Season

class TvEpisodeWindowTest {
    private fun seasons(count: Int) = (1..count).map { Season("season-$it", it) }
    private fun episodes(season: Int, count: Int = 2) =
        (1..count).map { EpisodeListItem("$season-$it", season, it) }

    @Test
    fun adjacentPagesPrependWithoutChangingEpisodeIdentities() {
        val window = TvEpisodeWindow()
        window.put(2, episodes(2))
        window.put(3, episodes(3))
        assertEquals(listOf("2-1", "2-2", "3-1", "3-2"), window.snapshot(seasons(3), 2).episodes.map { it.contentId })
        window.put(1, episodes(1).reversed())
        val snapshot = window.snapshot(seasons(3), 2)
        assertEquals((1..3).flatMap { episodes(it) }, snapshot.episodes)
        assertNull(snapshot.previousSeason)
        assertNull(snapshot.nextSeason)
    }

    @Test
    fun missingSeasonIsAnEdgeUntilItLoadsEvenAfterDistantJump() {
        val window = TvEpisodeWindow()
        window.put(1, episodes(1))
        window.put(3, episodes(3))
        assertEquals(episodes(1), window.snapshot(seasons(3), 1).episodes)
        assertEquals(2, window.snapshot(seasons(3), 1).nextSeason)
        assertEquals(episodes(3), window.snapshot(seasons(3), 3).episodes)
        assertEquals(2, window.snapshot(seasons(3), 3).previousSeason)
        window.put(2, emptyList())
        assertEquals(episodes(1) + episodes(3), window.snapshot(seasons(3), 3).episodes)
    }

    @Test
    fun specialsFollowRegularSeasonsRegardlessOfInputOrder() {
        val window = TvEpisodeWindow()
        val seasons = listOf(Season("special", 0, isSpecials = true)) + seasons(2).reversed()
        (0..2).forEach { window.put(it, episodes(it)) }
        assertEquals(listOf(1, 2, 0), window.orderedSeasons(seasons))
        assertEquals(episodes(1) + episodes(2) + episodes(0), window.snapshot(seasons, 2).episodes)
    }

    @Test
    fun thousandsOfEpisodesKeepAtMostFiveLoadedPages() {
        val window = TvEpisodeWindow()
        val seasons = seasons(100)
        for (season in 1..100) {
            window.put(season, episodes(season, 100))
            window.retainNear(seasons, season)
        }
        assertEquals(500, window.snapshot(seasons, 100).episodes.size)
        assertNull(window.get(1))
        assertEquals(episodes(100, 100), window.get(100))
    }

    @Test
    fun emptySeasonsDoNotBreakTheWindowOrEvictTheActivePage() {
        val window = TvEpisodeWindow()
        window.put(1, episodes(1))
        (2..9).forEach { window.put(it, emptyList()) }
        window.put(10, episodes(10))
        window.retainNear(seasons(10), 1)
        assertEquals(episodes(1) + episodes(10), window.snapshot(seasons(10), 1).episodes)
    }
}
