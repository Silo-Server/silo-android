package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.AudiobookGroup
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.LibraryCollectionsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.tv.ui.util.tvCatalogMediaTypeFor
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Library content sections committed by the Skyline cascade. The extra browse
 * variants are request presets over the same catalog grid, while Collections
 * and Recommended keep their dedicated surfaces.
 */
enum class TvLibraryTab(val label: String) {
    Recommended("Recommended"),
    Collections("Collections"),
    Browse("Browse"),
    Genres("Genres"),
    Alphabet("A-Z"),
    RecentlyAdded("Recently Added"),
    Authors("Authors"),
    Series("Series"),
}

/**
 * One named or anonymous collections section as it should appear on screen,
 * in the order computed from `sort_order`. Mirrors tvOS
 * `LibraryCollectionSection` / the phone client's grouped collections. An
 * empty [name] renders without a group header (the ungrouped / flat bucket).
 */
data class TvCollectionSection(
    val name: String,
    // "regular" or "user_collections". User-created collections resolve via a
    // different catalog source (source=user_collection), so the click must
    // route them to the user-collection detail — otherwise the server rejects
    // the library_collection lookup with "Catalog source not found" (issue #69).
    val kind: String = "regular",
    val collections: List<LibraryCollection>,
)

/**
 * Sortable catalog fields for the library Browse grid, mirroring tvOS
 * `CatalogSortKey` (labels, per-media-type availability, default order, and
 * direction hints). Wire values are the canonical server sort fields.
 */
enum class TvLibrarySortOption(val label: String, val wireValue: String) {
    /**
     * "Send no sort at all" — the server then keeps the source's intrinsic
     * order (a library collection's manual / MDBList / smart order). Only
     * offered where such an order exists ([availableForCollection]); the
     * Browse grid has none, so it never lists this.
     */
    CollectionOrder("Collection Order", ""),
    /**
     * The same "send no sort" behaviour for personal lists (favorites /
     * watchlist), where the stored order is most-recently-saved-first. It is a
     * distinct entry with its own wire value rather than a relabelled
     * [CollectionOrder]: two entries sharing the empty wire value would make
     * [fromWire] and the panel's current-selection lookup ambiguous. The
     * personal query builder maps it back to "no sort".
     */
    ListOrder("Recently Saved", "__list_order"),
    Title("Title", "title"),
    DateAdded("Date Added", "added_at"),
    // Server expects "year" for release-date sort (matches phone); the old
    // "release_date" value was unsupported.
    ReleaseDate("Year", "year"),
    Rating("Rating", "rating_imdb"),
    Runtime("Runtime", "runtime"),
    Resolution("Resolution", "resolution"),
    Author("Author", "author"),
    Narrator("Narrator", "narrator"),
    SeriesName("Series", "series");

    /**
     * False for the "keep the source's own order" entries: they send no sort,
     * so there is no asc/desc to show, flip, or arrow.
     */
    val hasDirection: Boolean get() = this != CollectionOrder && this != ListOrder

    /** Short hint for the active direction (tvOS `directionLabel`). */
    fun directionLabel(order: String): String = when (this) {
        // No direction to report — the order is whatever the source defines.
        CollectionOrder, ListOrder -> "Default"
        Title, Author, Narrator, SeriesName -> if (order == "asc") "A–Z" else "Z–A"
        ReleaseDate, DateAdded -> if (order == "asc") "Oldest" else "Newest"
        Runtime -> if (order == "asc") "Shortest" else "Longest"
        Rating, Resolution -> if (order == "asc") "Lowest" else "Highest"
    }

