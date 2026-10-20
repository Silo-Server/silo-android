package org.siloserver.silo.repository

import kotlinx.coroutines.flow.MutableStateFlow
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomMember
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.toList
import org.siloserver.silo.watchtogether.*
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
import org.siloserver.silo.network.RoomWorkRequest
import org.siloserver.silo.network.RoomWorkKind
import org.siloserver.silo.network.RealtimeWorkAdmission
import org.siloserver.silo.network.RealtimeSocketClosed
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.RoomTicketExpiringException
import org.siloserver.silo.network.RoomTicketRefusedException
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.DefaultWatchTogetherRealtimeClient
import org.siloserver.silo.network.WatchTogetherSocketConnection
import org.siloserver.silo.network.WatchTogetherSocketConnector
import org.siloserver.silo.network.WatchTogetherSocketRequest
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.realtimeAdmissionOwner
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.model.watchtogether.WsTransportRequest
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomTransportIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        val workRequests = mutableListOf<Pair<Long, RoomWorkRequest>>()
        var workSendResult = true
        var beforeRetryDispatch: (suspend () -> Unit)? = null
        override suspend fun sendWorkOnConnection(connectionId: Long, request: RoomWorkRequest, beforeDispatch: suspend () -> Boolean): Boolean {
            beforeRetryDispatch?.invoke()
            return beforeDispatch() && sendWorkOnConnection(connectionId, request)
        }
        override suspend fun sendWorkOnConnection(connectionId: Long, request: RoomWorkRequest): Boolean {
            workRequests += connectionId to request
            return workSendResult
        }
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
                emit(RoomRealtimeEvent.Opened)
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
        admissionOwnerProvider: suspend (AuthScopeSnapshot) -> org.siloserver.silo.network.RealtimeAdmissionOwner? = {
            org.siloserver.silo.network.RealtimeAdmissionOwner(it.serverId, it.serverUrl)
        },
    ) = WatchTogetherRepository(
        api = api,
        realtimeFactory = realtimeFactory ?: { realtime },
        monotonicNowMs = now,
        authScopeProvider = authScopeProvider,
        wallClockMs = { 1_000_000L + testScheduler.currentTime },
        timing = timing,
        random = NoJitter,
        admissionOwnerProvider = admissionOwnerProvider,
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
    fun `a 401 ticket refusal reconnects instead of ending the party`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) {
                    flow { emit(RoomRealtimeEvent.TransportTerminated(RoomTicketRefusedException(401, "unauthorized"))) }
                } else {
                    events.asSharedFlow()
                }
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        advanceTimeBy(testTiming.backoffMs.first() + 1)
        runCurrent()

        assertEquals(2, realtime.connectCount)
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `expiring tickets that never stop exhaust the reconnect budget`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { flow { emit(RoomRealtimeEvent.TransportTerminated(RoomTicketExpiringException())) } }
        }
        val r = repo(realtime = realtime)
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        advanceTimeBy(testTiming.reconnectBudgetMs + testTiming.expiringTicketRetryMs + 1)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(WatchPartyEndReason.ConnectionLost, r.roomClosedReason.value)
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
        advanceTimeBy(testTiming.reconnectBudgetMs + testTiming.backoffMs.last() + 1)
        runCurrent()

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

    @Test
    fun `a pong folded after a wait keeps the clock estimate`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime, timing = testTiming.copy(backgroundWork = true))
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        r.checkClockContinuity()

        // A 200 ms offset and an 80 ms round trip, received at [receivedWall].
        fun pong(receivedWall: Long) = RoomRealtimeEvent.Pong(
            clientSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(receivedWall - 81),
            serverReceivedAt = org.siloserver.silo.util.formatEpochMillisRfc3339(receivedWall + 159),
            serverSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(receivedWall + 160),
            clientReceivedMs = receivedWall,
        )
        realtime.events.emit(pong(1_000_000L + testScheduler.currentTime))
        runCurrent()
        assertEquals(200L, r.clock.value.offsetMs)
        val generation = r.clock.value.generation

        // Decoded 1.5 s before it is folded: a wait, not a wall-clock jump.
        realtime.events.emit(pong(1_000_000L + testScheduler.currentTime - 1_500))
        runCurrent()
        r.checkClockContinuity()

        assertEquals(200L, r.clock.value.offsetMs)
        assertEquals(generation, r.clock.value.generation)
        job.cancel()
    }

    @Test
    fun `pings every two seconds until four samples arrive, then every fifteen`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime, timing = testTiming.copy(backgroundWork = true))
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        fun answer() {
            val wall = 1_000_000L + testScheduler.currentTime
            realtime.events.tryEmit(
                RoomRealtimeEvent.Pong(
                    clientSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall - 81),
                    serverReceivedAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall + 159),
                    serverSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall + 160),
                    clientReceivedMs = wall,
                ),
            )
            runCurrent()
        }

        // Unanswered pings keep the short cadence.
        advanceTimeBy(4_001)
        assertEquals(3, realtime.pings)

        // Pings at 6, 8 and 10 s; the fourth sample lands just after the last.
        repeat(4) {
            answer()
            advanceTimeBy(2_000)
        }
        assertEquals(6, realtime.pings)
        // The next ping is 15 s after the one at 10 s.
        advanceTimeBy(12_900)
        assertEquals(6, realtime.pings, "still pinging every 2 s after four samples")
        advanceTimeBy(200)
        assertEquals(7, realtime.pings)
        job.cancel()
    }

    /** Answer with a pong at [offsetMs] and an 80 ms round trip, received now. */
    private fun TestScope.answerPing(realtime: FakeRealtime, offsetMs: Long = 200L) {
        val wall = 1_000_000L + testScheduler.currentTime
        realtime.events.tryEmit(
            RoomRealtimeEvent.Pong(
                clientSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall - 81),
                serverReceivedAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall + offsetMs - 41),
                serverSentAt = org.siloserver.silo.util.formatEpochMillisRfc3339(wall + offsetMs - 40),
                clientReceivedMs = wall,
            ),
        )
        runCurrent()
    }

    @Test
    fun `a clock step drops the estimate and restarts the warm-up`() = runTest {
        var monotonicJumpMs = 0L
        val realtime = FakeRealtime()
        val r = repo(
            realtime = realtime,
            timing = testTiming.copy(backgroundWork = true),
            now = { testScheduler.currentTime + monotonicJumpMs },
        )
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        advanceTimeBy(4_001)
        repeat(4) {
            answerPing(realtime)
            advanceTimeBy(2_000)
        }
        assertEquals(6, realtime.pings)
        assertEquals(200L, r.clock.value.offsetMs)
        val generation = r.clock.value.generation

        // The wall clock moved a minute against the monotonic clock. Android's
        // monotonic clock stops in deep sleep, so the step says nothing
        // reliable about the offset: the samples go.
        monotonicJumpMs = 60_000L
        r.checkClockContinuity()
        assertNull(r.clock.value.offsetMs)
        assertTrue(r.clock.value.generation > generation)
        advanceTimeBy(2_100)
        assertEquals(7, realtime.pings, "the warm-up did not restart")
        job.cancel()
    }

    @Test
    fun `a pong that disagrees with the estimate replaces it and restarts the warm-up`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime, timing = testTiming.copy(backgroundWork = true))
        r.createRoom(create())
        val job = launch { r.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        advanceTimeBy(4_001)
        repeat(4) {
            answerPing(realtime)
            advanceTimeBy(2_000)
        }
        assertEquals(6, realtime.pings)

        // The server clock stepped by 5 s: far outside both samples' error bounds.
        answerPing(realtime, offsetMs = 5_200L)
        assertEquals(5_200L, r.clock.value.offsetMs)
        advanceTimeBy(2_100)
        assertEquals(7, realtime.pings, "the warm-up did not restart")
        job.cancel()
    }

    // ---- suggestions -------------------------------------------------------------

    @Test
    fun `a coalesced suggestion read reports the shared read's failure`() = runTest {
        val api = FakeApi().apply { listSuggestionsResult = CompletableDeferred() }
        val r = repo(api = api)
        r.createRoom(create())
        val first = async { r.refreshSuggestions() }
        runCurrent()
        val second = async { r.refreshSuggestions() }
        val third = async { r.refreshSuggestions() }
        runCurrent()

        api.listSuggestionsResult!!.complete(ApiResult.NetworkError(IllegalStateException("offline")))
        runCurrent()

        assertIs<ApiResult.NetworkError>(first.await())
        assertIs<ApiResult.NetworkError>(second.await())
        assertIs<ApiResult.NetworkError>(third.await())
        assertEquals(2, api.count("list"))
    }

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

    @Test
    fun `actual room client and repository recover a refused wire action but never replay a lost acknowledgement`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "synthetic-session")
            .copy(selfRole = MemberRole.Host, selfCanControlTransport = true)
        val api = FakeApi(room(initial))
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closed = false
        val socket = object : WatchTogetherSocketConnection {
            override val workAdmission = RealtimeWorkAdmission(1, "node", "memory", "synthetic-epoch")
            override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()
            override suspend fun sendText(text: String) { sent += text }
            override suspend fun close() { closed = true; incoming.cancel() }
        }
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = scopeA
            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
                "synthetic-access".takeIf { scope == scopeA }
        }
        val client = DefaultWatchTogetherRealtimeClient(tokens, object : WatchTogetherSocketConnector {
            override suspend fun open(request: WatchTogetherSocketRequest) = socket
        })
        val repository = repo(api = api, realtimeFactory = { client })
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        assertTrue(repository.workAdmissionEnabled)
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.Seek, "seek", 90.0, false))
        val request = SiloJson.decodeFromString(WsTransportRequest.serializer(), sent.single())
        incoming.send("""{"type":"error","code":"realtime_rate_limited","request_id":"${request.requestId}","retry_after_ms":250,"work_started":false}""")
        runCurrent(); advanceTimeBy(249); runCurrent(); assertEquals(1, sent.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(sent.first(), sent.first()), sent)
        val appliedSnapshot = SiloJson.encodeToString(RoomSnapshot.serializer(), initial.copy(generation = 2))
        incoming.send("""{"type":"snapshot","room":$appliedSnapshot}""")
        incoming.send("""{"type":"action_result","request_id":"${request.requestId}","action":"transport_request","status":"applied"}""")
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, api.count("get"))
        assertEquals(2, sent.size)
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "pause", 90.0, true))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, sent.size)
        assertEquals(1, api.count("get"))
        assertEquals(1L, repository.workUpdate.value.uncertaintySequence)
        connection.cancelAndJoin()
        assertTrue(closed)
        assertNull(client.currentConnectionId())
    }

    @Test
    fun `admission retries a correlated transport once and applies its acknowledgement after snapshot`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1").copy(selfRole = MemberRole.Host, selfCanControlTransport = true)
        val api = FakeApi(room(initial))
        val realtime = FakeRealtime()
        val repository = repo(api, realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.Seek, "seek", 90.0, false))
        val request = realtime.workRequests.single().second
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 250, false))
        runCurrent(); advanceTimeBy(249); runCurrent(); assertEquals(1, realtime.workRequests.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(request, request), realtime.workRequests.map { it.second })
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(initial.copy(generation = 2)))
        realtime.events.emit(RoomRealtimeEvent.ActionResult(request.requestId, "transport_request", "applied"))
        runCurrent(); advanceTimeBy(5_001); runCurrent()
        assertEquals(2, realtime.workRequests.size)
        assertFalse(repository.workUpdate.value.uncertain)
        assertEquals(0, api.count("get"))
        connection.cancel()
    }

    @Test
    fun `admission timeout reconciles without replay and downstream error is uncertain`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1").copy(selfRole = MemberRole.Host, selfCanControlTransport = true)
        val api = FakeApi(room(initial))
        val realtime = FakeRealtime()
        val repository = repo(api, realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened); runCurrent()
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "pause", 12.0, true))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, realtime.workRequests.size)
        assertTrue(repository.workUpdate.value.uncertain)
        assertEquals(1, api.count("get"))
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "play", 12.0, false))
        val request = realtime.workRequests.last().second
        realtime.events.emit(RoomRealtimeEvent.Error("operation_failed", "failed", request.requestId, null, true))
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(2, realtime.workRequests.size)
        assertTrue(repository.workUpdate.value.uncertain)
        assertEquals(2, api.count("get"))
        connection.cancel()
    }

    @Test
    fun `a new seek during the action floor cancels the refused old target`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1")
            .copy(selfRole = MemberRole.Host, selfCanControlTransport = true)
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened); runCurrent()
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.Seek, "seek", 20.0, false))
        val old = realtime.workRequests.single().second
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", old.requestId, 1_000, false)); runCurrent()
        assertFalse(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.Seek, "seek", 40.0, false))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(old), realtime.workRequests.map { it.second })
        connection.cancel()
    }

    @Test
    fun `disconnect reconciles uncertain transport even when a report publication follows it`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1")
            .copy(selfRole = MemberRole.Host, selfCanControlTransport = true)
        val api = FakeApi(room(initial))
        val realtime = FakeRealtime()
        val repository = repo(api, realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened); runCurrent()
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "pause", 12.0, true))
        assertTrue(repository.stateReport("session-1", 12.0, true, null, false))
        realtime.events.emit(RoomRealtimeEvent.TransportTerminated()); runCurrent()
        assertEquals(1L, repository.workUpdate.value.uncertaintySequence)
        assertEquals(1, api.count("get"))
        assertEquals(2, realtime.workRequests.size)
        connection.cancel()
    }

    @Test
    fun `room report refusal shares the recovery floor and buffering recovery cancels stale retry`() = runTest {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1")
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened); runCurrent()
        assertTrue(repository.buffering("session-1", 12.0, false))
        val buffer = realtime.workRequests.last().second
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", buffer.requestId, 500, false)); runCurrent()
        assertFalse(repository.ready("session-1", 12.0, false, "command"))
        assertFalse(repository.stateReport("session-1", 12.0, false, "command", true))
        repository.cancelDesiredWork(RoomWorkKind.Buffering)
        advanceTimeBy(500); runCurrent()
        assertEquals(1, realtime.workRequests.size)
        assertTrue(repository.ready("session-1", 13.0, false, "new-command"))
        assertEquals("new-command", realtime.workRequests.last().second.commandId)
        connection.cancel()
    }

    @Test
    fun `another 1008 reason uses opaque backoff even after thirty seconds`() = runTest {
        val realtime = FakeRealtime()
        val repository = repo(realtime = realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot())); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        realtime.events.emit(RoomRealtimeEvent.TransportTerminated(RealtimeSocketClosed(1008, "other policy"))); runCurrent()
        assertEquals(1, realtime.connectCount)
        advanceTimeBy(999); runCurrent(); assertEquals(1, realtime.connectCount)
        advanceTimeBy(1); runCurrent(); assertEquals(2, realtime.connectCount)
        connection.cancel()
    }

    @Test
    fun `503 ticket retry honors metadata and a shield close after thirty seconds cannot renew immediately`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { count -> if (count == 1) flow {
                emit(RoomRealtimeEvent.TransportTerminated(RoomTicketRefusedException(503, "realtime_unavailable", 6_000)))
            } else events.asSharedFlow() }
        }
        val repository = repo(realtime = realtime)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        assertEquals(1, realtime.connectCount)
        advanceTimeBy(5_999); runCurrent(); assertEquals(1, realtime.connectCount)
        advanceTimeBy(1); runCurrent(); assertEquals(2, realtime.connectCount)
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot())); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot())); runCurrent() // useful activity clears the prior outage
        realtime.events.emit(RoomRealtimeEvent.TransportTerminated(RealtimeSocketClosed(1008, "realtime input shield"))); runCurrent()
        assertEquals(2, realtime.connectCount)
        advanceTimeBy(4_999); runCurrent(); assertEquals(2, realtime.connectCount)
        advanceTimeBy(1); runCurrent(); assertEquals(3, realtime.connectCount)
        assertNull(repository.roomClosedReason.value)
        connection.cancel()
    }

    private fun parseMs(value: String): Long = org.siloserver.silo.util.parseRfc3339ToEpochMillis(value)!!
    private class Player : RoomPlayerPort {
        override val observations = MutableStateFlow(RoomPlayerObservation(
            sessionId = "session-1", selectionRevision = 1, sourcePositionSeconds = 100.0,
            durationSeconds = 3000.0, playWhenReady = true, isPlaying = true, state = RoomPlayerState.Ready))
        val seeks = mutableListOf<Double>()
        override fun seekTo(sourceSeconds: Double) { seeks += sourceSeconds }
        override fun setPlaying(playing: Boolean) { }
        override fun setCorrectionRate(rate: Double?) { }
        override fun isBuffered(sourceSeconds: Double) = true
    }
    private data class Harness(
        val repository: WatchTogetherRepository, val wire: FakeRealtime, val api: FakeApi,
        val session: RoomSession, val player: Player, val binding: RoomPlaybackBinding,
        val initial: RoomSnapshot, val notices: MutableList<RoomPlaybackNotice>,
    ) {
        fun transports() = wire.workRequests.map { it.second }.filter { it.kind == RoomWorkKind.Transport }
    }
    private suspend fun TestScope.harness(): Harness {
        val initial = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1").copy(
            selfRole = MemberRole.Host, selfCanControlTransport = true, isPaused = false,
            playbackState = RoomPlaybackState.Playing,
            members = listOf(RoomMember(userId = "fixture-user", isSelf = true)))
        val api = FakeApi(room(initial))
        val wire = FakeRealtime()
        val r = repo(api, wire)
        r.createRoom(create())
        val session = RoomSession(r, backgroundScope, DefaultIdentityTransitionBarrier())
        session.enter("room-1"); runCurrent()
        wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        wire.events.emit(RoomRealtimeEvent.Opened)
        wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
        val stamp = org.siloserver.silo.util.formatEpochMillisRfc3339(1_000_000L + testScheduler.currentTime)
        wire.events.emit(RoomRealtimeEvent.Pong(stamp, stamp, stamp, 1_000_000L + testScheduler.currentTime)); runCurrent()
        val player = Player()
        val binding = RoomPlaybackBinding(r, player, backgroundScope,
            { testScheduler.currentTime }, { 1_000_000L + testScheduler.currentTime })
        val notices = mutableListOf<RoomPlaybackNotice>()
        backgroundScope.launch { binding.notices.toList(notices) }
        binding.start(); runCurrent()
        return Harness(r, wire, api, session, player, binding, initial, notices)
    }
    private suspend fun TestScope.finish(h: Harness) {
        h.binding.dispose(); h.session.leave(); runCurrent()
        assertFalse(h.session.isActive())
    }
    private suspend fun TestScope.refuse(h: Harness, request: RoomWorkRequest, delay: Long) {
        h.wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "fixture refusal", request.requestId, delay, false))
        runCurrent()
    }
    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun A2_olderTimeoutMustNotClearOrCancelNewerUnsentSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250)
        assertEquals(listOf(110.0), h.transports().map { it.positionSeconds })
        advance(4_900)
        h.binding.requestSeek(140.0); runCurrent()
        assertEquals(140.0, h.binding.pendingSeekSeconds.value)
        advance(100) // A reaches its 5s acknowledgement deadline; B is 100ms into its window.
        assertEquals(1L, h.repository.workUpdate.value.uncertaintySequence)
        assertEquals(1, h.api.count("get"))
        val heldAtTimeout = h.binding.pendingSeekSeconds.value
        advance(150)
        assertEquals(140.0, heldAtTimeout, "A's timeout must retain B's immediately displayed target")
        assertEquals(listOf(110.0, 140.0), h.transports().map { it.positionSeconds })
        } finally { finish(h) }
    }
    @Test fun A2_olderRefusedDeadlineMustNotClearNewerUnsentSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250)
        refuse(h, h.transports().single(), 5_000)
        advance(4_900)
        h.binding.requestSeek(140.0); runCurrent()
        advance(100) // Refused A expires, without replay; B has not yet reached sendWork.
        assertEquals(1L, h.repository.workUpdate.value.discardedTransportSequence)
        val heldAtDiscard = h.binding.pendingSeekSeconds.value
        advance(150)
        assertEquals(140.0, heldAtDiscard, "Discarding A must retain B's displayed target")
        assertEquals(listOf(110.0, 140.0), h.transports().map { it.positionSeconds })
        } finally { finish(h) }
    }
    @Test fun controlOlderSuccessKeepsNewerUnsentSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); val a = h.transports().single()
        advance(4_900); h.binding.requestSeek(140.0); runCurrent()
        h.wire.events.emit(RoomRealtimeEvent.ActionResult(a.requestId, "transport_request", "applied")); runCurrent()
        advance(250)
        assertEquals(listOf(110.0, 140.0), h.transports().map { it.positionSeconds })
        assertEquals(140.0, h.binding.pendingSeekSeconds.value)
        assertEquals(0L, h.repository.workUpdate.value.uncertaintySequence)
        } finally { finish(h) }
    }
    @Test fun controlOlderPauseTimeoutKeepsNewerUnsentSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestPlayPause(true); runCurrent(); advance(4_900)
        h.binding.requestSeek(140.0); runCurrent(); advance(250)
        assertEquals(listOf("pause", "seek"), h.transports().map { it.action })
        assertEquals(140.0, h.binding.pendingSeekSeconds.value)
        assertTrue(RoomPlaybackNotice.Uncertain in h.notices)
        } finally { finish(h) }
    }
    @Test fun controlCurrentSeekUncertaintyClearsHeldTargetAndNeverReplays() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); advance(5_000)
        assertNull(h.binding.pendingSeekSeconds.value)
        assertEquals(1, h.transports().size)
        assertEquals(1, h.api.count("get"))
        assertTrue(RoomPlaybackNotice.Uncertain in h.notices)
        } finally { finish(h) }
    }
    @Test fun controlRefusalRetriesSameRequestOnceAfterFloor() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); val a = h.transports().single()
        refuse(h, a, 1000); advance(999)
        assertEquals(1, h.transports().size)
        advance(1)
        assertEquals(listOf(a, a), h.transports())
        h.wire.events.emit(RoomRealtimeEvent.ActionResult(a.requestId, "transport_request", "applied")); runCurrent()
        advance(5_001)
        assertEquals(2, h.transports().size)
        assertEquals(0L, h.repository.workUpdate.value.uncertaintySequence)
        } finally { finish(h) }
    }
    private suspend fun TestScope.pauseSuccess(h: Harness) {
        val pause = h.transports().single { it.action == "pause" }
        // Same ordered shapes as pinned server: snapshot, command, then action_result.
        h.wire.events.emit(RoomRealtimeEvent.SnapshotEvent(h.initial.copy(generation = 2,
            isPaused = true, playbackState = RoomPlaybackState.Paused)))
        h.wire.events.emit(RoomRealtimeEvent.TransportCommandEvent(TransportCommand(
            commandId = "fixture-pause", sessionId = "session-1", selectionRevision = 1,
            action = TransportAction.Pause, positionSeconds = 100.0,
            executeAt = org.siloserver.silo.util.formatEpochMillisRfc3339(1_000_000L + testScheduler.currentTime + 350),
            playbackState = RoomPlaybackState.Paused)))
        h.wire.events.emit(RoomRealtimeEvent.ActionResult(pause.requestId, "transport_request", "applied"))
        runCurrent()
    }
    @Test fun A4_ownDistinctPauseSuccessMustNotLosePreviouslyQueuedSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(140.0); runCurrent()
        advance(100)
        h.binding.requestPlayPause(true); runCurrent()
        pauseSuccess(h)
        advance(150)
        assertEquals(listOf("seek", "pause"), h.transports().map { it.action }, "Both distinct user inputs must survive the seek window")
        } finally { finish(h) }
    }
    @Test fun distinctPauseInputsFlushQueuedSeekOnlyOnce() = runTest {
        val h = harness()
        try {
            h.binding.requestSeek(140.0); runCurrent(); advance(100)
            h.binding.requestPlayPause(true)
            h.binding.requestPlayPause(false)
            runCurrent(); advance(150)
            assertEquals(listOf("seek", "pause", "play"), h.transports().map { it.action })
        } finally { finish(h) }
    }
    @Test fun controlPauseWithoutResponseDoesNotMergeSeek() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(140.0); runCurrent(); advance(100)
        h.binding.requestPlayPause(true); runCurrent(); advance(150)
        assertEquals(listOf("seek", "pause"), h.transports().map { it.action })
        assertEquals(140.0, h.binding.pendingSeekSeconds.value)
        } finally { finish(h) }
    }
    @Test fun controlSeparateGenerationChangeFencesQueuedSeekVisibly() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(140.0); runCurrent(); advance(100)
        h.wire.events.emit(RoomRealtimeEvent.SnapshotEvent(h.initial.copy(generation = 2))); runCurrent()
        advance(150)
        assertTrue(h.transports().isEmpty())
        assertNull(h.binding.pendingSeekSeconds.value)
        assertTrue(RoomPlaybackNotice.Undelivered in h.notices)
        } finally { finish(h) }
    }
    @Test fun controlNewSessionFencesQueuedSeekVisibly() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(140.0); runCurrent(); advance(100)
        h.player.observations.value = h.player.observations.value.copy(sessionId = "fixture-session-2"); runCurrent()
        advance(150)
        assertTrue(h.transports().isEmpty())
        assertNull(h.binding.pendingSeekSeconds.value)
        assertTrue(RoomPlaybackNotice.Undelivered in h.notices)
        } finally { finish(h) }
    }
    @Test fun A7_disposalMustCancelRefusedPlaybackRetryWhileMembershipContinues() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); val a = h.transports().single()
        refuse(h, a, 1_000); advance(100)
        h.binding.dispose(); runCurrent()
        assertTrue(h.session.isActive(), "Leaving the player intentionally preserves room membership")
        // A platform session ending after its observer was disposed cannot update the repository.
        h.player.observations.value = h.player.observations.value.copy(sessionId = null); runCurrent()
        advance(900)
        assertEquals(listOf(a), h.transports(), "Disposed binding must revoke permission to automatically replay its transport")
        } finally { finish(h) }
    }
    @Test fun controlDisposalCancelsUnsentSeekWindow() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); runCurrent(); advance(100)
        h.binding.dispose(); advance(1_000)
        assertTrue(h.transports().isEmpty())
        assertTrue(h.session.isActive())
        } finally { finish(h) }
    }
    @Test fun controlObservedSessionEndBeforeDisposalCancelsRefusedRetry() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); val a = h.transports().single()
        refuse(h, a, 1_000); advance(100)
        h.player.observations.value = h.player.observations.value.copy(sessionId = null); runCurrent()
        h.binding.dispose(); advance(900)
        assertEquals(listOf(a), h.transports())
        assertTrue(h.session.isActive())
        } finally { finish(h) }
    }
    @Test fun controlMembershipLeaveCancelsRefusedRetry() = runTest {
        val h = harness()
        try {
        h.binding.requestSeek(110.0); advance(250); val a = h.transports().single()
        refuse(h, a, 1_000); advance(100)
        h.session.leave(); advance(900)
        assertEquals(listOf(a), h.transports())
        assertFalse(h.session.isActive())
        } finally { finish(h) }
    }
    private fun initialPlaying() = snapshot(phase = RoomPhase.Playing, attachedSessionId = "session-1")
        .copy(selfRole = MemberRole.Host, selfCanControlTransport = true, playbackState = RoomPlaybackState.Playing,
            members = listOf(RoomMember(userId = "synthetic-user", isSelf = true)))

    private suspend fun TestScope.open(repository: WatchTogetherRepository, realtime: FakeRealtime, initial: RoomSnapshot): kotlinx.coroutines.Job {
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        realtime.events.emit(RoomRealtimeEvent.Opened)
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(initial))
        runCurrent()
        assertTrue(repository.workAdmissionEnabled)
        return connection
    }

    @Test fun canceledDesireRetainsCorrelationAndClassMinimum() = runTest {
        val initial = initialPlaying()
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        val connection = open(repository, realtime, initial)
        assertTrue(repository.buffering("session-1", 12.0, false))
        val refused = realtime.workRequests.single().second
        repository.cancelDesiredWork(RoomWorkKind.Buffering)
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", refused.requestId, 60_000, false))
        runCurrent()
        assertFalse(repository.ready("session-1", 13.0, false, "synthetic-new-command"))
        assertEquals(1, realtime.workRequests.size)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(repository.workUpdate.value.refused)
        connection.cancelAndJoin()
    }

    @Test fun selectedRetryExpiresWhileReconciliationSuspends() = runTest {
        val initial = initialPlaying()
        val api = FakeApi(room(initial))
        val realtime = FakeRealtime()
        val repository = repo(api, realtime)
        val connection = open(repository, realtime, initial)
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "pause", 12.0, true)) // t=0, no acknowledgement
        advanceTimeBy(1000); runCurrent()
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "play", 12.0, false)) // original deadline t=6000
        val retried = realtime.workRequests.last().second
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", retried.requestId, 4000, false))
        runCurrent()
        api.roomReadResult = CompletableDeferred()
        advanceTimeBy(4000); runCurrent() // t=5000: pause times out, play selected, reconcile waits
        assertEquals(1, api.count("get"))
        assertEquals(2, realtime.workRequests.size)
        advanceTimeBy(1500); runCurrent() // t=6500, play is 5500ms old
        api.roomReadResult!!.complete(room(initial))
        runCurrent()
        assertEquals(2, realtime.workRequests.size)
        assertTrue(repository.workUpdate.value.discarded)
        assertEquals(6500L, testScheduler.currentTime)
        assertEquals(1, api.count("get"))
        connection.cancelAndJoin()
    }

    @Test fun selectedRetryExpiresWhileClientDispatchSuspends() = runTest {
        val initial = initialPlaying()
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        val connection = open(repository, realtime, initial)
        try {
            assertTrue(repository.transportRequest("pause", 12.0, true))
            val request = realtime.workRequests.single().second
            val gate = CompletableDeferred<Unit>()
            realtime.beforeRetryDispatch = { gate.await() }
            realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 1000, false))
            runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(1, realtime.workRequests.size)
            advanceTimeBy(4500); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(1, realtime.workRequests.size)
            assertEquals(0L, repository.workUpdate.value.uncertaintySequence)
            assertTrue(repository.workUpdate.value.discarded)
        } finally { connection.cancelAndJoin() }
    }

    private class VerifierPlayer : RoomPlayerPort {
        override val observations = MutableStateFlow(RoomPlayerObservation(
            sessionId = "session-1", selectionRevision = 1, sourcePositionSeconds = 12.0,
            durationSeconds = 3000.0, playWhenReady = true, isPlaying = true, state = RoomPlayerState.Ready))
        override fun seekTo(sourceSeconds: Double) { }
        override fun setPlaying(playing: Boolean) { }
        override fun setCorrectionRate(rate: Double?) { }
        override fun isBuffered(sourceSeconds: Double) = true
    }

    @Test fun bindingDisposalAndEndedPlayerSessionRevokeRepositoryRetry() = runTest {
        val initial = initialPlaying()
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        val connection = open(repository, realtime, initial)
        val player = VerifierPlayer()
        val binding = RoomPlaybackBinding(repository, player, backgroundScope,
            { testScheduler.currentTime }, { 1_000_000L + testScheduler.currentTime })
        binding.start(); runCurrent()
        assertEquals(RoomTransportResult.Sent, binding.requestPlayPause(true))
        runCurrent()
        val request = realtime.workRequests.map { it.second }.single { it.kind == RoomWorkKind.Transport }
        realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 1000, false))
        runCurrent()
        binding.dispose(); runCurrent()
        player.observations.value = player.observations.value.copy(sessionId = null, state = RoomPlayerState.Idle)
        runCurrent()
        advanceTimeBy(1000); runCurrent()
        val transports = realtime.workRequests.map { it.second }.filter { it.kind == RoomWorkKind.Transport }
        assertEquals(listOf(request), transports)
        assertTrue(repository.connectionState.value.writable)
        assertTrue(repository.currentTransportAuthorization() != null)
        connection.cancelAndJoin()
    }

    @Test fun optionalTransportPositionRemainsAbsentThroughRepositoryAndEncoder() = runTest {
        val initial = initialPlaying().copy(anchorPositionSeconds = 120.0)
        val realtime = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), realtime)
        val connection = open(repository, realtime, initial)
        assertTrue(repository.transportRequestForAuthorization(repository.currentTransportAuthorization()!!,
            RoomTransportIntent.PlayPause, "pause", null, true))
        val request = realtime.workRequests.single().second
        val encoded = SiloJson.parseToJsonElement(request.encode(SiloJson)).jsonObject
        assertNull(request.positionSeconds)
        assertTrue(encoded["position_seconds"] == null || encoded["position_seconds"] is JsonNull)
        val legacy = SiloJson.parseToJsonElement(SiloJson.encodeToString(WsTransportRequest.serializer(),
            WsTransportRequest(action = "pause", positionSeconds = null, isPaused = true))).jsonObject
        assertTrue(legacy["position_seconds"] == null || legacy["position_seconds"] is JsonNull)
        connection.cancelAndJoin()
    }
    @Test fun recoveryClassRefusalFencesRoomReconnectButActionClassDoesNot() = runTest {
        for (kind in listOf(RoomWorkKind.Report, RoomWorkKind.Transport)) {
            val initial = initialPlaying()
            val realtime = FakeRealtime()
            val repository = repo(FakeApi(room(initial)), realtime)
            val connection = open(repository, realtime, initial)
            try {
                if (kind == RoomWorkKind.Report) assertTrue(repository.stateReport("session-1", 12.0, false, null, false))
                else assertTrue(repository.transportRequest("pause", 12.0, true))
                val request = realtime.workRequests.single().second
                realtime.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 60_000, false))
                runCurrent()
                realtime.events.emit(RoomRealtimeEvent.TransportTerminated()); runCurrent()
                val minimum = if (kind == RoomWorkKind.Report) 60_000L else 1_000L
                advanceTimeBy(minimum - 1); runCurrent()
                assertEquals(1, realtime.connectCount, "fresh connector admission must respect the applicable recovery class")
                advanceTimeBy(1); runCurrent()
                assertEquals(2, realtime.connectCount)
                assertEquals(1, realtime.workRequests.size, "reconnect must never replay transport")
            } finally { connection.cancelAndJoin() }
        }
    }

    @Test fun sweepSeekEligibilitySurvivesEarlyFlushAndDistinctActions() = runTest {
        val h = harness()
        try {
            h.binding.requestSeek(210.0); advance(90)
            h.binding.requestPlayPause(true); runCurrent()
            assertEquals(listOf("seek", "pause"), h.transports().map { it.action })
            advance(50); h.binding.requestSeek(240.0)
            assertEquals(240.0, h.binding.pendingSeekSeconds.value)
            advance(50); h.binding.requestPlayPause(false); runCurrent()
            assertEquals(listOf("seek", "pause"), h.transports().map { it.action }, "Only one seek in the original 0..250ms window; play stays behind the second seek")
            advance(59)
            assertEquals(2, h.transports().size)
            advance(1)
            assertEquals(listOf("seek", "pause", "seek", "play"), h.transports().map { it.action })
            assertEquals(listOf(210.0, 240.0), h.transports().filter { it.action == "seek" }.map { it.positionSeconds })
        } finally { finish(h) }
    }

    private suspend fun TestScope.sweepOwnerTransition(change: String) {
        val initial = initialPlaying()
        val wire = FakeRealtime()
        val api = FakeApi(room(initial))
        var authority = scopeA.copy(credentialEpoch = 1, isIdentityGenerationStamped = true)
        val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
            override suspend fun snapshotCurrentScope() = authority
            override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority("stable-login", authority)
        }
        val repository = repo(api, wire, authScopeProvider = { authority }, admissionOwnerProvider = { tokens.realtimeAdmissionOwner(it) })
        val barrier = DefaultIdentityTransitionBarrier()
        val session = RoomSession(repository, backgroundScope, barrier)
        repository.createRoom(create()); session.enter(initial.roomId); runCurrent()
        wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        wire.events.emit(RoomRealtimeEvent.Opened); wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
        assertTrue(repository.stateReport("session-1", 50.0, false, null, false))
        val report = wire.workRequests.single().second
        wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", report.requestId, 60_000, false)); runCurrent()
        barrier.changing(if (change == "server") IdentityTransitionKind.SERVER_SWITCH else IdentityTransitionKind.PROFILE_SWITCH) {
            authority = when (change) {
                "server" -> authority.copy(serverId = "server-b", serverUrl = "https://b.invalid", identityGeneration = 2, credentialEpoch = 2)
                "profile" -> authority.copy(profileId = "profile-b", profileToken = "new-proof", identityGeneration = 2)
                else -> authority.copy(credentialEpoch = 2) // live credentials may refresh without changing the login
            }
        }
        assertFalse(session.isActive()); assertNull(repository.roomSnapshot.value)
        val next = initial.copy(roomId = "room-next"); api.createResponse = room(next)
        repository.createRoom(CreateRoomRequest(roomId = next.roomId)); session.enter(next.roomId); runCurrent()
        try {
            if (change == "server") assertEquals(2, wire.connectCount, "An unrelated server owns no prior floor")
            else {
                assertEquals(1, wire.connectCount); advance(59_999); assertEquals(1, wire.connectCount)
                advance(1); assertEquals(2, wire.connectCount)
            }
        } finally { session.leave(); runCurrent() }
    }
    @Test fun sweepUnrelatedServerDoesNotInheritRoomFloor() = runTest { sweepOwnerTransition("server") }
    @Test fun sweepSameLoginProfileAndCredentialChangesRetainRoomFloor() = runTest {
        sweepOwnerTransition("profile"); sweepOwnerTransition("refresh")
    }

    @Test fun sweepFinalIdentityRejectionDiscardsKnownRefusedRetry() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            assertTrue(repository.transportRequest("pause", 12.0, true))
            val request = wire.workRequests.single().second
            val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            wire.beforeRetryDispatch = { entered.complete(Unit); gate.await() }
            wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 750, false))
            runCurrent(); advance(750); assertTrue(entered.isCompleted)
            wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial.copy(generation = 3))); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(1, wire.workRequests.size)
            assertEquals(0L, repository.workUpdate.value.uncertaintySequence)
            assertEquals(1L, repository.workUpdate.value.discardedTransportSequence)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

    private suspend fun TestScope.sweepShieldTermination(cause: Throwable?, notice: Boolean, wait: Long) {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            if (notice) wire.events.emit(RoomRealtimeEvent.Error("realtime_input_limited", "shield"))
            wire.events.emit(RoomRealtimeEvent.TransportTerminated(cause)); runCurrent()
            advance(wait - 1); assertEquals(1, wire.connectCount)
            advance(1); assertEquals(2, wire.connectCount)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }
    @Test fun sweepShieldNoticeSurvivesEof() = runTest { sweepShieldTermination(null, true, 5_000) }
    @Test fun sweepShieldNoticeSurvivesTransportException() = runTest {
        sweepShieldTermination(IllegalStateException("synthetic EOF"), true, 5_000)
    }
    @Test fun sweepShieldAndUnrelated1008RemainDistinct() = runTest {
        sweepShieldTermination(RealtimeSocketClosed(1008, "realtime input shield"), false, 5_000)
        sweepShieldTermination(RealtimeSocketClosed(1008, "other policy"), false, 1_000)
    }

    @Test fun sweepNewLoginOnSameServerStartsWithoutOldFloor() = runTest {
        val initial = initialPlaying(); val api = FakeApi(room(initial)); val wire = FakeRealtime()
        var authority = scopeA
        var login = "login-a"
        val tokens = object : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
            override suspend fun snapshotCurrentScope() = authority
            override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority(login, authority)
        }
        val repository = WatchTogetherRepository(api, { wire }, { testScheduler.currentTime },
            { authority }, { 1_000_000L + testScheduler.currentTime }, testTiming, NoJitter,
            admissionOwnerProvider = { tokens.realtimeAdmissionOwner(it) })
        val barrier = DefaultIdentityTransitionBarrier(); val session = RoomSession(repository, backgroundScope, barrier)
        repository.createRoom(create()); session.enter(initial.roomId); runCurrent()
        wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
        wire.events.emit(RoomRealtimeEvent.Opened); wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
        repository.stateReport("session-1", 50.0, false, null, false)
        val request = wire.workRequests.single().second
        wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", request.requestId, 60_000, false)); runCurrent()
        barrier.changing(IdentityTransitionKind.ACCOUNT_REPLACE) { login = "login-b"; authority = authority.copy(identityGeneration = 2) }
        assertFalse(session.isActive())
        repository.createRoom(create()); session.enter(initial.roomId); runCurrent()
        try { assertEquals(2, wire.connectCount) } finally { session.leave(); runCurrent() }
    }

    @Test fun sweepSeekWindowAndDistinctOrderSurviveOwnPauseResponseFrames() = runTest {
        val h = harness()
        try {
            h.binding.requestSeek(210.0); advance(90)
            h.binding.requestPlayPause(true); runCurrent(); pauseSuccess(h)
            advance(50); h.binding.requestSeek(240.0)
            advance(50); h.binding.requestPlayPause(false); runCurrent()
            assertEquals(listOf("seek", "pause"), h.transports().map { it.action })
            assertEquals(240.0, h.binding.pendingSeekSeconds.value)
            advance(60)
            assertEquals(listOf("seek", "pause", "seek", "play"), h.transports().map { it.action })
            assertEquals(0L, h.repository.workUpdate.value.discardedTransportSequence)
        } finally { finish(h) }
    }

    @Test fun sweepShieldNoticeOnOldSocketCannotTakeImmediateRenewalPath() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            advance(30_000)
            wire.events.emit(RoomRealtimeEvent.Error("realtime_input_limited", "shield"))
            wire.events.emit(RoomRealtimeEvent.TransportTerminated()); runCurrent()
            advance(4_999); assertEquals(1, wire.connectCount)
            advance(1); assertEquals(2, wire.connectCount)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

    @Test fun backgroundQuietRoomSnapshotRestoresRenewalWithoutAnotherFrame() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            wire.events.emit(RoomRealtimeEvent.TransportTerminated(RealtimeSocketClosed(1008, "realtime input shield")))
            runCurrent(); advance(5_000); assertEquals(2, wire.connectCount)
            wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
            wire.events.emit(RoomRealtimeEvent.Opened)
            wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
            advance(30_000)
            wire.events.emit(RoomRealtimeEvent.TransportTerminated()); runCurrent()
            assertEquals(3, wire.connectCount, "A single accepted snapshot followed by quiet uptime qualifies")
            wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
            wire.events.emit(RoomRealtimeEvent.Opened); runCurrent()
            advance(30_000) // no accepted frame on this attempt
            wire.events.emit(RoomRealtimeEvent.TransportTerminated(RealtimeSocketClosed(1008, "realtime input shield")))
            runCurrent(); advance(4_999); assertEquals(3, wire.connectCount)
            advance(1); assertEquals(4, wire.connectCount, "Prior accepted health resets shield to five seconds")
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

    @Test fun backgroundRaisedFloorAtFinalDispatchVisiblyDiscardsOnlySelectedTransport() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            assertTrue(repository.transportRequest("pause", 12.0, true))
            assertTrue(repository.transportRequest("play", 12.0, false))
            val first = wire.workRequests[0].second; val second = wire.workRequests[1].second
            val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            wire.beforeRetryDispatch = { entered.complete(Unit); gate.await() }
            wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", first.requestId, 750, false))
            runCurrent(); advance(750); assertTrue(entered.isCompleted)
            wire.events.emit(RoomRealtimeEvent.Error("realtime_rate_limited", "wait", second.requestId, 2_000, false))
            runCurrent(); gate.complete(Unit); runCurrent()
            assertEquals(2, wire.workRequests.size)
            assertEquals(0L, repository.workUpdate.value.uncertaintySequence)
            assertEquals(1L, repository.workUpdate.value.discardedTransportSequence)
            advance(1_999); assertEquals(2, wire.workRequests.size)
            advance(1); assertEquals(listOf(first, second, second), wire.workRequests.map { it.second })
            wire.events.emit(RoomRealtimeEvent.ActionResult(second.requestId, "transport_request", "applied")); runCurrent()
            advance(5_000)
            assertEquals(0L, repository.workUpdate.value.uncertaintySequence)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

    @Test fun backgroundTimeAfterShieldNoticeDoesNotQualifyAsRoomHealth() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime()
        val repository = repo(FakeApi(room(initial)), wire); val connection = open(repository, wire, initial)
        try {
            wire.events.emit(RoomRealtimeEvent.TransportTerminated(RealtimeSocketClosed(1008, "realtime input shield")))
            runCurrent(); advance(5_000); assertEquals(2, wire.connectCount)
            wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
            wire.events.emit(RoomRealtimeEvent.Opened)
            wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
            advance(1_000)
            wire.events.emit(RoomRealtimeEvent.Error("realtime_input_limited", "shield")); runCurrent()
            advance(30_000)
            wire.events.emit(RoomRealtimeEvent.TransportTerminated()); runCurrent()
            advance(9_999); assertEquals(2, wire.connectCount)
            advance(1); assertEquals(3, wire.connectCount)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

    @Test fun backgroundQuietAcceptedRoomResetsOutageBeforeTransportRefusal() = runTest {
        val initial = initialPlaying(); val wire = FakeRealtime().apply {
            connectBehavior = { count -> if (count <= 5) flow {
                emit(RoomRealtimeEvent.TransportTerminated(RoomTicketRefusedException(503, "realtime_unavailable", 250)))
            } else events.asSharedFlow() }
        }
        val repository = repo(FakeApi(room(initial)), wire)
        repository.createRoom(create())
        val connection = backgroundScope.launch { repository.connect("room-1") }; runCurrent()
        try {
            for ((index, delay) in listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L).withIndex()) {
                assertEquals(index + 1, wire.connectCount)
                advance(delay)
            }
            assertEquals(6, wire.connectCount)
            wire.events.emit(RoomRealtimeEvent.Admission(RealtimeWorkAdmission(version = 1)))
            wire.events.emit(RoomRealtimeEvent.Opened)
            wire.events.emit(RoomRealtimeEvent.SnapshotEvent(initial)); runCurrent()
            advance(30_000) // quiet accepted room; pongs/open alone never supply health
            assertTrue(repository.transportRequest("pause", 12.0, true))
            val request = wire.workRequests.single().second
            wire.events.emit(RoomRealtimeEvent.Error("realtime_unavailable", "wait", request.requestId, 250, false)); runCurrent()
            assertEquals(0L, repository.workUpdate.value.discardedTransportSequence)
            advance(1_999); assertEquals(1, wire.workRequests.size)
            advance(1); assertEquals(listOf(request, request), wire.workRequests.map { it.second })
            wire.events.emit(RoomRealtimeEvent.ActionResult(request.requestId, "transport_request", "applied")); runCurrent()
            advance(5_000)
            assertEquals(0L, repository.workUpdate.value.uncertaintySequence)
            assertEquals(0L, repository.workUpdate.value.discardedTransportSequence)
        } finally { connection.cancelAndJoin(); repository.reset() }
    }

}
