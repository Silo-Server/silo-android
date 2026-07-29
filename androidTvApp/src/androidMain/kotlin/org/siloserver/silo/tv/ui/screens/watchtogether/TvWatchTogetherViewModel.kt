package org.siloserver.silo.tv.ui.screens.watchtogether

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.watchtogether.WatchTogetherEntryGateway
import org.siloserver.silo.watchtogether.resumableWatchTogetherRoom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the TV Watch Together entry + join-code dialogs. Hosts a room (create +
 * set this title as the room selection) or joins an existing room by invite code,
 * then surfaces a one-shot [UiState.result] [RoomSnapshot] the detail screen routes
 * on (host-with-selection → synced player, no selection → lobby).
 *
 * The gateway stores the room JWT internally on create/join and reads the
 * active roomId from its own snapshot, so [WatchTogetherEntryGateway.setSelection]
 * takes only the request. createRoom does NOT auto-select, so the host flow must
 * createRoom THEN setSelection(contentId, fileId) so the host lands on the player.
 * The ordering is safe because createRoom synchronously stores the snapshot before
 * returning. Create/join/selection all return a `{room, room_access_token}`
 * wrapper; the snapshot lives at `.data.room`.
 *
 * Mirrors the mobile `WatchTogetherEntryViewModel` host()/joinByCode() shape.
 */
class TvWatchTogetherViewModel(
    private val gateway: WatchTogetherEntryGateway,
) : ViewModel() {

    data class UiState(
        val isBusy: Boolean = false,
        val error: String? = null,
        /** Set once a create/join resolves — the screen observes this and navigates. */
        val result: RoomSnapshot? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val currentRoom: StateFlow<RoomSnapshot?> = gateway.roomSnapshot
        .map(::resumableWatchTogetherRoom)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            resumableWatchTogetherRoom(gateway.roomSnapshot.value),
        )

    /** Host flow: create a room with this title pre-selected as the room selection. */
    fun createRoom(
        contentId: String,
        fileId: Int?,
        selectionMode: RoomSelectionMode = RoomSelectionMode.HostPick,
    ) {
        if (selectionMode == RoomSelectionMode.Vote) {
            createEmptyVoteRoom()
            return
        }
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (
                val created = gateway.createRoom(
                    CreateRoomRequest(selectionMode = selectionMode.wire),
                )
            ) {
                is ApiResult.Success -> {
                    // createRoom does NOT auto-select; set this title as the room
                    // selection so the host lands on the synced player. The gateway
                    // already stored the snapshot synchronously, so setSelection
                    // reads the right roomId/token.
                    if (created.data.room.selectedContentId.isNullOrBlank()) {
                        when (
                            val sel = gateway.setSelection(
                                SetSelectionRequest(contentId = contentId, fileId = fileId),
                            )
                        ) {
                            is ApiResult.Success -> finish(sel.data.room)
                            is ApiResult.Error, is ApiResult.NetworkError ->
                                fail(sel.errorMessage("Failed to set selection"))
                        }
                    } else {
                        finish(created.data.room)
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError ->
                    fail(created.errorMessage("Failed to create room"))
            }
        }
    }

    /** Host flow for the menu action: create an empty vote room, then enter its lobby. */
    fun createEmptyVoteRoom() {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (
                val created = gateway.createRoom(
                    CreateRoomRequest(selectionMode = RoomSelectionMode.Vote.wire),
                )
            ) {
                is ApiResult.Success -> finish(created.data.room)
                is ApiResult.Error, is ApiResult.NetworkError ->
                    fail(created.errorMessage("Failed to create room"))
            }
        }
    }

    /** Resume the valid room snapshot already held by the process session. */
    fun resumeCurrentRoom() {
        if (_uiState.value.isBusy) return
        val room = resumableWatchTogetherRoom(gateway.roomSnapshot.value)
        if (room == null) {
            fail("Current room is no longer available")
        } else {
            finish(room)
        }
    }

    /** Join flow: resolve an invite code; the screen routes to player or lobby. */
    fun joinRoom(code: String) {
        val trimmed = code.trim().uppercase()
        if (_uiState.value.isBusy || trimmed.isBlank()) return
        _uiState.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val joined = gateway.joinRoom(JoinRoomRequest(code = trimmed))) {
                is ApiResult.Success -> finish(joined.data.room)
                is ApiResult.Error, is ApiResult.NetworkError ->
                    fail(joined.errorMessage("Could not join — check the code"))
            }
        }
    }

    private fun finish(room: RoomSnapshot) {
        _uiState.update { it.copy(isBusy = false, error = null, result = room) }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(isBusy = false, error = message) }
    }

    fun consumeResult() = _uiState.update { it.copy(result = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
