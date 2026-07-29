package org.siloserver.silo.repository

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.port.CatalogCachePort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Locks the repo-level cache-with-fallback contract on [SectionRepository.getLibrarySections]:
 * cache on success, serve cached on NetworkError/5xx, never on 4xx.
 */
class SectionRepositoryCacheTest {

    private class FakeCache(val preset: List<ResolvedSection>?) : CatalogCachePort {
        var cachedFor: Int? = null
        var cachedSections: List<ResolvedSection>? = null
        override suspend fun cacheLibrarySections(libraryId: Int, sections: List<ResolvedSection>) {
            cachedFor = libraryId; cachedSections = sections
        }
        override suspend fun getCachedLibrarySections(libraryId: Int): List<ResolvedSection>? = preset
    }

    private fun repo(status: HttpStatusCode, body: String, cache: CatalogCachePort): SectionRepository {
        val client = HttpClient(
            MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return SectionRepository(SectionApi(client), cache)
    }

    private fun gatedRepository(
        homeRequestDispatcher: CoroutineDispatcher,
        requestEntered: CompletableDeferred<Unit>,
        releaseResponse: CompletableDeferred<Unit>,
        body: () -> String,
        onRequest: () -> Unit = {},
        identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    ): SectionRepository {
        val client = HttpClient(
            MockEngine {
                onRequest()
                val responseBody = body()
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    responseBody,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return SectionRepository(
            sectionApi = SectionApi(client),
            identityTransitions = identityTransitions,
            homeRequestDispatcher = homeRequestDispatcher,
        )
    }

    private fun section(id: String) = ResolvedSection(id = id, sectionType = id, title = id)

    @Test
    fun cachesOnSuccess() = runTest {
        val cache = FakeCache(preset = null)
        val result = repo(HttpStatusCode.OK, """{"sections":[{"id":"s","section_type":"s","title":"s"}]}""", cache)
            .getLibrarySections(7)
        assertTrue(result is ApiResult.Success)
        assertEquals(7, cache.cachedFor)
        assertEquals("s", cache.cachedSections?.first()?.id)
    }

    @Test
    fun servesCacheOnServer5xx() = runTest {
        val cache = FakeCache(preset = listOf(section("cached")))
        val result = repo(HttpStatusCode.ServiceUnavailable, "{}", cache).getLibrarySections(7)
        assertTrue(result is ApiResult.Success)
        assertEquals("cached", result.data.sections.first().id)
    }

    @Test
    fun doesNotServeCacheOn4xx() = runTest {
        val cache = FakeCache(preset = listOf(section("cached")))
        val result = repo(HttpStatusCode.NotFound, "{}", cache).getLibrarySections(7)
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun concurrentHomeRequestsShareOneAggregateCall() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = gatedRepository(
            homeRequestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = entered,
            releaseResponse = release,
            body = { """{"sections":[]}""" },
            onRequest = { calls += 1 },
        )

        val requests = listOf(
            async { repository.getHomeSections() },
            async { repository.getHomeSections() },
        )
        entered.await()
        repeat(10) { yield() }
        release.complete(Unit)
        requests.awaitAll()

        assertEquals(1, calls)
    }

    @Test
    fun concurrentHomeItemRequestsShareOneCallPerSection() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = gatedRepository(
            homeRequestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = entered,
            releaseResponse = release,
            body = { """{"items":[],"total":0}""" },
            onRequest = { calls += 1 },
        )

        val requests = listOf(
            async { repository.getHomeSectionItems("same") },
            async { repository.getHomeSectionItems("same") },
        )
        entered.await()
        repeat(10) { yield() }
        release.complete(Unit)
        requests.awaitAll()

        assertEquals(1, calls)
    }

    @Test
    fun cancelingFirstHomeCallerDoesNotCancelSharedRequestOrPoisonNextCall() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = gatedRepository(
            homeRequestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = entered,
            releaseResponse = release,
            body = { """{"sections":[]}""" },
            onRequest = { calls += 1 },
        )

        val firstCaller = launch { repository.getHomeSections() }
        entered.await()
        val survivingCaller = async { repository.getHomeSections() }
        repeat(10) { yield() }
        firstCaller.cancelAndJoin()
        release.complete(Unit)

        assertTrue(survivingCaller.await() is ApiResult.Success)
        assertTrue(repository.getHomeSections() is ApiResult.Success)
        assertEquals(2, calls)
    }

    @Test
    fun homeRequestStartedBeforeProfileSwitchDoesNotServeOldProfileSectionsToNewProfile() = runTest {
        var calls = 0
        val oldRequestEntered = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val repository = gatedRepository(
            homeRequestDispatcher = StandardTestDispatcher(testScheduler),
            requestEntered = oldRequestEntered,
            releaseResponse = releaseOldRequest,
            body = {
                if (calls == 1) {
                    """{"sections":[{"id":"old","section_type":"old","title":"Old"}]}"""
                } else {
                    """{"sections":[{"id":"new","section_type":"new","title":"New"}]}"""
                }
            },
            onRequest = { calls += 1 },
            identityTransitions = identityTransitions,
        )

        val oldProfileRequest = async { repository.getHomeSections() }
        oldRequestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        val newProfileRequest = async { repository.getHomeSections() }

        releaseOldRequest.complete(Unit)
        val oldProfileResult = oldProfileRequest.await()
        val newProfileResult = newProfileRequest.await()

        assertEquals("Old", (oldProfileResult as ApiResult.Success).data.sections.single().title)
        assertEquals("New", (newProfileResult as ApiResult.Success).data.sections.single().title)
        assertEquals(2, calls)
    }

    @Test
    fun librarySectionsStartedBeforeProfileSwitchAreNotCachedForNewProfile() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestEntered.complete(Unit)
                releaseResponse.await()
                respond(
                    """{"sections":[{"id":"old","section_type":"old","title":"Profile A"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val cache = FakeCache(preset = null)
        val identityTransitions = DefaultIdentityTransitionBarrier()
        val repository = SectionRepository(
            sectionApi = SectionApi(client),
            catalogCache = cache,
            identityTransitions = identityTransitions,
        )

        val oldProfileRequest = async { repository.getLibrarySections(7) }
        requestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        releaseResponse.complete(Unit)

        assertTrue(oldProfileRequest.await() is ApiResult.Success)
        assertEquals(null, cache.cachedFor)
        assertEquals(null, cache.cachedSections)
    }
}
