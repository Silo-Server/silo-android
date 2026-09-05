package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvAudiobookCursorTest {
    @Test fun staleAuthorCompletionCannotReplaceCurrentSeriesCursor() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val authorStarted = CompletableDeferred<Unit>()
        val releaseAuthor = CompletableDeferred<Unit>()
        val seriesPageCursor = CompletableDeferred<String?>()
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v1/library/7/sections" -> """{"sections":[]}"""
                "/api/v2/catalog/filters" -> """{"genres":[],"studios":[],"networks":[],"countries":[],"original_languages":[],"content_ratings":[],"authors":[],"narrators":[],"series":[]}"""
                "/api/v2/catalog/audiobook-groups" -> {
                    val group = request.url.parameters["group_by"]
                    val cursor = request.url.parameters["cursor"]
                    if (group == "author") {
                        authorStarted.complete(Unit)
                        releaseAuthor.await()
                    }
                    if (group == "series" && cursor != null) seriesPageCursor.complete(cursor)
                    val name = if (cursor == null) group else "series-page-two"
                    val page = if (cursor == null) """{"has_more":true,"next_cursor":"$group-next"}""" else """{"has_more":false}"""
                    """{"items":[{"name":"$name","item_count":1}],"page":$page,"total":2,"total_exact":true}"""
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = TvLibraryDetailViewModel(SectionRepository(SectionApi(client)), CatalogRepository(CatalogApi(client)), 7, "Library", "audiobooks")
        try {
            vm.onTabSelected(TvLibraryTab.Authors)
            withContext(Dispatchers.Default) { withTimeout(10_000) { authorStarted.await() } }
            val oldJobs = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            vm.onTabSelected(TvLibraryTab.Series)
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.uiState.first { it.audiobookGroups.singleOrNull()?.name == "series" } }
            }
            releaseAuthor.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { oldJobs.joinAll() } }
            assertEquals("series", vm.uiState.value.audiobookGroups.single().name)
            vm.loadMoreAudiobookGroups()
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { vm.uiState.first { !it.audiobookGroupsLoadingMore } }
            }
            assertNull(vm.uiState.value.audiobookGroupsError)
            assertTrue(seriesPageCursor.isCompleted, "The active series continuation must reach the server")
            assertEquals("series-next", seriesPageCursor.await())
            assertEquals(listOf("series", "series-page-two"), vm.uiState.value.audiobookGroups.map { it.name })
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
