package org.siloserver.silo.repository

import org.siloserver.silo.RoomSyncEngine
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import org.siloserver.silo.watchtogether.RoomDeliveryEcho
import org.siloserver.silo.watchtogether.RoomDeliveryLatch
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import org.siloserver.silo.watchtogether.RoomSessionRepository
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.roomTransportAuthorized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * A transport command paired with its `execute_at` already parsed to a
 * server-epoch millisecond value, so the player binding never has to touch the
 * RFC3339Nano wire string. [executeAtMs] is null when the wire timestamp is
 * malformed (the binding should then apply immediately / fall back).
 */
data class ScheduledTransportCommand(
    val command: TransportCommand,
    val executeAtMs: Long?,
    val connection: WatchTogetherConnectionState,
)

/**
 * A pong frame with its three server-clock RFC3339Nano timestamps parsed to
 * epoch millis. The fourth NTP sample value, `clientReceivedMs`, is NOT here:
 * it must be stamped by the player binding at the instant it receives the pong
 * (the shared module has no wall clock — `Date.now()`/`System.currentTimeMillis`
 * are platform APIs), and supplied when the binding calls
 * `RoomSyncEngine.recordPongSample(clientSentMs, serverReceivedMs, serverSentMs, clientReceivedMs)`.
 * Any field is null when its wire timestamp was malformed.
 */
data class PongSample(
    val clientSentMs: Long?,
    val serverReceivedMs: Long?,
    val serverSentMs: Long?,
)

data class WatchTogetherConnectionState(
    val generation: Long = 0L,
    val epoch: Long = 0L,
    val writable: Boolean = false,
)

/** Immutable, atomically-published authority for one room + physical socket. */
class RoomTransportAuthorization internal constructor(
    val roomId: String,
    val generation: Long,
    val connectionOwner: Long?,
    val realtimeConnectionId: Long?,
    val snapshot: RoomSnapshot,
)

/**
 * Singleton owner of one Watch Together room's state and websocket lifecycle.
 * The per-room WS IS the feature (no REST fallback); the REST calls are for
 * create/join + host management + suggestion mutations.
 *
 * Holds the **room JWT** internally after create/join so every room-scoped op
 * passes it transparently. Folds snapshot/suggestions WS events into
 * [roomSnapshot]/[suggestions] StateFlows, re-merging `voted_by_me` (forced
 * false in broadcasts) from a locally-tracked vote set. Exposes the
 * sync-relevant client→server send passthroughs and a [transportCommands] flow
 * the player binding feeds to its [RoomSyncEngine] (the engine needs the
 * player's local position/playing/clock, which live in the binding, not here).
 *
 * [realtimeFactory] is injected so tests supply a fake event flow.
 */
