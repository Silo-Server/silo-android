package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.components.skeleton
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.viewmodel.RequestDiscoverRowViewModel

/**
 * Every title of one row of the server's Discover layout, loaded as the
 * viewer scrolls: the hub row's See all page.
 */
@Composable
fun RequestDiscoverRowScreen(
    rowKey: String,
    title: String,
    onBackClick: () -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestDiscoverRowViewModel = koinViewModel(key = "discover-row:$rowKey") {
        parametersOf(rowKey, title)
    },
) {
    val state by viewModel.uiState.collectAsState()
    val router = rememberRequestRouter(koinInject(), onLibraryItemClick, onRequestDetailClick)

    RequestsLargeTitlePage(
        title = state.title,
        onBackClick = onBackClick,
        isRefreshing = false,
        onRefresh = {},
    ) {
        when {
            state.notFound -> item(key = "gone") {
                EmptyStateView(
                    title = "This row is gone",
                    subtitle = "The server's Discover page no longer shows it",
                    icon = Icons.Outlined.Search,
                    modifier = Modifier.padding(top = 80.dp),
                )
            }
            state.error != null && state.results.isEmpty() -> item(key = "error") {
                ErrorView(message = state.error.orEmpty(), onRetry = viewModel::loadMore, modifier = Modifier.padding(top = 60.dp))
            }
            state.isLoading && state.results.isEmpty() -> item(key = "loading") {
                val progress = rememberShimmerProgress()
                SearchGrid(count = 9) { width, _ ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(modifier = Modifier.width(width).aspectRatio(RequestPosterAspect).skeleton(progress))
                        Box(modifier = Modifier.width(width * 0.7f).height(10.dp).skeleton(progress, RoundedCornerShape(3.dp)))
                    }
                }
            }
            state.results.isEmpty() -> item(key = "empty") {
                EmptyStateView(
                    title = "Nothing here yet",
                    subtitle = "This row has no titles right now",
                    icon = Icons.Outlined.Search,
                    modifier = Modifier.padding(top = 80.dp),
                )
            }
            else -> {
                item(key = "grid") {
                    SearchGrid(count = state.results.size) { width, index ->
                        val result = state.results[index]
                        RequestMediaCard(item = result, width = width, onClick = { router.openResult(result) })
                    }
                }
                if (!state.endReached) {
                    item(key = "more") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            if (state.moreFailed) {
                                TextButton(onClick = viewModel::loadMore) { Text("Couldn't load more. Try again") }
                            } else {
                                // Composed once scrolled into view: read the next page.
                                LaunchedEffect(state.results.size) { viewModel.loadMore() }
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
