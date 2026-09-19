package org.siloserver.silo.android.ui.screens.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.api.CollectionApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CollectionRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModelTest {
    @Test
    fun libraryUserCollectionUsesUserSourceAndCursor() = runCollectionTest { requests ->
        var userPages = 0
        val fixture = fixture(requests) { path, parameters ->
            when (path) {
                "/api/v2/library/7/collections" -> libraryCollectionsBody(
                    id = "user-1",
                    title = "User Picks",
                    kind = "user_collections",
                    itemCount = 2,
                )
                "/api/v2/catalog" -> {
                    assertEquals("user_collection", parameters["source"])
                    assertEquals("user-1", parameters["collection_id"])
                    assertEquals("7", parameters["library_id"])
                    userPages += 1
                    when (userPages) {
                        1 -> catalogBody(total = 2, hasMore = true, nextCursor = "next-user")
                        2 -> {
                            assertEquals("next-user", parameters["cursor"])
                            catalogBody(total = 2, hasMore = false)
                        }
                        else -> {
                            assertFalse("cursor" in parameters)
                            catalogBody(total = 2, hasMore = false)
                        }
                    }
                }
                else -> error("Unexpected path $path")
            }
        }

        val viewModel = fixture.viewModel(
            SavedStateHandle(mapOf("libraryId" to "7", "source" to "user_collection")),
        )
        fixture.store.put("detail", viewModel)
        viewModel.initialize("user-1")
        awaitState(viewModel) { !it.isLoading && it.hasMore }

        assertEquals("User Picks", viewModel.uiState.value.title)
        assertEquals(2, viewModel.uiState.value.total)
        assertFalse(viewModel.uiState.value.canManage)

        viewModel.loadMore()
        awaitState(viewModel) { !it.isLoadingMore && !it.hasMore }

        assertEquals(2, requests.count { it.path == "/api/v2/catalog" })
        assertTrue(requests.filter { it.path == "/api/v2/catalog" }.all {
            it.parameters["source"] == "user_collection"
        })

        viewModel.refresh()
        awaitState(viewModel) {
            !it.isLoading && requests.count { request -> request.path == "/api/v2/catalog" } == 3
        }

        val refresh = requests.last { it.path == "/api/v2/catalog" }
        assertEquals("user_collection", refresh.parameters["source"])
        assertFalse("cursor" in refresh.parameters)
    }

    @Test
    fun regularLibraryCollectionUsesLibrarySource() = runCollectionTest { requests ->
        val fixture = fixture(requests) { path, parameters ->
            when (path) {
                "/api/v2/library/7/collections" -> libraryCollectionsBody(
                    id = "regular-1",
                    title = "Server Picks",
                    kind = "admin",
                    itemCount = 1,
                )
                "/api/v2/catalog" -> {
                    assertEquals("library_collection", parameters["source"])
                    assertEquals("regular-1", parameters["collection_id"])
                    catalogBody(total = 1, hasMore = false)
                }
                else -> error("Unexpected path $path")
            }
        }

        val viewModel = fixture.viewModel(
            SavedStateHandle(mapOf("libraryId" to "7", "source" to "library_collection")),
        )
        fixture.store.put("detail", viewModel)
        viewModel.initialize("regular-1")
        awaitState(viewModel) { !it.isLoading }

        assertEquals("Server Picks", viewModel.uiState.value.title)
        assertEquals(1, viewModel.uiState.value.total)
        assertFalse(viewModel.uiState.value.canManage)
        assertEquals("library_collection", requests.single { it.path == "/api/v2/catalog" }.parameters["source"])
    }

    @Test
    fun unscopedPersonalCollectionRemainsManageable() = runCollectionTest { requests ->
        val fixture = fixture(requests) { path, parameters ->
            when (path) {
                "/api/v2/collections" -> """
                    {"items":[{"id":"personal-1","name":"Personal Picks","item_count":0}],"groups":[]}
                """.trimIndent()
                "/api/v2/catalog" -> {
                    assertEquals("user_collection", parameters["source"])
                    assertEquals("personal-1", parameters["collection_id"])
                    assertFalse("library_id" in parameters)
                    catalogBody(total = 0, hasMore = false)
                }
                else -> error("Unexpected path $path")
            }
        }

        val viewModel = fixture.viewModel(SavedStateHandle())
        fixture.store.put("detail", viewModel)
        viewModel.initialize("personal-1")
        awaitState(viewModel) { !it.isLoading }

        assertEquals("Personal Picks", viewModel.uiState.value.title)
        assertTrue(viewModel.uiState.value.canManage)
        assertEquals("user_collection", requests.single { it.path == "/api/v2/catalog" }.parameters["source"])
    }

    private fun runCollectionTest(block: suspend (MutableList<CapturedRequest>) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val requests = mutableListOf<CapturedRequest>()
        try {
            block(requests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun fixture(
        requests: MutableList<CapturedRequest>,
        response: (path: String, parameters: Map<String, String?>) -> String,
    ): Fixture {
        val client = HttpClient(
            MockEngine { request ->
                val parameters = request.url.parameters.names().associateWith { request.url.parameters[it] }
                requests += CapturedRequest(request.url.encodedPath, parameters)
                respondJson(response(request.url.encodedPath, parameters))
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return Fixture(client)
    }

    private suspend fun awaitState(
        viewModel: CollectionDetailViewModel,
        predicate: (CollectionDetailUiState) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                while (!predicate(viewModel.uiState.value)) delay(10)
            }
        }
    }

    private fun libraryCollectionsBody(
        id: String,
        title: String,
        kind: String,
        itemCount: Int,
    ): String = """
        {
          "library_id":"7",
          "collections":[],
          "groups":[{
            "id":"group-1",
            "name":"Collections",
            "kind":"$kind",
            "sort_mode":"manual",
            "sort_order":0,
            "collections":[{
              "id":"$id",
              "title":"$title",
              "poster_url":"",
              "item_count":$itemCount
            }]
          }]
        }
    """.trimIndent()

    private fun catalogBody(
        total: Int,
        hasMore: Boolean,
        nextCursor: String? = null,
    ): String = """
        {
          "items":[],
          "page":{"has_more":$hasMore,"next_cursor":${nextCursor?.let { "\"$it\"" } ?: "null"}},
          "total":$total
        }
    """.trimIndent()

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private data class CapturedRequest(
        val path: String,
        val parameters: Map<String, String?>,
    )

    private class Fixture(val client: HttpClient) {
        val store = ViewModelStore()

        fun viewModel(savedStateHandle: SavedStateHandle) = CollectionDetailViewModel(
            collectionRepository = CollectionRepository(
                CollectionApi(client, ApiV2Gate.Unrestricted),
            ),
            sectionRepository = SectionRepository(SectionApi(client)),
            savedStateHandle = savedStateHandle,
        )
    }
}
