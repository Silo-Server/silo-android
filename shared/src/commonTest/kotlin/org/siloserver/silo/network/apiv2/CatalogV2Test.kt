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

class CatalogV2Test {
    private fun body(next: String? = null) = """{"items":[{"content_id":"m1","type":"movie","title":"Film"}],"page":{"has_more":${next != null}${next?.let { ",\"next_cursor\":\"$it\"" } ?: ""}},"total":10,"total_exact":false,"window_cursor":"window"}"""

    @Test fun getSortAndOpaqueContinuationKeepQuery() = runTest {
        var count = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog", request.url.encodedPath)
            assertEquals("-year", request.url.parameters["sort"])
            assertEquals("p1", request.url.parameters["person_id"])
            assertNull(request.url.parameters["order"])
            assertNull(request.url.parameters["offset"])
            assertNull(request.url.parameters["snapshot"])
            assertEquals(if (count++ == 0) null else "next", request.url.parameters["cursor"])
            respond(body(if (count == 1) "next" else null), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = CatalogV2Api(client)
        val query = CatalogQueryV2(source = "person", personId = "p1", sort = "year", order = "desc")
        val first = assertIs<ApiResult.Success<CatalogPageV2<*>>>(api.browse(query)).data
        assertEquals("window", first.windowCursor)
        assertFalse(first.totalExact)
        assertIs<ApiResult.Error>(api.browse(query.copy(q = "changed"), first.continuation))
        val second = assertIs<ApiResult.Success<CatalogPageV2<*>>>(api.browse(query, first.continuation)).data
        assertNull(second.continuation)
        assertEquals(2, count)
        client.close()
    }

    @Test fun structuredQueryUsesPostWithScalarAndArrayValues() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v2/catalog/query", request.url.encodedPath)
            assertEquals("small", request.url.parameters["image_size"])
            val json = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("7", json["library_id"]?.jsonPrimitive?.content)
            val rules = json.getValue("groups").jsonArray.single().jsonObject.getValue("rules").jsonArray
            assertEquals("Drama", rules[0].jsonObject["value"]?.jsonPrimitive?.content)
            assertEquals(2, rules[1].jsonObject["value"]?.jsonArray?.size)
            assertNull(json["offset"])
            respond(body(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        val groups = listOf(CatalogRuleGroupV2("all", listOf(
            CatalogRuleV2("genre", "contains", JsonPrimitive("Drama")),
            CatalogRuleV2("year", "between", JsonArray(listOf(JsonPrimitive("1990"), JsonPrimitive("1999")))),
        )))
        assertIs<ApiResult.Success<*>>(CatalogV2Api(client).browse(CatalogQueryV2(libraryId = "7", groups = groups), imageSize = "small"))
        client.close()
    }

    @Test fun malformedAndRepeatedContinuationFailWithoutRestart() = runTest {
        var count = 0
        val client = HttpClient(MockEngine {
            count++
            respond(body("same"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = CatalogV2Api(client)
        val first = assertIs<ApiResult.Success<CatalogPageV2<*>>>(api.browse(CatalogQueryV2())).data
        assertEquals("invalid_cursor", assertIs<ApiResult.Error>(api.browse(CatalogQueryV2(), first.continuation)).error)
        assertEquals(2, count)
        client.close()
    }

    @Test fun facetsDecodeNestedTechnicalValues() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog/filters", request.url.encodedPath)
            assertEquals("false", request.url.parameters["skip_technical"])
            assertNull(request.url.parameters["include_technical"])
            respond("""{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[],"technical":{"resolutions":["4K"],"audio_languages":[],"subtitle_languages":[]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals(listOf("4K"), assertIs<ApiResult.Success<CatalogFiltersV2>>(CatalogV2Api(client).filters()).data.technical?.resolutions)
        client.close()
    }
}
