package org.siloserver.silo.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TvLibraryCollectionDetailScreen(
    libraryId: Int,
    collectionId: String,
    title: String,
    onItemClick: (contentId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: TvLibraryCollectionDetailViewModel = koinViewModel(
        key = "library-collection-$libraryId-$collectionId",
        parameters = { parametersOf(libraryId, collectionId, title) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    // Without an explicit focus target, the user lands on this screen with
    // nothing focused and has to mash D-pad before anything responds.
    val firstItemFocusRequester = remember { FocusRequester() }
    var collectionHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.firstOrNull()?.contentId) {
        if (initialFocusRequested || state.items.isEmpty()) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstItemFocusRequester::requestFocus,
            isFocused = { collectionHasFocus },
        )
        initialFocusRequested = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { collectionHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = viewModel.title.ifBlank { title },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = Spacing.safeArea,
                top = Spacing.xxl,
                end = Spacing.safeArea,
                bottom = Spacing.lg,
            ),
        )

        when {
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null && state.items.isEmpty() -> TvErrorScreen(
                message = state.error!!,
                onRetry = viewModel::retry,
            )
            else -> TvCatalogGrid(
                items = state.items,
                isLoading = state.isLoadingMore,
                hasMore = state.hasMore,
                onItemClick = onItemClick,
                onLoadMore = viewModel::loadMore,
                fixedColumnCount = 6,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.safeArea,
                    top = Spacing.lg,
                    end = Spacing.safeArea,
                    bottom = Spacing.xxxl,
                ),
                horizontalSpacing = 12.dp,
                firstItemFocusRequester = firstItemFocusRequester,
                emptyState = {
                    TvCatalogEmptyState(message = "This collection is empty.")
                },
            )
        }
    }
}
