package org.siloserver.silo.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import org.siloserver.silo.viewmodel.SectionBrowseArgs
import org.siloserver.silo.viewmodel.SectionBrowseViewModel

/**
 * A Home or library row's full page, opened from the row's "Explore all"
 * card: the row's titles in a paged poster grid under the row's title.
 */
@Composable
fun TvSectionBrowseScreen(
    args: SectionBrowseArgs,
    onItemClick: (BrowseItem) -> Unit,
    onBack: () -> Unit,
    viewModel: SectionBrowseViewModel = koinViewModel(
        key = "section-${args.libraryId ?: "home"}-${args.sectionId}-${args.mediaScope.orEmpty()}",
        parameters = { parametersOf(args) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    // Land on the first card once, so the D-pad responds without a hunt; a
    // later page must not pull focus back to the top.
    val firstItemFocusRequester = remember { FocusRequester() }
    var firstCardHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (initialFocusRequested || state.items.isEmpty()) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstItemFocusRequester::requestFocus,
            isFocused = { firstCardHasFocus },
        )
        initialFocusRequested = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.unavailable -> PageMessage(
                title = args.title,
                message = "This row is no longer available.",
            )
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null -> TvErrorScreen(message = state.error!!, onRetry = viewModel::retry)
            else -> TvCatalogGrid(
                items = state.items,
                isLoading = state.isLoadingMore,
                hasMore = state.hasMore,
                onItemClick = { contentId ->
                    state.items.firstOrNull { it.contentId == contentId }?.let(onItemClick)
                },
                onLoadMore = viewModel::loadMore,
                fixedColumnCount = tvPresetGridColumns(6),
                contentPadding = PaddingValues(
                    start = Spacing.safeArea,
                    top = Spacing.xxl,
                    end = Spacing.safeArea,
                    bottom = Spacing.xxxl,
                ),
                horizontalSpacing = 20.dp,
                verticalSpacing = 30.dp,
                firstItemFocusRequester = firstItemFocusRequester,
                firstItemCardModifier = Modifier.onFocusChanged { firstCardHasFocus = it.isFocused },
                header = { PageTitle(args.title) },
                emptyState = { TvCatalogEmptyState(message = "This row has no titles right now.") },
            )
        }
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Title over a centered message, for a page with nothing to focus. */
@Composable
private fun PageMessage(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.safeArea, top = Spacing.xxl, end = Spacing.safeArea),
    ) {
        PageTitle(title)
        TvCatalogEmptyState(message = message)
    }
}
