package org.siloserver.silo.tv.ui.screens.requests

import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.model.request.RequestMediaResult
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.requests.RequestStageTrack
import org.siloserver.silo.model.catalog.ExternalRatings
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStep
import org.siloserver.silo.model.request.RequestTargetSummary
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.tv.ui.components.PillKind
import org.siloserver.silo.tv.ui.components.SquaredPillSurface
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvHeroFactToken
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.screens.detail.TvDetailHero
import org.siloserver.silo.tv.ui.screens.detail.TvDetailHorizontalInset
import org.siloserver.silo.tv.ui.screens.detail.TvDetailSectionHeader
import org.siloserver.silo.tv.ui.screens.detail.tvDetailPageSurfaceColor
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.viewmodel.RequestDetailUiState
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.siloserver.silo.viewmodel.RequestPrimaryAction

/**
 * TV request detail on the same page surface and hero as a library title
 * (`TvDetailHero`), like tvOS `RequestDetailView`: a STATUS / REQUESTED /
 * QUALITY summary with a labeled stage track, one primary action that morphs
 * in place, Cancel, and a "More like this" row.
 *
 * Focus: the primary action is one stable node whose label and look follow
 * [RequestPrimaryAction], so focus stays put across request → requesting →
 * pending. Notify me is likewise one node whose label flips. A secondary pill
 * that leaves the row while focused hands focus to the primary action
 * ([RequestActionFocusFallback]).
 */
@Composable
fun TvRequestDetailScreen(
    mediaType: String,
    tmdbId: Int,
    onBack: () -> Unit,
    onOpenLibraryItem: (contentId: String) -> Unit = {},
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit = { _, _ -> },
    /**
     * False while the shell has an overlay (a cascade panel or the profile
     * menu) that Back should close first. This handler registers after the
     * shell's, so on Android 16 it would otherwise take that press and pop
     * the page from under the open overlay.
     */
    backEnabled: Boolean = true,
    onInitialContentFocus: () -> Unit = {},
    viewModel: RequestDetailViewModel = koinViewModel { parametersOf(mediaType, tmdbId) },
    repository: RequestsRepository = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val router = rememberRequestRouter(repository, onOpenLibraryItem, onOpenRequestDetail)
    val loadingFocusRequester = remember { FocusRequester() }
    val primaryActionFocusRequester = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    var pageHadFocus by remember { mutableStateOf(false) }
    var primaryHasFocus by remember { mutableStateOf(false) }
    val showsLoading = state.detail == null && state.error == null
    // The title, not the loaded detail: refreshes after an action replace the
    // detail and must not re-run the claim below.
    val detailKey = state.detail?.let { "${it.mediaType}:${it.tmdbId}" }
    val detailArrived by rememberUpdatedState(detailKey != null)

    // Nothing here is focusable until the detail loads, and the row that opened
    // the page is about to be disposed. Hold focus on the loading indicator so
    // it stays in the page.
    LaunchedEffect(showsLoading) {
        if (!showsLoading) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = loadingFocusRequester::requestFocus,
            isFocused = { pageHasFocus || detailArrived },
        )
    }

    // Hand focus to the primary action when the detail arrives, but only if
    // focus is still in the page (or never got here). Observed on the action
    // itself: the synopsis above it is focusable too, and focus entering the
    // page first lands there.
    val claimOnArrival = remember(detailKey) { pageHasFocus || !pageHadFocus }
    LaunchedEffect(detailKey) {
        if (detailKey == null || !claimOnArrival) return@LaunchedEffect
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = primaryActionFocusRequester::requestFocus,
            isFocused = { primaryHasFocus },
        )
        if (result == TvObservedFocusResult.Focused) onInitialContentFocus()
    }

    // A focused pill that leaves the row (Cancel once cancelled, Notify me once
    // the title is requestable or in the library) hands its focus to the
    // synopsis; put it back on the primary action, which never leaves.
    val actionFocusFallback = remember { RequestActionFocusFallback() }
    val actionFocusChecks = actionFocusFallback.checks
    LaunchedEffect(actionFocusChecks) {
        if (actionFocusChecks == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!actionFocusFallback.takeShouldReclaim()) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = primaryActionFocusRequester::requestFocus,
            isFocused = { primaryHasFocus },
        )
    }

    BackHandler(enabled = backEnabled) { onBack() }

    val backdropUrl = state.detail?.let { requestBackdropUrl(it.backdropPath) ?: requestPosterUrl(it.posterPath) }
    val pageTint = rememberAmbientBackdropTintState()
    LaunchedEffect(backdropUrl) { pageTint.set(item = null, url = backdropUrl) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                pageHasFocus = it.hasFocus
                if (it.hasFocus) pageHadFocus = true
            }
            .background(tvDetailPageSurfaceColor(pageTint.accent)),
    ) {
        val detail = state.detail
        when {
            detail != null -> RequestDetailContent(
                detail = detail,
                state = state,
                backdropUrl = backdropUrl,
                primaryActionFocusRequester = primaryActionFocusRequester,
                onPrimaryFocusChanged = { primaryHasFocus = it },
                onPrimary = {
                    when (val action = state.primaryAction) {
                        RequestPrimaryAction.Request -> viewModel.submitRequest()
                        is RequestPrimaryAction.OpenInLibrary -> onOpenLibraryItem(action.contentId)
                        else -> Unit
                    }
                },
                onCancel = viewModel::cancel,
                onToggleFollow = viewModel::toggleFollow,
                actionFocusFallback = actionFocusFallback,
                onOpenRecommendation = router::openResult,
            )
            state.error != null -> TvErrorScreen(message = state.error?.text().orEmpty(), onRetry = viewModel::load)
            else -> RequestDetailLoading(focusRequester = loadingFocusRequester)
        }
    }
}

