package org.siloserver.silo.repository

import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Locks the cache-with-fallback contract on [CatalogRepository.getItemDetail]. */
class CatalogRepositoryDetailCacheTest {

    private class FakeCache(
        val preset: ItemDetail? = null,
        val seasonsPreset: SeasonsResponse? = null,
        val episodesPreset: EpisodesResponse? = null,
        val identityTransitions: IdentityTransitionBarrier? = null,
        val beforeItemCache: suspend () -> Unit = {},
    ) : CatalogCachePort {
        var cachedId: String? = null
        override suspend fun cacheItemDetail(
            contentId: String,
            detail: ItemDetail,
            lease: CatalogCacheWriteLease,
        ) {
            beforeItemCache()
            if (
                identityTransitions == null ||
                lease.identityGeneration == identityTransitions.generation.value
            ) {
                cachedId = contentId
            }
        }
        override suspend fun getCachedItemDetail(contentId: String): ItemDetail? = preset
        override suspend fun getCachedSeasons(seriesId: String): SeasonsResponse? = seasonsPreset
        override suspend fun getCachedEpisodes(
            seriesId: String,
            seasonNumber: Int,
        ): EpisodesResponse? = episodesPreset
    }

    private fun repo(status: HttpStatusCode, body: String, cache: CatalogCachePort): CatalogRepository {
        val client = HttpClient(
            MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return CatalogRepository(CatalogApi(client), cache)
    }

    private fun repoThatFailsOnNetwork(cache: CatalogCachePort): CatalogRepository {
        val client = HttpClient(
            MockEngine { error("Network should not be used for a cached detail peek") },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return CatalogRepository(CatalogApi(client), cache)
    }

    private fun gatedRepository(
        requestDispatcher: CoroutineDispatcher,
        requestEntered: CompletableDeferred<Unit>,
        releaseResponse: CompletableDeferred<Unit>,
        onRequest: () -> Unit,
    ): CatalogRepository {
        val client = HttpClient(
            MockEngine {
                onRequest()
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    """{"content_id":"c1","type":"movie","title":"A"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return CatalogRepository(
            catalogApi = CatalogApi(client),
            requestDispatcher = requestDispatcher,
        )
    }

    @Test
    fun cachesOnSuccess() = runTest {
        val cache = FakeCache(preset = null)
        val result = repo(HttpStatusCode.OK, """{"content_id":"c1","type":"movie","title":"A"}""", cache)
            .getItemDetail("c1")
        assertTrue(result is ApiResult.Success)
        assertEquals("c1", cache.cachedId)
    }

    @Test
    fun servesCacheOnServer5xx() = runTest {
        val cache = FakeCache(preset = ItemDetail(contentId = "c1", type = "movie", title = "Cached"))
        val result = repo(HttpStatusCode.BadGateway, "{}", cache).getItemDetail("c1")
        assertTrue(result is ApiResult.Success)
        assertEquals("Cached", result.data.title)
    }

    @Test
    fun exposesCachedDetailWithoutNetworkForFastDetailShells() = runTest {
        val cache = FakeCache(preset = ItemDetail(contentId = "c1", type = "movie", title = "Cached"))
        val detail = repoThatFailsOnNetwork(cache).getCachedItemDetail("c1")
        assertEquals("Cached", detail?.title)
    }

    @Test
    fun prefetchUsesCachedDetailWithoutNetwork() = runTest {
        val cache = FakeCache(preset = ItemDetail(contentId = "c1", type = "movie", title = "Cached"))
        val result = repoThatFailsOnNetwork(cache).getItemDetailForPrefetch("c1")
        assertEquals("Cached", (result as ApiResult.Success).data.title)
    }

    @Test
    fun prefetchFetchesAndCachesWhenDetailIsAbsent() = runTest {
        val cache = FakeCache()
        val result = repo(HttpStatusCode.OK, """{"content_id":"c2","type":"movie","title":"Fresh"}""", cache)
            .getItemDetailForPrefetch("c2")
        assertEquals("Fresh", (result as ApiResult.Success).data.title)
        assertEquals("c2", cache.cachedId)
    }

    @Test
    fun seasonAndEpisodePrefetchUseCacheWithoutNetwork() = runTest {
        val cache = FakeCache(
            seasonsPreset = SeasonsResponse(),
            episodesPreset = EpisodesResponse(),
        )
        val repository = repoThatFailsOnNetwork(cache)

        assertTrue(repository.getSeasonsForPrefetch("series-1") is ApiResult.Success)
        assertTrue(repository.getEpisodesForPrefetch("series-1", 3) is ApiResult.Success)
    }

    @Test
    fun destinationJoinsActiveDetailWarmup() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = gatedRepository(
            requestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = entered,
            releaseResponse = release,
            onRequest = { calls += 1 },
        )

        val requests = listOf(
            async { repository.warmItemDetail("c1") },
            async { repository.getItemDetail("c1") },
        )
        entered.await()
        repeat(10) { yield() }
        release.complete(Unit)
        requests.awaitAll()

        assertEquals(1, calls)
    }

    @Test
    fun cancelingHomePrefetchDoesNotCancelDestinationDetailRequest() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = gatedRepository(
            requestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = entered,
            releaseResponse = release,
            onRequest = { calls += 1 },
        )

        val homePrefetch = launch { repository.warmItemDetail("c1") }
        entered.await()
        val destination = async { repository.getItemDetail("c1") }
        repeat(10) { yield() }
        homePrefetch.cancelAndJoin()
        release.complete(Unit)

        assertTrue(destination.await() is ApiResult.Success)
        assertEquals(1, calls)
    }

    @Test
    fun doesNotServeCacheOn4xx() = runTest {
        val cache = FakeCache(preset = ItemDetail(contentId = "c1", type = "movie", title = "Cached"))
        val result = repo(HttpStatusCode.NotFound, "{}", cache).getItemDetail("c1")
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun seasonsServesCacheOffline() = runTest {
        val cache = FakeCache(seasonsPreset = SeasonsResponse(seasons = emptyList()))
        val result = repo(HttpStatusCode.ServiceUnavailable, "{}", cache).getSeasons("series-1")
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun continueWatchingSeasonPrefetchUsesCachedNavigationWithoutNetwork() = runTest {
        val cache = FakeCache(
            seasonsPreset = SeasonsResponse(seasons = emptyList()),
            episodesPreset = EpisodesResponse(episodes = emptyList()),
        )
        val repository = repoThatFailsOnNetwork(cache)

        assertTrue(repository.getSeasonsForPrefetch("series-1") is ApiResult.Success)
        assertTrue(repository.getEpisodesForPrefetch("series-1", 3) is ApiResult.Success)
        assertEquals(cache.seasonsPreset, repository.getCachedSeasons("series-1"))
        assertEquals(cache.episodesPreset, repository.getCachedEpisodes("series-1", 3))
    }

    @Test
    fun detailResponseStartedBeforeProfileSwitchIsNotCachedForNewProfile() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    """{"content_id":"c1","type":"movie","title":"Profile A"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val cache = FakeCache()
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val repository = CatalogRepository(
            catalogApi = CatalogApi(client),
            catalogCache = cache,
            identityTransitions = identityTransitions,
        )

        val oldProfileRequest = async { repository.getItemDetail("c1") }
        requestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        releaseResponse.complete(Unit)

        assertTrue(oldProfileRequest.await() is ApiResult.Success)
        assertEquals(null, cache.cachedId)
    }

    @Test
    fun profileSwitchBetweenRepositoryGuardAndCacheWriteDoesNotCacheOldDetail() = runTest {
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val cache = FakeCache(
            identityTransitions = identityTransitions,
            beforeItemCache = {
                identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
            },
        )
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"content_id":"c1","type":"movie","title":"Profile A"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val repository = CatalogRepository(
            catalogApi = CatalogApi(client),
            catalogCache = cache,
            identityTransitions = identityTransitions,
        )

        assertTrue(repository.getItemDetail("c1") is ApiResult.Success)
        assertEquals(null, cache.cachedId)
    }
}
