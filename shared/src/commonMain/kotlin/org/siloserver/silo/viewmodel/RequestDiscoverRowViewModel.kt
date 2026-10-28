package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.applyingFollowUpdate
import org.siloserver.silo.model.request.applyingRequestUpdate
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestsRepository

data class RequestDiscoverRowUiState(
    val title: String = "",
    val results: List<RequestMediaResult> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** A first page that failed; a later page's failure keeps what loaded. */
    val error: String? = null,
    /** A later page failed: the grid offers to try it again. */
    val moreFailed: Boolean = false,
    /** The row is gone from the server's layout (turned off or removed). */
    val notFound: Boolean = false,
)

private const val LOAD_FAILED = "Couldn't load this row. Try again in a moment."

/**
 * Every title of one row of the server's Discover layout, read page by page
 * as the viewer scrolls: the row's See all page.
 */
class RequestDiscoverRowViewModel(
    private val repository: RequestsRepository,
    private val rowKey: String,
    title: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RequestDiscoverRowUiState(title = title))
    val uiState: StateFlow<RequestDiscoverRowUiState> = _uiState.asStateFlow()

    private var nextPage: Int? = 1
    private val seen = mutableSetOf<Pair<String, Int>>()

    init {
        loadMore()
        // A request or follow made from a title's detail page shows here too.
        viewModelScope.launch {
            repository.lastUpdate.drop(1).filterNotNull().collect { event ->
                _uiState.update { it.copy(results = it.results.applyingRequestUpdate(event.record)) }
            }
        }
        viewModelScope.launch {
            repository.lastFollow.drop(1).filterNotNull().collect { event ->
                _uiState.update { it.copy(results = it.results.applyingFollowUpdate(event)) }
            }
        }
    }

    fun loadMore() {
        val page = nextPage ?: return
        val state = _uiState.value
        if (state.isLoadingMore || (state.isLoading && page != 1) || state.notFound) return
        _uiState.update {
            it.copy(isLoadingMore = page != 1, isLoading = page == 1 && it.results.isEmpty(), error = null, moreFailed = false)
        }
        viewModelScope.launch {
            when (val result = repository.discoverRow(rowKey, page)) {
                is ApiResult.Success -> {
                    val section = result.data
                    nextPage = nextDiscoverRowPage(section, page)
                    // Charts shift between pages, so a title can come twice.
                    val fresh = section.results.filter { seen.add(it.mediaType to it.tmdbId) }
                    _uiState.update {
                        it.copy(
                            title = section.title.ifBlank { it.title },
                            results = it.results + fresh,
                            isLoading = false,
                            isLoadingMore = false,
                            endReached = nextPage == null,
                        )
                    }
                    // A page of titles already shown adds nothing for the grid
                    // to grow by, so read on rather than wait for a scroll.
                    if (fresh.isEmpty() && nextPage != null) loadMore()
                }
                is ApiResult.Error -> {
                    // A row removed mid-scroll ends where it is.
                    val gone = result.code == 404
                    if (gone && page != 1) nextPage = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            notFound = gone && page == 1,
                            endReached = nextPage == null,
                            error = if (page == 1 && !gone) result.message.ifBlank { LOAD_FAILED } else null,
                            moreFailed = page != 1 && !gone,
                        )
                    }
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = if (page == 1) LOAD_FAILED else null,
                        moreFailed = page != 1,
                    )
                }
            }
        }
    }
}
