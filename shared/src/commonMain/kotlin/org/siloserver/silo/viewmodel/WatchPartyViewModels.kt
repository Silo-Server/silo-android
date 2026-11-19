package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PickerResponse
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchPartyEnded
import org.siloserver.silo.repository.WatchPartyPendingAction
import org.siloserver.silo.repository.WatchTogetherConnectionState
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.LobbyReadiness
import org.siloserver.silo.watchtogether.RecentWatchParties
import org.siloserver.silo.watchtogether.RecentWatchParty
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.WatchPartyEligibility
import org.siloserver.silo.watchtogether.WatchPartyFeatures
import org.siloserver.silo.watchtogether.WatchPartyInvite
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.lobbyReadiness
import org.siloserver.silo.watchtogether.newWatchPartyId
import org.siloserver.silo.watchtogether.parseWatchPartyInvite
import org.siloserver.silo.watchtogether.watchPartyDestination
import org.siloserver.silo.watchtogether.watchPartyEligibility
import org.siloserver.silo.watchtogether.watchPartyErrorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An item to stage or suggest, as the detail page or picker displayed it. */
data class WatchPartyItem(
    val contentId: String,
    val contentType: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val fileId: Int? = null,
    val libraryId: Int? = null,
    /** How spoiler protection treats the title until its detail loads. */
    val spoilers: WatchPartySpoilers = WatchPartySpoilers.forType(contentType),
)

/**
 * The Watch Party hub: availability, the live party (Return to Party), the
 * recent party (Rejoin), and hosting or joining. A create keeps its
 * caller-selected room id until it definitely succeeds or fails, so an
 * explicit retry after an uncertain outcome replays the same room instead of
 * creating a second one.
 */
