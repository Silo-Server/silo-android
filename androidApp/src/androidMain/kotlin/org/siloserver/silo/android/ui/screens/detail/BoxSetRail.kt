package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.CardStyle
import org.siloserver.silo.android.ui.components.ExploreAllCard
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.boxSetResumePosition
import org.siloserver.silo.model.catalog.boxSetRowWindow
import org.siloserver.silo.overlays.OverlayDataExtractor

/**
 * The box set row on a movie's page: the box set's title over as many of its
 * movies, in release order, as fit across the screen without scrolling,
 * ended by an Explore all card that opens the box set page. The movie being
 * viewed is outlined and tagged; [boxSetRowWindow] picks which movies show.
 *
 * Cards keep the poster size of other rows. When fewer than three fit at that
 * size, they shrink so that two movies and the Explore all card do.
 */
@Composable
fun BoxSetRail(
    boxSet: CatalogBoxSetDetail,
    currentContentId: String,
    onOpenBoxSet: (CatalogBoxSet) -> Unit,
    onMovieClick: (contentId: String, libraryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (boxSet.items.isEmpty()) return
    val posterWidth = 120.dp * LocalCardPresentation.current.posterSize.posterScale
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        Text(
            text = boxSet.boxSet.title,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = DetailPrimaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SafePadding),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SafePadding),
        ) {
            val spacing = BoxSetRailSpacing
            val fitting = ((maxWidth + spacing) / (posterWidth + spacing)).toInt()
            val slots = fitting.coerceAtLeast(BoxSetRailMinSlots)
            val cardWidth = if (fitting >= BoxSetRailMinSlots) {
                posterWidth
            } else {
                (maxWidth - spacing * (slots - 1)) / slots
            }
            val currentIndex = boxSet.items.indexOfFirst { it.contentId == currentContentId }
            val window = boxSetRowWindow(boxSet.items.size, currentIndex, filmSlots = slots - 1)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                for (index in window) {
                    val item = boxSet.items[index]
                    key(item.contentId) {
                        BoxSetRailCard(
                            item = item,
                            width = cardWidth,
                            isCurrent = item.contentId == currentContentId,
                            onClick = { onMovieClick(item.contentId, boxSet.boxSet.libraryId) },
                        )
                    }
                }
                ExploreAllCard(
                    cardStyle = CardStyle.Poster,
                    onClick = { onOpenBoxSet(boxSet.boxSet) },
                    width = cardWidth,
                )
            }
        }
    }
}

private val BoxSetRailSpacing = 12.dp

/** Two movies and the Explore all card, on screens too narrow for three posters. */
private const val BoxSetRailMinSlots = 3

@Composable
private fun BoxSetRailCard(item: BrowseItem, width: Dp, isCurrent: Boolean, onClick: () -> Unit) {
    MediaCard(
        width = width,
        title = item.title,
        posterUrl = item.posterUrl,
        posterThumbhash = item.posterThumbhash,
        detailBackdropUrl = item.backdropUrl,
        detailBackdropThumbhash = item.backdropThumbhash,
        year = item.year?.takeIf { it > 0 },
        type = item.type,
        userState = item.userState,
        progress = boxSetMovieProgress(item),
        // The movie on screen opens nothing new; tapping it is a no-op.
        onClick = if (isCurrent) ({}) else onClick,
        overlay = OverlayDataExtractor.fromBrowseItem(item),
        sharedContentId = item.contentId.takeUnless { isCurrent },
        // Bottom corner: the top corners hold the quality overlays and watched check.
        cornerLabel = "THIS FILM".takeIf { isCurrent },
        highlighted = isCurrent,
    )
}

/** An unfinished movie's watched share, or null to draw no progress bar. */
internal fun boxSetMovieProgress(item: BrowseItem): Float? {
    val position = item.boxSetResumePosition() ?: return null
    val duration = item.durationSeconds?.takeIf { it > 0 } ?: return null
    return (position / duration).toFloat()
}
