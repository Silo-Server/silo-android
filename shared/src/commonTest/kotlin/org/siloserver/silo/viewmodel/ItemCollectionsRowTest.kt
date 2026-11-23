package org.siloserver.silo.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemCollectionsRowTest {
    private data class Seen(val path: String, val parameters: Parameters)

    private class Server(
        var capability: String? = ""","item_collections":true""",
        var collections: String = """{"items":[],"preview_items":[]}""",
        var collectionsStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val seen = mutableListOf<Seen>()
    }

    private fun card(id: String, title: String = "Collection $id") =
        """{"id":"$id","source":"library","title":"$title","item_count":3,"library_id":"3"}"""

    private fun title(id: String) = """{"content_id":"movie:$id","type":"movie","title":"Title $id"}"""

    private fun detail(type: String = "movie") = ItemDetail(contentId = "movie:spirited-away", type = type, title = "Spirited Away")

    private fun withServer(server: Server, block: suspend (CatalogRepository) -> Unit) = runTest {
        val client = HttpClient(MockEngine { request ->
            server.seen += Seen(request.url.encodedPath, request.url.parameters)
            when {
                request.url.encodedPath == "/api/v2/catalog/search/capabilities" -> respond(
                    """{"revision":"r","state":"available","provider":"postgres"${server.capability.orEmpty()}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/collections") -> respond(
                    if (server.collectionsStatus == HttpStatusCode.OK) server.collections
                    else """{"type":"about:blank","title":"Error","status":${server.collectionsStatus.value},"code":"internal"}""",
                    server.collectionsStatus,
                    headersOf(
                        HttpHeaders.ContentType,
                        if (server.collectionsStatus == HttpStatusCode.OK) "application/json" else "application/problem+json",
                    ),
                )
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }) { install(ContentNegotiation) { json(SiloJson) } }
        try {
            block(CatalogRepository(CatalogApi(client)))
        } finally {
            client.close()
        }
    }

    @Test
    fun severalCollectionsShowEveryCardUnderAlsoIn() {
        val cards = (1..15).joinToString(",") { card("c$it") }
        val server = Server(collections = """{"items":[$cards],"preview_items":[]}""")
        withServer(server) { repository ->
            val row = assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(), libraryId = 3)).data
            val alsoIn = assertIs<ItemCollectionsRow.AlsoIn>(row)
            assertEquals((1..15).map { "c$it" }, alsoIn.cards.map { it.id })
            val read = server.seen.single { it.path.endsWith("/collections") }
            assertEquals("/api/v2/catalog/items/movie:spirited-away/collections", read.path)
            assertEquals("3", read.parameters["library_id"])
        }
    }

    @Test
    fun oneCollectionWithOtherTitlesPreviewsUpToSixUnderItsName() {
        val titles = (1..8).joinToString(",") { title("t$it") }
        val server = Server(collections = """{"items":[${card("ghibli", "Studio Ghibli")}],"preview_items":[$titles]}""")
        withServer(server) { repository ->
            val row = assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(), libraryId = 3)).data
            val preview = assertIs<ItemCollectionsRow.Preview>(row)
            assertEquals("Studio Ghibli", preview.collection.title)
            assertEquals((1..6).map { "movie:t$it" }, preview.items.map { it.contentId })
        }
    }

    @Test
    fun oneCollectionHoldingOnlyThisTitleShowsItsCardUnderAlsoIn() {
        val server = Server(collections = """{"items":[${card("solo")}],"preview_items":[]}""")
        withServer(server) { repository ->
            val row = assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(), libraryId = null)).data
            assertEquals(listOf("solo"), assertIs<ItemCollectionsRow.AlsoIn>(row).cards.map { it.id })
            assertNull(server.seen.single { it.path.endsWith("/collections") }.parameters["library_id"])
        }
    }

    @Test
    fun noCollectionShowsNoRow() {
        withServer(Server()) { repository ->
            assertNull(assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(), libraryId = 3)).data)
        }
    }

    @Test
    fun serverWithoutTheReadIsNeverAsked() {
        for (capability in listOf(null, ""","item_collections":false""")) {
            val server = Server(capability = capability, collections = """{"items":[${card("c1")},${card("c2")}],"preview_items":[]}""")
            withServer(server) { repository ->
                assertNull(assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(), libraryId = 3)).data)
                assertTrue(server.seen.none { it.path.endsWith("/collections") }, "asked ${server.seen}")
            }
        }
    }

    @Test
    fun onlyMoviesAndSeriesAsk() {
        val server = Server(collections = """{"items":[${card("c1")},${card("c2")}],"preview_items":[]}""")
        withServer(server) { repository ->
            for (type in listOf("episode", "season", "audiobook")) {
                assertNull(assertIs<ApiResult.Success<ItemCollectionsRow?>>(loadItemCollectionsRow(repository, detail(type), libraryId = 3)).data)
            }
            assertTrue(server.seen.isEmpty(), "asked ${server.seen}")
            assertIs<ItemCollectionsRow.AlsoIn>(assertIs<ApiResult.Success<ItemCollectionsRow?>>(
                loadItemCollectionsRow(repository, detail("series"), libraryId = 3),
            ).data)
        }
    }

    @Test
    fun aFailedReadIsReturnedSoThePageKeepsWhatItShows() {
        val server = Server(collectionsStatus = HttpStatusCode.InternalServerError)
        withServer(server) { repository ->
            assertIs<ApiResult.Error>(loadItemCollectionsRow(repository, detail(), libraryId = 3))
        }
    }
}
