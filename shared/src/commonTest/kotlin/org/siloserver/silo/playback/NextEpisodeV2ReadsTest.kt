package org.siloserver.silo.playback

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

/** Both player ViewModels feed these repository reads into nextEpisodeAfter. */
class NextEpisodeV2ReadsTest {
    @Test fun regularSeasonRolloverPreservesEpisodeFilesAndSkipsSpecials() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            val body = when (path) {
                "/api/v2/catalog/series/show/seasons" -> """{"items":[{"content_id":"s0","season_number":0,"is_specials":true},{"content_id":"s1","season_number":1},{"content_id":"special","season_number":2,"is_specials":true},{"content_id":"s3","season_number":3}]}"""
                "/api/v2/catalog/series/show/seasons/1/episodes" -> """{"items":[{"content_id":"s1e2","season_number":1,"episode_number":2,"title":"Finale","files":[{"file_id":"42"}]}]}"""
                "/api/v2/catalog/series/show/seasons/3/episodes" -> """{"items":[{"content_id":"s3e1","season_number":3,"episode_number":1,"title":"Return","still_url":"https://example.invalid/still","runtime":45,"files":[{"file_id":"43"}]}]}"""
                else -> error("Unexpected request $path")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = CatalogRepository(CatalogApi(client))
            val current = assertIs<ApiResult.Success<EpisodesResponse>>(repository.getEpisodes("show", 1)).data.episodes
            val seasons = assertIs<ApiResult.Success<SeasonsResponse>>(repository.getSeasons("show")).data.seasons
            val following = seasons.filter { !it.isSpecials && it.seasonNumber > 1 }.minBy { it.seasonNumber }
            val upcoming = assertIs<ApiResult.Success<EpisodesResponse>>(repository.getEpisodes("show", following.seasonNumber)).data.episodes
            assertEquals(42, current.single().files.single().fileId)
            assertEquals("s1e2", nextEpisodeAfter(current + upcoming, 1, 1)?.contentId)
            val rollover = assertNotNull(nextEpisodeAfter(current + upcoming, 1, 2))
            assertEquals("s3e1", rollover.contentId)
            assertEquals(43, rollover.files.single().fileId)
            assertEquals(45, rollover.runtime)
            assertEquals("https://example.invalid/still", rollover.stillUrl)
            assertEquals(3, paths.size)
        } finally { client.close() }
    }

    @Test fun failedOrPartialCurrentSeasonIsAnErrorNotAnEmptySuccessfulSeason() = runTest {
        for ((body, status) in listOf(
            """{"type":"https://siloserver.org/problems/not_found","title":"Not found","status":404}""" to HttpStatusCode.NotFound,
            """{"items":[],"page":{"has_more":true,"next_cursor":"unrepresentable"}}""" to HttpStatusCode.OK,
            """{"episodes":[]}""" to HttpStatusCode.OK,
        )) {
            val client = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                // Both players require Success here before requesting any next season.
                val result = CatalogRepository(CatalogApi(client)).getEpisodes("show", 1)
                assertFalse(result is ApiResult.Success)
            } finally { client.close() }
        }
    }
}
