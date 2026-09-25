package org.siloserver.silo.repository

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.MemberStateRequest
import org.siloserver.silo.model.watchtogether.MemberStateResponse
import org.siloserver.silo.model.watchtogether.PickerResponse
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SelectionModeRequest
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SourceFallbackRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionPageInfo
import org.siloserver.silo.model.watchtogether.SuggestionReceipt
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.RoomTicketExpiringException
import org.siloserver.silo.network.RoomTicketRefusedException
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomTransportIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherRepositoryTest {

    // ---- Fakes ---------------------------------------------------------------

    private fun snapshot(
        roomId: String = "room-1",
        revision: Long = 1L,
        generation: Long = 1L,
        memberCount: Int = 1,
        phase: RoomPhase = RoomPhase.Lobby,
        attachedSessionId: String? = null,
    ) = RoomSnapshot(
        roomId = roomId,
        selectionRevision = revision,
        generation = generation,
        memberCount = memberCount,
        code = "ABCD1234",
        phase = phase,
        attachedSessionId = attachedSessionId,
    )

    private fun suggestion(
        id: String,
        voteCount: Int = 0,
        votedByMe: Boolean = false,
        createdAt: String = "2026-06-12T08:00:00Z",
    ) = Suggestion(
        id = id, roomId = "room-1", contentId = "c-$id", contentType = "movie",
        title = "T-$id", voteCount = voteCount, votedByMe = votedByMe, createdAt = createdAt,
    )

    private class FakeApi(
        var createResponse: ApiResult<RoomResponse> = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-1", code = "ABCD1234"), "jwt-room"),
        ),
    ) : WatchTogetherApi {
        val calls = mutableListOf<String>()
        var lastRoomToken: String? = null
        var lastRoomId: String? = null
        var lastSelection: SetSelectionRequest? = null
        var lastAuthScope: AuthScopeSnapshot? = null
        var createResult: CompletableDeferred<ApiResult<RoomResponse>>? = null
        var selectionResult: CompletableDeferred<ApiResult<RoomResponse>>? = null
        var roomReadResult: CompletableDeferred<ApiResult<RoomResponse>>? = null
        var roomReadResponse: ApiResult<RoomResponse>? = null
        var mutationResponse: ApiResult<RoomResponse>? = null
        var voteResponse: ApiResult<Unit> = ApiResult.Success(Unit)
        var listSuggestionPages: MutableList<ApiResult<SuggestionsResponse>> = mutableListOf()
        var listSuggestionsResponse: ApiResult<SuggestionsResponse> = ApiResult.Success(SuggestionsResponse())
        var listSuggestionsResult: CompletableDeferred<ApiResult<SuggestionsResponse>>? = null
        val listCursors = mutableListOf<String?>()

        fun count(name: String) = calls.count { it == name }

        private fun record(name: String, roomId: String?, token: String?, scope: AuthScopeSnapshot) {
            calls += name
            roomId?.let { lastRoomId = it }
            lastRoomToken = token
            lastAuthScope = scope
        }

        private fun mutation(): ApiResult<RoomResponse> = mutationResponse ?: createResponse

        override suspend fun capabilities(scope: AuthScopeSnapshot) =
            ApiResult.Success(WatchTogetherCapabilitiesV2())

        override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            record("create", null, null, scope)
            return createResult?.await() ?: createResponse
        }
        override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            record("join", null, null, scope)
            return createResponse
        }
        override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            record("get", roomId, roomToken, scope)
            return roomReadResult?.await() ?: roomReadResponse ?: createResponse
        }
        override suspend fun updatePolicy(roomId: String, request: UpdatePolicyRequest, scope: AuthScopeSnapshot) =
            mutation().also { record("policy", roomId, null, scope) }
        override suspend fun stageSelection(roomId: String, request: SetSelectionRequest, scope: AuthScopeSnapshot) =
            mutation().also { record("stage", roomId, null, scope) }
        override suspend fun startPlayback(roomId: String, scope: AuthScopeSnapshot) =
            mutation().also { record("start", roomId, null, scope) }
        override suspend fun stopPlayback(roomId: String, scope: AuthScopeSnapshot) =
            mutation().also { record("stop", roomId, null, scope) }
        override suspend fun setSelectionMode(roomId: String, request: SelectionModeRequest, scope: AuthScopeSnapshot) =
            mutation().also { record("mode", roomId, null, scope) }
        override suspend fun setSelection(
            roomId: String,
            request: SetSelectionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<RoomResponse> {
            record("select", roomId, null, scope)
            lastSelection = request
            return selectionResult?.await() ?: mutation()
        }
        override suspend fun closeRoom(roomId: String, scope: AuthScopeSnapshot): ApiResult<Unit> {
            record("close", roomId, null, scope)
            return ApiResult.Success(Unit)
        }
        override suspend fun sourceFallback(
            roomId: String,
            roomToken: String,
            request: SourceFallbackRequest,
            scope: AuthScopeSnapshot,
        ) = mutation().also { record("fallback", roomId, roomToken, scope) }
        override suspend fun listSuggestions(
            roomId: String,
            roomToken: String,
            scope: AuthScopeSnapshot,
            cursor: String?,
            limit: Int,
        ): ApiResult<SuggestionsResponse> {
            record("list", roomId, roomToken, scope)
            listCursors += cursor
            listSuggestionsResult?.let { return it.await() }
            return if (listSuggestionPages.isNotEmpty()) listSuggestionPages.removeAt(0) else listSuggestionsResponse
        }
        override suspend fun addSuggestion(
            roomId: String,
            roomToken: String,
            request: AddSuggestionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<SuggestionReceipt> {
            record("suggest", roomId, roomToken, scope)
            return ApiResult.Success(SuggestionReceipt(request.suggestionId))
        }
        override suspend fun deleteSuggestion(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) =
            ApiResult.Success(Unit).also { record("delete", roomId, roomToken, scope) }
        override suspend fun vote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) =
            voteResponse.also { record("vote", roomId, roomToken, scope) }
        override suspend fun unvote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot) =
            voteResponse.also { record("unvote", roomId, roomToken, scope) }
        override suspend fun promoteSuggestion(
            roomId: String,
            roomToken: String,
            request: PromoteSuggestionRequest,
            scope: AuthScopeSnapshot,
        ) = mutation().also { record("promote", roomId, roomToken, scope) }
        override suspend fun memberState(
            roomId: String,
            roomToken: String,
            request: MemberStateRequest,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(MemberStateResponse()).also { record("memberState", roomId, roomToken, scope) }
        override suspend fun picker(roomId: String, roomToken: String, scope: AuthScopeSnapshot) =
            ApiResult.Success(PickerResponse()).also { record("picker", roomId, roomToken, scope) }
    }

    private class FakeRealtime(
        val events: MutableSharedFlow<RoomRealtimeEvent> = MutableSharedFlow(replay = 0, extraBufferCapacity = 64),
    ) : WatchTogetherRealtimeClient {
        var connectCount = 0
        /** When true, the returned flow throws immediately on collection. */
        var failConnect = false
        var terminateImmediately = false
        var attachCount = 0
        val transportActions = mutableListOf<String>()
        val readyCommands = mutableListOf<String?>()
        val bufferingSessions = mutableListOf<String>()
        val lobbyReady = mutableListOf<Boolean>()
        var pings = 0
        var transportResultGate: CompletableDeferred<Boolean>? = null
        var connectBehavior: ((Int) -> Flow<RoomRealtimeEvent>)? = null
        /** Each attempt emits this one event and then throws: a flapping server. */
        var flappingEvent: RoomRealtimeEvent? = null
        val connectedRoomIds = mutableListOf<String>()
        val connectedRoomTokens = mutableListOf<String>()
        val connectedAuthScopes = mutableListOf<AuthScopeSnapshot?>()

        override fun connect(
            roomId: String,
            roomToken: String,
            authScope: AuthScopeSnapshot?,
        ): Flow<RoomRealtimeEvent> {
            connectCount++
            connectedRoomIds += roomId
            connectedRoomTokens += roomToken
            connectedAuthScopes += authScope
            connectBehavior?.let { return it(connectCount) }
            if (failConnect) return flow { throw IllegalStateException("boom") }
            if (terminateImmediately) return flow {
                emit(RoomRealtimeEvent.Opened)
                emit(RoomRealtimeEvent.TransportTerminated())
            }
            val flapEvent = flappingEvent
            if (flapEvent != null) return flow {
                emit(flapEvent)
                throw IllegalStateException("connection dropped after event")
            }
            return events.asSharedFlow()
        }
        override suspend fun attachSession(sessionId: String): Boolean {
            attachCount++
            return true
        }
        override suspend fun transportRequest(action: String, positionSeconds: Double?, isPaused: Boolean): Boolean {
            transportActions += action
            return true
        }
        override suspend fun currentConnectionId(): Long? = connectCount.toLong().takeIf { it > 0L }
        override suspend fun transportRequestOnConnection(
            connectionId: Long,
            action: String,
            positionSeconds: Double?,
            isPaused: Boolean,
        ): Boolean {
            transportResultGate?.let { return it.await() }
            transportActions += action
            return true
        }
        override suspend fun stateReport(
            sessionId: String,
            positionSeconds: Double,
            isPaused: Boolean,
            commandId: String?,
            isReady: Boolean,
        ) = true
        override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String?): Boolean {
            readyCommands += commandId
            return true
        }
        override suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean {
            bufferingSessions += sessionId
            return true
        }
        override suspend fun lobbyReady(ready: Boolean): Boolean {
            lobbyReady += ready
            return true
        }
        override suspend fun ping(clientSentAt: String): Boolean {
            pings++
            return true
        }
    }

    private val scopeA = AuthScopeSnapshot(
        serverId = "server-a",
        profileId = "profile-a",
        serverUrl = "https://a.example",
        profileToken = "profile-token-a",
        identityGeneration = 1L,
    )

    /** Jitter-free, background-work-free timing so tests control every delay. */
    private val testTiming = WatchPartyTiming(backgroundWork = false)
    private object NoJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun TestScope.repo(
        api: FakeApi = FakeApi(),
        realtime: FakeRealtime = FakeRealtime(),
        authScopeProvider: suspend () -> AuthScopeSnapshot? = { scopeA },
        now: () -> Long = { testScheduler.currentTime },
        timing: WatchPartyTiming = testTiming,
        realtimeFactory: (() -> WatchTogetherRealtimeClient?)? = null,
    ) = WatchTogetherRepository(
        api = api,
        realtimeFactory = realtimeFactory ?: { realtime },
        monotonicNowMs = now,
        authScopeProvider = authScopeProvider,
        wallClockMs = { 1_000_000L + testScheduler.currentTime },
        timing = timing,
        random = NoJitter,
    )

    private fun create() = CreateRoomRequest(roomId = "room-1")

    private fun room(snapshot: RoomSnapshot, token: String = "jwt-room") = ApiResult.Success(RoomResponse(snapshot, token))

    private val firstBackoff = testTiming.backoffMs.first()

    // ---- create / join / proof ----------------------------------------------

    @Test
    fun `create stores the proof used by member calls and keeps host calls proof-free`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        r.promoteSuggestion("s-1")
        assertEquals("jwt-room", api.lastRoomToken)
        r.setSelection(SetSelectionRequest(contentId = "tt-9"))
        assertNull(api.lastRoomToken)
        assertEquals("tt-9", api.lastSelection?.contentId)
    }

    @Test
    fun `every successful room response renews the proof without changing membership`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        val membership = r.membershipGeneration()

        api.mutationResponse = room(snapshot(generation = 2), token = "jwt-renewed")
        r.stageSelection(SetSelectionRequest(contentId = "movie:a"))
        r.promoteSuggestion("s-1")

        assertEquals("jwt-renewed", api.lastRoomToken)
        assertEquals(membership, r.membershipGeneration())
    }

    @Test
    fun `room read renews proof even when its snapshot is stale`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 5)))
        runCurrent()

        api.roomReadResponse = room(snapshot(generation = 3), token = "jwt-fresh")
        r.refreshRoom()

        assertEquals(5L, r.roomSnapshot.value?.generation)
        r.promoteSuggestion("s-1")
        assertEquals("jwt-fresh", api.lastRoomToken)
        job.cancel()
    }

    @Test
    fun `room binding retains the auth scope captured when it was created`() = runTest {
        val api = FakeApi()
        var currentScope = scopeA
        val r = repo(api = api, authScopeProvider = { currentScope })
        r.createRoom(create())
        currentScope = scopeA.copy(serverId = "server-b", serverUrl = "https://b.example", identityGeneration = 2L)

        r.setSelection(SetSelectionRequest(contentId = "tt-9"))

        assertEquals(scopeA, api.lastAuthScope)
    }

    @Test
    fun `create response cannot mint a room after the auth identity changes`() = runTest {
        val api = FakeApi()
        val pending = CompletableDeferred<ApiResult<RoomResponse>>()
        api.createResult = pending
        var currentScope = scopeA
        val r = repo(api = api, authScopeProvider = { currentScope })
        val create = launch { r.createRoom(create()) }
        runCurrent()
        currentScope = scopeA.copy(identityGeneration = 2L)

        pending.complete(api.createResponse)
        create.join()

        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `identity barrier invalidates a pending create before identity mutation`() = runTest {
        val api = FakeApi()
        val pending = CompletableDeferred<ApiResult<RoomResponse>>()
        api.createResult = pending
        val repository = repo(api = api)
        val barrier = DefaultIdentityTransitionBarrier()
        RoomSession(repository, backgroundScope, barrier)
        var createResult: ApiResult<RoomResponse>? = null
        val create = launch { createResult = repository.createRoom(create()) }
        runCurrent()

        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            assertNull(repository.roomSnapshot.value)
        }
        pending.complete(api.createResponse)
        create.join()

        assertIs<ApiResult.Error>(createResult)
        assertNull(repository.roomSnapshot.value)
    }

    @Test
    fun `blank room token fails closed without replacing the active room`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.createResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), token = "")

        assertIs<ApiResult.Error>(r.createRoom(create()))
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
        r.promoteSuggestion("still-a")
        assertEquals("room-1", api.lastRoomId)
        assertEquals("jwt-room", api.lastRoomToken)
    }

    @Test
    fun `room scoped response for another room is rejected`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2")

        assertIs<ApiResult.Error>(r.setSelection(SetSelectionRequest(contentId = "wrong-room")))
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
    }

    @Test
    fun `create fails closed when there is no authenticated scope`() = runTest {
        val r = repo(authScopeProvider = { null })
        assertIs<ApiResult.Error>(r.createRoom(create()))
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `a new membership clears the previous ended state`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.ConnectionReplaced("elsewhere"))
        job.join()
        assertEquals(WatchPartyEndReason.Replaced, r.ended.value?.reason)

        r.joinRoom(JoinRoomRequest(code = "ABCD1234"))

        assertNull(r.ended.value)
        assertNull(r.roomClosedReason.value)
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
    }

    // ---- actions: serialization, uncertainty, reconciliation ------------------------

    @Test
    fun `an uncertain start reconciles by reading the room and is never replayed`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = ApiResult.NetworkError(RuntimeException("reset"))
        api.roomReadResponse = room(snapshot(generation = 4, phase = RoomPhase.Playing))

        assertIs<ApiResult.NetworkError>(r.startPlayback())

        assertEquals(1, api.count("start"))
        assertEquals(1, api.count("get"))
        assertEquals(RoomPhase.Playing, r.roomSnapshot.value?.phase)
    }

    @Test
    fun `a conflict on a host action is action-level when the room is still readable`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = ApiResult.Error(409, "conflict", "Nothing is staged.")

        assertIs<ApiResult.Error>(r.startPlayback())

        assertEquals(1, api.count("get"))
        assertNull(r.roomClosedReason.value)
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
    }

    @Test
    fun `a conflict confirmed by the room read ends the engagement`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = ApiResult.Error(409, "conflict", "The room is closed.")
        api.roomReadResponse = ApiResult.Error(409, "conflict", "The room is closed.")

        r.stageSelection(SetSelectionRequest(contentId = "movie:a"))

        assertEquals(WatchPartyEndReason.Ended, r.roomClosedReason.value)
        assertNull(r.roomSnapshot.value)
        assertNull(r.membershipGeneration())
    }

    @Test
    fun `an ended snapshot in a response is terminal`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = room(snapshot(generation = 9, phase = RoomPhase.Ended))

        assertIs<ApiResult.Error>(r.stopPlayback())

        assertEquals(WatchPartyEndReason.Ended, r.roomClosedReason.value)
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `ending an already ended room converges on the ended state`() = runTest {
        val api = object : WatchTogetherApi by FakeApi() {
            override suspend fun closeRoom(roomId: String, scope: AuthScopeSnapshot): ApiResult<Unit> =
                ApiResult.Error(409, "conflict", "The room is closed.")
        }
        val r = WatchTogetherRepository(api = api, authScopeProvider = { scopeA }, timing = testTiming)
        r.createRoom(create())

        assertIs<ApiResult.Success<Unit>>(r.closeRoom())
        assertEquals(WatchPartyEndReason.Ended, r.roomClosedReason.value)
    }

    @Test
    fun `actions run one at a time and expose the pending action`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        val gate = CompletableDeferred<ApiResult<RoomResponse>>()
        api.selectionResult = gate

        val first = async { r.setSelection(SetSelectionRequest(contentId = "movie:a")) }
        runCurrent()
        assertEquals(WatchPartyPendingAction.Select, r.pendingAction.value)
        val second = async { r.stopPlayback() }
        runCurrent()
        assertEquals(0, api.count("stop"))

        gate.complete(api.createResponse)
        first.await()
        second.await()
        assertEquals(listOf("select", "stop"), api.calls.filter { it == "select" || it == "stop" })
        assertNull(r.pendingAction.value)
    }

    @Test
    fun `a late REST completion cannot publish after leaving`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        val pending = CompletableDeferred<ApiResult<RoomResponse>>()
        api.selectionResult = pending
        var staleResult: ApiResult<RoomResponse>? = null
        val stale = launch { staleResult = r.setSelection(SetSelectionRequest(contentId = "late-a")) }
        runCurrent()

        r.reset()
        pending.complete(room(RoomSnapshot(roomId = "room-1", selectionRevision = 99, generation = 9)))
        stale.join()

        assertEquals("obsolete_room_request", assertIs<ApiResult.Error>(staleResult).error)
        assertNull(r.roomSnapshot.value)

        api.createResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2")
        r.createRoom(CreateRoomRequest(roomId = "room-2"))
        assertEquals("room-2", r.roomSnapshot.value?.roomId)
    }

    // ---- snapshot ordering -------------------------------------------------------

    @Test
    fun `an equal-generation HTTP response cannot erase a newer socket attachment`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 3)))
        runCurrent()

        val gate = CompletableDeferred<ApiResult<RoomResponse>>()
        api.roomReadResult = gate
        val read = async { r.refreshRoom() }
        runCurrent()
        // The socket confirms the attachment while the read is in flight.
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 3, attachedSessionId = "ps-1")))
        runCurrent()
        gate.complete(room(snapshot(generation = 3)))
        read.await()

        assertEquals("ps-1", r.roomSnapshot.value?.attachedSessionId)
        assertEquals("ps-1", r.roomDeliveryEcho.value?.playbackSessionId)
        job.cancel()
    }

    @Test
    fun `an older-generation HTTP response cannot roll the room back`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 6, revision = 3, phase = RoomPhase.Playing)))
        runCurrent()

        api.mutationResponse = room(snapshot(generation = 4, revision = 2))
        r.stageSelection(SetSelectionRequest(contentId = "movie:a"))

        assertEquals(6L, r.roomSnapshot.value?.generation)
        assertEquals(RoomPhase.Playing, r.roomSnapshot.value?.phase)
        job.cancel()
    }

    @Test
    fun `a newer-generation HTTP response advances the room`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        api.mutationResponse = room(snapshot(generation = 7, revision = 2, phase = RoomPhase.Playing))

        r.startPlayback()

        assertEquals(RoomPhase.Playing, r.roomSnapshot.value?.phase)
    }

    @Test
    fun `a delayed lower-generation socket snapshot is ignored`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        api.mutationResponse = room(snapshot(generation = 5, revision = 2, phase = RoomPhase.Playing))
        r.startPlayback()

        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 4)))
        runCurrent()

        assertEquals(5L, r.roomSnapshot.value?.generation)
        job.cancel()
    }

    @Test
    fun `an ended socket snapshot without room_closed is terminal and stops reconnecting`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 8, phase = RoomPhase.Ended)))
        realtime.events.emit(RoomRealtimeEvent.TransportTerminated())
        advanceUntilIdle()

        assertEquals(WatchPartyEndReason.Ended, r.roomClosedReason.value)
        assertEquals(1, realtime.connectCount)
        assertTrue(job.isCompleted)

        // A stale Playing snapshot afterward cannot resurrect it.
        assertNull(r.roomSnapshot.value)
        assertNull(r.membershipGeneration())
    }

    @Test
    fun `snapshot for another room cannot replace the active room`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "room-2", revision = 9)))
        runCurrent()

        assertEquals("room-1", r.roomSnapshot.value?.roomId)
        job.cancel()
    }

    @Test
    fun `late room A socket event cannot overwrite replacement room B`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val oldConnection = launch { r.connect("room-1") }
        runCurrent()

        api.createResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2")
        r.createRoom(CreateRoomRequest(roomId = "room-2"))
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "room-1", revision = 99)))
        advanceUntilIdle()

        assertEquals("room-2", r.roomSnapshot.value?.roomId)
        assertTrue(oldConnection.isCompleted)
        assertTrue(!r.attachSession("session-b"))
        assertEquals(0, realtime.attachCount)
    }

    @Test
    fun `a malformed known frame reconciles by reading the room`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Malformed("snapshot"))
        runCurrent()

        assertEquals(1, api.count("get"))
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    // ---- transport leases ----------------------------------------------------------

    @Test
    fun `transport lease cannot send into a replacement room`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(create())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorizationA = requireNotNull(repository.currentTransportAuthorization())

        api.createResponse = room(
            RoomSnapshot(roomId = "room-2", code = "EFGH5678", phase = RoomPhase.Playing, selfCanControlTransport = true),
            "jwt-room-2",
        )
        repository.createRoom(CreateRoomRequest(roomId = "room-2"))

        assertFalse(
            repository.transportRequestForAuthorization(authorizationA, RoomTransportIntent.PlayPause, "pause", 42.0, true),
        )
        assertTrue(realtime.transportActions.isEmpty())
        connection.cancel()
    }

    @Test
    fun `transport lease revalidates authority after policy changes in the same room`() = runTest {
        val playing = RoomSnapshot(roomId = "room-1", code = "ABCD1234", phase = RoomPhase.Playing, selfCanControlTransport = true)
        val api = FakeApi(room(playing))
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(create())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorizationBeforePolicyChange = requireNotNull(repository.currentTransportAuthorization())
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(playing.copy(selfCanControlTransport = false)))
        runCurrent()

        assertFalse(
            repository.transportRequestForAuthorization(
                authorizationBeforePolicyChange, RoomTransportIntent.PlayPause, "pause", 42.0, true,
            ),
        )
        assertTrue(realtime.transportActions.isEmpty())
        connection.cancel()
    }

    @Test
    fun `blocked physical send does not hold room replacement or reset mutex`() = runTest {
        val playing = RoomSnapshot(roomId = "room-1", code = "ABCD1234", phase = RoomPhase.Playing, selfCanControlTransport = true)
        val api = FakeApi(room(playing))
        val realtime = FakeRealtime().apply { transportResultGate = CompletableDeferred() }
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(create())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorization = requireNotNull(repository.currentTransportAuthorization())

        val blockedSend = async {
            repository.transportRequestForAuthorization(authorization, RoomTransportIntent.PlayPause, "pause", 42.0, true)
        }
        runCurrent()
        val reset = async { repository.reset() }
        runCurrent()

        assertTrue(reset.isCompleted)
        assertFalse(blockedSend.isCompleted)
        realtime.transportResultGate?.complete(false)
        assertFalse(blockedSend.await())
        connection.cancel()
    }

    @Test
    fun `readiness and lobby frames reach the writable socket with their command`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        assertFalse(r.setLobbyReady(true))
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        assertTrue(r.ready("ps-1", 12.0, false, commandId = "cmd-7"))
        assertTrue(r.setLobbyReady(true))
        assertEquals(listOf<String?>("cmd-7"), realtime.readyCommands)
        assertEquals(listOf(true), realtime.lobbyReady)
        job.cancel()
    }

    // ---- connection lifecycle -----------------------------------------------------

    @Test
    fun `realtime reconnect uses the auth scope and proof captured by the membership`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) flow { throw IllegalStateException("drop") } else events.asSharedFlow()
            }
        }
        var currentScope = scopeA
        val r = repo(realtime = realtime, authScopeProvider = { currentScope })
        r.createRoom(create())
        currentScope = scopeA.copy(serverId = "server-b", serverUrl = "https://b.example", identityGeneration = 2L)
        val connection = launch { r.connect("room-1") }
        runCurrent()
        advanceTimeBy(firstBackoff + 1)
        runCurrent()

        assertEquals(2, realtime.connectCount)
        assertTrue(realtime.connectedAuthScopes.all { it == scopeA })
        assertTrue(realtime.connectedRoomTokens.all { it == "jwt-room" })
        connection.cancel()
    }

    @Test
    fun `connect rejects a room id that does not match the active membership`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())

        val job = launch { r.connect("room-2") }
        runCurrent()

        assertEquals(0, realtime.connectCount)
        job.cancel()
    }

    @Test
    fun `routine socket rotation reconnects at once and keeps the epoch advancing`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow {
                        emit(RoomRealtimeEvent.Opened)
                        emit(RoomRealtimeEvent.SnapshotEvent(snapshot()))
                        kotlinx.coroutines.delay(5 * 60_000L)
                        emit(RoomRealtimeEvent.TransportTerminated())
                    }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        // No backoff after a socket that stayed up.
        assertEquals(2, realtime.connectCount)
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        assertEquals(2L, r.connectionState.value.epoch)
        assertNull(r.connectionState.value.disconnectedAtMs)
        job.cancel()
    }

    @Test
    fun `connection replaced is terminal for this device and never reclaims`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot().copy(selfRole = MemberRole.Guest)))
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.ConnectionReplaced("This profile joined the Watch Party on another device."))
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(1, realtime.connectCount)
        assertEquals(WatchPartyEndReason.Replaced, r.roomClosedReason.value)
        assertEquals(WatchPartyEnded("room-1", "ABCD1234", WatchPartyEndReason.Replaced, wasHost = false), r.ended.value)
    }

    @Test
    fun `room_closed populates the reason and reset clears it`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        assertNull(r.roomClosedReason.value)

        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals("host_left", r.roomClosedReason.value)
        assertEquals(1, realtime.connectCount)
        assertTrue(job.isCompleted)

        // A terminal membership cannot open a fresh connection without a new join.
        r.connect("room-1")
        assertEquals(1, realtime.connectCount)

        r.reset()
        assertNull(r.roomClosedReason.value)
        assertNull(r.ended.value)
    }

    @Test
    fun `room_closed without a reason is reported as the host leaving`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Closed(null))
        advanceUntilIdle()

        assertEquals(WatchPartyEndReason.HostLeft, r.roomClosedReason.value)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `error frame surfaces on errors and does not end the party`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        val seen = mutableListOf<String>()
        val errorJob = launch { r.errors.collect { seen.add(it) } }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Error(code = "bad_request", message = "transport rejected"))
        runCurrent()

        assertNull(r.roomClosedReason.value)
        assertEquals(listOf("transport rejected"), seen)
        errorJob.cancel()
        job.cancel()
    }

    @Test
    fun `terminal ticket refusals end the engagement with a matching reason`() = runTest {
        for ((status, reason) in listOf(
            404 to WatchPartyEndReason.NotFound,
            409 to WatchPartyEndReason.Ended,
            401 to WatchPartyEndReason.Unauthorized,
            422 to WatchPartyEndReason.Unauthorized,
        )) {
            val realtime = FakeRealtime().apply {
                connectBehavior = { flow { emit(RoomRealtimeEvent.TransportTerminated(RoomTicketRefusedException(status, "x"))) } }
            }
            val r = repo(realtime = realtime)
            r.createRoom(create())
            r.connect("room-1")
            assertEquals(reason, r.roomClosedReason.value, "status $status")
            assertEquals(1, realtime.connectCount)
        }
    }

    @Test
    fun `a 403 ticket refusal renews the proof once and reconnects when the room is readable`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow { emit(RoomRealtimeEvent.TransportTerminated(RoomTicketRefusedException(403, "permission_denied"))) }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        api.roomReadResponse = room(snapshot(generation = 2), token = "jwt-renewed")
        val job = launch { r.connect("room-1") }
        runCurrent()

        assertEquals(2, realtime.connectCount)
        assertEquals(listOf("jwt-room", "jwt-renewed"), realtime.connectedRoomTokens)
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `an expiring ticket waits briefly and mints again`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow { emit(RoomRealtimeEvent.TransportTerminated(RoomTicketExpiringException())) }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        assertEquals(1, realtime.connectCount)

        advanceTimeBy(testTiming.expiringTicketRetryMs + 1)
        runCurrent()

        assertEquals(2, realtime.connectCount)
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `replacement during reconnect backoff cannot open another stale socket`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) flow { throw IllegalStateException("drop") } else events.asSharedFlow()
            }
        }
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val old = launch { r.connect("room-1") }
        runCurrent()

        api.createResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2")
        r.createRoom(CreateRoomRequest(roomId = "room-2"))
        advanceUntilIdle()

        assertEquals(1, realtime.connectCount)
        assertTrue(old.isCompleted)
    }

    @Test
    fun `newest duplicate connect exclusively owns folds and writable state`() = runTest {
        val first = FakeRealtime()
        val second = FakeRealtime()
        var factoryCall = 0
        val r = repo(realtimeFactory = { if (factoryCall++ == 0) first else second })
        r.createRoom(create())
        val old = launch { r.connect("room-1") }
        runCurrent()
        first.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        assertTrue(r.connectionState.value.writable)
        val newest = launch { r.connect("room-1") }
        runCurrent()
        assertTrue(!r.attachSession("too-early"))
        assertEquals(0, second.attachCount)

        second.events.emit(RoomRealtimeEvent.Opened)
        second.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(revision = 2)))
        runCurrent()
        first.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(revision = 99)))
        runCurrent()

        assertEquals(2L, r.roomSnapshot.value?.selectionRevision)
        assertTrue(r.connectionState.value.writable)
        assertTrue(old.isCompleted)
        newest.cancel()
    }

    @Test
    fun `throw after opened marks the connection unwritable and records when`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) flow {
                    emit(RoomRealtimeEvent.Opened)
                    throw IllegalStateException("drop after open")
                } else events.asSharedFlow()
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()

        assertTrue(!r.connectionState.value.writable)
        assertEquals(0L, r.connectionState.value.disconnectedAtMs)
        assertTrue(!r.attachSession("during-backoff"))
        assertEquals(0, realtime.attachCount)
        job.cancel()
    }

    @Test
    fun `the latest transport command keeps the exact epoch that emitted it`() = runTest {
        val command = TransportCommand(
            commandId = "epoch-1-command",
            sessionId = "playback-1",
            selectionRevision = 1,
            executeAt = "2026-07-27T12:00:00Z",
        )
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow {
                        emit(RoomRealtimeEvent.Opened)
                        emit(RoomRealtimeEvent.TransportCommandEvent(command))
                        emit(RoomRealtimeEvent.TransportTerminated())
                    }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val received = async { r.latestTransportCommand.filterNotNull().first() }
        val job = launch { r.connect("room-1") }
        runCurrent()
        val scheduled = received.await()

        assertEquals(1L, scheduled.connection.epoch)
        assertEquals(parseMs("2026-07-27T12:00:00Z"), scheduled.executeAtMs)
        advanceTimeBy(firstBackoff + 1)
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        assertEquals(2L, r.connectionState.value.epoch)
        assertTrue(scheduled.connection != r.connectionState.value)
        job.cancel()
    }

    @Test
    fun `a burst of commands leaves the newest one for a slow collector`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        repeat(40) { index ->
            realtime.events.emit(
                RoomRealtimeEvent.TransportCommandEvent(
                    TransportCommand(commandId = "cmd-$index", selectionRevision = 1, action = TransportAction.Seek, executeAt = ""),
                ),
            )
        }
        runCurrent()

        assertEquals("cmd-39", r.latestTransportCommand.value?.command?.commandId)
        assertNull(r.latestTransportCommand.value?.executeAtMs)
        job.cancel()
    }

    @Test
    fun `attach echo retains its observed epoch until the reconnect receives a fresh snapshot`() = runTest {
        val attachedSnapshot = snapshot(attachedSessionId = "playback-1")
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow {
                        emit(RoomRealtimeEvent.Opened)
                        emit(RoomRealtimeEvent.SnapshotEvent(attachedSnapshot))
                        emit(RoomRealtimeEvent.TransportTerminated())
                    }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val repository = repo(realtime = realtime)
        repository.createRoom(create())
        val job = launch { repository.connect("room-1") }
        runCurrent()

        assertEquals(1L, repository.roomDeliveryEcho.value?.connectionEpoch)
        advanceTimeBy(firstBackoff + 1)
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        assertEquals(2L, repository.connectionState.value.epoch)
        assertEquals(1L, repository.roomDeliveryEcho.value?.connectionEpoch)

        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(attachedSnapshot))
        runCurrent()

        assertEquals(2L, repository.roomDeliveryEcho.value?.connectionEpoch)
        job.cancel()
    }

    @Test
    fun `send fails before the active connection reports writable`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()

        assertTrue(!r.attachSession("session-1"))
        assertEquals(0, realtime.attachCount)
        job.cancel()
    }

    @Test
    fun `quick failures keep reconnecting through the host grace before giving up`() = runTest {
        val realtime = FakeRealtime().apply { failConnect = true }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }

        advanceTimeBy(testTiming.reconnectBudgetMs - 1)
        runCurrent()
        assertTrue(job.isActive, "gave up inside the reconnect budget")
        assertTrue(realtime.connectCount > testTiming.maxReconnectFailures)

        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(WatchPartyEndReason.ConnectionLost, r.roomClosedReason.value)
        assertTrue(testScheduler.currentTime >= testTiming.reconnectBudgetMs)
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `physical terminations without a snapshot count as failures`() = runTest {
        val realtime = FakeRealtime().apply { terminateImmediately = true }
        val r = repo(realtime = realtime)
        r.createRoom(create())

        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(WatchPartyEndReason.ConnectionLost, r.roomClosedReason.value)
        assertTrue(!r.connectionState.value.writable)
        assertEquals(realtime.connectCount.toLong(), r.connectionState.value.epoch)
    }

    @Test
    fun `a flapping server that emits then drops still exhausts the budget`() = runTest {
        val realtime = FakeRealtime().apply {
            flappingEvent = RoomRealtimeEvent.SnapshotEvent(RoomSnapshot(roomId = "room-1", code = "ABCD1234"))
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(WatchPartyEndReason.ConnectionLost, r.roomClosedReason.value)
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `a wrong-room snapshot cannot count as a healthy connection`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = {
                flow {
                    emit(RoomRealtimeEvent.Opened)
                    kotlinx.coroutines.delay(testTiming.stableConnectionMs)
                    emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "wrong-room")))
                    emit(RoomRealtimeEvent.TransportTerminated())
                }
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())

        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(WatchPartyEndReason.ConnectionLost, r.roomClosedReason.value)
    }

    @Test
    fun `a new membership starts without the previous room's clock samples`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime, timing = testTiming.copy(backgroundWork = true))
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        realtime.events.emit(
            RoomRealtimeEvent.Pong(
                clientSentAt = "2026-09-24T18:00:00.000Z",
                serverReceivedAt = "2026-09-24T18:00:00.240Z",
                serverSentAt = "2026-09-24T18:00:00.241Z",
                clientReceivedMs = parseMs("2026-09-24T18:00:00.081Z"),
            ),
        )
        runCurrent()
        assertEquals(200L, r.clock.value.offsetMs)
        job.cancel()

        r.reset()
        assertNull(r.clock.value.offsetMs)
        r.createRoom(create())
        assertNull(r.clock.value.offsetMs)
    }

    @Test
    fun `pings start when the socket opens and pongs update the clock`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime, timing = testTiming.copy(backgroundWork = true))
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        assertEquals(1, realtime.pings)

        realtime.events.emit(
            RoomRealtimeEvent.Pong(
                clientSentAt = "2026-09-24T18:00:00.000Z",
                serverReceivedAt = "2026-09-24T18:00:00.240Z",
                serverSentAt = "2026-09-24T18:00:00.241Z",
                clientReceivedMs = parseMs("2026-09-24T18:00:00.081Z"),
            ),
        )
        runCurrent()
        assertEquals(200L, r.clock.value.offsetMs)
        assertEquals(80L, r.clock.value.rttMs)

        advanceTimeBy(testTiming.initialPingSpacingMs * 2 + 1)
        runCurrent()
        assertEquals(3, realtime.pings)
        job.cancel()
    }

    // ---- suggestions -------------------------------------------------------------

    @Test
    fun `opening the socket hydrates suggestions off the receive path`() = runTest {
        val api = FakeApi().apply {
            listSuggestionsResponse = ApiResult.Success(SuggestionsResponse(listOf(suggestion("existing"))))
        }
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(create())
        val connection = launch { repository.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        assertEquals(1, api.count("list"))
        assertEquals(listOf("existing"), repository.suggestions.value.map { it.id })
        assertTrue(repository.personalVotesKnown.value)
        connection.cancel()
    }

    @Test
    fun `slow suggestion hydration does not block socket events`() = runTest {
        val api = FakeApi().apply { listSuggestionsResult = CompletableDeferred() }
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(create())
        val connection = launch { repository.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(generation = 4, phase = RoomPhase.Playing)))
        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        runCurrent()

        assertEquals(WatchPartyEndReason.HostLeft, repository.roomClosedReason.value)
        connection.cancel()
    }

    @Test
    fun `suggestion reads drain every page and rank by votes then age`() = runTest {
        val api = FakeApi().apply {
            listSuggestionPages = mutableListOf(
                ApiResult.Success(
                    SuggestionsResponse(
                        listOf(suggestion("old", voteCount = 1, createdAt = "2026-06-12T08:00:00Z")),
                        SuggestionPageInfo(hasMore = true, nextCursor = "c1"),
                    ),
                ),
                ApiResult.Success(
                    SuggestionsResponse(
                        listOf(
                            suggestion("new", voteCount = 1, createdAt = "2026-06-12T09:00:00Z", votedByMe = true),
                            suggestion("top", voteCount = 3, createdAt = "2026-06-12T10:00:00Z"),
                        ),
                    ),
                ),
            )
        }
        val r = repo(api = api)
        r.createRoom(create())

        assertIs<ApiResult.Success<*>>(r.refreshSuggestions())

        assertEquals(listOf<String?>(null, "c1"), api.listCursors)
        assertEquals(listOf("top", "old", "new"), r.suggestions.value.map { it.id })
        assertTrue(r.suggestions.value.last().votedByMe)
    }

    @Test
    fun `personal votes are unknown until an authenticated read`() = runTest {
        val realtime = FakeRealtime()
        val api = FakeApi().apply { listSuggestionsResult = CompletableDeferred() }
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1", voteCount = 2))))
        runCurrent()

        assertFalse(r.personalVotesKnown.value)
        assertEquals(listOf("s1"), r.suggestions.value.map { it.id })
        job.cancel()
    }

    @Test
    fun `a socket broadcast during a read keeps the newer tallies`() = runTest {
        val realtime = FakeRealtime()
        val gate = CompletableDeferred<ApiResult<SuggestionsResponse>>()
        val api = FakeApi()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        api.listSuggestionsResult = gate
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1", voteCount = 5))))
        runCurrent()
        gate.complete(ApiResult.Success(SuggestionsResponse(listOf(suggestion("s1", voteCount = 4, votedByMe = true)))))
        runCurrent()

        val s1 = r.suggestions.value.single()
        assertEquals(5, s1.voteCount)
        assertTrue(s1.votedByMe)
        job.cancel()
    }

    @Test
    fun `suggestions broadcasts keep known personal votes`() = runTest {
        val realtime = FakeRealtime()
        val api = FakeApi()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        // The authenticated read after the vote reports it.
        api.listSuggestionsResponse = ApiResult.Success(
            SuggestionsResponse(listOf(suggestion("s1", voteCount = 1), suggestion("s2", voteCount = 2, votedByMe = true))),
        )
        r.vote("s2")
        realtime.events.emit(
            RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1", voteCount = 1), suggestion("s2", voteCount = 2))),
        )
        runCurrent()

        val byId = r.suggestions.value.associateBy { it.id }
        assertTrue(byId.getValue("s2").votedByMe)
        assertFalse(byId.getValue("s1").votedByMe)

        api.listSuggestionsResponse = ApiResult.Success(SuggestionsResponse(listOf(suggestion("s2", voteCount = 1))))
        r.unvote("s2")
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s2", voteCount = 1))))
        runCurrent()
        assertFalse(r.suggestions.value.single().votedByMe)
        job.cancel()
    }

    @Test
    fun `a successful vote stays successful when the list read fails`() = runTest {
        val api = FakeApi().apply { listSuggestionsResponse = ApiResult.NetworkError(RuntimeException("offline")) }
        val r = repo(api = api)
        r.createRoom(create())

        val result = r.vote("s1")

        assertIs<ApiResult.Success<Unit>>(result)
        assertEquals(1, api.count("vote"))
        assertEquals(1, api.count("list"))
    }

    @Test
    fun `adding a suggestion keeps the caller id and reads the list afterward`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        val request = AddSuggestionRequest(suggestionId = "s-new", contentId = "movie:a", contentType = "movie", title = "A")

        val result = r.addSuggestion(request)

        assertEquals("s-new", assertIs<ApiResult.Success<SuggestionReceipt>>(result).data.suggestionId)
        assertEquals(listOf("suggest", "list"), api.calls.filter { it == "suggest" || it == "list" })
    }

    @Test
    fun `rest refresh replaces the authoritative local vote set`() = runTest {
        val api = FakeApi().apply {
            listSuggestionsResponse = ApiResult.Success(
                SuggestionsResponse(listOf(suggestion("removed-vote", votedByMe = true), suggestion("kept-vote", votedByMe = true))),
            )
        }
        val repository = repo(api = api)
        repository.createRoom(create())

        repository.refreshSuggestions()
        api.listSuggestionsResponse = ApiResult.Success(
            SuggestionsResponse(listOf(suggestion("removed-vote", votedByMe = false), suggestion("kept-vote", votedByMe = true))),
        )
        repository.refreshSuggestions()

        val byId = repository.suggestions.value.associateBy { it.id }
        assertFalse(byId.getValue("removed-vote").votedByMe)
        assertTrue(byId.getValue("kept-vote").votedByMe)
    }

    @Test
    fun `a stale hydration cannot publish into a replacement room`() = runTest {
        val gate = CompletableDeferred<ApiResult<SuggestionsResponse>>()
        val api = FakeApi().apply { listSuggestionsResult = gate }
        val r = repo(api = api)
        r.createRoom(create())
        val stale = async { r.refreshSuggestions() }
        runCurrent()

        api.createResponse = room(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2")
        r.createRoom(CreateRoomRequest(roomId = "room-2"))
        gate.complete(ApiResult.Success(SuggestionsResponse(listOf(suggestion("stale")))))

        assertEquals("obsolete_room_request", assertIs<ApiResult.Error>(stale.await()).error)
        assertTrue(r.suggestions.value.isEmpty())
    }

    @Test
    fun `member state and picker reads use the current proof`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())

        r.memberState(listOf("movie:a"))
        assertEquals("jwt-room", api.lastRoomToken)
        r.picker()
        assertEquals(listOf("memberState", "picker"), api.calls.takeLast(2))
    }

    // ---- reset -------------------------------------------------------------------

    @Test
    fun `reset clears room state and refuses member calls`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot()))
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1"))))
        runCurrent()

        r.reset()
        assertNull(r.roomSnapshot.value)
        assertTrue(r.suggestions.value.isEmpty())
        assertNull(r.latestTransportCommand.value)
        api.calls.clear()
        assertIs<ApiResult.Error>(r.promoteSuggestion("s1"))
        assertTrue(api.calls.isEmpty())
        job.cancel()
    }

    @Test
    fun `local delivery failure is surfaced as a non terminal notice`() = runTest {
        val repository = repo()
        val error = async { repository.errors.first() }
        runCurrent()

        repository.reportDeliveryFailure("room_transport_unavailable")

        assertEquals("room_transport_unavailable", error.await())
        assertNull(repository.roomClosedReason.value)
    }

    @Test
    fun `selection mode switch is a room action`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(create())
        r.setSelectionMode(RoomSelectionMode.Vote)
        assertEquals(1, api.count("mode"))
    }

    private fun parseMs(value: String): Long = org.siloserver.silo.util.parseRfc3339ToEpochMillis(value)!!
}
