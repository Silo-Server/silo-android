package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Android TV port of tvOS `TVSkylineSectionFeed`: shared by Home and library
 * Recommended so their lower row band, focus marquee, and ambient backdrop
 * stay pixel-aligned.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSkylineSectionFeed(
    sections: List<ResolvedSection>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequest: Int = 0,
    detailReturnFocusRequest: Int = 0,
    /** Shell-owned requester for the card a detail page was launched from.
     *  The shell uses it as its content restorer's enter fallback during the
     *  detail-return resume, so the very first synchronous focus claim lands
     *  on the launch card with no intermediate wrong-card frame. */
    detailReturnCardFocusRequester: FocusRequester? = null,
    firstRowFocusRequester: FocusRequester? = null,
    firstRowContainerRequester: FocusRequester? = null,
    onInitialContentFocus: () -> Unit = {},
    iconForSection: (ResolvedSection) -> ImageVector? = { null },
    onSeeAllClickForSection: (ResolvedSection) -> (() -> Unit)? = { null },
    showProgressForSection: (ResolvedSection) -> Boolean = { it.isTvProgressRow() },
    styleForSection: (ResolvedSection) -> TvRowStyle = {
        if (it.isTvProgressRow()) TvRowStyle.Backdrop else TvRowStyle.Poster
    },
    cardActions: (ResolvedSection, SectionItem) -> TvMediaCardActions = { _, _ -> TvMediaCardActions() },
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
) {
    val rows = remember(sections) { sections.filter { it.items.isNotEmpty() } }
    val tintState = rememberAmbientBackdropTintState()
    val context = LocalContext.current

    val catalogRepository: CatalogRepository = koinInject()
    val fetchDetail: suspend (String) -> ItemDetail? = remember(catalogRepository) {
        { contentId ->
            (catalogRepository.getItemDetail(contentId) as? ApiResult.Success)?.data
        }
    }
    val marquee = rememberTvFocusMarqueeState(fetchDetail = fetchDetail)
    val initialMarqueeSeed = remember(rows) {
        rows.firstOrNull()?.let { section ->
            section.items.firstOrNull()?.let { item ->
                TvSkylineMarqueeSeed(item = item, rowTitle = section.title)
            }
        }
    }

    LaunchedEffect(initialMarqueeSeed?.item?.contentId, initialMarqueeSeed?.rowTitle) {
        val seed = initialMarqueeSeed ?: return@LaunchedEffect
        if (marquee.content == null) {
            marquee.seedInitialPreview(seed.item, seed.rowTitle)
        }
    }

    // Warm the hero-sized backdrop/logo variants for the cards the user can
    // reach first. This is intentionally opportunistic: focus transitions
    // never wait on the network, but the shared Crossfade usually receives a
    // memory-cached image instead of a late ThumbHash replacement.
    LaunchedEffect(rows) {
        val requests = rows
            .take(HeroPreloadRowCount)
            .flatMap { it.items.take(HeroPreloadItemsPerRow) }
            .flatMap { item ->
                buildList {
                    item.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        add(
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(HeroBackdropPreloadWidthPx, HeroBackdropPreloadHeightPx)
                                .build(),
                        )
                    }
                    item.logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        add(
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(HeroLogoPreloadWidthPx, HeroLogoPreloadHeightPx)
                                .build(),
                        )
                    }
                }
            }
            .distinctBy { it.data.toString() }

        val loader = SingletonImageLoader.get(context)
        coroutineScope {
            requests.map { request ->
                async { runCatching { loader.execute(request) } }
            }.awaitAll()
        }
    }

    // Section payloads intentionally stay lightweight and omit the aired/cast
    // line. Warm detail for the same near-viewport cards whose hero artwork is
    // preloaded so normal D-pad navigation presents a complete marquee on its
    // first rested frame. The shared request guard prevents this from racing or
    // duplicating the focus-driven fetch for the currently displayed card.
    LaunchedEffect(rows, fetchDetail) {
        val loader = SingletonImageLoader.get(context)
        rows
            .take(HeroPreloadRowCount)
            .forEach { row ->
                coroutineScope {
                    row.items
                        .take(HeroPreloadItemsPerRow)
                        .map { item ->
                            async {
                                val contentId = item.contentId
                                if (!marquee.beginEnrichmentRequest(contentId)) return@async
                                try {
                                    val detail = runCatching { fetchDetail(contentId) }.getOrNull()
                                        ?: return@async
                                    val enrichment = TvMarqueeEnrichment.from(detail)
                                    marquee.applyEnrichment(contentId, enrichment)

                                    // Warm a possible episode-series art upgrade
                                    // at the exact hero decode size as well.
                                    enrichment.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                        runCatching {
                                            loader.execute(
                                                ImageRequest.Builder(context)
                                                    .data(url)
                                                    .size(
                                                        HeroBackdropPreloadWidthPx,
                                                        HeroBackdropPreloadHeightPx,
                                                    )
                                                    .build(),
                                            )
                                        }
                                    }
                                } finally {
                                    marquee.finishEnrichmentRequest(contentId)
                                }
                            }
                        }
                        .awaitAll()
                }
            }
    }

    val rowBandState = rememberLazyListState()
    // NOT keyed on `rows`: a quiet realtime/on-resume refetch emits a new
    // sections list, and resetting the focused-row index to -1 made the next
    // D-pad Up hand focus to the menu bar (the up-fallback treats <=0 as "top
    // row"). Cards are keyed by contentId + focusRestorer, so visual focus is
    // retained across the swap; the index must survive too. It's clamped into
    // the new bounds below in case rows were added/removed.
    var focusedRowIndex by remember { mutableIntStateOf(-1) }
    var focusedItemIndex by remember { mutableIntStateOf(-1) }
    var focusedContentId by remember { mutableStateOf<String?>(null) }
    var removalFocusRequest by remember { mutableIntStateOf(0) }
    // The (row, item) to restore focus to when this feed is recreated after
    // being removed from composition — saveable so it survives both the outer
    // Main → ItemDetail → Main round trip and inner-nav trips (Settings,
    // Search). Disposal drops the shell restorer's saved child NODE, so its
    // default enter can land on the wrong card; these indices let the
    // recreation ladder re-target it exactly. Updated continuously from card
    // focus (and on detail launch, where the clicked card is the focused one).
    var returnRowIndex by rememberSaveable { mutableIntStateOf(-1) }
    var returnItemIndex by rememberSaveable { mutableIntStateOf(-1) }
    // True while a restore target is armed. Gates the restore requester
    // attachments (and the row restorer's enter-fallback redirect they imply).
    var detailReturnPending by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rows) {
        val previousContentId = focusedContentId
        val focusedItemWasRemoved = previousContentId != null &&
            rows.none { row -> row.items.any { it.contentId == previousContentId } }
        if (focusedRowIndex >= rows.size) {
            focusedRowIndex = (rows.size - 1).coerceAtLeast(-1)
        }
        if (focusedRowIndex in rows.indices && focusedItemIndex >= rows[focusedRowIndex].items.size) {
            focusedItemIndex = (rows[focusedRowIndex].items.size - 1).coerceAtLeast(-1)
        }
        if (focusedItemWasRemoved && focusedRowIndex in rows.indices) {
            val targetRow = rows[focusedRowIndex]
            if (targetRow.items.isNotEmpty()) {
                returnRowIndex = focusedRowIndex
                returnItemIndex = focusedItemIndex.coerceIn(0, targetRow.items.lastIndex)
                detailReturnPending = true
                removalFocusRequest += 1
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val rowBandScope = rememberCoroutineScope()
    // Skyline matches tvOS' view-aligned row stack: vertical motion is owned by
    // this feed, while each row's LazyRow still handles horizontal card scroll.
    val onItemFocused: (SectionItem, String, Int, Int) -> Unit = { item, rowTitle, rowIndex, itemIndex ->
        marquee.preview(item, rowTitle)
        focusedRowIndex = rowIndex
        focusedItemIndex = itemIndex
        focusedContentId = item.contentId
        // Continuously mirror the browse position into the saveable return
        // slot and keep the restore armed. Any round trip that disposes this
        // feed — Settings, Search, an outer detail page — then recreates it on
        // pop restores focus (and the hero) to this exact card via the
        // recreation ladder below, instead of drifting to the first card while
        // the band is still scrolled rows down. Re-arming on every focus event
        // is safe: the ladder's first check sees the card already focused and
        // breaks immediately whenever nothing was actually lost.
        returnRowIndex = rowIndex
        returnItemIndex = itemIndex
        detailReturnPending = true
    }

    // Keep the two cards immediately before and after focus hot. Because this
    // window is established while the current card is focused, the next two
    // D-pad moves in either direction already have logo/backdrop bytes and
    // aired/cast enrichment in cache before their focus events arrive.
    LaunchedEffect(rows, focusedRowIndex, focusedItemIndex, fetchDetail) {
        val row = rows.getOrNull(focusedRowIndex) ?: return@LaunchedEffect
        if (focusedItemIndex !in row.items.indices) return@LaunchedEffect
        val window = ((focusedItemIndex - HeroFocusPrefetchRadius)..
            (focusedItemIndex + HeroFocusPrefetchRadius))
            .filter { it in row.items.indices && it != focusedItemIndex }
            .map(row.items::get)
        val loader = SingletonImageLoader.get(context)

        coroutineScope {
            window.map { item ->
                async {
                    coroutineScope {
                        val artwork = buildList {
                            item.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                add(
                                    ImageRequest.Builder(context)
                                        .data(url)
                                        .size(HeroBackdropPreloadWidthPx, HeroBackdropPreloadHeightPx)
                                        .build(),
                                )
                            }
                            item.logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                add(
                                    ImageRequest.Builder(context)
                                        .data(url)
                                        .size(HeroLogoPreloadWidthPx, HeroLogoPreloadHeightPx)
                                        .build(),
                                )
                            }
                        }
                        val artworkJobs = artwork.map { request ->
                            async { runCatching { loader.execute(request) } }
                        }
                        val detailJob = async {
                            val contentId = item.contentId
                            if (!marquee.beginEnrichmentRequest(contentId)) return@async
                            try {
                                val detail = runCatching { fetchDetail(contentId) }.getOrNull()
                                    ?: return@async
                                val enrichment = TvMarqueeEnrichment.from(detail)
                                marquee.applyEnrichment(contentId, enrichment)
                                enrichment.backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                    runCatching {
                                        loader.execute(
                                            ImageRequest.Builder(context)
                                                .data(url)
                                                .size(
                                                    HeroBackdropPreloadWidthPx,
                                                    HeroBackdropPreloadHeightPx,
                                                )
                                                .build(),
                                        )
                                    }
                                }
                            } finally {
                                marquee.finishEnrichmentRequest(contentId)
                            }
                        }
                        artworkJobs.awaitAll()
                        detailJob.await()
                    }
                }
            }.awaitAll()
        }
    }

    LaunchedEffect(focusedRowIndex, rows.size) {
        if (focusedRowIndex in rows.indices) {
            rowBandState.animateScrollToItem(focusedRowIndex)
        }
    }

    val currentContentUpFallback = rememberUpdatedState<() -> Boolean> {
        val currentRow = focusedRowIndex
        when {
            currentRow <= 0 || currentRow !in rows.indices ->
                // Top row (or unfocused): report not-handled so the shell hands
                // focus to the menu bar.
                false
            // Previous row is already laid out: move immediately so the returned
            // value is HONEST — the old code launched the move asynchronously and
            // returned `true` before it ran, so a failed move stranded focus
            // (neither moved up nor escalated to the menu).
            focusManager.moveFocus(FocusDirection.Up) -> true
            else -> {
                // Previous row is scrolled off; bring it on-screen first, then
                // move once the scroll settles (animateScrollToItem suspends until
                // it does, so the row is laid out before moveFocus).
                rowBandScope.launch {
                    rowBandState.animateScrollToItem(currentRow - 1)
                    withFrameNanos { }
                    focusManager.moveFocus(FocusDirection.Up)
                }
                true
            }
        }
    }

    // Stable per-screen registration so the shell can identify THIS feed's
    // ownership of the shared up-fallback slot across sibling (tab) swaps.
    val contentUpFallbackRegistration: () -> Boolean =
        remember { { currentContentUpFallback.value() } }

    DisposableEffect(onContentUpFallbackChanged, contentUpFallbackRegistration) {
        onContentUpFallbackChanged?.invoke(contentUpFallbackRegistration)
        onDispose {
            // Relinquish by identity (see TvMainShell's reconcile): pass our own
            // lambda, not a blind null, so the shell keeps the ENTERING feed's
            // registration. A NavHost composes the entering feed (which registers)
            // BEFORE it disposes this one, so an unconditional null here would drop
            // that new registration and lose the D-pad Up scroll-into-previous-row
            // fallback after every feed↔feed tab switch.
            onContentUpFallbackChanged?.invoke(contentUpFallbackRegistration)
        }
    }

    LaunchedEffect(marquee.backdropContent?.heroBackdropUrl) {
        marquee.backdropContent?.let { tintState.set(it.source, it.heroBackdropUrl) }
    }

    val fallbackFirstRowFocusRequester = remember { FocusRequester() }
    val resolvedFirstRowFocusRequester = firstRowFocusRequester ?: fallbackFirstRowFocusRequester
    // Attached to the first row's LazyRow group (not a card). A programmatic
    // focus request that targets a DESCENDANT of a focusRestorer group is
    // cancelled by the restorer's custom `enter` (it restores, cancels, and
    // the transaction rolls back), but a request ON the group itself is
    // honored — so the reset ladder below hops onto the row first.
    val firstRowContainerFocusRequester = firstRowContainerRequester ?: remember { FocusRequester() }
    // These consumption guards must survive the outer Main → ItemDetail → Main
    // round trip. Plain remember resets when the feed is disposed, replaying
    // both first-card effects after focusRestorer has correctly restored the
    // previously entered card.
    var initialFocusRequested by rememberSaveable { mutableStateOf(false) }
    var firstRowFocusRequest by remember { mutableIntStateOf(0) }
    val detailReturnRowContainerFocusRequester = remember { FocusRequester() }
    val detailReturnItemFocusRequester =
        detailReturnCardFocusRequester ?: remember { FocusRequester() }
    // Plain remember on purpose: the shell's detail-return counter is also
    // plain remember, so after a round trip both reset and the fresh bump
    // (0 → 1) must not be mistaken for an already-applied one.
    var lastAppliedDetailReturnRequest by remember { mutableIntStateOf(0) }
    // Last shell focus-request value we actually applied. Guards the effect
    // below so it only grabs the first row on a genuine counter bump, never on
    // a firstRowId change alone.
    var lastAppliedFocusRequest by rememberSaveable { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val firstRowId = rows.firstOrNull()?.id

    LaunchedEffect(removalFocusRequest) {
        if (removalFocusRequest == 0 || !detailReturnPending) return@LaunchedEffect
        val rowIndex = returnRowIndex
        val itemIndex = returnItemIndex
        if (rowIndex !in rows.indices || itemIndex !in rows[rowIndex].items.indices) {
            detailReturnPending = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        val rowRequester = if (rows[rowIndex].id == firstRowId) {
            firstRowContainerFocusRequester
        } else {
            detailReturnRowContainerFocusRequester
        }
        runCatching { rowRequester.requestFocus() }
        for (attempt in 0 until 8) {
            withFrameNanos { }
            if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) break
            runCatching { detailReturnItemFocusRequester.requestFocus() }
        }
        if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) {
            detailReturnPending = false
        }
    }

    fun requestFirstRowFocus(): Boolean {
        if (firstRowId == null) return false
        firstRowFocusRequest++
        onInitialContentFocus()
        return true
    }

    // Early detail-return restore: runs when this feed is RECREATED for the
    // pop-return transition, long before the shell's ON_RESUME claim (the nav
    // fade has to settle first, ~500ms). Compose assigns default focus to the
    // restored screen within its first frames — before the launch card's
    // requester attaches — which briefly landed on the return row's first
    // card. Keep re-targeting the launch card every couple of frames until
    // the request sticks, so any wrong-card frame is corrected immediately
    // instead of after the transition. Not keyed on detailReturnPending: it
    // must only fire on recreation (restored pending=true), never on the
    // click that sets pending while this feed is still composed and focused.
    LaunchedEffect(firstRowId) {
        if (!detailReturnPending || firstRowId == null) return@LaunchedEffect
        val rowIndex = returnRowIndex
        val itemIndex = returnItemIndex
        if (rowIndex !in rows.indices || itemIndex !in rows[rowIndex].items.indices) {
            return@LaunchedEffect
        }
        runCatching { rowBandState.scrollToItem(rowIndex) }
        val rowRequester = if (rows[rowIndex].id == firstRowId) {
            firstRowContainerFocusRequester
        } else {
            detailReturnRowContainerFocusRequester
        }
        for (attempt in 0 until 40) {
            withFrameNanos { }
            // The real success signal is the card's own focus callback —
            // requestFocus() can report success yet silently roll back when
            // the request crosses a restorer scope.
            if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) break
            // Hop one restorer scope per frame pair: row group, then card —
            // once default focus is anywhere inside the content group these
            // are honored, and the row restorer's enter fallback is the
            // launch card itself, so the hop lands directly on it.
            runCatching { rowRequester.requestFocus() }
            withFrameNanos { }
            runCatching { detailReturnItemFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(firstRowId, detailReturnFocusRequest) {
        if (detailReturnFocusRequest > 0) {
            // The shell's content focusRestorer owns detail-return focus. Any
            // feed recreated by Home's ON_RESUME refresh must not run its
            // first-entry fallback over the restored card.
            initialFocusRequested = true
            // Re-target the remembered launch card explicitly (the restorer's
            // saved node did not survive the round trip). Walk one restorer
            // scope per frame — row group, then card — because a request that
            // crosses a focusRestorer toward a descendant is cancelled and
            // rolled back. Skips without consuming while rows are still
            // loading so the firstRowId key re-runs it once data lands.
            if (detailReturnFocusRequest == lastAppliedDetailReturnRequest) return@LaunchedEffect
            if (!detailReturnPending) return@LaunchedEffect
            val rowIndex = returnRowIndex
            val itemIndex = returnItemIndex
            if (rowIndex !in rows.indices || itemIndex !in rows[rowIndex].items.indices) {
                detailReturnPending = false
                return@LaunchedEffect
            }
            lastAppliedDetailReturnRequest = detailReturnFocusRequest
            if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) {
                // The early recreation-time ladder already landed the launch
                // card; re-running the hops would only jiggle focus.
                detailReturnPending = false
                return@LaunchedEffect
            }
            runCatching { rowBandState.scrollToItem(rowIndex) }
            withFrameNanos { }
            val rowRequester = if (rows[rowIndex].id == firstRowId) {
                firstRowContainerFocusRequester
            } else {
                detailReturnRowContainerFocusRequester
            }
            runCatching { rowRequester.requestFocus() }
            // The card requester attaches once the row's restored LazyRow
            // window composes the launch card; retry across a few frames.
            for (attempt in 0 until 8) {
                withFrameNanos { }
                runCatching { detailReturnItemFocusRequester.requestFocus() }
                if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) break
            }
            if (focusedRowIndex == rowIndex && focusedItemIndex == itemIndex) {
                detailReturnPending = false
            }
            return@LaunchedEffect
        }
        if (initialFocusRequested || firstRowId == null) return@LaunchedEffect
        delay(120)
        if (initialFocusRequested) return@LaunchedEffect
        detailReturnPending = false
        requestFirstRowFocus()
        initialFocusRequested = true
    }

    LaunchedEffect(focusRequest, firstRowId, detailReturnFocusRequest) {
        if (detailReturnFocusRequest > 0) {
            // Consume any still-live menu handoff token without replaying it.
            lastAppliedFocusRequest = focusRequest
            return@LaunchedEffect
        }
        // Grab the first row ONLY on an actual shell focus-request bump
        // (menu→content selection), never on a firstRowId change alone. Keeping
        // firstRowId as a key lets a bump that arrived before rows loaded still
        // apply once data lands; the lastApplied guard then stops a quiet
        // refresh that swaps the first section (Continue Watching appearing or
        // disappearing) from re-firing the grab and warping focus back to
        // row 1 / card 0 mid-browse.
        if (focusRequest == 0 || firstRowId == null) return@LaunchedEffect
        if (focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        lastAppliedFocusRequest = focusRequest
        // Menu re-select is a full reset; drop any stale detail-return target.
        detailReturnPending = false
        // The shell bumps its token for EVERY menu selection, and during the
        // route crossfade the exiting feed is still composed — without this
        // gate it would briefly steal focus back (Home flashing focused en
        // route to Calendar). Exiting nav entries fall to STARTED and never
        // resume, so they park here until disposal with the token already
        // consumed; the entering (or re-selected) feed passes immediately or
        // when its transition settles.
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        // Menu re-select = full reset to the app-launch state: band scrolled
        // to the top, focus on row 0 / card 0. Focus walks down one scope per
        // frame (row group, then card) because each restorer-guarded scope
        // only honors a request on ITSELF — a request that has to pass
        // through a restorer to a descendant is cancelled and rolled back.
        onInitialContentFocus()
        runCatching {
            // Constant-velocity return to the top (~4 px/ms), clamped so a
            // one-row hop stays snappy and a deep-feed reset never drags.
            // animateScrollToItem's fixed-feel spec turns abrupt over long
            // distances, so drive the scroll by pixel distance instead.
            val info = rowBandState.layoutInfo
            val rowExtent = (info.visibleItemsInfo.firstOrNull()?.size ?: 0) +
                info.mainAxisItemSpacing
            val distance = rowBandState.firstVisibleItemIndex.toFloat() * rowExtent +
                rowBandState.firstVisibleItemScrollOffset
            if (distance > 0f) {
                val durationMs = (distance / 4f).toInt().coerceIn(250, 900)
                rowBandState.animateScrollBy(
                    -distance,
                    tween(durationMs, easing = FastOutSlowInEasing),
                )
            }
            // Exact-align safety net for any rounding drift; no-op at rest.
            rowBandState.scrollToItem(0)
        }
        withFrameNanos { }
        runCatching { firstRowContainerFocusRequester.requestFocus() }
        withFrameNanos { }
        runCatching { resolvedFirstRowFocusRequester.requestFocus() }
    }

    CompositionLocalProvider(
        LocalAmbientBackdropTint provides tintState,
        LocalBringIntoViewSpec provides TvSkylineBringIntoViewSpec,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val bandHeight = maxHeight * TvSkylineRowBandHeightFraction
            val trailingPreviewPadding = (bandHeight - TvSkylineRowBandBottomInset).coerceAtLeast(0.dp)

            // Base content changes drive matching 240 ms backdrop and copy
            // crossfades. Detail enrichment stays independent, mirroring tvOS:
            // its line fades into reserved space and episode art may upgrade,
            // but neither event replays/reflows the whole marquee.
            TvRootHeroBackdrop(
                content = marquee.backdropContent,
                modifier = Modifier.fillMaxSize(),
            )
            TvFocusMarquee(
                content = marquee.content,
                detailLine = marquee.enrichment?.detailLine,
                startPadding = Spacing.safeArea,
                bottomPadding = bandHeight + TvSkylineMarqueeBottomGap,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bandHeight)
                    .align(Alignment.BottomStart)
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = rowBandState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(TvSkylineRowPreviewSpacing),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = trailingPreviewPadding,
                    ),
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, row -> row.id },
                        contentType = { _, _ -> "skyline-section-row" },
                    ) { rowIndex, section ->
                        val isFirstRow = section.id == firstRowId
                        val showProgress = showProgressForSection(section)
                        val isReturnRow = detailReturnPending && rowIndex == returnRowIndex
                        TvMediaRow(
                            title = section.title,
                            items = section.items,
                            onItemClick = { contentId ->
                                returnRowIndex = rowIndex
                                returnItemIndex =
                                    section.items.indexOfFirst { it.contentId == contentId }
                                detailReturnPending = true
                                onItemClick(contentId)
                            },
                            icon = iconForSection(section),
                            onSeeAllClick = onSeeAllClickForSection(section),
                            showProgress = showProgress,
                            style = styleForSection(section),
                            startPadding = Spacing.safeArea,
                            endPadding = Spacing.safeArea,
                            itemSpacing = TvSkylineItemSpacing,
                            rowTopPadding = TvSkylineRowCardVerticalPadding,
                            rowBottomPadding = TvSkylineRowCardVerticalPadding,
                            posterWidth = RowDimens.DensePosterWidth,
                            firstItemFocusRequester = resolvedFirstRowFocusRequester
                                .takeIf { isFirstRow },
                            rowContainerFocusRequester = when {
                                isFirstRow -> firstRowContainerFocusRequester
                                isReturnRow -> detailReturnRowContainerFocusRequester
                                else -> null
                            },
                            firstItemFocusRequest = if (isFirstRow) firstRowFocusRequest else 0,
                            restoreFocusIndex = if (isReturnRow) returnItemIndex else -1,
                            restoreFocusRequester = detailReturnItemFocusRequester
                                .takeIf { isReturnRow },
                            onItemFocusedAtIndex = { item, itemIndex ->
                                onItemFocused(item, section.title, rowIndex, itemIndex)
                            },
                            cardActions = { item -> cardActions(section, item) },
                        )
                    }
                }
            }

        }
    }
}

