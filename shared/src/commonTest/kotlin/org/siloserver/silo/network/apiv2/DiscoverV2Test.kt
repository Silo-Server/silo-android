package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class DiscoverV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val valid = """{"items":[{"type":"movie","title":"For You","kind":"for-you-main","key":"stable","items":[{"content_id":"opaque:2","type":"movie","title":"Second","poster_url":"poster","year":2025},{"content_id":"1","type":"movie","title":"First"}]}]}"""
    @Test fun scopedOrderedRowsAndEmptyCollection() = runTest {
        var body = valid
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/recommendations/discover", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = DiscoverV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val row = api.read(owner).getOrThrow().rows.single()
            assertEquals("For You", row.label); assertEquals("for-you-main", row.sectionKind); assertEquals("stable", row.sectionKey)
            assertEquals(listOf("opaque:2", "1"), row.items.map { it.contentId })
            assertEquals("poster", row.items.first().posterUrl); assertEquals(2025, row.items.first().year)
            body = """{"items":[]}"""
            assertTrue(api.read(owner).getOrThrow().rows.isEmpty())
        } finally { client.close() }
    }
    @Test fun malformedIncompleteAndLateAuthorityRefuse() = runTest {
        var body = valid; var status = HttpStatusCode.OK; var late = false; var sends = 0
        val client = HttpClient(MockEngine {
            sends++; if (late) owner = owner.copy(profileToken = "replacement")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = DiscoverV2Api(client, tokens, ApiV2Gate.Unrestricted); val original = owner
            for (invalid in listOf("{}", valid.dropLast(1) + ""","page":{"has_more":true,"next_cursor":"c"}}""", valid.replace("\"opaque:2\"", "\"\""), valid.replace("\"items\":[{\"content_id\"", "\"missing\":[{\"content_id\""))) {
                body = invalid; assertFalse(api.read(original) is ApiResult.Success)
            }
            body = valid; status = HttpStatusCode.Accepted; assertFalse(api.read(original) is ApiResult.Success)
            status = HttpStatusCode.OK; late = true; assertIs<ApiResult.Error>(api.read(original))
            val sent = sends; assertIs<ApiResult.Error>(api.read(original)); assertEquals(sent, sends)
        } finally { client.close() }
    }
}
