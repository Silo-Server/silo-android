package org.siloserver.silo.tv.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvFilterChip
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.screens.requests.TvRequestCard
import org.siloserver.silo.tv.ui.screens.requests.canOpenLibraryDetail
import org.siloserver.silo.tv.ui.screens.requests.filterTvRequestResults
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.ElevatedSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.tv.ui.theme.tvPageContentPadding
import org.siloserver.silo.tv.ui.theme.tvPageStartPadding
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSearchScreen(
    onResultClick: (BrowseItem) -> Unit,
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit,
    onOpenLibraryItem: (contentId: String) -> Unit,
    searchFieldFocusRequester: FocusRequester? = null,
    viewModel: TvSearchViewModel = koinViewModel(),
    requestSearchViewModel: RequestSearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val requestState by requestSearchViewModel.uiState.collectAsState()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val firstResultFocusRequester = remember { FocusRequester() }
    val firstRequestResultFocusRequester = remember { FocusRequester() }
    val firstFilterChipFocusRequester = remember { FocusRequester() }
    val internalSearchFieldFocusRequester = remember { FocusRequester() }
    val activeSearchFieldFocusRequester = searchFieldFocusRequester ?: internalSearchFieldFocusRequester
    var pendingSearchFocus by remember { mutableStateOf(false) }
    val requestMediaType = state.mediaType.toRequestMediaType()
    val visibleRequestResults = requestState.results
        .filterTvRequestResults()
        .filter { state.mediaType.allowsRequestResult(it) }
    val canSearchRequests = requestsEnabled && state.query.trim().length >= 2
    val shouldShowRequestSection = canSearchRequests &&
        (visibleRequestResults.isNotEmpty() ||
            requestState.isLoading ||
            requestState.error != null ||
            requestState.hasSubmittedQuery)
    val requestSearchSettled = !canSearchRequests || !requestState.isLoading
    val firstContentFocusRequester = when {
        state.items.isNotEmpty() -> firstResultFocusRequester
        visibleRequestResults.isNotEmpty() -> firstRequestResultFocusRequester
        else -> firstFilterChipFocusRequester
    }

    LaunchedEffect(requestsEnabled, state.query, requestMediaType) {
        val query = state.query.trim()
        if (!requestsEnabled || query.length < 2) {
            requestSearchViewModel.onQueryChanged("")
            return@LaunchedEffect
        }
        delay(300)
        requestSearchViewModel.onMediaTypeChanged(requestMediaType)
        requestSearchViewModel.onQueryChanged(query)
        requestSearchViewModel.search()
    }

    LaunchedEffect(activeSearchFieldFocusRequester) {
        runCatching { activeSearchFieldFocusRequester.requestFocus() }
    }
    LaunchedEffect(
        pendingSearchFocus,
        state.isLoading,
        requestSearchSettled,
        state.items.size,
        visibleRequestResults.size,
    ) {
        if (!pendingSearchFocus || state.isLoading || !requestSearchSettled) return@LaunchedEffect
        pendingSearchFocus = false
        runCatching {
            if (state.items.isNotEmpty()) {
                firstResultFocusRequester.requestFocus()
            } else if (visibleRequestResults.isNotEmpty()) {
                firstRequestResultFocusRequester.requestFocus()
            } else {
                firstFilterChipFocusRequester.requestFocus()
            }
        }
    }
    // Note: we deliberately do NOT auto-jump focus to the first result when
    // it appears. Doing so during the debounced as-you-type search yanks
    // focus out of text entry mid-keystroke. Instead, the explicit
    // focusProperties wired below give the user three reliable handoffs:
    //   • search field —DOWN→ first chip
    //   • first chip   —DOWN→ first card (when results exist)
    //   • first card   —UP→   first chip
    // The IME Search action submits the query without stealing focus.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvCatalogGrid(
            items = state.items,
            isLoading = state.isLoading || state.isLoadingMore,
            hasMore = state.hasMore,
            onItemClick = { },
            onBrowseItemClick = onResultClick,
            onLoadMore = viewModel::loadMore,
            modifier = Modifier.fillMaxSize(),
            minCellWidth = 152.dp,
            contentPadding = tvPageContentPadding(
                top = TvTopMenuLayout.contentTopInset - 16.dp,
                bottom = Spacing.xxxl,
                end = 24.dp,
                expandedGap = Spacing.md,
            ),
            horizontalSpacing = 14.dp,
            verticalSpacing = 20.dp,
            firstItemFocusRequester = firstResultFocusRequester,
            // UP from the first card always lands back on the filter chip rail.
            // Without this Compose's spatial focus search can prefer the wider
            // search field above and skip over the smaller chip row.
            firstItemCardModifier = Modifier.focusProperties {
                up = firstFilterChipFocusRequester
            },
            header = {
                SearchStage(
                    query = state.query,
                    mediaType = state.mediaType,
                    availableMediaTypes = state.availableMediaTypes,
                    resultStatus = searchStatusText(
                        query = state.query,
                        total = state.total,
                        isSearching = state.isLoading,
                        error = state.error,
                    ),
                    hasResults = state.items.isNotEmpty() || visibleRequestResults.isNotEmpty(),
                    searchFieldFocusRequester = activeSearchFieldFocusRequester,
                    firstFilterChipFocusRequester = firstFilterChipFocusRequester,
                    firstResultFocusRequester = firstContentFocusRequester,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearch = {
                        pendingSearchFocus = true
                        val query = state.query.trim()
                        if (requestsEnabled && query.length >= 2) {
                            requestSearchViewModel.onMediaTypeChanged(requestMediaType)
                            requestSearchViewModel.onQueryChanged(query)
                            requestSearchViewModel.search()
                        }
                        viewModel.submitSearch()
                    },
                    onMediaTypeChanged = viewModel::onMediaTypeChanged,
                )
            },
            footer = {
                TvRequestSearchSection(
                    query = state.query,
                    requestsEnabled = requestsEnabled,
                    isLoading = requestState.isLoading,
                    error = requestState.error,
                    hasSubmittedQuery = requestState.hasSubmittedQuery,
                    results = visibleRequestResults,
                    shouldShow = shouldShowRequestSection,
                    firstItemFocusRequester = firstRequestResultFocusRequester,
                    firstItemCardModifier = Modifier.focusProperties {
                        up = if (state.items.isNotEmpty()) firstResultFocusRequester else firstFilterChipFocusRequester
                    },
                    onOpenRequestDetail = onOpenRequestDetail,
                    onOpenLibraryItem = onOpenLibraryItem,
                )
            },
            emptyState = {
                when {
                    state.query.isBlank() -> SearchFeedbackCard(
                        title = "Start typing to search your library",
                        body = "Results update as you type, and Search jumps straight into the grid.",
                    )
                    state.error != null -> SearchFeedbackCard(
                        title = "Search is unavailable right now",
                        body = state.error!!,
                    )
                    else -> SearchFeedbackCard(
                        title = "No matches for “${state.query}”",
                        body = "Try a shorter title or switch media filters.",
                    )
                }
            },
        )

    }
}