class WatchPartyHubViewModel(
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
    private val availability: WatchPartyAvailabilityRepository,
    private val recents: RecentWatchParties,
    /** Whether a link's server is the server this app is signed in to (base path included). */
    private val isCurrentServer: (String) -> Boolean,
) : ViewModel() {

    data class UiState(
        /** Null while the first probe runs. */
        val availability: WatchPartyAvailability? = null,
        val current: RoomSnapshot? = null,
        val recent: RecentWatchParty? = null,
        val ended: WatchPartyEnded? = null,
        val busy: WatchPartyPendingAction? = null,
        val error: UiText? = null,
        /** A create whose outcome is unknown can be retried with the same identity. */
        val createRetryable: Boolean = false,
        val destination: WatchPartyDestination? = null,
        /**
         * A forced re-check is running. [availability] still shows the last
         * answer meanwhile, so one-shot handoffs wait for this to clear.
         */
        val checking: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Forced re-checks still running; overlapping ones must all finish. */
    private val forcedChecks = MutableStateFlow(0)

    private var pendingCreate: CreateRoomRequest? = null
    private var pendingStage: WatchPartyItem? = null

    init {
        viewModelScope.launch {
            combine(
                repository.roomSnapshot,
                repository.ended,
                repository.pendingAction,
                availability.availability,
            ) { room, ended, pending, available ->
                _uiState.update {
                    it.copy(
                        current = room?.takeIf { snapshot -> watchPartyDestination(snapshot) != null },
                        ended = ended,
                        busy = pending,
                        availability = available ?: it.availability,
                    )
                }
            }.collect {}
        }
        refresh()
    }

    /** Probe availability (again when [force]) and reload the recent party. */
    fun refresh(force: Boolean = false) {
        if (force) forcedChecks.update { it + 1 }
        _uiState.update { it.copy(checking = forcedChecks.value > 0) }
        viewModelScope.launch {
            try {
                val result = availability.refresh(force)
                _uiState.update { it.copy(availability = result, recent = recents.current()) }
            } finally {
                if (force) forcedChecks.update { it - 1 }
                _uiState.update { it.copy(checking = forcedChecks.value > 0) }
            }
        }
    }

    /** Host an empty party. Host Picks unless [mode] says otherwise. */
    fun host(mode: RoomSelectionMode = RoomSelectionMode.HostPick) {
        pendingStage = null
        create(mode)
    }

    /**
     * Host a party with [item] staged. The item is staged in the lobby; nothing
     * plays until the host starts it. If staging fails after the room exists,
     * the room is kept and the lobby offers to try again.
     */
    fun hostWithItem(item: WatchPartyItem) {
        pendingStage = item
        create(RoomSelectionMode.HostPick)
    }

    /** Retry the last create with the same room identity. */
    fun retryCreate() {
        val request = pendingCreate ?: return
        launchCreate(request)
    }

    private fun create(mode: RoomSelectionMode) {
        if (_uiState.value.busy != null) return
        val features = (availability.availability.value as? WatchPartyAvailability.Available)?.features
        if (features?.stagedSelection != true) {
            fail(SharedString.WatchPartyErrorCannotHost)
            return
        }
        // A different mode is a different draft; the same mode reuses an uncertain one.
        val request = pendingCreate?.takeIf { it.selectionMode == mode.wire }
            ?: CreateRoomRequest(roomId = newWatchPartyId(), selectionMode = mode.wire)
        pendingCreate = request
        launchCreate(request)
    }

    private fun launchCreate(request: CreateRoomRequest) {
        _uiState.update { it.copy(error = null, createRetryable = false) }
        viewModelScope.launch {
            when (val created = repository.createRoom(request)) {
                is ApiResult.Success -> {
                    pendingCreate = null
                    enter(created.data.room)
                    pendingStage?.let { item -> stageAfterCreate(item) }
                    pendingStage = null
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            error = UiText.Key(SharedString.WatchPartyErrorCreateUnconfirmed),
                            createRetryable = true,
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (created.error == "invalid_response") {
                        _uiState.update {
                            it.copy(error = watchPartyErrorText(created, SharedString.WatchPartyErrorCreate), createRetryable = true)
                        }
                    } else {
                        pendingCreate = null
                        fail(watchPartyErrorText(created, SharedString.WatchPartyErrorCreate))
                    }
                }
            }
        }
    }

    private suspend fun stageAfterCreate(item: WatchPartyItem) {
        val staged = repository.stageSelection(
            SetSelectionRequest(contentId = item.contentId, fileId = item.fileId, libraryId = item.libraryId),
        )
        if (staged !is ApiResult.Success) {
            fail(SharedString.WatchPartyErrorAddAfterCreate, item.title)
        }
    }

    /** Join by typed code or pasted invitation. */
    fun join(input: String) {
        when (val invite = parseWatchPartyInvite(input)) {
            null -> fail(SharedString.WatchPartyErrorCodeInvalid)
            else -> joinInvite(invite)
        }
    }

    fun joinInvite(invite: WatchPartyInvite) {
        if (_uiState.value.busy != null) return
        if (repository.roomSnapshot.value != null) {
            fail(SharedString.WatchPartyErrorAlreadyInParty)
            return
        }
        val request = when (invite) {
            is WatchPartyInvite.Code -> JoinRoomRequest(code = invite.code)
            is WatchPartyInvite.Link -> {
                if (!isCurrentServer(invite.serverUrl)) {
                    fail(SharedString.WatchPartyErrorOtherServer)
                    return
                }
                JoinRoomRequest(joinToken = invite.token)
            }
        }
        launchJoin(request, SharedString.WatchPartyErrorJoin)
    }

    /** Explicit rejoin of the recent party: a fresh join by code. */
    fun rejoinRecent() {
        val recent = _uiState.value.recent ?: return
        if (_uiState.value.busy != null) return
        launchJoin(JoinRoomRequest(code = recent.code), SharedString.WatchPartyErrorRejoin) { failure ->
            if (failure is ApiResult.Error && (failure.code == 404 || failure.code == 409)) {
                recents.forget(recent.roomId)
                _uiState.update { it.copy(recent = null) }
            }
        }
    }

    /** Return to the live party. */
    fun returnToParty() {
        val room = repository.roomSnapshot.value ?: return
        enter(room)
    }

    private fun launchJoin(
        request: JoinRoomRequest,
        fallback: SharedString,
        onFailure: suspend (ApiResult<RoomResponse>) -> Unit = {},
    ) {
        _uiState.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val joined = repository.joinRoom(request)) {
                is ApiResult.Success -> enter(joined.data.room)
                else -> {
                    onFailure(joined)
                    fail(
                        when {
                            joined is ApiResult.Error && joined.code == 404 -> UiText.Key(SharedString.WatchPartyErrorNoPartyWithCode)
                            // None of the ordinary conflicts apply to joining,
                            // so a join 409 is a party that has ended.
                            joined is ApiResult.Error && joined.code == 409 -> UiText.Key(SharedString.WatchPartyErrorJoinEnded)
                            else -> watchPartyErrorText(joined, fallback)
                        },
                    )
                }
            }
        }
    }

    private fun enter(room: RoomSnapshot) {
        roomSession.adopt(room.roomId)
        _uiState.update { it.copy(destination = watchPartyDestination(room), error = null) }
    }

    private fun fail(message: UiText) {
        _uiState.update { it.copy(error = message) }
    }

    private fun fail(message: SharedString, vararg args: Any) = fail(UiText.Key(message, args.toList()))

    fun consumeDestination() = _uiState.update { it.copy(destination = null) }
    fun clearError() = _uiState.update { it.copy(error = null, createRetryable = false) }
}

