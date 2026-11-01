package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoxSetViewModelTest {
    private val body = """{"id":"1241","library_id":"3","title":"Harry Potter Collection","movie_count":2,"played_count":0,""" +
        """"items":[{"content_id":"hp1","type":"movie","title":"One"},{"content_id":"hp2","type":"movie","title":"Two"}]}"""

    private val inProgressBody = body.replace(
        "{\"content_id\":\"hp1\",\"type\":\"movie\",\"title\":\"One\"}",
        "{\"content_id\":\"hp1\",\"type\":\"movie\",\"title\":\"One\",\"position_seconds\":600}",
    )

    private fun json(status: HttpStatusCode, text: String) = Triple(status, text,
        if (status.isSuccess()) "application/json" else "application/problem+json")

    private fun runWith(responses: MutableList<Triple<HttpStatusCode, String, String>>, block: suspend kotlinx.coroutines.test.TestScope.(BoxSetViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                assertEquals("/api/v2/catalog/box-sets/1241", request.url.encodedPath)
                val (status, text, type) = responses.removeFirst()
                if (status == HttpStatusCode.ServiceUnavailable) throw java.io.IOException("offline")
                respond(text, status, headersOf(HttpHeaders.ContentType, type))
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val store = ViewModelStore()
        try {
            val vm = BoxSetViewModel(CatalogRepository(CatalogApi(client)), "1241", "3")
            store.put("box-set", vm)
            block(vm)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }

    @Test fun loadsTheBoxSetWithItsMovies() = runWith(mutableListOf(json(HttpStatusCode.OK, body))) { vm ->
        assertTrue(vm.uiState.value.isLoading)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("hp1", "hp2"), state.detail?.items?.map { it.contentId })
    }

    @Test fun aMissingBoxSetIsUnavailableNotAnError() = runWith(mutableListOf(
        json(HttpStatusCode.NotFound, """{"type":"about:blank","title":"Not Found","status":404}"""),
    )) { vm ->
        advanceUntilIdle()
        assertTrue(vm.uiState.value.unavailable)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.detail)
    }

    @Test fun aFailedRefreshKeepsThePageAndAGoodOneUpdatesIt() = runWith(mutableListOf(
        json(HttpStatusCode.OK, body),
        json(HttpStatusCode.ServiceUnavailable, ""),
        json(HttpStatusCode.OK, body.replace("\"played_count\":0", "\"played_count\":1")),
    )) { vm ->
        advanceUntilIdle()
        vm.refresh(); advanceUntilIdle()
        assertEquals(0, vm.uiState.value.detail?.boxSet?.playedCount)
        assertNull(vm.uiState.value.error)
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.detail?.boxSet?.playedCount)
    }

    @Test fun openingReadsTheBoxSetOnceAndAReturnReadsItAgain() = runWith(mutableListOf(
        json(HttpStatusCode.OK, body),
        json(HttpStatusCode.OK, body.replace("\"played_count\":0", "\"played_count\":1")),
    )) { vm ->
        // The screen's first resume lands while the opening read is in flight.
        vm.onScreenResumed()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.detail?.boxSet?.playedCount)
        // Back from a movie: one more read.
        vm.onScreenResumed(); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.detail?.boxSet?.playedCount)
    }

    @Test fun aFailedWatchedWriteOfflinePutsTheMovieBack() = runWith(mutableListOf(
        json(HttpStatusCode.OK, inProgressBody),
        // Offline: the re-read after the failed write fails as well.
        json(HttpStatusCode.ServiceUnavailable, ""),
    )) { vm ->
        advanceUntilIdle()
        vm.onMovieWatchedChanged("hp1", watched = true)
        assertEquals("hp2", org.siloserver.silo.model.catalog.boxSetNextMovie(vm.uiState.value.detail!!.items)?.contentId)
        vm.onMovieWatchedWriteFailed("hp1"); advanceUntilIdle()
        val detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(0, detail.boxSet.playedCount)
        val next = assertNotNull(org.siloserver.silo.model.catalog.boxSetNextMovie(detail.items))
        assertEquals("hp1", next.contentId)
        assertNotEquals(true, next.userState?.played)
        assertEquals(600.0, next.positionSeconds)
    }

    @Test fun aFailedWatchedWriteOnlineSettlesFromTheServer() = runWith(mutableListOf(
        json(HttpStatusCode.OK, inProgressBody),
        // Meanwhile the movie was finished on another device.
        json(HttpStatusCode.OK, body.replace("\"title\":\"One\"}", "\"title\":\"One\",\"user_state\":{\"played\":true}}")
            .replace("\"played_count\":0", "\"played_count\":1")),
    )) { vm ->
        advanceUntilIdle()
        vm.onMovieWatchedChanged("hp1", watched = false)
        vm.onMovieWatchedWriteFailed("hp1"); advanceUntilIdle()
        val detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(1, detail.boxSet.playedCount)
        assertEquals("hp2", org.siloserver.silo.model.catalog.boxSetNextMovie(detail.items)?.contentId)
    }

    @Test fun aMovieMarkedWatchedMovesTheCountAndThePlayTarget() = runWith(mutableListOf(json(HttpStatusCode.OK, body))) { vm ->
        advanceUntilIdle()
        vm.onMovieWatchedChanged("hp1", watched = true)
        val detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(1, detail.boxSet.playedCount)
        assertEquals("hp2", org.siloserver.silo.model.catalog.boxSetNextMovie(detail.items)?.contentId)
        vm.onMovieWatchedChanged("hp1", watched = false)
        assertEquals(0, vm.uiState.value.detail?.boxSet?.playedCount)
    }

    @Test fun aReadInFlightKeepsAWatchedChangeMadeMeanwhile() = runWith(mutableListOf(
        json(HttpStatusCode.OK, body),
        // Started before the change, so it still says neither movie is played.
        json(HttpStatusCode.OK, body),
    )) { vm ->
        advanceUntilIdle()
        vm.refresh()
        vm.onMovieWatchedChanged("hp2", watched = true)
        advanceUntilIdle()
        val detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(1, detail.boxSet.playedCount)
        assertEquals(true, detail.items.single { it.contentId == "hp2" }.userState?.played)
        assertEquals("hp1", org.siloserver.silo.model.catalog.boxSetNextMovie(detail.items)?.contentId)
    }

    @Test fun aReadInFlightDoesNotBringBackAChangeWhoseWriteFailed() = runWith(mutableListOf(
        json(HttpStatusCode.OK, inProgressBody),
        json(HttpStatusCode.OK, inProgressBody),
    )) { vm ->
        advanceUntilIdle()
        vm.refresh()
        vm.onMovieWatchedChanged("hp1", watched = true)
        // The write fails while the read is still out; its own re-read joins that read.
        vm.onMovieWatchedWriteFailed("hp1")
        advanceUntilIdle()
        val detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(0, detail.boxSet.playedCount)
        val next = assertNotNull(org.siloserver.silo.model.catalog.boxSetNextMovie(detail.items))
        assertEquals("hp1", next.contentId)
        assertEquals(600.0, next.positionSeconds)
    }

    @Test fun aReadKeepsAChangeWhoseWriteIsStillOutUntilItLands() = runWith(mutableListOf(
        json(HttpStatusCode.OK, body),
        // Read after hp2's write failed, before hp1's write landed.
        json(HttpStatusCode.OK, body),
        // Read after hp1's write landed and hp1 was unmarked on another device.
        json(HttpStatusCode.OK, body),
    )) { vm ->
        advanceUntilIdle()
        vm.onMovieWatchedChanged("hp1", watched = true)
        vm.onMovieWatchedChanged("hp2", watched = true)
        vm.onMovieWatchedWriteFailed("hp2"); advanceUntilIdle()
        var detail = assertNotNull(vm.uiState.value.detail)
        assertEquals(true, detail.items.single { it.contentId == "hp1" }.userState?.played)
        assertEquals(1, detail.boxSet.playedCount)
        vm.onMovieWatchedWriteSucceeded("hp1")
        vm.refresh(); advanceUntilIdle()
        detail = assertNotNull(vm.uiState.value.detail)
        assertNotEquals(true, detail.items.single { it.contentId == "hp1" }.userState?.played)
        assertEquals(0, detail.boxSet.playedCount)
    }

    @Test fun anAccessChangeThatRefusesTheBoxSetMakesItUnavailable() = runWith(mutableListOf(
        json(HttpStatusCode.OK, body),
        json(HttpStatusCode.Forbidden, """{"type":"about:blank","title":"Forbidden","status":403}"""),
    )) { vm ->
        advanceUntilIdle()
        vm.refreshAfterAccessChange(); advanceUntilIdle()
        assertTrue(vm.uiState.value.unavailable)
        assertNull(vm.uiState.value.detail)
    }
}
