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
import org.siloserver.silo.watchtogether.watchPartyErrorMessage
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
        val error: String? = null,
        /** A create whose outcome is unknown can be retried with the same identity. */
        val createRetryable: Boolean = false,
        val destination: WatchPartyDestination? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
        viewModelScope.launch {
            val result = availability.refresh(force)
            _uiState.update { it.copy(availability = result, recent = recents.current()) }
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
            fail("This server can't host a Watch Party. Update the server to host one.")
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
                            error = "Couldn't confirm the party was created. Try again.",
                            createRetryable = true,
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (created.error == "invalid_response") {
                        _uiState.update {
                            it.copy(error = watchPartyErrorMessage(created, CREATE_FAILED), createRetryable = true)
                        }
                    } else {
                        pendingCreate = null
                        fail(watchPartyErrorMessage(created, CREATE_FAILED))
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
            fail("The party is ready, but ${item.title} couldn't be added. Add it from the lobby.")
        }
    }

    /** Join by typed code or pasted invitation. */
    fun join(input: String) {
        when (val invite = parseWatchPartyInvite(input)) {
            null -> fail("Enter the 8-character party code or paste an invitation link.")
            else -> joinInvite(invite)
        }
    }

    fun joinInvite(invite: WatchPartyInvite) {
        if (_uiState.value.busy != null) return
        if (repository.roomSnapshot.value != null) {
            fail("You're already in a Watch Party. Leave it first to join a different one.")
            return
        }
        val request = when (invite) {
            is WatchPartyInvite.Code -> JoinRoomRequest(code = invite.code)
            is WatchPartyInvite.Link -> {
                if (!isCurrentServer(invite.serverUrl)) {
                    fail("This invitation is for a different Silo server. Sign in to that server to join.")
                    return
                }
                JoinRoomRequest(joinToken = invite.token)
            }
        }
        launchJoin(request, "Couldn't join. Check the code and try again.")
    }

    /** Explicit rejoin of the recent party: a fresh join by code. */
    fun rejoinRecent() {
        val recent = _uiState.value.recent ?: return
        if (_uiState.value.busy != null) return
        launchJoin(JoinRoomRequest(code = recent.code), "Couldn't rejoin. The party may have ended.") { failure ->
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
        fallback: String,
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
                            joined is ApiResult.Error && joined.code == 404 -> "No Watch Party uses that code."
                            // None of the ordinary conflicts apply to joining,
                            // so a join 409 is a party that has ended.
                            joined is ApiResult.Error && joined.code == 409 -> "That Watch Party has ended."
                            else -> watchPartyErrorMessage(joined, fallback)
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

    private fun fail(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun consumeDestination() = _uiState.update { it.copy(destination = null) }
    fun clearError() = _uiState.update { it.copy(error = null, createRetryable = false) }

    private companion object {
        const val CREATE_FAILED = "Couldn't create the party. Try again."
    }
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-shot notices for failed actions. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        roomSession.adopt(roomId)
        viewModelScope.launch { availability.refresh() }
        viewModelScope.launch { repository.errors.collect { _messages.tryEmit(it) } }
    }

    fun stage(item: WatchPartyItem) = act("Couldn't add ${item.title}.") {
        repository.stageSelection(SetSelectionRequest(item.contentId, item.fileId, item.libraryId))
    }

    fun start() = act("Couldn't start. Check the room and try again.") { repository.startPlayback() }

    fun setMode(mode: RoomSelectionMode) = act("Couldn't switch modes.") { repository.setSelectionMode(mode) }

    fun setGuestPolicy(policy: GuestControlPolicy) = act("Couldn't change who can play and pause.") {
        repository.updatePolicy(policy)
    }

    fun setLobbyReady(ready: Boolean) {
        viewModelScope.launch {
            if (!repository.setLobbyReady(ready)) _messages.tryEmit("Reconnecting to the party…")
        }
    }

    /** Suggest [item]. After an uncertain outcome, [retrySuggestion] resends the same suggestion. */
    fun suggest(item: WatchPartyItem) {
        val request = AddSuggestionRequest(
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

    private fun sendSuggestion(request: AddSuggestionRequest, item: WatchPartyItem) {
        viewModelScope.launch {
            when (val result = repository.addSuggestion(request)) {
                is ApiResult.Success -> suggestionDraft.value = null
                is ApiResult.NetworkError -> {
                    suggestionDraft.value = request to item
                    _messages.tryEmit("Couldn't confirm your suggestion. Try again.")
                }
                is ApiResult.Error -> {
                    suggestionDraft.value = (request to item).takeIf { result.error == "invalid_response" }
                    _messages.tryEmit(watchPartyErrorMessage(result, "Couldn't suggest ${item.title}."))
                }
            }
        }
    }

    fun vote(suggestion: Suggestion) = act("Couldn't vote.") {
        if (suggestion.votedByMe) repository.unvote(suggestion.id) else repository.vote(suggestion.id)
    }

    fun remove(suggestion: Suggestion) {
        if (!canRemoveSuggestion(repository.roomSnapshot.value, suggestion)) return
        act("Couldn't remove the suggestion.") { repository.deleteSuggestion(suggestion.id) }
    }

    /** Vote rooms: start a suggestion now (host). */
    fun promote(suggestion: Suggestion) = act("Couldn't start ${suggestion.title}.") {
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

    private fun <T> act(fallback: String, operation: suspend () -> ApiResult<T>): Job =
        viewModelScope.launch {
            val result = operation()
            if (result !is ApiResult.Success) _messages.tryEmit(watchPartyErrorMessage(result, fallback))
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
        val error: String? = null,
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
                is ApiResult.Success -> _state.update { it.copy(rows = result.data, rowsLoading = false, error = null) }
                else -> _state.update {
                    it.copy(rowsLoading = false, error = watchPartyErrorMessage(result, "Couldn't load suggestions."))
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
            else -> _state.update { it.copy(searching = false, error = watchPartyErrorMessage(result, "Search failed.")) }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MIN_QUERY = 2
        val PLAYABLE_TYPES = setOf("movie", "series", "episode")
    }
}
