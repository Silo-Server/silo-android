package org.siloserver.silo.android.ui.screens.watchtogether

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchTogetherEntryViewModelTest {
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
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.hostEmptyVoteRoom()

        assertEquals(listOf(CreateRoomRequest(RoomSelectionMode.Vote.wire)), gateway.createRequests)
        assertEquals(emptyList(), gateway.selectionRequests)
        assertEquals("watch_together/room-1", viewModel.uiState.value.destination)
    }

    @Test
    fun resumeUsesCurrentRoomWithoutCreateOrJoin() = runTest(dispatcher) {
        val room = RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby)
        val gateway = FakeGateway(room)
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.resumeCurrentRoom()

        assertEquals("watch_together/room-1", viewModel.uiState.value.destination)
        assertEquals(emptyList(), gateway.createRequests)
        assertEquals(emptyList(), gateway.joinRequests)
    }

    @Test
    fun identityClearRemovesResumeState() = runTest(dispatcher) {
        val gateway = FakeGateway(RoomSnapshot(roomId = "room-1", phase = RoomPhase.Lobby))
        val viewModel = WatchTogetherEntryViewModel(gateway)

        gateway.roomSnapshot.value = null

        assertNull(viewModel.currentRoom.value)
    }

    @Test
    fun titleHostStillSetsTheSelectedTitle() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.host(contentId = "movie-1", fileId = 7)

        assertEquals(
            listOf(SetSelectionRequest(contentId = "movie-1", fileId = 7)),
            gateway.selectionRequests,
        )
    }

    @Test
    fun joinByCodeTrimsAndUsesExistingErrorMapping() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = WatchTogetherEntryViewModel(gateway)

        viewModel.joinByCode(" ABCD1234 ")
        assertEquals(listOf(JoinRoomRequest(code = "ABCD1234")), gateway.joinRequests)

        viewModel.consumeDestination()
        gateway.joinResult = ApiResult.NetworkError(IllegalStateException("offline"))
        viewModel.joinByCode("EFGH5678")
        assertEquals("Network error. Check your connection.", viewModel.uiState.value.error)
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

        override suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse> {
            createRequests += request
            roomSnapshot.value = nextRoom
            return ApiResult.Success(RoomResponse(nextRoom, "test-room-token"))
        }

        override suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse> {
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
