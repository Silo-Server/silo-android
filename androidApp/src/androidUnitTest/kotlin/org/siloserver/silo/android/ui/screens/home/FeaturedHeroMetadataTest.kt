package org.siloserver.silo.android.ui.screens.home

import org.siloserver.silo.model.catalog.OverlaySummary
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals

class FeaturedHeroMetadataTest {
    @Test
    fun movieUsesOrderedEditorialMetadataWithoutGenericOrTechnicalChips() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "movie-1",
                type = "movie",
                title = "Arrival",
                year = 2016,
                genres = listOf("Science Fiction", "Drama"),
                ratingImdb = 7.9,
                contentRating = "PG-13",
                durationSeconds = 6_960.0,
                overlaySummary = OverlaySummary(
                    resolution = "2160p",
                    hdr = "Dolby Vision",
                    audio = "Atmos",
                ),
            ),
            maxGenres = 1,
        )

        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction", "PG-13"),
            chips.map { it.label },
        )
        assertEquals(
            listOf(
                FeaturedHeroMetadataKind.Plain,
                FeaturedHeroMetadataKind.Plain,
                FeaturedHeroMetadataKind.Rating,
                FeaturedHeroMetadataKind.Plain,
                FeaturedHeroMetadataKind.Classification,
            ),
            chips.map { it.kind },
        )
    }

    @Test
    fun episodeReliesOnExistingSeriesEyebrowAndTitleWithoutDuplicatingName() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "episode-1",
                type = "episode",
                title = "Long, Long Time",
                seriesTitle = "The Last of Us",
                seasonNumber = 1,
                episodeNumber = 3,
                ratingImdb = 8.6,
                contentRating = "TV-MA",
                durationSeconds = 4_560.0,
            ),
            maxGenres = 1,
        )

        assertEquals(
            listOf("S1 E3", "1h 16m", "8.6", "TV-MA"),
            chips.map { it.label },
        )
    }

    @Test
    fun invalidRatingsAndDurationsAreOmitted() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            11.0,
        ).forEachIndexed { index, invalid ->
            val chips = featuredHeroMetadata(
                SectionItem(
                    contentId = "invalid-$index",
                    type = "movie",
                    title = "Invalid",
                    ratingImdb = invalid,
                    durationSeconds = invalid,
                ),
                maxGenres = 1,
            )

            assertEquals(emptyList(), chips)
        }
    }

    @Test
    fun invalidRatingDoesNotHideValidPhoneRuntime() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "runtime-with-invalid-rating",
                type = "movie",
                title = "Movie",
                ratingImdb = Double.NaN,
                durationSeconds = 7_200.0,
            ),
            maxGenres = 1,
        )

        assertEquals(listOf("2h"), chips.map { it.label })
    }

    @Test
    fun validRatingDoesNotHideInvalidPhoneRuntime() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "rating-with-invalid-runtime",
                type = "movie",
                title = "Movie",
                ratingImdb = 8.4,
                durationSeconds = Double.NaN,
            ),
            maxGenres = 1,
        )

        assertEquals(listOf("8.4"), chips.map { it.label })
    }

    @Test
    fun catalogRuntimeWinsOverPlaybackDurationOnPhone() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "movie-runtime",
                type = "movie",
                title = "Movie",
                runtime = 125,
                durationSeconds = 60.0,
            ),
            maxGenres = 1,
        )

        assertEquals(listOf("2h 5m"), chips.map { it.label })
    }

    @Test
    fun invalidCatalogRuntimeFallsBackToPlaybackDurationOnPhone() {
        val chips = featuredHeroMetadata(
            SectionItem(
                contentId = "movie-runtime-fallback",
                type = "movie",
                title = "Movie",
                runtime = 0,
                durationSeconds = 6_960.0,
            ),
            maxGenres = 1,
        )

        assertEquals(listOf("1h 56m"), chips.map { it.label })
    }

    @Test
    fun phoneGenreAllowanceChangesExactlyAtSixHundredDp() {
        assertEquals(1, featuredHeroMaxGenres(screenWidthDp = 0))
        assertEquals(1, featuredHeroMaxGenres(screenWidthDp = 599))
        assertEquals(2, featuredHeroMaxGenres(screenWidthDp = 600))
        assertEquals(2, featuredHeroMaxGenres(screenWidthDp = 840))
    }

    @Test
    fun compactAndWidePhoneMapTheSharedGenreLimitWithoutMovingClassification() {
        val item = SectionItem(
            contentId = "movie-width",
            type = "movie",
            title = "Arrival",
            year = 2016,
            runtime = 116,
            ratingImdb = 7.9,
            genres = listOf(" Science Fiction ", "Drama", "Drama"),
            contentRating = " pg-13 ",
        )

        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction", "PG-13"),
            featuredHeroMetadata(item, maxGenres = featuredHeroMaxGenres(599)).map { it.label },
        )
        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction", "Drama", "PG-13"),
            featuredHeroMetadata(item, maxGenres = featuredHeroMaxGenres(600)).map { it.label },
        )
        assertEquals(
            FeaturedHeroMetadataKind.Classification,
            featuredHeroMetadata(item, maxGenres = 2).last().kind,
        )
    }

    @Test
    fun phoneEpisodeKeepsGenresOutEvenAtWideAllowance() {
        val chips = featuredHeroMetadata(
            item = SectionItem(
                contentId = "episode-wide",
                type = "episode",
                title = "Long, Long Time",
                seasonNumber = 1,
                episodeNumber = 3,
                runtime = 76,
                ratingImdb = 8.6,
                genres = listOf("Drama", "Horror"),
                contentRating = "TV-MA",
            ),
            maxGenres = 2,
        )

        assertEquals(listOf("S1 E3", "1h 16m", "8.6", "TV-MA"), chips.map { it.label })
    }
}
