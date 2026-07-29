package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.overlays.OverlayDataExtractor

/**
 * A vertical grid of media cards with infinite-scroll support.
 *
     * Uses the shared iOS-style adaptive poster grid with automatic load-more
     * triggering when the user scrolls near the bottom.
 *
 * @param items The catalog items to display.
 * @param isLoadingMore Whether additional items are currently loading.
 * @param hasMore Whether there are more items to load.
 * @param onItemClick Callback with content ID when a card is tapped.
 * @param onLoadMore Callback to trigger loading the next page.
 * @param modifier Compose modifier.
 */
@Composable
fun CatalogGrid(
    items: List<BrowseItem>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onItemClick: (String) -> Unit,
    /** Per-card caption overriding the year (e.g. date for date sorts). */
    cardSubtitle: ((BrowseItem) -> String?)? = null,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    selectedNamePrefix: String? = null,
    onNamePrefixSelected: ((String?) -> Unit)? = null,
    viewDensity: CatalogViewDensity = CatalogViewDensity.Normal,
    bottomContentInset: Dp = 0.dp,
) {
    val gridState = rememberLazyGridState()
    val cardWidth = viewDensity.minCardWidth

    // Trigger load more when scrolled near bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            hasMore && !isLoadingMore && lastVisibleItem >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            // iOS phone: adaptive poster grid, 110pt minimum card width, 12pt
            // column spacing, 16pt row spacing, 16pt horizontal page padding.
            columns = GridCells.Adaptive(cardWidth),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = if (onNamePrefixSelected != null) 56.dp else 16.dp,
                bottom = 8.dp + bottomContentInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                items = items,
                key = { it.contentId },
                contentType = { item -> item.type },
            ) { item ->
                val (actions, userState) = rememberBrowseItemCardActions(item)
                MediaCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    year = item.year,
                    subtitle = cardSubtitle?.invoke(item),
                    type = item.type,
                    userState = userState,
                    onClick = { onItemClick(item.contentId) },
                    width = cardWidth,
                    overlay = OverlayDataExtractor.fromBrowseItem(item),
                    actions = actions,
                )
            }

            // Loading indicator at bottom
            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        onNamePrefixSelected?.let { onSelected ->
            CatalogLetterRail(
                selectedNamePrefix = selectedNamePrefix,
                onNamePrefixSelected = onSelected,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .padding(bottom = bottomContentInset),
            )
        }
    }
}

private val CatalogLetterOptions: List<String?> = listOf(null) + ('A'..'Z').map { it.toString() }

@Composable
private fun CatalogLetterRail(
    selectedNamePrefix: String?,
    onNamePrefixSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The alphabet is intentionally scrollable so every entry can retain the
    // 48dp minimum touch target on short phone screens.
    LazyColumn(
        modifier = modifier
            .width(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(CatalogLetterOptions, key = { it ?: "all" }) { prefix ->
            val selected = selectedNamePrefix == prefix
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                        },
                    )
                    .clickable { onNamePrefixSelected(prefix) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = prefix ?: "All",
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
