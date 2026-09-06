package org.siloserver.silo.android.ui.screens.people

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModelTest {
    @Test
    fun loadMoreAppendsSecondPageUsingSnapshot() = runPersonTest {
        val queries = mutableListOf<Map<String, String?>>()
        val repository = repositoryFor(queries)
        val viewModel = PersonDetailViewModel(repository, SavedStateHandle(mapOf("personId" to 7)))
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.size == 60 }

        viewModel.loadMoreIfNeeded()
        awaitState(viewModel) { !it.isLoadingItems && it.items.size == 61 }

        val ids = viewModel.uiState.value.items.map { it.contentId }
        assertTrue(ids.isNotEmpty(), "Expected loaded items, state=${viewModel.uiState.value}, queries=$queries")
        assertEquals("movie-1", ids.first())
        assertEquals("movie-61", ids.last())
        assertEquals(61, ids.size)
        assertEquals(120, viewModel.uiState.value.totalItems)
        assertTrue(viewModel.uiState.value.hasMore)
        assertEquals("snap-1", queries.last()["cursor"])
    }

    @Test
    fun filterChangeResetsItemsSnapshotAndOffset() = runPersonTest {
        val queries = mutableListOf<Map<String, String?>>()
        val repository = repositoryFor(queries)
        val viewModel = PersonDetailViewModel(repository, SavedStateHandle(mapOf("personId" to 7)))
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.size == 60 }

        viewModel.loadMoreIfNeeded()
        awaitState(viewModel) { !it.isLoadingItems && it.items.size == 61 }
        viewModel.applyFilter(PersonMediaFilter.Audiobooks)
        awaitState(viewModel) {
            !it.isLoadingItems &&
                it.selectedFilter == PersonMediaFilter.Audiobooks &&
                it.items.map { item -> item.contentId } == listOf("audiobook-1")
        }

        assertEquals(
            listOf("audiobook-1"),
            viewModel.uiState.value.items.map { it.contentId },
            "state=${viewModel.uiState.value}, queries=$queries",
        )
        assertEquals("audiobook", queries.last()["type"])
        assertFalse("cursor" in queries.last().keys)
        assertFalse("snapshot" in queries.last().keys)
    }

    @Test
    fun readingFilterKeepsOnlyReadingItems() = runPersonTest {
        val queries = mutableListOf<Map<String, String?>>()
        val repository = repositoryFor(queries)
        val viewModel = PersonDetailViewModel(repository, SavedStateHandle(mapOf("personId" to 7)))
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems && it.items.size == 60 }

        viewModel.applyFilter(PersonMediaFilter.Reading)
        awaitState(viewModel) {
            !it.isLoadingItems &&
                it.selectedFilter == PersonMediaFilter.Reading &&
                it.items.map { item -> item.contentId } == listOf("ebook-1", "comic-1", "manga-1")
        }

        assertEquals(
            listOf("ebook-1", "comic-1", "manga-1"),
            viewModel.uiState.value.items.map { it.contentId },
            "state=${viewModel.uiState.value}, queries=$queries",
        )
        assertFalse("type" in queries.last().keys)
        assertFalse("cursor" in queries.last().keys)
    }

    @Test
    fun mobileFiltersIncludeAllClientSurfaces() = runPersonTest {
        val viewModel = PersonDetailViewModel(
            catalogRepository = repositoryFor(mutableListOf()),
            savedStateHandle = SavedStateHandle(mapOf("personId" to 7)),
        )
        awaitState(viewModel) { !it.isLoading && !it.isLoadingItems }

        assertEquals(
            listOf("All", "Movies", "TV", "Audiobooks", "Music", "Reading"),
            viewModel.uiState.value.availableFilters.map { it.title },
        )
    }

    private fun runPersonTest(block: suspend () -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitState(
        viewModel: PersonDetailViewModel,
        predicate: (PersonDetailUiState) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            withTimeout(AWAIT_POLL_TIMEOUT_MS) {
                while (!predicate(viewModel.uiState.value)) {
                    delay(10)
                }
            }
        }
    }

    private fun repositoryFor(
        queries: MutableList<Map<String, String?>>,
    ): CatalogRepository {
        var allCalls = 0
        val client = HttpClient(
            MockEngine { request ->
                queries += request.url.parameters.names().associateWith { request.url.parameters[it] }
                when (request.url.encodedPath) {
                    // Metadata-complete on purpose: a blank bio/photo/birth date
                    // arms the metadata auto-refresh poll, whose background
                    // GET /people/7 requests would race these tests' shared
                    // `queries` list under runTest's auto-advanced virtual time.
                    "/api/v2/catalog/people/7" -> respondJson(
                        """{"id":"7","name":"Person","birth_date":"1972-06-16",""" +
                            """"bio":"A person.","photo_url":"https://img/p7.jpg"}""",
                    )
                    "/api/v2/catalog" -> {
                        val body = when (request.url.parameters["type"]) {
                            "audiobook" -> catalogBody(
                                total = 1,
                                hasMore = false,
                                snapshot = "snap-audio",
                                items = listOf(item("audiobook-1", "Audiobook 1", "audiobook")),
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
                                allCalls += 1
                                if (allCalls == 1) {
                                    catalogBody(
                                        total = 120,
                                        hasMore = true,
                                        snapshot = "snap-1",
                                        items = (1..60).map { item("movie-$it", "Movie $it", "movie") },
                                    )
                                } else if (request.url.parameters["cursor"] == "snap-1") {
                                    catalogBody(
                                        total = 120,
                                        hasMore = true,
                                        snapshot = "snap-2",
                                        items = listOf(item("movie-61", "Movie 61", "movie")),
                                    )
                                } else {
                                    catalogBody(
                                        total = 4,
                                        hasMore = false,
                                        snapshot = "snap-reading",
                                        items = listOf(
                                            item("movie-extra", "Movie Extra", "movie"),
                                            item("ebook-1", "Ebook 1", "ebook"),
                                            item("comic-1", "Comic 1", "comic"),
                                            item("manga-1", "Manga 1", "manga"),
                                        ),
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
          "total_exact": true,
          "window_cursor": "window",
          "page": {"has_more": $hasMore, "next_cursor": ${if (hasMore) "\"$snapshot\"" else "null"}},
          "items": [${items.joinToString(",")}]
        }
    """.trimIndent()

    private fun item(id: String, title: String, type: String): String =
        """{"content_id":"$id","title":"$title","type":"$type"}"""
}

/**
 * Wall-clock backstop for the polling waits above.
 *
 * It exists to turn a hang into a failure, not to assert latency: a passing
 * test settles in milliseconds. Short deadlines here failed on a loaded CI
 * runner while the work was merely slow, which looks exactly like the race the
 * wait was written to catch.
 */
private const val AWAIT_POLL_TIMEOUT_MS = 30_000L
