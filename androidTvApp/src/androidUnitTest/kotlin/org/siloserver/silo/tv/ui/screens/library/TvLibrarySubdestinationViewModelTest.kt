package org.siloserver.silo.tv.ui.screens.library

import io.ktor.client.HttpClient
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TvLibrarySubdestinationViewModelTest {
    @Test
    fun alphabetDestinationUsesTitleSortAndServerNamePrefix() = runLibraryTest {
        val requests = mutableListOf<RequestRecord>()
        val viewModel = viewModelFor(requests, libraryType = "movies")

        viewModel.onTabSelected(TvLibraryTab.Alphabet)
        awaitState { requests.catalogRequestCount() >= 1 }
        viewModel.onNamePrefixChanged("K")
        awaitState { requests.lastCatalogRequestOrNull()?.query?.get("name_prefix") == "K" }

        val request = requests.lastCatalogRequest()
        assertEquals("title", request.query["sort"])
        assertEquals(null, request.query["order"])
        assertEquals("K", request.query["name_prefix"])
        assertEquals(null, request.query["cursor"])
    }

    @Test
    fun recentlyAddedDestinationUsesNewestFirstWithoutAlphabetPrefix() = runLibraryTest {
        val requests = mutableListOf<RequestRecord>()
        val viewModel = viewModelFor(requests, libraryType = "series")

        viewModel.onTabSelected(TvLibraryTab.Alphabet)
        awaitState { requests.catalogRequestCount() >= 1 }
        viewModel.onNamePrefixChanged("S")
        awaitState { requests.lastCatalogRequestOrNull()?.query?.get("name_prefix") == "S" }

        viewModel.onTabSelected(TvLibraryTab.RecentlyAdded)
        awaitState {
            requests.lastCatalogRequestOrNull()?.query?.get("sort") == "-added_at" &&
                requests.lastCatalogRequestOrNull()?.query?.get("name_prefix") == null
        }

        val request = requests.lastCatalogRequest()
        assertEquals("-added_at", request.query["sort"])
        assertEquals(null, request.query["order"])
        assertEquals(null, request.query["name_prefix"])
        assertEquals(null, request.query["cursor"])
    }

    @Test
    fun audiobookAuthorsAndSeriesDestinationsLoadGroupedRowsBeforeCatalogDrillIn() = runLibraryTest {
        val requests = mutableListOf<RequestRecord>()
        val viewModel = viewModelFor(requests, libraryType = "audiobooks")

        viewModel.onTabSelected(TvLibraryTab.Authors)
        awaitState {
            requests.lastAudiobookGroupsRequestOrNull()?.query?.get("group_by") == "author" &&
                viewModel.uiState.value.audiobookGroups.isNotEmpty()
        }
        assertEquals("Andy Weir", viewModel.uiState.value.audiobookGroups.single().name)
        assertEquals(0, requests.catalogRequestCount())

        viewModel.onAudiobookGroupSelected(viewModel.uiState.value.audiobookGroups.single())
        awaitState { requests.catalogRequestCount() == 1 }
        val rule = requests.lastCatalogRequest().body!!.getValue("groups").jsonArray.first().jsonObject.getValue("rules").jsonArray.first().jsonObject
        assertEquals("author", rule["field"]?.jsonPrimitive?.content)
        assertEquals("is", rule["op"]?.jsonPrimitive?.content)
        assertEquals("Andy Weir", rule["value"]?.jsonPrimitive?.content)

        viewModel.onTabSelected(TvLibraryTab.Series)
        awaitState { requests.lastAudiobookGroupsRequestOrNull()?.query?.get("group_by") == "series" }
        assertEquals(null, viewModel.uiState.value.selectedAudiobookGroup)
    }

    @Test
    fun reselectingTheActiveBrowseTabKeepsTheViewersSort() = runLibraryTest {
        val requests = mutableListOf<RequestRecord>()
        val viewModel = viewModelFor(requests, libraryType = "movies")

        viewModel.onTabSelected(TvLibraryTab.Browse)
        awaitState { requests.catalogRequestCount() >= 1 }
        viewModel.onSortKeySelected(TvLibrarySortOption.ReleaseDate)
        awaitState { requests.lastCatalogRequestOrNull()?.query?.get("sort") == "-year" }
        val requestsBeforeReentry = requests.catalogRequestCount()

        // Re-entering the screen (back out of item detail) re-issues the
        // already-committed section against this same ViewModel. That must not
        // reset the sort the viewer picked.
        viewModel.onTabSelected(TvLibraryTab.Browse)
        settle()

        assertEquals("year", viewModel.uiState.value.browseFilter.sort)
        assertEquals("desc", viewModel.uiState.value.browseFilter.order)
        assertEquals(requestsBeforeReentry, requests.catalogRequestCount())
    }

    private val createdViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun runLibraryTest(block: suspend () -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            // Cancel viewModelScope coroutines BEFORE resetting Main: they
            // dispatch on Dispatchers.Main, and one still alive when a later
            // test calls setMain throws IllegalStateException from
            // TestMainDispatcher — the CI-only flake on this class.
            createdViewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            createdViewModels.clear()
            Dispatchers.resetMain()
        }
    }

    /** Real-time window for any spurious request to land before asserting none did. */
    private suspend fun settle() {
        withContext(Dispatchers.IO) { delay(200) }
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                while (!predicate()) {
                    delay(10)
                }
            }
        }
    }

    private data class RequestRecord(
        val path: String,
        val query: Map<String, String?>,
        val body: JsonObject? = null,
    )

    private fun MutableList<RequestRecord>.catalogRequestCount(): Int =
        synchronized(this) { count { it.path in setOf("/api/v2/catalog", "/api/v2/catalog/query") } }

    private fun MutableList<RequestRecord>.lastCatalogRequest(): RequestRecord =
        synchronized(this) {
            lastOrNull { it.path in setOf("/api/v2/catalog", "/api/v2/catalog/query") }
                ?: error("Expected a catalog request, got ${toList()}")
        }

    private fun MutableList<RequestRecord>.lastCatalogRequestOrNull(): RequestRecord? =
        synchronized(this) {
            lastOrNull { it.path in setOf("/api/v2/catalog", "/api/v2/catalog/query") }
        }

    private fun MutableList<RequestRecord>.lastAudiobookGroupsRequest(): RequestRecord =
        synchronized(this) {
            lastOrNull { it.path == "/api/v2/catalog/audiobook-groups" }
                ?: error("Expected an audiobook groups request, got ${toList()}")
        }

    private fun MutableList<RequestRecord>.lastAudiobookGroupsRequestOrNull(): RequestRecord? =
        synchronized(this) {
            lastOrNull { it.path == "/api/v2/catalog/audiobook-groups" }
        }

    private fun viewModelFor(
        requests: MutableList<RequestRecord>,
        libraryType: String,
    ): TvLibraryDetailViewModel {
        val client = HttpClient(
            MockEngine { request ->
                val record = RequestRecord(
                    path = request.url.encodedPath,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it] },
                    body = (request.body as? TextContent)?.text?.let { SiloJson.parseToJsonElement(it).jsonObject },
                )
                synchronized(requests) {
                    requests += record
                }
                when (request.url.encodedPath) {
                    "/api/v1/library/7/sections" -> respondJson("""{"sections":[]}""")
                    "/api/v2/library/7/collections" -> respondJson("""{"library_id":"7","collections":[],"groups":[]}""")
                    "/api/v2/catalog/filters" -> respondJson(
                        """{"genres":["Drama"],"studios":[],"networks":[],"countries":[],"content_ratings":[],"original_languages":[],"authors":[],"narrators":[],"series":[]}""",
                    )
                    "/api/v2/catalog/audiobook-groups" -> respondJson(
                        """
                            {
                              "total": 1,
                              "total_exact": true,
                              "page":{"has_more":false},
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
                    )
                    "/api/v2/catalog", "/api/v2/catalog/query" -> respondJson(
                        """
                            {
                              "total": 1,
                              "total_exact":true,
                              "window_cursor":"window",
                              "page":{"has_more":false},
                              "title": "Library",
                              "items": [
                                {"content_id":"item-1","title":"Item One","type":"movie"}
                              ]
                            }
                        """.trimIndent(),
                    )
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }

        return TvLibraryDetailViewModel(
            sectionRepository = SectionRepository(SectionApi(client)),
            catalogRepository = CatalogRepository(CatalogApi(client)),
            libraryId = 7,
            libraryTitle = "Library",
            libraryType = libraryType,
        ).also { createdViewModels += it }
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
