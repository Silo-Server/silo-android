package org.siloserver.silo.viewmodel

import org.siloserver.silo.common.i18n.english
import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestActionMessage
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestReason
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultRequestsApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Notify me when available" on a title someone else requested (Silo 1.0
 * Requests AC1), against the real transport over a mock server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestDetailFollowTest {

    private val viewModels = ViewModelStore()

    @AfterTest
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun titleJson(following: Boolean, requestedByViewer: Boolean = false) =
        """{"media_type":"series","tmdb_id":1399,"title":"Thrones","availability":"missing",""" +
            """"request":{"status":"approved","state":"approved","requestable":false,"reason":"already_requested",""" +
            """"following":$following,"requested_by_viewer":$requestedByViewer}}"""

    /** One mock server; [respondTo] answers each follow call, everything else is a fixed read. */
    private class Server(
        var followSupported: Boolean = true,
        var detail: String,
        var respondTo: (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ) {
        val calls = mutableListOf<String>()
        /** When set, detail reads wait for it, so a read can be held out across a follow. */
        var detailGate: CompletableDeferred<Unit>? = null
    }

    private fun TestScope.harness(server: Server): Triple<RequestsRepository, RequestsFeatureStore, RequestDetailViewModel> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val problem = headersOf(HttpHeaders.ContentType, "application/problem+json")
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
                val path = request.url.encodedPath
                server.calls += "${request.method.value} $path"
                when {
                    path == "/api/v2/requests/status" -> respond(
                        """{"requests_enabled":${server.followSupported},"allowed":true,"state":"available","follow_supported":${server.followSupported}}""",
                        headers = json,
                    )
                    path == "/api/v2/requests/mine" -> respond("""{"items":[],"page":{"has_more":false}}""", headers = json)
                    path == "/api/v2/requests/search" -> respond(
                        """{"page":1,"total_pages":1,"total_results":1,"results":[${titleJson(following = false)}]}""",
                        headers = json,
                    )
                    path.startsWith("/api/v2/requests/detail/") -> {
                        val body = server.detail
                        server.detailGate?.await()
                        respond(body, headers = json)
                    }
                    path.startsWith("/api/v2/requests/follows/") -> {
                        val (status, body) = server.respondTo(request)
                        respond(body, status, if (status.value >= 400) problem else json)
                    }
                    else -> error("unexpected $path")
                }
            }
        })) { install(ContentNegotiation) { json(SiloJson) } }
        val repository = RequestsRepository(DefaultRequestsApi(client, ApiV2Gate.Unrestricted))
        val store = RequestsFeatureStore(repository)
        return Triple(repository, store, RequestDetailViewModel(repository, RequestMediaType.Series, 1399, featureStore = store).also { viewModels.put("detail", it) })
    }

    private suspend fun TestScope.ready(server: Server): Triple<RequestsRepository, RequestsFeatureStore, RequestDetailViewModel> {
        val parts = harness(server)
        parts.second.refresh()
        advanceUntilIdle()
        return parts
    }

    @Test
    fun followingFlipsTheActionAndConfirms() = runTest {
        val server = Server(detail = titleJson(following = false)) { request ->
            assertEquals(HttpMethod.Put, request.method)
            HttpStatusCode.OK to """{"status":"approved","state":"approved","requestable":false,"reason":"already_requested","following":true,"requested_by_viewer":false}"""
        }
        val (repository, _, viewModel) = ready(server)
        val search = RequestSearchViewModel(repository).also { viewModels.put("search", it) }
        search.onQueryChanged("Thrones")
        search.search()
        advanceUntilIdle()

        val before = viewModel.uiState.value
        assertTrue(before.canFollow)
        assertFalse(before.isFollowing)
        assertEquals("Notify me when available", before.followActionTitle.english())

        viewModel.toggleFollow()
        assertTrue(viewModel.uiState.value.isFollowPending)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertFalse(after.isFollowPending)
        assertTrue(after.isFollowing)
        assertTrue(after.canFollow)
        assertEquals("Stop notifying me", after.followActionTitle.english())
        assertEquals(RequestActionMessage.Followed, after.shownFollowNotice)
        assertNull(after.actionErrorMessage)
        assertTrue("PUT /api/v2/requests/follows/series/1399" in server.calls)
        // Other surfaces showing the title agree.
        assertTrue(search.uiState.value.results.single().request.following)
        assertEquals(true, repository.cache.detail(RequestDetailCache.Key(RequestMediaType.Series, 1399))?.request?.following)
    }

    @Test
    fun unfollowingClearsTheFlagFromANoContentAnswer() = runTest {
        val server = Server(detail = titleJson(following = true)) { request ->
            assertEquals(HttpMethod.Delete, request.method)
            HttpStatusCode.NoContent to ""
        }
        val (_, _, viewModel) = ready(server)
        assertTrue(viewModel.uiState.value.isFollowing)

        viewModel.toggleFollow()
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertFalse(after.isFollowing)
        assertTrue(after.canFollow)
        assertEquals("Notify me when available", after.followActionTitle.english())
        assertEquals(RequestActionMessage.Unfollowed, after.shownFollowNotice)
        assertTrue("DELETE /api/v2/requests/follows/series/1399" in server.calls)
    }

    @Test
    fun aFailureSaysSoAndLeavesTheStateAlone() = runTest {
        val server = Server(detail = titleJson(following = false)) {
            HttpStatusCode.InternalServerError to
                """{"type":"https://siloserver.org/docs/api/v2/problems/internal_error","title":"Internal","status":500,"detail":"An unexpected error occurred."}"""
        }
        val (_, _, viewModel) = ready(server)

        viewModel.toggleFollow()
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertFalse(after.isFollowPending)
        assertFalse(after.isFollowing)
        assertTrue(after.canFollow)
        assertEquals("An unexpected error occurred.", after.actionErrorMessage?.text?.english())
        assertNull(after.shownFollowNotice)
    }

    @Test
    fun aDisabledFeatureRereadsTheStatusAndHidesTheAction() = runTest {
        val server = Server(detail = titleJson(following = false)) { HttpStatusCode.OK to "{}" }
        server.respondTo = {
            // The admin turned requests off since the page loaded.
            server.followSupported = false
            HttpStatusCode.Conflict to
                """{"type":"https://siloserver.org/docs/api/v2/problems/capability_disabled","title":"Disabled","status":409,"detail":"The requests capability is disabled by an administrator."}"""
        }
        val (_, store, viewModel) = ready(server)
        assertTrue(store.followSupported.value)
        val statusReads = server.calls.count { it.endsWith("/requests/status") }

        viewModel.toggleFollow()
        advanceUntilIdle()

        assertEquals(statusReads + 1, server.calls.count { it.endsWith("/requests/status") })
        assertFalse(store.followSupported.value)
        val after = viewModel.uiState.value
        assertEquals("Requests are turned off", after.actionErrorMessage?.text?.english())
        assertFalse(after.canFollow)
        assertFalse(after.isFollowing)
    }

    @Test
    fun aReadSentBeforeAFollowDoesNotUndoIt() = runTest {
        val server = Server(detail = titleJson(following = false)) {
            HttpStatusCode.OK to """{"status":"approved","state":"approved","requestable":false,"reason":"already_requested","following":true,"requested_by_viewer":false}"""
        }
        val (repository, _, viewModel) = ready(server)
        // A re-read leaves before the follow and answers after it, with the old state.
        val gate = CompletableDeferred<Unit>()
        server.detailGate = gate
        viewModel.load()
        advanceUntilIdle()
        viewModel.toggleFollow()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFollowing)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFollowing)
        assertEquals("Stop notifying me", viewModel.uiState.value.followActionTitle.english())
        assertEquals(true, repository.cache.detail(RequestDetailCache.Key(RequestMediaType.Series, 1399))?.request?.following)
    }

    @Test
    fun theRequesterAndAServerWithoutFollowsAreNotOfferedIt() {
        val othersRequest = RequestMediaDetail(
            mediaType = RequestMediaType.Movie,
            tmdbId = 7,
            title = "Film",
            availability = RequestAvailability.Missing,
            request = RequestState(
                status = RequestStatus.Pending,
                requestable = false,
                reason = RequestReason.AlreadyRequested,
            ),
        )
        assertTrue(RequestDetailUiState(detail = othersRequest, followSupported = true).canFollow)
        // The server doesn't take follows.
        assertFalse(RequestDetailUiState(detail = othersRequest, followSupported = false).canFollow)
        // The viewer's own request: nothing to follow.
        val own = othersRequest.copy(request = othersRequest.request.copy(requestedByViewer = true, following = true))
        assertFalse(RequestDetailUiState(detail = own, followSupported = true).canFollow)
        // The first frame built from the viewer's own record, before the read lands.
        val record = MediaRequest(
            id = "r1",
            mediaType = RequestMediaType.Movie,
            tmdbId = 7,
            title = "Film",
            status = RequestStatus.Pending,
            outcome = RequestOutcome.Active,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        assertFalse(RequestDetailUiState(detail = RequestDetailCache.provisionalDetail(record), record = record, followSupported = true).canFollow)
        // A title anyone can request: Request, not Notify me.
        val requestable = othersRequest.copy(request = RequestState(requestable = true))
        assertFalse(RequestDetailUiState(detail = requestable, followSupported = true).canFollow)
        // Already in the library.
        val available = othersRequest.copy(
            availability = RequestAvailability.Available,
            request = RequestState(requestable = false, reason = RequestReason.AlreadyAvailable),
        )
        assertFalse(RequestDetailUiState(detail = available, followSupported = true).canFollow)
    }
}
