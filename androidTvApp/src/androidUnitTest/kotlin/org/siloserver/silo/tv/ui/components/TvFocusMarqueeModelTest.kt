package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.model.catalog.OverlaySummary
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals

class TvFocusMarqueeModelTest {
    @Test
    fun movieHeroPrioritizesEditorialMetadataAndOmitsStreamQuality() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
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
                    audio = "TrueHD Atmos",
                ),
            ),
            rowTitle = "Popular",
        )

        assertEquals(listOf("PG-13"), content.badges)
        assertEquals(
            listOf("2016", "1h 56m", "7.9", "Science Fiction"),
            content.metaParts,
        )
    }

    @Test
    fun episodeHeroUsesSeriesTitleAndEditorialEpisodeMetadata() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "episode-1",
                type = "episode",
                title = "Long, Long Time",
                seriesTitle = "The Last of Us",
                seasonNumber = 1,
                episodeNumber = 3,
                ratingImdb = 8.6,
                contentRating = "TV-MA",
                durationSeconds = 4_560.0,
                overlaySummary = OverlaySummary(
                    resolution = "1080p",
                    audio = "EAC3",
                ),
            ),
            rowTitle = "Continue Watching",
        )

        assertEquals("The Last of Us", content.title)
        assertEquals(listOf("TV-MA"), content.badges)
        assertEquals(
            listOf("S1 E3", "Long, Long Time", "1h 16m", "8.6"),
            content.metaParts,
        )
    }

    @Test
    fun missingEditorialMetadataProducesNoEmptyTokensOrBadges() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-2",
                type = "movie",
                title = "Untitled",
                overlaySummary = OverlaySummary(
                    resolution = "2160p",
                    hdr = "HDR10",
                    audio = "Atmos",
                ),
            ),
            rowTitle = "Recently Added",
        )

        assertEquals(emptyList(), content.badges)
        assertEquals(emptyList(), content.metaParts)
    }

    @Test
    fun invalidRatingsAndDurationsAreOmittedFromTvMetadata() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            11.0,
        ).forEachIndexed { index, invalid ->
            val content = TvMarqueeContent.from(
                item = SectionItem(
                    contentId = "invalid-$index",
                    type = "movie",
                    title = "Invalid",
                    ratingImdb = invalid,
                    durationSeconds = invalid,
                ),
                rowTitle = "Invalid",
            )

            assertEquals(emptyList(), content.metaParts)
        }
    }

    @Test
    fun invalidRatingDoesNotHideValidTvRuntime() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "runtime-with-invalid-rating",
                type = "movie",
                title = "Movie",
                ratingImdb = Double.NaN,
                durationSeconds = 7_200.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("2h"), content.metaParts)
    }

    @Test
    fun validRatingDoesNotHideInvalidTvRuntime() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "rating-with-invalid-runtime",
                type = "movie",
                title = "Movie",
                ratingImdb = 8.4,
                durationSeconds = Double.NaN,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("8.4"), content.metaParts)
    }

    @Test
    fun catalogRuntimeWinsOverPlaybackDurationOnTv() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-runtime",
                type = "movie",
                title = "Movie",
                runtime = 125,
                durationSeconds = 60.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("2h 5m"), content.metaParts)
    }

    @Test
    fun invalidCatalogRuntimeFallsBackToPlaybackDurationOnTv() {
        val content = TvMarqueeContent.from(
            item = SectionItem(
                contentId = "movie-runtime-fallback",
                type = "movie",
                title = "Movie",
                runtime = 0,
                durationSeconds = 6_960.0,
            ),
            rowTitle = "Row",
        )

        assertEquals(listOf("1h 56m"), content.metaParts)
    }
}
