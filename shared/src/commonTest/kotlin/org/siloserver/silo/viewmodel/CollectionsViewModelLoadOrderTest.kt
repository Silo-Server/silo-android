package org.siloserver.silo.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.siloserver.silo.network.api.CollectionApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.CollectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsViewModelLoadOrderTest {

    @Test
    fun anOlderLoadDoesNotOverwriteTheRefreshThatReplacedIt() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val client = HttpClient(
            MockEngine {
                val call = ++calls
                val id = if (call == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    "before"
                } else {
                    "after"
                }
                respond(
                    """{"items":[{"id":"$id","name":"$id"}],"groups":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        try {
            val viewModel = CollectionsViewModel(CollectionRepository(CollectionApi(client, ApiV2Gate.Unrestricted)))
            withContext(Dispatchers.Default) { firstEntered.await() }

            // An access change refreshes while the initial load still waits on
            // the server's answer under the old policy.
            viewModel.refresh()
            withContext(Dispatchers.Default) {
                viewModel.uiState.first { state -> state.collections.map { it.id } == listOf("after") }
                releaseFirst.complete(Unit)
                delay(200)
            }

            assertEquals(listOf("after"), viewModel.uiState.value.collections.map { it.id })
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }
}
