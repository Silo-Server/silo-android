package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.withWatched
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.CatalogRepository

data class BoxSetUiState(
    val detail: CatalogBoxSetDetail? = null,
    val isLoading: Boolean = true,
    /**
     * The library has box sets off, or the viewer can see too few of its
     * movies there (the server answers 404 for both).
     */
    val unavailable: Boolean = false,
    val error: String? = null,
)

/**
 * One movie box set in one library: the movies TMDB lists in one collection
 * that the viewer can see there, in release order. Shared by phone and TV.
 */
class BoxSetViewModel(
    private val catalogRepository: CatalogRepository,
    val boxSetId: String,
    val libraryId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BoxSetUiState())
    val uiState: StateFlow<BoxSetUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** The screen's first resume is the opening itself, which [load] covers. */
    private var openedResumeSeen = false

    /**
     * Each movie as it was before its latest watched change from a card, so a
     * failed write can put it back ([onMovieWatchedWriteFailed]).
     */
    private val beforeWatchedChange = mutableMapOf<String, BrowseItem>()

    /**
     * Each movie's latest watched change from a card, numbered in order. A
     * read that started before a change answers with the state from before
     * it, so the changes newer than the read are put back on its answer
     * ([withChangesSince]). A change whose write failed is dropped here when
     * the movie is put back.
     */
    private val watchedChanges = mutableMapOf<String, WatchedChange>()
    private var lastWatchedChange = 0L

    private data class WatchedChange(val number: Long, val watched: Boolean)

    init { load() }

    /** Loads the box set again, showing the loading state. */
    fun retry() = load()

    /**
     * Re-reads the box set after the viewer comes back to it, say from playing
     * one of its movies, so watched state and the play target follow. The page
     * stays as it is while it loads and when the read fails.
     */
    fun refresh() {
        // A read in flight already answers with the latest state.
        if (loadJob?.isActive == true) return
        if (_uiState.value.detail == null) return load()
        val readStart = lastWatchedChange
        loadJob = viewModelScope.launch {
            when (val result = catalogRepository.getBoxSet(boxSetId, libraryId)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(detail = result.data.withChangesSince(readStart), unavailable = false, error = null)
                }
                // The box set went away (box sets turned off, access narrowed).
                is ApiResult.Error -> if (result.code == 404) {
                    _uiState.update { BoxSetUiState(isLoading = false, unavailable = true) }
                }
                is ApiResult.NetworkError -> Unit
            }
        }
    }

    /**
     * The screen came (back) into view. The first time is the opening, which
     * the initial load already covers; every later time is a return, say from
     * playing one of the movies, and re-reads the box set ([refresh]).
     */
    fun onScreenResumed() {
        if (!openedResumeSeen) {
            openedResumeSeen = true
            return
        }
        refresh()
    }

    /**
     * The viewer marked one of the movies watched or unwatched from its card,
     * so the watched count and the play target follow at once rather than on
     * the next [refresh], which still settles them from the server.
     */
    fun onMovieWatchedChanged(contentId: String, watched: Boolean) {
        val before = _uiState.value.detail?.items?.firstOrNull { it.contentId == contentId } ?: return
        beforeWatchedChange[contentId] = before
        watchedChanges[contentId] = WatchedChange(++lastWatchedChange, watched)
        replaceMovie(before.withWatched(watched))
    }

    /**
     * The latest watched write for [contentId] failed. The movie goes back to
     * how it was before that change, watched state and resume position both,
     * which also puts its card back: the card follows the movie's state. This
     * holds offline, where a re-read fails too; online the re-read then
     * settles everything from the server.
     */
    fun onMovieWatchedWriteFailed(contentId: String) {
        watchedChanges.remove(contentId)
        beforeWatchedChange.remove(contentId)?.let(::replaceMovie)
        refresh()
    }

    /** This answer with the watched changes made after its read started put back on it. */
    private fun CatalogBoxSetDetail.withChangesSince(readStart: Long): CatalogBoxSetDetail {
        val newer = watchedChanges.filterValues { it.number > readStart }
        if (newer.isEmpty()) return this
        val movies = items.map { movie -> newer[movie.contentId]?.let { movie.withWatched(it.watched) } ?: movie }
        return copy(items = movies, boxSet = boxSet.copy(playedCount = movies.count { it.userState?.played == true }))
    }

    private fun replaceMovie(movie: BrowseItem) {
        _uiState.update { state ->
            val detail = state.detail ?: return@update state
            if (detail.items.none { it.contentId == movie.contentId }) return@update state
            val items = detail.items.map { if (it.contentId == movie.contentId) movie else it }
            state.copy(
                detail = detail.copy(
                    items = items,
                    boxSet = detail.boxSet.copy(playedCount = items.count { it.userState?.played == true }),
                ),
            )
        }
    }

    private fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null, unavailable = false) }
        loadJob = viewModelScope.launch {
            val next = when (val result = catalogRepository.getBoxSet(boxSetId, libraryId)) {
                is ApiResult.Success -> BoxSetUiState(detail = result.data, isLoading = false)
                is ApiResult.Error -> if (result.code == 404) {
                    BoxSetUiState(isLoading = false, unavailable = true)
                } else {
                    BoxSetUiState(isLoading = false, error = result.message.ifBlank { "Couldn't load this box set." })
                }
                is ApiResult.NetworkError -> BoxSetUiState(isLoading = false, error = result.errorMessage("Couldn't load this box set."))
            }
            _uiState.value = next
        }
    }
}
