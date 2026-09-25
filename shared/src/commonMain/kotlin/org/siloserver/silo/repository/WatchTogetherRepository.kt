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
import org.siloserver.silo.model.watchtogether.SourceFallbackReason
import org.siloserver.silo.model.watchtogether.SourceFallbackRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.SuggestionReceipt
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.RoomTicketExpiringException
import org.siloserver.silo.network.RoomTicketRefusedException
import org.siloserver.silo.network.WatchTogetherRealtimeClient
import org.siloserver.silo.network.api.WatchTogetherApi
import org.siloserver.silo.util.formatEpochMillisRfc3339
import org.siloserver.silo.util.parseRfc3339ToEpochMillis
import org.siloserver.silo.util.wallClockMillis
import org.siloserver.silo.watchtogether.RoomClockEstimate
import org.siloserver.silo.watchtogether.RoomClockEstimator
import org.siloserver.silo.watchtogether.RoomDeliveryEcho
import org.siloserver.silo.watchtogether.RoomDeliveryLatch
import org.siloserver.silo.watchtogether.RoomPlaybackRoom
import org.siloserver.silo.watchtogether.RoomProof
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import org.siloserver.silo.watchtogether.RoomSessionRepository
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.rankSuggestions
import org.siloserver.silo.watchtogether.roomProofExpiryMs
import org.siloserver.silo.watchtogether.roomTransportAuthorized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * A transport command paired with its `execute_at` already parsed to a
 * server-epoch millisecond value, so the player binding never has to touch the
 * RFC3339Nano wire string. [executeAtMs] is null when the wire timestamp is
 * malformed. [receivedAtMs] is the monotonic receipt time.
 */
data class ScheduledTransportCommand(
    val command: TransportCommand,
    val executeAtMs: Long?,
    val connection: WatchTogetherConnectionState,
    val receivedAtMs: Long = 0L,
)

/**
 * [disconnectedAtMs] is the monotonic time the room socket stopped being
 * writable, so the UI can wait before warning about a routine reconnect.
 */
data class WatchTogetherConnectionState(
    val generation: Long = 0L,
    val epoch: Long = 0L,
    val writable: Boolean = false,
    val disconnectedAtMs: Long? = null,
)

/** Immutable, atomically-published authority for one room + physical socket. */
class RoomTransportAuthorization internal constructor(
    val roomId: String,
    val generation: Long,
    val connectionOwner: Long?,
    val realtimeConnectionId: Long?,
    val snapshot: RoomSnapshot,
)

/** Why this device's Watch Party engagement ended. */
object WatchPartyEndReason {
    /** The host ended the party, or the host-disconnect grace ran out. The server does not say which. */
    const val HostLeft = "host_left"
    const val NotFound = "not_found"
    const val Ended = "ended"
    /** This profile joined the party on another device. The room keeps going. */
    const val Replaced = "connection_replaced"
    const val ConnectionLost = "connection_lost"
    const val Unauthorized = "unauthorized"
}

/** The last engagement that ended on this device, kept so the UI can explain it and offer Rejoin. */
data class WatchPartyEnded(
    val roomId: String,
    val code: String,
    val reason: String,
    val wasHost: Boolean,
)

/** A room or vote mutation in flight. One runs at a time so repeated taps cannot reorder work. */
enum class WatchPartyPendingAction {
    Create, Join, Stage, Start, Stop, End, Mode, Policy, Select, Promote, Suggest, RemoveSuggestion, Vote, Fallback,
}

/** Client timing choices; the web client's values unless evidence says otherwise. */
data class WatchPartyTiming(
    val pingIntervalMs: Long = 15_000L,
    val initialPings: Int = 3,
    val initialPingSpacingMs: Long = 1_000L,
    /** Renew the room proof this long before it expires: a socket lifetime plus a ticket. */
    val proofRenewalMarginMs: Long = 6 * 60_000L,
    val proofUnknownRenewalMs: Long = 60 * 60_000L,
    val proofRetryMs: Long = 30_000L,
    val stableConnectionMs: Long = 30_000L,
    /** Keep reconnecting at least this long after the last healthy socket; covers the host grace. */
    val reconnectBudgetMs: Long = 150_000L,
    val maxReconnectFailures: Int = 6,
    val backoffMs: List<Long> = listOf(500L, 1_000L, 2_000L, 5_000L),
    val expiringTicketRetryMs: Long = 1_500L,
    /** Test seam: background pings and proof renewal off. */
    val backgroundWork: Boolean = true,
)

/**
 * The single owner of this device's Watch Party engagement: membership, room
 * proof, the room socket, the server clock estimate, snapshot ordering,
 * suggestions, and room actions. The per-room socket IS the feature (no REST
 * fallback); REST carries create/join, host management, reads, and suggestion
 * mutations.
 *
 * Identities are kept apart. A membership ([RoomBinding]) is the room, the
 * captured authority, and a local generation; joining the same room again
 * creates a new membership. The room proof renews from every successful room
 * response without changing membership. Socket attempts are fenced by a
 * connection owner and epoch.
 *
 * Snapshot ordering: `generation` orders transport changes, not membership or
 * readiness updates. An HTTP snapshot is accepted only when its generation is
 * newer, or equal with no socket snapshot received since the request started,
 * so a delayed response cannot erase a newer attachment or readiness update.
 * A terminal engagement is never reopened by a late result.
 */
