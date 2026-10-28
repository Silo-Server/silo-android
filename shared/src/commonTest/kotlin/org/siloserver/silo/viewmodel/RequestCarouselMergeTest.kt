package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.request.RequestDiscoverRowRef
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors silo-apple's RequestCarouselMerge — Apple is authoritative. */
class RequestCarouselMergeTest {

    private fun result(id: Int, type: String) = RequestMediaResult(
        mediaType = type,
        tmdbId = id,
        title = "T$id",
    )

    private fun section(key: String, vararg results: RequestMediaResult) =
        RequestDiscoverySection(key = key, title = key, results = results.toList())

    @Test
    fun mergesTrendingAndPopularIntoTwoInterleavedCarousels() {
        val merged = mergeDiscoverCarousels(
            listOf(
                section("trending_movies", result(1, "movie"), result(2, "movie")),
                section("trending_series", result(3, "series")),
                section("popular_movies", result(4, "movie")),
                section("popular_series", result(5, "series")),
                // iOS ignores anything beyond trending_*/popular_*.
                section("upcoming", result(6, "movie")),
            ),
        )
        assertEquals(listOf("trending", "popular"), merged.map { it.key })
        assertEquals("Trending now", merged[0].title)
        assertEquals("Crowd favorites", merged[1].title)
        // Round-robin interleave: movie, series, movie.
        assertEquals(listOf(1, 3, 2), merged[0].results.map { it.tmdbId })
        assertEquals(listOf(4, 5), merged[1].results.map { it.tmdbId })
    }

    @Test
    fun dedupesAcrossMoviesAndSeriesWithinACarousel() {
        val merged = mergeDiscoverCarousels(
            listOf(
                section("trending_movies", result(1, "movie"), result(1, "movie")),
                section("trending_series", result(1, "series")),
            ),
        )
        // Same tmdbId with different media types are distinct items;
        // duplicate (movie, 1) collapses.
        assertEquals(2, merged.single().results.size)
    }

    @Test
    fun emptyPoolsProduceNoCarousels() {
        assertEquals(emptyList(), mergeDiscoverCarousels(emptyList()))
        assertEquals(
            listOf("popular"),
            mergeDiscoverCarousels(
                listOf(section("popular_movies", result(1, "movie"))),
            ).map { it.key },
        )
    }

    @Test
    fun aServerLayoutShowsItsOwnRowsInItsOrder() {
        val response = RequestsDiscoverResponse(
            sections = listOf(
                section("trending_movies", result(1, "movie")),
                section("r_anime", result(2, "series")),
                section("r_empty"),
            ),
            rowOrder = listOf(
                RequestDiscoverRowRef("r_anime", "tmdb_discover", "Anime"),
                RequestDiscoverRowRef("studios", "studios", "Studios"),
                RequestDiscoverRowRef("trending_movies", "tmdb_chart", "Trending Movies"),
                RequestDiscoverRowRef("r_empty", "mdblist", "Empty"),
            ),
        )
        // The brand row has no section and the empty row has no titles: both skipped.
        assertEquals(listOf("r_anime", "trending_movies"), discoverCarousels(response).map { it.key })
    }

    @Test
    fun anOlderServerStillGetsTheTwoMergedCarousels() {
        val response = RequestsDiscoverResponse(
            sections = listOf(section("trending_movies", result(1, "movie")), section("upcoming_movies", result(2, "movie"))),
        )
        assertEquals(listOf("trending"), discoverCarousels(response).map { it.key })
    }

    private fun page(totalPages: Int, nextPage: Int? = null) =
        RequestDiscoverySection(key = "r", title = "R", totalPages = totalPages, nextPage = nextPage)

    @Test
    fun seeAllPagesFollowTheServersCursor() {
        // Numbered pages up to the total.
        assertEquals(2, nextDiscoverRowPage(page(3), lastPage = 1))
        assertNull(nextDiscoverRowPage(page(3), lastPage = 3))
        // A cursor jumps past pages the server already read.
        assertEquals(4, nextDiscoverRowPage(page(40, nextPage = 4), lastPage = 1))
        // A page that read one source page, after cursor pages: page + 1.
        assertEquals(10, nextDiscoverRowPage(page(40), lastPage = 9))
        // A cursor past the last page: the read used the row up.
        assertNull(nextDiscoverRowPage(page(2, nextPage = 3), lastPage = 1))
    }
}
