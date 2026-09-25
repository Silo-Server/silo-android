package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.MemberStateRequest
import org.siloserver.silo.model.watchtogether.MemberStateResponse
import org.siloserver.silo.model.watchtogether.PickerEntry
import org.siloserver.silo.model.watchtogether.PickerResponse
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SelectionModeRequest
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SourceFallbackRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionReceipt
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.network.apiv2.PlaybackCapabilitiesV2
import org.siloserver.silo.repository.WatchPartyTiming
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RecentPartyOwner
import org.siloserver.silo.watchtogether.RecentWatchParties
import org.siloserver.silo.watchtogether.RecentWatchPartyStorage
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.WatchPartyInvite
import org.siloserver.silo.watchtogether.WatchPartyPlaybackFeatures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPartyViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val scope = AuthScopeSnapshot("server", "profile", "https://silo.example", profileToken = null, identityGeneration = 1)

    private class Api : WatchTogetherApi {
        val creates = mutableListOf<CreateRoomRequest>()
        val joins = mutableListOf<JoinRoomRequest>()
        val calls = mutableListOf<String>()
        var createResults = ArrayDeque<ApiResult<RoomResponse>>()
        var joinResult: ApiResult<RoomResponse>? = null
        var room = RoomSnapshot(roomId = "room-1", code = "K7PQ2M4X", phase = RoomPhase.Lobby, selectionMode = RoomSelectionMode.HostPick, selfRole = MemberRole.Host, selfCanManageRoom = true)
        var createGate: CompletableDeferred<Unit>? = null
        var pickerResponse = PickerResponse()

        private fun ok(snapshot: RoomSnapshot = room) = ApiResult.Success(RoomResponse(snapshot, "proof"))

        override suspend fun capabilities(scope: AuthScopeSnapshot) = ApiResult.Success(WatchTogetherCapabilitiesV2())
        override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            creates += request
            createGate?.await()
            return createResults.removeFirstOrNull() ?: ok(room.copy(roomId = request.roomId))
        }
        override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            joins += request
            return joinResult ?: ok(room.copy(selfRole = MemberRole.Guest, selfCanManageRoom = false))
        }
        override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot) = ok()
        override suspend fun updatePolicy(roomId: String, request: UpdatePolicyRequest, scope: AuthScopeSnapshot) = ok()
        override suspend fun stageSelection(roomId: String, request: SetSelectionRequest, scope: AuthScopeSnapshot) =
            ok(room.copy(roomId = roomId, selectedContentId = request.contentId, generation = room.generation + 1)).also { calls += "stage:${request.contentId}" }
        override suspend fun startPlayback(roomId: String, scope: AuthScopeSnapshot) = ok().also { calls += "start" }
        override suspend fun stopPlayback(roomId: String, scope: AuthScopeSnapshot) = ok()
        override suspend fun setSelectionMode(roomId: String, request: SelectionModeRequest, scope: AuthScopeSnapshot) = ok()
        override suspend fun setSelection(roomId: String, request: SetSelectionRequest, scope: AuthScopeSnapshot) =
            ok().also { calls += "select" }
        override suspend fun closeRoom(roomId: String, scope: AuthScopeSnapshot) = ApiResult.Success(Unit)
        override suspend fun sourceFallback(roomId: String, roomToken: String, request: SourceFallbackRequest, scope: AuthScopeSnapshot) = ok()
        override suspend fun listSuggestions(roomId: String, roomToken: String, scope: AuthScopeSnapshot, cursor: String?, limit: Int) =
            ApiResult.Success(SuggestionsResponse())
        override suspend fun addSuggestion(roomId: String, roomToken: String, request: AddSuggestionRequest, scope: AuthScopeSnapshot) =
            ApiResult.Success(SuggestionReceipt(request.suggestionId))
        override suspend fun deleteSuggestion(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) = ApiResult.Success(Unit)
        override suspend fun vote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) = ApiResult.Success(Unit)
        override suspend fun unvote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) = ApiResult.Success(Unit)
        override suspend fun promoteSuggestion(roomId: String, roomToken: String, request: PromoteSuggestionRequest, scope: AuthScopeSnapshot) =
            ok().also { calls += "promote" }
        override suspend fun memberState(roomId: String, roomToken: String, request: MemberStateRequest, scope: AuthScopeSnapshot) =
            ApiResult.Success(MemberStateResponse())
        override suspend fun picker(roomId: String, roomToken: String, scope: AuthScopeSnapshot) = ApiResult.Success(pickerResponse)
    }

    private class Storage : RecentWatchPartyStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private inner class Fixture(testScope: TestScope) {
        val api = Api()
        val repository = WatchTogetherRepository(
            api = api,
            authScopeProvider = { scope },
            timing = WatchPartyTiming(backgroundWork = false),
        )
        val session = RoomSession(repository, testScope.backgroundScope, DefaultIdentityTransitionBarrier())
        val availability = WatchPartyAvailabilityRepository(
            roomCapabilities = {
                ApiResult.Success(
                    WatchTogetherCapabilitiesV2(
                        state = "available", allowed = true, stagedSelection = true, connectionReplaced = true,
                        socketProtocol = "silo.room.v2", stopPlayback = true, selectionModeSwitch = true,
                        voteHostOverride = true, picker = true,
                    ),
                )
            },
            playbackCapabilities = {
                ApiResult.Success(
                    PlaybackCapabilitiesV2(
                        state = "available", allowed = true, protocolVersions = listOf(3),
                        features = listOf(WatchPartyPlaybackFeatures.Coordinator, WatchPartyPlaybackFeatures.FixedMediaFile),
                    ),
                )
            },
            authScopeProvider = { scope },
        )
        val recents = RecentWatchParties(Storage(), owner = { RecentPartyOwner("server", "login", "profile") }, nowEpochMs = { 1L })

        fun hub() = WatchPartyHubViewModel(repository, session, availability, recents) { it == "https://silo.example" }
    }

    @Test
    fun `hosting creates a host-picks room and opens its lobby`() = runTest(dispatcher) {
        val f = Fixture(this)
        val hub = f.hub()
        runCurrent()

        hub.host()
        runCurrent()

        assertEquals("host_pick", f.api.creates.single().selectionMode)
        assertIs<WatchPartyDestination.Lobby>(hub.uiState.value.destination)
    }

    @Test
    fun `hosting from a title stages it without starting playback`() = runTest(dispatcher) {
        val f = Fixture(this)
        val hub = f.hub()
        runCurrent()

        hub.hostWithItem(WatchPartyItem("movie:heat", "movie", "Heat", fileId = 42, libraryId = 7))
        runCurrent()

        assertEquals(listOf("stage:movie:heat"), f.api.calls)
        assertIs<WatchPartyDestination.Lobby>(hub.uiState.value.destination)
    }

    @Test
    fun `an uncertain create is retried with the same room identity`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.api.createResults.addLast(ApiResult.NetworkError(RuntimeException("reset")))
        val hub = f.hub()
        runCurrent()

        hub.host()
        runCurrent()
        assertTrue(hub.uiState.value.createRetryable)
        hub.retryCreate()
        runCurrent()

        assertEquals(2, f.api.creates.size)
        assertEquals(f.api.creates[0].roomId, f.api.creates[1].roomId)
        assertNotNull(hub.uiState.value.destination)
    }

    @Test
    fun `codes are normalized and foreign-server links never send the token`() = runTest(dispatcher) {
        val f = Fixture(this)
        val hub = f.hub()
        runCurrent()

        hub.join("k7pq-2m4x")
        runCurrent()
        assertEquals(JoinRoomRequest(code = "K7PQ2M4X"), f.api.joins.single())

        f.repository.reset()
        hub.joinInvite(WatchPartyInvite.Link("https://other.example", "secret-token"))
        runCurrent()
        assertEquals(1, f.api.joins.size)
        assertTrue(hub.uiState.value.error!!.contains("different Silo server"))
    }

    @Test
    fun `joining while already in a party asks to leave first`() = runTest(dispatcher) {
        val f = Fixture(this)
        val hub = f.hub()
        runCurrent()
        hub.join("K7PQ2M4X")
        runCurrent()

        hub.join("ABCD2345")
        runCurrent()

        assertEquals(1, f.api.joins.size)
        assertTrue(hub.uiState.value.error!!.contains("Leave it first"))
    }

    @Test
    fun `an unknown code says so`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.api.joinResult = ApiResult.Error(404, "not_found", "The room or suggestion was not found.")
        val hub = f.hub()
        runCurrent()

        hub.join("K7PQ2M4X")
        runCurrent()

        assertEquals("No Watch Party uses that code.", hub.uiState.value.error)
        assertNull(hub.uiState.value.destination)
    }

    @Test
    fun `the lobby opens the player only when the room plays`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.api.room = f.api.room.copy(selectedContentId = "movie:heat")
        f.repository.createRoom(CreateRoomRequest(roomId = "room-1"))
        val lobby = WatchPartyLobbyViewModel("room-1", f.repository, f.session, f.availability)
        runCurrent()
        assertEquals(WatchPartyDestination.Lobby("room-1"), lobby.destination.value)

        f.api.room = f.api.room.copy(phase = RoomPhase.Playing, generation = 5)
        lobby.start()
        runCurrent()
        assertEquals(WatchPartyDestination.Player("room-1"), lobby.destination.value)
    }

    @Test
    fun `queueing a suggestion stages it and never promotes`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.repository.createRoom(CreateRoomRequest(roomId = "room-1"))
        val lobby = WatchPartyLobbyViewModel("room-1", f.repository, f.session, f.availability)
        runCurrent()

        lobby.queue(Suggestion(id = "s", roomId = "room-1", contentId = "movie:a", contentType = "movie", title = "A", createdAt = ""))
        runCurrent()

        assertEquals(listOf("stage:movie:a"), f.api.calls)
    }

    @Test
    fun `lobby eligibility follows role and phase`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.api.room = f.api.room.copy(
            selectedContentId = "movie:a",
            members = listOf(RoomMember(userId = "1", profileId = "profile", isHost = true, isSelf = true, connected = true)),
        )
        f.repository.createRoom(CreateRoomRequest(roomId = "room-1"))
        val lobby = WatchPartyLobbyViewModel("room-1", f.repository, f.session, f.availability)
        runCurrent()

        val host = lobby.state.value.eligibility
        assertTrue(host.canStart && host.canStage && host.canSwitchMode && host.canEnd)
        assertTrue(!host.canLobbyReady && !host.canStop)
    }

    @Test
    fun `shared picker rows exclude nonvideo titles`() = runTest(dispatcher) {
        val f = Fixture(this)
        f.repository.createRoom(CreateRoomRequest(roomId = "room-1"))
        val movie = PickerEntry(BrowseItem("movie:a", "movie", "Movie"))
        val series = PickerEntry(BrowseItem("series:a", "series", "Series"))
        val episode = PickerEntry(BrowseItem("episode:a", "episode", "Episode"))
        val ebook = PickerEntry(BrowseItem("ebook:a", "ebook", "Book"))
        val audiobook = PickerEntry(BrowseItem("audiobook:a", "audiobook", "Audiobook"))
        f.api.pickerResponse = PickerResponse(
            continueTogether = listOf(ebook, series, movie),
            watchlistUnion = listOf(audiobook, movie, ebook, episode),
        )
        val picker = WatchPartyPickerViewModel(f.repository, f.availability) { ApiResult.Success(emptyList()) }

        picker.loadRows()
        runCurrent()

        val rows = assertNotNull(picker.state.value.rows)
        assertEquals(listOf(series, movie), rows.continueTogether)
        assertEquals(listOf(movie, episode), rows.watchlistUnion)
    }

    @Test
    fun `new picker input rejects the previous result before debounce expires`() = runTest(dispatcher) {
        val f = Fixture(this)
        val oldResult = CompletableDeferred<ApiResult<List<BrowseItem>>>()
        val newItem = BrowseItem("movie:new", "movie", "New")
        val calls = mutableListOf<String>()
        val picker = WatchPartyPickerViewModel(f.repository, f.availability) { query ->
            calls += query
            if (query == "old") withContext(NonCancellable) { oldResult.await() }
            else ApiResult.Success(listOf(newItem))
        }
        picker.onQuery("old")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("old"), calls)

        picker.onQuery("new")
        oldResult.complete(ApiResult.Success(listOf(BrowseItem("movie:old", "movie", "Old"))))
        runCurrent()
        assertTrue(picker.state.value.results.isEmpty())
        assertTrue(picker.state.value.searching)

        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("old", "new"), calls)
        assertEquals(listOf(newItem), picker.state.value.results)
        assertFalse(picker.state.value.searching)
    }

    @Test
    fun `new picker input cancels an unfinished search`() = runTest(dispatcher) {
        val f = Fixture(this)
        var cancelled = false
        val calls = mutableListOf<String>()
        val picker = WatchPartyPickerViewModel(f.repository, f.availability) { query ->
            calls += query
            if (query == "old") {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            ApiResult.Success(emptyList())
        }
        picker.onQuery("old")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("old"), calls)

        picker.onQuery("new")
        runCurrent()
        assertTrue(cancelled)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("old", "new"), calls)
        assertFalse(picker.state.value.searching)
    }

    @Test
    fun `clearing picker input removes results and ignores a pending failure`() = runTest(dispatcher) {
        val f = Fixture(this)
        val lateResult = CompletableDeferred<ApiResult<List<BrowseItem>>>()
        val picker = WatchPartyPickerViewModel(f.repository, f.availability) { query ->
            if (query == "first") ApiResult.Success(listOf(BrowseItem("movie:first", "movie", "First")))
            else withContext(NonCancellable) { lateResult.await() }
        }
        picker.onQuery("first")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, picker.state.value.results.size)

        picker.onQuery("pending")
        advanceTimeBy(301)
        runCurrent()
        assertTrue(picker.state.value.searching)

        picker.onQuery("")
        assertTrue(picker.state.value.results.isEmpty())
        assertFalse(picker.state.value.searching)
        lateResult.complete(ApiResult.NetworkError(RuntimeException("late failure")))
        runCurrent()
        assertNull(picker.state.value.error)
        assertTrue(picker.state.value.results.isEmpty())
        assertFalse(picker.state.value.searching)
    }
}
