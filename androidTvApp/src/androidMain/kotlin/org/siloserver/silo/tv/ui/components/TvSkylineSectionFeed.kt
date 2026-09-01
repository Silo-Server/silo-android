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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.tv.ui.focus.TvReturnResolution
import org.siloserver.silo.tv.ui.focus.TvReturnTarget
import org.siloserver.silo.tv.ui.focus.keyedTvReturnTargetSaver
import org.siloserver.silo.tv.ui.focus.keyedBooleanSaver
import org.siloserver.silo.tv.ui.focus.keyedIntSaver
import org.siloserver.silo.tv.ui.focus.resolveTvReturnTarget
import org.siloserver.silo.tv.ui.focus.toTvReturnSections
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.common.diagnostics.DiagnosticsListLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.diagnostics.DiagnosticsListSurface

/**
 * Data worth warming while a card is focused because Select opens a different
 * detail identity than the card itself (Continue Watching episodes open their
 * combined Series page). All fields are cache-only inputs; no navigation or
 * presentation state is changed by this request.
 */
data class TvSkylineDetailPrefetchTarget(
    val detailContentId: String,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeContentId: String? = null,
)

private data class TvSkylinePriorityPrefetchRequest(
    val sourceContentId: String,
    val target: TvSkylineDetailPrefetchTarget,
)

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
    /**
     * Identifies the surface this feed instance belongs to.
     *
     * `rememberSaveable` slots are POSITIONAL. Two feeds composed at the same
     * position in different surfaces — Home and a library detail — otherwise
     * share one slot, so a return target armed on one could be restored into
     * the other, sending focus to a card that surface never showed.
     *
     * Keying the slot alone is not enough, because rememberSaveable does not
     * validate a value RESTORED after process death against its inputs. This
     * key is therefore written into the savers too, and a payload belonging to
     * another surface is discarded on the way back.
     */
    surfaceKey: String,
    modifier: Modifier = Modifier,
    /** Home-only foreground drop; backdrop and fixed top navigation do not move. */
    contentVerticalOffset: Dp = 0.dp,
    /**
     * False while rows are still being hydrated.
     *
     * Without it a launch row that has not arrived yet is indistinguishable
     * from one that is gone, and resolution settles on the nearest survivor —
     * driving focus to a card the viewer never opened and retiring the real
     * target on the way. The rows list cannot answer this itself: it is
     * filtered to sections that already HAVE items, so an unhydrated
     * placeholder is dropped before the adapter could mark it incomplete.
     */
    sectionsComplete: Boolean = true,
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
    /** A non-null action replaces [onItemClick] while preserving detail-return tracking. */
    clickActionForSection: (ResolvedSection, SectionItem) -> (() -> Unit)? = { _, _ -> null },
    /**
     * Optional high-priority cache warmer for cards whose Select target needs
     * more than the card's own marquee detail. It starts on raw focus (and for
     * the initial card) so an immediate Select does not wait for the marquee's
     * focus-rest interval before useful work begins.
     */
    priorityDetailPrefetchTargetForSection: (
        ResolvedSection,
        SectionItem,
    ) -> TvSkylineDetailPrefetchTarget? = { _, _ -> null },
    /** A non-null action replaces the card context menu for that long press. */
    longClickActionForSection: (ResolvedSection, SectionItem) -> (() -> Unit)? = { _, _ -> null },
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
) {
    val rows = remember(sections) { sections.filter { it.items.isNotEmpty() } }
    val diagnosticsSurface = when {
        surfaceKey == "home" -> DiagnosticsListSurface.TV_HOME
        surfaceKey == "for_you" -> DiagnosticsListSurface.TV_FOR_YOU
        surfaceKey.startsWith("library-") -> DiagnosticsListSurface.TV_LIBRARY_RECOMMENDED
        else -> null
    }
    val diagnosticsListSnapshot = remember(rows, sectionsComplete) {
        DiagnosticsListSnapshot.fromKeys(
            keys = rows.map { it.id },
            rowKeys = rows.map { section -> section.items.map { it.contentId } },
            fullyResolved = sectionsComplete,
        )
    }
    LaunchedEffect(diagnosticsSurface, diagnosticsListSnapshot) {
        diagnosticsSurface?.let { surface ->
            DiagnosticsListLogger.snapshot(surface, diagnosticsListSnapshot)
        }
    }
    val tintState = rememberAmbientBackdropTintState()
    val context = LocalContext.current

    val catalogRepository: CatalogRepository = koinInject()
    val fetchDetail: suspend (String) -> ItemDetail? = remember(catalogRepository) {
        { contentId ->
            (catalogRepository.getItemDetailForPrefetch(contentId) as? ApiResult.Success)?.data
        }
    }
    val marquee = rememberTvFocusMarqueeState(fetchDetail = fetchDetail)
    val initialMarqueeSeed = remember(rows) {
        rows.firstOrNull()?.let { section ->
            section.items.firstOrNull()?.let { item ->
                TvSkylineMarqueeSeed(
                    item = item,
                    rowTitle = section.title,
                    rowIdentity = section.id,
                )
            }
        }
    }

    LaunchedEffect(
        initialMarqueeSeed?.item?.contentId,
        initialMarqueeSeed?.rowTitle,
        initialMarqueeSeed?.rowIdentity,
    ) {
        val seed = initialMarqueeSeed ?: return@LaunchedEffect
        marquee.seedInitialPreview(seed.item, seed.rowTitle, seed.rowIdentity)
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

    val priorityPrefetchRequest = remember(
        rows,
        focusedRowIndex,
        focusedContentId,
        initialMarqueeSeed,
    ) {
        val focused = rows.getOrNull(focusedRowIndex)?.let { section ->
            section.items.firstOrNull { it.contentId == focusedContentId }?.let { item ->
                priorityDetailPrefetchTargetForSection(section, item)?.let { target ->
                    TvSkylinePriorityPrefetchRequest(item.contentId, target)
                }
            }
        }
        focused ?: rows.firstOrNull()?.let { section ->
            section.items.firstOrNull()?.let { item ->
                priorityDetailPrefetchTargetForSection(section, item)?.let { target ->
                    TvSkylinePriorityPrefetchRequest(item.contentId, target)
                }
            }
        }
    }

    // Continue Watching is a latency-sensitive handoff: a movie opens its own
    // detail, while an episode opens Series + season + episode state. Warm the
    // exact navigation target immediately, independently of the 150 ms marquee
    // rest used to avoid noisy visual enrichment requests during fast D-pad
    // travel. The cache-first repository calls make revisits local-only.
    LaunchedEffect(priorityPrefetchRequest, fetchDetail) {
        val request = priorityPrefetchRequest ?: return@LaunchedEffect
        val target = request.target
        if (target.detailContentId.isBlank()) return@LaunchedEffect

        suspend fun warmDetail(contentId: String) {
            if (contentId.isBlank() || !marquee.beginEnrichmentRequest(contentId)) return
            try {
                val detail = runCatching { fetchDetail(contentId) }.getOrNull() ?: return
                // Only the card's own detail may enrich its hero. A Continue
                // Watching episode also warms its Series target, but Series
                // copy must never replace episode copy in the Home marquee.
                if (contentId == request.sourceContentId) {
                    marquee.applyEnrichment(contentId, TvMarqueeEnrichment.from(detail))
                }
            } finally {
                marquee.finishEnrichmentRequest(contentId)
            }
        }

        coroutineScope {
            val jobs = linkedSetOf(target.detailContentId, target.episodeContentId)
                .filterNotNull()
                .map { contentId -> async { warmDetail(contentId) } }
                .toMutableList()

            val seriesId = target.seriesId?.takeIf { it.isNotBlank() }
            if (seriesId != null) {
                jobs += async {
                    runCatching { catalogRepository.getSeasonsForPrefetch(seriesId) }
                    Unit
                }
                target.seasonNumber?.let { seasonNumber ->
                    jobs += async {
                        runCatching {
                            catalogRepository.getEpisodesForPrefetch(seriesId, seasonNumber)
                        }
                        Unit
                    }
                }
            }
            jobs.awaitAll()
        }
    }
    // Disposal drops the shell restorer's saved child NODE, so its default
    // enter can land on the wrong card; this target is what lets the recreation
    // ladder re-target the launch card exactly. Updated continuously from card
    // focus (and on detail launch, where the clicked card is the focused one),
    // except while a restoration is running.
    // The card a detail page was launched from, by identity. Two saved indices
    // used to stand in for this, and they only describe the same card while the
    // data is unchanged: a refresh on resume, Continue Watching reordering
    // after playback, a finished item leaving its row, or a recreated process
    // rebuilding from the server all leave the numbers valid and pointing
    // somewhere else. Saveable so it survives both the outer round trip and
    // process death, which is exactly when the live focus state below is gone
    // and indices would be all that was left.
    // Keyed SAVER, not just a keyed slot: rememberSaveable does not validate a
    // value restored after process death against its inputs, so a process
    // coming back on a different feed first would otherwise adopt this one's
    // target. Same guard TvFlatReturnRestoration already applies.
    var returnTarget by rememberSaveable(
        surfaceKey,
        stateSaver = keyedTvReturnTargetSaver(surfaceKey),
    ) {
        mutableStateOf<TvReturnTarget?>(null)
    }
    // True while a restore target is armed. Gates the restore requester
    // attachments (and the row restorer's enter-fallback redirect they imply).
    var detailReturnPending by rememberSaveable(
        surfaceKey,
        stateSaver = keyedBooleanSaver(surfaceKey, slot = "detailReturnPending"),
    ) { mutableStateOf(false) }
    // True while a ladder is actively driving focus back to the launch card.
    //
    // Focus lands on the wrong card first often enough that these ladders exist
    // for it, and every focus gain re-arms the return target. Without this the
    // intermediate card's focus callback overwrites the armed identity, the
    // resolution recomputes around it, and the ladder then declares success
    // against content the viewer never launched — the identity contract
    // defeating itself.
    // Counted, not a flag: the recreation ladder and the shell-request ladder
    // can both be live at once, and a boolean would let whichever finished
    // first re-open the window while the other was still driving focus.
    var restorationsInFlight by remember { mutableIntStateOf(0) }
    // Bumped every time a new return target is armed. A ladder captures it on
    // entry and stops the moment it no longer matches, because the driver
    // deliberately follows the current resolution: without this an older ladder
    // would pivot onto a newly clicked card, see it already focused, and clear
    // the NEW trip's pending state — losing restoration for the trip that had
    // only just started.
    var returnGeneration by rememberSaveable(
        surfaceKey,
        stateSaver = keyedIntSaver(surfaceKey, slot = "returnGeneration"),
    ) { mutableIntStateOf(0) }
    // Bumped when a ladder starts, so the row scrolls its own LazyRow to the
    // resolved card. Without it the card can sit outside the composed
    // horizontal window after a reorder, the requester never attaches, and
    // every retry fails — the contract's obligation to scroll the destination
    // into composition, unmet.
    var returnRestoreRequest by remember { mutableIntStateOf(0) }

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
        // Same guard as browse re-arming: a refresh that removes whatever
        // incidental focus happened to land on must not redefine what the
        // viewer launched from.
        if (focusedItemWasRemoved && focusedRowIndex in rows.indices && restorationsInFlight == 0) {
            val targetRow = rows[focusedRowIndex]
            if (targetRow.items.isNotEmpty()) {
                val itemIndex = focusedItemIndex.coerceIn(0, targetRow.items.lastIndex)
                returnTarget = TvReturnTarget(
                    sectionId = targetRow.id,
                    itemId = targetRow.items[itemIndex].contentId,
                    sectionIndex = focusedRowIndex,
                    itemIndex = itemIndex,
                )
                returnGeneration++
                detailReturnPending = true
                removalFocusRequest += 1
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val rowBandScope = rememberCoroutineScope()
    // Skyline matches tvOS' view-aligned row stack: vertical motion is owned by
    // this feed, while each row's LazyRow still handles horizontal card scroll.
    val onItemFocused: (SectionItem, String, String, Int, Int) -> Unit =
        { item, rowTitle, rowIdentity, rowIndex, itemIndex ->
        marquee.preview(item, rowTitle, rowIdentity)
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
        // Browse movement re-arms; a restoration in progress must not. See
        // restorationInFlight above.
        if (restorationsInFlight == 0) {
            returnTarget = TvReturnTarget(
                sectionId = rowIdentity,
                itemId = item.contentId,
                sectionIndex = rowIndex,
                itemIndex = itemIndex,
            )
            returnGeneration++
            detailReturnPending = true
        }
    }

    // Keep a small window around RESTED focus hot. A raw D-pad move cancels the
    // previous job immediately, but the new identity starts no speculative
    // work until the marquee's focus-rest transaction commits it.
    val settledFocus = settledFocusIdentity(
        rawRowIndex = focusedRowIndex,
        rawFocusedContentId = focusedContentId,
        rawFocusedMarqueeId = rows.getOrNull(focusedRowIndex)
            ?.let { row -> focusedContentId?.let { contentId -> "${row.id}#$contentId" } },
        settledMarqueeId = marquee.content?.id,
    )
    LaunchedEffect(rows, settledFocus, fetchDetail) {
        val focus = settledFocus ?: return@LaunchedEffect
        val row = rows.getOrNull(focus.rowIndex) ?: return@LaunchedEffect
        val window = settledPrefetchItems(
            items = row.items,
            rawFocusedContentId = focus.contentId,
            settledContentId = focus.contentId,
            radius = HeroFocusPrefetchRadius,
        )
        if (window.isEmpty()) return@LaunchedEffect
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

    var rowRelocationInFlight by remember { mutableStateOf(false) }
    val currentContentUpFallback = rememberUpdatedState<(Boolean) -> Boolean> { isRepeat ->
        val bandTopRow = rowBandState.firstVisibleItemIndex
        // See tvSkylineEffectiveRow: the reported focused row can lag or be
        // clamped; the band's top row is the ground truth it is checked against.
        val currentRow = tvSkylineEffectiveRow(focusedRowIndex, bandTopRow, rows.size)
        when (
            tvSkylineUpAction(
                currentRow = focusedRowIndex,
                rowCount = rows.size,
                isRepeat = isRepeat,
                relocationInFlight = rowRelocationInFlight,
                bandTopRow = bandTopRow,
            )
        ) {
            TvSkylineUpAction.EnterMenu -> false
            TvSkylineUpAction.StayInContent -> true
            TvSkylineUpAction.TryPreviousRow -> {
                if (focusManager.moveFocus(FocusDirection.Up)) {
                    return@rememberUpdatedState true
                }
                // Previous row is scrolled off; bring it on-screen first, then
                // move once the scroll settles. While this job owns relocation,
                // key repeats are consumed instead of launching competing jobs.
                rowRelocationInFlight = true
                rowBandScope.launch {
                    try {
                        val targetRow = (currentRow - 1).coerceAtLeast(0)
                        rowBandState.animateScrollToItem(targetRow)
                        // On a slow device the row's cards can take more than one
                        // frame to lay out after the scroll settles; moving before
                        // they exist finds nothing and strands focus. Wait for the
                        // target row to be present (bounded), then move.
                        var frames = 0
                        while (
                            frames < RelocationLayoutFrameBudget &&
                            rowBandState.layoutInfo.visibleItemsInfo.none { it.index == targetRow }
                        ) {
                            withFrameNanos { }
                            frames++
                        }
                        withFrameNanos { }
                        focusManager.moveFocus(FocusDirection.Up)
                    } finally {
                        rowRelocationInFlight = false
                    }
                }
                true
            }
        }
    }

    // Stable per-screen registration so the shell can identify THIS feed's
    // ownership of the shared up-fallback slot across sibling (tab) swaps.
    val contentUpFallbackRegistration: (Boolean) -> Boolean =
        remember { { isRepeat -> currentContentUpFallback.value(isRepeat) } }

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
    // Where the launch card is NOW. Resolved against the rows this feed
    // actually renders — Skyline drops empty rows before layout, so a
    // projection taken from further upstream would put these indices in a
    // different coordinate space from the rows they address.
    //
    // Rows are treated as a complete snapshot: an aggregate feed response
    // arrives whole, and a row trimmed to its item limit is capped rather than
    // paged, so nothing further will load into it. SameSectionOnly because
    // Home rows overlap — a title can sit in Continue Watching and Recently
    // Added at once, and following an id across rows would jump focus to a
    // copy the viewer never touched.
    // The section map depends on the rows alone; the return target is re-armed
    // on every focus move, so building the map inside the resolution remember
    // copied every content id in the feed per keypress.
    val returnSections = remember(rows) { rows.toTvReturnSections() }
    val returnResolution: TvReturnResolution =
        remember(returnSections, returnTarget, detailReturnPending) {
            if (detailReturnPending) {
                resolveTvReturnTarget(
                    target = returnTarget,
                    sections = returnSections,
                    sectionsComplete = sectionsComplete,
                )
            } else {
                TvReturnResolution.Empty
            }
        }
    // Read fresh inside the retry ladders. The resolution is a plain remembered
    // value, so a coroutine that captured it keeps working from the rows of the
    // composition it launched in; a quiet refresh would leave it steering by a
    // map of a feed that is no longer on screen.
    val currentResolution by rememberUpdatedState(returnResolution)
    val locatedReturn = returnResolution as? TvReturnResolution.Located


    // Landing is confirmed by identity, not by coordinates. The card at a
    // given index is not necessarily the card that was resolved, and an
    // index-only check reports success for whatever now occupies the slot.
    fun hasLandedOnReturnTarget(): Boolean {
        val located = currentResolution as? TvReturnResolution.Located ?: return false
        return focusedRowIndex == located.sectionIndex && focusedContentId == located.itemId
    }

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

    /**
     * Walk focus back to the resolved launch card, re-reading where that is on
     * every attempt.
     *
     * Steering has to be as fresh as the success check. A refresh that keeps
     * the same first row does not restart these effects, so a ladder that
     * captured its destination up front would keep driving toward a row the
     * feed has since moved, while the predicate looks for the new one — it
     * cannot succeed, and for the shell ladder the request token has already
     * been marked applied, so nothing retries.
     *
     * Hops one focus-restorer scope per frame pair — row group, then card —
     * because a request that crosses a restorer toward a descendant is
     * cancelled and rolled back. The vertical band is scrolled whenever the
     * resolved row changes, not once at the start, for the same reason.
     */
    suspend fun driveFocusToReturnTarget(generation: Int, attempts: Int, scrollBand: Boolean) {
        var scrolledToSection = -1
        repeat(attempts) {
            withFrameNanos { }
            // Someone armed a newer target; that trip owns restoration now.
            if (generation != returnGeneration) return
            // The real success signal is the card's own focus callback —
            // requestFocus() can report success yet silently roll back when
            // the request crosses a restorer scope.
            if (hasLandedOnReturnTarget()) return
            val located = currentResolution as? TvReturnResolution.Located ?: return
            if (scrollBand && located.sectionIndex != scrolledToSection) {
                val scrolled = runCatching { rowBandState.scrollToItem(located.sectionIndex) }
                scrolled.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                // Only remember a scroll that actually happened, or a failure
                // would be recorded as done and never retried.
                if (scrolled.isSuccess) scrolledToSection = located.sectionIndex
            }
            // Classified from the fresh index rather than a captured
            // firstRowId: the removal ladder is not keyed on the row list, so
            // a reorder mid-run would otherwise keep it addressing the row the
            // target used to be in.
            val rowRequester = if (located.sectionIndex == 0) {
                firstRowContainerFocusRequester
            } else {
                detailReturnRowContainerFocusRequester
            }
            runCatching { rowRequester.requestFocus() }
            withFrameNanos { }
            runCatching { detailReturnItemFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(removalFocusRequest) {
        if (removalFocusRequest == 0 || !detailReturnPending) return@LaunchedEffect
        // Pending means the answer is not knowable yet; keep the target and
        // wait for the data rather than spending it on a stand-in.
        if (returnResolution is TvReturnResolution.Pending) return@LaunchedEffect
        if (locatedReturn == null) {
            detailReturnPending = false
            return@LaunchedEffect
        }
        val generation = returnGeneration
        restorationsInFlight++
        returnRestoreRequest++
        try {
            withFrameNanos { }
            driveFocusToReturnTarget(generation, attempts = 8, scrollBand = false)
        } finally {
            restorationsInFlight--
        }
        // Only the trip that owns the target may retire it.
        if (generation == returnGeneration && hasLandedOnReturnTarget()) {
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
        if (returnResolution is TvReturnResolution.Pending) return@LaunchedEffect
        if (locatedReturn == null) return@LaunchedEffect
        val generation = returnGeneration
        restorationsInFlight++
        returnRestoreRequest++
        try {
            driveFocusToReturnTarget(generation, attempts = 40, scrollBand = true)
        } finally {
            restorationsInFlight--
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
            // Skip WITHOUT consuming the request while the answer is still
            // unknowable, so the firstRowId key re-runs this once data lands.
            if (returnResolution is TvReturnResolution.Pending) return@LaunchedEffect
            if (locatedReturn == null) {
                detailReturnPending = false
                return@LaunchedEffect
            }
            lastAppliedDetailReturnRequest = detailReturnFocusRequest
            if (hasLandedOnReturnTarget()) {
                // The early recreation-time ladder already landed the launch
                // card; re-running the hops would only jiggle focus.
                detailReturnPending = false
                return@LaunchedEffect
            }
            val generation = returnGeneration
            restorationsInFlight++
            returnRestoreRequest++
            try {
                withFrameNanos { }
                driveFocusToReturnTarget(generation, attempts = 8, scrollBand = true)
            } finally {
                restorationsInFlight--
            }
            if (generation == returnGeneration && hasLandedOnReturnTarget()) {
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
            val presentation = LocalCardPresentation.current
            val bandHeight = tvSkylineRowBandHeight(presentation)
                .coerceAtMost(maxHeight * TvSkylineRowBandMaxFraction)
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
                startPadding = TvSkyline.safeAreaX,
                // Keep the marquee out of the top-menu-bar zone: the block is
                // bottom-anchored and grows upward, and the raised typography
                // floors made it tall enough to collide with the bar.
                topPadding = TvSkyline.barTopInset + TvSkyline.barHeight,
                bottomPadding = bandHeight + TvSkylineMarqueeBottomGap,
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = contentVerticalOffset),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bandHeight)
                    .align(Alignment.BottomStart)
                    .offset(y = contentVerticalOffset)
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
                        val isReturnRow = locatedReturn?.sectionIndex == rowIndex
                        TvMediaRow(
                            title = section.title,
                            items = section.items,
                            onItemClick = { contentId ->
                                val item = section.items.firstOrNull { it.contentId == contentId }
                                returnTarget = TvReturnTarget(
                                    sectionId = section.id,
                                    itemId = contentId,
                                    sectionIndex = rowIndex,
                                    itemIndex = section.items
                                        .indexOfFirst { it.contentId == contentId },
                                )
                                returnGeneration++
                                detailReturnPending = true
                                item?.let { clickActionForSection(section, it) }?.invoke()
                                    ?: onItemClick(contentId)
                            },
                            icon = iconForSection(section),
                            onSeeAllClick = onSeeAllClickForSection(section),
                            showProgress = showProgress,
                            style = styleForSection(section),
                            startPadding = TvSkyline.safeAreaX,
                            endPadding = Spacing.safeArea,
                            itemSpacing = TvSkylineItemSpacing,
                            rowTopPadding = TvSkylineRowCardVerticalPadding,
                            rowBottomPadding = TvSkylineRowCardVerticalPadding,
                            posterWidth = RowDimens.DensePosterWidth *
                                presentation.posterSize.posterScale,
                            firstItemFocusRequester = resolvedFirstRowFocusRequester
                                .takeIf { isFirstRow },
                            rowContainerFocusRequester = when {
                                isFirstRow -> firstRowContainerFocusRequester
                                isReturnRow -> detailReturnRowContainerFocusRequester
                                else -> null
                            },
                            firstItemFocusRequest = if (isFirstRow) firstRowFocusRequest else 0,
                            restoreFocusIndex = if (isReturnRow) {
                                locatedReturn?.itemIndex ?: -1
                            } else {
                                -1
                            },
                            // Bumped when a ladder starts, so the row scrolls
                            // its own LazyRow to the resolved card. A card that
                            // moved horizontally can otherwise sit outside the
                            // composed window, leaving the requester unattached
                            // and every retry doomed.
                            //
                            // Gated on a ladder being IN FLIGHT, not on the row
                            // merely being the return row: the pending target is
                            // re-armed by every focus move and the counter
                            // outlives its ladder, so an ungated request
                            // re-fired the row's instant restore scrollToItem on
                            // every focus move after a detail round trip —
                            // cancelling the rail pin's animated glide. Ordinary
                            // row rendering must never move the row.
                            restoreFocusRequest = if (isReturnRow && restorationsInFlight > 0) {
                                returnRestoreRequest
                            } else {
                                0
                            },
                            restoreFocusRequester = detailReturnItemFocusRequester
                                .takeIf { isReturnRow },
                            onItemFocusedAtIndex = { item, itemIndex ->
                                onItemFocused(item, section.title, section.id, rowIndex, itemIndex)
                            },
                            cardActions = { item -> cardActions(section, item) },
                            longClickAction = { item ->
                                longClickActionForSection(section, item)
                            },
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

/** Rows whose Select action resumes immediately instead of opening details. */
fun ResolvedSection.isTvContinueWatchingRow(): Boolean {
    val type = sectionType.lowercase()
    return type.contains("continue") || type.contains("in_progress")
}

private data class TvSkylineMarqueeSeed(
    val item: SectionItem,
    val rowTitle: String,
    val rowIdentity: String,
)

// 0.64 × 1920 by 0.70 × 1080, and the 440×100dp logo cap at 2× density.
private const val HeroBackdropPreloadWidthPx = 1229
private const val HeroBackdropPreloadHeightPx = 756
private const val HeroLogoPreloadWidthPx = 880
private const val HeroLogoPreloadHeightPx = 200
private const val HeroFocusPrefetchRadius = 2

/** tvOS MediaRow cardSpacing 40pt maps to 20dp. */
private val TvSkylineItemSpacing = 20.dp

/** A little breathing room between adjacent Home sections. */
private val TvSkylineRowPreviewSpacing = 14.dp

/** tvOS rowBandCardVerticalPadding 14pt maps to 7dp. */
private val TvSkylineRowCardVerticalPadding = 7.dp

/** tvOS rowBandBottomInset 20pt maps to 10dp. */
private val TvSkylineRowBandBottomInset = 10.dp

// The band is sized from what its first row actually needs — section header,
// card at the scaled dense-poster height, and whichever caption lines the
// card-presentation preference shows — plus a peek of the next section. The
// previous fixed 0.50 fraction clipped `large` cards against the marquee and
// wasted marquee room under artwork-only captions. At the standard preset
// this derivation reproduces the old 270dp band on a 540dp-tall layout.
private val TvSkylineRowHeaderHeight = 26.dp // TvSectionHeader: 22sp title line + 4dp bottom pad
private val TvSkylineRowHeaderGap = 12.dp // TvMediaRow header→rail spacing
private val TvSkylineCaptionTitleHeight = 30.dp // TvMediaCard: 11dp spacer + 18.5sp title line
private val TvSkylineCaptionMetadataHeight = 18.dp // TvMediaCard: 18sp year line
private val TvSkylineRowPreviewPeek = 24.dp // sliver of the next section's header

/** The band never pushes the marquee below ~40% of the screen. */
private const val TvSkylineRowBandMaxFraction = 0.58f

private fun tvSkylineRowBandHeight(presentation: CardPresentation): Dp {
    val cardHeight = RowDimens.DensePosterWidth * presentation.posterSize.posterScale * 3f / 2f
    val captionHeight = when {
        !presentation.caption.showsTitle -> 0.dp
        presentation.caption.showsMetadata ->
            TvSkylineCaptionTitleHeight + TvSkylineCaptionMetadataHeight
        else -> TvSkylineCaptionTitleHeight
    }
    return TvSkylineRowHeaderHeight + TvSkylineRowHeaderGap +
        TvSkylineRowCardVerticalPadding * 2 + cardHeight + captionHeight +
        TvSkylineRowPreviewSpacing + TvSkylineRowPreviewPeek
}

/** Gap between the marquee block and the top of the row band. */
private val TvSkylineMarqueeBottomGap = 4.dp

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

/** Frames to wait for a relocated row to lay out before moving focus into it. */
private const val RelocationLayoutFrameBudget = 12
