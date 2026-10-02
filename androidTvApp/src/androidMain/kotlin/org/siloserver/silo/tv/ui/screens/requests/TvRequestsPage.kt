package org.siloserver.silo.tv.ui.screens.requests

import androidx.compose.runtime.rememberCoroutineScope
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.viewmodel.RequestApprovalsUiState
import org.siloserver.silo.model.request.RequestDiscoverySection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.requests.RequestStageTrack
import org.siloserver.silo.model.catalog.ExternalRatings
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestAttention
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvFocusMarquee
import org.siloserver.silo.tv.ui.components.TvHeroFactToken
import org.siloserver.silo.tv.ui.components.TvMarqueeContent
import org.siloserver.silo.tv.ui.components.TvRootHeroBackdrop
import org.siloserver.silo.tv.ui.components.TvSectionHeader
import org.siloserver.silo.tv.ui.components.LocalAmbientBackdropTint
import org.siloserver.silo.tv.ui.components.TvSkylineBringIntoViewSpec
import org.siloserver.silo.tv.ui.components.TvSkylineItemSpacing
import org.siloserver.silo.tv.ui.components.TvSkylineMarqueeBottomGap
import org.siloserver.silo.tv.ui.components.TvSkylineRowBandBottomInset
import org.siloserver.silo.tv.ui.components.TvSkylineRowBandMaxFraction
import org.siloserver.silo.tv.ui.components.TvSkylineRowCardVerticalPadding
import org.siloserver.silo.tv.ui.components.TvSkylineRowPreviewSpacing
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.components.tvSkylineRowBandHeight
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.viewmodel.MyRequestsViewModel
import org.siloserver.silo.viewmodel.RequestApprovalsViewModel
import org.siloserver.silo.viewmodel.RequestsViewModel

/**
 * The top-bar Requests tab in the Skyline layout Home and the library tabs
 * use: an ambient backdrop and a passive preview panel that follow the focused
 * card, including its request status and stage track, over horizontally
 * scrolling rows in the bottom band (the tvOS `TVRequestsPage`). Rows: requests
 * waiting for this admin's approval, the user's own requests, failed requests
 * (admins), then the discover carousels.
 *
 * Focus: the page claims the first card when the tab is selected, and the last
 * focused card when it returns from a detail page. Up from the first row
 * reaches the top bar through the shell. Loading, empty, and error states own
 * focus themselves, so entry always lands somewhere.
 */
