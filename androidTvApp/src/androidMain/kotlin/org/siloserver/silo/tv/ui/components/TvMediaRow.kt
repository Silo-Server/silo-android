package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyAnomalyLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyCollection
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot

/** Visual style of cards inside a [TvMediaRow]. */
enum class TvRowStyle { Poster, Backdrop }
enum class TvRowCardLayout { Default, ReferenceShelf }

private data class TvMediaRowItemModel(
    val item: SectionItem,
    val progress: Float?,
    val remainingMinutes: Int?,
    val backdropUrl: String?,
    val backdropThumbhash: String?,
    val shelfTitle: String,
    val shelfSubtitle: String?,
    val overlay: OverlayData,
    val contentType: String,
)

/**
 * Horizontal row with a header and a lazy list of cards. This is the workhorse
 * of the home screen and library-detail recommended views. The row adds a
 * little vertical padding so the focus lift on cards doesn't clip against the
 * top/bottom of its bounds.
 *
 * @param items the section items to render.
 * @param onItemClick fired when a card is pressed.
 * @param showProgress if true, displays the item's resume progress (used by
 *  continue-watching rows).
 * @param style whether to render items as 2:3 posters or 16:9 backdrops.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvMediaRow(
    title: String,
    items: List<SectionItem>,
    onItemClick: (contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onSeeAllClick: (() -> Unit)? = null,
    showProgress: Boolean = false,
    style: TvRowStyle = TvRowStyle.Poster,
    cardLayout: TvRowCardLayout = TvRowCardLayout.Default,
    horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.safeArea,
    startPadding: androidx.compose.ui.unit.Dp = horizontalPadding,
    endPadding: androidx.compose.ui.unit.Dp = horizontalPadding,
    itemSpacing: androidx.compose.ui.unit.Dp = 20.dp,
    rowTopPadding: androidx.compose.ui.unit.Dp = 24.dp,
    rowBottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    posterWidth: androidx.compose.ui.unit.Dp? = null,
    eyebrow: String? = null,
    /** When false, the built-in [TvSectionHeader] is omitted so callers can supply
     *  their own header above a bare rail (e.g. the detail "More Like This" rail). */
    showHeader: Boolean = true,
    itemCardModifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    onDirectionUp: (() -> Boolean)? = null,
    firstItemFocusRequester: FocusRequester? = null,
    firstItemFocusRequest: Int = 0,
    /** Targets the LazyRow GROUP itself (not a card). Programmatic requests
     *  that cross this row's focusRestorer toward a descendant card are
     *  cancelled by its `enter` interception; a request on the group is
     *  honored, letting callers hop onto the row before targeting card 0. */
    rowContainerFocusRequester: FocusRequester? = null,
    firstItemCardModifier: Modifier = Modifier,
    /** Attaches [restoreFocusRequester] to the card at this index so callers
     *  can restore focus to the exact card that launched a detail page. While
     *  set it also becomes the row restorer's enter fallback, so the FIRST
     *  enter after this row is recreated lands directly on that card instead
     *  of card 0. Callers should only pass it while a restore is pending. */
    restoreFocusIndex: Int = -1,
    restoreFocusRequester: FocusRequester? = null,
    /** Nonzero only while an exact-card return is pending. The row uses its
     *  private horizontal state to compose [restoreFocusIndex] before focus is
     *  requested; ordinary row rendering never changes horizontal position. */
    restoreFocusRequest: Int = 0,
    onRestoreFocusTargetPlaced: ((Int, Int) -> Unit)? = null,
    onRestoreFocusTargetDisposed: ((Int, Int) -> Unit)? = null,
    /** Fired (on focus GAIN only) with whichever card the user focuses, so the
     *  Skyline marquee + backdrop can preview the focused item. */
    onItemFocused: ((SectionItem) -> Unit)? = null,
    /** Indexed focus callback for callers that maintain a rolling prefetch
     *  window around the currently focused card. */
    onItemFocusedAtIndex: ((SectionItem, Int) -> Unit)? = null,
    /** Reports whether this row or any descendant card currently owns focus. */
    onRowFocusChanged: ((Boolean) -> Unit)? = null,
    cardActions: (SectionItem) -> TvMediaCardActions = { TvMediaCardActions() },
) {
    val diagnosticsKeySnapshot = remember(items) {
        DiagnosticsListSnapshot.fromKeys(items.map { it.contentId })
    }
    LaunchedEffect(diagnosticsKeySnapshot) {
        DiagnosticsKeyAnomalyLogger.snapshot(
            DiagnosticsKeyCollection.TV_MEDIA_ROW,
            diagnosticsKeySnapshot,
        )
    }
    if (items.isEmpty()) return
    val rowState = rememberLazyListState()
    val rowItems = remember(items, showProgress, style, cardLayout) {
        // Deduplicate before keying. A repeated contentId inside one row makes
        // the lazy list throw ("Key ... was already used"), which is fatal —
        // and a row has no reason to show the same title twice anyway. Feeds
        // can legitimately overlap, so this is a property of the row, not a
        // bug to fix upstream of it.
        items.distinctBy { it.contentId }.map { item ->
            TvMediaRowItemModel(
                item = item,
                progress = if (showProgress) item.progressFraction() else null,
                remainingMinutes = if (showProgress) item.remainingMinutes() else null,
                backdropUrl = item.bestBackdropUrl(),
                backdropThumbhash = item.bestBackdropThumbhash(),
                shelfTitle = item.shelfTitle(showProgress = showProgress),
                shelfSubtitle = item.shelfSubtitle(showProgress = showProgress),
                overlay = OverlayDataExtractor.fromSectionItem(item),
                contentType = "${cardLayout.name}:${style.name}:${item.type}",
            )
        }
    }
    // The caller counts positions in the list it handed us; we render a
    // deduplicated one, which can be shorter. Translate through contentId so a
    // duplicate earlier in the row cannot shift the restored card.
    val restoreFocusContentId = items.getOrNull(restoreFocusIndex)?.contentId
    // Outbound focus reports also speak the caller's list. distinctBy keeps
    // first occurrences, so a rendered item's first raw index is itself.
    val rawIndexByContentId = remember(items) {
        buildMap {
            items.forEachIndexed { rawIndex, item ->
                putIfAbsent(item.contentId, rawIndex)
            }
        }
    }
    val resolvedRestoreFocusIndex = remember(rowItems, restoreFocusContentId) {
        restoreFocusContentId
            ?.let { contentId -> rowItems.indexOfFirst { it.item.contentId == contentId } }
            ?: -1
    }

    LaunchedEffect(restoreFocusRequest, resolvedRestoreFocusIndex, restoreFocusContentId) {
        val scrolled = prepareTvMediaRowFocusRestore(
            requestId = restoreFocusRequest,
            restoreFocusIndex = resolvedRestoreFocusIndex,
            itemCount = rowItems.size,
            scrollToItem = rowState::scrollToItem,
        )
        // A restore scroll that fires while no caller-driven ladder could be
        // running is the signature of the rail snapping instead of gliding
        // (the instant jump cancels the pin's animation) — surface it in the
        // debug focus log rather than leaving it invisible on screen.
        if (restoreFocusRequest > 0 || scrolled) {
            TvFocusLog.d {
                "rail restore scroll request=$restoreFocusRequest " +
                    "index=$resolvedRestoreFocusIndex scrolled=$scrolled"
            }
        }
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
                icon = icon,
                iconSize = if (showProgress) 22.dp else 18.dp,
                onSeeAllClick = onSeeAllClick,
                eyebrow = eyebrow,
                modifier = Modifier.padding(start = startPadding, end = endPadding),
            )
        }
        TvRailScrollBehavior {
        LazyRow(
            state = rowState,
            // focusRestorer remembers the last-focused card inside this row.
            // After the user UPs to the menu and DOWNs back to the row,
            // focus returns to that exact card instead of jumping back to
            // index 0. The fallback (used the very first time, when nothing
            // is remembered yet) is the explicit firstItemFocusRequester
            // attached to index 0 — or Compose's default first-focusable
            // search if the row wasn't given one.
            modifier = Modifier
                .then(
                    if (rowContainerFocusRequester != null) {
                        Modifier.focusRequester(rowContainerFocusRequester)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onRowFocusChanged != null) {
                        Modifier.onFocusChanged { state ->
                            onRowFocusChanged(state.hasFocus)
                        }
                    } else {
                        Modifier
                    },
                )
                .focusRestorer(
                    restoreFocusRequester ?: firstItemFocusRequester ?: FocusRequester.Default,
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
                items = rowItems,
                key = { _, rowItem -> rowItem.item.contentId },
                contentType = { _, rowItem -> rowItem.contentType },
            ) { index, rowItem ->
                val item = rowItem.item
                val isRestoreFocusTarget =
                    restoreFocusRequest > 0 && index == resolvedRestoreFocusIndex
                if (isRestoreFocusTarget && onRestoreFocusTargetDisposed != null) {
                    // Report the position the caller asked about, not ours. It
                    // compares this against the index it passed in, and after
                    // deduplication the two coordinate spaces can differ.
                    DisposableEffect(restoreFocusRequest, restoreFocusIndex) {
                        onDispose {
                            onRestoreFocusTargetDisposed(restoreFocusRequest, restoreFocusIndex)
                        }
                    }
                }
                // Always anchor firstItemFocusRequester to index 0 so it can
                // serve as a stable fallback target for focusRestorer and for
                // imperative requestFocus() calls from parent screens.
                val itemFocusRequester = firstItemFocusRequester.takeIf { index == 0 }
                val appliedCardModifier = itemCardModifier.then(
                    if (index == 0) firstItemCardModifier else Modifier,
                ).then(
                    if (restoreFocusRequester != null && index == resolvedRestoreFocusIndex) {
                        Modifier.focusRequester(restoreFocusRequester)
                    } else {
                        Modifier
                    },
                ).then(
                    if (isRestoreFocusTarget && onRestoreFocusTargetPlaced != null) {
                        // Caller's coordinates, matching Disposed below: the
                        // consumer compares this against the index it passed in.
                        Modifier.onGloballyPositioned {
                            onRestoreFocusTargetPlaced(restoreFocusRequest, restoreFocusIndex)
                        }
                    } else {
                        Modifier
                    },
                ).then(
                    if (onDirectionUp != null) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                onDirectionUp?.invoke() ?: false
                            } else {
                                false
                            }
                        }
                    } else {
                        Modifier
                    },
                ).then(
                    if (upFocusRequester != null) {
                        Modifier.focusProperties { up = upFocusRequester }
                    } else {
                        Modifier
                    },
                ).tvRailPinOnFocus(rowState, index, startPadding)
                .then(
                    if (onItemFocused != null || onItemFocusedAtIndex != null) {
                        Modifier.onFocusChanged { st ->
                            if (st.isFocused) {
                                onItemFocused?.invoke(item)
                                onItemFocusedAtIndex?.invoke(
                                    item,
                                    rawIndexByContentId[item.contentId] ?: index,
                                )
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                // Memoised per item: the producer builds a fresh action bundle
                // (four fresh lambdas) on every call, and TvMediaCardActions is
                // a data class comparing those lambdas by identity — so without
                // this no visible card could ever skip recomposition once its
                // row recomposed (which the feed does on every focus move).
                //
                // Keyed on the PRODUCER as well as the item: what the bundle
                // contains depends on what the producer closes over, not only on
                // the item — Home decides whether to expose "remove from continue
                // watching" from the section it is building actions for. An
                // item-only key would keep a stale bundle (and stale callback
                // owners) after a refresh that reclassifies the section while
                // leaving the item equal (Codex).
                val itemActions = remember(item, cardActions) { cardActions(item) }
                when (cardLayout) {
                    TvRowCardLayout.ReferenceShelf -> TvReferenceShelfCard(
                        title = rowItem.shelfTitle,
                        imageUrl = rowItem.backdropUrl,
                        imageThumbhash = rowItem.backdropThumbhash,
                        subtitle = rowItem.shelfSubtitle,
                        detail = if (showProgress) rowItem.remainingMinutes?.let { "${it}m left" } else null,
                        progress = rowItem.progress,
                        onClick = { onItemClick(item.contentId) },
                        focusRequester = itemFocusRequester,
                        cardModifier = appliedCardModifier,
                        userState = item.userState,
                        actions = itemActions,
                    )
                    TvRowCardLayout.Default -> when (style) {
                        TvRowStyle.Backdrop -> TvEpisodeCard(
                            title = item.title,
                            stillUrl = rowItem.backdropUrl,
                            stillThumbhash = rowItem.backdropThumbhash,
                            seriesTitle = item.seriesTitle,
                            seasonNumber = item.seasonNumber,
                            episodeNumber = item.episodeNumber,
                            progress = rowItem.progress,
                            year = item.year.takeIf { it > 0 },
                            onClick = { onItemClick(item.contentId) },
                            focusRequester = itemFocusRequester,
                            cardModifier = appliedCardModifier,
                            userState = item.userState,
                            overlay = rowItem.overlay,
                            actions = itemActions,
                        )
                        TvRowStyle.Poster -> TvMediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            posterThumbhash = item.posterThumbhash,
                            year = item.year.takeIf { it > 0 },
                            userState = item.userState,
                            progress = rowItem.progress,
                            mediaType = item.type,
                            width = posterWidth ?: tvCardWidth(),
                            onClick = { onItemClick(item.contentId) },
                            focusRequester = itemFocusRequester,
                            cardModifier = appliedCardModifier,
                            overlay = rowItem.overlay,
                            actions = itemActions,
                        )
                    }
                }
            }
        }
        }
    }
}

