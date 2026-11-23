package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionCollectionCard
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.ProfileRepository
import org.koin.compose.koinInject
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus

/**
 * The profiles a personal collection card can name as its maker: the active
 * profile (whose own collections get no byline) and the account's profile
 * names by id.
 */
internal data class TvCollectionOwners(
    val activeProfileId: String? = null,
    val namesById: Map<String, String> = emptyMap(),
)

/**
 * [TvCollectionOwners] from what the device already holds: the open account's
 * cached profile list (kept current by the profile picker and the launch and
 * return refreshes) and the stored active profile id. Makes no request; a
 * profile missing from the cache simply gets no byline.
 */
@Composable
internal fun rememberTvCollectionOwners(): TvCollectionOwners {
    val serverRegistry: ServerRegistry = koinInject()
    val profileRepository: ProfileRepository = koinInject()
    val account by serverRegistry.activeAccount.collectAsState()
    val activeProfileId by produceState<String?>(null, account?.localAccountId, account?.profileId, profileRepository) {
        value = profileRepository.getActiveProfileId()
    }
    val cachedProfiles = account?.cachedProfiles
    return remember(cachedProfiles, activeProfileId) {
        TvCollectionOwners(
            activeProfileId = activeProfileId,
            namesById = cachedProfiles.orEmpty().associate { it.id to it.name },
        )
    }
}

/**
 * The "by Name" maker of [card]: a personal collection made by another
 * profile whose name is known. Null for library collections, the viewer's own,
 * and makers this device has no name for.
 */
internal fun TvCollectionOwners.ownerName(card: SectionCollectionCard): String? {
    if (!card.isUserCollection) return null
    val creator = card.creatorProfileId?.takeIf { it.isNotBlank() } ?: return null
    if (activeProfileId == null || creator == activeProfileId) return null
    return namesById[creator]?.takeIf { it.isNotBlank() }
}

/**
 * Prefix of the marquee id of a collection card. Content ids never contain
 * NUL (see [org.siloserver.silo.tv.ui.focus.TvExploreAllItemId]), so this id
 * can never name a title, and nothing may send it to the server.
 */
internal const val TvCollectionMarqueeIdPrefix = "\u0000collection:"

internal fun isTvCollectionMarqueeId(contentId: String?): Boolean =
    contentId?.startsWith(TvCollectionMarqueeIdPrefix) == true

/**
 * What the Skyline marquee previews for a focused collection card: its title,
 * and its backdrop as the hero (the poster when it has none), nothing else.
 */
internal fun SectionCollectionCard.tvMarqueeItem(): SectionItem = SectionItem(
    contentId = TvCollectionMarqueeIdPrefix + rowKey,
    type = "collection",
    title = title,
    posterUrl = posterUrl?.takeIf { it.isNotBlank() },
    posterThumbhash = posterThumbhash?.takeIf { it.isNotBlank() },
    backdropUrl = backdropUrl?.takeIf { it.isNotBlank() },
    backdropThumbhash = backdropThumbhash?.takeIf { it.isNotBlank() },
)

/**
 * What the marquee previews for each card of this row, in order: the cards'
 * stand-ins on a row of collections ([tvMarqueeItem]), the items otherwise.
 */
internal fun ResolvedSection.tvMarqueeItems(): List<SectionItem> =
    if (isCollectionRow) collections.map { it.tvMarqueeItem() } else items

/**
 * A Skyline row of collection cards (`collection_posters`). Honors the part of
 * [TvMediaRow]'s focus contract the feed uses: first-card and row-group
 * requesters, the row's focus restorer, and exact-card restore by index.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvCollectionRow(
    title: String,
    collections: List<SectionCollectionCard>,
    onCollectionClick: (SectionCollectionCard, Int) -> Unit,
    onCollectionFocused: (SectionCollectionCard, Int) -> Unit,
    startPadding: Dp,
    endPadding: Dp,
    itemSpacing: Dp,
    rowTopPadding: Dp,
    rowBottomPadding: Dp,
    posterWidth: Dp,
    owners: TvCollectionOwners,
    modifier: Modifier = Modifier,
    /** Off where the count would be wrong; a card then has no second line unless it has a byline. */
    showCounts: Boolean = true,
    firstItemFocusRequester: FocusRequester? = null,
    firstItemFocusRequest: Int = 0,
    rowContainerFocusRequester: FocusRequester? = null,
    restoreFocusIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    restoreFocusRequest: Int = 0,
    /** Off where the host draws its own heading, as a title page does. */
    showHeader: Boolean = true,
    /** Up from any card, as [TvMediaRow]'s: return true to consume it. */
    onDirectionUp: (() -> Boolean)? = null,
) {
    if (collections.isEmpty()) return
    val rowState = rememberLazyListState()

    LaunchedEffect(restoreFocusRequest, restoreFocusIndex) {
        prepareTvMediaRowFocusRestore(
            requestId = restoreFocusRequest,
            restoreFocusIndex = restoreFocusIndex,
            itemCount = collections.size,
            scrollToItem = rowState::scrollToItem,
        )
    }

    LaunchedEffect(firstItemFocusRequest) {
        if (firstItemFocusRequest > 0 && firstItemFocusRequester != null) {
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showHeader) {
            TvSectionHeader(
                title = title,
                modifier = Modifier.padding(start = startPadding, end = endPadding),
            )
        }
        TvRailScrollBehavior {
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .then(
                        if (rowContainerFocusRequester != null) {
                            Modifier.focusRequester(rowContainerFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .focusRestorer(
                        restoreFocusRequester ?: firstItemFocusRequester ?: FocusRequester.Default,
                    )
                    .then(
                        if (onDirectionUp != null) {
                            Modifier.onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onDirectionUp()
                            }
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                contentPadding = PaddingValues(
                    start = startPadding,
                    end = endPadding,
                    top = rowTopPadding,
                    bottom = rowBottomPadding,
                ),
            ) {
                itemsIndexed(
                    items = collections,
                    key = { _, card -> card.rowKey },
                    contentType = { _, _ -> "collection" },
                ) { index, card ->
                    // The card's one caption line: "by Name" for another
                    // profile's shared collection, else the count. Both do not
                    // fit one poster-wide line ("3 items · by Alex" already
                    // truncates the name), and the owner says more.
                    val countLine = if (showCounts) {
                        pluralStringResource(
                            CommonR.plurals.collections_itemCount,
                            card.itemCount,
                            card.itemCount,
                            LocaleFormat.groupedCount(card.itemCount),
                        )
                    } else {
                        null
                    }
                    val ownerLine = owners.ownerName(card)?.let { name ->
                        stringResourceWithNames(CommonR.string.collections_posterCard_byOwner, name)
                    }
                    val subtitle = ownerLine ?: countLine
                    TvCollectionCard(
                        collection = card.toLibraryCollection(),
                        subtitle = subtitle,
                        width = posterWidth,
                        onClick = { onCollectionClick(card, index) },
                        focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                        modifier = Modifier
                            .then(
                                if (restoreFocusRequester != null && index == restoreFocusIndex) {
                                    Modifier.focusRequester(restoreFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .onFocusChanged { if (it.isFocused) onCollectionFocused(card, index) }
                            .tvRailPinOnFocus(rowState, index, startPadding),
                    )
                }
            }
        }
    }
}
