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
                genres = listOf("Science Fiction"),
                ratingImdb = 7.9,
                contentRating = "PG-13",
                durationSeconds = 6_960.0,
                overlaySummary = OverlaySummary(
                    resolution = "2160p",
                    hdr = "Dolby Vision",
                    audio = "Atmos",
                ),
            ),
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
        )

        assertEquals(listOf("1h 56m"), chips.map { it.label })
    }
}