internal suspend fun prepareTvMediaRowFocusRestore(
    requestId: Int,
    restoreFocusIndex: Int,
    itemCount: Int,
    scrollToItem: suspend (Int) -> Unit,
): Boolean {
    if (requestId <= 0 || restoreFocusIndex !in 0 until itemCount) return false
    scrollToItem(restoreFocusIndex)
    return true
}

/** Fraction [0..1] of item consumed for "continue watching" progress bars. */
private fun SectionItem.progressFraction(): Float? {
    val pos = positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    if (dur <= 0) return null
    return (pos / dur).toFloat().coerceIn(0f, 1f)
}

/** Remaining runtime for layouts that expose it inside the card itself. */
private fun SectionItem.remainingMinutes(): Int? {
    val pos = positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    if (dur <= 0 || pos >= dur) return null
    return ((dur - pos) / 60.0).toInt()
}

/** Prefer wide artwork for 16:9 row cards, falling back to poster only if needed. */
private fun SectionItem.bestBackdropUrl(): String? {
    return backdropUrl ?: posterUrl
}

private fun SectionItem.bestBackdropThumbhash(): String? {
    return backdropThumbhash ?: posterThumbhash
}

private fun SectionItem.shelfTitle(showProgress: Boolean): String {
    return if (showProgress && !seriesTitle.isNullOrBlank()) {
        seriesTitle ?: title
    } else {
        title
    }
}

private fun SectionItem.shelfSubtitle(showProgress: Boolean): String? {
    if (showProgress) {
        val tag = formatShelfEpisodeTag(seasonNumber, episodeNumber)
        return listOfNotNull(tag, title.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { null }
    }
    return when {
        year > 0 && genres.isNotEmpty() -> "${year} • ${genres.first()}"
        year > 0 -> year.toString()
        genres.isNotEmpty() -> genres.first()
        else -> null
    }
}

private fun formatShelfEpisodeTag(season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val s = season?.let { "S${it}" } ?: ""
    val e = episode?.let { "E${it}" } ?: ""
    return "$s $e".trim().ifBlank { null }
}
