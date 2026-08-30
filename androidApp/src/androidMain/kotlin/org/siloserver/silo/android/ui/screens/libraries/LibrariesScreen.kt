package org.siloserver.silo.android.ui.screens.libraries

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.MediaRowsSkeleton
import org.siloserver.silo.android.ui.components.PosterGridSkeleton
import org.siloserver.silo.android.ui.components.TabTopBarActions
import org.siloserver.silo.android.ui.components.SortFilterControlsRow
import org.siloserver.silo.android.ui.components.SortMenuOption
import org.siloserver.silo.android.ui.components.topBarGlass
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import org.siloserver.silo.android.ui.screens.browse.BrowsePrefsStore
import org.siloserver.silo.android.ui.screens.browse.CatalogGrid
import org.siloserver.silo.android.ui.screens.browse.CatalogViewDensity
import org.siloserver.silo.android.ui.screens.browse.FilterSheet
import org.siloserver.silo.android.ui.screens.browse.facetValueLabel
import org.siloserver.silo.android.ui.screens.browse.normalizeCatalogNamePrefix
import org.siloserver.silo.catalog.filter.BrowseFacetMediaType
import org.siloserver.silo.catalog.filter.CatalogFacet
import org.siloserver.silo.catalog.filter.CatalogFilterQueryBuilder
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.diagnostics.DiagnosticsListLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.diagnostics.DiagnosticsListSurface
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.android.ui.screens.home.HomeSectionRow
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.android.ui.util.formatCardDate
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.SectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrariesSubtab {
    Recommended,
    Browse,
    Collections,
}

enum class LibraryBrowseSort(
    val label: String,
    val sortField: String,
    val sortOrder: String,
) {
    RecentlyAdded("Recently Added", "added_at", "desc"),
    Title("Title", "title", "asc"),
    ReleaseDate("Release Date", "release_date", "desc");

    companion object {
        /**
         * The sort rides the persisted [CatalogFilterState] (same as
         * BrowseViewModel), so restoring saved browse prefs also restores the
         * chip label. [CatalogFilterState]'s own defaults are exactly
         * [RecentlyAdded]'s pair, so a state saved before the sort travelled
         * with it — or one naming a field this client does not offer — falls
         * back to [RecentlyAdded].
         */
        fun fromFilterState(state: CatalogFilterState): LibraryBrowseSort =
            entries.firstOrNull { it.sortField == state.sort && it.sortOrder == state.order }
                ?: RecentlyAdded
    }
}

data class LibrariesUiState(
    val isLoadingLibraries: Boolean = true,
    val libraries: List<UserLibrary> = emptyList(),
    val selectedLibraryId: Int? = null,
    val selectedTab: LibrariesSubtab = LibrariesSubtab.Recommended,
    val isLoadingSections: Boolean = false,
    val sections: List<ResolvedSection> = emptyList(),
    val sectionsError: String? = null,
    val isLoadingCatalog: Boolean = false,
    val isLoadingMoreCatalog: Boolean = false,
    val catalogItems: List<BrowseItem> = emptyList(),
    val catalogTotal: Int = 0,
    val catalogHasMore: Boolean = false,
    // Full filter model shared with the standalone Browse screen: facets
    // (genre/decade/rating/studio/language/series/...), match-all/any, and the
    // available-filter vocabulary from the server. Genre is a Categories facet
    // here — no more inline genre chip rail (L3).
    val filterState: CatalogFilterState = CatalogFilterState(),
    val availableFilters: CatalogFiltersResponse? = null,
    val browseMediaType: BrowseFacetMediaType = BrowseFacetMediaType.Video,
    val preserveFilters: Boolean = true,
    val selectedNamePrefix: String? = null,
    val catalogDensity: CatalogViewDensity = CatalogViewDensity.Normal,
    val browseSort: LibraryBrowseSort = LibraryBrowseSort.RecentlyAdded,
    val catalogError: String? = null,
    val isLoadingCollections: Boolean = false,
    val collections: List<LibraryCollection> = emptyList(),
    val collectionsError: String? = null,
    val librariesError: String? = null,
)

