package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.CatalogCachePort
import kotlin.test.*

class LibrarySectionListV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val good = """{"sections":[{"id":"second","section_type":"custom","title":"Second","items":[{"content_id":"movie:2","type":"movie","title":"Two"}]},{"id":"first","section_type":"custom","title":"First","items":[]}]}"""
    @Test fun exactWireOrderedEmptyAndStrictFailures() = runTest {
        var body = good; var status = HttpStatusCode.OK
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/library/7/sections", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            assertEquals(listOf("second", "first"), api.list(7, owner).getOrThrow().sections.map { it.id })
            for (bad in listOf("{}", "not-json", good.replace("\"movie:2\"", "\"\""), good.replace("\"items\"", "\"missing\""))) {
                body = bad; assertIs<ApiResult.Error>(api.list(7, owner))
            }
            body = good; status = HttpStatusCode.Accepted; assertIs<ApiResult.Error>(api.list(7, owner))
            status = HttpStatusCode.OK; body = """{"sections":[]}"""; assertTrue(api.list(7, owner).getOrThrow().sections.isEmpty())
        } finally { client.close() }
    }
    @Test fun cachedFallbackIsOriginalOnlyAndNeverMasksMalformedResponse() = runTest {
        var body = good; var status = HttpStatusCode.OK; var offline = false
        var cacheReads = 0; var changeOnCache = false; var cached: List<ResolvedSection>? = null
        val original = owner
        val cache = object : CatalogCachePort {
            override suspend fun cacheLibrarySectionsV2(libraryId: Int, sections: List<ResolvedSection>, owner: AuthScopeSnapshot) { assertEquals(original, owner); cached = sections }
            override suspend fun getCachedLibrarySectionsV2(libraryId: Int, owner: AuthScopeSnapshot): List<ResolvedSection>? {
                assertEquals(original, owner); cacheReads++
                if (changeOnCache) this@LibrarySectionListV2Test.owner = owner.copy(profileToken = "new")
                return cached
            }
        }
        val client = HttpClient(MockEngine {
            if (offline) throw java.io.IOException("offline")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val repo = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)), cache)
            assertIs<ApiResult.Success<*>>(repo.getLibrarySections(7, original))
            body = "not-json"; assertIs<ApiResult.Error>(repo.getLibrarySections(7, original)); assertEquals(0, cacheReads)
            status = HttpStatusCode.Forbidden; assertIs<ApiResult.Error>(repo.getLibrarySections(7, original)); assertEquals(0, cacheReads)
            offline = true; assertIs<ApiResult.Success<*>>(repo.getLibrarySections(7, original)); assertEquals(1, cacheReads)
            changeOnCache = true; assertIs<ApiResult.Error>(repo.getLibrarySections(7, original)); assertEquals(2, cacheReads)
            assertIs<ApiResult.Error>(repo.getLibrarySections(7, original)); assertEquals(2, cacheReads)
        } finally { client.close() }
    }
}
