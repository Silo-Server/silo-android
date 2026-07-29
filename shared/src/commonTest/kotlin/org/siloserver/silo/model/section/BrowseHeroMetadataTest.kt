package org.siloserver.silo.model.section

import org.siloserver.silo.model.catalog.OverlaySummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowseHeroMetadataTest {
    private fun movie(
        runtime: Int? = 116,
        durationSeconds: Double? = 60.0,
        rating: Double? = 7.9,
        genres: List<String> = listOf("Science Fiction", "Drama"),
        contentRating: String? = " pg-13 ",
    ) = SectionItem(
        contentId = "movie-1",
        type = "movie",
        title = "Arrival",
        year = 2016,
        runtime = runtime,
        durationSeconds = durationSeconds,
        ratingImdb = rating,
        genres = genres,
        contentRating = contentRating,
        overlaySummary = OverlaySummary(
            resolution = "2160p",
            hdr = "Dolby Vision",
            audio = "TrueHD Atmos",
        ),
    )

    @Test
    fun completeMovieUsesExactEditorialPriorityAndNeverUsesTechnicalOverlay() {
        val metadata = movie().toBrowseHeroMetadata(maxGenres = 2)

        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction", "Drama"),
            metadata.orderedTokens(),
        )
        assertEquals("PG-13", metadata.contentRating)
    }

    @Test
    fun episodeUsesIdentityRuntimeAndRatingWithoutGenres() {
        val metadata = SectionItem(
            contentId = "episode-1",
            type = "episode",
            title = "Long, Long Time",
            seriesTitle = "The Last of Us",
            seasonNumber = 1,
            episodeNumber = 3,
            runtime = 76,
            ratingImdb = 8.6,
            genres = listOf("Drama", "Horror"),
            contentRating = " tv-ma ",
        ).toBrowseHeroMetadata(maxGenres = 2)

        assertEquals(listOf("S1 E3", "1h 16m", "8.6"), metadata.orderedTokens())
        assertEquals(emptyList(), metadata.genres)
        assertEquals("TV-MA", metadata.contentRating)
    }

    @Test
    fun partialEpisodeIdentityOmitsOnlyMissingPart() {
        val seasonOnly = SectionItem(
            contentId = "season-only",
            type = "episode",
            title = "Episode",
            seasonNumber = 2,
        ).toBrowseHeroMetadata(maxGenres = 2)
        val episodeOnly = SectionItem(
            contentId = "episode-only",
            type = "episode",
            title = "Episode",
            episodeNumber = 7,
        ).toBrowseHeroMetadata(maxGenres = 2)

        assertEquals("Season 2", seasonOnly.leadingToken)
        assertEquals("Episode 7", episodeOnly.leadingToken)
    }

    @Test
    fun positiveCatalogRuntimeWinsOverDurationFallback() {
        assertEquals(
            "2h 5m",
            movie(runtime = 125, durationSeconds = 60.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
    }

    @Test
    fun invalidCatalogRuntimeFallsBackToRoundedPositiveDuration() {
        assertEquals(
            "1h 56m",
            movie(runtime = 0, durationSeconds = 6_960.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
        assertEquals(
            "2h",
            movie(runtime = -2, durationSeconds = 7_200.0)
                .toBrowseHeroMetadata(maxGenres = 1)
                .runtimeToken,
        )
    }

    @Test
    fun missingNonFiniteNonPositiveAndRoundedZeroRuntimeAreOmitted() {
        listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -60.0,
            29.0,
        ).forEach { duration ->
            assertNull(
                movie(runtime = null, durationSeconds = duration)
                    .toBrowseHeroMetadata(maxGenres = 1)
                    .runtimeToken,
            )
        }
    }

    @Test
    fun ratingUsesStableOneDecimalAndRejectsUnsupportedValues() {
        assertEquals(
            "8.0",
            movie(rating = 8.04).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
        assertEquals(
            "8.1",
            movie(rating = 8.05).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
        listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            10.1,
        ).forEach { rating ->
            assertNull(
                movie(rating = rating)
                    .toBrowseHeroMetadata(maxGenres = 1)
                    .imdbRatingToken,
            )
        }
        assertEquals(
            "10.0",
            movie(rating = 10.0).toBrowseHeroMetadata(maxGenres = 1).imdbRatingToken,
        )
    }

    @Test
    fun genresAreTrimmedDeduplicatedAndLimitedInFirstSeenOrder() {
        val genres = listOf(" Drama ", "", "Drama", "Comedy", " comedy ", "Thriller")

        assertEquals(
            listOf("Drama"),
            movie(genres = genres).toBrowseHeroMetadata(maxGenres = 1).genres,
        )
        assertEquals(
            listOf("Drama", "Comedy"),
            movie(genres = genres).toBrowseHeroMetadata(maxGenres = 2).genres,
        )
    }

    @Test
    fun longGenreIsPreservedForExistingPlatformTruncation() {
        val longGenre = "Documentary About Science Technology Engineering and Mathematics"

        assertEquals(
            listOf(longGenre),
            movie(genres = listOf(" $longGenre "))
                .toBrowseHeroMetadata(maxGenres = 1)
                .genres,
        )
    }

    @Test
    fun genreLimitZeroIsSupportedAndNegativeLimitIsRejected() {
        assertEquals(
            emptyList(),
            movie().toBrowseHeroMetadata(maxGenres = 0).genres,
        )
        assertFailsWith<IllegalArgumentException> {
            movie().toBrowseHeroMetadata(maxGenres = -1)
        }
    }

    @Test
    fun contentRatingIsTrimmedUppercasedAndBlankIsOmitted() {
        assertEquals(
            "PG-13",
            movie(contentRating = " pg-13 ").toBrowseHeroMetadata(maxGenres = 1).contentRating,
        )
        assertNull(
            movie(contentRating = "  ").toBrowseHeroMetadata(maxGenres = 1).contentRating,
        )
    }
}
