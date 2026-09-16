package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class LibrarySectionItemsV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val body = """{"id":"row/a","section_type":"custom","title":"Row","total_count":1,"items":[{"content_id":"movie:a","type":"movie","title":"A","position_seconds":12.5}]}"""
    @Test fun exactScopedBareSectionAndEmpty() = runTest {
        var reply = body
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/library/3/sections/row%2Fa/items", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val result = api.read(3, "row/a", owner).getOrThrow()
            assertEquals("row/a", result.section?.id); assertEquals(12.5, result.items.single().positionSeconds)
            reply = """{"id":"row/a","section_type":"custom","title":"Row","items":[]}"""
            assertTrue(api.read(3, "row/a", owner).getOrThrow().items.isEmpty())
        } finally { client.close() }
    }
    @Test fun wrongIdentityShapeStatusAndLateOwnerRefuse() = runTest {
        var reply = body; var status = HttpStatusCode.OK; var late = false; var sends = 0
        val client = HttpClient(MockEngine {
            sends++; if (late) owner = owner.copy(profileToken = "new")
            respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted); val original = owner
            for (bad in listOf("{}", body.replace("row/a", "other"), body.replace("\"movie:a\"", "\"\""), body.replace("\"items\"", "\"missing\""))) {
                reply = bad; assertFalse(api.read(3, "row/a", original) is ApiResult.Success)
            }
            reply = body; status = HttpStatusCode.Accepted; assertFalse(api.read(3, "row/a", original) is ApiResult.Success)
            status = HttpStatusCode.OK; late = true; assertIs<ApiResult.Error>(api.read(3, "row/a", original))
            val sent = sends; assertIs<ApiResult.Error>(api.read(3, "row/a", original)); assertEquals(sent, sends)
        } finally { client.close() }
    }
}
