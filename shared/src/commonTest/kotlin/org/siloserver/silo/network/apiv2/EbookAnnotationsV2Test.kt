package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.ebook.EbookAnnotation
import org.siloserver.silo.model.ebook.EbookAnnotationListResponse
import org.siloserver.silo.network.*
import kotlin.test.*

class EbookAnnotationsV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun row(id: String = "one", content: String = "book", tag: String = "tag") =
        """{"id":"$id","content_id":"$content","kind":"bookmark","location":"page:2","etag":"$tag"}"""
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun drainsAllPagesAndRejectsRepeatedContinuationWithoutPartialPublication() = runTest {
        var calls = 0
        var repeated = false
        val c = client {
            assertEquals("50", it.url.parameters["limit"])
            calls++
            if (calls % 2 == 1) reply("""{"items":[${row()}],"page":{"has_more":true,"next_cursor":"opaque"}}""")
            else {
                assertEquals("opaque", it.url.parameters["cursor"])
                reply("""{"items":[${row("two")}],"page":${if (repeated) """{"has_more":true,"next_cursor":"opaque"}""" else """{"has_more":false}"""}}""")
            }
        }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals(listOf("one", "two"), assertIs<ApiResult.Success<EbookAnnotationListResponse>>(api.list("book", scope)).data.items.map { it.id })
            repeated = true
            assertIs<ApiResult.Error>(api.list("book", scope))
            assertEquals(4, calls)
        } finally { c.close() }
    }

    @Test fun retainedCreateIdentityReplaysExactBodyAndReturnsCurrentWinner() = runTest {
        val bodies = mutableListOf<String>()
        val c = client {
            assertEquals(HttpMethod.Post, it.method)
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            bodies += it.body.toByteArray().decodeToString()
            if (bodies.size == 1) reply("{}", HttpStatusCode.ServiceUnavailable)
            else reply(row("retained").replace("page:2", "page:9"))
        }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.createBookmark("book", "retained", "page:2", scope))
            val result = assertIs<ApiResult.Success<EbookAnnotation>>(api.createBookmark("book", "retained", "page:2", scope))
            assertEquals("page:9", result.data.location)
            assertEquals(bodies[0], bodies[1])
            assertEquals(JsonPrimitive("retained"), SiloJson.parseToJsonElement(bodies[0]).jsonObject["id"])
        } finally { c.close() }
    }

    @Test fun guardedPatchPreservesExplicitNullAndDeleteRequires204() = runTest {
        var calls = 0
        val c = client {
            calls++
            assertEquals("old", it.headers[HttpHeaders.IfMatch])
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            if (it.method == HttpMethod.Patch) {
                val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
                assertEquals(JsonNull, body["note"]); assertFalse(body.containsKey("location"))
                reply(row(tag = "new"))
            } else reply("", if (calls == 2) HttpStatusCode.OK else HttpStatusCode.NoContent)
        }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val annotation = EbookAnnotation(id = "one", contentId = "book", kind = "bookmark", etag = "old")
            assertEquals("new", assertIs<ApiResult.Success<EbookAnnotation>>(api.patch("book", annotation, buildJsonObject { put("note", JsonNull) }, scope)).data.etag)
            assertFalse(api.delete("book", annotation, scope) is ApiResult.Success)
            assertIs<ApiResult.Success<Unit>>(api.delete("book", annotation, scope))
            assertIs<ApiResult.Error>(api.delete("other", annotation, scope))
            assertEquals(3, calls)
        } finally { c.close() }
    }

    @Test fun foreignRowsAndChangedIdentityCannotPublish() = runTest {
        var changed = false
        val captured = scope
        val c = client {
            if (changed) scope = scope.copy(identityGeneration = 2)
            reply("""{"items":[${row(content = if (changed) "book" else "other")}],"page":{"has_more":false}}""")
        }
        try {
            val api = EbookReaderV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.list("book", captured))
            changed = true
            assertIs<ApiResult.Error>(api.list("book", captured))
        } finally { c.close() }
    }
}
