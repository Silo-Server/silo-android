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
    val isLoading: Boolean = false,
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
        if (state.isLoading || state.isLoadingMore || state.notFound) return
        // With nothing shown yet (a first page, or empty pages before it),
        // loading and failing are the screen's; after that, the grid's.
        _uiState.update {
            val nothingShown = it.results.isEmpty()
            it.copy(isLoadingMore = !nothingShown, isLoading = nothingShown, error = null, moreFailed = false)
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
                    val nothingShown = _uiState.value.results.isEmpty()
                    if (gone && !nothingShown) nextPage = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            notFound = gone && nothingShown,
                            endReached = nextPage == null,
                            error = if (nothingShown && !gone) result.message.ifBlank { LOAD_FAILED } else null,
                            moreFailed = !nothingShown && !gone,
                        )
                    }
                }
                is ApiResult.NetworkError -> _uiState.update {
                    val nothingShown = it.results.isEmpty()
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = if (nothingShown) LOAD_FAILED else null,
                        moreFailed = !nothingShown,
                    )
                }
            }
        }
    }
}
