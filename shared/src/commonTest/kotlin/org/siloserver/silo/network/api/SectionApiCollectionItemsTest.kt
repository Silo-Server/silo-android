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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionApiCollectionItemsTest {

    /**
     * The collection's own order is expressed by sending no sort at all, so a
     * null sort must not leak an `order` param either.
     */
    @Test
    fun omitsSortAndOrderWhenNoSortRequested() = runTest {
        val requests = mutableListOf<Map<String, String?>>()
        val api = SectionApi(clientFor(requests, """{"total":0,"total_exact":true,"window_cursor":"w","page":{"has_more":false},"items":[]}"""))

        api.getLibraryCollectionItems("c1", limit = 60, order = "desc")

        val query = requests.single()
        assertEquals("library_collection", query["source"])
        assertEquals("c1", query["collection_id"])
        assertFalse("sort" in query.keys)
        assertFalse("order" in query.keys)
    }

    @Test
    fun sendsSortOrderAndFacetGroupsWhenRequested() = runTest {
        val requests = mutableListOf<Map<String, String?>>()
        val api = SectionApi(
            clientFor(
                requests,
                """{"total":3,"total_exact":true,"window_cursor":"w","page":{"has_more":false},"items":[],"effective_sort":{"field":"title","order":"asc"}}""",
            ),
        )

        val result = api.getLibraryCollectionItems(
            collectionId = "c1",
            sort = "title",
            order = "asc",
            queryGroups = listOf(
                CatalogQueryGroup(
                    match = "any",
                    rules = listOf(CatalogQueryRule(field = "genre", op = "contains", value = "Drama")),
                ),
            ),
            match = "all",
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("title", result.data.effectiveSort?.field)
        assertEquals("asc", result.data.effectiveSort?.order)

        val query = requests.single()
        val body = SiloJson.parseToJsonElement(query.getValue("body")!!).jsonObject
        assertEquals("title", body["sort"]?.jsonPrimitive?.content)
        assertEquals("asc", body["order"]?.jsonPrimitive?.content)
        assertEquals("all", body["match"]?.jsonPrimitive?.content)
        val group = body.getValue("groups").jsonArray.single().jsonObject
        assertEquals("any", group["match"]?.jsonPrimitive?.content)
        val rule = group.getValue("rules").jsonArray.single().jsonObject
        assertEquals("genre", rule["field"]?.jsonPrimitive?.content)
        assertEquals("contains", rule["op"]?.jsonPrimitive?.content)
        assertEquals("Drama", rule["value"]?.jsonPrimitive?.content)

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
