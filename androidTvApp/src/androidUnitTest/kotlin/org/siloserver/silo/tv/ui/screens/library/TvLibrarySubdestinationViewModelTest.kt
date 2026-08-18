package org.siloserver.silo.tv.ui.screens.library

import io.ktor.client.HttpClient
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
import kotlinx.coroutines.cancel
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
        assertEquals("asc", request.query["order"])
        assertEquals("K", request.query["name_prefix"])
        assertEquals("0", request.query["offset"])
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
            requests.lastCatalogRequestOrNull()?.query?.get("sort") == "added_at" &&
                requests.lastCatalogRequestOrNull()?.query?.get("name_prefix") == null
        }

        val request = requests.lastCatalogRequest()
        assertEquals("added_at", request.query["sort"])
        assertEquals("desc", request.query["order"])
        assertEquals(null, request.query["name_prefix"])
        assertEquals("0", request.query["offset"])
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
        assertEquals("author", requests.lastCatalogRequest().query["groups[0][rules][0][field]"])
        assertEquals("is", requests.lastCatalogRequest().query["groups[0][rules][0][op]"])
        assertEquals("Andy Weir", requests.lastCatalogRequest().query["groups[0][rules][0][value]"])

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
        awaitState { requests.lastCatalogRequestOrNull()?.query?.get("sort") == "year" }
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
            createdViewModels.forEach { it.viewModelScope.cancel() }
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
    )

    private fun MutableList<RequestRecord>.catalogRequestCount(): Int =
        synchronized(this) { count { it.path == "/api/v1/catalog" } }

    private fun MutableList<RequestRecord>.lastCatalogRequest(): RequestRecord =
        synchronized(this) {
            lastOrNull { it.path == "/api/v1/catalog" }
                ?: error("Expected a catalog request, got ${toList()}")
        }

    private fun MutableList<RequestRecord>.lastCatalogRequestOrNull(): RequestRecord? =
        synchronized(this) {
            lastOrNull { it.path == "/api/v1/catalog" }
        }

    private fun MutableList<RequestRecord>.lastAudiobookGroupsRequest(): RequestRecord =
        synchronized(this) {
            lastOrNull { it.path == "/api/v1/catalog/audiobook-groups" }
                ?: error("Expected an audiobook groups request, got ${toList()}")
        }

    private fun MutableList<RequestRecord>.lastAudiobookGroupsRequestOrNull(): RequestRecord? =
        synchronized(this) {
            lastOrNull { it.path == "/api/v1/catalog/audiobook-groups" }
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
                )
                synchronized(requests) {
                    requests += record
                }
                when (request.url.encodedPath) {
                    "/api/v1/library/7/sections" -> respondJson("""{"sections":[]}""")
                    "/api/v1/library/7/collections" -> respondJson("""{"collections":[]}""")
                    "/api/v1/catalog/filters" -> respondJson(
                        """{"genres":["Drama"],"studios":[],"networks":[],"countries":[],"content_ratings":[]}""",
                    )
                    "/api/v1/catalog/audiobook-groups" -> respondJson(
                        """
                            {
                              "total": 1,
                              "total_exact": true,
                              "has_more": false,
                              "groups": [
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
                    "/api/v1/catalog" -> respondJson(
                        """
                            {
                              "total": 1,
                              "has_more": false,
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
