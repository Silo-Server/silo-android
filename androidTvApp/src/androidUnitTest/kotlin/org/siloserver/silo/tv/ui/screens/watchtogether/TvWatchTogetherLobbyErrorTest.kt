package org.siloserver.silo.tv.ui.screens.watchtogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TvWatchTogetherLobbyErrorTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rejected lobby operations use the transient repository message path`() = runTest(dispatcher) {
        val api = FailingLobbyApi()
        val repository = WatchTogetherRepository(
            api = api,
            authScopeProvider = { AUTH_SCOPE },
        )
        repository.createRoom(CreateRoomRequest())
        val roomSession = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())
        val viewModel = TvWatchTogetherLobbyViewModel("room-1", repository, roomSession)
        val messages = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.errors.toList(messages)
        }

        viewModel.vote("suggestion-1")
        viewModel.promote("suggestion-1")
        repository.reportDeliveryFailure("Socket request rejected")
        runCurrent()

        assertEquals(
            listOf(
                "Voting is disabled",
                "Only the host can promote",
                "Socket request rejected",
            ),
            messages,
        )
        collector.cancel()
    }

    @Test
    fun `suggest rejection remains a visible one shot detail message`() = runTest(dispatcher) {
        val repository = WatchTogetherRepository(
            api = FailingLobbyApi(),
            authScopeProvider = { AUTH_SCOPE },
        )
        repository.createRoom(CreateRoomRequest())
        val viewModel = TvSuggestToRoomViewModel(repository)

        viewModel.suggest("movie-1", "movie", "Movie One", null, null)
        runCurrent()

        assertEquals("Suggestions are locked", viewModel.uiState.value.error)
        viewModel.clearError()
        assertEquals(null, viewModel.uiState.value.error)
    }

    private class FailingLobbyApi : WatchTogetherApi {
        private val roomResponse =
            ApiResult.Success(RoomResponse(RoomSnapshot(roomId = "room-1"), "room-token"))

        override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot) = roomResponse
        override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot) = roomResponse
        override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot) = roomResponse
        override suspend fun setSelection(
            roomId: String,
            roomToken: String,
            request: SetSelectionRequest,
            scope: AuthScopeSnapshot,
        ) = roomResponse

        override suspend fun updatePolicy(
            roomId: String,
            roomToken: String,
            request: UpdatePolicyRequest,
            scope: AuthScopeSnapshot,
        ) = roomResponse

        override suspend fun closeRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot) =
            ApiResult.Success(Unit)

        override suspend fun listSuggestions(
            roomId: String,
            roomToken: String,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(SuggestionsResponse())

        override suspend fun addSuggestion(
            roomId: String,
            roomToken: String,
            request: AddSuggestionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<SuggestionsResponse> =
            ApiResult.Error(409, "suggestions_locked", "Suggestions are locked")

        override suspend fun deleteSuggestion(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(SuggestionsResponse())

        override suspend fun vote(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ): ApiResult<SuggestionsResponse> =
            ApiResult.Error(409, "voting_disabled", "Voting is disabled")

        override suspend fun unvote(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(SuggestionsResponse())

        override suspend fun promoteSuggestion(
            roomId: String,
            roomToken: String,
            request: PromoteSuggestionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<RoomResponse> =
            ApiResult.Error(403, "host_required", "Only the host can promote")
    }

    private companion object {
        val AUTH_SCOPE = AuthScopeSnapshot(
            serverId = "server-1",
            profileId = "profile-1",
            serverUrl = "https://example.test",
            profileToken = "profile-token",
            identityGeneration = 1L,
        )
    }
}
