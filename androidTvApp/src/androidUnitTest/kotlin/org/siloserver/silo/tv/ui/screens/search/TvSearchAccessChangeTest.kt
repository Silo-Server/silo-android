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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import kotlin.test.Test
import kotlin.test.assertEquals

/** An access change reloads the media-type chips; the first load must not land after it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TvSearchAccessChangeTest {

    @Test
    fun `the first library load does not overwrite the access change reload`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val firstLoad = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val client = HttpClient(
            MockEngine { request ->
                check(request.url.encodedPath == "/api/v2/user/libraries") { "Unexpected ${request.url}" }
                // Before the change the viewer only had an ebook library; after it, movies too.
                val body = if (requests.incrementAndGet() == 1) {
                    firstLoad.await()
                    """{"items":[{"id":"1","name":"Books","type":"ebooks","sort_order":0}],"page":{"has_more":false}}"""
                } else {
                    """{"items":[{"id":"2","name":"Movies","type":"movies","sort_order":0}],"page":{"has_more":false}}"""
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
            awaitState { requests.get() == 1 }
            viewModel.refreshForAccessChange()
            awaitState { TvSearchMediaType.Movies in viewModel.uiState.value.availableMediaTypes }
            firstLoad.complete(Unit)
            withContext(Dispatchers.IO) { delay(200) }

            assertEquals(
                listOf(TvSearchMediaType.All, TvSearchMediaType.Movies, TvSearchMediaType.Series),
                viewModel.uiState.value.availableMediaTypes,
            )
        } finally {
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(30_000) { while (!predicate()) delay(10) }
        }
    }
}
