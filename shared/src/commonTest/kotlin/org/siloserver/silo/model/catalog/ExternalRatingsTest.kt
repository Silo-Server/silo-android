package org.siloserver.silo.model.catalog

import kotlinx.serialization.json.Json
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.ItemDetailReadV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalRatingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serverListIsShownVerbatimAndInOrder() {
        val server = listOf(
            DisplayRating("rt_critic", "RT", 93.0, "93%"),
            DisplayRating("imdb", "IMDb", 85.0, "8.5"),
            DisplayRating("letterboxd", "Letterboxd", 84.0, "4.2"),
        )

        assertEquals(server, ExternalRatings.forTitle(server, ratingImdb = 1.0, ratingTmdb = 2.0))
    }

    @Test
    fun emptyServerListMeansNothingToShow() {
        assertEquals(emptyList(), ExternalRatings.forTitle(emptyList(), ratingImdb = 8.5, ratingTmdb = 8.3))
    }

    @Test
    fun olderServerFallsBackToImdbThenTmdbOnly() {
        val detail = json.decodeFromString<ItemDetail>(
            """
            {"content_id":"movie-1","type":"movie","title":"Movie",
             "rating_imdb":8.45,"rating_tmdb":8.25,
             "rating_rt_critic":93,"rating_rt_audience":95}
            """.trimIndent(),
        )

        assertNull(detail.ratings)
        assertEquals(
            listOf(
                DisplayRating("imdb", "IMDb", 84.5, "8.5"),
                DisplayRating("tmdb", "TMDB", 82.5, "8.3"),
            ),
            detail.titleRatings(),
        )
    }

    @Test
    fun fallbackSkipsMissingAndOutOfRangeScores() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 10.5).forEach { invalid ->
            assertEquals(
                listOf("TMDB"),
                ExternalRatings.forTitle(null, ratingImdb = invalid, ratingTmdb = 7.0).map { it.name },
            )
            assertNull(ExternalRatings.primary(invalid, invalid))
        }
        assertEquals("10.0", ExternalRatings.imdb(10.0)?.display)
    }

    @Test
    fun phoneShowsTheFirstThreeInServerOrder() {
        val server = listOf(
            DisplayRating("imdb", "IMDb", 85.0, "8.5"),
            DisplayRating("tmdb", "TMDB", 82.5, "8.3"),
            DisplayRating("rt_critic", "RT", 93.0, "93%"),
            DisplayRating("rt_audience", "RT Audience", 95.0, "95%"),
            DisplayRating("metacritic", "Metacritic", 87.0, "87"),
        )

        assertEquals(3, ExternalRatings.PHONE_LIMIT)
        assertEquals(server.take(3), ExternalRatings.forPhone(server))
        assertEquals(server.take(2), ExternalRatings.forPhone(server.take(2)))
        assertEquals(emptyList(), ExternalRatings.forPhone(emptyList()))
    }

    @Test
    fun primaryPrefersImdbAndFallsBackToTmdb() {
        assertEquals(DisplayRating("imdb", "IMDb", 79.0, "7.9"), ExternalRatings.primary(7.9, 8.2))
        assertEquals(DisplayRating("tmdb", "TMDB", 82.0, "8.2"), ExternalRatings.primary(null, 8.2))
        assertEquals("TMDB", ExternalRatings.primary(0.0, 8.2)?.name)
    }

    @Test
    fun oneDecimalIsLocaleIndependent() {
        assertEquals("8.5", ExternalRatings.formatOneDecimal(8.45))
        assertEquals("7.0", ExternalRatings.formatOneDecimal(7.0))
        assertEquals("7.0", ExternalRatings.formatOneDecimal(6.96))
        assertEquals("0.1", ExternalRatings.formatOneDecimal(0.05))
    }

    @Test
    fun v2DetailCarriesTheServerRatingsList() {
        val detail = json.decodeFromString<ItemDetailReadV2>(
            """
            {"content_id":"movie-1","type":"movie","title":"Movie","rating_imdb":1.0,
             "cast":[],"crew":[],"versions":[],"subtitles":[],
             "ratings":[{"source":"tmdb","name":"TMDB","score":82.5,"display":"8.3"}]}
            """.trimIndent(),
        ).toDomain()

        assertEquals(listOf(DisplayRating("tmdb", "TMDB", 82.5, "8.3")), detail.titleRatings())
    }

    @Test
    fun malformedRatingEntriesAreDroppedOneByOne() {
        val detail = SiloJson.decodeFromString<ItemDetailReadV2>(
            """
            {"content_id":"movie-1","type":"movie","title":"Movie",
             "cast":[],"crew":[],"versions":[],"subtitles":[],
             "ratings":[
               {"source":"imdb","name":"IMDb","score":85.0,"display":"8.5"},
               {"source":"plugin","name":"Plugin","score":null},
               {"source":"blank","name":"","score":50,"display":"5"},
               "not an object",
               {"source":"letterboxd","name":"Letterboxd","score":null,"display":"4.2"}
             ]}
            """.trimIndent(),
        ).toDomain()

        assertEquals(
            listOf(
                DisplayRating("imdb", "IMDb", 85.0, "8.5"),
                DisplayRating("letterboxd", "Letterboxd", null, "4.2"),
            ),
            detail.titleRatings(),
        )
    }
}
