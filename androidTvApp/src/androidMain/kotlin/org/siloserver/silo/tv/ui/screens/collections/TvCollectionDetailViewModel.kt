package org.siloserver.silo.tv.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.network.map
import org.siloserver.silo.network.api.CollectionContinuation
import org.siloserver.silo.network.api.CollectionEditor
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.repository.CollectionRepository
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for an individual collection's item grid. Receives `collectionId`
 * and `title` via Koin `parametersOf()` at construction time. Reached only for
 * the user's own collections (the user grid), with deletion available.
 */
class TvCollectionDetailViewModel(
    private val collectionRepository: CollectionRepository,
    private val collectionId: String,
    initialTitle: String,
) : ViewModel() {

    data class UiState(
        val name: String = "",
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val items: List<BrowseItem> = emptyList(),
        val hasMore: Boolean = false,
        val total: Int = 0,
        val error: String? = null,
        val showDeleteConfirm: Boolean = false,
        val isDeleting: Boolean = false,
        val deleteError: String? = null,
        /** Set once the collection is deleted so the screen can pop back. */
        val deleted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(name = initialTitle))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var deleteEditor: CollectionEditor<Collection>? = null
    private val pageSize = 40

    private var pagingJob: kotlinx.coroutines.Job? = null
    private var continuation: CollectionContinuation? = null

    init {
        load(reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.error != null) return
        load(reset = false)
    }

    fun retry() = load(reset = true)

    // --- Delete manageable user collections ---

    fun showDeleteConfirm() {
        viewModelScope.launch {
            when (val result = collectionRepository.getCollection(collectionId)) {
                is ApiResult.Success -> {
                    deleteEditor = result.data
                    _uiState.update { it.copy(showDeleteConfirm = true, deleteError = null) }
                }
                else -> _uiState.update { it.copy(error = result.errorMessage("Could not load collection")) }
            }
        }
    }
    fun hideDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = false, deleteError = null) }

    fun delete() {
        if (_uiState.value.isDeleting) return
        val editor = deleteEditor ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, deleteError = null) }
            when (val r = collectionRepository.deleteCollection(collectionId, editor)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isDeleting = false, showDeleteConfirm = false, deleted = true)
                }
                // Keep the confirm dialog open and surface the reason rather than
                // closing silently (the grid behind may still show items).
                is ApiResult.Error -> _uiState.update {
                    it.copy(isDeleting = false, deleteError = r.message.ifBlank { "Failed to delete" })
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isDeleting = false, deleteError = "Network error")
                }
            }
        }
    }

    private fun load(reset: Boolean) {
        if (reset) continuation = null
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                if (reset) it.copy(isLoading = true, isLoadingMore = false, error = null)
                else it.copy(isLoadingMore = true)
            }
            when (val r = collectionRepository.getItems(collectionId, continuation, pageSize).map { continuation = it.continuation; it.catalog }) {
                is ApiResult.Success -> _uiState.update {
                    val visible = r.data.items.visibleOnTv()
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        items = if (reset) visible else it.items + visible,
                        hasMore = r.data.hasMore,
                        total = r.data.total,
                        error = null,
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = r.message.ifBlank { "Failed to load collection" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = "Network error: ${r.exception.message ?: "unknown"}",
                    )
                }
            }
        }
    }
}
