package org.siloserver.silo.android.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSubtab
import org.siloserver.silo.android.ui.screens.libraries.LibrariesViewModel
import org.siloserver.silo.android.ui.screens.libraries.LibraryBrowseSort
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The A–Z rail's letter jump on the phone Browse screen and the Libraries Browse tab. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLetterIndexViewModelTest {
    @Test
    fun titleOrderReadsTheLetterIndexBesideAWholeFirstPageAndJumpsByPosition() = runCatalogTest {
        val server = FakeServer()
        val viewModel = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        awaitState("first page") { viewModel.uiState.value.items.isNotEmpty() }
        // Recently added: no rail, and no letter index asked for.
        assertNull(viewModel.letterJump.value)
        assertEquals(0, server.lettersRequests.size)

        viewModel.applyFilterState(CatalogFilterState(sort = "title", order = "asc"))
        awaitState("letter index") { viewModel.letterJump.value != null }
        val jump = assertNotNull(viewModel.letterJump.value)
        assertEquals(FakeServer.TOTAL, jump.pager.total)
        assertEquals(setOf("#", "A", "M", "Z"), jump.availableLetters)
        val letters = server.lettersRequests.single()
        assertEquals("1", letters["library_id"])
        assertEquals("asc", letters["order"])

        // The first page is one whole pager page, so it is not read again.
        awaitState("seeded page") { jump.pager.isLoaded(0..99) }
        val firstPage = server.catalogRequests.last { it["seek"] == null }
        assertEquals("100", firstPage["limit"])
        assertEquals("title", firstPage["sort"])

        // M starts at 210: the jump reads 198..257 by position before it lands.
        assertEquals(210, jumpPosition { viewModel.letterJumpPosition("M") })
        awaitState("landing pages") { jump.pager.isLoaded(198..257) }
        val seeks = server.catalogRequests.mapNotNull { it["seek"] }.toSet()
        assertEquals(setOf("100", "200"), seeks)
        // Seeks reuse the first page's window cursor (never a continuation)
        // and skip the count the letter index already gave.
        val seekRequests = server.catalogRequests.filter { it["seek"] != null }
        assertTrue(seekRequests.all { it["cursor"] == "window" && it["skip_total"] == "true" }, "seeks: $seekRequests")
        assertEquals("t210", jump.pager.item(210)?.contentId)

        // A letter with no titles has nowhere to jump.
        assertNull(jumpPosition { viewModel.letterJumpPosition("Q") })
    }

    @Test
    fun aServerWithoutALetterIndexShowsNoRailAndStopsAsking() = runCatalogTest {
        val server = FakeServer(lettersStatus = HttpStatusCode.NotFound)
        val viewModel = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        awaitState("first page") { viewModel.uiState.value.items.isNotEmpty() }

        viewModel.applyFilterState(CatalogFilterState(sort = "title", order = "asc"))
        awaitState("letters request") { server.lettersRequests.size == 1 }
        awaitState("title page") { server.catalogRequests.last()["sort"] == "title" && !viewModel.uiState.value.isLoading }
        assertNull(viewModel.letterJump.value)

        viewModel.applyFilterState(CatalogFilterState(sort = "title", order = "desc"))
        awaitState("descending page") { server.catalogRequests.last()["sort"] == "-title" && !viewModel.uiState.value.isLoading }
        assertEquals(1, server.lettersRequests.size)
        assertNull(viewModel.letterJump.value)
    }

    @Test
    fun leavingTitleOrderDropsTheRail() = runCatalogTest {
        val server = FakeServer()
        val viewModel = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        viewModel.applyFilterState(CatalogFilterState(sort = "title", order = "asc"))
        awaitState("letter index") { viewModel.letterJump.value != null }
        val pager = assertNotNull(viewModel.letterJump.value).pager

        viewModel.applyFilterState(CatalogFilterState(sort = "year", order = "desc"))
        assertNull(viewModel.letterJump.value)
        // The old list's pager reads nothing more.
        pager.show(1_000..1_010)
        assertTrue(pager.pagesInFlight.isEmpty())
    }

    @Test
    fun returningToALibraryShowsItsLetterIndexAtOnceAndRefreshesIt() = runCatalogTest {
        val server = FakeServer()
        val titleOrder = CatalogFilterState(sort = "title", order = "asc")
        val first = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        first.applyFilterState(titleOrder)
        awaitState("letter index") { first.letterJump.value != null }

        // The next read of the index waits; the session's copy shows meanwhile.
        val gate = CompletableDeferred<Unit>()
        server.lettersGate = gate
        val second = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        second.applyFilterState(titleOrder)
        assertNotNull(second.letterJump.value, "the rail shows before the fresh index arrives")
        awaitState("fresh letters request") { server.lettersRequests.size == 2 }
        gate.complete(Unit)

        // Another filter set is another list, with no copy yet.
        server.lettersGate = CompletableDeferred()
        second.applyFilterState(CatalogFilterState(sort = "title", order = "desc"))
        assertNull(second.letterJump.value)
        server.lettersGate?.complete(Unit)
    }

    @Test
    fun librariesTitleSortJumpsWithinTheLibraryAndScope() = runCatalogTest {
        val server = FakeServer()
        val viewModel = LibrariesViewModel(
            personalDataRepository = server.personal,
            sectionRepository = server.sections,
            catalogRepository = server.catalog,
        )
        awaitState("libraries") { !viewModel.uiState.value.isLoadingLibraries }
        viewModel.selectTab(LibrariesSubtab.Browse)
        awaitState("first page") { viewModel.uiState.value.catalogItems.isNotEmpty() }
        assertNull(viewModel.letterJump.value)

        viewModel.selectBrowseSort(LibraryBrowseSort.Title)
        awaitState("letter index") { viewModel.letterJump.value != null }
        assertEquals("1", server.lettersRequests.single()["library_id"])
        assertEquals("asc", server.lettersRequests.single()["order"])
        assertEquals("100", server.catalogRequests.last { it["seek"] == null }["limit"])
        assertEquals(210, jumpPosition { viewModel.letterJumpPosition("M") })

        viewModel.selectBrowseSort(LibraryBrowseSort.RecentlyAdded)
        assertNull(viewModel.letterJump.value)
    }

    @Test
    fun browseDensitySelectionUpdatesLayoutWithoutReloadingCatalog() = runCatalogTest {
        val server = FakeServer()
        val viewModel = BrowseViewModel(server.catalog, SavedStateHandle(mapOf("libraryId" to "1")))
        awaitState("first page") { viewModel.uiState.value.items.isNotEmpty() }
        val catalogRequestCount = server.catalogRequests.size

        viewModel.selectViewDensity(CatalogViewDensity.Compact)

        assertEquals(CatalogViewDensity.Compact, viewModel.uiState.value.catalogDensity)
        assertEquals(catalogRequestCount, server.catalogRequests.size)
    }

    @Test
    fun librariesDensitySelectionUpdatesLayoutWithoutReloadingCatalog() = runCatalogTest {
        val server = FakeServer()
        val viewModel = LibrariesViewModel(
            personalDataRepository = server.personal,
            sectionRepository = server.sections,
            catalogRepository = server.catalog,
        )
        awaitState("libraries") { !viewModel.uiState.value.isLoadingLibraries }
        viewModel.selectTab(LibrariesSubtab.Browse)
        awaitState("first page") { viewModel.uiState.value.catalogItems.isNotEmpty() }
        val catalogRequestCount = server.catalogRequests.size

        viewModel.selectViewDensity(CatalogViewDensity.Comfortable)

        assertEquals(CatalogViewDensity.Comfortable, viewModel.uiState.value.catalogDensity)
        assertEquals(catalogRequestCount, server.catalogRequests.size)
    }

    private fun runCatalogTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** A jump's brief wait for its titles runs in real time, like the mock engine. */
    private suspend fun jumpPosition(read: suspend () -> Int?): Int? = withContext(Dispatchers.IO) { read() }

    /**
     * Wait for the view model to reach a state, in real time: the Ktor mock
     * engine answers on its own threads, which the test scheduler can neither
     * see nor advance. The budget catches a hang, not latency on a loaded
     * build machine.
     */
    private suspend fun awaitState(description: String, predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            val reached = withTimeoutOrNull(30_000L) {
                while (!predicate()) delay(10)
                true
            }
            assertTrue(reached == true, "view model never reached $description")
        }
    }

    /**
     * A library of [TOTAL] titles in title order, `t0`…, whose letter index
     * puts `#` at 0, A at 10, M at 210 and Z at 280.
     */
    private class FakeServer(private val lettersStatus: HttpStatusCode = HttpStatusCode.OK) {
        val catalogRequests: MutableList<Map<String, String?>> = Collections.synchronizedList(mutableListOf())
        val lettersRequests: MutableList<Map<String, String?>> = Collections.synchronizedList(mutableListOf())
        @Volatile var lettersGate: CompletableDeferred<Unit>? = null

        private val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/v2/user/libraries" -> respondJson(
                        """{"items":[{"id":"1","name":"Films","type":"movies","sort_order":0}],"page":{"has_more":false}}""",
                    )
                    "/api/v2/library/1/sections" -> respondJson("""{"sections":[]}""")
                    "/api/v2/catalog/filters" -> respondJson(
                        """{"genres":[],"studios":[],"networks":[],"countries":[],"content_ratings":[],"original_languages":[],"authors":[],"narrators":[],"series":[]}""",
                    )
                    "/api/v2/catalog/letters" -> {
                        val body = SiloJson.parseToJsonElement((request.body as TextContent).text).jsonObject
                        lettersRequests += body.filterKeys { it != "groups" }.mapValues { it.value.jsonPrimitive.content }
                        lettersGate?.await()
                        if (lettersStatus != HttpStatusCode.OK) {
                            respond("", lettersStatus)
                        } else {
                            respondJson(LETTERS)
                        }
                    }
                    "/api/v2/catalog" -> {
                        val parameters = request.url.parameters
                        catalogRequests += parameters.names().associateWith { parameters[it] }
                        val start = parameters["seek"]?.toInt() ?: 0
                        val limit = parameters["limit"]?.toInt() ?: 50
                        respondJson(page(start, limit))
                    }
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }

        val personal = PersonalDataRepository(PersonalDataApi(client))
        val sections = SectionRepository(SectionApi(client))
        val catalog = CatalogRepository(CatalogApi(client))

        private fun page(start: Int, limit: Int): String {
            val count = minOf(limit, TOTAL - start).coerceAtLeast(0)
            return buildJsonObject {
                putJsonArray("items") {
                    repeat(count) { addJsonObject { put("content_id", "t${start + it}"); put("type", "movie"); put("title", "Title ${start + it}") } }
                }
                putJsonObject("page") {
                    val more = start + count < TOTAL
                    put("has_more", more)
                    if (more) put("next_cursor", "after-${start + count}")
                }
                put("total", TOTAL)
                put("total_exact", true)
                put("window_cursor", "window")
            }.toString()
        }

        private fun MockRequestHandleScope.respondJson(body: String) = respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

        companion object {
            const val TOTAL = 300
            val LETTERS = """
                {"total":300,
                 "letters":[{"letter":"#","count":10,"position":0},{"letter":"A","count":200,"position":10},
                            {"letter":"M","count":70,"position":210},{"letter":"Z","count":20,"position":280}],
                 "runs":[{"letter":"#","position":0},{"letter":"A","position":10},{"letter":"M","position":210},{"letter":"Z","position":280}]}
            """.trimIndent()
        }
    }
}
