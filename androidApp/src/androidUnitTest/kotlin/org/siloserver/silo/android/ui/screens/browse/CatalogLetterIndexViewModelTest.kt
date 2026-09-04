package org.siloserver.silo.android.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSubtab
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLetterIndexViewModelTest {
    @Test
    fun browseLetterSelectionUsesServerNamePrefixAndResetsPagination() = runCatalogTest {
        val requests = mutableListOf<RequestRecord>()
        val repositories = repositoriesFor(requests, StandardTestDispatcher(testScheduler))
        val viewModel = BrowseViewModel(
            catalogRepository = repositories.catalog,
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to "1")),
        )
        awaitState { viewModel.uiState.value.items.map { it.contentId } == listOf("all-1") }

        viewModel.loadMore()
        awaitState { viewModel.uiState.value.items.map { it.contentId } == listOf("all-1", "all-2") }

        viewModel.selectNamePrefix("M")
        awaitState { viewModel.uiState.value.items.map { it.contentId } == listOf("m-1") }

        val lastCatalogRequest = requests.lastCatalogRequest()
        assertEquals("M", lastCatalogRequest.query["name_prefix"])
        assertEquals("0", lastCatalogRequest.query["offset"])
        assertEquals("M", viewModel.uiState.value.selectedNamePrefix)
    }

    @Test
    fun browseDensitySelectionUpdatesLayoutWithoutReloadingCatalog() = runCatalogTest {
        val requests = mutableListOf<RequestRecord>()
        val repositories = repositoriesFor(requests, StandardTestDispatcher(testScheduler))
        val viewModel = BrowseViewModel(
            catalogRepository = repositories.catalog,
            savedStateHandle = SavedStateHandle(mapOf("libraryId" to "1")),
        )
        awaitState { viewModel.uiState.value.items.map { it.contentId } == listOf("all-1") }
        val catalogRequestCount = requests.catalogRequestCount()

        viewModel.selectViewDensity(CatalogViewDensity.Compact)

        assertEquals(CatalogViewDensity.Compact, viewModel.uiState.value.catalogDensity)
        assertEquals(catalogRequestCount, requests.catalogRequestCount())
    }

    @Test
    fun librariesBrowseLetterSelectionUsesServerNamePrefix() = runCatalogTest {
        val requests = mutableListOf<RequestRecord>()
        val repositories = repositoriesFor(requests, StandardTestDispatcher(testScheduler))
        val viewModel = LibrariesViewModel(
            personalDataRepository = repositories.personal,
            sectionRepository = repositories.sections,
            catalogRepository = repositories.catalog,
        )
        awaitState { !viewModel.uiState.value.isLoadingLibraries }

        viewModel.selectTab(LibrariesSubtab.Browse)
        awaitState { viewModel.uiState.value.catalogItems.map { it.contentId } == listOf("all-1") }

        viewModel.selectNamePrefix("D")
        awaitState { viewModel.uiState.value.catalogItems.map { it.contentId } == listOf("d-1") }

        assertEquals("D", requests.lastCatalogRequest().query["name_prefix"])
        assertEquals("D", viewModel.uiState.value.selectedNamePrefix)
    }

    @Test
    fun librariesDensitySelectionUpdatesLayoutWithoutReloadingCatalog() = runCatalogTest {
        val requests = mutableListOf<RequestRecord>()
        val repositories = repositoriesFor(requests, StandardTestDispatcher(testScheduler))
        val viewModel = LibrariesViewModel(
            personalDataRepository = repositories.personal,
            sectionRepository = repositories.sections,
            catalogRepository = repositories.catalog,
        )
        awaitState { !viewModel.uiState.value.isLoadingLibraries }
        viewModel.selectTab(LibrariesSubtab.Browse)
        awaitState { viewModel.uiState.value.catalogItems.map { it.contentId } == listOf("all-1") }
        val catalogRequestCount = requests.catalogRequestCount()

        viewModel.selectViewDensity(CatalogViewDensity.Comfortable)

        assertEquals(CatalogViewDensity.Comfortable, viewModel.uiState.value.catalogDensity)
        assertEquals(catalogRequestCount, requests.catalogRequestCount())
    }



    private fun runCatalogTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Wait for the view model to reach a state, in REAL time.
     *
     * Real time is not a shortcut here, it is forced: the Ktor engine backing
     * these repositories completes on its own dispatcher, so the work is on
     * actual threads and the test scheduler can neither see it nor advance it.
     * Draining the scheduler instead — which is what determinism would
     * require — returns before any response has arrived.
     *
     * The flake this replaces was the budget, not the mechanism. Five seconds
     * is ample on an idle machine and not always ample when Gradle is running
     * several test modules in parallel on the same cores, so the failure was
     * load-dependent rather than logical. The budget below is generous because
     * the only cost of generosity is how long a genuinely broken test takes to
     * report, while the cost of tightness is a red build that means nothing.
     *
     * Making this properly deterministic needs the engine dispatcher to be
     * injectable the way SectionRepository's already is — a production-side
     * change, not a test one.
     */
    private suspend fun awaitState(description: String = "expected state", predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            val deadline = withTimeoutOrNull(AwaitStateBudgetMillis) {
                while (!predicate()) {
                    delay(10)
                }
                true
            }
            assertTrue(deadline == true, "view model never reached $description")
        }
    }

    private data class Repositories(
        val personal: PersonalDataRepository,
        val sections: SectionRepository,
        val catalog: CatalogRepository,
    )

    private data class RequestRecord(
        val path: String,
        val query: Map<String, String?>,
    )

    private fun MutableList<RequestRecord>.lastCatalogRequest(): RequestRecord =
        lastOrNull { it.path == "/api/v1/catalog" }
            ?: error("Expected a catalog request, got $this")

    private fun List<RequestRecord>.catalogRequestCount(): Int =
        count { it.path == "/api/v1/catalog" }

    /**
     * [homeRequestDispatcher] is the seam that makes this deterministic.
     * SectionRepository otherwise fans its library requests out on
     * Dispatchers.Default, which is a real thread pool the test scheduler
     * cannot see or wait for.
     */
    private fun repositoriesFor(
        requests: MutableList<RequestRecord>,
        homeRequestDispatcher: CoroutineDispatcher,
    ): Repositories {
        val client = HttpClient(
            MockEngine { request ->
                requests += RequestRecord(
                    path = request.url.encodedPath,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it] },
                )
                when (request.url.encodedPath) {
                    "/api/v1/user/libraries" -> respondJson(
                        """[{"id":1,"name":"Books","type":"ebooks","sort_order":0}]""",
                    )
                    "/api/v1/library/1/sections" -> respondJson("""{"sections":[]}""")
                    "/api/v1/catalog/filters" -> respondJson(
                        """{"genres":[],"studios":[],"networks":[],"countries":[],"content_ratings":[]}""",
                    )
                    "/api/v1/catalog" -> respondJson(catalogBody(request.url.parameters["name_prefix"], request.url.parameters["offset"]))
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return Repositories(
            personal = PersonalDataRepository(PersonalDataApi(client)),
            sections = SectionRepository(SectionApi(client), homeRequestDispatcher = homeRequestDispatcher),
            catalog = CatalogRepository(CatalogApi(client)),
        )
    }

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun catalogBody(prefix: String?, offset: String?): String {
        val normalizedPrefix = prefix?.lowercase()?.takeIf { it.isNotBlank() } ?: "all"
        val page = (offset?.toIntOrNull() ?: 0) + 1
        return """
            {
              "total": 2,
              "has_more": ${offset == null || offset == "0"},
              "title": "Catalog",
              "items": [
                {"content_id":"$normalizedPrefix-$page","title":"${normalizedPrefix.uppercase()} $page","type":"movie"}
              ]
            }
        """.trimIndent()
    }

    private companion object {
        /**
         * Deliberately far beyond what the work needs. It exists to catch a
         * hang, not to police latency on a loaded build machine.
         */
        const val AwaitStateBudgetMillis = 30_000L
    }
}
