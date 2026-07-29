package org.siloserver.silo.android.ui.screens.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pure: should the lobby jump to the synced player? Only once the room is
 * actually playing AND a title is selected. Returns the player route or null.
 *
 * Compares against the real [RoomPhase] enum (not a wire string) — the landed
 * shared model uses lenient enums, see WatchTogetherModels.kt.
 */
fun lobbyPlayerDestinationOrNull(room: RoomSnapshot): String? =
    if (room.phase == RoomPhase.Playing &&
        !room.selectedContentId.isNullOrBlank() &&
        !(room.selfRole == MemberRole.Host && room.memberCount <= 1)
    ) {
        Route.Player(
            contentId = room.selectedContentId!!,
            fileId = room.selectedFileId,
            roomId = room.roomId,
        ).route
    } else {
        null
    }

/**
 * Backs [WatchTogetherLobbyScreen]. Binds the per-room websocket on enter so
 * snapshots/suggestions flow into the state flows, and exposes vote / unvote /
 * host-promote / host-pick / close-room ops.
 *
 * The repository owns the room JWT and reads the active roomId from its own
 * snapshot, so the room-scoped ops take only request/id params (not a roomId).
 * The application-scoped [RoomSession] owns reconnect and survives navigation
 * from this lobby into the synced player.
 */
class WatchTogetherLobbyViewModel(
    private val roomId: String,
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
) : ViewModel() {

    init {
        roomSession.adopt(roomId)
    }

    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.roomSnapshot.value)
    val suggestions: StateFlow<List<Suggestion>> = repository.suggestions
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.suggestions.value)
    val roomClosedReason: StateFlow<String?> = repository.roomClosedReason
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.roomClosedReason.value)
    val errors = repository.errors

    fun vote(suggestionId: String) =
        launchOperation("Could not vote") { repository.vote(suggestionId) }

    fun unvote(suggestionId: String) =
        launchOperation("Could not remove vote") { repository.unvote(suggestionId) }

    fun removeSuggestion(suggestionId: String) =
        launchOperation("Could not remove suggestion") {
            repository.deleteSuggestion(suggestionId)
        }

    /** Host: promote a suggestion to the room selection (moves everyone to the player). */
    fun promote(suggestionId: String) =
        launchOperation("Could not start suggestion") {
            repository.promoteSuggestion(PromoteSuggestionRequest(suggestionId = suggestionId))
        }

    fun closeRoom() = launchOperation("Could not close room") { repository.closeRoom() }

    private fun <T> launchOperation(
        fallback: String,
        operation: suspend () -> ApiResult<T>,
    ) = viewModelScope.launch {
        val result = operation()
        if (result !is ApiResult.Success) {
            repository.reportDeliveryFailure(result.errorMessage(fallback))
        }
    }

    /** Guest/host leave: tear down the WS + clear room state. */
    fun leave() {
        roomSession.depart()
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT reset here — the player binding reuses the same repo connection
        // when we auto-navigate into the synced player. reset() is called
        // explicitly via leave() when the user backs out without playing.
    }
}
