package org.siloserver.silo.android.ui.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.screens.requests.RequestMediaCard
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.viewmodel.RequestSearchViewModel

@Composable
fun RequestSearchSection(
    query: String,
    selectedMediaType: MobileSearchMediaType,
    requestsEnabled: Boolean,
    onRequestMediaClick: (RequestMediaResult) -> Unit,
    onRequestLibraryItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RequestSearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val trimmedQuery = query.trim()
    val requestMediaType = selectedMediaType.toRequestMediaType()
    val visibleResults = state.results.filter { result ->
        selectedMediaType.allowsRequestResult(result)
    }

    LaunchedEffect(requestsEnabled, trimmedQuery, requestMediaType) {
        if (!requestsEnabled || requestMediaType == null || trimmedQuery.length < 2) {
            viewModel.onQueryChanged("")
            return@LaunchedEffect
        }
        delay(300)
        viewModel.onMediaTypeChanged(requestMediaType)
        viewModel.onQueryChanged(trimmedQuery)
        viewModel.search()
    }

    val shouldShowSection = visibleResults.isNotEmpty() ||
        state.isLoading ||
        state.error != null ||
        state.hasSubmittedQuery

    if (!requestsEnabled || trimmedQuery.length < 2 || !shouldShowSection) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Available to request",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        when {
            visibleResults.isNotEmpty() -> {
                val rowState = rememberLazyListState()
                DeferImagePresentationWhileScrolling(rowState) {
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    items(
                        visibleResults,
                        key = { "${it.mediaType}-${it.tmdbId}" },
                        contentType = { item -> item.mediaType },
                    ) { item ->
                        RequestMediaCard(
                            item = item,
                            onClick = {
                                val libraryId = item.libraryContentId?.takeIf { it.isNotBlank() }
                                if (libraryId != null) {
                                    onRequestLibraryItemClick(libraryId)
                                } else {
                                    onRequestMediaClick(item)
                                }
                            },
                        )
                    }
                }
                }
            }
            state.isLoading -> RequestSearchFeedback("Checking requestable titles...", showProgress = true)
            state.error != null -> RequestSearchFeedback(state.error ?: "Request search failed.")
            state.hasSubmittedQuery -> RequestSearchFeedback("No requestable matches found.")
        }
    }
}

@Composable
private fun RequestSearchFeedback(
    message: String,
    showProgress: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showProgress) {
                CircularProgressIndicator()
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun MobileSearchMediaType.toRequestMediaType(): String? = when (this) {
    MobileSearchMediaType.All -> RequestMediaType.All
    MobileSearchMediaType.Video -> RequestMediaType.All
    MobileSearchMediaType.Audio -> RequestMediaType.Audiobook
    MobileSearchMediaType.Reading -> RequestMediaType.Ebook
}

private fun MobileSearchMediaType.allowsRequestResult(item: RequestMediaResult): Boolean =
    when (this) {
        MobileSearchMediaType.All -> true
        MobileSearchMediaType.Video -> item.mediaType == RequestMediaType.Movie || item.mediaType == RequestMediaType.Series
        MobileSearchMediaType.Audio -> item.mediaType == RequestMediaType.Audiobook
        MobileSearchMediaType.Reading -> item.mediaType == RequestMediaType.Ebook
    }
