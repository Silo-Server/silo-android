package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.boxSetRowWindow
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.tvCardWidth

/**
 * The box set row on a movie's page: the box set's title over as many of its
 * movies, in release order, as fit across the screen at the usual poster
 * size, ended by an Explore all card that opens the box set page
 * ([onExploreAll]). The row does not scroll; [boxSetRowWindow] picks which
 * movies show. The movie on screen is outlined and tagged.
 *
 * The row sits between the trailers and More Like This. Focus: entering the
 * row lands on the movie on screen the first time ([restoreFocusRequester]
 * at [restoreFocusIndex], an index into [items], where `items.size` names
 * Explore all); Explore all is its last stop. Up moves to the rail above, or
 * calls [onReturnToHero] when it is set.
 */
@Composable
internal fun TvBoxSetRowSection(
    boxSet: CatalogBoxSet,
    items: List<SectionItem>,
    currentContentId: String,
    onExploreAll: () -> Unit,
    onReturnToHero: (() -> Boolean)?,
    onMovieClick: (contentId: String) -> Unit,
    restoreFocusIndex: Int,
    restoreFocusRequester: FocusRequester?,
    restoreFocusRequest: Int,
    onItemFocused: ((SectionItem) -> Unit)?,
    onExploreAllFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TvDetailSectionHeader(
            title = boxSet.title,
            modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = tvCardWidth()
            val available = maxWidth - TvDetailHorizontalInset * 2
            val slots = ((available + TvBoxSetRowSpacing) / (cardWidth + TvBoxSetRowSpacing)).toInt().coerceAtLeast(2)
            val currentIndex = items.indexOfFirst { it.contentId == currentContentId }
            val window = boxSetRowWindow(items.size, currentIndex, filmSlots = slots - 1)
            val shownRestoreIndex = when (restoreFocusIndex) {
                items.size -> window.count()
                in window -> restoreFocusIndex - window.first
                else -> -1
            }
            TvMediaRow(
                title = boxSet.title,
                showHeader = false,
                items = items.slice(window),
                showProgress = true,
                onItemClick = onMovieClick,
                style = TvRowStyle.Poster,
                horizontalPadding = TvDetailHorizontalInset,
                itemSpacing = TvBoxSetRowSpacing,
                posterWidth = cardWidth,
                rowTopPadding = 0.dp,
                onDirectionUp = onReturnToHero,
                // The row speaks indexes into the movies it shows.
                restoreFocusIndex = shownRestoreIndex,
                restoreFocusRequester = restoreFocusRequester.takeIf { shownRestoreIndex >= 0 },
                restoreFocusRequest = restoreFocusRequest,
                onItemFocused = onItemFocused,
                onExploreAllClick = onExploreAll,
                onExploreAllFocused = onExploreAllFocused,
                markedContentId = currentContentId,
                markedBadge = "THIS FILM",
            )
        }
    }
}

private val TvBoxSetRowSpacing = 20.dp
