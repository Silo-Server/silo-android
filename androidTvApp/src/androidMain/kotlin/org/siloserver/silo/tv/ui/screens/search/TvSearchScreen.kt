package org.siloserver.silo.tv.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
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
import org.siloserver.silo.tv.ui.theme.ElevatedSurface
import org.siloserver.silo.tv.ui.theme.Spacing
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
    backToSearchFieldRequest: Int = 0,
    onSearchFieldFocusChanged: (Boolean) -> Unit = {},
    viewModel: TvSearchViewModel = koinViewModel(),
    requestSearchViewModel: RequestSearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val requestState by requestSearchViewModel.uiState.collectAsState()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val firstResultFocusRequester = remember { FocusRequester() }
    val firstRequestResultFocusRequester = remember { FocusRequester() }
    val feedbackActionFocusRequester = remember { FocusRequester() }
    val firstFilterChipFocusRequester = remember { FocusRequester() }
    val internalSearchFieldFocusRequester = remember { FocusRequester() }
    val searchGridState = rememberLazyGridState()
    val activeSearchFieldFocusRequester = searchFieldFocusRequester ?: internalSearchFieldFocusRequester
    val keyboardController = LocalSoftwareKeyboardController.current
    var pendingSearchFocus by remember { mutableStateOf(false) }
    val requestMediaType = state.mediaType.toRequestMediaType()
    val visibleRequestResults = requestState.results
        .filterTvRequestResults()
        .filter { state.mediaType.allowsRequestResult(it) }
        .filter { state.showAudiobooks || it.mediaType != RequestMediaType.Audiobook }
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
        state.error != null -> feedbackActionFocusRequester
        else -> firstFilterChipFocusRequester
    }
    val hasContentFocusTarget = state.items.isNotEmpty() ||
        visibleRequestResults.isNotEmpty() ||
        state.error != null

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
    LaunchedEffect(backToSearchFieldRequest) {
        if (backToSearchFieldRequest <= 0) return@LaunchedEffect
        searchGridState.animateScrollToItem(0)
        androidx.compose.runtime.withFrameNanos { }
        runCatching { activeSearchFieldFocusRequester.requestFocus() }
        keyboardController?.show()
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
            } else if (state.error != null) {
                feedbackActionFocusRequester.requestFocus()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvCatalogGrid(
            items = state.items,
            // Reset searches keep the existing grid stable and report progress
            // in the pinned status line. Only pagination owns a grid spinner.
            isLoading = state.isLoadingMore,
            hasMore = state.hasMore,
            onItemClick = { },
            onBrowseItemClick = onResultClick,
            onLoadMore = viewModel::loadMore,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            gridState = searchGridState,
            minCellWidth = 132.dp,
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                top = Spacing.sm,
                bottom = Spacing.xxxl,
                end = 24.dp,
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
                        isPartialCount = state.mediaType == TvSearchMediaType.All && state.hasMore,
                    ),
                    hasContentFocusTarget = hasContentFocusTarget,
                    searchFieldFocusRequester = activeSearchFieldFocusRequester,
                    firstFilterChipFocusRequester = firstFilterChipFocusRequester,
                    firstContentFocusRequester = firstContentFocusRequester,
                    onSearchFieldFocusChanged = { focused ->
                        onSearchFieldFocusChanged(focused)
                        if (focused) keyboardController?.show()
                    },
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
                    state.query.isBlank() -> SearchFeedbackMessage(
                        title = "Search your library",
                        body = availableMediaDescription(state.availableMediaTypes),
                    )
                    state.isLoading -> Box(modifier = Modifier.height(64.dp))
                    state.error != null -> SearchFeedbackMessage(
                        title = "Search is unavailable",
                        body = state.error!!,
                        actionLabel = "Try again",
                        actionFocusRequester = feedbackActionFocusRequester,
                        actionUpFocusRequester = firstFilterChipFocusRequester,
                        onAction = viewModel::submitSearch,
                    )
                    else -> SearchFeedbackMessage(
                        title = "No matches for “${state.query}”",
                        body = "Try a shorter title or a different filter.",
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
                start = Spacing.safeArea,
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SearchStage(
    query: String,
    mediaType: TvSearchMediaType,
    availableMediaTypes: List<TvSearchMediaType>,
    resultStatus: String?,
    hasContentFocusTarget: Boolean,
    searchFieldFocusRequester: FocusRequester,
    firstFilterChipFocusRequester: FocusRequester,
    firstContentFocusRequester: FocusRequester,
    onSearchFieldFocusChanged: (Boolean) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onMediaTypeChanged: (TvSearchMediaType) -> Unit,
) {
    val mediaTypes = availableMediaTypes
    val fieldShape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.safeArea,
                end = 24.dp,
                top = TvTopMenuLayout.contentTopInset - 12.dp,
                bottom = Spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChanged(it.take(TV_SEARCH_QUERY_MAX_LENGTH)) },
            singleLine = true,
            placeholder = {
                Text(
                    text = searchFieldPrompt(mediaTypes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.56f),
                )
            },
            leadingIcon = {
                M3Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(20.dp),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            shape = fieldShape,
            modifier = Modifier
                .width(520.dp)
                .height(52.dp)
                // Pin DOWN to the chip rail so the user can always step from
                // the search field onto the All/Movies/Series filters,
                // regardless of whether result cards are also rendered below.
                .focusRequester(searchFieldFocusRequester)
                .onFocusChanged { onSearchFieldFocusChanged(it.isFocused) }
                .focusProperties { down = firstFilterChipFocusRequester },
            colors = tvOutlinedTextFieldColors(
                focusedContainerColor = ElevatedSurface,
                unfocusedContainerColor = Color.White.copy(alpha = 0.055f),
                focusedBorderColor = Color.White.copy(alpha = 0.94f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            ),
        )

        LazyRow(
            modifier = Modifier.focusRestorer(firstFilterChipFocusRequester),
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
                        if (hasContentFocusTarget) {
                            Modifier.focusProperties { down = firstContentFocusRequester }
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

        // A fixed-height slot keeps the header completely stable while the
        // query moves between typing, loading, results, and error states.
        Box(
            modifier = Modifier.height(18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (resultStatus != null) {
                Text(
                    text = resultStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }
        }
    }
}

@Composable
private fun SearchFeedbackMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionFocusRequester: FocusRequester? = null,
    actionUpFocusRequester: FocusRequester? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        M3Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.34f),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f),
            )

            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .then(
                            if (actionFocusRequester != null) {
                                Modifier.focusRequester(actionFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (actionUpFocusRequester != null) {
                                Modifier.focusProperties { up = actionUpFocusRequester }
                            } else {
                                Modifier
                            },
                        ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun searchFieldPrompt(mediaTypes: List<TvSearchMediaType>): String =
    "Search ${availableMediaNames(mediaTypes)}"

private fun availableMediaDescription(mediaTypes: List<TvSearchMediaType>): String =
    "Find ${availableMediaNames(mediaTypes)} in one place."

private fun availableMediaNames(mediaTypes: List<TvSearchMediaType>): String {
    val names = mediaTypes
        .filterNot { it == TvSearchMediaType.All }
        .map { it.label.lowercase() }

    return when (names.size) {
        0 -> "your library"
        1 -> names.single()
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + ", and ${names.last()}"
    }
}

private fun searchStatusText(
    query: String,
    total: Int,
    isSearching: Boolean,
    error: String?,
    isPartialCount: Boolean,
): String? = when {
    query.isBlank() -> null
    isSearching -> "Searching…"
    error != null -> "Couldn't update results"
    total == 0 -> "No results"
    total == 1 -> "1 result"
    isPartialCount -> "$total+ results"
    else -> "$total results"
}

/*
 * Search stays a live discovery surface: the field and filters are pinned,
 * while the grid below is the only scrolling region. This keeps the user's
 * query visible and avoids racing the platform IME with scroll corrections.
 *
 * TV_SEARCH_QUERY_MAX_LENGTH lives in TvSearchInputRules.kt (shared with
 * TvRequestsScreen); a private duplicate here collided with it.
 */

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