@Composable
private fun TvRequestSearchSection(
    query: String,
    requestsEnabled: Boolean,
    isLoading: Boolean,
    error: String?,
    hasSubmittedQuery: Boolean,
    results: List<RequestMediaResult>,
    shouldShow: Boolean,
    firstItemFocusRequester: FocusRequester,
    firstItemCardModifier: Modifier,
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit,
    onOpenLibraryItem: (contentId: String) -> Unit,
) {
    if (!requestsEnabled || query.trim().length < 2 || !shouldShow) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = tvPageStartPadding(expandedGap = Spacing.md),
                end = 24.dp,
                top = 4.dp,
                bottom = 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Available to request",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        when {
            results.isNotEmpty() -> {
                LazyRow(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(end = Spacing.safeArea),
                ) {
                    itemsIndexed(
                        results,
                        key = { _, item -> "${item.mediaType}-${item.tmdbId}" },
                        contentType = { _, _ -> "request-search-result" },
                    ) { index, item ->
                        TvRequestCard(
                            result = item,
                            onClick = {
                                if (item.canOpenLibraryDetail()) {
                                    onOpenLibraryItem(item.libraryContentId.orEmpty())
                                } else {
                                    onOpenRequestDetail(item.mediaType, item.tmdbId)
                                }
                            },
                            focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                            cardModifier = if (index == 0) firstItemCardModifier else Modifier,
                        )
                    }
                }
            }
            isLoading -> RequestSearchFeedbackRow("Checking requestable titles...", showProgress = true)
            error != null -> RequestSearchFeedbackRow(error)
            hasSubmittedQuery -> RequestSearchFeedbackRow("No requestable matches found.")
        }
    }
}

