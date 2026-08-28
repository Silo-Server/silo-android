package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import org.siloserver.silo.viewmodel.RequestsViewModel
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    onBackClick: () -> Unit,
    onMyRequestsClick: () -> Unit,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestsViewModel = koinViewModel(),
    searchViewModel: RequestSearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val searchState by searchViewModel.uiState.collectAsState()
    val hasSubmittedQuery = searchState.hasSubmittedQuery
    val hasSearchResults = searchState.results.isNotEmpty()
    val showDiscoverRows = !hasSubmittedQuery ||
        (searchState.error != null && state.sections.any { it.results.isNotEmpty() })

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Requests",
                onBackClick = onBackClick,
                actions = {
                    TextButton(onClick = onMyRequestsClick) {
                        Text("My Requests")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading && state.sections.isEmpty() -> {
                LoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            state.error != null && state.sections.isEmpty() && !state.isEnabled && !hasSubmittedQuery -> {
                RequestErrorState(
                    message = state.error ?: "Requests are unavailable.",
                    onRetry = viewModel::load,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    val feedState = rememberLazyListState()
                    DeferImagePresentationWhileScrolling(feedState) {
                    LazyColumn(
                        state = feedState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item(contentType = "request-search") {
                            RequestSearchPanel(
                                query = searchState.query,
                                selectedType = searchState.mediaType,
                                isLoading = searchState.isLoading,
                                error = searchState.error,
                                totalResults = searchState.totalResults,
                                results = searchState.results,
                                hasSubmittedQuery = hasSubmittedQuery,
                                onQueryChange = searchViewModel::onQueryChanged,
                                onTypeChange = { type ->
                                    searchViewModel.onMediaTypeChanged(type)
                                    if (searchState.query.isNotBlank()) {
                                        searchViewModel.search()
                                    }
                                },
                                onSearch = { searchViewModel.search() },
                                onMediaClick = onMediaClick,
                                onLibraryItemClick = onLibraryItemClick,
                            )
                        }

                        state.error?.let { error ->
                            item(contentType = "request-error") {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        if (state.sections.isEmpty() && !hasSubmittedQuery) {
                            item(contentType = "request-empty") {
                                EmptyStateView(
                                    title = "No request suggestions yet",
                                    subtitle = "Search for movies or series to request.",
                                    icon = Icons.Outlined.Movie,
                                )
                            }
                        } else if (showDiscoverRows) {
                            items(
                                state.sections,
                                key = { it.key },
                                contentType = { "request-section" },
                            ) { section ->
                                RequestSectionRow(
                                    title = section.title,
                                    items = section.results,
                                    onMediaClick = onMediaClick,
                                    onLibraryItemClick = onLibraryItemClick,
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestSearchPanel(
    query: String,
    selectedType: String?,
    isLoading: Boolean,
    error: String?,
    totalResults: Int,
    results: List<RequestMediaResult>,
    hasSubmittedQuery: Boolean,
    onQueryChange: (String) -> Unit,
    onTypeChange: (String?) -> Unit,
    onSearch: () -> Unit,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
) {
    val summary = requestSearchSummary(
        query = query,
        totalResults = totalResults,
        isLoading = isLoading,
        hasSubmittedQuery = hasSubmittedQuery,
        hasResults = results.isNotEmpty(),
        error = error,
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Movie or series title") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestFilterChip(
                selected = selectedType == RequestMediaType.All,
                label = "All",
                onClick = { onTypeChange(RequestMediaType.All) },
            )
            RequestFilterChip(
                selected = selectedType == RequestMediaType.Movie,
                label = "Movies",
                onClick = { onTypeChange(RequestMediaType.Movie) },
            )
            RequestFilterChip(
                selected = selectedType == RequestMediaType.Series,
                label = "Series",
                onClick = { onTypeChange(RequestMediaType.Series) },
            )
        }
        Button(onClick = onSearch, enabled = !isLoading) {
            Text(if (isLoading) "Searching..." else "Search")
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (results.isNotEmpty()) {
            RequestMediaRow(
                items = results,
                onMediaClick = onMediaClick,
                onLibraryItemClick = onLibraryItemClick,
            )
        }
    }
}

@Composable
private fun RequestSectionRow(
    title: String,
    items: List<RequestMediaResult>,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        RequestMediaRow(
            items = items,
            onMediaClick = onMediaClick,
            onLibraryItemClick = onLibraryItemClick,
        )
    }
}

@Composable
private fun RequestMediaRow(
    items: List<RequestMediaResult>,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
) {
    val rowState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(rowState) {
    LazyRow(
        state = rowState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 16.dp),
    ) {
        items(
            items,
            key = { "${it.mediaType}-${it.tmdbId}" },
            contentType = { item -> item.mediaType },
        ) { item ->
            RequestMediaCard(
                item = item,
                onClick = {
                    val libraryId = item.libraryContentId?.takeIf { it.isNotBlank() }
                    if (libraryId != null) {
                        onLibraryItemClick(libraryId)
                    } else {
                        onMediaClick(item)
                    }
                },
            )
        }
    }
    }
}

@Composable
private fun RequestErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

private fun requestSearchSummary(
    query: String,
    totalResults: Int,
    isLoading: Boolean,
    hasSubmittedQuery: Boolean,
    hasResults: Boolean,
    error: String?,
): String? = when {
    query.isBlank() -> "Search movies and series to request them."
    isLoading -> "Searching..."
    error != null -> null
    hasSubmittedQuery && !hasResults -> "No matches for \"$query\"."
    hasResults && totalResults == 1 -> "1 result"
    hasResults -> "$totalResults results"
    else -> null
}
