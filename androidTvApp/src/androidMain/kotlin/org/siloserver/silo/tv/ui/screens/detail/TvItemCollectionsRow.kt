package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.section.SectionCollectionCard
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.ui.components.TvCollectionRow
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.rememberTvCollectionOwners
import org.siloserver.silo.tv.ui.components.tvCardWidth
import org.siloserver.silo.viewmodel.ItemCollectionsRow

/**
 * The collections row on a movie or series page (web `ItemCollectionsRow`).
 * When one collection holds the title, its other titles ([previewItems]) show
 * under the collection's name, ended by an Explore all card that opens the
 * collection. Otherwise an "Also in" row shows a poster card for every
 * collection. Up calls [onReturnToHero] when it is set.
 */
@Composable
internal fun TvItemCollectionsRowSection(
    row: ItemCollectionsRow,
    previewItems: List<SectionItem>,
    onCollectionClick: (SectionCollectionCard) -> Unit,
    onItemClick: (contentId: String, collection: SectionCollectionCard) -> Unit,
    onReturnToHero: (() -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    val cardWidth = tvCardWidth()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (row) {
            is ItemCollectionsRow.Preview -> {
                TvDetailSectionHeader(
                    title = row.collection.title,
                    modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
                )
                TvMediaRow(
                    title = row.collection.title,
                    showHeader = false,
                    items = previewItems,
                    onItemClick = { contentId -> onItemClick(contentId, row.collection) },
                    style = TvRowStyle.Poster,
                    horizontalPadding = TvDetailHorizontalInset,
                    itemSpacing = TvItemCollectionsRowSpacing,
                    posterWidth = cardWidth,
                    rowTopPadding = 0.dp,
                    onDirectionUp = onReturnToHero,
                    onExploreAllClick = { onCollectionClick(row.collection) },
                )
            }
            is ItemCollectionsRow.AlsoIn -> {
                val title = stringResource(CommonR.string.detail_alsoIn)
                TvDetailSectionHeader(
                    title = title,
                    modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
                )
                TvCollectionRow(
                    title = title,
                    showHeader = false,
                    collections = row.cards.distinctBy { it.rowKey },
                    onCollectionClick = { card, _ -> onCollectionClick(card) },
                    onCollectionFocused = { _, _ -> },
                    startPadding = TvDetailHorizontalInset,
                    endPadding = TvDetailHorizontalInset,
                    itemSpacing = TvItemCollectionsRowSpacing,
                    rowTopPadding = 0.dp,
                    rowBottomPadding = 0.dp,
                    posterWidth = cardWidth,
                    owners = rememberTvCollectionOwners(),
                )
            }
        }
    }
}

private val TvItemCollectionsRowSpacing = 20.dp
