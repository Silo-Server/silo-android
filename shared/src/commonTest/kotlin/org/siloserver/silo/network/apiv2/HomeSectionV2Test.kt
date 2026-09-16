package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class HomeSectionV2Test {
    @Test fun exactSectionIdentityAndShapeRequired() = runTest {
        val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
        val valid = """{"id":"row/a","section_type":"next_up","title":"Next","items":[]}"""
        var body = valid; var status = HttpStatusCode.OK
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/home/sections/row%2Fa/items", it.url.encodedPath)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            assertEquals("row/a", api.section("row/a", owner).getOrThrow().section?.id)
            for (bad in listOf("{}", valid.replace("row/a", "wrong"), valid.replace("\"items\"", "\"missing\""))) {
                body = bad; assertFalse(api.section("row/a", owner) is ApiResult.Success)
            }
            body = valid; status = HttpStatusCode.Accepted; assertFalse(api.section("row/a", owner) is ApiResult.Success)
        } finally { client.close() }
    }
}
