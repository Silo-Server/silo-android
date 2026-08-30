package org.siloserver.silo.tv.ui.screens.personal

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.screens.library.TvBrowseControlRow
import org.siloserver.silo.tv.ui.screens.library.TvBrowseFilterPanel
import org.siloserver.silo.tv.ui.screens.library.TvBrowseSortPanel
import org.siloserver.silo.tv.ui.screens.library.TvLibrarySortOption
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.tv.ui.theme.tvPageContentPadding
import org.siloserver.silo.tv.ui.theme.tvPageStartPadding
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.viewmodel.PersonalListUiState
import org.siloserver.silo.viewmodel.PersonalListViewModel
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Favorites / Watchlist / History screens all share the same `6-column grid
 * with pagination` layout — the only thing that differs is the header icon,
 * the title, and the ViewModel they read from. This file has one composable
 * per screen that forwards to a shared [PersonalGrid] helper.
 *
 * Favorites and Watchlist additionally carry the Browse Sort/Filter controls,
 * rendered as the grid's header row (the library collection page idiom) so
 * they scroll with the content and never sit over the grid's viewport.
 * History has no controls — it is a chronological log, and re-sorting it is
 * not a thing the list means.
 *
 * Navigated to from Settings → Library shortcuts (Phase F). None of the
 * three appears directly on the navigation rail, matching tvOS.
 */

/** Which overlay panel is open over a personal grid (mirrors Browse). */
private enum class TvPersonalPanel { Sort, Filter }

/** Catalog sources for the two lists that support sort/filter. */
private const val FavoritesSource = "favorites"
private const val WatchlistSource = "watchlist"

/**
 * The facet vocabulary these lists filter on. They are cross-library by
 * nature, so there is no one library type to ask about; "mixed" is any
 * non-audiobook-like value and selects the video facet set.
 */
private const val PersonalListFacetType = "mixed"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFavoritesScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val controls = rememberPersonalListControls(FavoritesSource, viewModel)
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Favorites",
        surfaceKey = "personal-favorites",
        icon = Icons.Filled.Favorite,
        emptyMessage = "No favorites yet",
        state = state,
        controls = controls,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWatchlistScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val controls = rememberPersonalListControls(WatchlistSource, viewModel)
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Watchlist",
        surfaceKey = "personal-watchlist",
        icon = Icons.Outlined.BookmarkBorder,
        emptyMessage = "Your watchlist is empty",
        state = state,
        controls = controls,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

