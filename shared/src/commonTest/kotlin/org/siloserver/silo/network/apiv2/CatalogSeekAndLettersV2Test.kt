package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlin.test.*

/** The letter jump's two reads: a page by list position, and the letter index. */
class CatalogSeekAndLettersV2Test {
    private fun page(next: String? = null) = buildJsonObject {
        putJsonArray("items") { addJsonObject { put("content_id", "m1"); put("type", "movie"); put("title", "Film") } }
        putJsonObject("page") {
            put("has_more", next != null)
            next?.let { put("next_cursor", it) }
        }
        put("total", 500)
        put("total_exact", true)
        put("window_cursor", "window")
    }.toString()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun seekStartsAPageAndItsContinuationNeverResendsIt() = runTest {
        val requests = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url
            val next = if (request.url.parameters["cursor"] == null) "next" else null
            respond(page(next), headers = jsonHeaders)
        })
        val api = CatalogV2Api(client, ApiV2Gate.Unrestricted)
        val query = CatalogQueryV2(libraryId = "7", sort = "title", order = "asc", limit = 100)
        val seeked = assertIs<ApiResult.Success<CatalogPageV2<*>>>(api.browse(query, seek = 300)).data
        assertEquals("300", requests.last().parameters["seek"])
        // Its continuation carries the position; sending seek again would repeat the page.
        assertIs<ApiResult.Success<*>>(api.browse(query, seeked.continuation))
        assertNull(requests.last().parameters["seek"])
        assertEquals("next", requests.last().parameters["cursor"])
        // A seek of zero is the first page; a seek never continues a listing.
        api.browse(query, seek = 0)
        assertNull(requests.last().parameters["seek"])
        assertEquals("invalid_cursor", assertIs<ApiResult.Error>(api.browse(query, seeked.continuation, seek = 100)).error)
        assertEquals(3, requests.size)
        client.close()
    }

    @Test fun aWindowCursorRidesWithASeekOnBothForms() = runTest {
        val requests = mutableListOf<Pair<Url, JsonObject?>>()
        val client = HttpClient(MockEngine { request ->
            val body = (request.body as? TextContent)?.let { SiloJson.parseToJsonElement(it.text).jsonObject }
            requests += request.url to body
            respond(page(), headers = jsonHeaders)
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val api = CatalogV2Api(client, ApiV2Gate.Unrestricted)
        val query = CatalogQueryV2(libraryId = "7", sort = "title", limit = 100)
        api.browse(query, seek = 400, windowCursor = "window")
        assertEquals("window", requests.last().first.parameters["cursor"])
        assertEquals("400", requests.last().first.parameters["seek"])
        // Position zero is the first page itself; the cursor goes only with a seek.
        api.browse(query, seek = 0, windowCursor = "window")
        assertNull(requests.last().first.parameters["cursor"])
        val groups = listOf(CatalogRuleGroupV2("all", listOf(CatalogRuleV2("genre", "contains", JsonPrimitive("Drama")))))
        api.browse(query.copy(groups = groups), seek = 400, windowCursor = "window")
        assertEquals("window", requests.last().second?.get("cursor")?.jsonPrimitive?.content)
        assertEquals(400, requests.last().second?.get("seek")?.jsonPrimitive?.int)
        client.close()
    }

    @Test fun seekRidesTheStructuredQueryBody() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog/query", request.url.encodedPath)
            val json = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(200, json["seek"]?.jsonPrimitive?.int)
            assertNull(request.url.parameters["seek"])
            respond(page(), headers = jsonHeaders)
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val groups = listOf(CatalogRuleGroupV2("all", listOf(CatalogRuleV2("genre", "contains", JsonPrimitive("Drama")))))
        assertIs<ApiResult.Success<*>>(
            CatalogV2Api(client, ApiV2Gate.Unrestricted)
                .browse(CatalogQueryV2(libraryId = "7", groups = groups, sort = "title"), seek = 200),
        )
        client.close()
    }

    @Test fun lettersPostTheScopeAndDecodePositions() = runTest {
        val body = buildJsonObject {
            put("total", 250)
            putJsonArray("letters") {
                addJsonObject { put("letter", "#"); put("count", 10); put("position", 0) }
                addJsonObject { put("letter", "A"); put("count", 200); put("position", 10) }
                addJsonObject { put("letter", "Z"); put("count", 40); put("position", 210) }
            }
            putJsonArray("runs") {
                addJsonObject { put("letter", "#"); put("position", 0) }
                addJsonObject { put("letter", "A"); put("position", 10) }
                addJsonObject { put("letter", "Z"); put("position", 210) }
                // Accented A titles the collation sorts after Z.
                addJsonObject { put("letter", "A"); put("position", 245) }
            }
        }.toString()
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v2/catalog/letters", request.url.encodedPath)
            val json = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("7", json["library_id"]?.jsonPrimitive?.content)
            assertEquals("movie", json["type"]?.jsonPrimitive?.content)
            assertEquals("desc", json["order"]?.jsonPrimitive?.content)
            assertNull(json["match"])
            respond(body, headers = jsonHeaders)
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val letters = assertNotNull(
            assertIs<ApiResult.Success<CatalogLettersV2?>>(
                CatalogV2Api(client, ApiV2Gate.Unrestricted)
                    .letters(CatalogLettersQueryV2(libraryId = "7", type = "movie", order = "desc")),
            ).data,
        )
        assertEquals(250, letters.total)
        assertEquals(10, letters.position("A"))
        assertNull(letters.position("B"))
        // A position takes its letter from the runs, which a collation can split.
        assertEquals("#", letters.letterAt(9))
        assertEquals("Z", letters.letterAt(244))
        assertEquals("A", letters.letterAt(249))
        client.close()
    }

    @Test fun lettersFromAServerWithoutThemAreNullAndOtherFailuresStayErrors() = runTest {
        for (status in listOf(404, 405, 501)) {
            val client = HttpClient(MockEngine { respond("", HttpStatusCode.fromValue(status)) }) { install(ContentNegotiation) { json(SiloJson) } }
            val result = CatalogV2Api(client, ApiV2Gate.Unrestricted).letters(CatalogLettersQueryV2(libraryId = "1"))
            assertNull(assertIs<ApiResult.Success<CatalogLettersV2?>>(result, "status $status").data)
            client.close()
        }
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }) { install(ContentNegotiation) { json(SiloJson) } }
        val result = CatalogV2Api(client, ApiV2Gate.Unrestricted).letters(CatalogLettersQueryV2())
        assertEquals(500, assertIs<ApiResult.Error>(result).code)
        client.close()
    }
}
