package com.continuum.app.tv.ui.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.model.request.CreateMediaRequest
import com.continuum.app.model.request.RequestAvailability
import com.continuum.app.model.request.RequestDiscoverySection
import com.continuum.app.model.request.RequestMediaResult
import com.continuum.app.model.request.RequestMediaType
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.RequestsRepository
import com.continuum.app.tv.ui.components.TvErrorScreen
import com.continuum.app.tv.ui.components.TvFilterChip
import com.continuum.app.tv.ui.components.TvLoadingScreen
import com.continuum.app.tv.ui.components.applyTvAnsiKeyboardAction
import com.continuum.app.tv.ui.screens.search.SearchDisplayField
import com.continuum.app.tv.ui.screens.search.SiloSearchKeyboard
import com.continuum.app.tv.ui.screens.search.TV_SEARCH_QUERY_MAX_LENGTH
import com.continuum.app.tv.ui.shell.TvTopMenuLayout
import com.continuum.app.tv.ui.theme.ContinuumBlue
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.sectionEyebrow
import com.continuum.app.viewmodel.RequestSearchViewModel
import com.continuum.app.viewmodel.RequestsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val requestMediaFilters = listOf(
    RequestMediaType.All to "All",
    RequestMediaType.Movie to "Movies",
    RequestMediaType.Series to "Series",
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestsScreen(
    onOpenLibraryItem: (contentId: String) -> Unit,
    onOpenMyRequests: () -> Unit,
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit = { _, _ -> },
    onInitialContentFocus: () -> Unit = {},
    viewModel: RequestsViewModel = koinViewModel(),
    searchViewModel: RequestSearchViewModel = koinViewModel(),
    repository: RequestsRepository = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val searchState by searchViewModel.uiState.collectAsState()
    val visibleSearchResults = searchState.results.filterTvRequestResults()
    val visibleDiscoverSections = state.sections.filterTvRequestSections()
    val searchFieldFocusRequester = remember { FocusRequester() }
    val firstFilterChipFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val requestKeyboardFirstKeyFocusRequester = remember { FocusRequester() }
    val hasSubmittedQuery = searchState.hasSubmittedQuery
    val hasSearchResults = visibleSearchResults.isNotEmpty()
    val firstDiscoverSectionKey = visibleDiscoverSections.firstOrNull { it.results.isNotEmpty() }?.key
    val hasDiscoverResults = firstDiscoverSectionKey != null
    val showDiscoverRows = !hasSubmittedQuery || (searchState.error != null && hasDiscoverResults)
    val hasFocusableResult = hasSearchResults || (showDiscoverRows && hasDiscoverResults)
    var initialFocusRequested by remember { mutableStateOf(false) }
    var focusResultsAfterSearch by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<RequestMediaResult?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var submittingKey by remember { mutableStateOf<String?>(null) }
    var isRequestKeyboardVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshRequests() {
        actionError = null
        actionMessage = null
        viewModel.refresh()
    }

    fun handleRequestItemClick(item: RequestMediaResult) {
        if (submittingKey != null) return
        when {
            item.canOpenLibraryDetail() -> onOpenLibraryItem(item.libraryContentId.orEmpty())
            item.canRequest() -> pendingRequest = item
            // Non-actionable (already requested / pending / unavailable): open the
            // request detail so the user can see metadata + current status, instead
            // of just a one-shot message (phone parity).
            else -> onOpenRequestDetail(item.mediaType, item.tmdbId)
        }
    }

    LaunchedEffect(searchFieldFocusRequester) {
        if (initialFocusRequested) return@LaunchedEffect
        runCatching { searchFieldFocusRequester.requestFocus() }
        onInitialContentFocus()
        initialFocusRequested = true
    }

    LaunchedEffect(focusResultsAfterSearch, searchState.isLoading, hasSearchResults) {
        if (focusResultsAfterSearch && !searchState.isLoading) {
            if (hasSearchResults) {
                runCatching { firstResultFocusRequester.requestFocus() }
            } else {
                runCatching { firstFilterChipFocusRequester.requestFocus() }
            }
            focusResultsAfterSearch = false
        }
    }
    LaunchedEffect(isRequestKeyboardVisible) {
        if (isRequestKeyboardVisible) {
            delay(120)
            runCatching { requestKeyboardFirstKeyFocusRequester.requestFocus() }
        }
    }

    pendingRequest?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRequest = null },
            title = { Text("Request ${item.title}?") },
            text = { Text("This will submit a ${item.mediaType} request for your profile.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRequest = null
                        actionError = null
                        actionMessage = null
                        submittingKey = item.requestKey()
                        scope.launch {
                            when (val result = repository.create(item.toCreateMediaRequest())) {
                                is ApiResult.Success -> {
                                    actionMessage = "Request submitted."
                                    viewModel.refresh()
                                    if (searchState.query.isNotBlank()) {
                                        searchViewModel.search(searchState.page)
                                    }
                                }
                                is ApiResult.Error -> {
                                    actionError = result.message.ifBlank { "Failed to submit request" }
                                }
                                is ApiResult.NetworkError -> {
                                    actionError = "Network error. Check your connection."
                                }
                            }
                            submittingKey = null
                        }
                    },
                ) {
                    Text("Request")
                }
            },
            dismissButton = {
                Button(onClick = { pendingRequest = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RequestsHeader(
                query = searchState.query,
                mediaType = searchState.mediaType,
                searchStatus = requestSearchStatusText(
                    query = searchState.submittedQuery,
                    total = visibleSearchResults.size,
                    isSearching = searchState.isLoading,
                    error = searchState.error,
                ),
                isRefreshing = state.isRefreshing || submittingKey != null,
                searchFieldFocusRequester = searchFieldFocusRequester,
                firstFilterChipFocusRequester = firstFilterChipFocusRequester,
                firstResultFocusRequester = firstResultFocusRequester,
                hasFocusableResult = hasFocusableResult,
                onOpenKeyboard = { isRequestKeyboardVisible = true },
                onMediaTypeChanged = { type ->
                    searchViewModel.onMediaTypeChanged(type)
                    if (searchState.query.isNotBlank()) {
                        searchViewModel.search()
                    }
                },
                onRefresh = ::refreshRequests,
                onOpenMyRequests = onOpenMyRequests,
            )
            when {
                state.isLoading && state.sections.isEmpty() -> TvLoadingScreen()
                state.error != null && state.sections.isEmpty() && !hasSubmittedQuery -> TvErrorScreen(
                    message = state.error ?: "Requests are unavailable.",
                    onRetry = viewModel::load,
                )
                visibleDiscoverSections.none { it.results.isNotEmpty() } && !hasSubmittedQuery -> RequestsEmptyState(
                    message = state.error ?: "Search movies and series to request them.",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 56.dp),
                ) {
                    if (actionError != null || searchState.error != null || state.error != null || actionMessage != null) {
                        item(contentType = "request-notice") {
                            RequestNotice(
                                text = actionError ?: searchState.error ?: state.error ?: actionMessage.orEmpty(),
                                isError = actionError != null || searchState.error != null || state.error != null,
                            )
                        }
                    }

                    searchEmptyMessage(
                        query = searchState.query,
                        isSearching = searchState.isLoading,
                        hasSubmittedQuery = hasSubmittedQuery,
                        hasSearchResults = hasSearchResults,
                        hasSearchError = searchState.error != null,
                    )?.let { message ->
                        item(key = "search-empty", contentType = "request-search-empty") {
                            RequestSearchEmptyItem(message = message)
                        }
                    }

                    if (hasSearchResults) {
                        item(key = "search-results", contentType = "request-search-results") {
                            RequestResultRow(
                                title = "Search Results",
                                results = visibleSearchResults,
                                firstItemFocusRequester = firstResultFocusRequester,
                                firstItemCardModifier = Modifier.focusProperties {
                                    up = firstFilterChipFocusRequester
                                },
                                submittingKey = submittingKey,
                                onItemClick = ::handleRequestItemClick,
                            )
                        }
                    }

                    if (showDiscoverRows) {
                        items(
                            visibleDiscoverSections,
                            key = { it.key },
                            contentType = { "request-section" },
                        ) { section ->
                            RequestSectionRow(
                                section = section,
                                firstItemFocusRequester = firstResultFocusRequester
                                    .takeIf { !hasSearchResults && section.key == firstDiscoverSectionKey },
                                firstItemCardModifier = Modifier.focusProperties {
                                    up = firstFilterChipFocusRequester
                                },
                                submittingKey = submittingKey,
                                onItemClick = ::handleRequestItemClick,
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }

        if (isRequestKeyboardVisible) {
            SiloSearchKeyboard(
                value = searchState.query,
                enabled = !searchState.isLoading,
                firstKeyFocusRequester = requestKeyboardFirstKeyFocusRequester,
                onAction = { action ->
                    searchViewModel.onQueryChanged(
                        applyTvAnsiKeyboardAction(
                            value = searchState.query,
                            action = action,
                            maxLength = TV_SEARCH_QUERY_MAX_LENGTH,
                        ),
                    )
                },
                onSearch = {
                    isRequestKeyboardVisible = false
                    focusResultsAfterSearch = searchState.query.isNotBlank()
                    searchViewModel.search()
                },
                onDismiss = {
                    isRequestKeyboardVisible = false
                    runCatching { searchFieldFocusRequester.requestFocus() }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 48.dp, end = 48.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun RequestSectionRow(
    section: RequestDiscoverySection,
    firstItemFocusRequester: FocusRequester?,
    firstItemCardModifier: Modifier = Modifier,
    submittingKey: String?,
    onItemClick: (RequestMediaResult) -> Unit,
) {
    RequestResultRow(
        title = section.title,
        results = section.results,
        firstItemFocusRequester = firstItemFocusRequester,
        firstItemCardModifier = firstItemCardModifier,
        submittingKey = submittingKey,
        onItemClick = onItemClick,
    )
}

@Composable
private fun RequestResultRow(
    title: String,
    results: List<RequestMediaResult>,
    firstItemFocusRequester: FocusRequester?,
    firstItemCardModifier: Modifier = Modifier,
    submittingKey: String?,
    onItemClick: (RequestMediaResult) -> Unit,
) {
    if (results.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = Spacing.safeArea),
        )
        LazyRow(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                end = Spacing.safeArea,
                top = 10.dp,
                bottom = 22.dp,
            ),
        ) {
            itemsIndexed(
                results,
                key = { _, item -> item.requestKey() },
                contentType = { _, _ -> "request-result" },
            ) { index, item ->
                TvRequestCard(
                    result = if (submittingKey == item.requestKey()) {
                        item.copy(request = item.request.copy(status = "submitting", requestable = false))
                    } else {
                        item
                    },
                    onClick = { onItemClick(item) },
                    focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                    cardModifier = if (index == 0) firstItemCardModifier else Modifier,
                )
            }
        }
    }
}

@Composable
private fun RequestSearchEmptyItem(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.72f),
        modifier = Modifier.padding(horizontal = Spacing.safeArea),
    )
}

@Composable
private fun RequestsHeader(
    query: String,
    mediaType: String?,
    searchStatus: String?,
    isRefreshing: Boolean,
    searchFieldFocusRequester: FocusRequester,
    firstFilterChipFocusRequester: FocusRequester,
    firstResultFocusRequester: FocusRequester,
    hasFocusableResult: Boolean,
    onOpenKeyboard: () -> Unit,
    onMediaTypeChanged: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenMyRequests: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = Spacing.safeArea,
            end = Spacing.safeArea,
            top = TvTopMenuLayout.contentTopInset,
            bottom = Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "REQUESTS",
                style = sectionEyebrow,
                color = ContinuumBlue.copy(alpha = 0.92f),
            )
            if (searchStatus != null) {
                RequestSearchStatusPill(text = searchStatus)
            }
        }
        Column(
            modifier = Modifier.widthIn(max = 410.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SearchDisplayField(
                value = query,
                hint = "Search movies and series to request",
                enabled = true,
                focusRequester = searchFieldFocusRequester,
                onOpenKeyboard = onOpenKeyboard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .focusProperties { down = firstFilterChipFocusRequester },
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(requestMediaFilters) { index, (type, label) ->
                    val chipModifier = Modifier
                        .then(
                            if (index == 0) {
                                Modifier.focusRequester(firstFilterChipFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (index == 0 && hasFocusableResult) {
                                Modifier.focusProperties { down = firstResultFocusRequester }
                            } else {
                                Modifier
                            },
                        )
                    TvFilterChip(
                        text = label,
                        selected = mediaType == type,
                        onClick = { onMediaTypeChanged(type) },
                        modifier = chipModifier,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Find Something New",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvRequestActionPill(
                    label = "My Requests",
                    icon = Icons.Filled.Inbox,
                    onClick = onOpenMyRequests,
                )
                TvRequestActionPill(
                    label = if (isRefreshing) "Refreshing" else "Refresh",
                    icon = Icons.Filled.Refresh,
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                )
            }
        }
    }
}

@Composable
private fun RequestsEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.tv.material3.Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.height(30.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun RequestSearchStatusPill(text: String) {
    val shape = RoundedCornerShape(100.dp)
    Box(
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .background(Color.White.copy(alpha = 0.045f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.68f),
        )
    }
}

@Composable
private fun RequestNotice(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else ContinuumBlue,
        modifier = Modifier.padding(horizontal = Spacing.safeArea),
    )
}

private fun RequestMediaResult.toCreateMediaRequest(): CreateMediaRequest = CreateMediaRequest(
    mediaType = mediaType,
    tmdbId = tmdbId,
    title = title,
    year = year,
    overview = overview,
    posterPath = posterPath,
    backdropPath = backdropPath,
)

private fun RequestMediaResult.requestKey(): String = "$mediaType-$tmdbId"

private fun RequestMediaResult.nonActionableMessage(): String = when {
    availability == RequestAvailability.Available -> "This title is already in your library."
    request.status?.isNotBlank() == true -> "$title is already ${request.status}."
    request.reason.isNotBlank() -> request.reason
    else -> "This title cannot be requested right now."
}

private fun searchEmptyMessage(
    query: String,
    isSearching: Boolean,
    hasSubmittedQuery: Boolean,
    hasSearchResults: Boolean,
    hasSearchError: Boolean,
): String? = when {
    !hasSubmittedQuery -> "Search movies and series to request them."
    isSearching -> "Searching..."
    hasSearchError -> null
    hasSearchResults -> null
    else -> "No matches for \"${query.trim()}\"."
}

private fun requestSearchStatusText(
    query: String,
    total: Int,
    isSearching: Boolean,
    error: String?,
): String? = when {
    query.isBlank() -> null
    isSearching -> "Searching..."
    error != null && total == 0 -> "Search unavailable"
    total == 0 -> "No results"
    total == 1 -> "1 result"
    else -> "$total results"
}
