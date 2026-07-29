package org.siloserver.silo.tv.ui.screens.watchtogether

import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TvWatchTogetherViewModelTest {
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
    fun emptyHostCreatesVoteRoomWithoutSelection() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = TvWatchTogetherViewModel(gateway)

        viewModel.createEmptyVoteRoom()

        assertEquals(
            listOf(CreateRoomRequest(RoomSelectionMode.Vote.wire)),
            gateway.createRequests,
        )
        assertEquals(emptyList(), gateway.selectionRequests)
        assertEquals("room-1", viewModel.uiState.value.result?.roomId)
    }

    @Test
    fun resumeUsesCurrentRoomWithoutNetworkCalls() = runTest(dispatcher) {
        val room = RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby)
        val gateway = FakeGateway(room)
        val viewModel = TvWatchTogetherViewModel(gateway)

        viewModel.resumeCurrentRoom()

        assertEquals(room, viewModel.uiState.value.result)
        assertEquals(emptyList(), gateway.createRequests)
        assertEquals(emptyList(), gateway.joinRequests)
    }

    @Test
    fun joinCodeNormalizesAndKeepsExistingErrorCopy() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = TvWatchTogetherViewModel(gateway)

        viewModel.joinRoom(" abcd1234 ")
        assertEquals(listOf(JoinRoomRequest(code = "ABCD1234")), gateway.joinRequests)

        viewModel.consumeResult()
        gateway.joinResult = ApiResult.NetworkError(IllegalStateException("offline"))
        viewModel.joinRoom("efgh5678")
        assertEquals("Network error. Check your connection.", viewModel.uiState.value.error)
    }

    @Test
    fun titleDetailHostStillSetsSelection() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = TvWatchTogetherViewModel(gateway)

        viewModel.createRoom(contentId = "movie-1", fileId = 7)

        assertEquals(
            listOf(SetSelectionRequest(contentId = "movie-1", fileId = 7)),
            gateway.selectionRequests,
        )
    }

    private class FakeGateway(
        room: RoomSnapshot? = null,
    ) : WatchTogetherEntryGateway {
        override val roomSnapshot = MutableStateFlow(room)
        val createRequests = mutableListOf<CreateRoomRequest>()
        val joinRequests = mutableListOf<JoinRoomRequest>()
        val selectionRequests = mutableListOf<SetSelectionRequest>()
        var joinResult: ApiResult<RoomResponse>? = null
        var nextRoom = RoomSnapshot(
            roomId = "room-1",
            phase = RoomPhase.Lobby,
            selectionMode = RoomSelectionMode.Vote,
        )

        override suspend fun createRoom(
            request: CreateRoomRequest,
        ): ApiResult<RoomResponse> {
            createRequests += request
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }

        override suspend fun joinRoom(
            request: JoinRoomRequest,
        ): ApiResult<RoomResponse> {
            joinRequests += request
            joinResult?.let { return it }
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }

        override suspend fun setSelection(
            request: SetSelectionRequest,
        ): ApiResult<RoomResponse> {
            selectionRequests += request
            nextRoom = nextRoom.copy(
                selectedContentId = request.contentId,
                selectedFileId = request.fileId,
            )
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }
    }
}
