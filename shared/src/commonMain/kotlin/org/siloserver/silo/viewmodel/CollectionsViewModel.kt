package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.model.personal.CollectionGroup
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CollectionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A flat rendering item — either a named group header or the anonymous
 * "Ungrouped" header — paired with the collections it contains. Mirrors
 * the buildGroupedSections helper used by the web frontend.
 */
data class CollectionSection(
    /** Null when this section is the anonymous "Ungrouped" bucket. */
    val groupId: String?,
    val name: String,
    val collections: List<Collection>,
)

data class CollectionsUiState(
    val collections: List<Collection> = emptyList(),
    val groups: List<CollectionGroup> = emptyList(),
    /** Cached output of [buildCollectionSections]; refreshed whenever
     *  [collections] or [groups] change so Compose reads are constant-time. */
    val sections: List<CollectionSection> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Builds the same ordered list of sections that the web frontend
 * renders: named groups sorted by sort_order, followed by a single
 * "Ungrouped" bucket. Hidden sections with no collections are kept so
 * the UI can still render an empty-state target.
 */
internal fun buildCollectionSections(
    collections: List<Collection>,
    groups: List<CollectionGroup>,
): List<CollectionSection> {
    val byGroup = collections.groupBy { it.groupId }
    val sorted = groups.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    val named = sorted.map { g ->
        CollectionSection(
            groupId = g.id,
            name = g.name,
            collections = (byGroup[g.id] ?: emptyList())
                .sortedBy { it.sortOrder },
        )
    }
    val ungrouped = (byGroup[null] ?: emptyList()).sortedBy { it.sortOrder }
    // Hide an empty Ungrouped bucket whenever at least one named group
    // exists — matches both the iOS `rebuildSections` and the web app.
    if (ungrouped.isEmpty() && named.isNotEmpty()) return named
    return named + CollectionSection(
        groupId = null,
        name = "Ungrouped",
        collections = ungrouped,
    )
}

class CollectionsViewModel(
    private val collectionRepository: CollectionRepository,
) : ViewModel() {
    /** Access changes this ViewModel has applied, kept while its screen is away. */
    val accessChanges = org.siloserver.silo.network.AccessChangeCursor()

    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    // The newest load is the only one that publishes: a refresh after an
    // access change replaces a load still waiting on the older policy.
    // Declared before init, which starts the first load.
    private var loadJob: Job? = null

    init {
        loadCollections()
    }

    fun loadCollections() = load(refreshing = false)

    fun refresh() = load(refreshing = true)

    private fun load(refreshing: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { if (refreshing) it.copy(isRefreshing = true) else it.copy(isLoading = true, error = null) }
            val result = collectionRepository.listCollections()
            ensureActive()
            applyResult(result, refreshing)
        }
    }

    private fun applyResult(
        result: ApiResult<org.siloserver.silo.model.personal.CollectionsResponse>,
        refreshing: Boolean,
    ) {
        when (result) {
            is ApiResult.Success -> _uiState.update {
                it.copy(
                    collections = result.data.collections,
                    groups = result.data.groups,
                    sections = buildCollectionSections(result.data.collections, result.data.groups),
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                )
            }
            is ApiResult.Error -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = if (!refreshing) result.message.ifBlank { "Failed to load collections" } else it.error,
                )
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = if (!refreshing) "Network error. Please check your connection." else it.error,
                )
            }
        }
    }
}
