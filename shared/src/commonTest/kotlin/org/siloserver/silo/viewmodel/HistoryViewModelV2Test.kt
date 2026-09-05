package org.siloserver.silo.viewmodel

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.PersonalDataRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelV2Test {
    private fun card(id: String) = """{"content_id":"$id","type":"series","title":"Series","watch":{"media_item_id":"episode-$id","watched_at":"2026-01-02T03:04:05.000Z","duration_seconds":12.5,"completed":true}}"""
    private fun page(id: String?, next: String? = null) = """{"items":[${id?.let(::card).orEmpty()}],"page":{"has_more":${next != null}${next?.let { ",\"next_cursor\":\"$it\"" }.orEmpty()}}}"""
    private suspend fun settled(vm: HistoryViewModel) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { vm.uiState.first { !it.isLoading && !it.isLoadingMore && !it.isRefreshing } }
    }

    @Test fun actualRepositoryPagesEmptyAndDuplicateCardsThenRequiresExplicitReload() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val cursors = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/history", request.url.encodedPath)
            assertNull(request.url.parameters["offset"])
            val cursor = request.url.parameters["cursor"]; cursors += cursor
            if (cursor == "expired") respond("""{"type":"https://siloserver.org/problems/invalid_cursor","title":"Expired","status":400}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            else respond(when (cursor) {
                null -> page(null, "visible")
                "visible" -> page("show", "duplicate")
                "duplicate" -> page("show", "expired")
                else -> error("Unexpected cursor")
            }, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = HistoryViewModel(PersonalDataRepository(PersonalDataApi(client)))
        try {
            settled(vm); assertTrue(vm.uiState.value.items.isEmpty()); assertTrue(vm.uiState.value.hasMore)
            vm.loadMore(); settled(vm)
            assertEquals("show", vm.uiState.value.items.single().contentId)
            assertEquals("episode-show", vm.uiState.value.historyWatches["show"]?.mediaItemId)
            assertNull(vm.uiState.value.total)
            vm.loadMore(); settled(vm)
            assertEquals(1, vm.uiState.value.items.size); assertTrue(vm.uiState.value.hasMore)
            vm.loadMore(); settled(vm)
            assertNotNull(vm.uiState.value.error); assertEquals(1, vm.uiState.value.items.size)
            vm.loadMore(); assertEquals(listOf(null, "visible", "duplicate", "expired"), cursors)
            vm.retry(); settled(vm)
            assertEquals(null, cursors.last()); assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.items.isEmpty()); assertTrue(vm.uiState.value.historyWatches.isEmpty())
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun delayedOldPageCannotReplaceRefreshedCursorOrWitness() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val cursors = mutableListOf<String?>()
        var firstPages = 0
        val client = HttpClient(MockEngine { request ->
            val cursor = request.url.parameters["cursor"]; cursors += cursor
            val body = when (cursor) {
                null -> if (firstPages++ == 0) page("old", "old-next") else page("fresh", "fresh-next")
                "old-next" -> withContext(NonCancellable) {
                    oldStarted.complete(Unit); releaseOld.await(); oldFinished.complete(Unit); page("stale", "stale-next")
                }
                "fresh-next" -> page("newer")
                else -> error("Unexpected cursor $cursor")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = HistoryViewModel(PersonalDataRepository(PersonalDataApi(client)))
        try {
            settled(vm); vm.loadMore()
            withContext(Dispatchers.Default) { withTimeout(10_000) { oldStarted.await() } }
            vm.refresh(); settled(vm)
            releaseOld.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { oldFinished.await() } }
            vm.loadMore(); settled(vm)
            assertEquals(listOf("fresh", "newer"), vm.uiState.value.items.map { it.contentId })
            assertEquals(setOf("fresh", "newer"), vm.uiState.value.historyWatches.keys)
            assertEquals(listOf(null, "old-next", null, "fresh-next"), cursors)
            assertFalse(vm.uiState.value.hasMore)
        } finally { releaseOld.complete(Unit); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun identityTransitionClearsOldCardsAndStartsWithCurrentAuthority() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = DefaultIdentityTransitionBarrier()
        var scope = AuthScopeSnapshot("server", "p1", "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        val requests = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.parameters["cursor"]
            respond(page(scope.profileId, "next-${scope.profileId}"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = HistoryViewModel(PersonalDataRepository(PersonalDataApi(client, tokenManager = tokens)), barrier)
        try {
            settled(vm); assertEquals("p1", vm.uiState.value.items.single().contentId)
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
                assertTrue(vm.uiState.value.items.isEmpty())
                scope = scope.copy(profileId = "p2", identityGeneration = 2)
            }
            settled(vm)
            assertEquals("p2", vm.uiState.value.items.single().contentId)
            assertEquals(setOf("p2"), vm.uiState.value.historyWatches.keys)
            assertEquals(listOf<String?>(null, null), requests)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); client.close(); Dispatchers.resetMain() }
    }
}
