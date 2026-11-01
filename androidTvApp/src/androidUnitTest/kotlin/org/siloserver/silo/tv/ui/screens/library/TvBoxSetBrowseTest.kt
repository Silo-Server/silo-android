package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.model.catalog.boxSetCard
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import org.siloserver.silo.repository.*
import kotlin.test.*

/** A box set library browses grouped; any other library browses exactly as before. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvBoxSetBrowseTest {
    @Test fun boxSetLibraryBrowsesGroupedAndOthersDoNot() = runTest {
        for (enabled in listOf(true, false)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val groups = mutableListOf<String?>()
            val client = HttpClient(MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val body = when {
                        request.url.encodedPath.endsWith("/sections") -> """{"sections":[]}"""
                        request.url.encodedPath.endsWith("/filters") -> """{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""
                        else -> {
                            assertEquals("/api/v2/catalog", request.url.encodedPath)
                            groups += request.url.parameters["group"]
                            """{"items":[{"content_id":"box_set:1241","type":"box_set","title":"Harry Potter Collection",""" +
                                """"box_set":{"id":"1241","library_id":"7","title":"Harry Potter Collection","movie_count":8,"played_count":0}}],""" +
                                """"page":{"has_more":false},"total":1,"total_exact":true,"window_cursor":"w"}"""
                        }
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            })) { install(ContentNegotiation) { json(SiloJson) } }
            val store = ViewModelStore()
            try {
                val vm = TvLibraryDetailViewModel(
                    SectionRepository(SectionApi(client)), CatalogRepository(CatalogApi(client)),
                    7, "Movies", "movies", boxSetsEnabled = enabled,
                )
                store.put("library", vm)
                advanceUntilIdle()
                vm.onTabSelected(TvLibraryTab.Browse); advanceUntilIdle()
                vm.onSortKeySelected(TvLibrarySortOption.Title); advanceUntilIdle()
                assertEquals(List(2) { if (enabled) "box_set" else null }, groups)
                // The card survives the TV type filter and opens its box set.
                assertEquals("1241", vm.uiState.value.browseItems.single().boxSetCard?.id)
            } finally { store.clear(); client.close(); Dispatchers.resetMain() }
        }
    }
}
