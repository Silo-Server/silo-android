package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TvCollectionCursorTest {
    @Test fun hiddenBookPageAdvancesOpaqueCursorThenFailureRequiresReload() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val cursors = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/filters")) {
                respond("""{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertEquals("/api/v2/catalog", request.url.encodedPath)
                assertEquals("library_collection", request.url.parameters["source"])
                assertNull(request.url.parameters["offset"])
                assertNull(request.url.parameters["sort"])
                val cursor = request.url.parameters["cursor"]
                cursors += cursor
                if (cursor == "expire") respond("""{"type":"https://siloserver.org/problems/invalid_cursor","title":"Expired","status":400,"detail":"Expired"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                else {
                    val id = if (cursor == null) "book" else "film"
                    val type = if (cursor == null) "ebook" else "movie"
                    val next = if (cursor == null) "visible" else "expire"
                    respond("""{"items":[{"content_id":"$id","type":"$type","title":"$id"}],"page":{"has_more":true,"next_cursor":"$next"},"total":3,"total_exact":true,"window_cursor":"window"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        })
        val vm = TvLibraryCollectionDetailViewModel(SectionRepository(SectionApi(client)), CatalogRepository(CatalogApi(client)), 7, "c1", "Collection")
        try {
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.uiState.first { !it.isLoading } } }
            assertEquals(listOf(null, "visible"), cursors)
            assertEquals(listOf("film"), vm.uiState.value.items.map { it.contentId })
            vm.loadMore()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.uiState.first { it.error != null } } }
            assertNotNull(vm.uiState.value.error)
            assertEquals(listOf("film"), vm.uiState.value.items.map { it.contentId })
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(null, "visible", "expire"), cursors)
            vm.retry()
            withContext(Dispatchers.Default) { withTimeout(10_000) { vm.uiState.first { !it.isLoading } } }
            assertEquals(listOf(null, "visible", "expire", null, "visible"), cursors)
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
