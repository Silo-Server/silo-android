package org.siloserver.silo.catalog.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSortTest {
    private val allRatings = setOf("imdb", "tmdb", "rt_critic", "rt_audience")

    private fun fields(relevance: CatalogSortRelevance, personalized: Boolean = true, ratings: Set<String> = allRatings) =
        CatalogSortField.options(relevance, personalized, ratings)

    @Test
    fun seriesLibraryListsTheWebClientsSortsInItsOrder() {
        assertEquals(
            listOf(
                CatalogSortField.Title, CatalogSortField.DateAdded, CatalogSortField.ReleaseDate,
                CatalogSortField.LatestEpisodeAirDate, CatalogSortField.LatestEpisodeAdded, CatalogSortField.Year,
                CatalogSortField.ContentRating, CatalogSortField.Duration, CatalogSortField.ImdbRating,
                CatalogSortField.TmdbRating, CatalogSortField.RtCriticRating, CatalogSortField.RtAudienceRating,
                CatalogSortField.Resolution, CatalogSortField.Bitrate, CatalogSortField.Progress,
                CatalogSortField.DateViewed, CatalogSortField.Plays,
            ),
            fields(CatalogSortRelevance.forLibrary("series")),
        )
    }

    @Test
    fun episodeSortsStayOffMovieAndMixedLibraries() {
        val movie = fields(CatalogSortRelevance.forLibrary("movies"))
        assertFalse(CatalogSortField.LatestEpisodeAdded in movie)
        assertFalse(CatalogSortField.LatestEpisodeAirDate in movie)
        // A mixed library browsed on its Series side lists them; with no side picked it does not.
        assertTrue(CatalogSortField.LatestEpisodeAdded in fields(CatalogSortRelevance.forLibrary("mixed", "series")))
        assertFalse(CatalogSortField.LatestEpisodeAdded in fields(CatalogSortRelevance.forLibrary("mixed")))
    }

    @Test
    fun rottenTomatoesSortsNeedTheirSourceShownUnlessActive() {
        val series = CatalogSortRelevance.forLibrary("series")
        val withoutRt = CatalogSortField.options(series, true, setOf("imdb", "tmdb"))
        assertFalse(CatalogSortField.RtCriticRating in withoutRt)
        assertFalse(CatalogSortField.RtAudienceRating in withoutRt)
        val kept = CatalogSortField.options(series, true, emptySet(), keep = CatalogSortField.RtCriticRating)
        assertTrue(CatalogSortField.RtCriticRating in kept)
    }

    @Test
    fun theActiveSortStaysListedWhereTheListWouldNotOfferIt() {
        val movie = CatalogSortRelevance.forLibrary("movies")
        val restored = CatalogSortField.options(movie, false, emptySet(), keep = CatalogSortField.LatestEpisodeAdded)
        assertTrue(CatalogSortField.LatestEpisodeAdded in restored)
        assertFalse(CatalogSortField.LatestEpisodeAirDate in restored)
    }

    @Test
    fun personalizedSortsAreLeftOutWhenNotAllowed() {
        val saved = fields(CatalogSortRelevance.Any, personalized = false)
        assertFalse(saved.any { it.personalized })
        assertTrue(CatalogSortField.LatestEpisodeAdded in saved)
    }

    @Test
    fun bookLibrariesUseTheirOwnSortsAndReadingNames() {
        assertEquals(
            listOf(
                CatalogSortField.Title, CatalogSortField.DateAdded, CatalogSortField.ReleaseDate, CatalogSortField.Year,
                CatalogSortField.Duration, CatalogSortField.Bitrate, CatalogSortField.Progress, CatalogSortField.DateViewed,
                CatalogSortField.Plays, CatalogSortField.Author, CatalogSortField.Narrator, CatalogSortField.Series,
            ),
            fields(CatalogSortRelevance.forLibrary("audiobooks")),
        )
        val ebook = CatalogSortRelevance.forLibrary("ebooks")
        assertTrue(ebook.isReading)
        assertFalse(CatalogSortRelevance.forLibrary("audiobooks").isReading)
        assertFalse(CatalogSortField.Narrator in fields(ebook))
    }

    @Test
    fun wireValuesAndLegacyAliasesResolve() {
        assertEquals(CatalogSortField.LatestEpisodeAdded, CatalogSortField.fromWire("latest_episode_added"))
        assertEquals(CatalogSortField.DateAdded, CatalogSortField.fromWire("recently_added"))
        assertEquals(CatalogSortField.ImdbRating, CatalogSortField.fromWire("rating"))
        assertEquals(null, CatalogSortField.fromWire("unknown"))
    }
}
