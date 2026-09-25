package org.siloserver.silo.android.ui.screens.watchtogether

import org.siloserver.silo.watchtogether.newWatchPartyId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.WatchTogetherRepository

class SuggestToRoomViewModel(
    private val repository: WatchTogetherRepository,
) : ViewModel() {
    data class UiState(
        val isBusy: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot

    fun suggest(
        contentId: String,
        contentType: String,
        title: String,
        subtitle: String?,
        posterUrl: String?,
    ) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = repository.addSuggestion(
                AddSuggestionRequest(
                    suggestionId = newWatchPartyId(),
                    contentId = contentId,
                    contentType = contentType,
                    title = title,
                    subtitle = subtitle,
                    posterUrl = posterUrl,
                ),
            )
            when (result) {
                is ApiResult.Success ->
                    _uiState.update { it.copy(isBusy = false, notice = "Suggested to the room") }
                is ApiResult.Error, is ApiResult.NetworkError ->
                    _uiState.update {
                        it.copy(isBusy = false, error = result.errorMessage("Could not suggest that"))
                    }
            }
        }
    }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
