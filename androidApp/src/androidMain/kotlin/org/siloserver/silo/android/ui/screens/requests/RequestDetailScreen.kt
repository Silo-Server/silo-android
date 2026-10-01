package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.screens.detail.AdaptiveDetailHero
import org.siloserver.silo.android.ui.screens.detail.DetailLoadingSkeleton
import org.siloserver.silo.android.ui.screens.detail.DetailPageSurface
import org.siloserver.silo.android.ui.screens.detail.DetailScrollState
import org.siloserver.silo.android.ui.screens.detail.DetailTopChrome
import org.siloserver.silo.android.ui.screens.detail.HeaderSettledDp
import org.siloserver.silo.android.ui.screens.detail.HeroMetadata
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.ExternalRatings
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStep
import org.siloserver.silo.model.request.RequestTargetSummary
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.model.request.requestReasonCopy
import org.siloserver.silo.viewmodel.RequestDetailUiState
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.siloserver.silo.viewmodel.RequestPrimaryAction

/**
 * Detail for a requestable TMDB title, built on the same page surface, hero,
 * and floating chrome as a library title. One server-computed primary action,
 * a status card with the stage steps once a request exists, and a "More like
 * this" rail. Requesting has no confirmation dialog: the button is the
 * confirmation and turns into the status in place.
 */