class WatchTogetherRepository(
    private val api: WatchTogetherApi,
    private val realtimeFactory: () -> WatchTogetherRealtimeClient? = { null },
    private val monotonicNowMs: () -> Long = { MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds },
    private val authScopeProvider: suspend () -> AuthScopeSnapshot? = { null },
    private val wallClockMs: () -> Long = ::wallClockMillis,
    private val timing: WatchPartyTiming = WatchPartyTiming(),
    private val random: Random = Random.Default,
) : RoomSessionRepository, WatchTogetherEntryGateway, RoomPlaybackRoom {
    /** Successful delivery state follows the process connection, not a UI controller. */
    val roomDeliveryLatch = RoomDeliveryLatch()

    private data class RoomBinding(
        val roomId: String,
        val authScope: AuthScopeSnapshot,
        val generation: Long,
    ) {
        override fun toString(): String = "RoomBinding(roomId=<redacted>, generation=$generation)"
    }

    private val stateMutex = Mutex()
    private val actionMutex = Mutex()
    private val suggestionReadMutex = Mutex()
    private var nextGeneration = 0L
    private var latestRoomRequest = 0L
    private var binding: RoomBinding? = null
    private var proof: RoomProof? = null
    private var terminalGeneration: Long? = null
    private var realtimeGeneration: Long? = null
    private var realtimeConnectionId: Long? = null
    private var nextConnectionOwner = 0L
    private var activeConnectionOwner: Long? = null
    private var socketSnapshotSeq = 0L
    private var socketSuggestionsSeq = 0L
    private var suggestionReadRequests = 0L
    private var suggestionReadStartedAt = 0L
    private var rawSuggestions: List<Suggestion> = emptyList()
    private val clockEstimator = RoomClockEstimator()

    private val _roomSnapshot = MutableStateFlow<RoomSnapshot?>(null)
    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    private val _personalVotesKnown = MutableStateFlow(false)
    private val _roomDeliveryEcho = MutableStateFlow<RoomDeliveryEcho?>(null)
    private val _connectionState = MutableStateFlow(WatchTogetherConnectionState())
    private val _latestCommand = MutableStateFlow<ScheduledTransportCommand?>(null)
    private val _clock = MutableStateFlow(clockEstimator.estimate)
    private val _roomClosedReason = MutableStateFlow<String?>(null)
    private val _ended = MutableStateFlow<WatchPartyEnded?>(null)
    private val _pendingAction = MutableStateFlow<WatchPartyPendingAction?>(null)

    override val roomSnapshot: StateFlow<RoomSnapshot?> = _roomSnapshot.asStateFlow()

    /** Suggestions in vote order, with this profile's own votes merged in. */
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    /**
     * False until an authenticated suggestion read has told this profile which
     * suggestions it voted for. Socket broadcasts never carry personal votes.
     */
    val personalVotesKnown: StateFlow<Boolean> = _personalVotesKnown.asStateFlow()
    override val roomDeliveryEcho: StateFlow<RoomDeliveryEcho?> = _roomDeliveryEcho.asStateFlow()
    override val connectionState: StateFlow<WatchTogetherConnectionState> = _connectionState.asStateFlow()

    /**
     * The newest accepted transport command. Commands supersede each other, so
     * the binding needs only the latest; a slow collector can never lose it.
     */
    override val latestTransportCommand: StateFlow<ScheduledTransportCommand?> = _latestCommand.asStateFlow()

    /** Server clock estimate, sampled on the room socket from the moment it opens. */
    override val clock: StateFlow<RoomClockEstimate> = _clock.asStateFlow()

    /** The action in flight, if any. */
    val pendingAction: StateFlow<WatchPartyPendingAction?> = _pendingAction.asStateFlow()

    /** The last engagement that ended, with its reason. Cleared by [reset] and by a new membership. */
    val ended: StateFlow<WatchPartyEnded?> = _ended.asStateFlow()

    @kotlin.concurrent.Volatile
    private var transportAuthorization: RoomTransportAuthorization? = null

    /** One atomic read; never pair independently-read snapshot/generation values. */
    override fun currentTransportAuthorization(): RoomTransportAuthorization? = transportAuthorization

    /**
     * Why the engagement ended — terminal only (see [WatchPartyEndReason]).
     * Transient server `error` frames never populate this; they flow on
     * [errors].
     */
    override val roomClosedReason: StateFlow<String?> = _roomClosedReason.asStateFlow()

    /**
     * Transient, non-terminal notices (a rejected transport request, a local
     * delivery failure). Buffered + drop-oldest; a missed notice is harmless.
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

    // Ids this profile has voted for, known only after an authenticated read.
    private val votedIds = mutableSetOf<String>()

    @kotlin.concurrent.Volatile
    private var realtime: WatchTogetherRealtimeClient? = null

    override suspend fun membershipGeneration(): Long? = activeBinding()?.generation

    // ---- REST: create / join ------------------------------------------------

    /**
     * Create a room with the caller-selected identity in [request]. After an
     * uncertain outcome, retry with the same request: the server replays it.
     */
    override suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse> = action(WatchPartyPendingAction.Create) {
        val scope = authScopeProvider() ?: return@action missingAuthScope()
        val requestGeneration = beginRoomRequest()
        val r = api.createRoom(request, scope)
        if (authScopeProvider() != scope) return@action obsoleteRoomRequest()
        if (r is ApiResult.Success) installRoomResponse(r.data, scope, requestGeneration) else r
    }

    override suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse> = action(WatchPartyPendingAction.Join) {
        val scope = authScopeProvider() ?: return@action missingAuthScope()
        val requestGeneration = beginRoomRequest()
        val r = api.joinRoom(request, scope)
        if (authScopeProvider() != scope) return@action obsoleteRoomRequest()
        if (r !is ApiResult.Success) return@action r
        installRoomResponse(r.data, scope, requestGeneration)
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
                authScope = scope,
                generation = ++nextGeneration,
            )
            binding = installed
            proof = RoomProof(data.roomAccessToken, roomProofExpiryMs(data.roomAccessToken))
            terminalGeneration = null
            votedIds.clear()
            rawSuggestions = emptyList()
            _suggestions.value = emptyList()
            _personalVotesKnown.value = false
            _roomClosedReason.value = null
            _ended.value = null
            _roomSnapshot.value = data.room
            _roomDeliveryEcho.value = null
            _latestCommand.value = null
            _connectionState.value = WatchTogetherConnectionState(generation = installed.generation)
            realtimeConnectionId = null
            refreshTransportAuthorizationLocked()
        }
        return ApiResult.Success(data)
    }

    // ---- REST: host management ------------------------------------------------

    override suspend fun stageSelection(request: SetSelectionRequest): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Stage) { lease, _ -> api.stageSelection(lease.roomId, request, lease.authScope) }

    suspend fun startPlayback(): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Start) { lease, _ -> api.startPlayback(lease.roomId, lease.authScope) }

    suspend fun stopPlayback(): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Stop) { lease, _ -> api.stopPlayback(lease.roomId, lease.authScope) }

    suspend fun setSelectionMode(mode: RoomSelectionMode): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Mode) { lease, _ ->
            api.setSelectionMode(lease.roomId, SelectionModeRequest(mode.wire), lease.authScope)
        }

    /** Direct selection: starts playback. Never use it to stage. */
    override suspend fun setSelection(request: SetSelectionRequest): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Select) { lease, _ -> api.setSelection(lease.roomId, request, lease.authScope) }

    suspend fun updatePolicy(policy: GuestControlPolicy): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Policy) { lease, _ ->
            api.updatePolicy(lease.roomId, UpdatePolicyRequest(policy.wire), lease.authScope)
        }

    /** End the party for everyone (host). */
    override suspend fun closeRoom(): ApiResult<Unit> = action(WatchPartyPendingAction.End) {
        val lease = activeBinding() ?: return@action missingRoom()
        val result = api.closeRoom(lease.roomId, lease.authScope)
        when {
            !isCurrent(lease) -> obsoleteRoomRequest()
            result is ApiResult.Error && result.code == 409 -> {
                // Already ended: converge on the ended state.
                endIfCurrent(lease, WatchPartyEndReason.Ended)
                ApiResult.Success(Unit)
            }
            else -> result
        }
    }

    suspend fun promoteSuggestion(suggestionId: String): ApiResult<RoomResponse> =
        roomAction(WatchPartyPendingAction.Promote) { lease, token ->
            api.promoteSuggestion(lease.roomId, token, PromoteSuggestionRequest(suggestionId), lease.authScope)
        }

    /**
     * Ask the server to move the whole room to another source after an
     * approved playback refusal. Fenced by [selectionRevision] and
     * [failedFileId]; a stale request returns the current snapshot.
     */
    suspend fun requestSourceFallback(
        selectionRevision: Long,
        failedFileId: String,
        reason: SourceFallbackReason,
    ): ApiResult<RoomResponse> = roomAction(WatchPartyPendingAction.Fallback) { lease, token ->
        api.sourceFallback(
            lease.roomId,
            token,
            SourceFallbackRequest(selectionRevision, failedFileId, reason.wire),
            lease.authScope,
        )
    }

    /** Read the room, adopting its snapshot (when newer) and renewed proof. */
    suspend fun refreshRoom(): ApiResult<RoomResponse> {
        val lease = activeBinding() ?: return missingRoom()
        return reconcileRoom(lease)
    }

    // ---- REST: shared browsing -----------------------------------------------

    suspend fun memberState(contentIds: List<String>): ApiResult<MemberStateResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val token = proofToken(lease) ?: return missingRoom()
        val result = api.memberState(lease.roomId, token, MemberStateRequest(contentIds), lease.authScope)
        return if (isCurrent(lease)) result else obsoleteRoomRequest()
    }

    suspend fun picker(): ApiResult<PickerResponse> {
        val lease = activeBinding() ?: return missingRoom()
        val token = proofToken(lease) ?: return missingRoom()
        val result = api.picker(lease.roomId, token, lease.authScope)
        return if (isCurrent(lease)) result else obsoleteRoomRequest()
    }

    // ---- REST: suggestions ----------------------------------------------------

    /**
     * Read every suggestion page under one captured membership and publish the
     * merged list with authoritative personal votes. Concurrent requests
     * coalesce: a caller whose request predates a read that already started
     * gets that read's result.
     */
    suspend fun refreshSuggestions(): ApiResult<List<Suggestion>> {
        val lease = activeBinding() ?: return missingRoom()
        val ticket = stateMutex.withLock { ++suggestionReadRequests }
        return suggestionReadMutex.withLock {
            if (stateMutex.withLock { suggestionReadStartedAt >= ticket }) {
                return@withLock ApiResult.Success(_suggestions.value)
            }
            val socketSeqAtStart = stateMutex.withLock {
                suggestionReadStartedAt = suggestionReadRequests
                socketSuggestionsSeq
            }
            val pages = mutableListOf<Suggestion>()
            var cursor: String? = null
            while (true) {
                val token = proofToken(lease) ?: return@withLock missingRoom()
                when (val page = api.listSuggestions(lease.roomId, token, lease.authScope, cursor)) {
                    is ApiResult.Success -> {
                        pages += page.data.suggestions
                        cursor = page.data.page?.nextCursor?.takeIf { page.data.page.hasMore }
                        if (cursor == null) break
                    }
                    is ApiResult.Error -> return@withLock page
                    is ApiResult.NetworkError -> return@withLock page
                }
            }
            val merged = pages.distinctBy { it.id }
            val published = stateMutex.withLock {
                if (!isCurrentLocked(lease)) return@withLock false
                votedIds.clear()
                votedIds.addAll(merged.filter { it.votedByMe }.map { it.id })
                _personalVotesKnown.value = true
                // A socket broadcast that arrived during the read carries newer
                // tallies; keep its rows and take only personal votes from HTTP.
                if (socketSuggestionsSeq == socketSeqAtStart) rawSuggestions = merged
                publishSuggestionsLocked()
                true
            }
            if (published) ApiResult.Success(_suggestions.value) else obsoleteRoomRequest()
        }
    }

    /**
     * Add a suggestion with the caller-selected id in [request]. After an
     * uncertain outcome, retry with the same request. A failed list refresh
     * after the receipt does not fail the suggestion.
     */
    suspend fun addSuggestion(request: AddSuggestionRequest): ApiResult<SuggestionReceipt> =
        suggestionAction(WatchPartyPendingAction.Suggest) { lease, token ->
            api.addSuggestion(lease.roomId, token, request, lease.authScope)
        }

    /** Remove a suggestion (host, or its suggester). A 404 means it is already gone. */
    suspend fun deleteSuggestion(suggestionId: String): ApiResult<Unit> {
        val result = suggestionAction(WatchPartyPendingAction.RemoveSuggestion) { lease, token ->
            api.deleteSuggestion(lease.roomId, token, suggestionId, lease.authScope)
        }
        return if (result is ApiResult.Error && result.code == 404 && result.error != "no_active_room") {
            ApiResult.Success(Unit)
        } else {
            result
        }
    }

    suspend fun vote(suggestionId: String): ApiResult<Unit> = setVote(suggestionId, voted = true)

    suspend fun unvote(suggestionId: String): ApiResult<Unit> = setVote(suggestionId, voted = false)

    private suspend fun setVote(suggestionId: String, voted: Boolean): ApiResult<Unit> =
        suggestionAction(WatchPartyPendingAction.Vote) { lease, token ->
            val result = if (voted) {
                api.vote(lease.roomId, token, suggestionId, lease.authScope)
            } else {
                api.unvote(lease.roomId, token, suggestionId, lease.authScope)
            }
            if (result is ApiResult.Success) {
                stateMutex.withLock {
                    if (isCurrentLocked(lease)) {
                        if (voted) votedIds.add(suggestionId) else votedIds.remove(suggestionId)
                        publishSuggestionsLocked()
                    }
                }
            }
            result
        }

    private fun publishSuggestionsLocked() {
        _suggestions.value = rankSuggestions(rawSuggestions).map { s ->
            if ((s.id in votedIds) != s.votedByMe) s.copy(votedByMe = s.id in votedIds) else s
        }
    }

    // ---- Actions ---------------------------------------------------------------

    private suspend fun <T> action(kind: WatchPartyPendingAction, block: suspend () -> ApiResult<T>): ApiResult<T> =
        actionMutex.withLock {
            _pendingAction.value = kind
            try {
                block()
            } finally {
                _pendingAction.value = null
            }
        }

    /**
     * One room mutation returning a snapshot. Success publishes it (and the
     * renewed proof). An uncertain outcome (network failure, malformed
     * response) or an ambiguous refusal (403, 404, 409) reconciles by reading
     * the room; the read, not the refusal, decides whether the room ended.
     * Nothing is replayed.
     */
    private suspend fun roomAction(
        kind: WatchPartyPendingAction,
        call: suspend (RoomBinding, String) -> ApiResult<RoomResponse>,
    ): ApiResult<RoomResponse> = action(kind) {
        val lease = activeBinding() ?: return@action missingRoom()
        val token = proofToken(lease) ?: return@action missingRoom()
        val seq = stateMutex.withLock { socketSnapshotSeq }
        when (val result = call(lease, token)) {
            is ApiResult.Success -> publishRoomResponse(lease, result, seq)
            is ApiResult.Error -> {
                if (result.code in RECONCILE_STATUSES || result.error == INVALID_RESPONSE) reconcileRoom(lease)
                result
            }
            is ApiResult.NetworkError -> {
                reconcileRoom(lease)
                result
            }
        }
    }

    private suspend fun <T> suggestionAction(
        kind: WatchPartyPendingAction,
        call: suspend (RoomBinding, String) -> ApiResult<T>,
    ): ApiResult<T> {
        val (lease, result) = action(kind) {
            val lease = activeBinding() ?: return@action missingRoom()
            val token = proofToken(lease) ?: return@action missingRoom()
            ApiResult.Success(lease to call(lease, token))
        }.let { outer ->
            when (outer) {
                is ApiResult.Success -> outer.data
                is ApiResult.Error -> return outer
                is ApiResult.NetworkError -> return outer
            }
        }
        if (!isCurrent(lease)) return obsoleteRoomRequest()
        // Reconcile the list whatever the outcome; the mutation result stands
        // even if this read fails.
        refreshSuggestions()
        if (result is ApiResult.Error && result.code == 409) reconcileRoom(lease)
        return result
    }

    // ---- Publication ------------------------------------------------------------

    private suspend fun proofToken(lease: RoomBinding): String? = stateMutex.withLock {
        if (isCurrentLocked(lease)) proof?.token else null
    }

    private suspend fun publishRoomResponse(
        lease: RoomBinding,
        result: ApiResult.Success<RoomResponse>,
        socketSeqAtRequest: Long,
    ): ApiResult<RoomResponse> {
        val response = result.data
        if (response.room.roomId != lease.roomId || response.roomAccessToken.isBlank()) return invalidRoomResponse()
        var ended = false
        val published = stateMutex.withLock {
            if (!isCurrentLocked(lease)) return@withLock false
            proof = RoomProof(response.roomAccessToken, roomProofExpiryMs(response.roomAccessToken))
            if (response.room.phase == RoomPhase.Ended) {
                endLocked(lease, WatchPartyEndReason.Ended)
                ended = true
                return@withLock true
            }
            val current = _roomSnapshot.value
            val newer = current == null ||
                response.room.generation > current.generation ||
                (response.room.generation == current.generation && socketSnapshotSeq == socketSeqAtRequest)
            if (newer) {
                _roomSnapshot.value = response.room
                val echo = _roomDeliveryEcho.value
                if (echo != null && echo.playbackSessionId != response.room.attachedSessionId) {
                    _roomDeliveryEcho.value = null
                }
                refreshTransportAuthorizationLocked()
            }
            true
        }
        return when {
            !published -> obsoleteRoomRequest()
            ended -> ApiResult.Error(409, WatchPartyEndReason.Ended, "The Watch Party has ended.")
            else -> result
        }
    }

    /**
     * Read the room to settle an uncertain or ambiguous outcome. A 403, 404,
     * or 409 here means this device can no longer take part.
     */
    private suspend fun reconcileRoom(lease: RoomBinding): ApiResult<RoomResponse> {
        val token = proofToken(lease) ?: return missingRoom()
        val seq = stateMutex.withLock { socketSnapshotSeq }
        return when (val r = api.getRoom(lease.roomId, token, lease.authScope)) {
            is ApiResult.Success -> publishRoomResponse(lease, r, seq)
            is ApiResult.Error -> {
                when (r.code) {
                    404 -> endIfCurrent(lease, WatchPartyEndReason.NotFound)
                    409 -> endIfCurrent(lease, WatchPartyEndReason.Ended)
                    403 -> endIfCurrent(lease, WatchPartyEndReason.Unauthorized)
                }
                r
            }
            is ApiResult.NetworkError -> r
        }
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

    private suspend fun endIfCurrent(lease: RoomBinding, reason: String) {
        stateMutex.withLock { if (isCurrentLocked(lease)) endLocked(lease, reason) }
    }

    /** Terminal for this membership: nothing may reopen it. */
    private fun endLocked(lease: RoomBinding, reason: String) {
        val last = _roomSnapshot.value
        terminalGeneration = lease.generation
        _ended.value = WatchPartyEnded(
            roomId = lease.roomId,
            code = last?.code.orEmpty(),
            reason = reason,
            wasHost = last?.selfRole == MemberRole.Host,
        )
        _roomClosedReason.value = reason
        _roomSnapshot.value = null
        _roomDeliveryEcho.value = null
        _latestCommand.value = null
        _connectionState.value = _connectionState.value.copy(writable = false)
        realtimeConnectionId = null
        refreshTransportAuthorizationLocked()
    }

    private fun invalidRoomResponse(): ApiResult.Error =
        ApiResult.Error(502, "invalid_room_response", "The room response was missing or mismatched.")

    private fun missingRoom(): ApiResult.Error =
        ApiResult.Error(409, "no_active_room", "No active Watch Party.")

    private fun missingAuthScope(): ApiResult.Error =
        ApiResult.Error(401, "missing_auth_scope", "No authenticated Watch Party scope.")

    private fun obsoleteRoomRequest(): ApiResult.Error =
        ApiResult.Error(409, "obsolete_room_request", "The Watch Party identity changed.")

    // ---- WS: client→server sends ------------------------------------------------

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

    override suspend fun attachSession(sessionId: String): Boolean =
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
    override suspend fun transportRequestForAuthorization(
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

    override suspend fun stateReport(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        commandId: String?,
        isReady: Boolean,
    ): Boolean = currentWritableRealtime()?.stateReport(sessionId, positionSeconds, isPaused, commandId, isReady) ?: false

    override suspend fun ready(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        commandId: String?,
    ): Boolean = currentWritableRealtime()?.ready(sessionId, positionSeconds, isPaused, commandId) ?: false

    override suspend fun buffering(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
    ): Boolean = currentWritableRealtime()?.buffering(sessionId, positionSeconds, isPaused) ?: false

    /** Advisory lobby Ready over the room socket. */
    suspend fun setLobbyReady(ready: Boolean): Boolean =
        currentWritableRealtime()?.lobbyReady(ready) ?: false

    // ---- WS lifecycle ------------------------------------------------------------

    private sealed interface AttemptEnd {
        data class Closed(val reason: String?) : AttemptEnd
        data object Replaced : AttemptEnd
        data class Transport(val cause: Throwable?) : AttemptEnd
    }

    /**
     * Own the room socket for the current membership until the engagement ends
     * or the caller's scope is cancelled. A socket that stayed up reconnects at
     * once (the server rotates every socket after five minutes); a socket that
     * fails quickly backs off. Reconnecting continues for at least
     * [WatchPartyTiming.reconnectBudgetMs] after the last healthy socket, which
     * covers the host's two-minute grace. Terminal frames and terminal ticket
     * refusals end the engagement; `connection_replaced` never reclaims it.
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
                disconnectedAtMs = monotonicNowMs(),
            )
            refreshTransportAuthorizationLocked()
            newOwner
        }
        try {
            coroutineScope {
                val renewal = if (timing.backgroundWork) launch { renewProofLoop(lease, owner) } else null
                socketLoop(lease, owner, client, this)
                renewal?.cancel()
            }
        } finally {
            withContext(NonCancellable) { clearRealtimeIfCurrent(lease, client, owner) }
        }
    }

    private suspend fun socketLoop(
        lease: RoomBinding,
        owner: Long,
        client: WatchTogetherRealtimeClient,
        scope: CoroutineScope,
    ) {
        var backoffIndex = 0
        var failures = 0
        var lastHealthyMs = monotonicNowMs()
        var proofRenewedForRefusal = false
        while (isCurrent(lease, owner)) {
            val token = proofToken(lease) ?: break
            var openedAtMs: Long? = null
            var sawSnapshot = false
            var end: AttemptEnd = AttemptEnd.Transport(null)
            var pings: Job? = null
            try {
                client.connect(lease.roomId, token, lease.authScope).collect { event ->
                    if (!isCurrent(lease, owner)) throw ObsoleteBinding
                    when (event) {
                        RoomRealtimeEvent.Opened -> {
                            openedAtMs = monotonicNowMs()
                            markOpened(lease, owner, client.currentConnectionId())
                            pings?.cancel()
                            if (timing.backgroundWork) pings = scope.launch { pingLoop(lease, owner, client) }
                            // Suggestions that predate this socket are not replayed on
                            // it. Hydrate off the receive path.
                            scope.launch { refreshSuggestions() }
                        }
                        is RoomRealtimeEvent.Closed -> {
                            end = AttemptEnd.Closed(event.reason)
                            throw AttemptStopped
                        }
                        is RoomRealtimeEvent.ConnectionReplaced -> {
                            end = AttemptEnd.Replaced
                            throw AttemptStopped
                        }
                        is RoomRealtimeEvent.TransportTerminated -> {
                            end = AttemptEnd.Transport(event.cause)
                            throw AttemptStopped
                        }
                        is RoomRealtimeEvent.Malformed -> scope.launch { reconcileRoom(lease) }
                        else -> {
                            if (event is RoomRealtimeEvent.SnapshotEvent && event.room.roomId == lease.roomId) {
                                sawSnapshot = true
                            }
                            fold(lease, owner, event)
                        }
                    }
                }
            } catch (e: CancellationException) {
                pings?.cancel()
                throw e
            } catch (_: ObsoleteBinding) {
                pings?.cancel()
                break
            } catch (_: AttemptStopped) {
                // `end` records why.
            } catch (failure: Throwable) {
                end = AttemptEnd.Transport(failure)
            }
            pings?.cancel()
            markNotWritable(lease, owner)
            lastSocketEnd = describeSocketEnd(end, openedAtMs?.let { monotonicNowMs() - it })
            when (val ended = end) {
                is AttemptEnd.Closed -> {
                    endIfOwner(lease, owner, ended.reason?.takeIf { it.isNotBlank() } ?: WatchPartyEndReason.HostLeft)
                    break
                }
                AttemptEnd.Replaced -> {
                    endIfOwner(lease, owner, WatchPartyEndReason.Replaced)
                    break
                }
                is AttemptEnd.Transport -> when (val cause = ended.cause) {
                    is RoomTicketRefusedException -> {
                        if (cause.status == 403 && !proofRenewedForRefusal) {
                            // The proof may have lapsed; one renewal decides.
                            proofRenewedForRefusal = true
                            if (reconcileRoom(lease) is ApiResult.Success) continue
                        }
                        if (cause.terminal) {
                            endIfOwner(lease, owner, ticketEndReason(cause.status))
                            break
                        }
                    }
                    is RoomTicketExpiringException -> {
                        val expiry = stateMutex.withLock { proof?.expiresAtEpochMs }
                        if (expiry != null && expiry - wallClockMs() < timing.proofRenewalMarginMs) reconcileRoom(lease)
                        delay(timing.expiringTicketRetryMs)
                        continue
                    }
                    else -> Unit
                }
            }
            if (!isCurrent(lease, owner)) break
            val now = monotonicNowMs()
            val opened = openedAtMs
            if (opened != null && sawSnapshot) {
                lastHealthyMs = now
                proofRenewedForRefusal = false
                if (now - opened >= timing.stableConnectionMs) {
                    // Routine rotation: reconnect at once.
                    failures = 0
                    backoffIndex = 0
                    continue
                }
            }
            failures++
            if (failures >= timing.maxReconnectFailures && now - lastHealthyMs >= timing.reconnectBudgetMs) {
                endIfOwner(lease, owner, WatchPartyEndReason.ConnectionLost)
                break
            }
            val step = timing.backoffMs[backoffIndex.coerceAtMost(timing.backoffMs.lastIndex)]
            delay(step + random.nextLong(0L, step / 4 + 1))
            backoffIndex = (backoffIndex + 1).coerceAtMost(timing.backoffMs.lastIndex)
        }
    }

    /**
     * Why the last room socket ended and how long it had been open, for the
     * debug harness. Carries exception class names and messages only, never
     * tickets or tokens.
     */
    @Volatile
    var lastSocketEnd: String? = null
        private set

    private fun describeSocketEnd(end: AttemptEnd, openMs: Long?): String {
        val what = when (end) {
            is AttemptEnd.Closed -> "closed:${end.reason}"
            AttemptEnd.Replaced -> "replaced"
            is AttemptEnd.Transport -> end.cause?.let { cause ->
                "transport:${cause::class.simpleName}:${cause.message?.take(160)}"
            } ?: "transport:eof"
        }
        return "$what after ${openMs ?: -1}ms"
    }

    private fun ticketEndReason(status: Int): String = when (status) {
        404 -> WatchPartyEndReason.NotFound
        409 -> WatchPartyEndReason.Ended
        else -> WatchPartyEndReason.Unauthorized
    }

    private suspend fun pingLoop(lease: RoomBinding, owner: Long, client: WatchTogetherRealtimeClient) {
        var sent = 0
        while (isCurrent(lease, owner)) {
            client.ping(formatEpochMillisRfc3339(wallClockMs()))
            sent++
            delay(if (sent < timing.initialPings) timing.initialPingSpacingMs else timing.pingIntervalMs)
        }
    }

    private suspend fun renewProofLoop(lease: RoomBinding, owner: Long) {
        while (isCurrent(lease, owner)) {
            val expiry = stateMutex.withLock { proof?.expiresAtEpochMs }
            val waitMs = if (expiry == null) timing.proofUnknownRenewalMs else expiry - wallClockMs() - timing.proofRenewalMarginMs
            if (waitMs > 0) delay(waitMs)
            if (!isCurrent(lease, owner)) break
            val renewed = reconcileRoom(lease)
            if (renewed !is ApiResult.Success) {
                if (!isCurrent(lease, owner)) break
                delay(timing.proofRetryMs)
            }
        }
    }

    private suspend fun isCurrent(lease: RoomBinding): Boolean =
        stateMutex.withLock { isCurrentLocked(lease) }

    private suspend fun isCurrent(lease: RoomBinding, owner: Long): Boolean =
        stateMutex.withLock { isCurrentOwnerLocked(lease, owner) }

    private fun isCurrentOwnerLocked(lease: RoomBinding, owner: Long): Boolean =
        isCurrentLocked(lease) && activeConnectionOwner == owner

    private suspend fun endIfOwner(lease: RoomBinding, owner: Long, reason: String) {
        stateMutex.withLock { if (isCurrentOwnerLocked(lease, owner)) endLocked(lease, reason) }
    }

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
                    disconnectedAtMs = null,
                )
                refreshTransportAuthorizationLocked()
            }
        }
    }

    private suspend fun markNotWritable(lease: RoomBinding, owner: Long) {
        stateMutex.withLock {
            if (binding == lease && activeConnectionOwner == owner) {
                val previous = _connectionState.value
                _connectionState.value = previous.copy(
                    writable = false,
                    disconnectedAtMs = previous.disconnectedAtMs ?: monotonicNowMs(),
                )
                realtimeConnectionId = null
                refreshTransportAuthorizationLocked()
            }
        }
    }

    /** Sentinels that unwind one socket attempt's collect loop. */
    private object AttemptStopped : Throwable()
    private object ObsoleteBinding : Throwable()

    /** Fold one realtime event into the state flows. */
    private suspend fun fold(lease: RoomBinding, owner: Long, event: RoomRealtimeEvent) {
        stateMutex.withLock {
            if (!isCurrentOwnerLocked(lease, owner)) return
            when (event) {
                is RoomRealtimeEvent.SnapshotEvent -> {
                    val room = event.room
                    if (room.roomId != lease.roomId) return
                    if (room.phase == RoomPhase.Ended) {
                        endLocked(lease, WatchPartyEndReason.Ended)
                        return
                    }
                    val current = _roomSnapshot.value
                    if (current != null && room.generation < current.generation) return
                    socketSnapshotSeq++
                    _roomSnapshot.value = room
                    _roomDeliveryEcho.value = room.attachedSessionId
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
                is RoomRealtimeEvent.SuggestionsEvent -> {
                    socketSuggestionsSeq++
                    rawSuggestions = event.suggestions.filter { it.roomId.isBlank() || it.roomId == lease.roomId }
                    publishSuggestionsLocked()
                }
                is RoomRealtimeEvent.TransportCommandEvent -> {
                    _latestCommand.value = ScheduledTransportCommand(
                        command = event.command,
                        executeAtMs = parseRfc3339ToEpochMillis(event.command.executeAt),
                        connection = _connectionState.value,
                        receivedAtMs = monotonicNowMs(),
                    )
                }
                is RoomRealtimeEvent.Pong -> {
                    val sent = parseRfc3339ToEpochMillis(event.clientSentAt)
                    val serverReceived = parseRfc3339ToEpochMillis(event.serverReceivedAt)
                    val serverSent = parseRfc3339ToEpochMillis(event.serverSentAt)
                    val received = event.clientReceivedMs
                    if (sent != null && serverReceived != null && serverSent != null && received != null) {
                        clockEstimator.record(sent, serverReceived, serverSent, received, monotonicNowMs())
                        _clock.value = clockEstimator.estimate
                    }
                }
                is RoomRealtimeEvent.Error ->
                    // Transient, NON-terminal: a rejected request must not end the party.
                    _errors.tryEmit(event.message.ifBlank { event.code })
                RoomRealtimeEvent.Opened,
                is RoomRealtimeEvent.Closed,
                is RoomRealtimeEvent.ConnectionReplaced,
                is RoomRealtimeEvent.TransportTerminated,
                is RoomRealtimeEvent.Malformed -> Unit
            }
        }
    }

    /**
     * Check the local wall clock against the monotonic clock. A jump resets
     * the server-time estimate so no timing work uses a stale offset.
     */
    override suspend fun checkClockContinuity(): RoomClockEstimate = stateMutex.withLock {
        if (clockEstimator.checkContinuity(wallClockMs(), monotonicNowMs())) _clock.value = clockEstimator.estimate
        _clock.value
    }

    /** Clear all room state on leave or identity change. The connect() loop ends via scope cancellation. */
    override suspend fun reset() {
        stateMutex.withLock {
            nextGeneration++
            latestRoomRequest++
            binding = null
            proof = null
            terminalGeneration = null
            _roomSnapshot.value = null
            _roomDeliveryEcho.value = null
            _latestCommand.value = null
            rawSuggestions = emptyList()
            _suggestions.value = emptyList()
            _personalVotesKnown.value = false
            _roomClosedReason.value = null
            _ended.value = null
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
        private const val INVALID_RESPONSE = "invalid_response"

        /** Refusals that may mean the room ended or this membership lapsed; the room read decides. */
        private val RECONCILE_STATUSES = setOf(403, 404, 409)
        private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()
    }
}