/** Saved-list content embedded in the For You page, without secondary-page chrome. */
@Composable
fun TvFavoritesInline(
    onItemClick: (contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val controls = rememberPersonalListControls(FavoritesSource, viewModel)
    PersonalListResumeRefresh(viewModel)
    PersonalInlineGrid(
        state = state,
        controls = controls,
        emptyMessage = "No favorites yet",
        emptyIcon = Icons.Filled.Favorite,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        firstItemFocusRequester = firstItemFocusRequester,
        modifier = modifier,
    )
}

/** Saved-list content embedded in the For You page, without secondary-page chrome. */
@Composable
fun TvWatchlistInline(
    onItemClick: (contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val controls = rememberPersonalListControls(WatchlistSource, viewModel)
    PersonalListResumeRefresh(viewModel)
    PersonalInlineGrid(
        state = state,
        controls = controls,
        emptyMessage = "Your watchlist is empty",
        emptyIcon = Icons.Outlined.BookmarkBorder,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        firstItemFocusRequester = firstItemFocusRequester,
        modifier = modifier,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHistoryScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Watch History",
        surfaceKey = "personal-history",
        icon = Icons.Filled.History,
        emptyMessage = "No watch history yet",
        state = state,
        controls = null,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

/**
 * Binds a list's sort/filter holder to the shared list ViewModel that fetches
 * under it. The holder is keyed by source, so the standalone page and the For
 * You inline variant of the same list share one selection, and leaving and
 * returning within the session keeps it.
 *
 * It is resolved against the ACTIVITY's ViewModel store rather than the current
 * owner: inside the nav host the current owner is the destination's back stack
 * entry, so the inline For You surface and the standalone Favorites/Watchlist
 * destination have different stores and the key alone would hand each its own
 * holder — a sort chosen on one would not reach the other (Codex). The activity
 * is the nearest store both entries share.
 */
@Composable
private fun rememberPersonalListControls(
    source: String,
    listViewModel: PersonalListViewModel,
): TvPersonalListControlsViewModel {
    val sharedOwner = LocalActivity.current as? ViewModelStoreOwner
        ?: LocalViewModelStoreOwner.current
        ?: error("No ViewModelStoreOwner for personal list controls")
    val controls: TvPersonalListControlsViewModel = koinViewModel(
        viewModelStoreOwner = sharedOwner,
        key = "personal-controls-$source",
        parameters = { parametersOf(source) },
    )
    val controlsState by controls.uiState.collectAsState()
    // applyQuery no-ops on an unchanged query, so this is safe to re-run on
    // recomposition and on re-entry to the composition.
    LaunchedEffect(controlsState.query) {
        listViewModel.applyQuery(controlsState.query)
    }
    return controls
}

/**
 * Re-pull a personal list when the screen returns to the foreground. TV has no
 * pull-to-refresh, and these lists load once in `init` and never re-fetch on
 * their own. Card long-press toggles (favorite/watchlist/watched) write only to
 * per-card optimistic state, so an item removed here — or on any other surface —
 * would otherwise linger as a ghost entry until this back-stack entry is popped.
 * ON_RESUME re-fetch is the least-invasive self-heal (mirrors the other TV
 * screens). Gating on [PersonalListViewModel.hasLoadedOnce] (VM-scoped, not
 * composition-scoped) keeps the first-entry replay suppressed naturally: the
 * `init` load's isLoading=true covers the very first resume, and re-entering
 * the composition can't reset the gate the way a remembered flag did.
 */
@Composable
private fun PersonalListResumeRefresh(viewModel: PersonalListViewModel) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val current = viewModel.uiState.value
                if (viewModel.hasLoadedOnce && !current.isLoading && !current.isRefreshing) {
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PersonalGrid(
    title: String,
    icon: ImageVector,
    emptyMessage: String,
    surfaceKey: String,
    state: PersonalListUiState,
    controls: TvPersonalListControlsViewModel?,
    onItemClick: (contentId: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val startPadding = tvPageStartPadding()
    val gridState = rememberLazyGridState()
    val restoreItemFocusRequester = remember { FocusRequester() }
    var openPanel by remember { mutableStateOf<TvPersonalPanel?>(null) }

    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.items.map { it.contentId },
        hasMore = state.hasMore,
        isLoadingMore = state.isLoadingMore,
        // These lists refresh on every resume — exactly when a viewer comes
        // back from a detail page — and a refresh REPLACES the items with page
        // one rather than appending. Folding it into isLoadingMore was not
        // enough: a stale multi-page list still contains the target, so it
        // resolves before that flag is ever consulted. isLoading covers the
        // same shape for a sort/filter change, which reorders in place: the
        // outgoing list still contains the target at a position the incoming
        // one will not agree with.
        isReplacingContent = state.isRefreshing || state.isLoading,
        errorMessage = state.error,
        surfaceKey = surfaceKey,
        onLoadMore = onLoadMore,
        // The controls occupy a spanning grid item ahead of the cards, so an
        // ITEM index is one row-slot short of the grid index.
        scrollToItem = { itemIndex ->
            gridState.scrollToItem(itemIndex + if (controls != null) 1 else 0)
        },
        requestFocus = restoreItemFocusRequester::requestFocus,
        onRestored = onInitialContentFocus,
    )

    // No separate first-entry path. On a fresh arrival the restoration already
    // targets index zero, so the grid gives that slot to the restore requester
    // and this one was never attached — it requested focus on nothing and then
    // told the shell content had taken focus. One claimant, reporting only
    // once focus is confirmed.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.padding(
                start = startPadding,
                end = Spacing.safeArea,
                // Clear the floating top bar — Spacing.xxl left the header
                // underneath the Silo wordmark (QA 2026-07-08).
                top = TvTopMenuLayout.contentTopInset,
                bottom = Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "YOUR LIBRARY",
                style = sectionEyebrow,
                color = SiloBlue.copy(alpha = 0.92f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SiloBlue,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // History has no controls to keep on screen, so it keeps the
        // whole-surface loading and empty states it always had. The controlled
        // lists never swap the grid out: the pills have to stay reachable, and
        // a reload that hid them would strand a viewer mid-filter.
        val historyWholeSurfaceState = controls == null && state.items.isEmpty()
        when {
            historyWholeSurfaceState && state.isLoading -> TvLoadingScreen()
            // Errors too: a failed sort/filter reload leaves the list empty, and
            // a whole-surface error would take the pills away exactly when the
            // viewer needs them to undo the query that is failing — Retry only
            // repeats it. The controlled lists render the failure inside the
            // grid instead (Codex).
            historyWholeSurfaceState && state.error != null -> TvErrorScreen(
                message = state.error ?: "",
                onRetry = onRetry,
            )
            historyWholeSurfaceState -> EmptyState(message = emptyMessage, icon = icon)
            else -> {
                // Null for History, which has no controls. Stable per call site —
                // a screen either has a controls holder for its whole life or not.
                val controlsState = controls?.uiState?.collectAsState()?.value
                TvCatalogGrid(
                    items = state.items,
                    // A restored deep scroll position sits at the paging threshold,
                    // so the grid would ask for the next page the moment it lands.
                    // During a refresh that page is fetched at an offset the
                    // refresh is about to invalidate — it either gets discarded or
                    // lands after page one and leaves a hole. isLoading rather than
                    // a loading SCREEN so the header survives a sort/filter reload.
                    isLoading = state.isLoading || state.isLoadingMore || state.isRefreshing,
                    hasMore = state.hasMore,
                    onItemClick = { contentId ->
                        restoration.onItemClicked(
                            itemId = contentId,
                            index = state.items.indexOfFirst { it.contentId == contentId },
                        )
                        onItemClick(contentId)
                    },
                    onLoadMore = onLoadMore,
                    contentPadding = tvPageContentPadding(top = Spacing.lg),
                    // Match every other catalog grid (browse/person/collections):
                    // the adaptive default rendered ~5 oversized columns here
                    // (QA 2026-07-08).
                    fixedColumnCount = tvPresetGridColumns(6),
                    gridState = gridState,
                    restoreItemIndex = restoration.requesterItemIndex,
                    restoreItemFocusRequester = restoreItemFocusRequester,
                    onRestoreRequesterAttached = restoration::onRequesterAttached,
                    onItemFocusedAtIndex = { item, index, focused ->
                        if (focused) {
                            restoration.onItemFocused(item.contentId, index)
                        } else {
                            restoration.onItemFocusLost(item.contentId)
                        }
                    },
                    header = controlsState?.let { cs ->
                        {
                            PersonalControlHeader(
                                controlsState = cs,
                                total = state.total,
                                isLoading = state.isLoading,
                                onSort = { openPanel = TvPersonalPanel.Sort },
                                onFilter = { openPanel = TvPersonalPanel.Filter },
                                onClearFilters = { controls?.clearFilters() },
                            )
                        }
                    },
                    emptyState = {
                        val error = state.error
                        if (error != null) {
                            TvErrorScreen(message = error, onRetry = onRetry)
                        } else {
                            EmptyState(
                                message = if (controlsState?.facetSelection?.hasActiveFilters == true) {
                                    "No titles match the current filters."
                                } else {
                                    emptyMessage
                                },
                                icon = icon,
                            )
                        }
                    },
                )
            }
        }
    }

    PersonalControlPanels(
        controls = controls,
        openPanel = openPanel,
        onClose = { openPanel = null },
    )
}

@Composable
private fun PersonalInlineGrid(
    state: PersonalListUiState,
    controls: TvPersonalListControlsViewModel,
    emptyMessage: String,
    emptyIcon: ImageVector,
    onItemClick: (contentId: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    val controlsState by controls.uiState.collectAsState()
    var openPanel by remember { mutableStateOf<TvPersonalPanel?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // For You hands this grid the page's focus claim, and with an empty
        // list there is no card to give it to. The Sort pill takes it
        // instead — without a focusable claimant the shell's handover fails
        // and focus falls back to the menu bar. Only ever one holder: the
        // pill takes the requester exactly when no first card exists.
        //
        // Not while the first page is still in flight, though: the header
        // renders from frame one, so handing the pill the requester then
        // would let the claim succeed on it and leave the viewer parked on
        // Sort once the cards arrive. Unclaimed, the caller simply retries
        // until a card exists — which is what it did before the header did.
        val listIsEmpty = state.items.isEmpty() && !state.isLoading && !state.isRefreshing
        TvCatalogGrid(
            items = state.items,
            // A restored deep scroll position sits at the paging threshold,
            // so the grid would ask for the next page the moment it lands.
            // During a refresh that page is fetched at an offset the
            // refresh is about to invalidate — it either gets discarded or
            // lands after page one and leaves a hole. isLoading keeps the
            // grid (and its controls) mounted through a sort/filter reload.
            isLoading = state.isLoading || state.isLoadingMore || state.isRefreshing,
            hasMore = state.hasMore,
            onItemClick = onItemClick,
            onLoadMore = onLoadMore,
            contentPadding = PaddingValues(
                // For You's saved-list grid sits directly beneath its
                // selector pills; share their exact leading edge.
                start = Spacing.safeArea,
                end = Spacing.safeArea,
                top = Spacing.md,
                bottom = Spacing.xl,
            ),
            fixedColumnCount = tvPresetGridColumns(6),
            firstItemFocusRequester = firstItemFocusRequester.takeIf { !listIsEmpty },
            header = {
                PersonalControlHeader(
                    controlsState = controlsState,
                    total = state.total,
                    isLoading = state.isLoading,
                    onSort = { openPanel = TvPersonalPanel.Sort },
                    onFilter = { openPanel = TvPersonalPanel.Filter },
                    onClearFilters = controls::clearFilters,
                    sortPillFocusRequester = firstItemFocusRequester.takeIf { listIsEmpty },
                )
            },
            emptyState = {
                // Inside the grid, not over it: the pills have to stay
                // reachable so a rejected filter can be changed (Codex).
                val error = state.error
                if (error != null) {
                    TvErrorScreen(message = error, onRetry = onRetry)
                } else {
                    EmptyState(
                        message = if (controlsState.facetSelection.hasActiveFilters) {
                            "No titles match the current filters."
                        } else {
                            emptyMessage
                        },
                        icon = emptyIcon,
                    )
                }
            },
        )
    }

    PersonalControlPanels(
        controls = controls,
        openPanel = openPanel,
        onClose = { openPanel = null },
    )
}

/** Sort/Filter pills on the left, item count on the right — the grid's header row. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PersonalControlHeader(
    controlsState: TvPersonalListControlsViewModel.UiState,
    total: Int,
    isLoading: Boolean,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    onClearFilters: () -> Unit,
    sortPillFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sortOption = controlsState.sortOption
        TvBrowseControlRow(
            sortLabel = sortOption.label,
            sortDirection = sortOption.directionLabel(controlsState.order),
            filterCount = controlsState.facetSelection.activeFacetCount,
            onSort = onSort,
            onFilter = onFilter,
            onClearFilters = onClearFilters,
            sortPillFocusRequester = sortPillFocusRequester,
        )
        Spacer(modifier = Modifier.weight(1f))
        // Hidden until a page has landed, so the count never contradicts a
        // list that is still being replaced.
        if (!isLoading && total > 0) {
            Text(
                text = if (total == 1) "1 item" else "$total items",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PersonalControlPanels(
    controls: TvPersonalListControlsViewModel?,
    openPanel: TvPersonalPanel?,
    onClose: () -> Unit,
) {
    if (controls == null) return
    val controlsState by controls.uiState.collectAsState()
    when (openPanel) {
        TvPersonalPanel.Sort -> TvBrowseSortPanel(
            options = TvLibrarySortOption.availableForPersonalList(),
            currentSort = controlsState.sort,
            order = controlsState.order,
            onSelect = { option ->
                controls.onSortSelected(option)
                onClose()
            },
            onClose = onClose,
        )
        TvPersonalPanel.Filter -> TvBrowseFilterPanel(
            libraryType = PersonalListFacetType,
            facetOptions = controlsState.facetOptions,
            initial = controlsState.facetSelection,
            onApply = controls::onFacetSelectionApplied,
            onClose = onClose,
        )
        null -> Unit
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyState(
    message: String,
    icon: ImageVector,
) {
    // No focusable claimant here any more: the controlled lists park the For
    // You focus claim on the Sort pill instead, which is a real control rather
    // than an invisible focus sink over a message.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Unused import suppressor (Movie icon retained for future use).
@Suppress("unused")
private val _unused = Icons.Filled.Movie
