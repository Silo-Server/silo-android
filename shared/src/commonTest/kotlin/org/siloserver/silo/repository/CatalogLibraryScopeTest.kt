package org.siloserver.silo.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import kotlin.test.*

class CatalogLibraryScopeTest {
    private val detail = """{"content_id":"m1","type":"movie","title":"Film","cast":[],"crew":[],"subtitles":[],"versions":[{"file_id":"42"}]}"""

    @Test
    fun libraryContextReachesEveryDetailAndWatchRead() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", null)
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = owner
        }
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            requests += path to request.url.parameters["library_id"]
            val body = when (path) {
                "/api/v2/catalog/items/m1" -> detail
                "/api/v2/watch/m1" -> """{"content_id":"m1","type":"movie","title":"Film","versions":[{"file_id":"42","duration_seconds":120}]}"""
                else -> """{"items":[]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = CatalogRepository(CatalogApi(client,
                watchDetail = WatchDetailV2Api(client, tokens, ApiV2Gate.Unrestricted)))
            for (libraryId in listOf(7, 8, null)) {
                assertIs<ApiResult.Success<*>>(repository.getItemDetail("m1", libraryId))
                assertIs<ApiResult.Success<*>>(repository.getItemVersions("m1", libraryId))
                assertIs<ApiResult.Success<*>>(repository.getItemEpisodes("m1", libraryId))
                assertIs<ApiResult.Success<*>>(repository.getSeasons("m1", libraryId))
                assertIs<ApiResult.Success<*>>(repository.getEpisodes("m1", 2, libraryId))
                assertIs<ApiResult.Success<*>>(repository.getWatchDetail("m1", owner, libraryId))
                assertIs<ApiResult.Success<*>>(repository.getWatchDetail("m1", libraryId))
            }
            val paths = listOf(
                "/api/v2/catalog/items/m1", "/api/v2/catalog/items/m1/versions",
                "/api/v2/catalog/items/m1/episodes", "/api/v2/catalog/series/m1/seasons",
                "/api/v2/catalog/series/m1/seasons/2/episodes", "/api/v2/watch/m1", "/api/v2/watch/m1",
            )
            assertEquals(listOf("7", "8", null).flatMap { scope -> paths.map { it to scope } }, requests)
        } finally { client.close() }
    }

    @Test
    fun scopedReadsNeverReadOrWriteTheUnscopedCache() = runTest {
        var reads = 0
        var writes = 0
        val cache = object : CatalogCachePort {
            override suspend fun getCachedItemDetail(contentId: String): ItemDetail? {
                reads++
                return ItemDetail(contentId = contentId, type = "movie", title = "Unscoped")
            }
            override suspend fun getCachedSeasons(seriesId: String): SeasonsResponse {
                reads++
                return SeasonsResponse()
            }
            override suspend fun getCachedEpisodes(seriesId: String, seasonNumber: Int): EpisodesResponse {
                reads++
                return EpisodesResponse()
            }
            override suspend fun cacheItemDetail(contentId: String, detail: ItemDetail, lease: CatalogCacheWriteLease) { writes++ }
            override suspend fun cacheSeasons(seriesId: String, seasons: SeasonsResponse, lease: CatalogCacheWriteLease) { writes++ }
            override suspend fun cacheEpisodes(seriesId: String, seasonNumber: Int, episodes: EpisodesResponse, lease: CatalogCacheWriteLease) { writes++ }
        }
        var fail = false
        val client = HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath.endsWith("/m1")) detail else """{"items":[]}""",
                if (fail) HttpStatusCode.BadGateway else HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = CatalogRepository(CatalogApi(client), cache)
            assertNull(repository.getCachedItemDetail("m1", 7))
            assertNull(repository.getCachedSeasons("m1", 7))
            assertNull(repository.getCachedEpisodes("m1", 2, 7))
            for (failure in listOf(false, true)) {
                fail = failure
                val results = listOf(
                    repository.getItemDetail("m1", 7), repository.getItemDetailForPrefetch("m1", 7),
                    repository.getSeasons("m1", 7), repository.getSeasonsForPrefetch("m1", 7),
                    repository.getEpisodes("m1", 2, 7), repository.getEpisodesForPrefetch("m1", 2, 7),
                )
                results.forEach { assertEquals(!failure, it is ApiResult.Success) }
            }
            assertEquals(0, reads)
            assertEquals(0, writes)
        } finally { client.close() }
    }

    @Test
    fun libraryReadDoesNotJoinHomeWarmup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scopes = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            val scope = request.url.parameters["library_id"]
            scopes += scope
            if (scope == null) {
                entered.complete(Unit)
                release.await()
            }
            respond(detail, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repository = CatalogRepository(CatalogApi(client), requestDispatcher = StandardTestDispatcher(testScheduler))
            val home = async { repository.warmItemDetail("m1") }
            entered.await()
            try {
                assertIs<ApiResult.Success<*>>(repository.getItemDetail("m1", 7))
                assertEquals(listOf(null, "7"), scopes)
            } finally { release.complete(Unit) }
            home.await()
        } finally { client.close() }
    }
}