    companion object {
        fun fromWire(value: String): TvLibrarySortOption =
            entries.firstOrNull { it.wireValue == value } ?: DateAdded

        /** Sort keys offered for a library media type, in tvOS display order. */
        fun availableFor(libraryType: String): List<TvLibrarySortOption> =
            if (org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType(libraryType)) {
                listOf(Title, Author, Narrator, SeriesName, DateAdded, Runtime)
            } else {
                listOf(Title, DateAdded, ReleaseDate, Rating, Runtime, Resolution)
            }

        /**
         * Sort keys for a library collection's detail grid. Leads with
         * [CollectionOrder] because that is the collection's own curation and
         * the state the page opens in; the rest follow the owning library's
         * media type, so an audiobook collection offers Author/Narrator/Series
         * rather than the video-only Year/Rating/Resolution keys (Codex).
         */
        fun availableForCollection(libraryType: String): List<TvLibrarySortOption> =
            listOf(CollectionOrder) + availableFor(libraryType)

        /**
         * Sort keys for a personal list (favorites / watchlist). Leads with
         * [ListOrder] — the stored order the list opens in. [DateAdded] here
         * means "date added to the list", which is what the server sorts
         * `added_at` by for these sources.
         */
        fun availableForPersonalList(): List<TvLibrarySortOption> =
            listOf(ListOrder, Title, DateAdded, ReleaseDate, Rating, Runtime)
    }
}

data class TvLibraryBrowseFilter(
    val genre: String? = null,
    val namePrefix: String? = null,
    // Default to newest-first (added_at), matching the global browse + the
    // shared CatalogRepository's default; TV previously diverged with Title.
    val sort: String = TvLibrarySortOption.DateAdded.wireValue,
    val order: String = "desc",
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val queryGroups: List<CatalogQueryGroup> = emptyList(),
    /** Multi-facet selections from the Browse filter panel (tvOS parity). */
    val facetSelection: TvCatalogFacetSelection = TvCatalogFacetSelection(),
)

