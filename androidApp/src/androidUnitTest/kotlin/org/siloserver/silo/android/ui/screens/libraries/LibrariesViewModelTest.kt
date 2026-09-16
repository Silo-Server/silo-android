package org.siloserver.silo.android.ui.screens.libraries

import org.siloserver.silo.network.apiv2.ApiV2Gate

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import org.siloserver.silo.android.ui.screens.browse.BrowsePrefsStore
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.LibrarySectionItemsV2Api
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
// BrowsePrefsStore writes through real SharedPreferences, so the persistence
// regression below needs an Android context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LibrariesViewModelTest {
    @Test
    fun recommendedLatePinDoesNotPublish() = runTest {
        val fixture = DeferredLibrariesFixture(setOf("sections:1"))
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            val request = viewModel.onlyActiveRequest()
            fixture.owner = fixture.owner.copy(profileToken = "new")
            fixture.complete("sections:1", sectionsBody("stale"))
            request.join()
            assertEquals(emptyList(), viewModel.uiState.value.sections)
            assertEquals(false, viewModel.uiState.value.isLoadingSections)
        } finally { store.clear(); Dispatchers.resetMain(); fixture.close() }
    }

    @Test
    fun recommendedResponseFromPreviousLibraryCannotReplaceCurrentLibraryRows() = runTest {
        val fixture = DeferredLibrariesFixture(
            deferredKeys = setOf("sections:1", "sections:2"),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            val staleRequest = viewModel.onlyActiveRequest()

            viewModel.selectLibrary(2)
            fixture.awaitRequest("sections:2")
            fixture.complete("sections:2", sectionsBody("current"))
            viewModel.uiState.first { it.sections.map { section -> section.id } == listOf("current") }

            fixture.complete("sections:1", sectionsBody("stale"))
            staleRequest.join()

            assertEquals(2, viewModel.uiState.value.selectedLibraryId)
            assertEquals(listOf("current"), viewModel.uiState.value.sections.map { it.id })
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    @Test
    fun browseResponseFromPreviousSortCannotReplaceCurrentQueryGrid() = runTest {
        val fixture = DeferredLibrariesFixture(
            deferredKeys = setOf(
                "catalog:1:added_at:desc",
                "catalog:1:title:asc",
            ),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            viewModel.uiState.first { !it.isLoadingSections }
            viewModel.selectTab(LibrariesSubtab.Browse)
            fixture.awaitRequest("catalog:1:added_at:desc")
            val staleRequest = viewModel.onlyActiveRequest()

            viewModel.selectBrowseSort(LibraryBrowseSort.Title)
            fixture.awaitRequest("catalog:1:title:asc")
            fixture.complete("catalog:1:title:asc", catalogBody("current"))
            viewModel.uiState.first {
                it.catalogItems.map { item -> item.contentId } == listOf("current")
            }

            fixture.complete("catalog:1:added_at:desc", catalogBody("stale"))
            staleRequest.join()

            assertEquals(LibraryBrowseSort.Title, viewModel.uiState.value.browseSort)
            assertEquals(listOf("current"), viewModel.uiState.value.catalogItems.map { it.contentId })
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    @Test
    fun browseResponseFromPreviousFilterStateCannotReplaceCurrentQueryGrid() = runTest {
        val fixture = DeferredLibrariesFixture(
            deferredKeys = setOf(
                "catalog:1:added_at:desc",
                "catalog:1:added_at:desc:filtered",
            ),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            viewModel.viewModelScope.launch { delay(1) }
            fixture.awaitRequest("sections:1")
            viewModel.uiState.first { !it.isLoadingSections }
            viewModel.selectTab(LibrariesSubtab.Browse)
            fixture.awaitRequest("catalog:1:added_at:desc")
            val staleRequest = viewModel.onlyActiveRequest()

            val dramaOnly = CatalogFilterState(
                selections = mapOf(CatalogFacet.Genre to setOf("Drama")),
            )
            viewModel.applyFilterState(dramaOnly)
            fixture.awaitRequest("catalog:1:added_at:desc:filtered")
            fixture.complete(
                "catalog:1:added_at:desc:filtered",
                catalogBody("current-filter"),
            )
            viewModel.uiState.first {
                it.catalogItems.map { item -> item.contentId } == listOf("current-filter")
            }

            fixture.complete("catalog:1:added_at:desc", catalogBody("stale-unfiltered"))
            staleRequest.join()

            assertEquals(dramaOnly, viewModel.uiState.value.filterState)
            assertEquals(
                listOf("current-filter"),
                viewModel.uiState.value.catalogItems.map { it.contentId },
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    @Test
    fun delayedFilterVocabularyStillAppliesAfterLoadingNextPage() = runTest {
        val firstPageKey = "catalog:1:added_at:desc:0"
        val secondPageKey = "catalog:1:added_at:desc:1"
        val fixture = DeferredLibrariesFixture(
            deferredKeys = setOf("filters", firstPageKey, secondPageKey),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            viewModel.uiState.first { !it.isLoadingSections }
            viewModel.selectTab(LibrariesSubtab.Browse)
            fixture.awaitRequest("filters")
            fixture.awaitRequest(firstPageKey)
            val filterRequest = viewModel.onlyActiveRequest()
            fixture.complete(firstPageKey, catalogPageBody("page-1", hasMore = true))
            viewModel.uiState.first {
                it.catalogItems.map { item -> item.contentId } == listOf("page-1") &&
                    it.catalogHasMore
            }

            viewModel.loadMoreCatalog()
            fixture.awaitRequest(secondPageKey)
            fixture.complete(secondPageKey, catalogPageBody("page-2", hasMore = false))
            viewModel.uiState.first {
                it.catalogItems.map { item -> item.contentId } == listOf("page-1", "page-2")
            }

            fixture.complete("filters", filtersBody("Drama"))
            filterRequest.join()

            assertEquals(listOf("Drama"), viewModel.uiState.value.availableFilters?.genres)
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    @Test
    fun collectionsResponseFromPreviousLibraryCannotReplaceCurrentLibraryCollections() = runTest {
        val fixture = DeferredLibrariesFixture(
            deferredKeys = setOf("collections:1", "collections:2"),
        )
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val viewModel = fixture.viewModel()
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            viewModel.uiState.first { !it.isLoadingSections }
            viewModel.selectTab(LibrariesSubtab.Collections)
            fixture.awaitRequest("collections:1")
            val staleRequest = viewModel.onlyActiveRequest()

            viewModel.selectLibrary(2)
            fixture.awaitRequest("collections:2")
            fixture.complete("collections:2", collectionsBody("current"))
            viewModel.uiState.first {
                it.collections.map { collection -> collection.id } == listOf("current")
            }

            fixture.complete("collections:1", collectionsBody("stale"))
            staleRequest.join()

            assertEquals(2, viewModel.uiState.value.selectedLibraryId)
            assertEquals(listOf("current"), viewModel.uiState.value.collections.map { it.id })
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    @Test
    fun browseSortIsPersistedAndRestoredOnAFreshViewModel() = runTest {
        val fixture = DeferredLibrariesFixture(deferredKeys = emptySet())
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val browsePrefs = BrowsePrefsStore(
            context = ApplicationProvider.getApplicationContext(),
            serverRegistry = FakeServerRegistry(),
        )
        val viewModel = fixture.viewModel(browsePrefs = browsePrefs)
        val store = ViewModelStore().also { it.put("libraries", viewModel) }
        try {
            fixture.awaitRequest("sections:1")
            viewModel.uiState.first { !it.isLoadingSections }
            viewModel.selectTab(LibrariesSubtab.Browse)
            fixture.awaitRequest("catalog:1:added_at:desc")

            viewModel.selectBrowseSort(LibraryBrowseSort.Title)
            // The sort is persisted before the reload is issued, so awaiting
            // the re-sorted request is a sufficient sync point.
            fixture.awaitRequest("catalog:1:title:asc")

            val restored = fixture.viewModel(browsePrefs = browsePrefs)
            val restoredStore = ViewModelStore().also { it.put("libraries-restored", restored) }
            try {
                val state = restored.uiState.first {
                    !it.isLoadingLibraries && it.selectedLibraryId == 1
                }
                assertEquals(LibraryBrowseSort.Title, state.browseSort)
                assertEquals("title", state.filterState.sort)
                assertEquals("asc", state.filterState.order)
            } finally {
                restoredStore.clear()
            }
        } finally {
            store.clear()
            Dispatchers.resetMain()
            fixture.close()
        }
    }

    private suspend fun LibrariesViewModel.onlyActiveRequest(): Job = withTimeout(5_000) {
        while (true) {
            val activeRequests = viewModelScope.coroutineContext[Job]
                ?.children
                ?.filter { it.isActive }
                ?.toList()
                .orEmpty()
            when (activeRequests.size) {
                0 -> error("Expected an active Libraries request")
                1 -> return@withTimeout activeRequests.single()
                else -> delay(1)
            }
        }
        error("Unreachable")
    }

    /** BrowsePrefsStore persists nothing without an active server + profile. */
    private class FakeServerRegistry : ServerRegistry {
        private val entry = ServerEntry(
            id = "server-1",
            url = "https://silo.test",
            profileId = "profile-1",
        )
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry))
        override val activeServerId: StateFlow<String?> = MutableStateFlow(entry.id)
        override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(entry)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = entry.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    private class DeferredLibrariesFixture(
        private val deferredKeys: Set<String>,
    ) {
        var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
        private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
        private val requests = Channel<String>(Channel.UNLIMITED)
        private val pendingRequests = mutableListOf<String>()
        private val responses = deferredKeys.associateWith { CompletableDeferred<String>() }
        private val client = HttpClient(
            MockEngine { request ->
                val key = when (request.url.encodedPath) {
                    "/api/v2/user/libraries" -> "libraries"
                    "/api/v2/catalog/filters" -> "filters"
                    // Unfiltered browses are GET /catalog with `sort=-field`; facet
                    // filters switch to POST /catalog/query with the query in the body.
                    "/api/v2/catalog", "/api/v2/catalog/query" -> {
                        val body = (request.body as? io.ktor.http.content.TextContent)
                            ?.let { SiloJson.parseToJsonElement(it.text).jsonObject }
                        fun field(name: String): String? = body?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull }
                            ?: request.url.parameters[name]
                        val rawSort = field("sort")
                        val sort = rawSort?.removePrefix("-")
                        val order = field("order") ?: if (rawSort?.startsWith("-") == true) "desc" else "asc"
                        val baseKey = "catalog:${field("library_id")}:$sort:$order"
                        val filterSuffix = if ((body?.get("groups") as? JsonArray)?.isNotEmpty() == true) {
                            ":filtered"
                        } else {
                            ""
                        }
                        val offsetSuffix = ":${field("cursor") ?: "0"}"
                        listOf(
                            baseKey + filterSuffix + offsetSuffix,
                            baseKey + filterSuffix,
                            baseKey + offsetSuffix,
                            baseKey,
                        ).firstOrNull(responses::containsKey) ?: (baseKey + filterSuffix)
                    }
                    else -> {
                        val segments = request.url.encodedPath.split('/')
                        val family = segments.last()
                        "$family:${segments[4]}"
                    }
                }
                requests.send(key)
                val body = responses[key]?.await() ?: immediateBody(key)
                respondJson(body)
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }

        fun viewModel(browsePrefs: BrowsePrefsStore? = null) = LibrariesViewModel(
            personalDataRepository = PersonalDataRepository(PersonalDataApi(client)),
            sectionRepository = SectionRepository(SectionApi(client, sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted))),
            catalogRepository = CatalogRepository(CatalogApi(client)),
            browsePrefs = browsePrefs,
        )

        suspend fun awaitRequest(expected: String) {
            val pendingIndex = pendingRequests.indexOf(expected)
            if (pendingIndex >= 0) {
                pendingRequests.removeAt(pendingIndex)
                return
            }
            while (true) {
                val actual = requests.receive()
                if (actual == expected) return
                pendingRequests += actual
            }
        }

        fun complete(key: String, body: String) {
            checkNotNull(responses[key]) { "No deferred response for $key" }.complete(body)
        }

        fun close() {
            client.close()
        }

        private fun immediateBody(key: String): String = when {
            key == "libraries" -> """
                {"items":[
                  {"id":"1","name":"First","type":"movies","sort_order":0},
                  {"id":"2","name":"Second","type":"movies","sort_order":1}
                ],"page":{"has_more":false}}
            """.trimIndent()
            key == "filters" -> filtersBody(null)
            key.startsWith("sections:") -> """{"sections":[]}"""
            key.startsWith("collections:") -> """{"library_id":"1","collections":[],"groups":[]}"""
            key.startsWith("catalog:") -> catalogBody("immediate")
            else -> error("Unexpected request key $key")
        }

        private fun MockRequestHandleScope.respondJson(body: String) = respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    companion object {
        private fun sectionsBody(id: String) = """
            {
              "sections":[{
                "id":"$id",
                "section_type":"recently_added",
                "title":"$id",
                "items":[{"content_id":"$id-item","type":"movie","title":"$id"}]
              }]
            }
        """.trimIndent()

        private fun catalogBody(id: String) = """
            {
              "total":1,
              "total_exact":true,
              "window_cursor":"window",
              "page":{"has_more":false},
              "items":[{"content_id":"$id","type":"movie","title":"$id"}]
            }
        """.trimIndent()

        /** The second page is requested with `cursor=1`, which the fixture keys as `:1`. */
        private fun catalogPageBody(id: String, hasMore: Boolean) = """
            {
              "total":2,
              "total_exact":true,
              "window_cursor":"window",
              "page":{"has_more":$hasMore${if (hasMore) ""","next_cursor":"1"""" else ""}},
              "items":[{"content_id":"$id","type":"movie","title":"$id"}]
            }
        """.trimIndent()

        private fun filtersBody(genre: String?) =
            """{"genres":[${genre?.let { "\"$it\"" } ?: ""}],"studios":[],"networks":[],"countries":[],""" +
                """"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""

        private fun collectionsBody(id: String) =
            """{"library_id":"1","collections":[{"id":"$id","title":"$id","library_id":"1","library_ids":["1"],""" +
                """"collection_type":"regular","poster_url":"","item_count":0,"sort_order":0}],"groups":[]}"""
    }
}