class WatchTogetherRepository(
    private val api: WatchTogetherApi,
    private val realtimeFactory: () -> WatchTogetherRealtimeClient? = { null },
    private val monotonicNowMs: () -> Long = { MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds },
    private val authScopeProvider: suspend () -> AuthScopeSnapshot? = { null },
) : RoomSessionRepository, WatchTogetherEntryGateway {
    /** Successful delivery state follows the process connection, not a UI controller. */
    val roomDeliveryLatch = RoomDeliveryLatch()

    private data class RoomBinding(
        val roomId: String,
        val roomToken: String,
        val authScope: AuthScopeSnapshot,
        val generation: Long,
    )

    private val stateMutex = Mutex()
    private var nextGeneration = 0L
    private var latestRoomRequest = 0L
    private var binding: RoomBinding? = null
    private var terminalGeneration: Long? = null
    private var realtimeGeneration: Long? = null
    private var realtimeConnectionId: Long? = null
    private var nextConnectionOwner = 0L
    private var activeConnectionOwner: Long? = null
    private val _roomSnapshot = MutableStateFlow<RoomSnapshot?>(null)
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    private val _roomDeliveryEcho = MutableStateFlow<RoomDeliveryEcho?>(null)

    override val roomSnapshot: StateFlow<RoomSnapshot?> = _roomSnapshot.asStateFlow()
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()
    val roomDeliveryEcho: StateFlow<RoomDeliveryEcho?> = _roomDeliveryEcho.asStateFlow()
    private val _connectionState = MutableStateFlow(WatchTogetherConnectionState())
    val connectionState: StateFlow<WatchTogetherConnectionState> = _connectionState.asStateFlow()
    @kotlin.concurrent.Volatile
    private var transportAuthorization: RoomTransportAuthorization? = null

    /** One atomic read; never pair independently-read snapshot/generation values. */
    fun currentTransportAuthorization(): RoomTransportAuthorization? = transportAuthorization

    /**
     * Transport commands surfaced for the player binding to feed to its
     * [RoomSyncEngine]. Buffered + drop-oldest so emission never suspends the
     * collect loop. replay=0 — a late subscriber should not re-apply a stale
     * command.
     */
    private val _transportCommands = MutableSharedFlow<ScheduledTransportCommand>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val transportCommands: SharedFlow<ScheduledTransportCommand> = _transportCommands.asSharedFlow()

    /**
     * Pong samples for the player binding's engine clock-sync, with the three
     * server-clock timestamps parsed to epoch millis. The binding stamps
     * `clientReceivedMs` itself (see [PongSample]).
     */
    private val _pongs = MutableSharedFlow<PongSample>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pongs: SharedFlow<PongSample> = _pongs.asSharedFlow()

    /**
     * Why the room ended — TERMINAL only. Set exclusively on the server
     * `room_closed` / [RoomRealtimeEvent.Closed] reason path (host left / explicit
     * close); the player observes this and exits. Cleared on [reset] and at the
     * start of a fresh [connect]. Transient server `error` frames do NOT populate
     * this (they would eject the user) — see [errors].
     */
    private val _roomClosedReason = MutableStateFlow<String?>(null)
    override val roomClosedReason: StateFlow<String?> = _roomClosedReason.asStateFlow()

    /**
     * Transient, non-terminal server `error` frames (e.g. a rejected
     * transport_request). The UI may surface these as a snackbar/toast WITHOUT
     * exiting the room. Buffered + drop-oldest so emission never suspends the
     * fold; replay=0 so a late subscriber doesn't re-show a stale error.
     */
    private val _errors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Surface a local delivery failure through the same non-terminal UI path. */
    fun reportDeliveryFailure(message: String) {
        if (message.isNotBlank()) _errors.tryEmit(message)
    }

    // Locally-tracked vote set: ids the local user has voted for. Used to
    // re-merge voted_by_me into broadcast suggestion lists (which force false).
    private val votedIds = mutableSetOf<String>()

    @kotlin.concurrent.Volatile
    private var realtime: WatchTogetherRealtimeClient? = null

    // ---- REST: create / join (store the room token) ---------------------------

    override suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse> {
        val scope = authScopeProvider() ?: return missingAuthScope()
        val requestGeneration = beginRoomRequest()
        val r = api.createRoom(request, scope)
        if (authScopeProvider() != scope) return obsoleteRoomRequest()
        return if (r is ApiResult.Success) installRoomResponse(r.data, scope, requestGeneration) else r
    }

    override suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse> {
        val scope = authScopeProvider() ?: return missingAuthScope()
        val requestGeneration = beginRoomRequest()
        val r = api.joinRoom(request, scope)
        if (authScopeProvider() != scope) return obsoleteRoomRequest()
        if (r !is ApiResult.Success) return r
        val installed = installRoomResponse(r.data, scope, requestGeneration)
        if (installed !is ApiResult.Success) return installed

        // The realtime handshake's initial event is a room snapshot; it does
        // not replay suggestions that existed before this member connected.
        // Hydrate them while the join lease is current so a re-entered lobby
        // does not render an empty vote list until somebody mutates it.
        val lease = stateMutex.withLock {
            binding?.takeIf { current ->
                latestRoomRequest == requestGeneration &&
                    current.roomId == r.data.room.roomId &&
                    isCurrentLocked(current)
            }
        } ?: return obsoleteRoomRequest()
        publishSuggestionsResponse(
            lease,
            api.listSuggestions(lease.roomId, lease.roomToken, lease.authScope),
            expectedRoomRequest = requestGeneration,
        )
        val stillCurrent = stateMutex.withLock {
            latestRoomRequest == requestGeneration && isCurrentLocked(lease)
        }
        if (!stillCurrent) return obsoleteRoomRequest()
        return installed
    }

    private suspend fun beginRoomRequest(): Long = stateMutex.withLock { ++latestRoomRequest }

    private suspend fun installRoomResponse(
        data: RoomResponse,
        scope: AuthScopeSnapshot,
        requestGeneration: Long,
    ): ApiResult<RoomResponse> {
        if (data.room.roomId.isBlank() || data.roomAccessToken.isBlank()) {
            return invalidRoomResponse()
        }
        stateMutex.withLock {
            if (requestGeneration != latestRoomRequest) return obsoleteRoomRequest()
            val installed = RoomBinding(
                roomId = data.room.roomId,
                roomToken = data.roomAccessToken,
                authScope = scope,
                generation = ++nextGeneration,
            )
            binding = installed
            terminalGeneration = null
            votedIds.clear()
            _suggestions.value = emptyList()
            _roomClosedReason.value = null
            _roomSnapshot.value = data.room
            _roomDeliveryEcho.value = null
            _connectionState.value = WatchTogetherConnectionState(generation = installed.generation)
            realtimeConnectionId = null
            refreshTransportAuthorizationLocked()
        }
        return ApiResult.Success(data)
    }

    // ---- REST: host management ------------------------------------------------

    override suspend fun setSelection(request: SetSelectionRequest): ApiResult<RoomResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.setSelection(lease.roomId, lease.roomToken, request, lease.authScope)
        return publishRoomResponse(lease, r)
    }

    suspend fun updatePolicy(request: UpdatePolicyRequest): ApiResult<RoomResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.updatePolicy(lease.roomId, lease.roomToken, request, lease.authScope)
        return publishRoomResponse(lease, r)
    }

    override suspend fun closeRoom(): ApiResult<Unit> {
        val lease = activeBinding() ?: return missingRoom()
        val result = api.closeRoom(lease.roomId, lease.roomToken, lease.authScope)
        return if (isCurrent(lease)) result else obsoleteRoomRequest()
    }

    // ---- REST: suggestions ----------------------------------------------------

    suspend fun refreshSuggestions(): ApiResult<SuggestionsResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.listSuggestions(lease.roomId, lease.roomToken, lease.authScope)
        return publishSuggestionsResponse(lease, r)
    }

    suspend fun addSuggestion(request: AddSuggestionRequest): ApiResult<SuggestionsResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.addSuggestion(lease.roomId, lease.roomToken, request, lease.authScope)
        return publishSuggestionsResponse(lease, r)
    }

    suspend fun deleteSuggestion(suggestionId: String): ApiResult<SuggestionsResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.deleteSuggestion(lease.roomId, lease.roomToken, suggestionId, lease.authScope)
        return publishSuggestionsResponse(lease, r)
    }

    suspend fun vote(suggestionId: String): ApiResult<SuggestionsResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.vote(lease.roomId, lease.roomToken, suggestionId, lease.authScope)
        if (r !is ApiResult.Success) return r
        val published = stateMutex.withLock {
            if (!isCurrentLocked(lease)) return@withLock false
            votedIds.add(suggestionId)
            applySuggestions(r.data.suggestions, fromBroadcast = true)
            true
        }
        return if (published) r else obsoleteRoomRequest()
    }

    suspend fun unvote(suggestionId: String): ApiResult<SuggestionsResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.unvote(lease.roomId, lease.roomToken, suggestionId, lease.authScope)
        if (r !is ApiResult.Success) return r
        val published = stateMutex.withLock {
            if (!isCurrentLocked(lease)) return@withLock false
            votedIds.remove(suggestionId)
            applySuggestions(r.data.suggestions, fromBroadcast = true)
            true
        }
        return if (published) r else obsoleteRoomRequest()
    }

    suspend fun promoteSuggestion(request: PromoteSuggestionRequest): ApiResult<RoomResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val r = api.promoteSuggestion(lease.roomId, lease.roomToken, request, lease.authScope)
        return publishRoomResponse(lease, r)
    }

    private suspend fun activeBinding(): RoomBinding? = stateMutex.withLock {
        binding?.takeIf { terminalGeneration != it.generation }
    }

    private fun isCurrentLocked(lease: RoomBinding): Boolean =
        binding == lease && terminalGeneration != lease.generation

    private fun refreshTransportAuthorizationLocked() {
        val current = binding
        val snapshot = _roomSnapshot.value
        val owner = activeConnectionOwner
        val connectionId = realtimeConnectionId
        transportAuthorization = if (
            current != null &&
            snapshot != null &&
            isCurrentLocked(current) &&
            snapshot.roomId == current.roomId
        ) {
            RoomTransportAuthorization(
                roomId = current.roomId,
                generation = current.generation,
                connectionOwner = owner,
                realtimeConnectionId = connectionId,
                snapshot = snapshot,
            )
        } else {
            null
        }
    }

    private suspend fun publishRoomResponse(
        lease: RoomBinding,
        result: ApiResult<RoomResponse>,
    ): ApiResult<RoomResponse> {
        if (result !is ApiResult.Success) return result
        if (result.data.room.roomId != lease.roomId) return invalidRoomResponse()
        val published = stateMutex.withLock {
            if (!isCurrentLocked(lease)) return@withLock false
            _roomSnapshot.value = result.data.room
            _roomDeliveryEcho.value = null
            refreshTransportAuthorizationLocked()
            true
        }
        return if (published) result else obsoleteRoomRequest()
    }

    private suspend fun publishSuggestionsResponse(
        lease: RoomBinding,
        result: ApiResult<SuggestionsResponse>,
        expectedRoomRequest: Long? = null,
        expectedConnectionOwner: Long? = null,
    ): ApiResult<SuggestionsResponse> {
        if (result !is ApiResult.Success) return result
        val published = stateMutex.withLock {
            if (
                !isCurrentLocked(lease) ||
                (expectedRoomRequest != null && latestRoomRequest != expectedRoomRequest) ||
                (expectedConnectionOwner != null && activeConnectionOwner != expectedConnectionOwner)
            ) {
                return@withLock false
            }
            applySuggestions(result.data.suggestions, fromBroadcast = false)
            true
        }
        return if (published) result else obsoleteRoomRequest()
    }

    private fun invalidRoomResponse(): ApiResult.Error =
        ApiResult.Error(502, "invalid_room_response", "The room response was missing or mismatched.")

    private fun missingRoom(): ApiResult.Error =
        ApiResult.Error(409, "no_active_room", "No active Watch Together room.")

    private fun missingAuthScope(): ApiResult.Error =
        ApiResult.Error(401, "missing_auth_scope", "No authenticated Watch Together scope.")

    private fun obsoleteRoomRequest(): ApiResult.Error =
        ApiResult.Error(409, "obsolete_room_request", "The Watch Together identity changed.")

    /**
     * Publish suggestions, re-merging `voted_by_me` from the local [votedIds]
     * set. Authoritative REST lists replace [votedIds]; broadcasts and
     * optimistic vote mutation responses preserve the local set because their
     * per-recipient vote flags are not authoritative.
     */
    private fun applySuggestions(list: List<Suggestion>, fromBroadcast: Boolean) {
        if (!fromBroadcast) {
            votedIds.clear()
            votedIds.addAll(list.filter { it.votedByMe }.map { it.id })
        }
        _suggestions.value = list.map { s ->
            if (s.id in votedIds) s.copy(votedByMe = true) else s
        }
    }

    // ---- WS: client→server send passthroughs ----------------------------------

    private suspend fun currentWritableRealtime(): WatchTogetherRealtimeClient? =
        stateMutex.withLock {
            val current = binding
            realtime.takeIf {
                current != null &&
                    terminalGeneration != current.generation &&
                    realtimeGeneration == current.generation &&
                    activeConnectionOwner != null &&
                    _connectionState.value.writable
            }
        }

    suspend fun attachSession(sessionId: String): Boolean =
        currentWritableRealtime()?.attachSession(sessionId) ?: false

    suspend fun transportRequest(
        action: String,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): Boolean = currentWritableRealtime()?.transportRequest(action, positionSeconds, isPaused) ?: false

    /**
     * Send a user transport intent only while the exact room generation that
     * authorized it is still current. Authority is rechecked under the same
     * lock that guards room replacement, so a delayed UI coroutine cannot send
     * an old room's command through a replacement room or changed policy.
     */
    suspend fun transportRequestForAuthorization(
        authorization: RoomTransportAuthorization,
        intent: RoomTransportIntent,
        action: String,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): Boolean {
        val target = stateMutex.withLock {
            val current = binding ?: return@withLock null
            val snapshot = _roomSnapshot.value ?: return@withLock null
            val currentRealtime = realtime ?: return@withLock null
            val connectionId = authorization.realtimeConnectionId ?: return@withLock null
            val connectionOwner = authorization.connectionOwner ?: return@withLock null
            if (
                transportAuthorization != authorization ||
                !isCurrentLocked(current) ||
                current.generation != authorization.generation ||
                current.roomId != authorization.roomId ||
                snapshot != authorization.snapshot ||
                !roomTransportAuthorized(snapshot, intent) ||
                realtimeGeneration != current.generation ||
                activeConnectionOwner != connectionOwner ||
                realtimeConnectionId != connectionId ||
                !_connectionState.value.writable
            ) {
                return@withLock null
            }
            currentRealtime to connectionId
        } ?: return false
        return target.first.transportRequestOnConnection(
            connectionId = target.second,
            action = action,
            positionSeconds = positionSeconds,
            isPaused = isPaused,
        )
    }

    suspend fun stateReport(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
    ): Boolean = currentWritableRealtime()?.stateReport(sessionId, positionSeconds, isPaused) ?: false

    suspend fun ready(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
    ): Boolean = currentWritableRealtime()?.ready(sessionId, positionSeconds, isPaused) ?: false

    suspend fun buffering(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
    ): Boolean = currentWritableRealtime()?.buffering(sessionId, positionSeconds, isPaused) ?: false

    suspend fun ping(clientSentAt: String): Boolean =
        currentWritableRealtime()?.ping(clientSentAt) ?: false

    // ---- WS lifecycle: connect + reconnect-with-backoff ------------------------

    /**
     * Collect the room socket with capped-backoff reconnect, folding each event
     * into the state flows. Suspends until the caller's scope is cancelled OR a
     * server `room_closed` arrives (which stops reconnecting). Backoff steps are
     * [BACKOFF_MS]; a healthy event resets the index.
     */
    override suspend fun connect(roomId: String) {
        val lease = activeBinding()?.takeIf { it.roomId == roomId } ?: return
        val client = realtimeFactory() ?: return
        val owner = stateMutex.withLock {
            if (!isCurrentLocked(lease)) return
            val newOwner = ++nextConnectionOwner
            activeConnectionOwner = newOwner
            realtime = client
            realtimeGeneration = lease.generation
            realtimeConnectionId = null
            _connectionState.value = _connectionState.value.copy(
                generation = lease.generation,
                writable = false,
            )
            _roomClosedReason.value = null
            refreshTransportAuthorizationLocked()
            newOwner
        }
        var backoffIndex = 0
        var failures = 0
        while (true) {
            if (!isCurrent(lease, owner)) break
            var closedByServer = false
            var openedAtMs: Long? = null
            var sawSnapshot = false
            try {
                client.connect(lease.roomId, lease.roomToken, lease.authScope).collect { event ->
                    if (!isCurrent(lease, owner)) throw ObsoleteBinding
                    if (event is RoomRealtimeEvent.Closed) {
                        // Any server-initiated close (with or without a reason) is terminal.
                        // The event flow (a hot SharedFlow) never completes on its
                        // own, so we stop collecting by throwing a private sentinel.
                        closedByServer = true
                        stateMutex.withLock {
                            if (isCurrentOwnerLocked(lease, owner)) {
                                terminalGeneration = lease.generation
                                _roomClosedReason.value = event.reason ?: "room_closed"
                                _roomSnapshot.value = null
                                _roomDeliveryEcho.value = null
                                refreshTransportAuthorizationLocked()
                            }
                        }
                        throw ServerClosed
                    } else if (event is RoomRealtimeEvent.TransportTerminated) {
                        markNotWritable(lease, owner)
                        throw TransportEnded
                    } else if (event is RoomRealtimeEvent.Opened) {
                        openedAtMs = monotonicNowMs()
                        markOpened(lease, owner, client.currentConnectionId())
                        publishSuggestionsResponse(
                            lease = lease,
                            result = api.listSuggestions(
                                lease.roomId,
                                lease.roomToken,
                                lease.authScope,
                            ),
                            expectedConnectionOwner = owner,
                        )
                    } else if (
                        event is RoomRealtimeEvent.SnapshotEvent &&
                        event.room.roomId == lease.roomId
                    ) {
                        sawSnapshot = true
                    }
                    fold(lease, owner, event)
                }
                markNotWritable(lease, owner)
                if (isStableAttempt(openedAtMs, sawSnapshot)) {
                    failures = 0
                    backoffIndex = 0
                }
                failures++
            } catch (e: CancellationException) {
                clearRealtimeIfCurrent(lease, client, owner)
                throw e
            } catch (_: ObsoleteBinding) {
                break
            } catch (_: ServerClosed) {
                // terminal — handled below via closedByServer
            } catch (_: Throwable) {
                // Any throw (including from a flapping server) counts as a failure,
                // regardless of whether a healthy event arrived in the same attempt.
                markNotWritable(lease, owner)
                if (isStableAttempt(openedAtMs, sawSnapshot)) {
                    failures = 0
                    backoffIndex = 0
                }
                failures++
            }
            if (closedByServer) break
            if (!isCurrent(lease, owner)) break
            if (failures >= MAX_RECONNECT_ATTEMPTS) {
                stateMutex.withLock {
                    if (isCurrentOwnerLocked(lease, owner)) {
                        terminalGeneration = lease.generation
                        _roomClosedReason.value = "connection_lost"
                        _roomSnapshot.value = null
                        _roomDeliveryEcho.value = null
                        refreshTransportAuthorizationLocked()
                    }
                }
                break
            }
            delay(BACKOFF_MS[backoffIndex])
            backoffIndex = (backoffIndex + 1).coerceAtMost(BACKOFF_MS.lastIndex)
        }
        clearRealtimeIfCurrent(lease, client, owner)
    }

    private suspend fun isCurrent(lease: RoomBinding): Boolean =
        stateMutex.withLock { isCurrentLocked(lease) }

    private suspend fun isCurrent(lease: RoomBinding, owner: Long): Boolean =
        stateMutex.withLock { isCurrentOwnerLocked(lease, owner) }

    private fun isCurrentOwnerLocked(lease: RoomBinding, owner: Long): Boolean =
        isCurrentLocked(lease) && activeConnectionOwner == owner

    private suspend fun clearRealtimeIfCurrent(
        lease: RoomBinding,
        client: WatchTogetherRealtimeClient,
        owner: Long,
    ) {
        stateMutex.withLock {
            if (
                realtime === client &&
                realtimeGeneration == lease.generation &&
                activeConnectionOwner == owner
            ) {
                realtime = null
                realtimeGeneration = null
                realtimeConnectionId = null
                activeConnectionOwner = null
                _connectionState.value = _connectionState.value.copy(writable = false)
                refreshTransportAuthorizationLocked()
            }
        }
    }

    private suspend fun markOpened(
        lease: RoomBinding,
        owner: Long,
        connectionId: Long?,
    ) {
        stateMutex.withLock {
            if (isCurrentOwnerLocked(lease, owner)) {
                realtimeConnectionId = connectionId
                val previous = _connectionState.value
                _connectionState.value = WatchTogetherConnectionState(
                    generation = lease.generation,
                    epoch = if (previous.generation == lease.generation) previous.epoch + 1 else 1,
                    writable = true,
                )
                refreshTransportAuthorizationLocked()
            }
        }
    }

    private suspend fun markNotWritable(lease: RoomBinding, owner: Long) {
        stateMutex.withLock {
            if (binding == lease && activeConnectionOwner == owner) {
                _connectionState.value = _connectionState.value.copy(writable = false)
                realtimeConnectionId = null
                refreshTransportAuthorizationLocked()
            }
        }
    }

    private fun isStableAttempt(openedAtMs: Long?, sawSnapshot: Boolean): Boolean =
        sawSnapshot && openedAtMs != null && monotonicNowMs() - openedAtMs >= STABLE_CONNECTION_MS

    /** Sentinel to unwind the [connect] collect loop on a server `room_closed`. */
    private object ServerClosed : Throwable()
    private object TransportEnded : Throwable()
    private object ObsoleteBinding : Throwable()

    /** Pure-ish fold of one realtime event into the state flows + side streams. */
    private suspend fun fold(lease: RoomBinding, owner: Long, event: RoomRealtimeEvent) {
        stateMutex.withLock {
            if (!isCurrentOwnerLocked(lease, owner)) return
            when (event) {
                RoomRealtimeEvent.Opened -> Unit
                is RoomRealtimeEvent.SnapshotEvent ->
                    if (event.room.roomId == lease.roomId) {
                        _roomSnapshot.value = event.room
                        _roomDeliveryEcho.value = event.room.attachedSessionId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { sessionId ->
                                val connection = _connectionState.value
                                RoomDeliveryEcho(
                                    connectionGeneration = connection.generation,
                                    connectionEpoch = connection.epoch,
                                    playbackSessionId = sessionId,
                                )
                            }
                        refreshTransportAuthorizationLocked()
                    }
                is RoomRealtimeEvent.SuggestionsEvent -> applySuggestions(event.suggestions, fromBroadcast = true)
                is RoomRealtimeEvent.TransportCommandEvent -> _transportCommands.tryEmit(
                    ScheduledTransportCommand(
                        command = event.command,
                        executeAtMs = parseRfc3339ToEpochMillis(event.command.executeAt),
                        connection = _connectionState.value,
                    ),
                )
                is RoomRealtimeEvent.Pong -> _pongs.tryEmit(
                    PongSample(
                        clientSentMs = parseRfc3339ToEpochMillis(event.clientSentAt),
                        serverReceivedMs = parseRfc3339ToEpochMillis(event.serverReceivedAt),
                        serverSentMs = parseRfc3339ToEpochMillis(event.serverSentAt),
                    ),
                )
                is RoomRealtimeEvent.Closed -> { /* lifecycle handled in connect() */ }
                is RoomRealtimeEvent.TransportTerminated -> Unit
                is RoomRealtimeEvent.Error ->
                    // Transient, NON-terminal: a server `error` frame (e.g. a rejected
                    // transport_request) must NOT feed roomClosedReason.
                    _errors.tryEmit(event.message.ifBlank { event.code })
            }
        }
    }

    /** Clear all room state on leave. The connect() loop ends via scope cancellation. */
    override suspend fun reset() {
        stateMutex.withLock {
            nextGeneration++
            latestRoomRequest++
            binding = null
            terminalGeneration = null
            _roomSnapshot.value = null
            _roomDeliveryEcho.value = null
            _suggestions.value = emptyList()
            _roomClosedReason.value = null
            votedIds.clear()
            realtime = null
            realtimeGeneration = null
            realtimeConnectionId = null
            activeConnectionOwner = null
            _connectionState.value = WatchTogetherConnectionState(generation = nextGeneration)
            refreshTransportAuthorizationLocked()
        }
    }

    companion object {
        /** Reconnect backoff steps (ms) — spec: not after room_closed. */
        val BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L)

        /**
         * Maximum number of consecutive connection failures (e.g. throws during
         * [connect] collection, factory/handshake errors) before the reconnect
         * loop gives up. Reset to zero on any healthy server event.
         */
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val STABLE_CONNECTION_MS = 30_000L
        private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()
    }
}
