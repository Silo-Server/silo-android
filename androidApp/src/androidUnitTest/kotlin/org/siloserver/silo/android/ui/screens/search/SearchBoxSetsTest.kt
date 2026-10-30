package org.siloserver.silo.android.ui.screens.search

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** Box sets lead search results, and only servers with box sets are asked for them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchBoxSetsTest {
    private fun runSearch(
        boxSetDelayMillis: Long = 0,
        block: suspend TestScope.(SearchViewModel, MutableList<String>) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val path = request.url.encodedPath
                paths += path
                if (path == "/api/v2/catalog/box-sets") kotlinx.coroutines.delay(boxSetDelayMillis)
                val body = when (path) {
                    "/api/v2/catalog/box-sets" ->
                        """{"items":[{"id":"1241","library_id":"3","title":"Harry Potter Collection","movie_count":8,"played_count":0}]}"""
                    "/api/v2/catalog" ->
                        """{"items":[{"content_id":"hp1","type":"movie","title":"Philosopher's Stone"}],"page":{"has_more":false},"total":1,"total_exact":true,"window_cursor":"w"}"""
                    else -> """{"items":[]}"""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val vm = SearchViewModel(CatalogRepository(CatalogApi(client)))
            store.put("search", vm)
            block(vm, paths)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun matchingBoxSetsArriveWithTheTitles() = runSearch { vm, paths ->
        vm.setBoxSetsAvailable(true)
        vm.onQueryChanged("harry potter"); advanceUntilIdle()
        assertEquals(listOf("1241"), vm.uiState.value.boxSets.map { it.id })
        assertEquals(listOf("hp1"), vm.uiState.value.results.map { it.contentId })
        assertEquals(1, paths.count { it == "/api/v2/catalog/box-sets" })
    }

    @Test fun aServerWithoutBoxSetsIsNeverAsked() = runSearch { vm, paths ->
        vm.onQueryChanged("harry potter"); advanceUntilIdle()
        assertEquals(emptyList(), vm.uiState.value.boxSets)
        assertEquals(listOf("hp1"), vm.uiState.value.results.map { it.contentId })
        assertEquals(0, paths.count { it == "/api/v2/catalog/box-sets" })
    }

    @Test fun anAudioSearchLeavesBoxSetsOut() = runSearch { vm, paths ->
        vm.setBoxSetsAvailable(true)
        vm.setAvailableModes(listOf(org.siloserver.silo.model.navigation.MediaMode.Video, org.siloserver.silo.model.navigation.MediaMode.Audio))
        vm.onMediaTypeChanged(MobileSearchMediaType.Audio)
        vm.onQueryChanged("harry potter"); advanceUntilIdle()
        assertEquals(emptyList(), vm.uiState.value.boxSets)
        assertEquals(0, paths.count { it == "/api/v2/catalog/box-sets" })
    }

    @Test fun aSlowBoxSetSearchDoesNotHoldTheTitlesBack() = runSearch(boxSetDelayMillis = 10_000) { vm, _ ->
        vm.setBoxSetsAvailable(true)
        vm.onQueryChanged("harry potter")
        // Debounce, then the grace period, but far short of the slow answer.
        advanceTimeBy(300 + org.siloserver.silo.repository.BOX_SET_SEARCH_GRACE_MILLIS + 100)
        runCurrent()
        assertEquals(listOf("hp1"), vm.uiState.value.results.map { it.contentId })
        assertEquals(emptyList(), vm.uiState.value.boxSets)
        assertEquals(false, vm.uiState.value.isSearching)
    }
}
