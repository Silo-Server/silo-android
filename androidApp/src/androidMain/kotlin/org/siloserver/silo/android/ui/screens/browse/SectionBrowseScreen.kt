package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.viewmodel.SectionBrowseArgs
import org.siloserver.silo.viewmodel.SectionBrowseViewModel

/**
 * A Home or library row's full page, opened from its "Explore all" card or
 * its title: the row's titles in a paged grid under the row's title.
 */
@Composable
fun SectionBrowseScreen(
    args: SectionBrowseArgs,
    onBackClick: () -> Unit,
    onItemClick: (BrowseItem) -> Unit,
    viewModel: SectionBrowseViewModel = koinViewModel(
        key = "section-${args.libraryId ?: "home"}-${args.sectionId}-${args.mediaScope.orEmpty()}",
        parameters = { parametersOf(args) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { SiloTopBar(title = args.title, onBackClick = onBackClick) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when {
            state.unavailable -> EmptyStateView(
                title = "This row is no longer available",
                subtitle = "It may have been removed or hidden.",
                icon = Icons.Outlined.VisibilityOff,
                modifier = contentModifier,
            )
            state.isLoading && state.items.isEmpty() -> LoadingIndicator(modifier = contentModifier)
            state.error != null -> ErrorView(
                message = state.error ?: "Couldn't load this row.",
                onRetry = viewModel::retry,
                modifier = contentModifier,
            )
            state.items.isEmpty() -> EmptyStateView(
                title = "Nothing here yet",
                subtitle = "This row has no titles right now.",
                icon = Icons.Outlined.VideoLibrary,
                modifier = contentModifier,
            )
            else -> Box(modifier = contentModifier.fillMaxSize()) {
                CatalogGrid(
                    items = state.items,
                    isLoadingMore = state.isLoadingMore,
                    // A failed page waits for Retry instead of re-firing as the grid settles.
                    hasMore = state.hasMore && state.loadMoreError == null,
                    onItemClick = { contentId ->
                        state.items.firstOrNull { it.contentId == contentId }?.let(onItemClick)
                    },
                    onLoadMore = viewModel::loadMore,
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.loadMoreError != null) {
                    LoadMoreRetryBar(
                        onRetry = viewModel::loadMore,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreRetryBar(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Couldn't load more",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