@Composable
fun RequestDetailScreen(
    mediaType: String,
    tmdbId: Int,
    onBackClick: () -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: RequestDetailViewModel = koinViewModel(
        parameters = { parametersOf(mediaType to tmdbId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val router = rememberRequestRouter(onLibraryItemClick, onRequestDetailClick)
    val scroll = remember { DetailScrollState() }
    var confirmingDecline by remember { mutableStateOf(false) }
    val view = LocalView.current

    // Counters the view model keeps; remembered so a return or rotation
    // doesn't replay the last action's haptic.
    val accepted = state.submittedCount + state.moderatedCount
    var seenAccepted by rememberSaveable { mutableStateOf(accepted) }
    LaunchedEffect(accepted) {
        if (accepted > seenAccepted) view.performHapticFeedback(confirmHaptic())
        seenAccepted = accepted
    }

    if (confirmingDecline) {
        AlertDialog(
            onDismissRequest = { confirmingDecline = false },
            title = { Text("Decline this request?") },
            text = { Text("The person who asked for it will see it as declined.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDecline = false
                    viewModel.moderate(AdminRequestAction.Decline)
                }) { Text("Decline", color = RequestColors.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmingDecline = false }) { Text("Keep") } },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(SiloBackground)) {
        val branch = when {
            state.detail != null -> "detail"
            state.error != null -> "error"
            else -> "loading"
        }
        Crossfade(targetState = branch, animationSpec = tween(180), label = "requestDetailBranch") { current ->
            when (current) {
                "detail" -> state.detail?.let { detail ->
                    RequestDetailContent(
                        detail = detail,
                        state = state,
                        scroll = scroll,
                        onPrimary = {
                            when (val action = state.primaryAction) {
                                RequestPrimaryAction.Request -> viewModel.submitRequest()
                                is RequestPrimaryAction.OpenInLibrary -> onLibraryItemClick(action.contentId)
                                else -> Unit
                            }
                        },
                        onModerate = { action ->
                            if (action == AdminRequestAction.Decline) confirmingDecline = true else viewModel.moderate(action)
                        },
                        onCancel = viewModel::cancel,
                        router = router,
                    )
                }
                "error" -> ErrorView(message = state.error.orEmpty(), onRetry = viewModel::load)
                else -> DetailLoadingSkeleton()
            }
        }
        DetailTopChrome(title = state.detail?.title.orEmpty(), scroll = scroll, onBackClick = onBackClick)
    }
}

@Composable
private fun RequestDetailContent(
    detail: RequestMediaDetail,
    state: RequestDetailUiState,
    scroll: DetailScrollState,
    onPrimary: () -> Unit,
    onModerate: (AdminRequestAction) -> Unit,
    onCancel: () -> Unit,
    router: RequestRouter,
) {
    val itemDetail = remember(detail) { detail.toHeroDetail() }
    val dominantColor by rememberDominantColor(imageUrl = itemDetail.backdropUrl, fallback = SiloBackground)
    val feedState = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(feedState, scroll, density) {
        snapshotFlow {
            if (feedState.firstVisibleItemIndex > 0) {
                HeaderSettledDp
            } else {
                with(density) { feedState.firstVisibleItemScrollOffset.toDp().value }
            }
        }.collect { scroll.update(it) }
    }
    DetailPageSurface(backdropUrl = itemDetail.backdropUrl, backdropThumbhash = null, tint = dominantColor) {
        DeferImagePresentationWhileScrolling(feedState) {
            LazyColumn(
                state = feedState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                item(key = "hero", contentType = "detail-hero") {
                    AdaptiveDetailHero(
                        detail = itemDetail,
                        eyebrow = state.eyebrow,
                        // The library hero's facts: year and runtime or
                        // seasons, then two genres as one token.
                        sourceTokens = if (itemDetail.type == "series") {
                            HeroMetadata.seriesSourceTokens(itemDetail)
                        } else {
                            HeroMetadata.movieSourceTokens(itemDetail)
                        },
                        factsLine = if (itemDetail.type == "series") {
                            HeroMetadata.seriesFactsLine(itemDetail)
                        } else {
                            HeroMetadata.movieFactsLine(itemDetail)
                        },
                        dominantColor = dominantColor,
                        directorText = detail.creditText(),
                        actions = {
                            RequestDetailActions(state = state, onPrimary = onPrimary, onModerate = onModerate, onCancel = onCancel)
                        },
                    )
                }
                val recommendations = state.recommendations
                if (recommendations.isNotEmpty()) {
                    item(key = "more-like-this", contentType = "rail") {
                        Column(
                            modifier = Modifier.padding(bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RequestsSectionHeader(title = "More like this")
                            RequestCardRail(items = recommendations, key = { "${it.mediaType}:${it.tmdbId}" }) { result ->
                                RequestMediaCard(item = result, onClick = { router.openResult(result) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The primary action, its hint or error, the status card, admin decisions, and Cancel. */
@Composable
private fun RequestDetailActions(
    state: RequestDetailUiState,
    onPrimary: () -> Unit,
    onModerate: (AdminRequestAction) -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PrimaryActionButton(state = state, onClick = onPrimary)
        val message = state.actionErrorMessage
        if (message != null) {
            Text(
                text = message,
                fontSize = 12.sp,
                color = SiloSecondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.showsRequestHint) {
            Text(
                text = "Not in your library yet. Your server admin reviews requests.",
                fontSize = 12.sp,
                color = SiloSecondaryText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val progress = state.progress
        if (progress != null && state.showsStatus) {
            RequestStatusCard(progress = progress, record = state.displayedRecord)
        }
        val actions = state.moderationActions
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    val prominent = action != AdminRequestAction.Decline
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(CircleShape)
                            .background(if (prominent) Color.White else RequestChromeSelectedFill)
                            .clickable { onModerate(action) },
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (action) {
                                AdminRequestAction.Approve -> Icons.Filled.Check
                                AdminRequestAction.Decline -> Icons.Filled.Close
                                AdminRequestAction.Retry -> Icons.Filled.Refresh
                            },
                            contentDescription = null,
                            tint = if (prominent) Color.Black else SiloOnSurface,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = action.name,
                            fontSize = 15.sp,
                            fontWeight = if (prominent) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (prominent) Color.Black else SiloOnSurface,
                        )
                    }
                }
            }
        }
        if (state.canCancel) {
            Text(
                text = "Cancel Request",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = RequestColors.Rose,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onCancel)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/**
 * One button whose label and look follow [RequestPrimaryAction]: a white pill
 * for Request and Open in Library, a quiet capsule with the status dot otherwise.
 */
@Composable
private fun PrimaryActionButton(state: RequestDetailUiState, onClick: () -> Unit) {
    val action = state.primaryAction
    if (action == RequestPrimaryAction.Loading) return
    val interactive = action.isInteractive
    val shape = CircleShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (interactive) Color.White else RequestChromeRestingFill)
            .border(1.dp, if (interactive) Color.Transparent else RequestChromeRestingBorder, shape)
            .clickable(enabled = interactive, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (interactive) Color.Black else SiloOnSurface
        when (action) {
            RequestPrimaryAction.Request -> Icon(Icons.Filled.Add, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            RequestPrimaryAction.Submitting -> CircularProgressIndicator(color = SiloSecondaryText, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            is RequestPrimaryAction.OpenInLibrary ->
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            is RequestPrimaryAction.Status ->
                Box(modifier = Modifier.size(9.dp).background(action.state.tint.uiColor(), CircleShape))
            RequestPrimaryAction.Loading -> Unit
        }
        Text(
            text = state.primaryActionTitle,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The four request steps with their timestamps, under the primary action. */
@Composable
private fun RequestStatusCard(progress: RequestProgress, record: MediaRequest?) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp),
    ) {
        Text(
            text = "REQUEST STATUS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = SiloOnSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(bottom = 14.dp),
        )
        RequestStep.entries.forEach { step ->
            StepRow(step = step, progress = progress, record = record, isLast = step == RequestStep.Library)
        }
    }
}

@Composable
private fun StepRow(step: RequestStep, progress: RequestProgress, record: MediaRequest?, isLast: Boolean) {
    val isDone = step.ordinal < progress.completedSteps
    val isCurrent = step == progress.currentStep
    Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMarker(isDone = isDone, isCurrent = isCurrent, tint = progress.tint.uiColor())
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(if (isDone) SiloOnSurface.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.14f)),
                )
            }
        }
        Column(
            modifier = Modifier.padding(bottom = if (isLast) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stepTitle(step, isCurrent, progress),
                fontSize = 15.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isDone || isCurrent) SiloOnSurface else SiloOnSurface.copy(alpha = 0.45f),
            )
            stepDetail(step, isDone, isCurrent, progress, record)?.let {
                Text(text = it, fontSize = 12.sp, color = SiloSecondaryText)
            }
        }
    }
}

@Composable
private fun StepMarker(isDone: Boolean, isCurrent: Boolean, tint: Color) {
    when {
        isDone -> Box(
            modifier = Modifier.size(22.dp).background(SiloOnSurface.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(13.dp)) }
        isCurrent -> Box(
            modifier = Modifier.size(22.dp).border(2.dp, tint, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(modifier = Modifier.size(9.dp).background(tint, CircleShape)) }
        else -> Box(modifier = Modifier.size(22.dp).border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape))
    }
}

/** The current step reads as what's happening now: "Declined" instead of "Approved", "Queued" instead of "Downloading". */
private fun stepTitle(step: RequestStep, isCurrent: Boolean, progress: RequestProgress): String {
    if (!isCurrent) return step.title
    return when (progress.display) {
        is RequestDisplayState.NeedsAttention, RequestDisplayState.OnTheWay -> progress.shortLabel
        else -> step.title
    }
}

private fun stepDetail(step: RequestStep, isDone: Boolean, isCurrent: Boolean, progress: RequestProgress, record: MediaRequest?): String? {
    val reason = (progress.display as? RequestDisplayState.NeedsAttention)?.reason
    return when (step) {
        RequestStep.Requested -> RequestRowCopy.stamp(record?.createdAt)
        RequestStep.Approval -> when {
            isDone -> RequestRowCopy.stamp(record?.approvedAt)
            isCurrent && progress.display is RequestDisplayState.NeedsAttention -> requestReasonCopy(reason)
            isCurrent -> "Waiting for your server admin"
            else -> null
        }
        RequestStep.Download -> when {
            !isCurrent && !isDone -> null
            isCurrent && progress.display is RequestDisplayState.NeedsAttention -> requestReasonCopy(reason)
            else -> record?.let { RequestTargetSummary.text(it.targets) }
        }
        RequestStep.Library -> if (isCurrent) "Your library is picking it up" else null
    }
}

/** A catalog-shaped detail for the shared hero: the title, artwork, rating, and facts a request carries. */
private fun RequestMediaDetail.toHeroDetail(): ItemDetail = ItemDetail(
    contentId = "request:$mediaType:$tmdbId",
    type = if (mediaType == RequestMediaType.Series) "series" else "movie",
    title = title,
    year = year ?: 0,
    overview = overview.takeIf { it.isNotBlank() },
    contentRating = contentRating.takeIf { it.isNotBlank() },
    genres = genres,
    runtime = runtime ?: 0,
    seasonCount = numberOfSeasons,
    posterUrl = requestPosterUrl(posterPath),
    backdropUrl = requestBackdropUrl(backdropPath) ?: requestPosterUrl(posterPath),
    ratings = listOfNotNull(ExternalRatings.tmdb(voteAverage)),
)

private fun RequestMediaDetail.creditText(): String? = when {
    director.isNotBlank() -> "Directed by $director"
    creators.isNotEmpty() -> "Created by ${creators.take(2).joinToString(", ")}"
    else -> null
}
