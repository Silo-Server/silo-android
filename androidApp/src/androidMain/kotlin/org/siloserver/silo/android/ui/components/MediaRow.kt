package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import org.siloserver.silo.common.cards.LocalCardPresentation
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.siloserver.silo.repository.ImageCapabilitiesSession
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyAnomalyLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyCollection
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.settings.EpisodeSpoilerPrefs
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import kotlinx.coroutines.flow.distinctUntilChanged

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
    /** Spoiler protection: blur this card's episode still. */
    val hidesArtwork: Boolean,
)

/**
 * True when [item] is an episode the profile has not started and [prefs]
 * blur such stills. For episodes the backdrop is the still, so the card and
 * the detail hand-off both treat it as a spoiler.
 */
private fun hidesEpisodeArtwork(item: SectionItem, prefs: EpisodeSpoilerPrefs): Boolean =
    item.type.equals("episode", ignoreCase = true) &&
        prefs.hidesImage(EpisodeSpoilers.isUnwatched(item), EpisodeSpoilers.selectedImageIsStill(item))

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
    /** Opens the row's full page: the title gains a chevron and the row ends
     *  with an "Explore all" card. Null shows neither. */
    onExploreAllClick: (() -> Unit)? = null,
    showProgress: Boolean = false,
    cardStyle: CardStyle = CardStyle.Poster,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    cardActions: (SectionItem) -> MediaCardActions = { MediaCardActions() },
    /** Publishes the card nearest the viewport centre after a row settles. */
    onCenteredItemChanged: ((SectionItem?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val imageCapabilities: ImageCapabilitiesSession = koinInject()
    val heroHandoff = LocalHeroSourceHandoff.current
    val uniqueItems = remember(items) { items.distinctBy { it.contentId } }
    val diagnosticsKeySnapshot = remember(items) {
        DiagnosticsListSnapshot.fromKeys(items.map { it.contentId })
    }
    LaunchedEffect(diagnosticsKeySnapshot) {
        DiagnosticsKeyAnomalyLogger.snapshot(
            DiagnosticsKeyCollection.PHONE_MEDIA_ROW,
            diagnosticsKeySnapshot,
        )
    }
    val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
    val rowItems = remember(uniqueItems, showProgress, cardStyle, spoilerPrefs) {
        uniqueItems.map { item ->
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
            // Landscape cards prefer the backdrop. Episode provenance tells
            // us whether the selected artwork is a still or series fallback.
            val imageUrl = item.backdropUrl?.takeIf { it.isNotBlank() } ?: item.posterUrl
            val imageThumbhash = if (!item.backdropUrl.isNullOrBlank()) item.backdropThumbhash else item.posterThumbhash
            MediaRowItemModel(
                item = item,
                progress = progress,
                remainingMinutes = remainingMinutes,
                backdropUrl = imageUrl,
                backdropThumbhash = imageThumbhash,
                overlay = OverlayDataExtractor.fromSectionItem(item),
                isBook = isBookLikeItemType(item.type),
                contentType = "${cardStyle.name}:${item.type}",
                hidesArtwork = hidesEpisodeArtwork(item, spoilerPrefs),
            )
        }
    }

    Column(modifier = modifier) {
        // iOS MediaRow header: optional leading icon (16pt semibold onSurface,
        // 6pt to the title), siloHeadline (16sp) title. rowVerticalSpacing =
        // smallPadding (8dp) below the header. A row with a full page makes
        // the title a link to it, marked with a trailing chevron.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = if (onExploreAllClick != null) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = "Explore all", role = Role.Button, onClick = onExploreAllClick)
                } else {
                    Modifier
                },
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
                if (onExploreAllClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp),
                    )
                }
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
        fun openDetail(item: SectionItem) {
            // A blurred episode still must not reappear sharp as the detail
            // page's loading artwork, so it is neither warmed nor handed off.
            // (Home swaps in the parent series artwork when it knows it.)
            if (hidesEpisodeArtwork(item, spoilerPrefs)) {
                heroHandoff?.pendingArtworkUrl = null
                heroHandoff?.pendingArtworkThumbhash = null
                onItemClick(item.contentId)
                return
            }
            // A server that sizes backdrops per screen gives the detail its own
            // hero URL, so the card's backdrop only stands in while it loads.
            // Otherwise both use one URL: keep one full-size request alive
            // across the loading-skeleton -> detail-content composition swap,
            // or the skeleton's differently-sized request can be cancelled as
            // soon as metadata arrives and the hero starts from zero again.
            val sizedBackdrops = "backdrop" in imageCapabilities.capabilities.value?.acceptedWidthSlots.orEmpty()
            item.backdropUrl?.takeIf { it.isNotBlank() && !sizedBackdrops }?.let { backdropUrl ->
                val metrics = context.resources.displayMetrics
                val widthPx = metrics.widthPixels.coerceAtLeast(1)
                val heightPx = minOf(
                    metrics.heightPixels.coerceAtLeast(1),
                    (widthPx * 1.18f).roundToInt().coerceAtLeast(1),
                )
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .size(widthPx, heightPx)
                        .build(),
                )
            }
            heroHandoff?.pendingArtworkUrl = item.backdropUrl ?: item.posterUrl
            heroHandoff?.pendingArtworkThumbhash = item.backdropThumbhash ?: item.posterThumbhash
            onItemClick(item.contentId)
        }
        val currentItems = rememberUpdatedState(rowItems)
        val currentOnCenteredItemChanged = rememberUpdatedState(onCenteredItemChanged)
        LaunchedEffect(rowState, onCenteredItemChanged) {
            if (onCenteredItemChanged == null) return@LaunchedEffect
            snapshotFlow {
                if (rowState.isScrollInProgress) {
                    null
                } else {
                    val info = rowState.layoutInfo
                    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                    // The "Explore all" card is not an item to publish.
                    info.visibleItemsInfo.filter { it.key != EXPLORE_ALL_KEY }.minByOrNull { visible ->
                        kotlin.math.abs((visible.offset + visible.size / 2) - viewportCenter)
                    }?.key as? String
                }
            }
                .distinctUntilChanged()
                .collect { contentId ->
                    if (contentId != null) {
                        currentOnCenteredItemChanged.value?.invoke(
                            currentItems.value.firstOrNull { it.item.contentId == contentId }?.item,
                        )
                    }
                }
        }
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
                            hideArtwork = rowItem.hidesArtwork,
                            seriesTitle = item.seriesTitle,
                            seasonNumber = item.seasonNumber,
                            episodeNumber = item.episodeNumber,
                            progress = rowItem.progress,
                            remainingMinutes = rowItem.remainingMinutes,
                            onClick = { openDetail(item) },
                            userState = item.userState,
                            overlay = rowItem.overlay,
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
                            hideArtwork = item.type.equals("episode", ignoreCase = true) && spoilerPrefs.hidesImage(EpisodeSpoilers.isUnwatched(item), item.posterIsEpisodeStill),
                            posterUrl = item.posterUrl,
                            posterThumbhash = item.posterThumbhash,
                            year = item.year,
                            type = item.type,
                            userState = item.userState,
                            progress = rowItem.progress,
                            onClick = { openDetail(item) },
                            overlay = rowItem.overlay,
                            actions = cardActions(item),
                            modifier = Modifier.animateItem(),
                            sharedContentId = item.contentId,
                        )
                    }
                }
                }
            }
            if (onExploreAllClick != null) {
                item(key = EXPLORE_ALL_KEY, contentType = "explore-all:${cardStyle.name}") {
                    CompositionLocalProvider(LocalViewConfiguration provides baseViewConfiguration) {
                        ExploreAllCard(cardStyle = cardStyle, onClick = onExploreAllClick)
                    }
                }
            }
        }
        }
        }
    }
}

private const val EXPLORE_ALL_KEY = "\u0000explore-all"

/**
 * The row's last card: opens the row's full page. Same size and shape as the
 * row's cards (a poster or a 16:9 thumbnail), with an icon and the label.
 */
@Composable
private fun ExploreAllCard(cardStyle: CardStyle, onClick: () -> Unit) {
    val scale = LocalCardPresentation.current.posterSize.posterScale
    val (width, aspectRatio, shape) = when (cardStyle) {
        CardStyle.Poster -> Triple(120.dp * scale, 2f / 3.3f, MaterialTheme.shapes.small)
        CardStyle.Backdrop -> Triple(280.dp * scale, 16f / 9f, RoundedCornerShape(10.dp))
    }
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(aspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "Explore all",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
