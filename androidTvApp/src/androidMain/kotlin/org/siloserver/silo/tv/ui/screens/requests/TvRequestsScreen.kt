package org.siloserver.silo.tv.ui.screens.requests

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvFilterChip
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.screens.search.TV_SEARCH_QUERY_MAX_LENGTH
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import org.siloserver.silo.viewmodel.RequestsViewModel
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
    actionViewModel: TvRequestsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val searchState by searchViewModel.uiState.collectAsState()
    val actionState by actionViewModel.actionState.collectAsState()
    val visibleSearchResults = searchState.results.filterTvRequestResults()
    val visibleDiscoverSections = state.sections.filterTvRequestSections()
    val searchFieldFocusRequester = remember { FocusRequester() }
    val firstFilterChipFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val hasSubmittedQuery = searchState.hasSubmittedQuery
    val hasSearchResults = visibleSearchResults.isNotEmpty()
    val firstDiscoverSectionKey = visibleDiscoverSections.firstOrNull { it.results.isNotEmpty() }?.key
    val hasDiscoverResults = firstDiscoverSectionKey != null
    val showDiscoverRows = !hasSubmittedQuery || (searchState.error != null && hasDiscoverResults)
    val hasFocusableResult = hasSearchResults || (showDiscoverRows && hasDiscoverResults)
    var initialFocusRequested by remember { mutableStateOf(false) }
    var focusResultsAfterSearch by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<RequestMediaResult?>(null) }
    // The highest successGeneration this screen has already refreshed for.
    // rememberSaveable so a restored composition (config change, tab
    // save/restore) doesn't re-run the success refresh for a create that was
    // handled before the screen went away.
    var consumedSuccessGeneration by rememberSaveable { mutableStateOf(0) }

    fun refreshRequests() {
        actionViewModel.clearNotices()
        viewModel.refresh()
    }

    fun handleRequestItemClick(item: RequestMediaResult) {
        if (actionState.submittingKey != null) return
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

    // Once a create succeeds, refresh discover/search so the just-requested card
    // reflects its new status. The create itself runs in the ViewModel's scope
    // (see TvRequestsViewModel) so it survives navigating away; this only tidies
    // the rows when the user is still on this screen. Only UNCONSUMED
    // generations fire the refresh — re-entering the screen with the ViewModel
    // still holding a past success must not re-fire it; instead the stale
    // "Request submitted." notice is dropped so it doesn't resurface.
    LaunchedEffect(actionState.successGeneration) {
        if (actionState.successGeneration > consumedSuccessGeneration) {
            consumedSuccessGeneration = actionState.successGeneration
            viewModel.refresh()
            if (searchState.query.isNotBlank()) {
                searchViewModel.search(searchState.page)
            }
        } else if (actionState.message != null) {
            // Already-consumed success on re-entry: clear the success banner
            // only (errors stay visible until acted on).
            actionViewModel.consumeSuccessNotice()
        }
    }

    pendingRequest?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRequest = null },
            containerColor = DarkSurfaceElevated,
            titleContentColor = SiloOnSurface,
            textContentColor = SiloOnSurface.copy(alpha = 0.76f),
            title = { Text("Request ${item.title}?") },
            text = { Text("This will submit a ${item.mediaType} request for your profile.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRequest = null
                        actionViewModel.submit(item.requestKey(), item.toCreateMediaRequest())
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
                isRefreshing = state.isRefreshing || actionState.submittingKey != null,
                searchFieldFocusRequester = searchFieldFocusRequester,
                firstFilterChipFocusRequester = firstFilterChipFocusRequester,
                firstResultFocusRequester = firstResultFocusRequester,
                hasFocusableResult = hasFocusableResult,
                onQueryChanged = { query -> searchViewModel.onQueryChanged(query) },
                onSearch = {
                    focusResultsAfterSearch = searchState.query.isNotBlank()
                    searchViewModel.search()
                },
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
                    if (actionState.error != null || searchState.error != null || state.error != null || actionState.message != null) {
                        item(contentType = "request-notice") {
                            RequestNotice(
                                text = actionState.error ?: searchState.error ?: state.error ?: actionState.message.orEmpty(),
                                isError = actionState.error != null || searchState.error != null || state.error != null,
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
                                submittingKey = actionState.submittingKey,
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
                                submittingKey = actionState.submittingKey,
                                onItemClick = ::handleRequestItemClick,
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
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
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
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
                color = SiloBlue.copy(alpha = 0.92f),
            )
            if (searchStatus != null) {
                RequestSearchStatusPill(text = searchStatus)
            }
        }
        Column(
            modifier = Modifier.widthIn(max = 410.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { onQueryChanged(it.take(TV_SEARCH_QUERY_MAX_LENGTH)) },
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Search movies and series to request",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.56f),
                    )
                },
                leadingIcon = {
                    M3Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.72f),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRequester(searchFieldFocusRequester)
                    .focusProperties { down = firstFilterChipFocusRequester },
                colors = tvOutlinedTextFieldColors(),
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
        color = if (isError) MaterialTheme.colorScheme.error else SiloBlue,
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
