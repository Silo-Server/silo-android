package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.BOX_SET_GROUP
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.boxSetCard
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

class BoxSetsV2Test {
    private val boxSet = """"id":"1241","library_id":"3","title":"Harry Potter Collection","overview":"Eight films.",""" +
        """"poster_url":"https://img/p","poster_thumbhash":"ph","movie_count":8,"played_count":5,"first_year":2001,"last_year":2011"""

    private fun client(handler: (HttpRequestDataView) -> String) = HttpClient(MockEngine { request ->
        val view = HttpRequestDataView(request.method, request.url.encodedPath, request.url.parameters)
        respond(handler(view), headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(SiloJson) } }

    data class HttpRequestDataView(val method: HttpMethod, val path: String, val parameters: Parameters)

    @Test fun groupedBrowseSendsGroupAndDecodesBoxSetCards() = runTest {
        val seen = mutableListOf<HttpRequestDataView>()
        val c = client {
            seen += it
            """{"items":[{"content_id":"box_set:1241","type":"box_set","title":"Harry Potter Collection","box_set":{$boxSet}},""" +
                """{"content_id":"m1","type":"movie","title":"Dune"}],"page":{"has_more":false},"total":2,"total_exact":true,"window_cursor":"w"}"""
        }
        try {
            val repository = CatalogRepository(CatalogApi(c))
            val page = assertIs<ApiResult.Success<*>>(repository.browse(libraryId = 3, group = BOX_SET_GROUP)).data
                as org.siloserver.silo.model.catalog.CatalogResponse
            assertEquals("/api/v2/catalog", seen.single().path)
            assertEquals("box_set", seen.single().parameters["group"])
            assertEquals("3", seen.single().parameters["library_id"])
            val card = assertNotNull(page.items[0].boxSetCard)
            assertEquals(CatalogBoxSet("1241", "3", "Harry Potter Collection", "Eight films.", "https://img/p", "ph",
                movieCount = 8, playedCount = 5, firstYear = 2001, lastYear = 2011), card)
            assertNull(page.items[1].boxSetCard)
            // A library without box sets browses exactly as before: no group sent.
            repository.browse(libraryId = 3)
            assertNull(seen.last().parameters["group"])
        } finally { c.close() }
    }

    @Test fun groupedStructuredQueryCarriesGroupInTheBody() = runTest {
        var body: String? = null
        val c = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog/query", request.url.encodedPath)
            body = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"items":[],"page":{"has_more":false},"total":0,"total_exact":true,"window_cursor":"w"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val groups = listOf(org.siloserver.silo.model.catalog.CatalogQueryGroup("all",
                listOf(org.siloserver.silo.model.catalog.CatalogQueryRule("genre", "contains", "Fantasy"))))
            assertIs<ApiResult.Success<*>>(CatalogRepository(CatalogApi(c)).browse(libraryId = 3, queryGroups = groups, group = BOX_SET_GROUP))
            assertEquals("box_set", SiloJson.parseToJsonElement(body!!).let { (it as kotlinx.serialization.json.JsonObject)["group"] }
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        } finally { c.close() }
    }

    @Test fun boxSetReadNamesItsLibraryAndKeepsCollectionOrder() = runTest {
        val seen = mutableListOf<HttpRequestDataView>()
        val c = client {
            seen += it
            """{$boxSet,"items":[{"content_id":"hp1","type":"movie","title":"Philosopher's Stone","year":2001,"user_state":{"played":true}},""" +
                """{"content_id":"hp2","type":"movie","title":"Chamber of Secrets","year":2002}]}"""
        }
        try {
            val detail = assertIs<ApiResult.Success<CatalogBoxSetDetail>>(CatalogRepository(CatalogApi(c)).getBoxSet("1241", "3")).data
            assertEquals("/api/v2/catalog/box-sets/1241", seen.single().path)
            assertEquals("3", seen.single().parameters["library_id"])
            assertEquals("Harry Potter Collection", detail.boxSet.title)
            assertEquals(8, detail.boxSet.movieCount)
            assertEquals(listOf("hp1", "hp2"), detail.items.map { it.contentId })
            assertTrue(detail.items[0].userState?.played == true)
        } finally { c.close() }
    }

    @Test fun itemBoxSetDecodesTheBoxSetOrNone() = runTest {
        val seen = mutableListOf<HttpRequestDataView>()
        var body = """{"box_set":{$boxSet,"items":[{"content_id":"hp1","type":"movie","title":"Philosopher's Stone","user_state":{"played":true}},""" +
            """{"content_id":"hp3","type":"movie","title":"Prisoner of Azkaban"}]}}"""
        val c = client { seen += it; body }
        try {
            val repository = CatalogRepository(CatalogApi(c))
            val detail = assertNotNull(assertIs<ApiResult.Success<CatalogBoxSetDetail?>>(repository.getItemBoxSet("hp3", 3)).data)
            assertEquals("/api/v2/catalog/items/hp3/box-set", seen.single().path)
            assertEquals("3", seen.single().parameters["library_id"])
            assertEquals("1241", detail.boxSet.id)
            assertEquals(5, detail.boxSet.playedCount)
            assertEquals(listOf("hp1", "hp3"), detail.items.map { it.contentId })
            // A movie in no box set the viewer sees answers {}: no row.
            body = "{}"
            assertNull(assertIs<ApiResult.Success<CatalogBoxSetDetail?>>(repository.getItemBoxSet("dune")).data)
            assertNull(seen.last().parameters["library_id"])
        } finally { c.close() }
    }

    @Test fun searchSendsTheQueryAndAFailedReadAnswersNone() = runTest {
        val seen = mutableListOf<HttpRequestDataView>()
        var fail = false
        val c = HttpClient(MockEngine { request ->
            seen += HttpRequestDataView(request.method, request.url.encodedPath, request.url.parameters)
            if (fail) {
                respond("""{"type":"about:blank","title":"Not Found","status":404}""", HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"))
            } else {
                respond("""{"items":[{$boxSet}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            val repository = CatalogRepository(CatalogApi(c))
            assertEquals(listOf("1241"), repository.searchBoxSets(" harry potter ").map { it.id })
            assertEquals("/api/v2/catalog/box-sets", seen.single().path)
            assertEquals("harry potter", seen.single().parameters["q"])
            assertEquals("5", seen.single().parameters["limit"])
            // A server without box sets answers 404; search shows none.
            fail = true
            assertEquals(emptyList(), repository.searchBoxSets("harry"))
            // Blank text never reaches the server.
            assertEquals(emptyList(), repository.searchBoxSets("  "))
            assertEquals(2, seen.size)
        } finally { c.close() }
    }
}
