package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.ItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneDetailHeroMetadataTest {
    private fun detail(
        type: String = "movie",
        ratingImdb: Double? = 7.9,
        ratingTmdb: Double? = 8.8,
        genres: List<String> = listOf(" Drama ", "", "Drama", "Science Fiction", "Thriller"),
        contentRating: String? = " pg-13 ",
    ) = ItemDetail(
        contentId = "detail-1",
        type = type,
        title = "Arrival",
        year = 2016,
        runtime = 116,
        ratingImdb = ratingImdb,
        ratingTmdb = ratingTmdb,
        genres = genres,
        contentRating = contentRating,
        studios = listOf(" Paramount "),
        networks = listOf(" HBO "),
    )

    @Test
    fun validImdbUsesLocaleStableLabelAndFacts() {
        val detail = detail(ratingImdb = 8.05)

        assertEquals("IMDb 8.1", HeroMetadata.movieEyebrow(detail))
        assertEquals(listOf("Drama · Science Fiction", "IMDb 8.1"), HeroMetadata.movieFactsLine(detail))
    }

    @Test
    fun absentImdbDoesNotRelabelTmdbAsImdb() {
        val detail = detail(ratingImdb = null, ratingTmdb = 8.8)

        assertNull(HeroMetadata.movieEyebrow(detail))
        assertEquals(listOf("Drama · Science Fiction"), HeroMetadata.movieFactsLine(detail))
    }

    @Test
    fun invalidImdbValuesAreOmittedFromEyebrowAndFacts() {
        listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            0.0,
            -1.0,
            10.1,
        ).forEach { invalid ->
            val detail = detail(ratingImdb = invalid)
            assertNull(HeroMetadata.movieEyebrow(detail))
            assertEquals(listOf("Drama · Science Fiction"), HeroMetadata.movieFactsLine(detail))
        }
    }

    @Test
    fun detailGenresAreTrimmedDeduplicatedAndCappedAtTwo() {
        assertEquals(
            listOf("Drama · Science Fiction", "IMDb 7.9"),
            HeroMetadata.seriesFactsLine(detail(type = "series")),
        )
    }

    @Test
    fun detailContentRatingIsTrimmedUppercasedAndBlankSafe() {
        assertEquals("PG-13", HeroMetadata.contentRating(detail()))
        assertNull(HeroMetadata.contentRating(detail(contentRating = "  ")))
        assertNull(HeroMetadata.contentRating(detail(contentRating = null)))
    }

    @Test
    fun sourceTokensTrimEditorialStudioAndNetworkAndOmitEmptyValues() {
        assertEquals(
            listOf("2016", "1h 56m", "Paramount"),
            HeroMetadata.movieSourceTokens(detail()),
        )
        assertEquals(
            listOf("2016", "HBO"),
            HeroMetadata.seriesSourceTokens(detail(type = "series")),
        )
    }
}
