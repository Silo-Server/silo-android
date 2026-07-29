package org.siloserver.silo.repository

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomTransportIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        memberCount: Int = 1,
    ) = RoomSnapshot(roomId = roomId, selectionRevision = revision, memberCount = memberCount, code = "ABCD1234")

    private fun suggestion(id: String, voteCount: Int = 0, votedByMe: Boolean = false) = Suggestion(
        id = id, roomId = "room-1", contentId = "c-$id", contentType = "movie",
        title = "T-$id", voteCount = voteCount, votedByMe = votedByMe, createdAt = "2026-06-12T08:00:00Z",
    )

    private class FakeApi(
        var createResponse: ApiResult<RoomResponse> = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-1", code = "ABCD1234"), "jwt-room"),
        ),
    ) : WatchTogetherApi {
        var createCalls = 0
        var addSuggestionCalls = 0
        var voteCalls = 0
        var promoteCalls = 0
        var closeCalls = 0
        var lastRoomToken: String? = null
        var lastRoomId: String? = null
        var lastSelection: SetSelectionRequest? = null
        var lastAuthScope: AuthScopeSnapshot? = null
        var createResult: CompletableDeferred<ApiResult<RoomResponse>>? = null
        var selectionResult: CompletableDeferred<ApiResult<RoomResponse>>? = null
        var listSuggestionsResponse: ApiResult<SuggestionsResponse> =
            ApiResult.Success(SuggestionsResponse())
        var listSuggestionsResult: CompletableDeferred<ApiResult<SuggestionsResponse>>? = null
        var listSuggestionsCalls = 0
        override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> {
            createCalls++
            lastAuthScope = scope
            return createResult?.await() ?: createResponse
        }
        override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot) =
            createResponse.also { lastAuthScope = scope }
        override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot) =
            createResponse.also { lastRoomToken = roomToken; lastAuthScope = scope }
        override suspend fun setSelection(
            roomId: String,
            roomToken: String,
            request: SetSelectionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<RoomResponse> {
            lastRoomId = roomId
            lastRoomToken = roomToken
            lastSelection = request
            lastAuthScope = scope
            return selectionResult?.await() ?: createResponse
        }
        override suspend fun updatePolicy(
            roomId: String,
            roomToken: String,
            request: UpdatePolicyRequest,
            scope: AuthScopeSnapshot,
        ) = createResponse.also { lastRoomToken = roomToken; lastAuthScope = scope }
        override suspend fun closeRoom(
            roomId: String,
            roomToken: String,
            scope: AuthScopeSnapshot,
        ): ApiResult<Unit> {
            closeCalls++
            lastRoomToken = roomToken
            lastAuthScope = scope
            return ApiResult.Success(Unit)
        }
        override suspend fun listSuggestions(roomId: String, roomToken: String, scope: AuthScopeSnapshot) =
            (listSuggestionsResult?.await() ?: listSuggestionsResponse).also {
                listSuggestionsCalls++
                lastRoomToken = roomToken
                lastAuthScope = scope
            }
        override suspend fun addSuggestion(
            roomId: String,
            roomToken: String,
            request: AddSuggestionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<SuggestionsResponse> {
            addSuggestionCalls++
            lastRoomToken = roomToken
            lastAuthScope = scope
            return ApiResult.Success(SuggestionsResponse())
        }
        override suspend fun deleteSuggestion(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken; lastAuthScope = scope }
        override suspend fun vote(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ): ApiResult<SuggestionsResponse> {
            voteCalls++
            lastRoomToken = roomToken
            lastAuthScope = scope
            return ApiResult.Success(SuggestionsResponse())
        }
        override suspend fun unvote(
            roomId: String,
            roomToken: String,
            suggestionId: String,
            scope: AuthScopeSnapshot,
        ) = ApiResult.Success(SuggestionsResponse()).also { lastRoomToken = roomToken; lastAuthScope = scope }
        override suspend fun promoteSuggestion(
            roomId: String,
            roomToken: String,
            request: PromoteSuggestionRequest,
            scope: AuthScopeSnapshot,
        ): ApiResult<RoomResponse> {
            promoteCalls++
            lastRoomToken = roomToken
            lastAuthScope = scope
            return createResponse
        }
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
        val readySessions = mutableListOf<String>()
        val bufferingSessions = mutableListOf<String>()
        var transportResultGate: CompletableDeferred<Boolean>? = null
        var connectBehavior: ((Int) -> Flow<RoomRealtimeEvent>)? = null
        /**
         * When non-null, each connect attempt emits this one event and then throws.
         * Models a flapping server: healthy traffic is observed but the connection
         * always drops — the classic scenario that could bypass the failure cap if
         * failures were reset per-event rather than per-clean-completion.
         */
        var flappingEvent: RoomRealtimeEvent? = null
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
        val connectedRoomIds = mutableListOf<String>()
        val connectedRoomTokens = mutableListOf<String>()
        val connectedAuthScopes = mutableListOf<AuthScopeSnapshot?>()
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
        override suspend fun stateReport(sessionId: String, positionSeconds: Double, isPaused: Boolean) = true
        override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean {
            readySessions += sessionId
            return true
        }
        override suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean {
            bufferingSessions += sessionId
            return true
        }
        override suspend fun ping(clientSentAt: String) = true
    }

    private val scopeA = AuthScopeSnapshot(
        serverId = "server-a",
        profileId = "profile-a",
        serverUrl = "https://a.example",
        profileToken = "profile-token-a",
        identityGeneration = 1L,
    )

    private fun repo(
        api: FakeApi = FakeApi(),
        realtime: FakeRealtime = FakeRealtime(),
        authScopeProvider: suspend () -> AuthScopeSnapshot? = { scopeA },
    ) = WatchTogetherRepository(
        api = api,
        realtimeFactory = { realtime },
        authScopeProvider = authScopeProvider,
    )

    // ---- create stores room token -------------------------------------------

    @Test
    fun `create stores room token used by subsequent room-scoped calls`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(CreateRoomRequest(selectionMode = "vote"))
        r.setSelection(SetSelectionRequest(contentId = "tt-9"))
        assertEquals("jwt-room", api.lastRoomToken)
        assertEquals("tt-9", api.lastSelection?.contentId)
    }

    @Test
    fun `empty vote room owner keeps existing suggestion vote override and close authority`() = runTest {
        val api = FakeApi(
            createResponse = ApiResult.Success(
                RoomResponse(
                    room = RoomSnapshot(
                        roomId = "room-1",
                        selectionMode = RoomSelectionMode.Vote,
                        selfRole = MemberRole.Host,
                        selfCanManageRoom = true,
                    ),
                    roomAccessToken = "room-token",
                ),
            ),
        )
        val repository = WatchTogetherRepository(
            api = api,
            authScopeProvider = { scopeA },
        )

        repository.createRoom(CreateRoomRequest(selectionMode = RoomSelectionMode.Vote.wire))
        repository.addSuggestion(
            AddSuggestionRequest(
                contentId = "movie-1",
                contentType = "movie",
                title = "Movie One",
            ),
        )
        repository.vote("suggestion-1")
        repository.promoteSuggestion(PromoteSuggestionRequest("suggestion-1"))
        repository.closeRoom()

        assertEquals(1, api.createCalls)
        assertEquals(1, api.addSuggestionCalls)
        assertEquals(1, api.voteCalls)
        assertEquals(1, api.promoteCalls)
        assertEquals(1, api.closeCalls)
        assertEquals("room-token", api.lastRoomToken)
    }

    @Test
    fun `join hydrates suggestions that predate the websocket connection`() = runTest {
        val api = FakeApi().apply {
            listSuggestionsResponse = ApiResult.Success(
                SuggestionsResponse(suggestions = listOf(suggestion("existing"))),
            )
        }
        val repository = repo(api = api)

        val result = repository.joinRoom(JoinRoomRequest(code = "ABCD1234"))

        assertIs<ApiResult.Success<RoomResponse>>(result)
        assertEquals(1, api.listSuggestionsCalls)
        assertEquals(listOf("existing"), repository.suggestions.value.map { it.id })
    }

    @Test
    fun `join returns obsolete when its hydration lease is replaced by another room`() = runTest {
        val hydration = CompletableDeferred<ApiResult<SuggestionsResponse>>()
        val api = FakeApi().apply { listSuggestionsResult = hydration }
        val repository = repo(api = api)
        val joiningA = async { repository.joinRoom(JoinRoomRequest(code = "ABCD1234")) }
        runCurrent()

        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
        )
        assertIs<ApiResult.Success<RoomResponse>>(repository.createRoom(CreateRoomRequest()))
        hydration.complete(ApiResult.Success(SuggestionsResponse(suggestions = listOf(suggestion("stale")))))

        val staleResult = assertIs<ApiResult.Error>(joiningA.await())
        assertEquals("obsolete_room_request", staleResult.error)
        assertEquals("room-2", repository.roomSnapshot.value?.roomId)
        assertTrue(repository.suggestions.value.isEmpty())
    }

    @Test
    fun `join returns obsolete when a newer room request starts during hydration`() = runTest {
        val hydration = CompletableDeferred<ApiResult<SuggestionsResponse>>()
        val newerCreate = CompletableDeferred<ApiResult<RoomResponse>>()
        val api = FakeApi().apply {
            listSuggestionsResult = hydration
            createResult = newerCreate
        }
        val repository = repo(api = api)
        val joiningA = async { repository.joinRoom(JoinRoomRequest(code = "ABCD1234")) }
        runCurrent()
        val creatingB = async { repository.createRoom(CreateRoomRequest()) }
        runCurrent()

        hydration.complete(ApiResult.Success(SuggestionsResponse(suggestions = listOf(suggestion("stale")))))

        val staleResult = assertIs<ApiResult.Error>(joiningA.await())
        assertEquals("obsolete_room_request", staleResult.error)
        assertTrue(repository.suggestions.value.isEmpty())
        newerCreate.complete(
            ApiResult.Success(
                RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
            ),
        )
        assertIs<ApiResult.Success<RoomResponse>>(creatingB.await())
    }

    @Test
    fun `transport lease cannot send into a replacement room`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(CreateRoomRequest())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorizationA = requireNotNull(repository.currentTransportAuthorization())

        api.createResponse = ApiResult.Success(
            RoomResponse(
                RoomSnapshot(
                    roomId = "room-2",
                    code = "EFGH5678",
                    phase = org.siloserver.silo.model.watchtogether.RoomPhase.Playing,
                    selfCanControlTransport = true,
                ),
                "jwt-room-2",
            ),
        )
        repository.createRoom(CreateRoomRequest())

        assertFalse(
            repository.transportRequestForAuthorization(
                authorization = authorizationA,
                intent = RoomTransportIntent.PlayPause,
                action = "pause",
                positionSeconds = 42.0,
                isPaused = true,
            ),
        )
        assertTrue(realtime.transportActions.isEmpty())
        connection.cancel()
    }

    @Test
    fun `transport lease revalidates authority after policy changes in the same room`() = runTest {
        val api = FakeApi().apply {
            createResponse = ApiResult.Success(
                RoomResponse(
                    RoomSnapshot(
                        roomId = "room-1",
                        code = "ABCD1234",
                        phase = org.siloserver.silo.model.watchtogether.RoomPhase.Playing,
                        selfCanControlTransport = true,
                    ),
                    "jwt-room",
                ),
            )
        }
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(CreateRoomRequest())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorizationBeforePolicyChange = requireNotNull(repository.currentTransportAuthorization())
        realtime.events.emit(
            RoomRealtimeEvent.SnapshotEvent(
                api.createResponse.let { (it as ApiResult.Success).data.room.copy(selfCanControlTransport = false) },
            ),
        )
        runCurrent()

        assertFalse(
            repository.transportRequestForAuthorization(
                authorization = authorizationBeforePolicyChange,
                intent = RoomTransportIntent.PlayPause,
                action = "pause",
                positionSeconds = 42.0,
                isPaused = true,
            ),
        )
        assertTrue(realtime.transportActions.isEmpty())
        connection.cancel()
    }

    @Test
    fun `blocked physical send does not hold room replacement or reset mutex`() = runTest {
        val api = FakeApi().apply {
            createResponse = ApiResult.Success(
                RoomResponse(
                    RoomSnapshot(
                        roomId = "room-1",
                        code = "ABCD1234",
                        phase = org.siloserver.silo.model.watchtogether.RoomPhase.Playing,
                        selfCanControlTransport = true,
                    ),
                    "jwt-room",
                ),
            )
        }
        val realtime = FakeRealtime().apply {
            transportResultGate = CompletableDeferred()
        }
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(CreateRoomRequest())
        val connection = launch { repository.connect("room-1") }
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        val authorization = requireNotNull(repository.currentTransportAuthorization())

        val blockedSend = async {
            repository.transportRequestForAuthorization(
                authorization = authorization,
                intent = RoomTransportIntent.PlayPause,
                action = "pause",
                positionSeconds = 42.0,
                isPaused = true,
            )
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
    fun `room binding retains the auth scope captured when it was created`() = runTest {
        val api = FakeApi()
        var currentScope = scopeA
        val r = repo(api = api, authScopeProvider = { currentScope })
        r.createRoom(CreateRoomRequest())
        currentScope = scopeA.copy(
            serverId = "server-b",
            serverUrl = "https://b.example",
            identityGeneration = 2L,
        )

        r.setSelection(SetSelectionRequest(contentId = "tt-9"))

        assertEquals(scopeA, api.lastAuthScope)
    }

    @Test
    fun `realtime reconnect uses the auth scope captured by the room binding`() = runTest {
        val realtime = FakeRealtime()
        var currentScope = scopeA
        val r = repo(realtime = realtime, authScopeProvider = { currentScope })
        r.createRoom(CreateRoomRequest())
        currentScope = scopeA.copy(
            serverId = "server-b",
            serverUrl = "https://b.example",
            identityGeneration = 2L,
        )

        val connection = launch { r.connect("room-1") }
        runCurrent()

        assertEquals(1, realtime.connectedAuthScopes.size)
        assertEquals(scopeA, realtime.connectedAuthScopes.single())
        connection.cancel()
    }

    @Test
    fun `every reconnect attempt retains the room auth scope`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) flow { throw IllegalStateException("drop") } else events.asSharedFlow()
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val connection = launch { r.connect("room-1") }
        runCurrent()
        advanceTimeBy(WatchTogetherRepository.BACKOFF_MS.first())
        runCurrent()

        assertEquals(2, realtime.connectedAuthScopes.size)
        assertTrue(realtime.connectedAuthScopes.all { it == scopeA })
        connection.cancel()
    }

    @Test
    fun `create response cannot mint a room after the auth identity changes`() = runTest {
        val api = FakeApi()
        val pending = CompletableDeferred<ApiResult<RoomResponse>>()
        api.createResult = pending
        var currentScope = scopeA
        val r = repo(api = api, authScopeProvider = { currentScope })
        val create = launch { r.createRoom(CreateRoomRequest()) }
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
        val create = launch {
            createResult = repository.createRoom(CreateRoomRequest())
        }
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
        r.createRoom(CreateRoomRequest())
        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), ""),
        )

        val result = r.createRoom(CreateRoomRequest())

        assertIs<ApiResult.Error>(result)
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
        r.setSelection(SetSelectionRequest(contentId = "still-a"))
        assertEquals("room-1", api.lastRoomId)
        assertEquals("jwt-room", api.lastRoomToken)
    }

    @Test
    fun `mismatched room response fails closed without replacing the active room`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(CreateRoomRequest())
        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "", code = "EFGH5678"), "jwt-room-2"),
        )

        val result = r.createRoom(CreateRoomRequest())

        assertIs<ApiResult.Error>(result)
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
    }

    @Test
    fun `room scoped response for another room is rejected`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(CreateRoomRequest())
        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
        )

        val result = r.setSelection(SetSelectionRequest(contentId = "wrong-room"))

        assertIs<ApiResult.Error>(result)
        assertEquals("room-1", r.roomSnapshot.value?.roomId)
    }

    @Test
    fun `create fails closed when there is no authenticated scope`() = runTest {
        val r = repo(authScopeProvider = { null })

        val result = r.createRoom(CreateRoomRequest())

        assertIs<ApiResult.Error>(result)
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `connect rejects a room id that does not match the active binding`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())

        val job = launch { r.connect("room-2") }
        runCurrent()

        assertEquals(0, realtime.connectCount)
        job.cancel()
    }

    @Test
    fun `local delivery failure is surfaced as a non terminal room error`() = runTest {
        val repository = repo()
        val error = async { repository.errors.first() }
        runCurrent()

        repository.reportDeliveryFailure("room_transport_unavailable")

        assertEquals("room_transport_unavailable", error.await())
        assertNull(repository.roomClosedReason.value)
    }

    @Test
    fun `controller recreation shares successful delivery state for the same epoch`() = runTest {
        val repository = repo()
        val key = repository.roomDeliveryLatch.keyOrNull(
            WatchTogetherConnectionState(generation = 7, epoch = 2, writable = true),
            "playback-1",
        )!!

        repository.roomDeliveryLatch.recordAttach(key, delivered = true)

        assertTrue(repository.roomDeliveryLatch.isAttached(key))
        assertTrue(!repository.roomDeliveryLatch.needsAttach(key))
    }

    @Test
    fun `two participants independently receive room state commands and terminal close`() = runTest {
        val sharedSnapshot = snapshot(memberCount = 2)
        val response = ApiResult.Success(RoomResponse(sharedSnapshot, "jwt-room"))
        val hostRealtime = FakeRealtime()
        val guestRealtime = FakeRealtime()
        val host = repo(api = FakeApi(response), realtime = hostRealtime)
        val guest = repo(api = FakeApi(response), realtime = guestRealtime)
        host.createRoom(CreateRoomRequest())
        guest.createRoom(CreateRoomRequest())
        val hostCommand = async { host.transportCommands.first() }
        val guestCommand = async { guest.transportCommands.first() }
        val hostConnection = launch { host.connect("room-1") }
        val guestConnection = launch { guest.connect("room-1") }
        runCurrent()
        val command = org.siloserver.silo.model.watchtogether.TransportCommand(
            commandId = "cmd-1",
            sessionId = "playback-1",
            selectionRevision = sharedSnapshot.selectionRevision,
            action = org.siloserver.silo.model.watchtogether.TransportAction.Seek,
            positionSeconds = 42.0,
            executeAt = "2026-07-27T12:00:00Z",
        )

        hostRealtime.events.emit(RoomRealtimeEvent.Opened)
        guestRealtime.events.emit(RoomRealtimeEvent.Opened)
        hostRealtime.events.emit(RoomRealtimeEvent.SnapshotEvent(sharedSnapshot))
        guestRealtime.events.emit(RoomRealtimeEvent.SnapshotEvent(sharedSnapshot))
        hostRealtime.events.emit(RoomRealtimeEvent.TransportCommandEvent(command))
        guestRealtime.events.emit(RoomRealtimeEvent.TransportCommandEvent(command))
        runCurrent()

        assertEquals(sharedSnapshot, host.roomSnapshot.value)
        assertEquals(sharedSnapshot, guest.roomSnapshot.value)
        val hostScheduled = hostCommand.await()
        val guestScheduled = guestCommand.await()
        assertEquals(command, hostScheduled.command)
        assertEquals(command, guestScheduled.command)
        assertEquals(host.connectionState.value, hostScheduled.connection)
        assertEquals(guest.connectionState.value, guestScheduled.connection)
        assertTrue(host.attachSession("host-playback"))
        assertTrue(guest.attachSession("guest-playback"))
        assertTrue(host.transportRequest("pause", 42.0, true))
        assertTrue(guest.transportRequest("play", 42.0, false))
        assertTrue(host.buffering("host-playback", 42.0, true))
        assertTrue(guest.ready("guest-playback", 42.0, false))
        assertEquals(listOf("pause"), hostRealtime.transportActions)
        assertEquals(listOf("play"), guestRealtime.transportActions)

        hostRealtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        guestRealtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        hostConnection.join()
        guestConnection.join()

        assertEquals("host_left", host.roomClosedReason.value)
        assertEquals("host_left", guest.roomClosedReason.value)
        assertEquals(1, hostRealtime.connectCount)
        assertEquals(1, guestRealtime.connectCount)
    }

    @Test
    fun `late room A REST completion cannot overwrite replacement room B`() = runTest {
        val api = FakeApi()
        val r = repo(api = api)
        r.createRoom(CreateRoomRequest())
        val pending = CompletableDeferred<ApiResult<RoomResponse>>()
        api.selectionResult = pending
        var staleResult: ApiResult<RoomResponse>? = null
        val stale = launch {
            staleResult = r.setSelection(SetSelectionRequest(contentId = "late-a"))
        }
        runCurrent()

        api.selectionResult = null
        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
        )
        r.createRoom(CreateRoomRequest())
        pending.complete(
            ApiResult.Success(
                RoomResponse(RoomSnapshot(roomId = "room-1", selectionRevision = 99), "jwt-room"),
            ),
        )
        stale.join()

        assertEquals("room-2", r.roomSnapshot.value?.roomId)
        assertIs<ApiResult.Error>(staleResult)
    }

    @Test
    fun `late room A socket event cannot overwrite replacement room B`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val oldConnection = launch { r.connect("room-1") }
        runCurrent()

        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
        )
        r.createRoom(CreateRoomRequest())
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "room-1", revision = 99)))
        advanceUntilIdle()

        assertEquals("room-2", r.roomSnapshot.value?.roomId)
        assertTrue(oldConnection.isCompleted)
        assertTrue(!r.attachSession("session-b"))
        assertEquals(0, realtime.attachCount)
    }

    // ---- snapshot fold --------------------------------------------------------

    @Test
    fun `snapshot event publishes room snapshot`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(revision = 5, memberCount = 3)))
        advanceUntilIdle()
        assertEquals(5L, r.roomSnapshot.value?.selectionRevision)
        assertEquals(3, r.roomSnapshot.value?.memberCount)
        job.cancel()
    }

    @Test
    fun `snapshot for another room cannot replace the active room`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "room-2", revision = 9)))
        runCurrent()

        assertEquals("room-1", r.roomSnapshot.value?.roomId)
        job.cancel()
    }

    // ---- suggestions fold + voted_by_me re-merge ------------------------------

    @Test
    fun `opened refreshes suggestions after reconnect without refreshing after room closed`() = runTest {
        val api = FakeApi().apply {
            listSuggestionsResponse = ApiResult.Success(
                SuggestionsResponse(suggestions = listOf(suggestion("before-drop"))),
            )
        }
        val realtime = FakeRealtime()
        val repository = repo(api = api, realtime = realtime)
        repository.createRoom(CreateRoomRequest())
        val connection = launch { repository.connect("room-1") }
        runCurrent()

        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        assertEquals(1, api.listSuggestionsCalls)
        assertEquals(listOf("before-drop"), repository.suggestions.value.map { it.id })

        realtime.events.emit(RoomRealtimeEvent.TransportTerminated())
        runCurrent()
        api.listSuggestionsResponse = ApiResult.Success(
            SuggestionsResponse(
                suggestions = listOf(
                    suggestion("before-drop"),
                    suggestion("missed-during-drop"),
                ),
            ),
        )
        advanceTimeBy(WatchTogetherRepository.BACKOFF_MS.first())
        runCurrent()
        assertEquals(2, realtime.connectCount)

        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()
        assertEquals(2, api.listSuggestionsCalls)
        assertEquals(
            listOf("before-drop", "missed-during-drop"),
            repository.suggestions.value.map { it.id },
        )

        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals(2, realtime.connectCount)
        assertEquals(2, api.listSuggestionsCalls)
        assertTrue(connection.isCompleted || connection.isCancelled)
    }

    @Test
    fun `rest refresh replaces authoritative local vote set`() = runTest {
        val api = FakeApi().apply {
            listSuggestionsResponse = ApiResult.Success(
                SuggestionsResponse(
                    suggestions = listOf(
                        suggestion("removed-vote", votedByMe = true),
                        suggestion("preserved-vote", votedByMe = true),
                    ),
                ),
            )
        }
        val repository = repo(api = api)
        repository.createRoom(CreateRoomRequest())

        repository.refreshSuggestions()
        api.listSuggestionsResponse = ApiResult.Success(
            SuggestionsResponse(
                suggestions = listOf(
                    suggestion("removed-vote", votedByMe = false),
                    suggestion("preserved-vote", votedByMe = true),
                ),
            ),
        )
        repository.refreshSuggestions()

        val byId = repository.suggestions.value.associateBy { it.id }
        assertFalse(byId.getValue("removed-vote").votedByMe)
        assertTrue(byId.getValue("preserved-vote").votedByMe)
    }

    @Test
    fun `suggestions event re-merges voted_by_me from local vote set`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        // Local user voted for s2 (REST round-trip records it in the local set).
        r.vote("s2")
        advanceUntilIdle()

        // Broadcast forces voted_by_me=false for all — repository must re-merge.
        realtime.events.emit(
            RoomRealtimeEvent.SuggestionsEvent(
                listOf(
                    suggestion("s1", voteCount = 1, votedByMe = false),
                    suggestion("s2", voteCount = 2, votedByMe = false),
                ),
            ),
        )
        advanceUntilIdle()

        val byId = r.suggestions.value.associateBy { it.id }
        assertTrue(byId.getValue("s2").votedByMe) // re-merged from local set
        assertTrue(!byId.getValue("s1").votedByMe)
        job.cancel()
    }

    @Test
    fun `unvote removes from local vote set so re-merge keeps false`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        r.vote("s2"); advanceUntilIdle()
        r.unvote("s2"); advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s2", votedByMe = false))))
        advanceUntilIdle()
        assertTrue(!r.suggestions.value.first().votedByMe)
        job.cancel()
    }

    // ---- reset ---------------------------------------------------------------

    @Test
    fun `reset clears snapshot suggestions and room token`() = runTest {
        val api = FakeApi()
        val realtime = FakeRealtime()
        val r = repo(api = api, realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        realtime.events.emit(RoomRealtimeEvent.SnapshotEvent(snapshot()))
        realtime.events.emit(RoomRealtimeEvent.SuggestionsEvent(listOf(suggestion("s1"))))
        advanceUntilIdle()

        r.reset()
        assertNull(r.roomSnapshot.value)
        assertTrue(r.suggestions.value.isEmpty())
        // After reset the room token is gone; a room-scoped call must not reuse it.
        api.lastRoomToken = null
        val result = r.setSelection(SetSelectionRequest(contentId = "x"))
        assertIs<ApiResult.Error>(result)
        assertNull(api.lastRoomToken) // fail fast: no request is sent with an empty token
        job.cancel()
    }

    // ---- closed/error surface ------------------------------------------------

    @Test
    fun `room_closed populates roomClosedReason and reset clears it`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertNull(r.roomClosedReason.value)

        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals("host_left", r.roomClosedReason.value)

        r.reset()
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `error frame surfaces on errors and does not populate roomClosedReason`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertNull(r.roomClosedReason.value)

        // Collect the transient errors stream; it's a hot SharedFlow so we must
        // be subscribed before the event is emitted.
        val seen = mutableListOf<String>()
        val errorJob = launch { r.errors.collect { seen.add(it) } }
        advanceUntilIdle()

        // A transient server `error` frame (e.g. a rejected transport_request)
        // must NOT eject the user: roomClosedReason stays null and the message
        // surfaces on the errors stream instead.
        realtime.events.emit(RoomRealtimeEvent.Error(code = "rejected", message = "transport rejected"))
        advanceUntilIdle()

        assertNull(r.roomClosedReason.value)
        assertEquals(listOf("transport rejected"), seen)

        errorJob.cancel()
        job.cancel()
    }

    // ---- reconnect stops on room_closed --------------------------------------

    @Test
    fun `reconnect loop stops after room_closed`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)

        // Server-initiated close: repository must NOT reconnect.
        realtime.events.emit(RoomRealtimeEvent.Closed("host_left"))
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)
        assertTrue(job.isCompleted || job.isCancelled)

        // A terminal binding is tombstoned. Reusing the old room id cannot
        // create a fresh connection without a new successful create/join.
        r.connect("room-1")
        assertEquals(1, realtime.connectCount)
    }

    @Test
    fun `reconnect loop stops after room_closed with no reason`() = runTest {
        val realtime = FakeRealtime()
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)

        // Server-initiated close with no reason: repository must NOT reconnect.
        realtime.events.emit(RoomRealtimeEvent.Closed(null))
        advanceUntilIdle()
        assertEquals(1, realtime.connectCount)
        assertEquals("room_closed", r.roomClosedReason.value)
        assertTrue(job.isCompleted || job.isCancelled)
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
        r.createRoom(CreateRoomRequest())
        val old = launch { r.connect("room-1") }
        runCurrent() // first attempt failed and is waiting in its 500 ms backoff

        api.createResponse = ApiResult.Success(
            RoomResponse(RoomSnapshot(roomId = "room-2", code = "EFGH5678"), "jwt-room-2"),
        )
        r.createRoom(CreateRoomRequest())
        advanceUntilIdle()

        assertEquals(1, realtime.connectCount)
        assertTrue(old.isCompleted)
    }

    @Test
    fun `newest duplicate connect exclusively owns folds and writable state`() = runTest {
        val first = FakeRealtime()
        val second = FakeRealtime()
        var factoryCall = 0
        val r = WatchTogetherRepository(
            api = FakeApi(),
            realtimeFactory = { if (factoryCall++ == 0) first else second },
            authScopeProvider = { scopeA },
        )
        r.createRoom(CreateRoomRequest())
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
    fun `throw after opened marks the connection unwritable during backoff`() = runTest {
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt == 1) flow {
                    emit(RoomRealtimeEvent.Opened)
                    throw IllegalStateException("drop after open")
                } else events.asSharedFlow()
            }
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        runCurrent()

        assertTrue(!r.connectionState.value.writable)
        assertTrue(!r.attachSession("during-backoff"))
        assertEquals(0, realtime.attachCount)
        job.cancel()
    }

    @Test
    fun `queued transport command retains the exact epoch that emitted it across reconnect`() = runTest {
        val command = org.siloserver.silo.model.watchtogether.TransportCommand(
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
        r.createRoom(CreateRoomRequest())
        val received = async { r.transportCommands.first() }
        val job = launch { r.connect("room-1") }
        runCurrent()
        val scheduled = received.await()

        assertEquals(1L, scheduled.connection.epoch)
        assertTrue(!r.connectionState.value.writable)
        advanceTimeBy(500)
        runCurrent()
        realtime.events.emit(RoomRealtimeEvent.Opened)
        runCurrent()

        assertEquals(2L, r.connectionState.value.epoch)
        assertTrue(scheduled.connection != r.connectionState.value)
        job.cancel()
    }

    @Test
    fun `attach echo retains its observed epoch until the reconnect receives a fresh snapshot`() = runTest {
        val attachedSnapshot = snapshot().copy(attachedSessionId = "playback-1")
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
        repository.createRoom(CreateRoomRequest())
        val job = launch { repository.connect("room-1") }
        runCurrent()

        assertEquals(1L, repository.roomDeliveryEcho.value?.connectionEpoch)
        advanceTimeBy(500)
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
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        runCurrent()

        assertTrue(!r.attachSession("session-1"))
        assertEquals(0, realtime.attachCount)
        job.cancel()
    }

    // ---- reconnect gives up after max consecutive failures ----------------------

    @Test
    fun `reconnect loop gives up after the max consecutive failures`() = runTest {
        val realtime = FakeRealtime().apply { failConnect = true }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()
        // The loop must have stopped on its own (job completed, not still running).
        assertTrue(job.isCompleted || job.isCancelled)
        // And the closed reason must signal connection_lost.
        assertEquals("connection_lost", r.roomClosedReason.value)
        // Verify the exact number of attempts so an off-by-one or wrong-constant
        // regression is caught immediately. Expected: MAX_RECONNECT_ATTEMPTS = 6.
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        // Stale room state must not be observable after giving up.
        assertNull(r.roomSnapshot.value)
    }

    @Test
    fun `physical transport termination reconnects and counts toward the cap`() = runTest {
        val realtime = FakeRealtime().apply { terminateImmediately = true }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())

        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        assertEquals("connection_lost", r.roomClosedReason.value)
        assertTrue(!r.connectionState.value.writable)
        assertEquals(
            WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS.toLong(),
            r.connectionState.value.epoch,
        )
    }

    @Test
    fun `snapshot plus thirty stable seconds resets the reconnect failure window`() = runTest {
        var nowMs = 0L
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                when {
                    attempt <= 5 -> flow { throw IllegalStateException("early failure") }
                    attempt == 6 -> flow {
                        emit(RoomRealtimeEvent.Opened)
                        nowMs = WatchTogetherRepository.STABLE_CONNECTION_MS
                        emit(RoomRealtimeEvent.SnapshotEvent(snapshot()))
                        emit(RoomRealtimeEvent.TransportTerminated())
                    }
                    attempt <= 10 -> flow { throw IllegalStateException("later failure") }
                    else -> events.asSharedFlow()
                }
            }
        }
        val r = WatchTogetherRepository(
            api = FakeApi(),
            realtimeFactory = { realtime },
            monotonicNowMs = { nowMs },
            authScopeProvider = { scopeA },
        )
        r.createRoom(CreateRoomRequest())

        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertEquals(11, realtime.connectCount)
        assertTrue(job.isActive)
        assertNull(r.roomClosedReason.value)
        job.cancel()
    }

    @Test
    fun `wrong room snapshot cannot reset the reconnect failure window`() = runTest {
        var nowMs = 0L
        val realtime = FakeRealtime().apply {
            connectBehavior = { attempt ->
                if (attempt < WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS) {
                    flow { throw IllegalStateException("early failure") }
                } else {
                    flow {
                        emit(RoomRealtimeEvent.Opened)
                        nowMs = WatchTogetherRepository.STABLE_CONNECTION_MS
                        emit(RoomRealtimeEvent.SnapshotEvent(snapshot(roomId = "wrong-room")))
                        emit(RoomRealtimeEvent.TransportTerminated())
                    }
                }
            }
        }
        val r = WatchTogetherRepository(
            api = FakeApi(),
            realtimeFactory = { realtime },
            monotonicNowMs = { nowMs },
            authScopeProvider = { scopeA },
        )
        r.createRoom(CreateRoomRequest())

        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        assertEquals("connection_lost", r.roomClosedReason.value)
    }

    // ---- flapping server hits the cap (regression for Issue 1) -----------------

    @Test
    fun `flapping server that emits then drops still hits the reconnect cap`() = runTest {
        // Each connect attempt emits one healthy SnapshotEvent, then throws.
        // Under the buggy code (failures reset per healthy event), this would loop
        // forever because failures never accumulates to MAX_RECONNECT_ATTEMPTS.
        // Under the correct code (failures reset only on clean completion), each
        // throwing attempt still increments failures and the loop terminates.
        val realtime = FakeRealtime().apply {
            flappingEvent = RoomRealtimeEvent.SnapshotEvent(
                RoomSnapshot(roomId = "room-1", code = "ABCD1234"),
            )
        }
        val r = repo(realtime = realtime)
        r.createRoom(CreateRoomRequest())
        val job = launch { r.connect("room-1") }
        advanceUntilIdle()

        assertTrue(job.isCompleted || job.isCancelled)
        assertEquals("connection_lost", r.roomClosedReason.value)
        // Must have stopped after exactly MAX_RECONNECT_ATTEMPTS attempts.
        assertEquals(WatchTogetherRepository.MAX_RECONNECT_ATTEMPTS, realtime.connectCount)
        // Stale room state must not be observable after the cap is hit.
        assertNull(r.roomSnapshot.value)
    }
}
