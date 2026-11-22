package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.CardStyle
import org.siloserver.silo.android.ui.components.CollectionPosterRow
import org.siloserver.silo.android.ui.components.ExploreAllCard
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.rememberCollectionOwnerName
import org.siloserver.silo.android.ui.components.sharedArtworkAspectRatio
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.section.SectionCollectionCard
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.viewmodel.DetailSeeds
import org.siloserver.silo.viewmodel.ItemCollectionsRow

/**
 * The collections row on a movie or series page (web `ItemCollectionsRow`).
 * When one collection holds the title, its other titles show under the
 * collection's name, ended by an Explore all card that opens the collection.
 * Otherwise an "Also in" row shows a poster card for every collection.
 */
@Composable
internal fun ItemCollectionsRail(
    row: ItemCollectionsRow,
    onCollectionClick: (SectionCollectionCard) -> Unit,
    onItemClick: (BrowseItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardWidth = 120.dp * LocalCardPresentation.current.posterSize.posterScale
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        when (row) {
            is ItemCollectionsRow.Preview -> {
                SectionHeader(title = row.collection.title)
                // Square covers when every title is an audiobook; one card height otherwise.
                val posterAspectRatio = remember(row.items) { sharedArtworkAspectRatio(row.items.map { it.type }) }
                ItemCollectionsLazyRow {
                    items(row.items, key = { it.contentId }, contentType = { "collection-title" }) { item ->
                        MediaCard(
                            width = cardWidth,
                            title = item.title,
                            posterUrl = item.posterUrl,
                            posterThumbhash = item.posterThumbhash,
                            detailBackdropUrl = item.backdropUrl,
                            detailBackdropThumbhash = item.backdropThumbhash,
                            year = item.year?.takeIf { it > 0 },
                            type = item.type,
                            userState = item.userState,
                            progress = null,
                            onClick = {
                                DetailSeeds.offer(item)
                                onItemClick(item)
                            },
                            artworkAspectRatio = posterAspectRatio,
                            overlay = OverlayDataExtractor.fromBrowseItem(item),
                            sharedContentId = item.contentId,
                            widthIsExact = true,
                        )
                    }
                    item(key = "explore-all", contentType = "explore-all") {
                        ExploreAllCard(
                            cardStyle = CardStyle.Poster,
                            onClick = { onCollectionClick(row.collection) },
                            width = cardWidth,
                            posterAspectRatio = posterAspectRatio,
                        )
                    }
                }
            }
            is ItemCollectionsRow.AlsoIn -> {
                val title = stringResource(CommonR.string.detail_alsoIn)
                SectionHeader(title = title)
                val ownerName = rememberCollectionOwnerName()
                CollectionPosterRow(
                    title = title,
                    cards = row.cards,
                    onCardClick = onCollectionClick,
                    byline = { card ->
                        ownerName(card)?.let { stringResourceWithNames(CommonR.string.collections_posterCard_byOwner, it) }
                    },
                    showHeader = false,
                )
            }
        }
    }
}

@Composable
private fun ItemCollectionsLazyRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val rowState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(rowState) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = SafePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}
