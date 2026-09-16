package org.siloserver.silo.repository

import org.siloserver.silo.network.apiv2.ApiV2Gate

import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.LibrarySectionItemsV2Api
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the repo-level cache-with-fallback contract on [SectionRepository.getLibrarySections]:
 * cache on success, serve cached on NetworkError/5xx, never on 4xx.
 */
class SectionRepositoryCacheTest {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }


    private class FakeCache(val preset: List<ResolvedSection>?) : CatalogCachePort {
        var cachedFor: Int? = null
        var cachedSections: List<ResolvedSection>? = null
        override suspend fun cacheLibrarySectionsV2(libraryId: Int, sections: List<ResolvedSection>, owner: AuthScopeSnapshot) {
            cachedFor = libraryId; cachedSections = sections
        }
        override suspend fun getCachedLibrarySectionsV2(libraryId: Int, owner: AuthScopeSnapshot): List<ResolvedSection>? = preset
    }

    private fun repo(status: HttpStatusCode, body: String, cache: CatalogCachePort): SectionRepository {
        val client = HttpClient(
            MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)), cache)
    }

    private fun section(id: String) = ResolvedSection(id = id, sectionType = id, title = id)

    @Test
    fun cachesOnSuccess() = runTest {
        val cache = FakeCache(preset = null)
        val result = repo(HttpStatusCode.OK, """{"sections":[{"id":"s","section_type":"s","title":"s","items":[]}]}""", cache)
            .getLibrarySections(7, owner)
        assertTrue(result is ApiResult.Success)
        assertEquals(7, cache.cachedFor)
        assertEquals("s", cache.cachedSections?.first()?.id)
    }

    @Test
    fun servesCacheOnServer5xx() = runTest {
        val cache = FakeCache(preset = listOf(section("cached")))
        val result = repo(HttpStatusCode.ServiceUnavailable, "{}", cache).getLibrarySections(7, owner)
        assertTrue(result is ApiResult.Success)
        assertEquals("cached", result.data.sections.first().id)
    }

    @Test
    fun doesNotServeCacheOn4xx() = runTest {
        val cache = FakeCache(preset = listOf(section("cached")))
        val result = repo(HttpStatusCode.NotFound, "{}", cache).getLibrarySections(7, owner)
        assertTrue(result is ApiResult.Error)
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
                    """{"sections":[{"id":"old","section_type":"old","title":"Profile A","items":[]}]}""",
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
            sectionApi = SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)),
            catalogCache = cache,
        )

        val oldProfileRequest = async { repository.getLibrarySections(7, owner) }
        requestEntered.await()
        identityTransitions.changing(IdentityTransitionKind.PROFILE_SWITCH) { }
        owner = owner.copy(profileToken = "new")
        releaseResponse.complete(Unit)

        assertTrue(oldProfileRequest.await() is ApiResult.Error)
        assertEquals(null, cache.cachedFor)
        assertEquals(null, cache.cachedSections)
    }
}
