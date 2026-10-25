package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.catalog.matchesLibraryMediaScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.SectionRepository

/** Which row a section page shows. */
data class SectionBrowseArgs(
    val sectionId: String,
    val title: String,
    /** The library page the row sits on; null for a Home row. */
    val libraryId: Int? = null,
    /** A mixed library's Movies or Series view keeps the row's filter on its page. */
    val mediaScope: String? = null,
)

data class SectionBrowseUiState(
    val items: List<BrowseItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** The first page failed; the page shows this with a retry. */
    val error: String? = null,
    /** A later page failed; the items stay and paging can be retried. */
    val loadMoreError: String? = null,
    /** The server answered 404: the row was removed, hidden, or is no longer visible to this profile. */
    val unavailable: Boolean = false,
)

/**
 * A Home or library row's full page ("Explore all"): pages the row through
 * the catalog's `source=section` read. The acting profile is captured on the
 * first page and every later page is read for it, so a profile switch ends
 * the paging instead of mixing two profiles' rows.
 */
class SectionBrowseViewModel(
    private val repository: SectionRepository,
    val args: SectionBrowseArgs,
    /** Platform filter on top of [SectionBrowseArgs.mediaScope] (TV hides reading items). */
    private val itemFilter: (BrowseItem) -> Boolean = { true },
) : ViewModel() {
    private val state = MutableStateFlow(SectionBrowseUiState())
    val uiState: StateFlow<SectionBrowseUiState> = state.asStateFlow()

    private var owner: AuthScopeSnapshot? = null
    private var continuation: CatalogContinuationV2? = null
    private var generation = 0
    private var job: Job? = null

    init {
        load(reset = true)
    }

    fun retry() = load(reset = true)

    fun loadMore() {
        val current = state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore || current.unavailable) return
        if (job?.isActive == true) return
        load(reset = false)
    }

    private fun keep(item: BrowseItem) =
        matchesLibraryMediaScope(item.type, args.mediaScope) && itemFilter(item)

    private fun load(reset: Boolean) {
        if (reset) {
            generation++
            job?.cancel()
            continuation = null
            owner = null
            state.value = SectionBrowseUiState(isLoading = true)
        } else {
            state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        }
        val requestGeneration = generation
        job = viewModelScope.launch {
            val scope = owner ?: repository.captureHomeAuthority()
            if (requestGeneration != generation) return@launch
            if (scope == null) {
                state.update { it.copy(isLoading = false, isLoadingMore = false, error = "Sign in to browse this row.") }
                return@launch
            }
            owner = scope
            var cursor = if (reset) null else continuation
            var skippedPages = 0
            while (true) {
                val result = repository.getSectionCatalogItems(args.libraryId, args.sectionId, scope, cursor)
                if (requestGeneration != generation) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        val page = result.data
                        val added = page.items.filter(::keep)
                        cursor = page.continuation
                        continuation = cursor
                        val more = page.hasMore && cursor != null
                        // A filtered page can come back empty; read on rather
                        // than leave a grid with nothing to scroll toward.
                        if (more && added.isEmpty() && skippedPages++ < MAX_SKIPPED_PAGES) continue
                        state.update { current ->
                            current.copy(
                                items = (if (reset) added else current.items + added).distinctBy { it.contentId },
                                isLoading = false,
                                isLoadingMore = false,
                                hasMore = more,
                                error = null,
                                loadMoreError = null,
                            )
                        }
                        return@launch
                    }
                    is ApiResult.Error, is ApiResult.NetworkError -> {
                        val notFound = result is ApiResult.Error && result.code == 404
                        val message = result.errorMessage("Couldn't load this row.")
                        state.update { current ->
                            when {
                                notFound -> current.copy(isLoading = false, isLoadingMore = false, hasMore = false, unavailable = true)
                                reset -> current.copy(isLoading = false, error = message)
                                else -> current.copy(isLoadingMore = false, loadMoreError = message)
                            }
                        }
                        return@launch
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_SKIPPED_PAGES = 4
    }
}
