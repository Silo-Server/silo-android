package org.siloserver.silo.android.ui.screens.personal

import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.PosterGridSkeleton
import org.siloserver.silo.android.ui.screens.browse.CatalogGrid
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.components.MediaCardContextMenu
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.common.ui.components.SpoilerImage
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardWatchStatusBadge
import org.siloserver.silo.overlays.CardWatchStatus
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.viewmodel.PersonalListQuery
import org.siloserver.silo.viewmodel.PersonalListUiState
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.personal.PersonalMediaFilter
import org.siloserver.silo.common.personal.rememberPersonalMediaFilterStore
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale

/** Empty-state copy for a Movies or TV Shows view that has nothing to show. */
private val PersonalMediaFilter.emptyText: Pair<Int, Int>?
    get() = when (this) {
        PersonalMediaFilter.All -> null
        PersonalMediaFilter.Movies -> CommonR.string.personal_movies_empty to CommonR.string.personal_movies_emptyBody
        PersonalMediaFilter.TvShows -> CommonR.string.personal_tvShows_empty to CommonR.string.personal_tvShows_emptyBody
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalMediaFilterSelector(
    selected: PersonalMediaFilter,
    onSelected: (PersonalMediaFilter) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        PersonalMediaFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, PersonalMediaFilter.entries.size),
                label = { androidx.compose.material3.Text(stringResource(filter.label)) },
            )
        }
    }
}

@Composable
fun FavoritesGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    /** Sort/filter to fetch with; null keeps whatever the ViewModel has. */
    query: PersonalListQuery? = null,
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val filterStore = rememberPersonalMediaFilterStore()
    val mediaFilter by filterStore.filter.collectAsState()
    if (query != null) {
        LaunchedEffect(query) { viewModel.applyQuery(query) }
    }

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = { listState ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonalMediaFilterSelector(mediaFilter, onSelected = filterStore::select)
                header?.invoke(listState)
            }
        },
        mediaFilter = mediaFilter,
        emptyTitle = stringResource(CommonR.string.personal_favorites_empty),
        emptySubtitle = stringResource(CommonR.string.personal_favorites_emptyTapHint),
        emptyIcon = Icons.Outlined.FavoriteBorder,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onItemClick = onItemClick,
    )
}

@Composable
fun WatchlistGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    /** Sort/filter to fetch with; null keeps whatever the ViewModel has. */
    query: PersonalListQuery? = null,
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val filterStore = rememberPersonalMediaFilterStore()
    val mediaFilter by filterStore.filter.collectAsState()
    if (query != null) {
        LaunchedEffect(query) { viewModel.applyQuery(query) }
    }

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = { listState ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonalMediaFilterSelector(mediaFilter, onSelected = filterStore::select)
                header?.invoke(listState)
            }
        },
        mediaFilter = mediaFilter,
        emptyTitle = stringResource(CommonR.string.personal_watchlist_empty),
        emptySubtitle = stringResource(CommonR.string.personal_watchlist_emptyTapHint),
        emptyIcon = Icons.Outlined.BookmarkBorder,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onItemClick = onItemClick,
    )
}

