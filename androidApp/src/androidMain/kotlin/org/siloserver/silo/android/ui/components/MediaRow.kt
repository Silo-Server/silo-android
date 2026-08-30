package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import org.siloserver.silo.model.catalog.isBookLikeItemType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyAnomalyLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyCollection
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDataExtractor

enum class CardStyle { Poster, Backdrop }

private data class MediaRowItemModel(
    val item: SectionItem,
    val progress: Float?,
    val remainingMinutes: Int?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val overlay: OverlayData,
    val isBook: Boolean,
    val contentType: String,
)

/**
 * Horizontal row of media cards with a section headline above.
 *
 * Mirrors iOS `SectionRow` (HomeView.swift): 16sp semibold headline,
 * 12dp gap between cards, 16dp horizontal screen padding.
 */
@Composable
fun MediaRow(
    title: String,
    items: List<SectionItem>,
    onItemClick: (String) -> Unit,
    /** Direct-resume action for the backdrop card's center play glyph;
     *  null keeps the glyph decorative. Never applied to book items. */
    onItemPlay: ((SectionItem) -> Unit)? = null,
    onSeeAllClick: (() -> Unit)? = null,
    showProgress: Boolean = false,
    cardStyle: CardStyle = CardStyle.Poster,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    cardActions: (SectionItem) -> MediaCardActions = { MediaCardActions() },
) {
    val diagnosticsKeySnapshot = remember(items) {
        DiagnosticsListSnapshot.fromKeys(items.map { it.contentId })
    }
    LaunchedEffect(diagnosticsKeySnapshot) {
        DiagnosticsKeyAnomalyLogger.snapshot(
            DiagnosticsKeyCollection.PHONE_MEDIA_ROW,
            diagnosticsKeySnapshot,
        )
    }
    val rowItems = remember(items, showProgress, cardStyle) {
        items.map { item ->
            val pos = item.positionSeconds
            val dur = item.durationSeconds
            val progress = if (showProgress && pos != null && dur != null && dur > 0) {
                (pos / dur).toFloat().coerceIn(0f, 1f)
            } else {
                null
            }
            val remainingMinutes = if (showProgress && pos != null && dur != null && dur > 0 && pos < dur) {
                ((dur - pos) / 60.0).toInt()
            } else {
                null
            }
            // Landscape cards take the backdrop first for every item type
            // (iOS EpisodeThumbCard). For episodes the server's backdrop_url
            // IS the episode still (falling back to the series backdrop),
            // while poster_url is the season/series portrait — which the
            // 16:9 frame used to crop down to a sliver of the title art.
            val imageUrl = item.backdropUrl ?: item.posterUrl
            val imageThumbhash = item.backdropThumbhash ?: item.posterThumbhash
            MediaRowItemModel(
                item = item,
                progress = progress,
                remainingMinutes = remainingMinutes,
                backdropUrl = imageUrl,
                backdropThumbhash = imageThumbhash,
                overlay = OverlayDataExtractor.fromSectionItem(item),
                isBook = isBookLikeItemType(item.type),
                contentType = "${cardStyle.name}:${item.type}",
            )
        }
    }

    Column(modifier = modifier) {
        // iOS MediaRow header: optional leading icon (16pt semibold onSurface,
        // 6pt to the title), siloHeadline (16sp) title, plain caption
        // "See All" at onSurface 0.6 — no chevron. rowVerticalSpacing =
        // smallPadding (8dp) below the header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Row headings read a step larger than the 16sp headline so
                // "Continue Watching" / "Next Up" carry the feed against
                // 14sp card captions. 20sp at the default font scale (and it
                // grows with larger settings as usual), but floored at 20dp
                // so a "small" system font cannot shrink it into a caption.
                val density = LocalDensity.current
                val headingSize = with(density) { maxOf(20.sp.toPx(), 20.dp.toPx()).toSp() }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = headingSize,
                        lineHeight = headingSize * 1.3f,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onSeeAllClick != null) {
                Text(
                    text = "See All",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.clickable(onClick = onSeeAllClick),
                )
            }
        }

        // Horizontal rows live inside the vertically scrolling feed. iOS gets
        // this "for free": the nested horizontal/vertical UIScrollViews claim by
        // dominant drag direction at a small shared threshold, and a card tap is
        // cancelled the moment either scroll begins — so a sideways flick scrolls
        // the row and never fires the tap (MediaRow.swift does zero gesture
        // tuning). Compose has no native direction arbitration between the row's
        // scroll and the parent column's, so a mostly-vertical drag that wobbles
        // sideways past stock slop lets the row claim the gesture and the feed
        // "sticks". We raise slop only for the row's scroll gesture to demand
        // clearer horizontal intent before it claims — the parent column (outside
        // this provider) keeps stock sensitivity.
        //
        // Crucially the card's own tap/click detector must NOT inherit the
        // inflated slop: if it did, its tap-cancel radius would widen to match,
        // leaving a dead band (~stock..inflated dp) where a horizontal nudge is
        // too small for the row to scroll yet still counts as a tap — opening the
        // detail page or firing the center play glyph on a flick. So each card
        // re-provides the base ViewConfiguration, restoring a tight (stock) tap
        // radius: a horizontal drift past stock slop cancels the tap the way iOS
        // does, even in the band below the row's scroll threshold.
        val baseViewConfiguration = LocalViewConfiguration.current
        val rowViewConfiguration = remember(baseViewConfiguration) {
            HorizontalBiasViewConfiguration(baseViewConfiguration)
        }
        // A row fling defers artwork presentation just like the parent feed's
        // vertical fling (the helper ORs in any deferral already in scope).
        val rowState = rememberLazyListState()
        DeferImagePresentationWhileScrolling(rowState) {
        CompositionLocalProvider(LocalViewConfiguration provides rowViewConfiguration) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = rowItems,
                key = { rowItem -> rowItem.item.contentId },
                contentType = { rowItem -> rowItem.contentType },
            ) { rowItem ->
                val item = rowItem.item
                CompositionLocalProvider(LocalViewConfiguration provides baseViewConfiguration) {
                when (cardStyle) {
                    CardStyle.Backdrop -> {
                        BackdropCard(
                            title = item.title,
                            backdropUrl = rowItem.backdropUrl,
                            backdropThumbhash = rowItem.backdropThumbhash,
                            seriesTitle = item.seriesTitle,
                            seasonNumber = item.seasonNumber,
                            episodeNumber = item.episodeNumber,
                            progress = rowItem.progress,
                            remainingMinutes = rowItem.remainingMinutes,
                            onClick = { onItemClick(item.contentId) },
                            userState = item.userState,
                            actions = cardActions(item),
                            overlayIcon = if (rowItem.isBook) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.PlayArrow,
                            overlayContentDescription = if (rowItem.isBook) "Read" else "Play",
                            onOverlayClick = if (!rowItem.isBook && onItemPlay != null) {
                                { onItemPlay(item) }
                            } else {
                                null
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    CardStyle.Poster -> {
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            posterThumbhash = item.posterThumbhash,
                            year = item.year,
                            type = item.type,
                            userState = item.userState,
                            progress = rowItem.progress,
                            onClick = { onItemClick(item.contentId) },
                            overlay = rowItem.overlay,
                            actions = cardActions(item),
                            modifier = Modifier.animateItem(),
                            sharedContentId = item.contentId,
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


/**
 * [ViewConfiguration] that inflates touch slop for the horizontal scroll
 * gesture of a row nested inside a vertical feed, so incidental sideways wobble
 * during a vertical drag doesn't lock the gesture to the row. Applied only to
 * the row's scroll; the cards re-provide the base configuration so their tap
 * radius stays tight (see the provider setup in [MediaRow]).
 */
private class HorizontalBiasViewConfiguration(
    private val base: ViewConfiguration,
) : ViewConfiguration by base {
    // Bumped 1.75 -> 2.25 (Jim QA 2026-07-09: rows still too grabby on Pixel,
    // vertical scroll getting hijacked by incidental horizontal activation).
    // Governs only when horizontal scroll STARTS, not fling velocity.
    override val touchSlop: Float get() = base.touchSlop * 2.25f
}