@Composable
fun TvRequestsPage(
    onOpenLibraryItem: (contentId: String) -> Unit,
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit,
    onInitialContentFocus: () -> Unit,
    onFocusHandoffFailed: () -> Unit = {},
    /** Shell token bumped when the tab is selected from the bar. */
    focusRequest: Int = 0,
    hub: RequestsViewModel = koinViewModel(key = "tv-requests-hub") { parametersOf(false) },
    /** For Cancel on the user's own cards; the hub's list shows the rows. */
    mine: MyRequestsViewModel = koinViewModel(key = "tv-requests-mine") { parametersOf(false) },
    approvals: RequestApprovalsViewModel = koinViewModel(key = "tv-requests-approvals") { parametersOf(false) },
    featureStore: RequestsFeatureStore = koinInject(),
    repository: RequestsRepository = koinInject(),
) {
    val hubState by hub.uiState.collectAsState()
    val mineState by mine.uiState.collectAsState()
    val approvalsState by approvals.uiState.collectAsState()
    val canModerate by featureStore.canModerate.collectAsState()
    var hasLoaded by rememberSaveable { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<TvRequestItem?>(null) }
    val scope = rememberCoroutineScope()

    // Rows appear together once every read for the page has answered, so the
    // first row never changes under a focused card. Re-read on every return.
    suspend fun load() {
        val discover = scope.launch { hub.fetch() }
        if (featureStore.canModerate.value) approvals.fetch()
        discover.join()
        hasLoaded = true
    }
    LaunchedEffect(Unit) { load() }
    // Moderation can be confirmed after the page's first load.
    LaunchedEffect(canModerate) {
        if (canModerate && hasLoaded) approvals.fetch()
    }

    val rows = if (hasLoaded) tvRequestRows(hubState.myRequests, hubState.sections, approvalsState, canModerate) else emptyList()
    // A failed action's reason shows for a few seconds; an unconfirmed decision
    // stays until a read settles it.
    // The view models outlive this page's composition (a detail push and
    // Back re-enter it), so remember what was already shown and replay nothing.
    var transientMessage by remember { mutableStateOf<String?>(null) }
    var seenFailedActions by rememberSaveable { mutableStateOf(approvalsState.failedActions) }
    var seenCancelMessage by rememberSaveable { mutableStateOf(mineState.actionErrorMessage) }
    LaunchedEffect(approvalsState.failedActions) {
        if (approvalsState.failedActions <= seenFailedActions) return@LaunchedEffect
        seenFailedActions = approvalsState.failedActions
        transientMessage = approvalsState.lastActionError
        delay(ActionMessageMs)
        transientMessage = null
    }
    LaunchedEffect(mineState.actionErrorMessage) {
        val message = mineState.actionErrorMessage
        if (message == null || message == seenCancelMessage) return@LaunchedEffect
        seenCancelMessage = message
        transientMessage = message
        delay(ActionMessageMs)
        if (transientMessage == message) transientMessage = null
    }
    // An admin whose approval queue couldn't load sees why, instead of a page
    // that silently lacks its approval rows.
    val approvalsError = approvalsState.error?.takeIf { canModerate && hasLoaded }
        ?.let { "Couldn't load requests waiting for approval" }
    val actionMessage = approvalsState.actionErrorMessage ?: transientMessage ?: approvalsError

    val router = rememberRequestRouter(repository, onOpenLibraryItem, onOpenRequestDetail)
    fun open(item: TvRequestItem) {
        when (item) {
            is TvRequestItem.Record -> router.openRecord(item.record)
            is TvRequestItem.Approval -> router.openModerationRecord(item.record)
            is TvRequestItem.Result -> router.openResult(item.result)
        }
    }

    fun actionsFor(item: TvRequestItem): List<TvRequestAction> =
        item.actions(canCancel = mineState::canCancel, canAct = approvalsState::canAct)

    val target = actionTarget
    val targetActions = target?.let(::actionsFor).orEmpty()
    // A card's actions can lapse while its dialog is up (an action on it
    // started elsewhere); the dialog closes rather than offer nothing.
    LaunchedEffect(target, targetActions.isEmpty()) {
        if (target != null && targetActions.isEmpty()) actionTarget = null
    }
    if (target != null && targetActions.isNotEmpty()) {
        TvRequestActionDialog(
            item = target,
            actions = targetActions,
            onDismiss = { actionTarget = null },
            onAction = { action, record ->
                actionTarget = null
                when (action) {
                    // The hub's list shows the row; it settles an uncertain cancel.
                    TvRequestAction.Cancel -> mine.cancel(record, refresh = { hub.fetch() })
                    TvRequestAction.Approve -> approvals.perform(AdminRequestAction.Approve, record)
                    TvRequestAction.Decline -> approvals.perform(AdminRequestAction.Decline, record)
                    TvRequestAction.Retry -> approvals.perform(AdminRequestAction.Retry, record)
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (rows.isEmpty()) {
            TvRequestsPlaceholder(
                hasLoaded = hasLoaded,
                error = hubState.error,
                focusRequest = focusRequest,
                onRetry = {
                    hasLoaded = false
                    scope.launch { load() }
                },
                onInitialContentFocus = onInitialContentFocus,
                onFocusHandoffFailed = onFocusHandoffFailed,
            )
        } else {
            TvRequestsFeed(
                rows = rows,
                focusRequest = focusRequest,
                onOpen = ::open,
                // A card with nothing to offer ignores the long-press.
                onLongPress = { item -> if (actionsFor(item).isNotEmpty()) actionTarget = item },
                onInitialContentFocus = onInitialContentFocus,
                onFocusHandoffFailed = onFocusHandoffFailed,
            )
        }
        if (actionMessage != null) {
            Text(
                text = actionMessage,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                color = SiloOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TvSkyline.safeAreaX, bottom = 20.dp)
                    .background(DarkSurfaceElevated.copy(alpha = 0.92f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ── Rows ─────────────────────────────────────────────────────

internal data class TvRequestRow(
    val id: String,
    val title: String,
    val items: List<TvRequestItem>,
    val label: String? = null,
) {
    /** Page-unique focus key: the same title can sit in two rows. */
    fun key(item: TvRequestItem): String = "$id|${item.id}"
}

internal sealed interface TvRequestItem {
    val id: String

    /** One of the signed-in user's requests. */
    data class Record(val record: MediaRequest) : TvRequestItem {
        override val id: String get() = "request:${record.id}"
    }

    /** Someone's request, shown to an admin who can decide on it. */
    data class Approval(val record: MediaRequest) : TvRequestItem {
        override val id: String get() = "approval:${record.id}"
    }

    data class Result(val result: RequestMediaResult) : TvRequestItem {
        override val id: String get() = "tmdb:${result.mediaType}:${result.tmdbId}"
    }
}

private fun tvRequestRows(
    myRequests: List<MediaRequest>,
    sections: List<RequestDiscoverySection>,
    approvals: RequestApprovalsUiState,
    showsApprovals: Boolean,
): List<TvRequestRow> = buildList {
    if (showsApprovals && approvals.awaitingApproval.isNotEmpty()) {
        add(TvRequestRow("approvals", "Waiting for your approval", approvals.awaitingApproval.map { TvRequestItem.Approval(it) }))
    }
    val mine = myRequests.filterTvMediaRequests()
    if (mine.isNotEmpty()) add(TvRequestRow("mine", "Your requests", mine.map { TvRequestItem.Record(it) }))
    if (showsApprovals && approvals.failed.isNotEmpty()) {
        add(TvRequestRow("failed", "Failed requests", approvals.failed.map { TvRequestItem.Approval(it) }))
    }
    sections.filterTvRequestSections().forEachIndexed { index, section ->
        add(
            TvRequestRow(
                id = "discover:${section.key}",
                title = section.title,
                items = section.results.map { TvRequestItem.Result(it) },
                label = if (index == 0) "Discover" else null,
            ),
        )
    }
}

// ── Feed ─────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRequestsFeed(
    rows: List<TvRequestRow>,
    focusRequest: Int,
    onOpen: (TvRequestItem) -> Unit,
    onLongPress: (TvRequestItem) -> Unit,
    onInitialContentFocus: () -> Unit,
    onFocusHandoffFailed: () -> Unit,
) {
    val tintState = rememberAmbientBackdropTintState()
    val bandState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String) = requesters.getOrPut(key) { FocusRequester() }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    /** The card holding focus right now, for confirming an entry claim landed on its target. */
    var focusedCardKey by remember { mutableStateOf<String?>(null) }
    /** Last focused card, restored when the page returns from a detail page. */
    var lastFocusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedFocusRequest by rememberSaveable { mutableStateOf(0) }
    // Set when a card opens a page. Focus moves while this page is torn down
    // (an outer route takes the whole shell out of composition), and those
    // moves must not replace the card the viewer actually opened.
    var leaving by remember { mutableStateOf(false) }
    fun openFrom(key: String, item: TvRequestItem) {
        lastFocusedKey = key
        leaving = true
        onOpen(item)
    }
    var previewKey by remember { mutableStateOf<String?>(null) }
    val latestRows by rememberUpdatedState(rows)

    fun locate(key: String?): Pair<Int, Int>? {
        if (key == null) return null
        latestRows.forEachIndexed { rowIndex, row ->
            val itemIndex = row.items.indexOfFirst { row.key(it) == key }
            if (itemIndex >= 0) return rowIndex to itemIndex
        }
        return null
    }

    // Entry: the first card when the tab is selected from the bar, the last
    // focused card on a return from detail.
    LaunchedEffect(focusRequest) {
        val fromBar = focusRequest != appliedFocusRequest
        appliedFocusRequest = focusRequest
        val first = latestRows.first().let { it.key(it.items.first()) }
        val target = if (fromBar) first else lastFocusedKey?.takeIf { locate(it) != null } ?: first
        val (rowIndex, itemIndex) = locate(target) ?: (0 to 0)
        bandState.scrollToItem(rowIndex)
        rowStates[latestRows[rowIndex].id]?.scrollToItem(itemIndex)
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = { requesterFor(target).requestFocus() },
            // The target card itself: focus entering the page lands on some
            // other card first, and that must not count as the claim.
            isFocused = { focusedCardKey == target },
        )
        if (result == TvObservedFocusResult.Focused) onInitialContentFocus() else onFocusHandoffFailed()
    }

    // The band's vertical bring-into-view is off (TvSkylineBringIntoViewSpec),
    // so the focused row is scrolled to the top of the band explicitly, as
    // Home's feed does.
    val focusedRowIndex = locate(focusedKey)?.first
    LaunchedEffect(focusedRowIndex, rows.size) {
        if (focusedRowIndex != null && focusedRowIndex in rows.indices) bandState.animateScrollToItem(focusedRowIndex)
    }

    // The preview follows focus after a short rest, as Home's marquee does.
    LaunchedEffect(focusedKey) {
        val key = focusedKey ?: return@LaunchedEffect
        if (previewKey != null) delay(MarqueeRestMs)
        previewKey = key
    }
    val preview = remember(previewKey, rows) {
        val key = previewKey ?: rows.first().let { it.key(it.items.first()) }
        locate(key)?.let { (rowIndex, itemIndex) -> rows[rowIndex].let { row -> TvRequestPreview.of(row.items[itemIndex], row) } }
    }
    LaunchedEffect(preview?.content?.heroBackdropUrl) {
        preview?.let { tintState.set(it.content.source, it.content.heroBackdropUrl) }
    }

    CompositionLocalProvider(
        LocalAmbientBackdropTint provides tintState,
        LocalBringIntoViewSpec provides TvSkylineBringIntoViewSpec,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val presentation = LocalCardPresentation.current
            val bandHeight = tvSkylineRowBandHeight(presentation).coerceAtMost(maxHeight * TvSkylineRowBandMaxFraction)
            val trailingPadding = (bandHeight - TvSkylineRowBandBottomInset).coerceAtLeast(0.dp)
            val previews = remember(rows) { mutableMapOf<String, TvRequestPreview>() }

            TvRootHeroBackdrop(content = preview?.content, modifier = Modifier.fillMaxSize())
            TvFocusMarquee(
                content = preview?.content,
                startPadding = TvSkyline.safeAreaX,
                topPadding = TvSkyline.barTopInset + TvSkyline.barHeight,
                bottomPadding = bandHeight + TvSkylineMarqueeBottomGap,
                modifier = Modifier.fillMaxSize(),
                footer = { content ->
                    val shown = if (content.id == preview?.content?.id) preview else previews[content.id]
                    shown?.let { TvRequestPreviewStatus(it) }
                },
            )
            preview?.let { previews[it.content.id] = it }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bandHeight)
                    .align(Alignment.BottomStart)
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = bandState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(TvSkylineRowPreviewSpacing),
                    contentPadding = PaddingValues(bottom = trailingPadding),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { _, row ->
                        val rowState = rowStates.getOrPut(row.id) { LazyListState() }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvSectionHeader(
                                title = row.title,
                                eyebrow = row.label,
                                modifier = Modifier.padding(start = TvSkyline.safeAreaX, end = Spacing.safeArea),
                            )
                            LazyRow(
                                state = rowState,
                                modifier = Modifier.focusRestorer(),
                                horizontalArrangement = Arrangement.spacedBy(TvSkylineItemSpacing),
                                contentPadding = PaddingValues(
                                    start = TvSkyline.safeAreaX,
                                    end = Spacing.safeArea,
                                    top = TvSkylineRowCardVerticalPadding,
                                    bottom = TvSkylineRowCardVerticalPadding,
                                ),
                            ) {
                                itemsIndexed(row.items, key = { _, item -> item.id }) { _, item ->
                                    val key = row.key(item)
                                    val cardModifier = Modifier.onFocusChanged {
                                        if (it.hasFocus) {
                                            focusedCardKey = key
                                        } else if (focusedCardKey == key) {
                                            focusedCardKey = null
                                        }
                                        if (it.hasFocus && !leaving) {
                                            focusedKey = key
                                            lastFocusedKey = key
                                        }
                                    }
                                    when (item) {
                                        is TvRequestItem.Record -> TvRequestRecordCard(
                                            record = item.record,
                                            onClick = { openFrom(key, item) },
                                            onLongClick = { onLongPress(item) },
                                            focusRequester = requesterFor(key),
                                            modifier = cardModifier,
                                        )
                                        is TvRequestItem.Approval -> TvRequestRecordCard(
                                            record = item.record,
                                            onClick = { openFrom(key, item) },
                                            onLongClick = { onLongPress(item) },
                                            focusRequester = requesterFor(key),
                                            modifier = cardModifier,
                                        )
                                        is TvRequestItem.Result -> TvRequestCard(
                                            result = item.result,
                                            onClick = { openFrom(key, item) },
                                            focusRequester = requesterFor(key),
                                            modifier = cardModifier,
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
}

private const val MarqueeRestMs = 150L
private const val ActionMessageMs = 4_000L

/** What the preview panel shows for the focused card. */
internal data class TvRequestPreview(
    val content: TvMarqueeContent,
    val progress: RequestProgress?,
    val statusText: String?,
) {
    companion object {
        fun of(item: TvRequestItem, row: TvRequestRow): TvRequestPreview {
            val meta = mutableListOf<TvHeroFactToken>()
            val title: String
            val overview: String?
            val posterPath: String?
            val backdropPath: String?
            val mediaType: String
            val progress: RequestProgress?
            val statusText: String?
            val year: Int?
            when (item) {
                is TvRequestItem.Record, is TvRequestItem.Approval -> {
                    val record = (item as? TvRequestItem.Record)?.record ?: (item as TvRequestItem.Approval).record
                    title = record.title
                    overview = record.overview
                    posterPath = record.posterPath
                    backdropPath = record.backdropPath
                    mediaType = record.mediaType
                    year = record.year
                    progress = RequestProgress.of(record)
                    statusText = RequestRowCopy.status(record, progress)
                }
                is TvRequestItem.Result -> {
                    val result = item.result
                    title = result.title
                    overview = result.overview
                    posterPath = result.posterPath
                    backdropPath = result.backdropPath
                    mediaType = result.mediaType
                    year = result.year
                    // Like the detail page: a title that can't be requested has no track.
                    progress = RequestProgress.of(result.availability, result.request)
                        ?.takeUnless { it.display is RequestDisplayState.Unavailable }
                    statusText = progress?.longLabel
                }
            }
            year?.takeIf { it > 0 }?.let { meta += TvHeroFactToken.TextToken(it.toString()) }
            meta += TvHeroFactToken.TextToken(RequestRowCopy.mediaTypeLabel(mediaType))
            when (item) {
                is TvRequestItem.Record -> RequestRowCopy.day(item.record.createdAt)?.let { meta += TvHeroFactToken.TextToken("Requested $it") }
                is TvRequestItem.Approval -> RequestRowCopy.day(item.record.createdAt)?.let { meta += TvHeroFactToken.TextToken("Requested $it") }
                is TvRequestItem.Result -> ExternalRatings.tmdb(item.result.voteAverage)?.let { meta += TvHeroFactToken.ExternalRating(it) }
            }
            val posterUrl = requestPosterUrl(posterPath)
            val backdropUrl = requestBackdropUrl(backdropPath)
            val content = TvMarqueeContent(
                id = row.key(item),
                title = title,
                logoUrl = null,
                badges = emptyList(),
                metaParts = meta,
                synopsis = overview?.takeIf { it.isNotBlank() },
                detailLine = null,
                specLine = null,
                backdropUrl = backdropUrl,
                backdropThumbhash = null,
                posterUrl = posterUrl,
                posterThumbhash = null,
                isEpisode = false,
                // The tint samples the same artwork the backdrop shows.
                source = SectionItem(
                    contentId = row.key(item),
                    type = mediaType,
                    title = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                ),
            )
            return TvRequestPreview(content, progress, statusText)
        }
    }
}

/** The status sentence and stage track under the preview's synopsis. */
@Composable
private fun TvRequestPreviewStatus(preview: TvRequestPreview) {
    val progress = preview.progress ?: return
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TvRequestStatusLabel(
            progress = progress,
            text = preview.statusText ?: progress.longLabel,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SiloOnSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        RequestStageTrack(progress = progress, height = 3.dp, modifier = Modifier.width(120.dp))
    }
}

// ── Placeholders ─────────────────────────────────────────────

/**
 * The page before it has rows: a loading skeleton in the feed's geometry, the
 * empty state, or the error. Each owns focus, so entry from the bar always
 * lands in the page.
 */
@Composable
private fun TvRequestsPlaceholder(
    hasLoaded: Boolean,
    error: String?,
    focusRequest: Int,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
    onFocusHandoffFailed: () -> Unit,
) {
    val owner = remember { FocusRequester() }
    var ownerHasFocus by remember { mutableStateOf(false) }
    val state = when {
        !hasLoaded -> "loading"
        error != null -> "error"
        else -> "empty"
    }
    LaunchedEffect(focusRequest, state) {
        // The error view owns its Retry button; hand the shell back its bar
        // rather than holding the handoff open.
        if (state == "error") {
            onFocusHandoffFailed()
            return@LaunchedEffect
        }
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = owner::requestFocus,
            isFocused = { ownerHasFocus },
        )
        if (result == TvObservedFocusResult.Focused) onInitialContentFocus() else onFocusHandoffFailed()
    }
    when (state) {
        "error" -> TvErrorScreen(message = error.orEmpty(), onRetry = onRetry)
        else -> {
            val label = if (state == "loading") "Loading requests" else "Nothing here yet"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(owner)
                    .onFocusChanged { ownerHasFocus = it.isFocused }
                    .semantics { contentDescription = label }
                    .focusable(),
            ) {
                if (state == "loading") TvRequestsLoadingView() else TvRequestsEmptyState()
            }
        }
    }
}

@Composable
private fun TvRequestsEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = Icons.Outlined.Inbox, contentDescription = null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(36.dp))
        Text(text = "Nothing here yet", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            text = "Use Search to find a movie or series to request",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f),
        )
    }
}

/**
 * First frame while rows load: the same preview-and-row geometry as the feed,
 * so the real page replaces it in place instead of building itself from a
 * black canvas.
 */
@Composable
private fun TvRequestsLoadingView() {
    val presentation = LocalCardPresentation.current
    val cardWidth = tvRequestCardWidth()
    val bar = Color.White.copy(alpha = 0.10f)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bandHeight = tvSkylineRowBandHeight(presentation).coerceAtMost(maxHeight * TvSkylineRowBandMaxFraction)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = TvSkyline.safeAreaX, bottom = bandHeight + TvSkylineMarqueeBottomGap),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.size(260.dp, 36.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(59.dp, 41.dp, 75.dp).forEach { Box(modifier = Modifier.size(it, 11.dp).background(bar, RoundedCornerShape(3.dp))) }
            }
            Box(modifier = Modifier.size(360.dp, 9.dp).background(bar, RoundedCornerShape(3.dp)))
            Box(modifier = Modifier.size(305.dp, 9.dp).background(bar, RoundedCornerShape(3.dp)))
            Text(
                text = "Loading Requests",
                style = MaterialTheme.typography.bodyMedium,
                color = SiloOnSurface.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(bandHeight)
                .padding(start = TvSkyline.safeAreaX),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(140.dp, 18.dp).background(bar, RoundedCornerShape(3.dp)))
            Row(
                modifier = Modifier.padding(top = TvSkylineRowCardVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(TvSkylineItemSpacing),
            ) {
                repeat(8) {
                    Box(modifier = Modifier.size(cardWidth, cardWidth * 1.5f).background(bar, RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

// ── Long-press actions ───────────────────────────────────────

internal enum class TvRequestAction(val label: String) {
    Cancel("Cancel Request"),
    Approve("Approve"),
    Decline("Decline"),
    Retry("Retry"),
}

/** The long-press actions a card offers right now; empty when it has none. */
internal fun TvRequestItem.actions(
    canCancel: (MediaRequest) -> Boolean,
    canAct: (MediaRequest) -> Boolean,
): List<TvRequestAction> = when (this) {
    is TvRequestItem.Record -> if (canCancel(record)) listOf(TvRequestAction.Cancel) else emptyList()
    is TvRequestItem.Approval -> if (!canAct(record)) {
        emptyList()
    } else {
        when (val display = RequestDisplayState.of(record)) {
            RequestDisplayState.Pending -> listOf(TvRequestAction.Approve, TvRequestAction.Decline)
            is RequestDisplayState.NeedsAttention ->
                if (display.attention == RequestAttention.Failed) listOf(TvRequestAction.Retry) else emptyList()
            else -> emptyList()
        }
    }
    is TvRequestItem.Result -> emptyList()
}

/** [actions] is non-empty and [item] is a request card; the caller decides both. */
@Composable
private fun TvRequestActionDialog(
    item: TvRequestItem,
    actions: List<TvRequestAction>,
    onDismiss: () -> Unit,
    onAction: (TvRequestAction, MediaRequest) -> Unit,
) {
    val record = when (item) {
        is TvRequestItem.Record -> item.record
        is TvRequestItem.Approval -> item.record
        is TvRequestItem.Result -> return
    }
    var confirmingDecline by remember(item) { mutableStateOf(false) }
    if (confirmingDecline) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DarkSurfaceElevated,
            titleContentColor = SiloOnSurface,
            textContentColor = SiloOnSurface.copy(alpha = 0.76f),
            title = { Text("Decline this request?", color = SiloOnSurface) },
            text = {
                Text(
                    "${record.title} will show as declined to the person who asked for it.",
                    color = SiloOnSurface.copy(alpha = 0.76f),
                )
            },
            confirmButton = {
                // Keep first, so a stray press doesn't decide on someone's request.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss) { Text("Keep") }
                    Button(onClick = { onAction(TvRequestAction.Decline, record) }) { Text("Decline") }
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceElevated,
        titleContentColor = SiloOnSurface,
        textContentColor = SiloOnSurface.copy(alpha = 0.76f),
        title = { Text(record.title, color = SiloOnSurface) },
        text = {
            Text(
                if (item is TvRequestItem.Approval) "Requested by someone on this server." else RequestProgress.of(record).longLabel,
                color = SiloOnSurface.copy(alpha = 0.76f),
            )
        },
        confirmButton = {
            // Close first, so a stray press doesn't send a non-retryable action.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss) { Text("Close") }
                actions.forEach { action ->
                    Button(
                        onClick = {
                            // Decline asks first.
                            if (action == TvRequestAction.Decline) confirmingDecline = true else onAction(action, record)
                        },
                    ) { Text(action.label) }
                }
            }
        },
    )
}