class LibrariesViewModel(
    private val personalDataRepository: PersonalDataRepository,
    private val sectionRepository: SectionRepository,
    private val catalogRepository: CatalogRepository,
    private val userItemState: org.siloserver.silo.repository.port.UserItemStatePort =
        org.siloserver.silo.repository.port.NoOpUserItemStatePort,
    private val playerSettingsStore: org.siloserver.silo.common.settings.PlayerSettingsStore? = null,
    private val browsePrefs: BrowsePrefsStore? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibrariesUiState())
    val uiState: StateFlow<LibrariesUiState> = _uiState.asStateFlow()
    // iOS AppNavPreferences.showAudiobooks parity: audiobook libraries are
    // hidden from the picker unless the user opts in under Settings > Library.
    private var showAudiobooks = false
    private var recommendedLoadedLibraryId: Int? = null
    private var browseLoadedLibraryId: Int? = null
    private var collectionsLoadedLibraryId: Int? = null
    private var recommendedRequestGeneration = 0L
    private var catalogRequestGeneration = 0L
    private var catalogQueryGeneration = 0L
    private var collectionsRequestGeneration = 0L
    private val pageSize = 42

    init {
        playerSettingsStore?.showAudiobooksFlow
            ?.onEach { show ->
                if (show != showAudiobooks) {
                    showAudiobooks = show
                    refresh()
                }
            }
            ?.launchIn(viewModelScope)
        refresh()
    }

    private fun isHiddenAudiobookLibrary(library: UserLibrary): Boolean =
        !showAudiobooks && library.type.trim().lowercase() in setOf("audiobook", "audiobooks")

    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingLibraries = true,
                    librariesError = null,
                )
            }

            when (val result = personalDataRepository.listUserLibraries()) {
                is ApiResult.Success -> {
                    // Libraries is the unified hub for every library type
                    // (video / audio / reading). The selector lists them all
                    // and ItemDetail routes each item to the right player or
                    // reader by its type.
                    val libraries = result.data
                        .filterNot(::isHiddenAudiobookLibrary)
                        .sortedBy { library -> library.sortOrder }
                    val previousLibraryId = _uiState.value.selectedLibraryId
                    val selectedLibraryId = previousLibraryId
                        ?.takeIf { currentId -> libraries.any { it.id == currentId } }
                        ?: libraries.firstOrNull()?.id
                    // When this resolves to a *new* library (first load, or the
                    // prior one vanished), restore its saved browse filter +
                    // preserve state — same as selectLibrary — so opening Browse
                    // on the default library isn't an unfiltered grid for a
                    // profile with saved filters. An unchanged selection keeps
                    // whatever filters are already active.
                    val restoreBrowsePrefs =
                        selectedLibraryId != null && selectedLibraryId != previousLibraryId
                    // Null when there is nothing to restore, so the branches
                    // below keep the live state untouched.
                    val restoredFilterState = if (restoreBrowsePrefs) {
                        browsePrefs?.savedState(selectedLibraryId) ?: CatalogFilterState()
                    } else {
                        null
                    }

                    _uiState.update {
                        it.copy(
                            isLoadingLibraries = false,
                            libraries = libraries,
                            selectedLibraryId = selectedLibraryId,
                            librariesError = null,
                            filterState = restoredFilterState ?: it.filterState,
                            // The sort lives inside the persisted filter state,
                            // so derive the chip from what was restored.
                            browseSort = restoredFilterState
                                ?.let(LibraryBrowseSort::fromFilterState)
                                ?: it.browseSort,
                            preserveFilters = if (restoreBrowsePrefs)
                                (browsePrefs?.preserveEnabled(selectedLibraryId) ?: true)
                            else it.preserveFilters,
                        )
                    }

                    if (selectedLibraryId != null) {
                        loadCurrentTab(selectedLibraryId, force = true)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingLibraries = false,
                            libraries = emptyList(),
                            sections = emptyList(),
                            librariesError = result.message.ifBlank { "Failed to load libraries" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoadingLibraries = false,
                            libraries = emptyList(),
                            sections = emptyList(),
                            librariesError = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }

    fun selectLibrary(libraryId: Int) {
        if (_uiState.value.selectedLibraryId == libraryId) return
        recommendedLoadedLibraryId = null
        browseLoadedLibraryId = null
        collectionsLoadedLibraryId = null
        // Restore this library's persisted filter/sort state (iOS parity) so a
        // preserved selection doesn't flash the unfiltered grid; default to a
        // clean filter — and therefore RecentlyAdded — when nothing is saved.
        val restoredFilterState = browsePrefs?.savedState(libraryId) ?: CatalogFilterState()
        _uiState.update {
            it.copy(
                selectedLibraryId = libraryId,
                sections = emptyList(),
                sectionsError = null,
                catalogItems = emptyList(),
                catalogTotal = 0,
                catalogHasMore = false,
                filterState = restoredFilterState,
                browseSort = LibraryBrowseSort.fromFilterState(restoredFilterState),
                availableFilters = null,
                preserveFilters = browsePrefs?.preserveEnabled(libraryId) ?: true,
                selectedNamePrefix = null,
                catalogError = null,
                collections = emptyList(),
                collectionsError = null,
            )
        }
        loadCurrentTab(libraryId, force = true)
    }

    fun selectTab(tab: LibrariesSubtab) {
        if (_uiState.value.selectedTab == tab) return
        _uiState.update { it.copy(selectedTab = tab) }
        _uiState.value.selectedLibraryId?.let { loadCurrentTab(it, force = false) }
    }

    /** Apply a new facet/match filter selection, persist it (when preserve is
     *  on), and reload the catalog. Mirrors BrowseViewModel.applyFilterState. */
    fun applyFilterState(state: CatalogFilterState) {
        val current = _uiState.value.filterState
        // [selectBrowseSort] owns sort/order. Callers derive `state` from a
        // composition snapshot that can be a frame stale — the Reset control
        // changes the sort and clears the facets in the same frame — so take
        // only the facet/match parts and keep the sort already committed here.
        val reconciled = state.copy(sort = current.sort, order = current.order)
        if (reconciled == current) return
        _uiState.update {
            it.copy(
                filterState = reconciled,
                catalogItems = emptyList(),
                catalogTotal = 0,
                catalogHasMore = false,
            )
        }
        browsePrefs?.saveState(_uiState.value.selectedLibraryId, reconciled)
        _uiState.value.selectedLibraryId?.let { loadCatalog(it, reset = true, force = true) }
    }

    fun setPreserveFilters(enabled: Boolean) {
        val libraryId = _uiState.value.selectedLibraryId
        browsePrefs?.setPreserveEnabled(libraryId, enabled)
        _uiState.update { it.copy(preserveFilters = enabled) }
        if (enabled) browsePrefs?.saveState(libraryId, _uiState.value.filterState)
    }

    fun selectBrowseSort(sort: LibraryBrowseSort) {
        // The sort rides the persisted filter state so "Preserve sort & filters"
        // actually restores it, instead of the chips coming back while the sort
        // snaps to Recently Added.
        val nextFilterState = _uiState.value.filterState.copy(
            sort = sort.sortField,
            order = sort.sortOrder,
        )
        _uiState.update {
            it.copy(
                browseSort = sort,
                filterState = nextFilterState,
                catalogItems = emptyList(),
                catalogTotal = 0,
                catalogHasMore = false,
            )
        }
        browsePrefs?.saveState(_uiState.value.selectedLibraryId, nextFilterState)
        _uiState.value.selectedLibraryId?.let { loadCatalog(it, reset = true, force = true) }
    }

    fun selectNamePrefix(prefix: String?) {
        val normalizedPrefix = normalizeCatalogNamePrefix(prefix)
        if (_uiState.value.selectedNamePrefix == normalizedPrefix) return
        _uiState.update {
            it.copy(
                selectedNamePrefix = normalizedPrefix,
                catalogItems = emptyList(),
                catalogTotal = 0,
                catalogHasMore = false,
            )
        }
        _uiState.value.selectedLibraryId?.let { loadCatalog(it, reset = true, force = true) }
    }

    fun selectViewDensity(density: CatalogViewDensity) {
        if (_uiState.value.catalogDensity == density) return
        _uiState.update { it.copy(catalogDensity = density) }
    }

    fun loadMoreCatalog() {
        val state = _uiState.value
        val libraryId = state.selectedLibraryId ?: return
        if (state.isLoadingCatalog || state.isLoadingMoreCatalog || !state.catalogHasMore) return
        loadCatalog(libraryId, reset = false, force = true)
    }

    private fun loadCurrentTab(libraryId: Int, force: Boolean) {
        when (_uiState.value.selectedTab) {
            LibrariesSubtab.Recommended -> loadRecommended(libraryId, force)
            LibrariesSubtab.Browse -> loadCatalog(libraryId, reset = true, force = force)
            LibrariesSubtab.Collections -> loadCollections(libraryId, force)
        }
    }

    fun showBrowseFromRecommended() {
        _uiState.update { it.copy(selectedTab = LibrariesSubtab.Browse) }
        _uiState.value.selectedLibraryId?.let { loadCatalog(it, reset = true, force = false) }
    }

    fun retryCurrentTab() {
        _uiState.value.selectedLibraryId?.let { loadCurrentTab(it, force = true) }
    }

    private fun loadRecommended(libraryId: Int, force: Boolean) {
        if (!force && recommendedLoadedLibraryId == libraryId) return
        recommendedLoadedLibraryId = libraryId
        val requestGeneration = ++recommendedRequestGeneration
        viewModelScope.launch {
            if (!isRecommendedRequestCurrent(requestGeneration, libraryId)) return@launch
            _uiState.update {
                if (isRecommendedRequestCurrent(requestGeneration, libraryId, it)) {
                    it.copy(
                        isLoadingSections = true,
                        sectionsError = null,
                        sections = emptyList(),
                    )
                } else {
                    it
                }
            }

            when (val result = sectionRepository.getLibrarySections(libraryId)) {
                is ApiResult.Success -> {
                    if (!isRecommendedRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isRecommendedRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingSections = false,
                                sections = result.data.sections.filter { section -> section.items.isNotEmpty() },
                                sectionsError = null,
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.Error -> {
                    if (!isRecommendedRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isRecommendedRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingSections = false,
                                sections = emptyList(),
                                sectionsError = result.message.ifBlank { "Failed to load recommendations" },
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    if (!isRecommendedRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isRecommendedRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingSections = false,
                                sections = emptyList(),
                                sectionsError = "Network error: ${result.exception.message ?: "unknown"}",
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private fun loadCatalog(libraryId: Int, reset: Boolean, force: Boolean) {
        if (reset && !force && browseLoadedLibraryId == libraryId && _uiState.value.catalogItems.isNotEmpty()) {
            return
        }
        browseLoadedLibraryId = libraryId
        val requestState = _uiState.value
        val requestIdentity = CatalogRequestIdentity(
            libraryId = libraryId,
            browseSort = requestState.browseSort,
            selectedNamePrefix = requestState.selectedNamePrefix,
            filterState = requestState.filterState,
        )
        val requestGeneration = ++catalogRequestGeneration
        val queryGeneration =
            if (reset) ++catalogQueryGeneration else catalogQueryGeneration
        val offset = if (reset) 0 else requestState.catalogItems.size
        viewModelScope.launch {
            if (!isCatalogRequestCurrent(requestGeneration, requestIdentity)) return@launch

            if (reset && requestState.availableFilters == null) {
                launch {
                    // includeTechnical: resolution + audio/subtitle-language facets
                    // are only fetched on request (iOS parity). Keep the FULL
                    // response so the filter sheet has every facet, not just genres.
                    when (val filters = catalogRepository.getFilters(libraryId, includeTechnical = true)) {
                        is ApiResult.Success -> {
                            if (!isCatalogQueryCurrent(queryGeneration, requestIdentity)) {
                                return@launch
                            }
                            _uiState.update {
                                if (isCatalogQueryCurrent(queryGeneration, requestIdentity, it)) {
                                    it.copy(availableFilters = filters.data)
                                } else {
                                    it
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            _uiState.update {
                if (!isCatalogRequestCurrent(requestGeneration, requestIdentity, it)) {
                    it
                } else if (reset) {
                    it.copy(isLoadingCatalog = true, isLoadingMoreCatalog = false, catalogError = null)
                } else {
                    it.copy(isLoadingMoreCatalog = true, catalogError = null)
                }
            }

            when (
                val result = catalogRepository.browse(
                    libraryId = libraryId,
                    sort = requestState.browseSort.sortField,
                    order = requestState.browseSort.sortOrder,
                    offset = offset,
                    limit = pageSize,
                    namePrefix = requestState.selectedNamePrefix,
                    // Full facet filtering (genre/decade/rating/studio/language/...)
                    // via the shared query builder — replaces the single-genre param.
                    queryGroups = CatalogFilterQueryBuilder.buildGroups(requestState.filterState),
                    match = CatalogFilterQueryBuilder.matchParam(requestState.filterState)
                        .takeIf { requestState.filterState.hasActiveFilters },
                )
            ) {
                is ApiResult.Success -> {
                    // Overlay local optimistic watched/favorite (mirrors Home/Browse).
                    val overlaid = overlayLocalState(result.data.items)
                    if (!isCatalogRequestCurrent(requestGeneration, requestIdentity)) return@launch
                    // Audiobook libraries expose book-native facets
                    // (author/narrator/series) — detected from the first item.
                    val detectedMediaType = overlaid.firstOrNull()?.let { first ->
                        if (isAudiobookItemType(first.type)) {
                            BrowseFacetMediaType.Audiobook
                        } else {
                            BrowseFacetMediaType.Video
                        }
                    }
                    _uiState.update {
                        if (isCatalogRequestCurrent(requestGeneration, requestIdentity, it)) {
                            it.copy(
                                isLoadingCatalog = false,
                                isLoadingMoreCatalog = false,
                                catalogItems = if (reset) overlaid else it.catalogItems + overlaid,
                                catalogTotal = result.data.total,
                                catalogHasMore = result.data.hasMore,
                                browseMediaType = detectedMediaType ?: it.browseMediaType,
                                catalogError = null,
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.Error -> {
                    if (!isCatalogRequestCurrent(requestGeneration, requestIdentity)) return@launch
                    _uiState.update {
                        if (isCatalogRequestCurrent(requestGeneration, requestIdentity, it)) {
                            it.copy(
                                isLoadingCatalog = false,
                                isLoadingMoreCatalog = false,
                                catalogError = result.message.ifBlank { "Failed to load catalog" },
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    if (!isCatalogRequestCurrent(requestGeneration, requestIdentity)) return@launch
                    _uiState.update {
                        if (isCatalogRequestCurrent(requestGeneration, requestIdentity, it)) {
                            it.copy(
                                isLoadingCatalog = false,
                                isLoadingMoreCatalog = false,
                                catalogError = "Network error: ${result.exception.message ?: "unknown"}",
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    /** Overlay local optimistic watched/favorite onto the library grid (local
     *  non-null wins), so an offline mutation shows immediately. Mirrors Home/
     *  Browse. No-op on the default port. */
    private suspend fun overlayLocalState(items: List<BrowseItem>): List<BrowseItem> {
        val ids = items.map { it.contentId }.distinct()
        if (ids.isEmpty()) return items
        val local = userItemState.localContentStates(ids)
        if (local.isEmpty()) return items
        return items.map { item ->
            val ls = local[item.contentId] ?: return@map item
            val base = item.userState ?: MediaItemUserState()
            item.copy(
                userState = base.copy(
                    played = ls.watched ?: base.played,
                    isFavorite = ls.favorite ?: base.isFavorite,
                ),
            )
        }
    }

    private fun loadCollections(libraryId: Int, force: Boolean) {
        if (!force && collectionsLoadedLibraryId == libraryId && _uiState.value.collections.isNotEmpty()) {
            return
        }
        collectionsLoadedLibraryId = libraryId
        val requestGeneration = ++collectionsRequestGeneration
        viewModelScope.launch {
            if (!isCollectionsRequestCurrent(requestGeneration, libraryId)) return@launch
            _uiState.update {
                if (isCollectionsRequestCurrent(requestGeneration, libraryId, it)) {
                    it.copy(
                        isLoadingCollections = true,
                        collectionsError = null,
                        collections = emptyList(),
                    )
                } else {
                    it
                }
            }
            when (val result = sectionRepository.getLibraryCollections(libraryId)) {
                is ApiResult.Success -> {
                    if (!isCollectionsRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isCollectionsRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingCollections = false,
                                collections = result.data,
                                collectionsError = null,
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.Error -> {
                    if (!isCollectionsRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isCollectionsRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingCollections = false,
                                collectionsError = result.message.ifBlank { "Failed to load collections" },
                            )
                        } else {
                            it
                        }
                    }
                }
                is ApiResult.NetworkError -> {
                    if (!isCollectionsRequestCurrent(requestGeneration, libraryId)) return@launch
                    _uiState.update {
                        if (isCollectionsRequestCurrent(requestGeneration, libraryId, it)) {
                            it.copy(
                                isLoadingCollections = false,
                                collectionsError = "Network error: ${result.exception.message ?: "unknown"}",
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private fun isRecommendedRequestCurrent(
        generation: Long,
        libraryId: Int,
        state: LibrariesUiState = _uiState.value,
    ): Boolean =
        generation == recommendedRequestGeneration && state.selectedLibraryId == libraryId

    private fun isCatalogRequestCurrent(
        generation: Long,
        identity: CatalogRequestIdentity,
        state: LibrariesUiState = _uiState.value,
    ): Boolean =
        generation == catalogRequestGeneration &&
            identity.matches(state)

    private fun isCatalogQueryCurrent(
        generation: Long,
        identity: CatalogRequestIdentity,
        state: LibrariesUiState = _uiState.value,
    ): Boolean =
        generation == catalogQueryGeneration &&
            identity.matches(state)

    private fun CatalogRequestIdentity.matches(state: LibrariesUiState): Boolean =
        state.selectedLibraryId == libraryId &&
            state.browseSort == browseSort &&
            state.selectedNamePrefix == selectedNamePrefix &&
            state.filterState == filterState

    private fun isCollectionsRequestCurrent(
        generation: Long,
        libraryId: Int,
        state: LibrariesUiState = _uiState.value,
    ): Boolean =
        generation == collectionsRequestGeneration && state.selectedLibraryId == libraryId

    private data class CatalogRequestIdentity(
        val libraryId: Int,
        val browseSort: LibraryBrowseSort,
        val selectedNamePrefix: String?,
        val filterState: CatalogFilterState,
    )
}

// Distance the Recommended tab must scroll for the chrome scrim to fully
// fade in. Mirrors `chromeScrimFadeDistance` on iOS.
private const val ChromeFadeDistanceDp = 80f

@Composable
fun LibrariesScreen(
    onItemClick: (String) -> Unit,
    onCollectionClick: (String, Int) -> Unit,
    viewModel: LibrariesViewModel,
    activeProfile: Profile?,
    onLibrarySelectorClick: () -> Unit,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val selectedLibrary = state.libraries.firstOrNull { it.id == state.selectedLibraryId }

    // Recommended tab scroll state — drives the chrome scrim opacity so the
    // header fades in its scrim once the user scrolls the rows underneath it.
    val recommendedListState = rememberLazyListState()
    val density = LocalDensity.current
    val chromeFadePx = remember(density) {
        with(density) { ChromeFadeDistanceDp.dp.toPx() }
    }
    val recommendedScrollProgress by remember(chromeFadePx) {
        derivedStateOf {
            if (recommendedListState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (recommendedListState.firstVisibleItemScrollOffset / chromeFadePx).coerceIn(0f, 1f)
            }
        }
    }
    val chromeScrimProgress = if (state.selectedTab == LibrariesSubtab.Recommended) {
        recommendedScrollProgress
    } else {
        1f
    }

    // The chrome floats over the content, which scrolls up beneath its
    // feathered glass edge. Its height is measured (the selector wraps to two
    // lines) and handed to each subtab as the inset its own top must clear.
    val chromeHaze = rememberHazeState()
    var chromeHeightPx by remember { mutableIntStateOf(0) }
    val chromeHeight = with(density) { chromeHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LibraryContentViewport(
            modifier = Modifier
                .fillMaxSize()
                // Background inside the source so the glass captures an
                // opaque scene rather than compositing over the sharp content.
                .hazeSource(chromeHaze)
                .background(MaterialTheme.colorScheme.background)
                .clipToBounds(),
        ) {
            // Hold content until the chrome has been measured once so the
            // first frame does not lay rows out under the header and jump.
            if (chromeHeightPx > 0) {
                val topInset = chromeHeight
                when {
                    state.isLoadingLibraries && state.libraries.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(top = topInset),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.librariesError != null && state.libraries.isEmpty() -> {
                        ErrorView(
                            message = state.librariesError ?: "Failed to load libraries",
                            onRetry = viewModel::refresh,
                            modifier = Modifier.fillMaxSize().padding(top = topInset),
                        )
                    }
                    selectedLibrary == null -> {
                        EmptyStateView(
                            title = "No libraries available",
                            subtitle = "Libraries visible to this profile will show up here",
                            icon = Icons.Default.VideoLibrary,
                            modifier = Modifier.fillMaxSize().padding(top = topInset),
                        )
                    }
                    state.selectedTab == LibrariesSubtab.Recommended -> {
                        RecommendedTabContent(
                            state = state,
                            listState = recommendedListState,
                            topInset = topInset,
                            onItemClick = onItemClick,
                            onRetry = viewModel::retryCurrentTab,
                        )
                    }
                    state.selectedTab == LibrariesSubtab.Browse -> {
                        BrowseTabContent(
                            state = state,
                            topInset = topInset,
                            onItemClick = onItemClick,
                            onRetry = viewModel::retryCurrentTab,
                            onLoadMore = viewModel::loadMoreCatalog,
                            onSortChanged = viewModel::selectBrowseSort,
                            onNamePrefixChanged = viewModel::selectNamePrefix,
                            onDensityChanged = viewModel::selectViewDensity,
                            onApplyFilter = viewModel::applyFilterState,
                            onSetPreserve = viewModel::setPreserveFilters,
                        )
                    }
                    else -> {
                        CollectionsTabContent(
                            state = state,
                            topInset = topInset,
                            onCollectionClick = { collectionId ->
                                state.selectedLibraryId?.let { libraryId ->
                                    onCollectionClick(collectionId, libraryId)
                                }
                            },
                            onRetry = viewModel::retryCurrentTab,
                        )
                    }
                }
            }
        }

        LibrariesFloatingChrome(
            scrimProgress = chromeScrimProgress,
            hazeState = chromeHaze,
            selectedLibrary = selectedLibrary,
            canSwitch = state.libraries.size > 1,
            activeProfile = activeProfile,
            selectedTab = state.selectedTab,
            onLibrarySelectorClick = onLibrarySelectorClick,
            onTabSelected = viewModel::selectTab,
            onSearchClick = onSearchClick,
            onRequestsClick = onRequestsClick,
            onWatchTogetherClick = onWatchTogetherClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onSwitchServerClick = onSwitchServerClick,
            onSignOutClick = onSignOutClick,
            modifier = Modifier.onSizeChanged { chromeHeightPx = it.height },
        )
    }
}

@Composable
private fun LibraryContentViewport(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun RecommendedTabContent(
    state: LibrariesUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    topInset: Dp,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val diagnosticsListSnapshot = remember(state.sections) {
        DiagnosticsListSnapshot.fromKeys(
            keys = state.sections.map { it.id },
            rowKeys = state.sections.map { section -> section.items.map { it.contentId } },
        )
    }
    LaunchedEffect(diagnosticsListSnapshot, state.isLoadingSections) {
        if (!state.isLoadingSections && state.sections.isNotEmpty()) {
            DiagnosticsListLogger.snapshot(
                DiagnosticsListSurface.PHONE_LIBRARY_RECOMMENDED,
                diagnosticsListSnapshot,
            )
        }
    }
    when {
        state.isLoadingSections && state.sections.isEmpty() -> {
            MediaRowsSkeleton(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        state.sectionsError != null && state.sections.isEmpty() -> {
            ErrorView(
                message = state.sectionsError ?: "Failed to load recommendations",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        state.sections.isEmpty() -> {
            EmptyStateView(
                title = "No recommendations yet",
                subtitle = "Try switching libraries or browsing the full catalog",
                icon = libraryIcon(state.libraries.firstOrNull { it.id == state.selectedLibraryId }?.type.orEmpty()),
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        else -> {
            // No hero carousel (matches iOS): a `featured` section is just
            // another row, kept in the order the server configured it.
            // iOS `LibraryRecommendedView`: LazyVStack(spacing: largePadding = 24)
            // between section rows.
            DeferImagePresentationWhileScrolling(listState) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Rows start below the floating chrome and scroll up under it.
                contentPadding = PaddingValues(top = topInset + 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {

                items(
                    items = state.sections,
                    key = { section -> section.id },
                ) { section ->
                    // No "See All" — iOS has no such affordance (H3, Jim
                    // 2026-07-10); the row omits it when onSeeAllClick is null.
                    HomeSectionRow(
                        section = section,
                        onItemClick = onItemClick,
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp + LocalBottomChromeInset.current))
                }
            }
            }
        }
    }
}

@Composable
private fun BrowseTabContent(
    state: LibrariesUiState,
    topInset: Dp,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSortChanged: (LibraryBrowseSort) -> Unit,
    onNamePrefixChanged: (String?) -> Unit,
    onDensityChanged: (CatalogViewDensity) -> Unit,
    onApplyFilter: (CatalogFilterState) -> Unit,
    onSetPreserve: (Boolean) -> Unit,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val isCustomised = state.browseSort != LibraryBrowseSort.RecentlyAdded ||
        state.filterState.hasActiveFilters ||
        state.selectedNamePrefix != null

    // Sort ▾ / Filter (n) / Reset — the same control row as the saved lists —
    // plus removable chips for active facets. Rendered as the grid's header
    // so it scrolls with the content under the chrome's glass.
    val controlsHeader: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(bottom = 4.dp)) {
            SortFilterControlsRow(
                sortLabel = state.browseSort.label,
                sortActive = state.browseSort != LibraryBrowseSort.RecentlyAdded,
                sortOptions = LibraryBrowseSort.entries.map { SortMenuOption(id = it.name, label = it.label) },
                selectedSortId = state.browseSort.name,
                onSelectSort = { id -> onSortChanged(LibraryBrowseSort.valueOf(id)) },
                filterCount = state.filterState.activeFacetCount,
                onOpenFilters = { showFilterSheet = true },
                showReset = isCustomised,
                onReset = {
                    onSortChanged(LibraryBrowseSort.RecentlyAdded)
                    onApplyFilter(state.filterState.resetFilters())
                    onNamePrefixChanged(null)
                },
            )
            if (state.filterState.hasActiveFilters) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogFacet.available(state.browseMediaType).forEach { facet ->
                        state.filterState.valuesFor(facet).sorted().forEach { value ->
                            LibraryActiveFilterChip(
                                label = facetValueLabel(facet, value),
                                onRemove = { onApplyFilter(state.filterState.toggle(facet, value)) },
                            )
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoadingCatalog && state.catalogItems.isEmpty() -> {
                PosterGridSkeleton(
                    progress = rememberShimmerProgress(),
                    modifier = Modifier.fillMaxSize().padding(top = topInset),
                )
            }
            state.catalogError != null && state.catalogItems.isEmpty() -> {
                // Controls stay mounted so a rejected sort/filter/letter can be
                // changed from here rather than only retried.
                Column(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { controlsHeader() }
                    ErrorView(
                        message = state.catalogError ?: "Failed to load catalog",
                        onRetry = onRetry,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.catalogItems.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { controlsHeader() }
                    EmptyStateView(
                        title = if (isCustomised) "No matches" else "No items found",
                        subtitle = if (isCustomised) "No titles match the current sort or filters." else "Try switching libraries",
                        icon = libraryIcon(state.libraries.firstOrNull { it.id == state.selectedLibraryId }?.type.orEmpty()),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> {
                CatalogGrid(
                    items = state.catalogItems,
                    isLoadingMore = state.isLoadingMoreCatalog,
                    hasMore = state.catalogHasMore,
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                    // Date sorts surface the sorted-by date under each card
                    // (mirrors the standalone BrowseScreen), Jim QA 2026-07-09.
                    cardSubtitle = when (state.browseSort.sortField) {
                        "added_at" -> { item -> formatCardDate(item.addedAt) }
                        "release_date" -> { item -> formatCardDate(item.releaseDate) }
                        else -> null
                    },
                    selectedNamePrefix = state.selectedNamePrefix,
                    onNamePrefixSelected = onNamePrefixChanged,
                    viewDensity = state.catalogDensity,
                    bottomContentInset = LocalBottomChromeInset.current,
                    topContentInset = topInset,
                    header = controlsHeader,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            viewDensity = state.catalogDensity,
            onSelectDensity = onDensityChanged,
            currentFilters = state.filterState,
            availableFilters = state.availableFilters,
            mediaType = state.browseMediaType,
            preserveFilters = state.preserveFilters,
            onCommit = onApplyFilter,
            onSetPreserve = onSetPreserve,
            onDismiss = { showFilterSheet = false },
        )
    }
}

/**
 * Removable active-filter capsule chip for the Libraries browse tab (mirrors the
 * standalone Browse screen's private chip). Removing a chip toggles that one
 * facet value off.
 */
@Composable
private fun LibraryActiveFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "Remove filter",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun CollectionsTabContent(
    state: LibrariesUiState,
    topInset: Dp,
    onCollectionClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isLoadingCollections && state.collections.isEmpty() -> {
            PosterGridSkeleton(
                progress = rememberShimmerProgress(),
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        state.collectionsError != null && state.collections.isEmpty() -> {
            ErrorView(
                message = state.collectionsError ?: "Failed to load collections",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        state.collections.isEmpty() -> {
            EmptyStateView(
                title = "No collections found",
                subtitle = "This library does not have any collections yet",
                icon = Icons.Default.VideoLibrary,
                modifier = Modifier.fillMaxSize().padding(top = topInset),
            )
        }
        else -> {
            // iOS `LibraryCollectionsView`: adaptive poster grid with shared
            // column/row spacing and 16pt padding insets. Follows the Library
            // grid's view density so both tabs show the same column count.
            val gridState = rememberLazyGridState()
            DeferImagePresentationWhileScrolling(gridState) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(
                    state.catalogDensity.minCardWidth *
                        LocalCardPresentation.current.posterSize.posterScale,
                ),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
                verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topInset + 16.dp,
                    bottom = 24.dp + LocalBottomChromeInset.current,
                ),
            ) {
                items(
                    items = state.collections,
                    key = { it.id },
                    contentType = { "library-collection" },
                ) { collection ->
                    InlineLibraryCollectionCard(
                        collection = collection,
                        onClick = { onCollectionClick(collection.id) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun InlineLibraryCollectionCard(
    collection: LibraryCollection,
    onClick: () -> Unit,
) {
    // iOS `LibraryCollectionCard`: VStack(spacing: 6) of a 2:3.3 poster
    // (smallCornerRadius = 6) carrying a bottom-trailing count badge, a
    // siloCaption (12) name (2 lines), and a siloSmall (11) secondary
    // type label. The two text lines follow the caption preference
    // (showsTitle / showsMetadata); the count badge rides the artwork.
    val caption = LocalCardPresentation.current.caption
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                .aspectRatio(2f / 3.3f),
        ) {
            ThumbhashImage(
                url = collection.posterUrl,
                thumbhash = collection.posterThumbhash,
                contentDescription = collection.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = collection.itemCount?.takeIf { it > 0 }?.toString() ?: "Smart",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        if (caption.showsTitle) {
            Text(
                text = collection.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (caption.showsMetadata) {
            Text(
                text = collection.itemCount?.let { "$it items" } ?: "Collection",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibrariesFloatingChrome(
    scrimProgress: Float,
    hazeState: HazeState,
    selectedLibrary: UserLibrary?,
    canSwitch: Boolean,
    activeProfile: Profile?,
    selectedTab: LibrariesSubtab,
    onLibrarySelectorClick: () -> Unit,
    onTabSelected: (LibrariesSubtab) -> Unit,
    onSearchClick: () -> Unit,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val animatedFill by animateFloatAsState(
        targetValue = scrimProgress,
        label = "librariesChromeFill",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // Progressive glass, faded in by the scroll-driven opacity on the
        // Recommended tab and always on for Browse / Collections. Its bottom
        // edge feathers to clear so rows dissolve into the chrome.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = animatedFill }
                .topBarGlass(hazeState, progressive = true),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarPadding.calculateTopPadding() + 8.dp),
        ) {
        // Top row: library selector on the left, action icons on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibrarySelectorButton(
                library = selectedLibrary,
                canSwitch = canSwitch,
                onClick = onLibrarySelectorClick,
                modifier = Modifier.weight(1f),
            )

            TabTopBarActions(
                activeProfile = activeProfile,
                onSearchClick = onSearchClick,
                onRequestsClick = onRequestsClick,
                onWatchTogetherClick = onWatchTogetherClick,
                onSettingsClick = onSettingsClick,
                onSwitchProfileClick = onSwitchProfileClick,
                onSwitchServerClick = onSwitchServerClick,
                onSignOutClick = onSignOutClick,
            )
        }

        // iOS: top bar bottom inset = smallPadding (8).
        Spacer(modifier = Modifier.height(8.dp))

        LibrarySubtabRow(
            selectedTab = selectedTab,
            onRecommendedClick = { onTabSelected(LibrariesSubtab.Recommended) },
            onBrowseClick = { onTabSelected(LibrariesSubtab.Browse) },
            onCollectionsClick = { onTabSelected(LibrariesSubtab.Collections) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

            // iOS: tab selector bottom inset = padding (16).
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Compact text-based library selector that mirrors iOS
 * `LibrarySelectorButton` — library name with a small chevron stacked above
 * a secondary type label. Tapping opens the picker sheet.
 */
@Composable
private fun LibrarySelectorButton(
    library: UserLibrary?,
    canSwitch: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS `LibrarySelectorButton`: VStack(spacing: 1) of a name+chevron row
    // (siloTitle = 18pt bold) above a siloCaption (12pt) type label.
    Column(
        modifier = modifier
            .clickable(enabled = canSwitch && library != null, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = library?.name ?: "Libraries",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (canSwitch && library != null) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = library?.typeLabel() ?: "Choose a library",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesSelectorSheet(
    libraries: List<UserLibrary>,
    selectedLibraryId: Int?,
    onSelectLibrary: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = selectorSheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // iOS `LibraryPickerSheet`: a large "Libraries" navigation title above a
        // VStack(spacing: 8) of rows, with h/v padding = padding (16).
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Libraries",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            libraries.forEach { library ->
                LibrarySelectorRow(
                    library = library,
                    isSelected = library.id == selectedLibraryId,
                    onClick = { onSelectLibrary(library.id) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LibrarySubtabRow(
    selectedTab: LibrariesSubtab,
    onRecommendedClick: () -> Unit,
    onBrowseClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibrarySubtabChip(
            label = "Recommended",
            selected = selectedTab == LibrariesSubtab.Recommended,
            onClick = onRecommendedClick,
        )
        LibrarySubtabChip(
            label = "Library",
            selected = selectedTab == LibrariesSubtab.Browse,
            onClick = onBrowseClick,
        )
        LibrarySubtabChip(
            label = "Collections",
            selected = selectedTab == LibrariesSubtab.Collections,
            onClick = onCollectionsClick,
        )
    }
}

@Composable
private fun LibrarySubtabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // iOS `LibraryPageTabSelector` chip: Capsule, siloCaption (12pt),
    // selected = onSurface (white #EDEDED) fill / background (black) label and
    // semibold weight; unselected = surfaceElevated (#15171C) fill / secondary
    // label and regular weight. Padding h16 v8.
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            SiloSurfaceElevated
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LibrarySelectorRow(
    library: UserLibrary,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // iOS `LibraryPickerRow`: cornerRadius (8) card, selected fill onSurface@10%
    // else surfaceElevated, hairline siloOutline (white@12%) border. Row
    // padding h16 v14; circle 40 onSurface@12% with an 18pt icon; name
    // siloHeadline (16) above a siloCaption (12) secondary label;
    // a 14pt checkmark on the selected row.
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        } else {
            SiloSurfaceElevated
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = libraryIcon(library.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = library.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = library.typeLabel(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun libraryChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color.White,
    selectedLabelColor = Color.Black,
    selectedLeadingIconColor = Color.Black,
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    labelColor = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun libraryIcon(type: String): ImageVector = when (type.lowercase()) {
    "movies", "movie" -> Icons.Default.LocalMovies
    "series", "tv", "shows" -> Icons.Default.Tv
    "audiobook", "audiobooks" -> Icons.Default.Headphones
    "book", "books", "ebook", "ebooks" -> Icons.AutoMirrored.Filled.MenuBook
    "comic", "comics", "manga" -> Icons.Default.AutoStories
    else -> Icons.Default.VideoLibrary
}

private fun UserLibrary.typeLabel(): String = when (type.lowercase()) {
    "movies", "movie" -> "Movies library"
    "series", "tv", "shows" -> "TV library"
    "audiobook", "audiobooks" -> "Audiobooks library"
    "book", "books", "ebook", "ebooks" -> "Books library"
    "comic", "comics", "manga" -> "Comics / manga library"
    else -> "Library"
}
