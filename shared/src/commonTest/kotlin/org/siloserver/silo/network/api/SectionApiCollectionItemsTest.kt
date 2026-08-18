package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
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
        val api = SectionApi(clientFor(requests, """{"total":0,"has_more":false,"items":[]}"""))

        api.getLibraryCollectionItems("c1", offset = 0, limit = 60, order = "desc")

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
                """{"total":3,"has_more":false,"items":[],"effective_sort":{"field":"title","order":"asc"}}""",
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
        assertEquals("title", query["sort"])
        assertEquals("asc", query["order"])
        assertEquals("all", query["match"])
        assertEquals("any", query["groups[0][match]"])
        assertEquals("genre", query["groups[0][rules][0][field]"])
        assertEquals("contains", query["groups[0][rules][0][op]"])
        assertEquals("Drama", query["groups[0][rules][0][value]"])
    }

    private fun clientFor(
        requests: MutableList<Map<String, String?>>,
        body: String,
    ): HttpClient = HttpClient(
        MockEngine { request ->
            requests += request.url.parameters.names().associateWith { request.url.parameters[it] }
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