/**
 * The loading indicator plus a spinner-sized focus target over it, so focus
 * can wait inside the page.
 */
@Composable
private fun RequestDetailLoading(focusRequester: FocusRequester) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TvLoadingScreen()
        Box(
            modifier = Modifier
                .size(64.dp)
                .focusRequester(focusRequester)
                .focusable(),
        )
    }
}

@Composable
private fun RequestDetailContent(
    detail: RequestMediaDetail,
    state: RequestDetailUiState,
    backdropUrl: String?,
    primaryActionFocusRequester: FocusRequester,
    onPrimaryFocusChanged: (Boolean) -> Unit,
    onPrimary: () -> Unit,
    onCancel: () -> Unit,
    onToggleFollow: () -> Unit,
    actionFocusFallback: RequestActionFocusFallback,
    onOpenRecommendation: (RequestMediaResult) -> Unit,
) {
    val progress = state.progress?.takeIf { state.showsStatus }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 70.dp),
    ) {
        item(key = "hero", contentType = "detail-hero") {
            TvDetailHero(
                title = detail.title,
                seriesTitle = null,
                logoUrl = null,
                backdropUrl = backdropUrl,
                backdropThumbhash = null,
                sourceTokens = detail.genres.take(3),
                ratingChip = detail.contentRating.takeIf { it.isNotBlank() },
                overview = detail.overview.takeIf { it.isNotBlank() },
                tagline = detail.tagline.takeIf { it.isNotBlank() },
                factsLine = detail.factTokens(),
                directorText = detail.creditText(),
                extraHeight = if (progress != null) StatusStripHeight else 0.dp,
                playbackSummary = progress?.let { { RequestStatusStrip(progress = it, state = state) } },
                actions = {
                    Row(
                        modifier = Modifier.onFocusChanged { actionFocusFallback.onRowFocusChanged(it.hasFocus) },
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RequestPrimaryActionPill(
                            state = state,
                            focusRequester = primaryActionFocusRequester,
                            onClick = onPrimary,
                            modifier = Modifier.onFocusChanged { onPrimaryFocusChanged(it.hasFocus) },
                        )
                        if (state.canCancel) {
                            RequestActionPill(icon = Icons.Filled.Close, title = stringResource(CommonR.string.requests_action_cancel), onClick = onCancel, focusFallback = actionFocusFallback)
                        }
                        if (state.canFollow) {
                            // One node across toggles: only the label and icon change, so focus stays.
                            key("follow") {
                                RequestActionPill(
                                    icon = if (state.isFollowing) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                                    title = state.followActionTitle.text(),
                                    pending = state.isFollowPending,
                                    onClick = onToggleFollow,
                                    focusFallback = actionFocusFallback,
                                )
                            }
                        }
                        (state.actionErrorMessage ?: state.shownFollowNotice)?.let { message ->
                            Text(
                                text = message.text.text(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                color = SiloOnSurface.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        }
        val recommendations = state.recommendations
        if (recommendations.isNotEmpty()) {
            item(key = "more-like-this", contentType = "rail") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvDetailSectionHeader(title = stringResource(CommonR.string.requests_moreLikeThis), modifier = Modifier.padding(horizontal = TvDetailHorizontalInset))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(horizontal = TvDetailHorizontalInset, vertical = 8.dp),
                    ) {
                        items(recommendations, key = { "${it.mediaType}:${it.tmdbId}" }) { result ->
                            TvRequestCard(result = result, onClick = { onOpenRecommendation(result) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The page's one primary action: a white Request / Open in Library pill, or
 * the request's status with its dot. Never disabled and never swapped for
 * another node, so a submit can't drop focus mid-morph; presses on a status
 * do nothing.
 */
@Composable
private fun RequestPrimaryActionPill(
    state: RequestDetailUiState,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = state.primaryAction
    SquaredPillSurface(
        kind = PillKind.Primary,
        onClick = { if (action.isInteractive) onClick() },
        modifier = modifier.height(38.dp),
        focusRequester = focusRequester,
        capsule = true,
        stableHero = true,
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { foreground ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            when (action) {
                RequestPrimaryAction.Request -> Icon(Icons.Filled.Add, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                RequestPrimaryAction.Submitting -> CircularProgressIndicator(color = foreground, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                is RequestPrimaryAction.OpenInLibrary ->
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
                is RequestPrimaryAction.Status ->
                    Box(modifier = Modifier.size(9.dp).background(action.state.tint.tvColor(), CircleShape))
                RequestPrimaryAction.Loading -> Unit
            }
            Text(
                text = state.primaryActionTitle.text(),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 14.sp),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

/**
 * A secondary action in the hero row (Cancel, Notify me), in the row's 38dp
 * capsule so every control in it shares one height.
 */
@Composable
private fun RequestActionPill(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    pending: Boolean = false,
    /** Told when this pill leaves the row, so focus it held can return to the primary action. */
    focusFallback: RequestActionFocusFallback? = null,
) {
    DisposableEffect(focusFallback) {
        onDispose { focusFallback?.onPillRemoved() }
    }
    SquaredPillSurface(
        kind = PillKind.Secondary,
        onClick = onClick,
        modifier = Modifier.height(38.dp),
        focusRequester = null,
        capsule = true,
        stableHero = true,
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { foreground ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending) {
                CircularProgressIndicator(color = foreground, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            } else {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 14.sp),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

/**
 * Notices a focused secondary pill leaving the action row. Compose hands the
 * focus of a removed node to the nearest focusable, which here is the synopsis
 * above the row, so Select would open the overview instead of acting. A TV has
 * no touch fallback, so focus must return to the primary action.
 *
 * Whether the pill held focus is judged from the row: either the row still has
 * focus when the pill is disposed, or it loses focus in the same frame (the
 * order of the two callbacks is not guaranteed). Each signal bumps [checks];
 * the screen waits a frame, then reclaims for the primary action. A pill that
 * leaves while focus is outside the row (the top bar, a rail, another page)
 * changes nothing; one that leaves while the primary action is focused makes
 * the reclaim a no-op.
 */
@Stable
internal class RequestActionFocusFallback {
    private var rowHasFocus = false
    private var rowLostFocus = false
    private var pillRemoved = false
    private var rowFocusedAtRemoval = false

    var checks by mutableIntStateOf(0)
        private set

    fun onRowFocusChanged(hasFocus: Boolean) {
        if (rowHasFocus && !hasFocus) {
            rowLostFocus = true
            checks++
        }
        rowHasFocus = hasFocus
    }

    fun onPillRemoved() {
        pillRemoved = true
        if (rowHasFocus) rowFocusedAtRemoval = true
        checks++
    }

    /** Whether to reclaim focus now; clears the signals either way. */
    fun takeShouldReclaim(): Boolean {
        val reclaim = shouldReclaimRequestActionFocus(
            pillRemoved = pillRemoved,
            rowFocusedAtRemoval = rowFocusedAtRemoval,
            rowLostFocus = rowLostFocus,
        )
        rowLostFocus = false
        pillRemoved = false
        rowFocusedAtRemoval = false
        return reclaim
    }
}

internal fun shouldReclaimRequestActionFocus(pillRemoved: Boolean, rowFocusedAtRemoval: Boolean, rowLostFocus: Boolean): Boolean =
    pillRemoved && (rowFocusedAtRemoval || rowLostFocus)

/** Extra hero height for the status strip and labeled track. */
private val StatusStripHeight = 64.dp

/**
 * The labeled summary under the synopsis, in the playback summary's grammar,
 * plus the stage track with its step names.
 */
@Composable
private fun RequestStatusStrip(progress: RequestProgress, state: RequestDetailUiState) {
    val record = state.record
    Column(modifier = Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryField(label = stringResource(CommonR.string.requests_strip_status), value = progress.longText.text(), dot = progress.tint.tvColor())
            if (record != null) {
                RequestRowCopy.day(record.createdAt)?.let { SummaryField(label = stringResource(CommonR.string.requests_strip_requested), value = it) }
                (RequestTargetSummary.text(record.targets)?.text() ?: RequestTargetSummary.qualities(record.targets))?.let {
                    SummaryField(label = stringResource(CommonR.string.requests_strip_quality), value = it)
                }
            }
        }
        Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            RequestStageTrack(progress = progress, height = 3.dp)
            Row {
                RequestStep.entries.forEach { step ->
                    val color = when {
                        step == progress.currentStep -> progress.tint.tvColor()
                        step.ordinal < progress.completedSteps -> SiloOnSurface
                        else -> SiloOnSurface.copy(alpha = 0.45f)
                    }
                    Text(
                        text = step.text.text(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = color,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryField(label: String, value: String, dot: Color? = null) {
    Column(modifier = Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp),
            color = Color.White.copy(alpha = 0.48f),
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dot != null) Box(modifier = Modifier.size(6.dp).background(dot, CircleShape))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `2026 · 2h 25m · TMDB 7.9`, with genres after it as source tokens. */
@Composable
private fun RequestMediaDetail.factTokens(): List<TvHeroFactToken> = buildList {
    year?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken(it.toString())) }
    if (mediaType == RequestMediaType.Series) {
        numberOfSeasons?.takeIf { it > 0 }?.let {
            add(TvHeroFactToken.TextToken(pluralStringResource(CommonR.plurals.requests_detail_seasons, it, it)))
        }
    } else {
        runtime?.takeIf { it > 0 }?.let {
            val text = if (it >= 60) {
                stringResource(CommonR.string.catalog_runtimeHoursMinutes, LocaleFormat.integer(it / 60), LocaleFormat.integer(it % 60))
            } else {
                stringResource(CommonR.string.overlays_runtimeMinutes, LocaleFormat.integer(it))
            }
            add(TvHeroFactToken.TextToken(text))
        }
    }
    ExternalRatings.tmdb(voteAverage)?.let { add(TvHeroFactToken.ExternalRating(it)) }
}

@Composable
private fun RequestMediaDetail.creditText(): String? = when {
    director.isNotBlank() -> stringResourceWithNames(CommonR.string.detail_directedBy, director)
    creators.isNotEmpty() -> stringResourceWithNames(CommonR.string.requests_createdBy, creators.take(2).joinToString(", "))
    else -> null
}

