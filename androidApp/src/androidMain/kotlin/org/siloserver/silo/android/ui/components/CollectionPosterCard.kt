package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.koin.compose.koinInject
import org.siloserver.silo.network.ServerRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.SectionCollectionCard

/**
 * A collection's poster card: the Libraries Collections tab's grid card and
 * the card of a Home or library page row of collections. Fills the width it
 * is given.
 *
 * @param showItemCount Shows the count badge and the item count line; off in
 *  a Movies- or Series-scoped view, where the whole-collection count is wrong.
 * @param secondaryText Replaces the line under the name, such as a "by Name"
 *  byline. Null keeps the item count (or "Collection").
 */
@Composable
internal fun CollectionPosterCard(
    collection: LibraryCollection,
    showItemCount: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    /**
     * The count is exact, as on a row of collections, so 0 is an empty
     * collection and the badge reads 0, not the grid's uncounted "Smart".
     */
    exactCount: Boolean = false,
) {
    // iOS `LibraryCollectionCard`: VStack(spacing: 6) of a 2:3.3 poster
    // (smallCornerRadius = 6) carrying a bottom-trailing count badge, a
    // siloCaption (12) name (2 lines), and a siloSmall (11) secondary
    // type label; the count badge rides the artwork.
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                .aspectRatio(PosterArtworkAspectRatio),
        ) {
            if (collection.posterUrl.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Outlined.CollectionsBookmark,
                    contentDescription = collection.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
            } else {
                ThumbhashImage(
                    url = collection.posterUrl,
                    thumbhash = collection.posterThumbhash,
                    contentDescription = collection.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showItemCount) Text(
                text = collection.itemCount?.takeIf { it > 0 || exactCount }?.let { LocaleFormat.groupedCount(it, currentNumberLocale()) } ?: stringResource(CommonR.string.collections_type_smart),
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Text(
            text = collection.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = secondaryText
                ?: collection.itemCount?.takeIf { showItemCount }
                    ?.let { pluralStringResource(CommonR.plurals.collections_itemCount, it, it, LocaleFormat.groupedCount(it)) }
                ?: stringResource(CommonR.string.collections_collection),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The name of the profile that made a personal collection, when another
 * profile of the account made it, for a card's "by Name" line (web
 * `CollectionPosterCard`). The names come from the account's cached profile
 * list; an unknown name, or any other card, answers null.
 */
@Composable
internal fun rememberCollectionOwnerName(activeProfileId: String? = null): (SectionCollectionCard) -> String? {
    val serverRegistry: ServerRegistry = koinInject()
    val activeAccount by serverRegistry.activeAccount.collectAsState()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val viewerId = activeEntry?.profileId ?: activeProfileId
    val profileNames = remember(activeAccount) {
        activeAccount?.cachedProfiles.orEmpty()
            .filter { it.name.isNotBlank() }
            .associate { it.id to it.name }
    }
    return remember(viewerId, profileNames) {
        { card ->
            card.creatorProfileId
                ?.takeIf { card.isUserCollection && viewerId != null && it != viewerId }
                ?.let { profileNames[it] }
        }
    }
}

/**
 * A Home or library page row of collections (`collection_posters`): the
 * [MediaRow] header over one [CollectionPosterCard] per card, sized and
 * spaced like the row's poster cards. It has no "Explore all".
 *
 * @param byline The card's "by Name" line, or null to show its item count.
 * @param showCounts Off on a Movies- or Series-scoped library page, where a
 *  collection's whole count is wrong, as in the Collections grid.
 */
@Composable
internal fun CollectionPosterRow(
    title: String,
    cards: List<SectionCollectionCard>,
    onCardClick: (SectionCollectionCard) -> Unit,
    byline: @Composable (SectionCollectionCard) -> String?,
    modifier: Modifier = Modifier,
    showCounts: Boolean = true,
    /** Off where the host draws its own heading, as a title page does. */
    showHeader: Boolean = true,
) {
    val uniqueCards = remember(cards) { cards.distinctBy { it.rowKey } }
    val cardWidth = 120.dp * LocalCardPresentation.current.posterSize.posterScale
    Column(modifier = modifier) {
        if (showHeader) MediaRowHeader(title = title)
        // Same gesture handling as MediaRow: a stricter slop for the row's
        // scroll, the stock one for the cards' taps.
        val baseViewConfiguration = LocalViewConfiguration.current
        val rowViewConfiguration = remember(baseViewConfiguration) {
            HorizontalBiasViewConfiguration(baseViewConfiguration)
        }
        val rowState = rememberLazyListState()
        DeferImagePresentationWhileScrolling(rowState) {
            CompositionLocalProvider(LocalViewConfiguration provides rowViewConfiguration) {
                LazyRow(
                    state = rowState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = uniqueCards,
                        key = { it.rowKey },
                        contentType = { "collection-poster" },
                    ) { card ->
                        val collection = remember(card) { card.toLibraryCollection() }
                        CompositionLocalProvider(LocalViewConfiguration provides baseViewConfiguration) {
                            // As on the web: the count rides the poster, and a
                            // byline replaces only the line under the name.
                            CollectionPosterCard(
                                collection = collection,
                                showItemCount = showCounts,
                                onClick = { onCardClick(card) },
                                secondaryText = byline(card),
                                exactCount = true,
                                modifier = Modifier.width(cardWidth).animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}