@Composable
fun HistoryGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = header,
        emptyTitle = stringResource(CommonR.string.personal_history_empty),
        emptySubtitle = stringResource(CommonR.string.personal_history_emptyBody),
        emptyIcon = Icons.Outlined.History,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onItemClick = onItemClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalMediaGridContent(
    state: PersonalListUiState,
    emptyTitle: String,
    emptySubtitle: String,
    emptyIcon: ImageVector,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Optional full-width row that scrolls with the grid (For You's saved-list
    // pills, the sort/filter controls). It also renders above the loading /
    // empty / error views so the controls stay reachable when the list has
    // nothing to show. Receives the state so it can show the item count.
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    // Null for History, which has no media switch.
    mediaFilter: PersonalMediaFilter? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    val visibleItems = remember(state.items, mediaFilter) { mediaFilter?.apply(state.items) ?: state.items }
    // The header's count describes what is showing, not the whole list.
    val headerState = mediaFilter?.let { state.copy(total = it.shownTotal(state, visibleItems.size)) } ?: state
    // Keyed on the loaded count and refresh too, so the effect re-runs after a
    // page or refresh that added no matches; otherwise every key can come back
    // unchanged and a narrowed view stays empty while pages remain.
    LaunchedEffect(visibleItems.isEmpty(), state.items.size, state.hasMore, state.isLoadingMore, state.isLoading, state.isRefreshing) {
        if (visibleItems.isEmpty() && state.hasMore && !state.isLoadingMore && !state.isLoading && !state.isRefreshing) {
            onLoadMore()
        }
    }
    when {
        state.isLoading -> {
            // Poster-grid skeleton (not a spinner) so the list keeps its shape
            // while it loads; the header's controls stay reachable above it.
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(headerState) } }
                PosterGridSkeleton(
                    progress = rememberShimmerProgress(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.error != null -> {
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(headerState) } }
                ErrorView(
                    message = state.error?.text() ?: stringResource(CommonR.string.catalog_error_unknown),
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        visibleItems.isEmpty() -> {
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(headerState) } }
                // A narrowed query with no hits is not an empty list — say so,
                // and keep the header's controls reachable to widen it (TV parity).
                val filtered = !state.query.isDefault
                val typeEmpty = mediaFilter?.emptyText?.takeIf { state.items.isNotEmpty() }
                EmptyStateView(
                    title = when {
                        filtered -> stringResource(CommonR.string.personal_noMatches)
                        typeEmpty != null -> stringResource(typeEmpty.first)
                        else -> emptyTitle
                    },
                    subtitle = when {
                        filtered -> stringResource(CommonR.string.personal_noMatchesBody)
                        typeEmpty != null -> stringResource(typeEmpty.second)
                        else -> emptySubtitle
                    },
                    icon = emptyIcon,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        else -> {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize(),
            ) {
                // The library's own grid: adaptive columns, MediaCard, and
                // load-more. contentPadding becomes insets inside the grid so
                // items scroll edge to edge under any chrome the caller
                // reserved space for.
                CatalogGrid(
                    items = visibleItems,
                    isLoadingMore = state.isLoadingMore,
                    hasMore = state.hasMore && !state.isLoading,
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                    topContentInset = contentPadding.calculateTopPadding(),
                    bottomContentInset = contentPadding.calculateBottomPadding(),
                    header = header?.let { content -> { content(headerState) } },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = contentPadding.calculateStartPadding(layoutDirection),
                            end = contentPadding.calculateEndPadding(layoutDirection),
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: BrowseItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFavoriteToggle: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onWatchlistToggle: (() -> Unit)? = null,
    isInWatchlist: Boolean = false,
) {
    val (actions, userState, positionSeconds) = rememberBrowseItemCardActions(item)
    val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
    val hideArtwork = EpisodeSpoilers.hidesBrowseArtwork(item, spoilerPrefs, userState.played, positionSeconds = positionSeconds)
    val hideDetailArtwork = EpisodeSpoilers.hidesBrowseArtwork(item, spoilerPrefs, userState.played, selectBackdrop = true, positionSeconds = positionSeconds)
    val overlayState = LocalCardOverlayUiState.current
    var menuExpanded by remember { mutableStateOf(false) }
    val heroHandoff = LocalHeroSourceHandoff.current

    androidx.compose.foundation.layout.Column(
        modifier = modifier.combinedClickable(
            onClick = {
                heroHandoff?.pendingArtworkUrl = (item.backdropUrl ?: item.posterUrl).takeUnless { hideDetailArtwork }
                heroHandoff?.pendingArtworkThumbhash = if (hideDetailArtwork) null else item.backdropThumbhash ?: item.posterThumbhash
                onClick()
            },
            onLongClick = { menuExpanded = true },
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3.3f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            SpoilerImage(
                hidden = hideArtwork,
                url = item.posterUrl,
                thumbhash = item.posterThumbhash,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            if (overlayState.enabled) {
                CardOverlays(
                    data = OverlayDataExtractor.fromBrowseItem(item),
                    prefs = overlayState.prefs,
                    variant = CardOverlayVariant.Poster,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CardWatchStatusBadge(CardWatchStatus.of(userState), Modifier.fillMaxSize())
        }

        androidx.compose.material3.Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (item.year > 0) {
            androidx.compose.material3.Text(
                text = LocaleFormat.integer(item.year, currentNumberLocale()),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MediaCardContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = actions,
            isPlayed = userState.played,
            isFavorite = userState.isFavorite,
            isInWatchlist = userState.inWatchlist,
        )
    }
}
