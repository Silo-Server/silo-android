package org.siloserver.silo.common.startup

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import kotlin.test.*

class StartupHomeV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private var unresolved = false
    private var fail = false
    private var fallbackReads = 0
    private var writes = 0
    private var artwork = 0
    private var current = true
    private var listHook: () -> Unit = {}
    private var cacheHook: () -> Unit = {}
    private val cache = object : HomeCachePort {
        override suspend fun cacheHomeV2IfAbsent(sections: List<ResolvedSection>, owner: AuthScopeSnapshot, stillCurrent: () -> Boolean) {
            assertEquals(this@StartupHomeV2Test.owner, owner); assertTrue(stillCurrent()); writes++; cacheHook()
        }
    }
    private suspend fun scenario(block: suspend (SectionRepository) -> Unit) {
        val client = HttpClient(MockEngine {
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            val fallback = it.url.encodedPath.endsWith("/items")
            val row = """{"id":"row","section_type":"continue_watching","title":"Row","items":[{"content_id":"movie:a","type":"movie","title":"A"}]}"""
            val body = if (fallback) { fallbackReads++; row } else {
                assertEquals("/api/v2/home/sections", it.url.encodedPath); listHook()
                if (unresolved) """{"sections":[{"id":"row","section_type":"continue_watching","title":"Row","total_count":1,"items":[]}]}"""
                else """{"sections":[$row]}"""
            }
            respond(body, if (fallback && fail) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { block(SectionRepository(SectionApi(client, home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)))) } finally { client.close() }
    }
    private suspend fun warm(repo: SectionRepository) = warmStartupHomeSections(repo, cache, owner, { current }) { rows, mayWarm ->
        assertTrue(mayWarm()); assertEquals("movie:a", rows.single().items.single().contentId); artwork++
    }
    @Test fun completeOnlyCacheAndArtworkWithoutPopulatedFanout() = runTest { scenario { repo ->
        warm(repo); assertEquals(0, fallbackReads); assertEquals(1, writes); assertEquals(1, artwork)
        unresolved = true; fail = true; warm(repo)
        assertEquals(1, fallbackReads); assertEquals(1, writes); assertEquals(1, artwork)
        fail = false; warm(repo); assertEquals(2, fallbackReads); assertEquals(2, writes); assertEquals(2, artwork)
    } }
    @Test fun replacementOwnerAndSupersededGenerationCannotWarm() = runTest { scenario { repo ->
        listHook = { owner = owner.copy(profileToken = "new") }; warm(repo)
        assertEquals(0, writes); assertEquals(0, artwork)
        listHook = { current = false }; warm(repo); assertEquals(0, writes); assertEquals(0, artwork)
    } }
    @Test fun lateCacheAuthorityAndPerImageCheckRefuse() = runTest { scenario { repo ->
        cacheHook = { owner = owner.copy(credentialEpoch = 2) }; warm(repo)
        assertEquals(1, writes); assertEquals(0, artwork)
        cacheHook = {}
        warmStartupHomeSections(repo, cache, owner, { current }) { _, mayWarm ->
            assertTrue(mayWarm()); owner = owner.copy(profileToken = "replacement"); assertFalse(mayWarm())
        }
    } }
}
