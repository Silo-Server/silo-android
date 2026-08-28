package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestDisplayLabel
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailScreen(
    mediaType: String,
    tmdbId: Int,
    onBackClick: () -> Unit,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestDetailViewModel = koinViewModel(
        parameters = { parametersOf(mediaType to tmdbId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Request Details",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading && state.detail == null -> {
                LoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            state.error != null && state.detail == null -> {
                EmptyStateView(
                    title = "Details unavailable",
                    subtitle = state.error ?: "Failed to load this title.",
                    icon = Icons.Outlined.ErrorOutline,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            state.detail != null -> {
                RequestDetailContent(
                    detail = requireNotNull(state.detail),
                    isSubmitting = state.isSubmitting,
                    error = state.error,
                    notice = state.notice,
                    onRequest = viewModel::submitRequest,
                    onRetry = viewModel::load,
                    onMediaClick = onMediaClick,
                    onLibraryItemClick = onLibraryItemClick,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun RequestDetailContent(
    detail: RequestMediaDetail,
    isSubmitting: Boolean,
    error: String?,
    notice: String?,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    onMediaClick: (RequestMediaResult) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(listState) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                ThumbhashImage(
                    url = requestBackdropUrl(detail.backdropPath) ?: requestPosterUrl(detail.posterPath),
                    thumbhash = null,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(116.dp)
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    ThumbhashImage(
                        url = requestPosterUrl(detail.posterPath),
                        thumbhash = null,
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    RequestMetaLine(mediaType = detail.mediaType, year = detail.year)
                    Text(
                        text = detail.basicFacts(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RequestActions(
                        detail = detail,
                        isSubmitting = isSubmitting,
                        onRequest = onRequest,
                        onLibraryItemClick = onLibraryItemClick,
                    )
                }
            }
        }
        notice?.let {
            item {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        error?.let {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
        if (detail.overview.isNotBlank()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Overview",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = detail.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            if (detail.recommendations.isEmpty()) {
                EmptyStateView(
                    title = "No recommendations",
                    subtitle = "Similar titles will appear here when the server returns them.",
                    icon = Icons.Outlined.Info,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Recommendations",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    val recRowState = rememberLazyListState()
                    DeferImagePresentationWhileScrolling(recRowState) {
                    LazyRow(
                        state = recRowState,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(detail.recommendations, key = { "${it.mediaType}-${it.tmdbId}" }) { item ->
                            RequestMediaCard(
                                item = item,
                                onClick = {
                                    val libraryId = item.libraryContentId?.takeIf { it.isNotBlank() }
                                    if (libraryId != null) onLibraryItemClick(libraryId) else onMediaClick(item)
                                },
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
private fun RequestActions(
    detail: RequestMediaDetail,
    isSubmitting: Boolean,
    onRequest: () -> Unit,
    onLibraryItemClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val libraryContentId = detail.libraryContentId?.takeIf { it.isNotBlank() }
        when {
            libraryContentId != null -> {
                Button(onClick = { onLibraryItemClick(libraryContentId) }) {
                    Text("Open Library Item")
                }
            }
            detail.request.requestable -> {
                Button(
                    onClick = onRequest,
                    enabled = !isSubmitting,
                ) {
                    Text(if (isSubmitting) "Requesting..." else "Request")
                }
            }
            else -> {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                ) {
                    Text(
                        text = detail.request.status
                            ?.takeIf { it.isNotBlank() }
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "Unavailable",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        RequestDetailStatus(detail)
    }
}

@Composable
private fun RequestDetailStatus(detail: RequestMediaDetail) {
    val status = detail.request.reason
        .takeIf { it.isNotBlank() }
        ?: detail.availability.requestDisplayLabel()
    Text(
        text = status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun RequestMediaDetail.basicFacts(): String {
    val facts = buildList {
        runtime?.let { add("${it}m") }
        if (genres.isNotEmpty()) add(genres.take(2).joinToString(", "))
        voteAverage?.let { add("TMDB ${"%.1f".format(it)}") }
        if (numberOfSeasons != null) add("$numberOfSeasons seasons")
    }
    return facts.joinToString(" • ").ifBlank { availability.requestDisplayLabel() }
}
