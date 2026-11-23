package org.siloserver.silo.tv.ui.screens.search

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.model.catalog.boxSetCard
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** Matching box sets lead the TV search grid; servers without box sets are never asked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TvSearchBoxSetsTest {
    @Test
    fun `box sets lead the grid when a library has them on`() = search(boxSetsEnabled = true) { viewModel, paths ->
        assertEquals(listOf("1241", null), viewModel.uiState.value.items.map { it.boxSetCard?.id })
        assertEquals(1, paths.count { it == "/api/v2/catalog/box-sets" })
    }

    @Test
    fun `a server without box sets searches exactly as before`() = search(boxSetsEnabled = false) { viewModel, paths ->
        assertEquals(listOf("hp1"), viewModel.uiState.value.items.map { it.contentId })
        assertEquals(0, paths.count { it == "/api/v2/catalog/box-sets" })
    }

    private fun search(
        boxSetsEnabled: Boolean,
        verify: (TvSearchViewModel, List<String>) -> Unit,
    ) = runTest {
        // Real time, like the mock server: on virtual time an idle scheduler
        // would skip straight past the box set grace period while a real
        // request is still answering.
        val main = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val flag = if (boxSetsEnabled) ""","box_sets_enabled":true""" else ""
        val client = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                val body = when (request.url.encodedPath) {
                    "/api/v2/user/libraries" ->
                        """{"items":[{"id":"3","name":"Movies","type":"movies","sort_order":0$flag}],"page":{"has_more":false}}"""
                    "/api/v2/catalog/box-sets" ->
                        """{"items":[{"id":"1241","library_id":"3","title":"Harry Potter Collection","movie_count":8,"played_count":0}]}"""
                    "/api/v2/catalog" ->
                        """{"items":[{"content_id":"hp1","type":"movie","title":"Philosopher's Stone"}],"page":{"has_more":false},"total":1,"total_exact":true,"window_cursor":"w"}"""
                    else -> """{"items":[]}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(SiloJson) } }
        val tokens = TokenManagerImpl()
        val viewModel = TvSearchViewModel(
            catalogRepository = CatalogRepository(CatalogApi(client)),
            personalDataRepository = PersonalDataRepository(PersonalDataApi(client)),
            libraryScopeStore = TvLibraryScopeStore(ApplicationProvider.getApplicationContext(), tokens),
        )
        try {
            awaitState { TvSearchMediaType.Movies in viewModel.uiState.value.availableMediaTypes }
            viewModel.onQueryChanged("harry potter")
            viewModel.submitSearch()
            awaitState { viewModel.uiState.value.items.any { it.contentId == "hp1" } }
            verify(viewModel, paths.toList())
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(30_000) { while (!predicate()) delay(10) }
        }
    }
}
