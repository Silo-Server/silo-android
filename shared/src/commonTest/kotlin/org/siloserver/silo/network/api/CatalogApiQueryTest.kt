package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogApiQueryTest {
    @Test
    fun getAudiobookGroupsPassesLibraryGroupingAndPagingParams() = runTest {
        val requests = mutableListOf<Map<String, String?>>()
        val api = CatalogApi(
            client = clientFor(
                requests = requests,
                body = """
                    {
                      "total": 1,
                      "total_exact": true,
                      "page": {"has_more":false},
                      "items": [
                        {
                          "name": "Andy Weir",
                          "item_count": 2,
                          "total_duration_seconds": 12345,
                          "in_progress_count": 1,
                          "finished_count": 0,
                          "poster_urls": ["https://img/1.jpg"]
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val result = api.getAudiobookGroups(
            libraryId = 7,
            groupBy = "author",
            sort = "count",
            limit = 20,
            query = "weir",
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("Andy Weir", result.data.groups.single().name)
        assertEquals(2, result.data.groups.single().itemCount)
        assertEquals("7", requests.single()["library_id"])
        assertEquals("author", requests.single()["group_by"])
        assertEquals("count", requests.single()["sort"])
        assertEquals(null, requests.single()["offset"])
        assertEquals("20", requests.single()["limit"])
        assertEquals("weir", requests.single()["q"])
    }

    @Test
    fun getCatalogEncodesGenericQueryGroupsForFacetDrillIn() = runTest {
        val requests = mutableListOf<Map<String, String?>>()
        val api = CatalogApi(
            client = clientFor(
                requests = requests,
                body = """{"total":0,"total_exact":true,"window_cursor":"w","page":{"has_more":false},"items":[]}""",
            ),
        )

        api.getCatalog(
            libraryId = 7,
            mediaType = "audiobook",
            queryGroups = listOf(
                CatalogQueryGroup(
                    match = "all",
                    rules = listOf(CatalogQueryRule(field = "author", op = "is", value = "Andy Weir")),
                ),
            ),
        )

        val query = requests.single()
        val groups = SiloJson.parseToJsonElement(query.getValue("body")!!).jsonObject.getValue("groups").jsonArray
        assertEquals("all", groups.single().jsonObject["match"]?.jsonPrimitive?.content)
        val rule = groups.single().jsonObject.getValue("rules").jsonArray.single().jsonObject
        assertEquals("author", rule["field"]?.jsonPrimitive?.content)
        assertEquals("is", rule["op"]?.jsonPrimitive?.content)
        assertEquals("Andy Weir", rule["value"]?.jsonPrimitive?.content)

    }

    private fun clientFor(
        requests: MutableList<Map<String, String?>>,
        body: String,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            requests += request.url.parameters.names().associateWith { request.url.parameters[it] } +
                mapOf("body" to (request.body as? TextContent)?.text)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
    }
}