fun ResolvedSection.isTvProgressRow(): Boolean {
    val type = sectionType.lowercase()
    return type.contains("continue") ||
        type.contains("in_progress") ||
        type.contains("next_up") ||
        type.contains("up_next")
}

private data class TvSkylineMarqueeSeed(
    val item: SectionItem,
    val rowTitle: String,
)

// 0.64 × 1920 by 0.70 × 1080, and the 440×100dp logo cap at 2× density.
private const val HeroBackdropPreloadWidthPx = 1229
private const val HeroBackdropPreloadHeightPx = 756
private const val HeroLogoPreloadWidthPx = 880
private const val HeroLogoPreloadHeightPx = 200
private const val HeroPreloadRowCount = 2
private const val HeroPreloadItemsPerRow = 8
private const val HeroFocusPrefetchRadius = 2

/** tvOS MediaRow cardSpacing 40pt maps to 20dp. */
private val TvSkylineItemSpacing = 20.dp

/** A little breathing room between adjacent Home sections. */
private val TvSkylineRowPreviewSpacing = 14.dp

/** tvOS rowBandCardVerticalPadding 14pt maps to 7dp. */
private val TvSkylineRowCardVerticalPadding = 7.dp

/** tvOS rowBandBottomInset 20pt maps to 10dp. */
private val TvSkylineRowBandBottomInset = 10.dp

/** Portion of the screen reserved for the row stack. */
private const val TvSkylineRowBandHeightFraction = 0.50f

/** Gap between the marquee block and the top of the row band. */
private val TvSkylineMarqueeBottomGap = 12.dp

// Row-band relocation requests are close to row-sized; horizontal card rails
// have much wider viewports and must still use the smooth scroll distance.
private const val TvSkylineVerticalContainerRatio = 3f

private val TvSkylineBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 520,
        easing = FastOutSlowInEasing,
    )

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val isVerticalRowBand = size > 0f && containerSize <= size * TvSkylineVerticalContainerRatio
        return if (isVerticalRowBand) {
            0f
        } else {
            TvSmoothBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
        }
    }
}
