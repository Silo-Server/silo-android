package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CollectionApi
import org.siloserver.silo.network.api.CollectionEditor
import org.siloserver.silo.network.api.CollectionOrder
import kotlin.test.*

class CollectionsV2Test {
    @Test fun listUsesItemsEnvelope() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/collections", request.url.encodedPath)
            respond("""{"items":[{"id":"c1","name":"Films"}],"groups":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("c1", assertIs<ApiResult.Success<org.siloserver.silo.model.personal.CollectionsResponse>>(CollectionApi(client, ApiV2Gate.Unrestricted).listCollections()).data.collections.single().id)
        client.close()
    }

    @Test fun canonicalReadAndConflictKeepOriginalTagWithoutReplay() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/v2/collections/c1", request.url.encodedPath)
            if (request.method == HttpMethod.Get) {
                respond("""{"id":"c1","name":"Films"}""", headers = headersOf(HttpHeaders.ETag to listOf("\"original\""), HttpHeaders.ContentType to listOf("application/json")))
            } else {
                assertEquals(HttpMethod.Delete, request.method)
                assertEquals("\"original\"", request.headers[HttpHeaders.IfMatch])
                respond("""{"type":"https://siloserver.org/docs/api/v2/problems/precondition_failed","title":"Changed","status":412,"instance":"urn:test","code":"precondition_failed","detail":"Changed"}""", HttpStatusCode.PreconditionFailed, headersOf(HttpHeaders.ETag to listOf("\"new\""), HttpHeaders.ContentType to listOf("application/problem+json")))
            }
        })
        val api = CollectionApi(client, ApiV2Gate.Unrestricted)
        val editor = assertIs<ApiResult.Success<CollectionEditor<Collection>>>(api.getCollection("c1")).data
        assertEquals(412, assertIs<ApiResult.Error>(api.deleteCollection("c1", editor)).code)
        assertEquals("\"original\"", editor.etag)
        assertEquals(2, calls)
        client.close()
    }

    @Test fun movePreservesExplicitNullAndMembershipHasPositionBody() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            if (request.method == HttpMethod.Patch) {
                assertEquals("/api/v2/collections/c1", request.url.encodedPath)
                assertEquals("\"tag\"", request.headers[HttpHeaders.IfMatch])
                assertEquals("{\"group_id\":null}", (request.body as TextContent).text)
                respond("""{"id":"c1","name":"Films"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertEquals("/api/v2/collections/c1/items/m1", request.url.encodedPath)
                assertEquals("{\"position\":0}", (request.body as TextContent).text)
                respond("", HttpStatusCode.NoContent)
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val api = CollectionApi(client, ApiV2Gate.Unrestricted)
        val editor = CollectionEditor(Collection("c1", name = "Films"), "\"tag\"", null)
        assertIs<ApiResult.Success<*>>(api.moveCollectionToGroup("c1", null, editor))
        assertIs<ApiResult.Success<Unit>>(api.addItem("c1", "m1"))
        assertEquals(2, calls)
        client.close()
    }

    @Test fun refusesMissingValidatorAndOversizedOrder() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"id":"c1","name":"Films"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = CollectionApi(client, ApiV2Gate.Unrestricted)
        assertEquals("missing_etag", assertIs<ApiResult.Error>(api.getCollection("c1")).error)
        assertIs<ApiResult.NetworkError>(api.reorderItems("c1", emptyList(), CollectionEditor(CollectionOrder(emptyList(), hasMore = true), "\"tag\"", null)))
        assertEquals(1, calls)
        client.close()
    }
    @Test fun rejectsLostViewerBeforeGuardedWrite() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("", HttpStatusCode.NoContent) })
        val scope = org.siloserver.silo.network.AuthScopeSnapshot("server", "profile", "https://example.invalid", null)
        val editor = CollectionEditor(Collection("c1", name = "Films"), "\"tag\"", scope)
        assertEquals("identity_changed", assertIs<ApiResult.Error>(CollectionApi(client, ApiV2Gate.Unrestricted).deleteCollection("c1", editor)).error)
        assertEquals(0, calls)
        client.close()
    }

    @Test fun malformedListDoesNotBecomeEmptySuccess() = runTest {
        val client = HttpClient(MockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        assertIs<ApiResult.NetworkError>(CollectionApi(client, ApiV2Gate.Unrestricted).listCollections())
        client.close()
    }

    @Test fun personalBrowseFollowsOpaqueCursorWithoutOffsetOrSort() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog", request.url.encodedPath)
            assertEquals("user_collection", request.url.parameters["source"])
            assertEquals("c1", request.url.parameters["collection_id"])
            assertNull(request.url.parameters["library_id"])
            assertEquals("40", request.url.parameters["limit"])
            assertNull(request.url.parameters["offset"])
            assertNull(request.url.parameters["sort"])
            assertEquals(if (calls++ == 0) null else "opaque", request.url.parameters["cursor"])
            respond(if (calls == 1) """{"items":[],"page":{"has_more":true,"next_cursor":"opaque"},"total":2}"""
                else """{"items":[],"page":{"has_more":false},"total":2}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = CollectionApi(client, ApiV2Gate.Unrestricted)
        val first = assertIs<ApiResult.Success<org.siloserver.silo.network.api.CollectionItemsPage>>(api.getCollectionItems("c1")).data
        assertTrue(first.catalog.hasMore)
        val second = assertIs<ApiResult.Success<org.siloserver.silo.network.api.CollectionItemsPage>>(api.getCollectionItems("c1", first.continuation)).data
        assertNull(second.continuation)
        assertEquals(2, calls)
        client.close()
    }

    @Test fun libraryScopedPersonalBrowseKeepsScopeAcrossOpaqueCursor() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("7", request.url.parameters["library_id"])
            assertEquals(if (calls++ == 0) null else "opaque", request.url.parameters["cursor"])
            respond(if (calls == 1) """{"items":[],"page":{"has_more":true,"next_cursor":"opaque"},"total":2}"""
                else """{"items":[],"page":{"has_more":false},"total":2}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = CollectionApi(client, ApiV2Gate.Unrestricted)
        val first = assertIs<ApiResult.Success<org.siloserver.silo.network.api.CollectionItemsPage>>(
            api.getCollectionItems("c1", libraryId = 7),
        ).data
        assertEquals(7, first.continuation?.libraryId)
        val mismatched = api.getCollectionItems("c1", continuation = first.continuation, libraryId = 8)
        assertEquals("invalid_cursor", assertIs<ApiResult.Error>(mismatched).error)
        assertEquals(1, calls)
        val second = assertIs<ApiResult.Success<org.siloserver.silo.network.api.CollectionItemsPage>>(
            api.getCollectionItems("c1", continuation = first.continuation, libraryId = 7),
        ).data
        assertNull(second.continuation)
        assertEquals(2, calls)
        client.close()
    }

    @Test fun invalidBrowseCursorNeverRestartsAutomatically() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"type":"https://siloserver.org/docs/api/v2/problems/invalid_cursor","title":"Invalid cursor","status":400,"instance":"urn:test","code":"invalid_cursor","detail":"Expired"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        })
        val result = CollectionApi(client, ApiV2Gate.Unrestricted).getCollectionItems("c1", org.siloserver.silo.network.api.CollectionContinuation("old", "c1", 40, null))
        assertEquals("invalid_cursor", assertIs<ApiResult.Error>(result).error)
        assertEquals(1, calls)
        client.close()
    }

}