/** How the room socket is doing, for the reconnect notice. */
sealed interface WatchPartyConnectionStatus {
    data object Connected : WatchPartyConnectionStatus
    /** [sinceMs] is monotonic; show a notice only once it has lasted 2 s. */
    data class Reconnecting(val sinceMs: Long) : WatchPartyConnectionStatus
}

/**
 * The lobby: the empty or staged item, members and lobby Ready, Start, mode
 * and guest policy, suggestions and votes. Every action goes through the room
 * owner, which runs one at a time; the room's phase alone decides when to
 * open the player.
 */
class WatchPartyLobbyViewModel(
    private val roomId: String,
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
    private val availability: WatchPartyAvailabilityRepository,
) : ViewModel() {

    data class LobbyState(
        val room: RoomSnapshot? = null,
        val suggestions: List<Suggestion> = emptyList(),
        val personalVotesKnown: Boolean = false,
        val eligibility: WatchPartyEligibility = WatchPartyEligibility(),
        val features: WatchPartyFeatures? = null,
        val readiness: LobbyReadiness = LobbyReadiness(0, 0),
        val connection: WatchPartyConnectionStatus = WatchPartyConnectionStatus.Connected,
        val pending: WatchPartyPendingAction? = null,
        val ended: WatchPartyEnded? = null,
        /** The last suggestion draft whose outcome is unknown; retry keeps its id. */
        val suggestionRetry: WatchPartyItem? = null,
    )

    private val suggestionDraft = MutableStateFlow<Pair<AddSuggestionRequest, WatchPartyItem>?>(null)

    val state: StateFlow<LobbyState> = combine(
        combine(repository.roomSnapshot, repository.suggestions, repository.personalVotesKnown, ::Triple),
        combine(repository.pendingAction, repository.connectionState, repository.ended, ::Triple),
        availability.availability,
        suggestionDraft,
    ) { (room, suggestions, votesKnown), (pending, connection, ended), available, draft ->
        val features = (available as? WatchPartyAvailability.Available)?.features
        LobbyState(
            room = room,
            suggestions = suggestions,
            personalVotesKnown = votesKnown,
            eligibility = watchPartyEligibility(room, features, busy = pending != null, personalVotesKnown = votesKnown),
            features = features,
            readiness = lobbyReadiness(room),
            connection = connection.status(),
            pending = pending,
            ended = ended,
            suggestionRetry = draft?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LobbyState())

    /** Where to go: the player once the room plays. */
    val destination: StateFlow<WatchPartyDestination?> = repository.roomSnapshot
        .map { room -> room?.takeIf { it.roomId == roomId }?.let(::watchPartyDestination) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot notices for failed actions. */
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    init {
        roomSession.adopt(roomId)
        viewModelScope.launch { availability.refresh() }
        viewModelScope.launch { repository.errors.collect { _messages.tryEmit(UiText.Raw(it)) } }
    }

    fun stage(item: WatchPartyItem) = act(SharedString.WatchPartyErrorAddTitle, item.title) {
        repository.stageSelection(SetSelectionRequest(item.contentId, item.fileId, item.libraryId))
    }

    fun start() = act(SharedString.WatchPartyErrorStart) { repository.startPlayback() }

    fun setMode(mode: RoomSelectionMode) = act(SharedString.WatchPartyErrorMode) { repository.setSelectionMode(mode) }

    fun setGuestPolicy(policy: GuestControlPolicy) = act(SharedString.WatchPartyErrorPolicy) {
        repository.updatePolicy(policy)
    }

    fun setLobbyReady(ready: Boolean) {
        viewModelScope.launch {
            if (!repository.setLobbyReady(ready)) _messages.tryEmit(UiText.Key(SharedString.WatchPartyReconnecting))
        }
    }

    /** Suggest [item]. After an uncertain outcome, [retrySuggestion] resends the same suggestion. */
    fun suggest(item: WatchPartyItem) {
        // An uncertain or in-flight suggestion of the same title resends its
        // own id, so the server can't end up with the title twice.
        val request = (suggestionDraft.value?.first ?: inFlightSuggestion)?.takeIf { it.contentId == item.contentId } ?: AddSuggestionRequest(
            suggestionId = newWatchPartyId(),
            contentId = item.contentId,
            contentType = item.contentType,
            title = item.title,
            subtitle = item.subtitle,
            posterUrl = item.posterUrl,
        )
        sendSuggestion(request, item)
    }

    fun retrySuggestion() {
        val (request, item) = suggestionDraft.value ?: return
        sendSuggestion(request, item)
    }

    fun dismissSuggestionRetry() {
        suggestionDraft.value = null
    }

    /** The suggestion being sent now; a repeat of the same title reuses its id. */
    private var inFlightSuggestion: AddSuggestionRequest? = null

    private fun sendSuggestion(request: AddSuggestionRequest, item: WatchPartyItem) {
        inFlightSuggestion = request
        // Only this request's own outcome may clear or replace the retry draft.
        fun draftIsMineOrEmpty() = suggestionDraft.value?.first?.suggestionId.let { it == null || it == request.suggestionId }
        viewModelScope.launch {
            val result = repository.addSuggestion(request)
            if (inFlightSuggestion == request) inFlightSuggestion = null
            when (result) {
                is ApiResult.Success -> if (draftIsMineOrEmpty()) suggestionDraft.value = null
                is ApiResult.NetworkError -> {
                    suggestionDraft.value = request to item
                    _messages.tryEmit(UiText.Key(SharedString.WatchPartyErrorSuggestionUnconfirmed))
                }
                is ApiResult.Error -> {
                    if (draftIsMineOrEmpty()) suggestionDraft.value = (request to item).takeIf { result.error == "invalid_response" }
                    _messages.tryEmit(watchPartyErrorText(result, SharedString.WatchPartyErrorSuggestTitle, item.title))
                }
            }
        }
    }

    fun vote(suggestion: Suggestion) = act(SharedString.WatchPartyErrorVote) {
        if (suggestion.votedByMe) repository.unvote(suggestion.id) else repository.vote(suggestion.id)
    }

    fun remove(suggestion: Suggestion) {
        if (!canRemoveSuggestion(repository.roomSnapshot.value, suggestion)) return
        act(SharedString.WatchPartyErrorRemoveSuggestion) { repository.deleteSuggestion(suggestion.id) }
    }

    /** Vote rooms: start a suggestion now (host). */
    fun promote(suggestion: Suggestion) = act(SharedString.WatchPartyErrorStartTitle, suggestion.title) {
        repository.promoteSuggestion(suggestion.id)
    }

    /**
     * Host-pick rooms: stage a suggestion as the next pick ("Up next"). This
     * uses the stage operation, never promote, so nothing starts playing.
     */
    fun queue(suggestion: Suggestion) = stage(
        WatchPartyItem(
            contentId = suggestion.contentId,
            contentType = suggestion.contentType,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            posterUrl = suggestion.posterUrl,
        ),
    )

    /** Who in the room has watched [contentId]: one bounded read for the selected candidate. */
    suspend fun memberState(contentId: String): ItemMemberState? {
        val features = (availability.availability.value as? WatchPartyAvailability.Available)?.features
        if (features?.memberState != true) return null
        return (repository.memberState(listOf(contentId)) as? ApiResult.Success)?.data?.items?.firstOrNull()
    }

    fun endForEveryone() = roomSession.depart(closeRoom = true)

    fun leave() = roomSession.depart(closeRoom = false)

    private fun <T> act(fallback: SharedString, vararg args: Any, operation: suspend () -> ApiResult<T>): Job =
        viewModelScope.launch {
            val result = operation()
            if (result !is ApiResult.Success) _messages.tryEmit(watchPartyErrorText(result, fallback, *args))
        }
}

private fun WatchTogetherConnectionState.status(): WatchPartyConnectionStatus =
    if (writable) {
        WatchPartyConnectionStatus.Connected
    } else {
        WatchPartyConnectionStatus.Reconnecting(disconnectedAtMs ?: 0L)
    }

/**
 * The room picker: the server's shared rows (Continue Together, members'
 * watchlists) and catalog search. Search is debounced and a newer query
 * discards an older response. Rows only list what this profile may browse;
 * they do not prove every member can play an item.
 */
class WatchPartyPickerViewModel(
    private val repository: WatchTogetherRepository,
    private val availability: WatchPartyAvailabilityRepository,
    private val search: suspend (String) -> ApiResult<List<BrowseItem>>,
) : ViewModel() {

    data class PickerState(
        val rows: PickerResponse? = null,
        val rowsLoading: Boolean = false,
        val query: String = "",
        val results: List<BrowseItem> = emptyList(),
        val searching: Boolean = false,
        val error: UiText? = null,
    )

    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()
    private val queries = MutableStateFlow("")
    private var generation = 0L

    init {
        viewModelScope.launch {
            queries.collectLatest { query ->
                val ticket = generation
                if (query.length >= MIN_QUERY) {
                    delay(SEARCH_DEBOUNCE_MS)
                    runSearch(query, ticket)
                }
            }
        }
    }

    /** Load the shared rows when the server offers them; otherwise search alone. */
    fun loadRows() {
        viewModelScope.launch {
            val features = (availability.refresh() as? WatchPartyAvailability.Available)?.features
            if (features?.picker != true) {
                _state.update { it.copy(rows = null, rowsLoading = false) }
                return@launch
            }
            _state.update { it.copy(rowsLoading = true) }
            when (val result = repository.picker()) {
                // The picker reads profile catalog access, which can include
                // ebooks and other non-video items; a party plays video only,
                // and TV never shows reading items.
                is ApiResult.Success -> _state.update {
                    it.copy(rows = result.data.playableOnly(), rowsLoading = false, error = null)
                }
                else -> _state.update {
                    it.copy(rowsLoading = false, error = watchPartyErrorText(result, SharedString.WatchPartyErrorLoadSuggestions))
                }
            }
        }
    }

    fun onQuery(query: String) {
        val normalized = query.trim()
        _state.update { it.copy(query = query) }
        if (normalized == queries.value) return
        // Invalidate before the debounce so an old completion cannot replace
        // the results or loading state for the text already on screen.
        generation++
        _state.update {
            it.copy(results = emptyList(), searching = normalized.length >= MIN_QUERY, error = null)
        }
        queries.value = normalized
    }

    private suspend fun runSearch(query: String, ticket: Long) {
        if (ticket != generation) return
        val result = search(query)
        if (ticket != generation) return
        when (result) {
            is ApiResult.Success -> _state.update {
                it.copy(results = result.data.filter { item -> item.type in PLAYABLE_TYPES }, searching = false, error = null)
            }
            else -> _state.update { it.copy(searching = false, error = watchPartyErrorText(result, SharedString.WatchPartyErrorSearch)) }
        }
    }

    private fun PickerResponse.playableOnly(): PickerResponse = copy(
        continueTogether = continueTogether.filter { it.item.type in PLAYABLE_TYPES },
        watchlistUnion = watchlistUnion.filter { it.item.type in PLAYABLE_TYPES },
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MIN_QUERY = 2
        val PLAYABLE_TYPES = setOf("movie", "series", "episode")
    }
}
