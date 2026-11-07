package org.siloserver.silo.catalog.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSortTest {
    private val allRatings = setOf("imdb", "tmdb", "rt_critic", "rt_audience")

    private fun labels(relevance: CatalogSortRelevance, personalized: Boolean = true, ratings: Set<String> = allRatings) =
        CatalogSortField.options(relevance, personalized, ratings).map { it.labelFor(relevance) }

    @Test
    fun seriesLibraryListsTheWebClientsSortsInItsOrder() {
        assertEquals(
            listOf(
                "Title", "Date Added", "Release Date", "Latest Episode Air Date", "Latest Episode Added",
                "Year", "Content Rating", "Duration", "IMDb Rating", "TMDB Rating", "RT Critic Rating",
                "RT Audience Rating", "Resolution", "Bitrate", "Progress", "Date Viewed", "Plays",
            ),
            labels(CatalogSortRelevance.forLibrary("series")),
        )
    }

    @Test
    fun episodeSortsStayOffMovieAndMixedLibraries() {
        val movie = labels(CatalogSortRelevance.forLibrary("movies"))
        assertFalse("Latest Episode Added" in movie)
        assertFalse("Latest Episode Air Date" in movie)
        // A mixed library browsed on its Series side lists them; with no side picked it does not.
        assertTrue("Latest Episode Added" in labels(CatalogSortRelevance.forLibrary("mixed", "series")))
        assertFalse("Latest Episode Added" in labels(CatalogSortRelevance.forLibrary("mixed")))
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
        val saved = labels(CatalogSortRelevance.Any, personalized = false)
        assertFalse("Progress" in saved || "Date Viewed" in saved || "Plays" in saved)
        assertTrue("Latest Episode Added" in saved)
    }

    @Test
    fun bookLibrariesUseTheirOwnSortsAndReadingNames() {
        assertEquals(
            listOf("Title", "Date Added", "Release Date", "Year", "Duration", "Bitrate", "Progress", "Date Viewed", "Plays", "Author", "Narrator", "Series"),
            labels(CatalogSortRelevance.forLibrary("audiobooks")),
        )
        val ebook = labels(CatalogSortRelevance.forLibrary("ebooks"))
        assertTrue("Date Read" in ebook && "Reads" in ebook)
        assertFalse("Narrator" in ebook)
    }

    @Test
    fun wireValuesAndLegacyAliasesResolve() {
        assertEquals(CatalogSortField.LatestEpisodeAdded, CatalogSortField.fromWire("latest_episode_added"))
        assertEquals(CatalogSortField.DateAdded, CatalogSortField.fromWire("recently_added"))
        assertEquals(CatalogSortField.ImdbRating, CatalogSortField.fromWire("rating"))
        assertEquals(null, CatalogSortField.fromWire("unknown"))
        assertEquals("Newest", CatalogSortField.LatestEpisodeAdded.directionLabel("desc"))
        assertEquals("A–Z", CatalogSortField.Title.directionLabel("asc"))
    }
}