class TvLibraryDetailViewModel(
    private val sectionRepository: SectionRepository,
    private val catalogRepository: CatalogRepository,
    private val libraryId: Int,
    private val libraryTitle: String,
    private val libraryType: String,
) : ViewModel() {

    data class UiState(
        val title: String,
        val libraryType: String,
        val selectedTab: TvLibraryTab = TvLibraryTab.Recommended,
        val sections: List<ResolvedSection> = emptyList(),
        val recommendedLoading: Boolean = true,
        val recommendedError: String? = null,
        val genres: List<String> = emptyList(),
        /** Full facet vocabulary for the Browse filter panel (lazy-loaded). */
        val facetOptions: org.siloserver.silo.model.catalog.CatalogFiltersResponse? = null,
        val filtersLoading: Boolean = true,
        val browseItems: List<BrowseItem> = emptyList(),
        val browseHasMore: Boolean = false,
        val browseLoading: Boolean = false,
        val browseLoadingMore: Boolean = false,
        val browseError: String? = null,
        val browseFilter: TvLibraryBrowseFilter = TvLibraryBrowseFilter(),
        val audiobookGroups: List<AudiobookGroup> = emptyList(),
        val audiobookGroupsHasMore: Boolean = false,
        val audiobookGroupsLoading: Boolean = false,
        val audiobookGroupsLoadingMore: Boolean = false,
        val audiobookGroupsError: String? = null,
        val selectedAudiobookGroup: AudiobookGroup? = null,
        val collections: List<LibraryCollection> = emptyList(),
        val collectionSections: List<TvCollectionSection> = emptyList(),
        val collectionsLoading: Boolean = false,
        val collectionsError: String? = null,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            title = libraryTitle,
            libraryType = libraryType,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val pageSize = 100
    private var loadedRecommended = false
    private var loadedBrowse = false
    private var loadedCollections = false
    private var loadedFilters = false
    private var browseGeneration = 0
    private var browseSnapshot: String? = null

    // Raw (pre-visibleOnTv-filter) loaded count = the server offset for the next
    // browse page. Paging off the filtered browseItems.size would double-count
    // (duplicate contentId keys → LazyVerticalGrid crash) whenever a page holds
    // TV-hidden entries, or stall the offset forever if a whole page is hidden.
    // Mirrors the rawLoaded counter in TvBrowseViewModel.
    private var browseRawLoaded = 0
    private var loadedAudiobookGroupBy: String? = null
    private var audiobookGroupsGeneration = 0

    // Last cascade-commit nonce whose section we applied. The screen's
    // section-apply effect re-runs on every re-entry into composition —
    // including returning from ItemDetail/Player — and this ViewModel survives
    // that round-trip, so without gating on the nonce a bare re-entry would
    // re-apply the initial section and throw away the user's in-screen tab /
    // A-Z filter (issue #66: Back from a movie returned to the library's main
    // view instead of the browse list). Only a genuine new commit bumps the
    // nonce, so we apply then and no-op on re-entry.
    private var lastAppliedSectionNonce: Int? = null

    init {
        // Only the default Recommended tab loads eagerly. Filters (the genre
        // rail) are fetched lazily when Browse is first opened — the
        // `/catalog/filters` call is slow and is wasted work for the (common)
        // case where the user never leaves Recommended.
        loadRecommended()
    }

    /**
     * Applies a section committed from the Skyline cascade, but only once per
     * distinct commit nonce (see [lastAppliedSectionNonce]) so a plain
     * re-entry into composition — e.g. returning from a detail screen — does
     * not clobber the user's current tab/filter.
     */
    fun applyCommittedSection(tab: TvLibraryTab, nonce: Int) {
        if (nonce == lastAppliedSectionNonce) return
        lastAppliedSectionNonce = nonce
        onTabSelected(tab)
    }

    fun onTabSelected(tab: TvLibraryTab) {
        val state = _uiState.value
        val nextFilter = state.browseFilter.forTab(tab)
        val filterChanged = nextFilter != state.browseFilter
        val audiobookGroupBy = tab.audiobookGroupBy
        // Re-selecting the section that is already active is a no-op. The
        // screen re-issues the committed section every time it re-enters
        // composition — backing out of item detail / the player returns to a
        // surviving ViewModel and fires the section-apply effect again — so
        // re-applying `forTab` here would reset the viewer's customised sort
        // and facets back to the tab's defaults (Title A–Z). Only a genuine
        // tab CHANGE applies the new tab's defaults.
        if (state.selectedTab == tab) return
        _uiState.update {
            it.copy(
                selectedTab = tab,
                browseFilter = nextFilter,
                browseItems = if (audiobookGroupBy != null) emptyList() else it.browseItems,
                browseHasMore = if (audiobookGroupBy != null) false else it.browseHasMore,
                browseError = if (audiobookGroupBy != null) null else it.browseError,
                selectedAudiobookGroup = if (audiobookGroupBy != null) null else it.selectedAudiobookGroup,
            )
        }
        when (tab) {
            TvLibraryTab.Recommended -> if (!loadedRecommended) loadRecommended()
            TvLibraryTab.Browse,
            TvLibraryTab.Genres,
            TvLibraryTab.Alphabet,
            TvLibraryTab.RecentlyAdded -> {
                if (!loadedFilters) loadFilters()
                if (!loadedBrowse || filterChanged || state.selectedTab != tab) loadBrowse(reset = true)
            }
            TvLibraryTab.Authors,
            TvLibraryTab.Series -> {
                if (loadedAudiobookGroupBy != audiobookGroupBy || state.selectedTab != tab) {
                    loadAudiobookGroups(groupBy = audiobookGroupBy ?: return, reset = true)
                }
            }
            TvLibraryTab.Collections -> if (!loadedCollections) loadCollections()
        }
    }

    fun onGenreChanged(genre: String?) {
        updateBrowseFilter(
            _uiState.value.browseFilter.copy(
                genre = genre,
                namePrefix = null,
            ),
        )
    }

    fun onSortChanged(sort: TvLibrarySortOption) {
        updateBrowseFilter(
            _uiState.value.browseFilter.copy(
                sort = sort.wireValue,
                order = sort.defaultOrder,
                namePrefix = null,
            ),
        )
    }

    /**
     * Sort panel behavior (tvOS `setSort`): picking the active key flips the
     * direction; picking a different key selects it at its default order. The
     * A–Z prefix survives — "Action / T" stays a valid composite state.
     */
    fun onSortKeySelected(sort: TvLibrarySortOption) {
        val filter = _uiState.value.browseFilter
        val next = if (filter.sort == sort.wireValue) {
            filter.copy(order = if (filter.order == "asc") "desc" else "asc")
        } else {
            filter.copy(sort = sort.wireValue, order = sort.defaultOrder)
        }
        updateBrowseFilter(next)
    }

    /** Replaces the Browse panel's facet selections and reloads the grid. */
    fun onFacetSelectionApplied(selection: TvCatalogFacetSelection) {
        updateBrowseFilter(
            _uiState.value.browseFilter.copy(
                facetSelection = selection,
                namePrefix = null,
            ),
        )
    }

    fun onNamePrefixChanged(prefix: String?) {
        updateBrowseFilter(
            _uiState.value.browseFilter.copy(namePrefix = prefix),
        )
    }

    fun onYearRangeChanged(yearMin: Int?, yearMax: Int?) {
        updateBrowseFilter(
            _uiState.value.browseFilter.copy(
                yearMin = yearMin,
                yearMax = yearMax,
                // Match the existing pattern in onGenreChanged/onSortChanged:
                // changing a high-level filter dimension resets the alphabet jump.
                namePrefix = null,
            ),
        )
    }

    fun loadMoreBrowse() {
        val state = _uiState.value
        if (state.browseLoading || state.browseLoadingMore || !state.browseHasMore) return
        loadBrowse(reset = false)
    }

    fun loadMoreAudiobookGroups() {
        val state = _uiState.value
        val groupBy = state.selectedTab.audiobookGroupBy ?: return
        if (state.audiobookGroupsLoading || state.audiobookGroupsLoadingMore || !state.audiobookGroupsHasMore) return
        loadAudiobookGroups(groupBy = groupBy, reset = false)
    }

    fun onAudiobookGroupSelected(group: AudiobookGroup) {
        val field = _uiState.value.selectedTab.audiobookCatalogField ?: return
        val queryGroup = CatalogQueryGroup(
            match = "all",
            rules = listOf(
                CatalogQueryRule(
                    field = field,
                    op = "is",
                    value = group.name,
                ),
            ),
        )
        loadedBrowse = false
        _uiState.update {
            it.copy(
                selectedAudiobookGroup = group,
                browseItems = emptyList(),
                browseHasMore = false,
                browseError = null,
                browseFilter = TvLibraryBrowseFilter(
                    sort = TvLibrarySortOption.Title.wireValue,
                    order = "asc",
                    queryGroups = listOf(queryGroup),
                ),
            )
        }
        loadBrowse(reset = true)
    }

    fun onAudiobookGroupCleared() {
        loadedBrowse = false
        _uiState.update {
            it.copy(
                selectedAudiobookGroup = null,
                browseItems = emptyList(),
                browseHasMore = false,
                browseLoading = false,
                browseLoadingMore = false,
                browseError = null,
                browseFilter = it.browseFilter.forTab(it.selectedTab),
            )
        }
    }

    fun retryRecommended() {
        loadRecommended()
    }

    fun retryBrowse() {
        loadBrowse(reset = true)
    }

    fun retryCollections() {
        loadCollections()
    }

    fun retryAudiobookGroups() {
        val groupBy = _uiState.value.selectedTab.audiobookGroupBy ?: return
        loadAudiobookGroups(groupBy = groupBy, reset = true)
    }

    private fun updateBrowseFilter(filter: TvLibraryBrowseFilter) {
        if (_uiState.value.browseFilter == filter) return
        _uiState.update { it.copy(browseFilter = filter) }
        if (_uiState.value.selectedTab == TvLibraryTab.Browse || loadedBrowse) {
            loadBrowse(reset = true)
        }
    }

    private fun loadRecommended() {
        loadedRecommended = true
        viewModelScope.launch {
            _uiState.update { it.copy(recommendedLoading = true, recommendedError = null) }

            val layout = when (val layoutResult = sectionRepository.getLibrarySections(libraryId)) {
                is ApiResult.Success -> layoutResult.data
                is ApiResult.Error -> {
                    loadedRecommended = false
                    _uiState.update {
                        it.copy(
                            recommendedLoading = false,
                            recommendedError = layoutResult.message.ifBlank { "Failed to load sections" },
                        )
                    }
                    return@launch
                }
                is ApiResult.NetworkError -> {
                    loadedRecommended = false
                    _uiState.update {
                        it.copy(
                            recommendedLoading = false,
                            recommendedError = "Network error: ${layoutResult.exception.message ?: "unknown"}",
                        )
                    }
                    return@launch
                }
            }

            // `/library/{id}/sections` already returns every section with its
            // items hydrated inline — byte-for-byte the same objects the
            // per-section `/items` endpoint returns (progress + user_state
            // included). Render them directly instead of re-fetching each one:
            // the previous fan-out was an N+1 that re-downloaded data already in
            // hand (10–14 extra requests per library open).
            //
            // Defensive fallback: resolve only sections the server left
            // un-inlined (older deployments / a dynamically-resolved section
            // type that reports a non-zero total but ships no items). The
            // measured common case has every section populated and makes zero
            // extra calls.
            val sections = layout.sections
            val unresolved = sections.filter { it.items.isEmpty() && it.totalCount > 0 }
            val resolved = if (unresolved.isEmpty()) {
                sections
            } else {
                val resolvedById = unresolved.map { section ->
                    async {
                        section.id to when (
                            val result = sectionRepository.getLibrarySectionItems(libraryId, section.id)
                        ) {
                            is ApiResult.Success -> result.data.section ?: section
                            else -> section
                        }
                    }
                }.awaitAll().toMap()
                sections.map { section -> resolvedById[section.id] ?: section }
            }

            _uiState.update {
                it.copy(
                    sections = resolved.visibleOnTv(),
                    recommendedLoading = false,
                    recommendedError = null,
                )
            }
        }
    }

    private fun loadFilters() {
        loadedFilters = true
        viewModelScope.launch {
            _uiState.update { it.copy(filtersLoading = true) }
            // include_technical adds the resolution / audio-language /
            // subtitle-language vocabularies the filter panel offers (tvOS
            // FacetLoader always requests them).
            when (val filters = catalogRepository.getFilters(libraryId, includeTechnical = true)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        genres = filters.data.genres.sorted(),
                        facetOptions = filters.data,
                        filtersLoading = false,
                    )
                }
                else -> _uiState.update { it.copy(filtersLoading = false) }
            }
        }
    }

    private fun loadBrowse(reset: Boolean) {
        loadedBrowse = true

        val generation = if (reset) {
            browseGeneration += 1
            browseGeneration
        } else {
            browseGeneration
        }

        if (reset) {
            browseSnapshot = null
            browseRawLoaded = 0
        }

        viewModelScope.launch {
            val state = _uiState.value
            val offset = if (reset) 0 else browseRawLoaded
            val filter = state.browseFilter

            _uiState.update {
                if (reset) {
                    it.copy(
                        browseItems = emptyList(),
                        browseHasMore = false,
                        browseLoading = true,
                        browseLoadingMore = false,
                        browseError = null,
                    )
                } else {
                    it.copy(browseLoadingMore = true)
                }
            }

            val facetGroups = filter.facetSelection.toQueryGroups()
            val result = catalogRepository.browse(
                source = "query",
                mediaType = mediaTypeFor(libraryType),
                libraryId = libraryId,
                genre = filter.genre,
                sort = filter.sort,
                order = filter.order,
                offset = offset,
                limit = pageSize,
                namePrefix = filter.namePrefix,
                yearMin = filter.yearMin,
                yearMax = filter.yearMax,
                snapshotAt = browseSnapshot,
                queryGroups = filter.queryGroups + facetGroups,
                match = if (facetGroups.isNotEmpty()) {
                    if (filter.facetSelection.matchAll) "all" else "any"
                } else {
                    null
                },
            )

            if (generation != browseGeneration) return@launch

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    if (browseSnapshot == null) {
                        browseSnapshot = response.snapshot
                    }
                    browseRawLoaded = if (reset) {
                        response.items.size
                    } else {
                        browseRawLoaded + response.items.size
                    }
                    _uiState.update {
                        val visibleItems = response.items.visibleOnTv()
                        it.copy(
                            // distinctBy contentId is a belt-and-suspenders guard
                            // against duplicate keys crashing LazyVerticalGrid if a
                            // page ever overlaps the previous one.
                            browseItems = if (reset) {
                                visibleItems
                            } else {
                                (it.browseItems + visibleItems).distinctBy { item -> item.contentId }
                            },
                            browseHasMore = response.hasMore,
                            browseLoading = false,
                            browseLoadingMore = false,
                            browseError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    loadedBrowse = false
                    _uiState.update {
                        it.copy(
                            browseLoading = false,
                            browseLoadingMore = false,
                            browseError = result.message.ifBlank { "Failed to load library" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    loadedBrowse = false
                    _uiState.update {
                        it.copy(
                            browseLoading = false,
                            browseLoadingMore = false,
                            browseError = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }

    private fun loadAudiobookGroups(groupBy: String, reset: Boolean) {
        loadedAudiobookGroupBy = groupBy

        val generation = if (reset) {
            audiobookGroupsGeneration += 1
            audiobookGroupsGeneration
        } else {
            audiobookGroupsGeneration
        }

        viewModelScope.launch {
            val state = _uiState.value
            val offset = if (reset) 0 else state.audiobookGroups.size

            _uiState.update {
                if (reset) {
                    it.copy(
                        audiobookGroups = emptyList(),
                        audiobookGroupsHasMore = false,
                        audiobookGroupsLoading = true,
                        audiobookGroupsLoadingMore = false,
                        audiobookGroupsError = null,
                        selectedAudiobookGroup = null,
                    )
                } else {
                    it.copy(audiobookGroupsLoadingMore = true)
                }
            }

            when (
                val result = catalogRepository.getAudiobookGroups(
                    libraryId = libraryId,
                    groupBy = groupBy,
                    sort = "name",
                    offset = offset,
                    limit = pageSize,
                )
            ) {
                is ApiResult.Success -> {
                    if (generation != audiobookGroupsGeneration) return@launch
                    val response = result.data
                    _uiState.update {
                        it.copy(
                            audiobookGroups = if (reset) response.groups else it.audiobookGroups + response.groups,
                            audiobookGroupsHasMore = response.hasMore,
                            audiobookGroupsLoading = false,
                            audiobookGroupsLoadingMore = false,
                            audiobookGroupsError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (generation != audiobookGroupsGeneration) return@launch
                    loadedAudiobookGroupBy = null
                    _uiState.update {
                        it.copy(
                            audiobookGroupsLoading = false,
                            audiobookGroupsLoadingMore = false,
                            audiobookGroupsError = result.message.ifBlank { "Failed to load audiobook groups" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    if (generation != audiobookGroupsGeneration) return@launch
                    loadedAudiobookGroupBy = null
                    _uiState.update {
                        it.copy(
                            audiobookGroupsLoading = false,
                            audiobookGroupsLoadingMore = false,
                            audiobookGroupsError = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }

    private fun loadCollections() {
        loadedCollections = true
        viewModelScope.launch {
            _uiState.update { it.copy(collectionsLoading = true, collectionsError = null) }
            when (val result = sectionRepository.getLibraryCollectionsGrouped(libraryId)) {
                is ApiResult.Success -> {
                    val sections = buildCollectionSections(result.data)
                    _uiState.update {
                        it.copy(
                            collections = sections.flatMap { section -> section.collections },
                            collectionSections = sections,
                            collectionsLoading = false,
                            collectionsError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    loadedCollections = false
                    _uiState.update {
                        it.copy(
                            collectionsLoading = false,
                            collectionsError = result.message.ifBlank { "Failed to load collections" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    loadedCollections = false
                    _uiState.update {
                        it.copy(
                            collectionsLoading = false,
                            collectionsError = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Builds the ordered render list: each non-empty group becomes a section in
     * `sort_order` order, and the ungrouped bucket is woven in at its own
     * `sort_order` slot. When the response is flat (no groups), a single
     * anonymous section is produced. Mirrors the phone client's `buildSections`.
     */
    private fun buildCollectionSections(
        response: LibraryCollectionsResponse,
    ): List<TvCollectionSection> {
        val groups = response.groups
        val ungrouped = response.ungrouped

        if (groups.isEmpty() && ungrouped == null) {
            return if (response.collections.isEmpty()) {
                emptyList()
            } else {
                listOf(TvCollectionSection(name = "", collections = response.collections))
            }
        }

        data class Slot(val order: Int, val section: TvCollectionSection)
        val slots = mutableListOf<Slot>()
        for (group in groups) {
            if (group.collections.isEmpty()) continue
            slots += Slot(
                order = group.sortOrder,
                section = TvCollectionSection(name = group.name, kind = group.kind, collections = group.collections),
            )
        }
        if (ungrouped != null && ungrouped.collections.isNotEmpty()) {
            slots += Slot(
                order = ungrouped.sortOrder,
                section = TvCollectionSection(name = "", collections = ungrouped.collections),
            )
        }
        return slots.sortedBy { it.order }.map { it.section }
    }

    private fun mediaTypeFor(type: String): String? = tvCatalogMediaTypeFor(type)

    private fun TvLibraryBrowseFilter.forTab(tab: TvLibraryTab): TvLibraryBrowseFilter =
        when (tab) {
            TvLibraryTab.Recommended,
            TvLibraryTab.Collections -> this
            // Browse lands on the tvOS default view: Title A–Z, no facets.
            TvLibraryTab.Browse -> copy(
                genre = null,
                namePrefix = null,
                sort = TvLibrarySortOption.Title.wireValue,
                order = "asc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
            TvLibraryTab.Genres -> copy(
                namePrefix = null,
                sort = TvLibrarySortOption.Title.wireValue,
                order = "asc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
            TvLibraryTab.Alphabet -> copy(
                genre = null,
                sort = TvLibrarySortOption.Title.wireValue,
                order = "asc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
            TvLibraryTab.RecentlyAdded -> copy(
                genre = null,
                namePrefix = null,
                sort = TvLibrarySortOption.DateAdded.wireValue,
                order = "desc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
            TvLibraryTab.Authors -> copy(
                genre = null,
                namePrefix = null,
                sort = TvLibrarySortOption.Title.wireValue,
                order = "asc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
            TvLibraryTab.Series -> copy(
                genre = null,
                namePrefix = null,
                sort = TvLibrarySortOption.Title.wireValue,
                order = "asc",
                queryGroups = emptyList(),
                facetSelection = TvCatalogFacetSelection(),
            )
        }
}

private val TvLibraryTab.audiobookGroupBy: String?
    get() = when (this) {
        TvLibraryTab.Authors -> "author"
        TvLibraryTab.Series -> "series"
        else -> null
    }

private val TvLibraryTab.audiobookCatalogField: String?
    get() = when (this) {
        TvLibraryTab.Authors -> "author"
        TvLibraryTab.Series -> "series"
        else -> null
    }

// Mirrors the server's per-field natural direction (tvOS `defaultOrder`):
// name-like fields ascend, magnitude/recency fields descend.
internal val TvLibrarySortOption.defaultOrder: String
    get() = when (this) {
        // Unused — these send no sort, so no order goes with them.
        TvLibrarySortOption.CollectionOrder,
        TvLibrarySortOption.ListOrder,
        TvLibrarySortOption.Title,
        TvLibrarySortOption.Author,
        TvLibrarySortOption.Narrator,
        TvLibrarySortOption.SeriesName -> "asc"
        TvLibrarySortOption.DateAdded,
        TvLibrarySortOption.ReleaseDate,
        TvLibrarySortOption.Rating,
        TvLibrarySortOption.Runtime,
        TvLibrarySortOption.Resolution -> "desc"
    }