@Composable
private fun RequestSearchFeedbackRow(
    message: String,
    showProgress: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchStage(
    query: String,
    mediaType: TvSearchMediaType,
    availableMediaTypes: List<TvSearchMediaType>,
    resultStatus: String?,
    hasResults: Boolean,
    searchFieldFocusRequester: FocusRequester,
    firstFilterChipFocusRequester: FocusRequester,
    firstResultFocusRequester: FocusRequester,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onMediaTypeChanged: (TvSearchMediaType) -> Unit,
) {
    val startPadding = tvPageStartPadding(expandedGap = Spacing.md)
    val mediaTypes = availableMediaTypes

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPadding,
                end = 24.dp,
                top = 0.dp,
                bottom = Spacing.xs,
            )
            .widthIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SEARCH",
                style = sectionEyebrow,
                color = SiloBlue.copy(alpha = 0.78f),
            )

            if (resultStatus != null) {
                SearchStatusPill(text = resultStatus)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChanged(it.take(TV_SEARCH_QUERY_MAX_LENGTH)) },
            singleLine = true,
            placeholder = {
                Text(
                    text = "Search titles, movies, series, and audiobooks",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.56f),
                )
            },
            leadingIcon = {
                M3Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(18.dp),
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
                // Pin DOWN to the chip rail so the user can always step from
                // the search field onto the All/Movies/Series filters,
                // regardless of whether result cards are also rendered below.
                .focusRequester(searchFieldFocusRequester)
                .focusProperties { down = firstFilterChipFocusRequester },
            colors = tvOutlinedTextFieldColors(),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                mediaTypes,
                contentType = { _, _ -> "media-type-chip" },
            ) { index, type ->
                val chipModifier = Modifier
                    .then(
                        if (index == 0) {
                            Modifier.focusRequester(firstFilterChipFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Only redirect DOWN when there's actually a card to
                        // land on — pointing at an unattached FocusRequester
                        // makes the key event a no-op and traps the user.
                        if (hasResults) {
                            Modifier.focusProperties { down = firstResultFocusRequester }
                        } else {
                            Modifier
                        },
                    )
                TvFilterChip(
                    text = type.label,
                    selected = mediaType == type,
                    onClick = { onMediaTypeChanged(type) },
                    modifier = chipModifier,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SearchStatusPill(text: String) {
    val shape = RoundedCornerShape(100.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.sp,
                lineHeight = 13.sp,
            ),
            color = Color.White.copy(alpha = 0.68f),
        )
    }
}

@Composable
private fun SearchFeedbackCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(ElevatedSurface)
                .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                M3Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }
        }
    }
}

private fun searchStatusText(
    query: String,
    total: Int,
    isSearching: Boolean,
    error: String?,
): String? = when {
    query.isBlank() -> null
    isSearching -> "Searching…"
    error != null && total == 0 -> "Search unavailable"
    total == 0 -> "No results"
    total == 1 -> "1 result"
    else -> "$total results"
}

private fun TvSearchMediaType.toRequestMediaType(): String = when (this) {
    TvSearchMediaType.All -> RequestMediaType.All
    TvSearchMediaType.Movies -> RequestMediaType.Movie
    TvSearchMediaType.Series -> RequestMediaType.Series
    TvSearchMediaType.Audiobooks -> RequestMediaType.Audiobook
}

private fun TvSearchMediaType.allowsRequestResult(item: RequestMediaResult): Boolean =
    when (this) {
        TvSearchMediaType.All -> true
        TvSearchMediaType.Movies -> item.mediaType == RequestMediaType.Movie
        TvSearchMediaType.Series -> item.mediaType == RequestMediaType.Series
        TvSearchMediaType.Audiobooks -> item.mediaType == RequestMediaType.Audiobook
    }
