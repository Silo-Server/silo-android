package org.siloserver.silo.tv.ui.screens.requests

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.requests.RequestStageTrack
import org.siloserver.silo.model.catalog.ExternalRatings
import org.siloserver.silo.model.request.AdminRequestAction
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
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.viewmodel.RequestDetailUiState
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.siloserver.silo.viewmodel.RequestPrimaryAction

/**
 * TV request detail on the same page surface and hero as a library title
 * (`TvDetailHero`), like tvOS `RequestDetailView`: a STATUS / REQUESTED /
 * QUALITY summary with a labeled stage track, one primary action that morphs
 * in place, admin decisions, Cancel, and a "More like this" row.
 *
 * Focus: the primary action is one stable node whose label and look follow
 * [RequestPrimaryAction], so focus stays put across request → requesting →
 * pending.
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
    var confirmingDecline by remember { mutableStateOf(false) }
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

    BackHandler(enabled = backEnabled) { onBack() }

    if (confirmingDecline) {
        AlertDialog(
            onDismissRequest = { confirmingDecline = false },
            containerColor = DarkSurfaceElevated,
            titleContentColor = SiloOnSurface,
            textContentColor = SiloOnSurface.copy(alpha = 0.76f),
            title = { Text("Decline this request?", color = SiloOnSurface) },
            text = { Text("The person who asked for it will see it as declined.", color = SiloOnSurface.copy(alpha = 0.76f)) },
            confirmButton = {
                // Keep first, so a stray press doesn't decide on someone's request.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { confirmingDecline = false }) { Text("Keep") }
                    Button(onClick = {
                        confirmingDecline = false
                        viewModel.moderate(AdminRequestAction.Decline)
                    }) { Text("Decline") }
                }
            },
        )
    }

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
                onModerate = { action ->
                    if (action == AdminRequestAction.Decline) confirmingDecline = true else viewModel.moderate(action)
                },
                onCancel = viewModel::cancel,
                onOpenRecommendation = router::openResult,
            )
            state.error != null -> TvErrorScreen(message = state.error.orEmpty(), onRetry = viewModel::load)
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
    onModerate: (AdminRequestAction) -> Unit,
    onCancel: () -> Unit,
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
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RequestPrimaryActionPill(
                            state = state,
                            focusRequester = primaryActionFocusRequester,
                            onClick = onPrimary,
                            modifier = Modifier.onFocusChanged { onPrimaryFocusChanged(it.hasFocus) },
                        )
                        state.moderationActions.forEach { action ->
                            val (icon, title) = when (action) {
                                AdminRequestAction.Approve -> Icons.Filled.Check to "Approve"
                                AdminRequestAction.Decline -> Icons.Filled.Close to "Decline"
                                AdminRequestAction.Retry -> Icons.Filled.Refresh to "Retry"
                            }
                            RequestActionPill(icon = icon, title = title, onClick = { onModerate(action) })
                        }
                        if (state.canCancel) {
                            RequestActionPill(icon = Icons.Filled.Close, title = "Cancel Request", onClick = onCancel)
                        }
                        state.actionErrorMessage?.let { message ->
                            Text(
                                text = message,
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
                    TvDetailSectionHeader(title = "More like this", modifier = Modifier.padding(horizontal = TvDetailHorizontalInset))
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
                text = state.primaryActionTitle,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 14.sp),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

/**
 * A secondary action in the hero row (Approve, Decline, Retry, Cancel), in the
 * row's 38dp capsule so every control in it shares one height.
 */
@Composable
private fun RequestActionPill(icon: ImageVector, title: String, onClick: () -> Unit) {
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
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 14.sp),
                color = foreground,
                maxLines = 1,
            )
        }
    }
}

/** Extra hero height for the status strip and labeled track. */
private val StatusStripHeight = 64.dp

/**
 * The labeled summary under the synopsis, in the playback summary's grammar,
 * plus the stage track with its step names.
 */
@Composable
private fun RequestStatusStrip(progress: RequestProgress, state: RequestDetailUiState) {
    val record = state.displayedRecord
    Column(modifier = Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SummaryField(label = "STATUS", value = progress.longLabel, dot = progress.tint.tvColor())
            if (record != null) {
                RequestRowCopy.day(record.createdAt)?.let { SummaryField(label = "REQUESTED", value = it) }
                (RequestTargetSummary.text(record.targets) ?: RequestTargetSummary.qualities(record.targets))?.let {
                    SummaryField(label = "QUALITY", value = it)
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
                        text = step.title,
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
private fun RequestMediaDetail.factTokens(): List<TvHeroFactToken> = buildList {
    year?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken(it.toString())) }
    if (mediaType == RequestMediaType.Series) {
        numberOfSeasons?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken(if (it == 1) "1 season" else "$it seasons")) }
    } else {
        runtime?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m")) }
    }
    ExternalRatings.tmdb(voteAverage)?.let { add(TvHeroFactToken.ExternalRating(it)) }
}

private fun RequestMediaDetail.creditText(): String? = when {
    director.isNotBlank() -> "Directed by $director"
    creators.isNotEmpty() -> "Created by ${creators.take(2).joinToString(", ")}"
    else -> null
}

