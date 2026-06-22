package com.continuum.app.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.components.MediaCard
import com.continuum.app.android.ui.theme.ContinuumSurfaceElevated
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.repository.RecommendationRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.compose.koinInject

/**
 * Horizontal poster rail of "More Like This" items shown at the bottom
 * of Movie / Series detail pages. Mirrors `PhoneSimilarRail.swift`:
 *   1. Hit `/recommendations/similar/{contentId}` for scored IDs
 *   2. Resolve each ID to an `ItemDetail` in parallel
 *   3. Render a poster card per resolved item; tap opens detail
 *
 * Hidden when the request fails or returns nothing — recommendations
 * are non-essential, so a missing rail is preferable to an error placeholder.
 */
@Composable
fun SimilarRail(
    contentId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    recommendationRepository: RecommendationRepository = koinInject(),
    catalogRepository: CatalogRepository = koinInject(),
) {
    var items by remember(contentId) { mutableStateOf<List<ItemDetail>>(emptyList()) }
    var isLoading by remember(contentId) { mutableStateOf(true) }

    LaunchedEffect(contentId) {
        isLoading = true
        items = emptyList()
        val resolved = loadSimilar(contentId, recommendationRepository, catalogRepository)
        items = resolved
        isLoading = false
    }

    when {
        isLoading -> SimilarRailPlaceholder(modifier = modifier)
        items.isNotEmpty() -> SimilarRailContent(items = items, onSelect = onSelect, modifier = modifier)
    }
}

private suspend fun loadSimilar(
    contentId: String,
    recommendationRepository: RecommendationRepository,
    catalogRepository: CatalogRepository,
): List<ItemDetail> {
    val scored = when (val res = recommendationRepository.getSimilar(contentId, limit = 12)) {
        is ApiResult.Success -> res.data.items
        else -> return emptyList()
    }
    if (scored.isEmpty()) return emptyList()

    // Resolve detail pages in parallel — preserve engine ranking by
    // dropping null results (failed lookups) without reordering.
    return coroutineScope {
        scored
            .map { ref ->
                async {
                    when (val r = catalogRepository.getItemDetail(ref.mediaItemId)) {
                        is ApiResult.Success -> r.data
                        else -> null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}

@Composable
private fun SimilarRailContent(
    items: List<ItemDetail>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items,
            key = { it.contentId },
            contentType = { "similar-item" },
        ) { item ->
            MediaCard(
                title = item.title,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                year = item.year.takeIf { it > 0 },
                type = item.type,
                userState = null,
                progress = null,
                onClick = { onSelect(item.contentId) },
                overlay = com.continuum.app.overlays.OverlayDataExtractor.fromItemDetail(item),
                sharedContentId = item.contentId,
            )
        }
    }
}

@Composable
private fun SimilarRailPlaceholder(modifier: Modifier = Modifier) {
    // iOS PhoneSimilarRail.loadingPlaceholder: poster-sized skeletons
    // (120×198, corner 12, surfaceElevated fill), spacing 12.
    LazyRow(
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(4, contentType = { "similar-placeholder" }) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(198.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ContinuumSurfaceElevated),
            )
        }
    }
}
