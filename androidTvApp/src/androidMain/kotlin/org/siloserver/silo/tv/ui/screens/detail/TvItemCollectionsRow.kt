package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
 *
 * Focus return: while a return is pending, [restoreFocusRequester] sits on
 * the card at [restoreFocusIndex] (an index into [previewItems], where
 * `previewItems.size` names Explore all, or into the "Also in" cards), and
 * [onCardFocused] reports each card that gains focus by [returnKey].
 */
@Composable
internal fun TvItemCollectionsRowSection(
    row: ItemCollectionsRow,
    previewItems: List<SectionItem>,
    /** Opens a collection; the return key names the card that did, for [onCardFocused]. */
    onCollectionClick: (SectionCollectionCard, returnKey: String) -> Unit,
    onItemClick: (contentId: String) -> Unit,
    onReturnToHero: (() -> Boolean)?,
    restoreFocusIndex: Int,
    restoreFocusRequester: FocusRequester?,
    restoreFocusRequest: Int,
    onCardFocused: (returnKey: String) -> Unit,
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
                    onItemClick = onItemClick,
                    style = TvRowStyle.Poster,
                    horizontalPadding = TvDetailHorizontalInset,
                    itemSpacing = TvItemCollectionsRowSpacing,
                    posterWidth = cardWidth,
                    rowTopPadding = 0.dp,
                    onDirectionUp = onReturnToHero,
                    restoreFocusIndex = restoreFocusIndex,
                    restoreFocusRequester = restoreFocusRequester,
                    restoreFocusRequest = restoreFocusRequest,
                    onItemFocused = { item -> onCardFocused(item.contentId) },
                    onExploreAllClick = { onCollectionClick(row.collection, TvCollectionsExploreAllReturnKey) },
                    onExploreAllFocused = { onCardFocused(TvCollectionsExploreAllReturnKey) },
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
                    collections = row.cards,
                    onCollectionClick = { card, _ -> onCollectionClick(card, card.rowKey) },
                    onCollectionFocused = { card, _ -> onCardFocused(card.rowKey) },
                    startPadding = TvDetailHorizontalInset,
                    endPadding = TvDetailHorizontalInset,
                    itemSpacing = TvItemCollectionsRowSpacing,
                    rowTopPadding = 0.dp,
                    rowBottomPadding = 0.dp,
                    posterWidth = cardWidth,
                    owners = rememberTvCollectionOwners(),
                    restoreFocusIndex = restoreFocusIndex,
                    restoreFocusRequester = restoreFocusRequester,
                    restoreFocusRequest = restoreFocusRequest,
                    onDirectionUp = onReturnToHero,
                )
            }
        }
    }
}

/**
 * Where a return to this row lands: the preview title or "Also in" card that
 * opened a page, by [returnKey]; `size` of the shown list for Explore all.
 */
internal fun ItemCollectionsRow.returnIndex(previewItems: List<SectionItem>, returnKey: String): Int = when (this) {
    is ItemCollectionsRow.Preview ->
        if (returnKey == TvCollectionsExploreAllReturnKey) {
            previewItems.size
        } else {
            previewItems.indexOfFirst { it.contentId == returnKey }
        }
    is ItemCollectionsRow.AlsoIn -> cards.indexOfFirst { it.rowKey == returnKey }
}

/** The Explore all card's return key. NUL never appears in a content id or a card's row key. */
internal const val TvCollectionsExploreAllReturnKey = "\u0000collections-explore-all"

private val TvItemCollectionsRowSpacing = 20.dp
