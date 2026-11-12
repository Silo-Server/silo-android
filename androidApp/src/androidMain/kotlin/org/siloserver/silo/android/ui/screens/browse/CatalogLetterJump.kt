package org.siloserver.silo.android.ui.screens.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.siloserver.silo.catalog.filter.CatalogFilterQueryBuilder
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogLettersV2
import org.siloserver.silo.repository.CatalogRepository

/** A title-ordered list the letter index can describe: its scope, rules and order. */
data class CatalogLetterQuery(
    val libraryId: Int?,
    val mediaType: String?,
    val order: String,
    val queryGroups: List<CatalogQueryGroup>,
    val match: String?,
) {
    companion object {
        /** The list a grid pages for [filters], in [order]; the letter index counts the same one. */
        fun of(libraryId: Int?, mediaType: String?, order: String, filters: CatalogFilterState) = CatalogLetterQuery(
            libraryId = libraryId,
            mediaType = mediaType,
            order = order,
            queryGroups = CatalogFilterQueryBuilder.buildGroups(filters),
            match = CatalogFilterQueryBuilder.matchParam(filters).takeIf { filters.hasActiveFilters },
        )
    }
}

/**
 * What a grid needs to show a whole title-ordered list and jump in it: the
 * letter index and a pager that reads the list by position. [query] names
 * the list; a new one means a new list.
 */
class CatalogLetterJumpState(
    val query: CatalogLetterQuery,
    val index: CatalogLettersV2,
    val pager: CatalogPositionPager,
) {
    /** Letters with at least one title. */
    val availableLetters: Set<String> = index.letters.mapTo(HashSet()) { it.letter }
}

/**
 * The letter jump of a title-ordered library grid on phone and tablet, shared
 * by the Browse screen and the Libraries Browse tab.
 *
 * [reset] starts each list: it shows the letter index read for the same list
 * earlier this session at once, and reads a fresh one beside the grid's first
 * page. A server without a letter index (404, 405, 501) gets no rail for the
 * rest of the screen's life.
 */
class CatalogLetterJump(
    private val scope: CoroutineScope,
    private val repository: CatalogRepository,
    /** Applies local watched/favourite state to titles read by position. */
    private val overlay: suspend (List<BrowseItem>) -> List<BrowseItem> = { it },
    private val retryDelayMillis: Long = 2_000L,
) {
    private val _state = MutableStateFlow<CatalogLetterJumpState?>(null)

    /** Null while the list is not in title order, before an index exists, and from a server without one. */
    val state: StateFlow<CatalogLetterJumpState?> = _state.asStateFlow()

    private var firstPage: List<BrowseItem>? = null
    /**
     * The list's `window_cursor`, from its first page or first positional
     * read. Sent with each seek so the server reuses the ordering boundaries
     * it recorded instead of scanning up to the position every time.
     */
    private var windowCursor: String? = null
    private var lettersJob: Job? = null
    /** Bumps on every reset; a read only answers for the reset that started it. */
    private var generation = 0
    private var serverHasIndex = true

    /**
     * The grid starts [next] from the top, or a list the index can't describe
     * when null. Drops the old pager and its reads either way.
     */
    fun reset(next: CatalogLetterQuery?) {
        lettersJob?.cancel()
        val myGeneration = ++generation
        firstPage = null
        windowCursor = null
        drop()
        if (next == null || !serverHasIndex) return
        lettersJob = scope.launch {
            val cached = repository.cachedCatalogLetters(next.libraryId, next.mediaType, next.order, next.queryGroups, next.match)
            if (myGeneration != generation) return@launch
            if (cached != null) install(next, cached)
            val result = repository.catalogLetters(next.libraryId, next.mediaType, next.order, next.queryGroups, next.match)
            if (myGeneration != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val index = result.data
                    if (index == null) {
                        serverHasIndex = false
                        drop()
                    } else {
                        install(next, index)
                    }
                }
                // Keep the index already shown; the next reset asks again.
                else -> Unit
            }
        }
    }

    /** The grid's first page arrived; the pager need not read it again. */
    fun seed(items: List<BrowseItem>, windowCursor: String?) {
        firstPage = items
        if (windowCursor != null) this.windowCursor = windowCursor
        _state.value?.pager?.seed(items)
    }

    /** The finger is on [letter]: read where a jump there would land. */
    fun prepareJump(letter: String) {
        val current = _state.value ?: return
        val position = current.index.position(letter) ?: return
        current.pager.prepareLanding(position)
    }

    /**
     * List position of [letter]'s first title, for the grid to scroll to.
     * Waits up to [patienceMillis] for the titles there, so the grid lands on
     * cards rather than placeholders when the server is quick enough.
     */
    suspend fun positionForJump(letter: String, patienceMillis: Long = JUMP_PATIENCE_MILLIS): Int? {
        val current = _state.value ?: return null
        val position = current.index.position(letter) ?: return null
        current.pager.awaitLanding(position, patienceMillis)
        // A list that changed meanwhile has other positions.
        return position.takeIf { _state.value?.pager === current.pager }
    }

    private fun install(query: CatalogLetterQuery, index: CatalogLettersV2) {
        if (index.total <= 0) return drop()
        val current = _state.value
        if (current != null && current.query == query && current.pager.total == index.total) {
            if (current.index != index) _state.value = CatalogLetterJumpState(query, index, current.pager)
            return
        }
        current?.pager?.cancel()
        val readGeneration = generation
        val pager = CatalogPositionPager(index.total, scope, retryDelayMillis) { start, limit ->
            readPage(query, start, limit, readGeneration)
        }
        firstPage?.let(pager::seed)
        _state.value = CatalogLetterJumpState(query, index, pager)
    }

    private suspend fun readPage(query: CatalogLetterQuery, start: Int, limit: Int, readGeneration: Int): List<BrowseItem>? {
        val window = windowCursor
        val result = repository.browse(
            libraryId = query.libraryId,
            mediaType = query.mediaType,
            sort = TITLE_SORT,
            order = query.order,
            limit = limit,
            queryGroups = query.queryGroups,
            match = query.match,
            seek = start,
            windowCursor = window,
            // The letter index already gave the exact total.
            skipTotal = true,
        )
        val current = readGeneration == generation
        return when (result) {
            is ApiResult.Success -> {
                if (current && windowCursor == null) windowCursor = result.data.windowCursor
                overlay(result.data.items)
            }
            else -> {
                // A cursor the server refuses (expired, or another viewer's)
                // is dropped; the retry seeks without it.
                if (current && window != null && windowCursor == window) windowCursor = null
                null
            }
        }
    }

    private fun drop() {
        _state.value?.pager?.cancel()
        _state.value = null
    }

    companion object {
        const val TITLE_SORT = "title"
        /** How long a jump waits for the titles it lands on. */
        const val JUMP_PATIENCE_MILLIS = 300L
        /** First-page size while the grid is in title order: one whole pager page. */
        const val TITLE_FIRST_PAGE_SIZE = CatalogPositionPager.PAGE_SIZE
    }
}
