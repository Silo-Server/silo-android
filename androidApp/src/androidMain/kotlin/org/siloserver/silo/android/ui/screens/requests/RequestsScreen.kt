package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.components.skeleton
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.RequestRouter
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.viewmodel.RequestsUiState
import org.siloserver.silo.viewmodel.RequestsViewModel

/**
 * The Requests hub, in the same grammar as the library pages: search TMDB to
 * request, a status summary and the user's own requests one glance down, then
 * the discover carousels.
 */
@Composable
fun RequestsScreen(
    onBackClick: () -> Unit,
    onMyRequestsClick: () -> Unit,
    onApprovalsClick: () -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val router = rememberRequestRouter(koinInject(), onLibraryItemClick, onRequestDetailClick)

    RequestsLargeTitlePage(
        title = "Requests",
        onBackClick = onBackClick,
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
    ) {
        item(key = "search", contentType = "search") {
            RequestsSearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChanged,
                onSearch = viewModel::submitSearch,
            )
        }
        when {
            !state.isShowingDiscover -> searchResults(state, router::openResult)
            state.error != null -> item(key = "error") {
                ErrorView(message = state.error.orEmpty(), onRetry = viewModel::load, modifier = Modifier.padding(top = 60.dp))
            }
            state.isLoading -> item(key = "loading") {
                val progress = rememberShimmerProgress()
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    RequestRailSkeleton(progress, title = "Your requests")
                    RequestRailSkeleton(progress)
                }
            }
            state.myRequests.isEmpty() && state.sections.isEmpty() -> item(key = "empty") {
                EmptyStateView(
                    title = "Nothing here yet",
                    subtitle = "Search for a movie or series to request it",
                    icon = Icons.Outlined.AutoAwesome,
                    modifier = Modifier.padding(top = 80.dp),
                )
            }
            else -> discover(state, router, onMyRequestsClick, onApprovalsClick)
        }
    }
}

private fun LazyListScope.discover(
    state: RequestsUiState,
    router: RequestRouter,
    onMyRequestsClick: () -> Unit,
    onApprovalsClick: () -> Unit,
) {
    val moving = state.inProgressCount + state.needsAttentionCount
    if (moving > 0 || state.pendingApprovals > 0) {
        item(key = "summary", contentType = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (moving > 0) {
                    RequestSummaryCard(title = summaryTitle(state), parts = summaryParts(state), onClick = onMyRequestsClick)
                }
                if (state.pendingApprovals > 0) {
                    RequestSummaryCard(
                        title = when {
                            state.morePendingApprovals -> "${state.pendingApprovals}+ requests need your approval"
                            state.pendingApprovals == 1 -> "1 request needs your approval"
                            else -> "${state.pendingApprovals} requests need your approval"
                        },
                        leadingIcon = Icons.Outlined.HowToReg,
                        leadingTint = RequestColors.Amber,
                        onClick = onApprovalsClick,
                    )
                }
            }
        }
    }
    if (state.myRequests.isNotEmpty()) {
        item(key = "mine", contentType = "rail") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RequestsSectionHeader(title = "Your requests", trailing = "See all", onTrailingClick = onMyRequestsClick)
                RequestCardRail(items = state.myRequests, key = { it.id }) { record ->
                    RequestRecordCard(record = record, onClick = { router.openRecord(record) })
                }
            }
        }
    }
    state.sections.forEachIndexed { index, section ->
        item(key = "discover:${section.key}", contentType = "rail") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RequestsSectionHeader(title = section.title, label = if (index == 0) "Discover" else null)
                RequestCardRail(items = section.results, key = { "${it.mediaType}:${it.tmdbId}" }) { result ->
                    RequestMediaCard(item = result, onClick = { router.openResult(result) })
                }
            }
        }
    }
}

private fun summaryTitle(state: RequestsUiState): String {
    val count = state.inProgressCount
    return when (count) {
        0 -> "Your requests need you"
        1 -> "1 request in progress"
        else -> "$count requests in progress"
    }
}

private fun summaryParts(state: RequestsUiState): List<Pair<RequestStatusTint, String>> = buildList {
    if (state.onTheWayCount > 0) add(RequestStatusTint.Sky to "${state.onTheWayCount} on the way")
    if (state.pendingCount > 0) add(RequestStatusTint.Amber to "${state.pendingCount} pending")
    if (state.needsAttentionCount > 0) add(RequestStatusTint.Rose to "${state.needsAttentionCount} need you")
}

/** Library search's grid: adaptive columns from the card presentation, placeholders in the same cells while searching. */
private fun LazyListScope.searchResults(state: RequestsUiState, onOpen: (RequestMediaResult) -> Unit) {
    when {
        state.isSearching && state.searchResults.isEmpty() -> item(key = "search-loading") {
            SearchGrid(count = 9) { width, _ ->
                val progress = rememberShimmerProgress()
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        modifier = Modifier.width(width).aspectRatio(RequestPosterAspect).skeleton(progress),
                    )
                    Box(
                        modifier = Modifier.width(width * 0.7f).height(10.dp).skeleton(progress, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
        state.hasSearched && state.searchResults.isEmpty() -> item(key = "search-empty") {
            EmptyStateView(
                title = "No matches",
                subtitle = "Nothing on TMDB matched that search",
                icon = Icons.Outlined.Search,
                modifier = Modifier.padding(top = 80.dp),
            )
        }
        else -> {
            item(key = "search-count") {
                Text(
                    text = "${state.searchTotal} result${if (state.searchTotal == 1) "" else "s"}",
                    fontSize = 12.sp,
                    color = SiloSecondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "search-grid") {
                SearchGrid(count = state.searchResults.size) { width, index ->
                    val result = state.searchResults[index]
                    RequestMediaCard(item = result, width = width, onClick = { onOpen(result) })
                }
            }
        }
    }
}

@Composable
private fun SearchGrid(count: Int, cell: @Composable (width: Dp, index: Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = requestGridColumns(maxWidth, MediaGridDefaults.scaledPosterGridMinWidth)
        val spacing = MediaGridDefaults.PosterGridHorizontalSpacing
        val cellWidth = (maxWidth - 32.dp - spacing * (columns - 1)) / columns
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
        ) {
            (0 until count).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { index -> cell(cellWidth, index) }
                }
            }
        }
    }
}
