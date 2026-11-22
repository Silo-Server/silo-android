package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArtworkWidthsV2Test {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }
    private val hero = ArtworkWidths(poster = 176, backdrop = 1344, logo = 880)

    private fun Parameters.widths() = listOf("poster_width", "backdrop_width", "logo_width", "still_width", "photo_width")
        .mapNotNull { name -> get(name)?.let { name to it } }.toMap()

    @Test
    fun everyArtworkReadSendsItsWidths() = runTest {
        val sent = mutableMapOf<String, Map<String, String>>()
        val client = HttpClient(MockEngine { request ->
            sent[request.url.encodedPath] = request.url.parameters.widths()
            val body = when {
                request.url.encodedPath.endsWith("/items") ->
                    """{"id":"row","section_type":"custom","title":"Row","items":[]}"""
                request.url.encodedPath.endsWith("/sections") -> """{"sections":[]}"""
                else -> """{"items":[]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<*>>(home.list(owner, hero))
            assertIs<ApiResult.Success<*>>(home.section("row", owner, hero))
            val library = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<*>>(library.list(7, owner, hero))
            assertIs<ApiResult.Success<*>>(library.read(7, "row", owner, hero))
            assertIs<ApiResult.Success<*>>(DiscoverV2Api(client, tokens, ApiV2Gate.Unrestricted).read(owner, hero))
            assertIs<ApiResult.Success<*>>(SimilarCardsV2Api(client, tokens, ApiV2Gate.Unrestricted).list("m", 12, owner, ArtworkWidths(poster = 260)))
            val catalog = CatalogV2Api(client, ApiV2Gate.Unrestricted)
            catalog.itemDetail("m", widths = ArtworkWidths(backdrop = 1344, logo = 650, photo = 200))
            catalog.seasonEpisodes("s", 1, widths = ArtworkWidths(still = 360))
        } finally {
            client.close()
        }
        val heroParams = mapOf("poster_width" to "176", "backdrop_width" to "1344", "logo_width" to "880")
        assertEquals(heroParams, sent["/api/v2/home/sections"])
        assertEquals(heroParams, sent["/api/v2/home/sections/row/items"])
        assertEquals(heroParams, sent["/api/v2/library/7/sections"])
        assertEquals(heroParams, sent["/api/v2/library/7/sections/row/items"])
        assertEquals(heroParams, sent["/api/v2/recommendations/discover"])
        assertEquals(mapOf("poster_width" to "260"), sent["/api/v2/recommendations/similar/m"])
        assertEquals(mapOf("backdrop_width" to "1344", "logo_width" to "650", "photo_width" to "200"), sent["/api/v2/catalog/items/m"])
        assertEquals(mapOf("still_width" to "360"), sent["/api/v2/catalog/series/s/seasons/1/episodes"])
    }

    @Test
    fun aListingKeepsTheWidthsOfItsFirstPage() = runTest {
        val sent = mutableListOf<Map<String, String>>()
        val client = HttpClient(MockEngine { request ->
            sent += request.url.parameters.widths()
            val page = if (request.url.parameters["cursor"] == null) """{"has_more":true,"next_cursor":"c1"}""" else """{"has_more":false}"""
            respond(
                """{"items":[],"page":$page,"total":0,"total_exact":true,"window_cursor":"w"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            val api = CatalogV2Api(client, ApiV2Gate.Unrestricted)
            val query = CatalogQueryV2(libraryId = "7")
            val first = assertIs<ApiResult.Success<CatalogPageV2<*>>>(api.browse(query, widths = ArtworkWidths(poster = 312))).data
            // The screen's widths changed (a fold, say); the cursor stays bound to the first page's.
            assertIs<ApiResult.Success<*>>(api.browse(query, first.continuation, widths = ArtworkWidths(poster = 600)))
        } finally {
            client.close()
        }
        assertEquals(listOf(mapOf("poster_width" to "312"), mapOf("poster_width" to "312")), sent)
    }
}
