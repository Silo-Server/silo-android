package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.model.catalog.ItemDetail

/**
 * "More Like This" section — header plus a horizontal poster rail —
 * shown at the bottom of Movie / Series detail pages. Mirrors
 * `PhoneSimilarRail.swift`. Items are loaded eagerly by
 * [ItemDetailViewModel] and rendered as poster cards that open detail.
 *
 * The whole section (header included) stays hidden until the request
 * resolves with items — servers without media embeddings return an
 * empty/failed response, and an orphaned header would just read as a
 * broken row.
 */
@Composable
fun SimilarRail(
    items: List<ItemDetail>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = modifier,
        ) {
            SectionHeader(title = "More Like This")
            SimilarRailContent(items = items, onSelect = onSelect)
        }
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
                overlay = org.siloserver.silo.overlays.OverlayDataExtractor.fromItemDetail(item),
                sharedContentId = item.contentId,
            )
        }
    }
}
