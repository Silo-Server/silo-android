package org.siloserver.silo.tv.ui.screens.people

import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TvPersonDetailViewModelTest {
    @Test
    fun loadMoreAppendsSecondPageUsingSnapshotAndRawOffset() = runPersonTest {
        val queries = mutableListOf<Map<String, String?>>()
        val viewModel = createViewModel(queries)
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.size == 59 }

        viewModel.loadMoreIfNeeded()
        awaitState(viewModel) { !it.isLoadingItems && it.items.size == 60 }

        val ids = viewModel.uiState.value.items.map { it.contentId }
        assertFalse("ebook-hidden" in ids)
        assertTrue("audiobook-1" in ids)
        assertEquals("movie-59", ids.last())
        assertEquals(120, viewModel.uiState.value.totalItems)
        assertTrue(viewModel.uiState.value.hasMore)
        assertEquals("snap-1", queries.last()["snapshot"])
        assertEquals("60", queries.last()["offset"])
    }

    @Test
    fun filterChangeResetsItemsSnapshotAndOffset() = runPersonTest {
        val queries = mutableListOf<Map<String, String?>>()
        val viewModel = createViewModel(queries)
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.size == 59 }

        viewModel.loadMoreIfNeeded()
        awaitState(viewModel) { !it.isLoadingItems && it.items.size == 60 }
        viewModel.applyFilter(TvPersonMediaFilter.Audiobooks)
        awaitState(viewModel) {
            !it.isLoadingItems &&
                it.selectedFilter == TvPersonMediaFilter.Audiobooks &&
                it.items.map { item -> item.contentId } == listOf("audiobook-filtered")
        }

        assertEquals("audiobook", queries.last()["type"])
        assertEquals("0", queries.last()["offset"])
        assertFalse("snapshot" in queries.last().keys)
    }

    @Test
    fun tvFiltersExcludeReadingSurface() = runPersonTest {
        val viewModel = createViewModel()
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems }

        assertEquals(
            listOf("All", "Movies", "Series", "Audiobooks", "Music"),
            viewModel.uiState.value.availableFilters.map { it.title },
        )
        assertFalse(viewModel.uiState.value.availableFilters.any { it.title == "Reading" })
    }

    @Test
    fun audiobookPreferenceHidesFilterAndAllResults() = runPersonTest {
        val viewModel = TvPersonDetailViewModel(
            catalogRepository = repositoryFor(mutableListOf()),
            personId = 7,
            showAudiobooksProvider = { false },
        ).also { createdViewModels += it }
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.isNotEmpty() }

        assertFalse(viewModel.uiState.value.availableFilters.contains(TvPersonMediaFilter.Audiobooks))
        assertFalse(viewModel.uiState.value.items.any { it.type == "audiobook" })
    }

    private val createdViewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun createViewModel(
        queries: MutableList<Map<String, String?>> = mutableListOf(),
    ): TvPersonDetailViewModel =
        TvPersonDetailViewModel(repositoryFor(queries), personId = 7)
            .also { createdViewModels += it }

    private fun runPersonTest(block: suspend () -> Unit) = runTest {
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

    private suspend fun awaitState(
        viewModel: TvPersonDetailViewModel,
        predicate: (TvPersonDetailUiState) -> Boolean,
    ) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(30_000) {
                while (!predicate(viewModel.uiState.value)) {
                    delay(10)
                }
            }
        }
    }

    private fun repositoryFor(
        queries: MutableList<Map<String, String?>>,
    ): CatalogRepository {
        val client = HttpClient(
            MockEngine { request ->
                queries += request.url.parameters.names().associateWith { request.url.parameters[it] }
                when (request.url.encodedPath) {
                    "/api/v1/people/7" -> respondJson(
                        """{"id":7,"name":"Person","birth_date":"1972-06-16"}""",
                    )
                    "/api/v1/catalog" -> {
                        val body = when (request.url.parameters["type"]) {
                            "audiobook" -> catalogBody(
                                total = 1,
                                hasMore = false,
                                snapshot = "snap-audio",
                                items = listOf(item("audiobook-filtered", "Audiobook Filtered", "audiobook")),
                            )
                            "music" -> catalogBody(
                                total = 1,
                                hasMore = false,
                                snapshot = "snap-music",
                                items = listOf(item("music-1", "Music 1", "music")),
                            )
                            "movie" -> catalogBody(
                                total = 1,
                                hasMore = false,
                                snapshot = "snap-movie",
                                items = listOf(item("movie-filtered", "Movie Filtered", "movie")),
                            )
                            "series" -> catalogBody(
                                total = 1,
                                hasMore = false,
                                snapshot = "snap-series",
                                items = listOf(item("series-1", "Series 1", "series")),
                            )
                            else -> {
                                if (request.url.parameters["snapshot"] == "snap-1") {
                                    catalogBody(
                                        total = 120,
                                        hasMore = true,
                                        snapshot = "snap-1",
                                        items = listOf(item("movie-59", "Movie 59", "movie")),
                                    )
                                } else {
                                    catalogBody(
                                        total = 120,
                                        hasMore = true,
                                        snapshot = "snap-1",
                                        items = (1..58).map { item("movie-$it", "Movie $it", "movie") } +
                                            item("ebook-hidden", "Hidden Ebook", "ebook") +
                                            item("audiobook-1", "Audiobook 1", "audiobook"),
                                    )
                                }
                            }
                        }
                        respondJson(body)
                    }
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return CatalogRepository(CatalogApi(client))
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun catalogBody(
        total: Int,
        hasMore: Boolean,
        snapshot: String,
        items: List<String>,
    ): String = """
        {
          "total": $total,
          "has_more": $hasMore,
          "snapshot": "$snapshot",
          "items": [${items.joinToString(",")}]
        }
    """.trimIndent()

    private fun item(id: String, title: String, type: String): String =
        """{"content_id":"$id","title":"$title","type":"$type"}"""
}
