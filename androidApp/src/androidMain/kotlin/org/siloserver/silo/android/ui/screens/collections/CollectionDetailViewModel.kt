package org.siloserver.silo.android.ui.screens.collections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.network.map
import org.siloserver.silo.network.api.CollectionContinuation
import org.siloserver.silo.network.api.CollectionEditor
import org.siloserver.silo.repository.CollectionRepository
import org.siloserver.silo.repository.SectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val collection: Collection? = null,
    val title: String = "Collection",
    val items: List<BrowseItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val total: Int = 0,
    val showDeleteConfirm: Boolean = false,
    val isDeleting: Boolean = false,
    val deleted: Boolean = false,
    val canManage: Boolean = true,
)

class CollectionDetailViewModel(
    private val collectionRepository: CollectionRepository,
    private val sectionRepository: SectionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    private var pagingJob: kotlinx.coroutines.Job? = null
    private var libraryContinuation: CatalogContinuationV2? = null
    private var continuation: CollectionContinuation? = null
    private var deleteEditor: CollectionEditor<Collection>? = null
    private var collectionId: String = ""
    private val libraryId: Int? = savedStateHandle.get<String>("libraryId")?.toIntOrNull()
    private val pageSize = 40

    fun initialize(id: String) {
        if (collectionId == id) return
        collectionId = id
        loadCollectionDetails()
    }

    private fun loadCollectionDetails() {
        if (libraryId != null) {
            loadLibraryCollectionDetails(libraryId)
            return
        }

        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isLoadingMore = false, error = null, canManage = true) }

            // Load collection metadata from the list
            when (val collectionsResult = collectionRepository.listCollections()) {
                is ApiResult.Success -> {
                    val collection = collectionsResult.data.collections.find { it.id == collectionId }
                    _uiState.update {
                        it.copy(
                            collection = collection,
                            title = collection?.name ?: "Collection",
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    // Non-fatal: we can still show items
                }
            }

            // Load items
            when (val result = collectionRepository.getItems(collectionId, limit = pageSize).map { continuation = it.continuation; it.catalog }) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            items = result.data.items,
                            total = result.data.total,
                            hasMore = result.data.hasMore,
                            isLoading = false,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message.ifBlank { "Failed to load items" })
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Network error. Please check your connection.")
                    }
                }
            }
        }
    }

    private fun loadLibraryCollectionDetails(libraryId: Int) {
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    canManage = false,
                )
            }

            when (val collectionsResult = sectionRepository.getLibraryCollections(libraryId)) {
                is ApiResult.Success -> {
                    val collection = collectionsResult.data.find { it.id == collectionId }
                    _uiState.update {
                        it.copy(
                            title = collection?.name ?: "Collection",
                            total = collection?.itemCount ?: it.total,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    // Non-fatal: item load can still succeed.
                }
            }

            when (
                val result = sectionRepository.getLibraryCollectionItems(
                    collectionId,
                    limit = pageSize,
                ).map { libraryContinuation = it.continuation; it }
            ) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            items = result.data.items,
                            total = result.data.total,
                            hasMore = result.data.hasMore,
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to load collection items" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.isRefreshing || current.isLoadingMore || !current.hasMore || current.error != null) return

        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            // Library paging is migrated separately; personal collections use opaque cursors.
            val result = if (libraryId != null) {
                sectionRepository.getLibraryCollectionItems(
                    collectionId,
                    continuation = libraryContinuation,
                    limit = pageSize,
                ).map { libraryContinuation = it.continuation; it }
            } else {
                collectionRepository.getItems(
                    collectionId,
                    continuation = continuation,
                    limit = pageSize,
                ).map { continuation = it.continuation; it.catalog }
            }
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            items = it.items + result.data.items,
                            total = result.data.total,
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingMore = false, error = result.errorMessage("Could not load more. Refresh to try again.")) }
                }
            }
        }
    }

    fun refresh() {
        if (libraryId != null) {
            loadCollectionDetails()
            return
        }
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, isLoadingMore = false) }
            when (val result = collectionRepository.getItems(collectionId, limit = pageSize).map { continuation = it.continuation; it.catalog }) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            items = result.data.items,
                            total = result.data.total,
                            hasMore = result.data.hasMore,
                            isRefreshing = false,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isRefreshing = false, error = result.errorMessage("Could not reload collection")) }
                }
            }
        }
    }

    fun removeItem(itemId: String) {
        if (libraryId != null) return
        viewModelScope.launch {
            val result = collectionRepository.removeItem(collectionId, itemId)
            if (result !is ApiResult.Success) {
                _uiState.update { it.copy(error = result.errorMessage("Could not remove item")) }
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    items = state.items.filter { it.contentId != itemId },
                    total = (state.total - 1).coerceAtLeast(0),
                )
            }
        }
    }

    fun showDeleteConfirm() {
        if (libraryId != null) return
        viewModelScope.launch {
            when (val result = collectionRepository.getCollection(collectionId)) {
                is ApiResult.Success -> {
                    deleteEditor = result.data
                    _uiState.update { it.copy(showDeleteConfirm = true, error = null) }
                }
                else -> _uiState.update { it.copy(error = result.errorMessage("Could not load collection")) }
            }
        }
    }

    fun hideDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun deleteCollection() {
        if (libraryId != null) return
        val editor = deleteEditor ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true) }
            when (val result = collectionRepository.deleteCollection(collectionId, editor)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isDeleting = false, deleted = true, showDeleteConfirm = false) }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isDeleting = false, error = result.errorMessage("Could not delete collection")) }
                }
            }
        }
    }
}
