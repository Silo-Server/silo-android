package org.siloserver.silo.tv.ui.screens.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.ExternalRatings
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.reasonMessage
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestDisplayLabel
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvFactsRow
import org.siloserver.silo.tv.ui.components.TvHeroFactToken
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.viewmodel.RequestDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * TV request detail — the 10-foot counterpart to the phone RequestDetailScreen.
 * Reuses the shared [RequestDetailViewModel] (load + submitRequest) keyed by
 * (mediaType, tmdbId). Shows title/metadata/genres/overview and one primary
 * action: Request when the title is requestable, otherwise the request status.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestDetailScreen(
    mediaType: String,
    tmdbId: Int,
    onBack: () -> Unit,
    /**
     * False while the shell has an overlay (a cascade panel or the profile
     * menu) that Back should close first. This handler registers after the
     * shell's, so on Android 16 it would otherwise take that press and pop
     * the page from under the open overlay.
     */
    backEnabled: Boolean = true,
    onInitialContentFocus: () -> Unit = {},
    viewModel: RequestDetailViewModel = koinViewModel { parametersOf(mediaType, tmdbId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val loadingFocusRequester = remember { FocusRequester() }
    val primaryActionFocusRequester = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    var pageHadFocus by remember { mutableStateOf(false) }
    val showsLoading = state.isLoading && state.detail == null
    // The title, not the loaded detail: the refresh after a submit replaces the
    // detail and must not re-run the claim below.
    val detailKey = state.detail?.let { "${it.mediaType}:${it.tmdbId}" }
    val detailArrived by rememberUpdatedState(detailKey != null)

    // Nothing here is focusable until the detail loads, and the row that opened
    // the page is about to be disposed, so Compose would re-home focus onto the
    // top bar's Search button. Hold it on the loading indicator instead: focus
    // stays in the page, and a move to the bar while this loads is the viewer's.
    LaunchedEffect(showsLoading) {
        if (!showsLoading) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = loadingFocusRequester::requestFocus,
            isFocused = { pageHasFocus || detailArrived },
        )
    }

    // Hand focus to the primary action when the detail arrives, but only if it
    // is still in the page (or never got here). A viewer who went up to the bar
    // or into the profile menu while this loaded keeps their place. Read in the
    // composition that swaps the indicator for the content, so it is the focus
    // from before the swap.
    val claimOnArrival = remember(detailKey) { pageHasFocus || !pageHadFocus }
    LaunchedEffect(detailKey) {
        if (detailKey == null || !claimOnArrival) return@LaunchedEffect
        val result = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = primaryActionFocusRequester::requestFocus,
            isFocused = { pageHasFocus },
        )
        if (result == TvObservedFocusResult.Focused) onInitialContentFocus()
    }

    BackHandler(enabled = backEnabled) { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                pageHasFocus = it.hasFocus
                if (it.hasFocus) pageHadFocus = true
            }
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            showsLoading -> RequestDetailLoading(focusRequester = loadingFocusRequester)
            state.error != null && state.detail == null -> TvErrorScreen(
                message = state.error ?: "Failed to load this title.",
                onRetry = viewModel::load,
            )
            state.detail != null -> RequestDetailContent(
                detail = state.detail!!,
                isSubmitting = state.isSubmitting,
                notice = state.notice,
                error = state.error,
                onRequest = viewModel::submitRequest,
                primaryActionFocusRequester = primaryActionFocusRequester,
            )
        }
    }
}

/**
 * The loading indicator plus a spinner-sized focus target over it, so focus
 * can wait inside the page. Kept small and centred: D-pad Up from it has to
 * find the top bar by geometry, which a full-screen target would not.
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

/**
 * Content starts below the shell's top navigation, which is a `TopStart`
 * overlay drawn over every screen rather than something that reserves space.
 * Without this the title rendered *through* the nav row and the eyebrow was
 * clipped off the top edge entirely.
 */
private val ShellNavInset = 96.dp

/** TV-safe horizontal margin; the outer ~5% of a panel is not reliably visible. */
private val SafeHorizontal = 48.dp

/**
 * The overview is a full TMDB synopsis — often several hundred words, which on
 * a 10-foot display filled the screen and pushed the Request action off the
 * bottom. Clamped to a readable teaser; the point of this screen is deciding
 * whether to request, not reading the whole plot.
 */
private const val OverviewMaxLines = 4

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RequestDetailContent(
    detail: RequestMediaDetail,
    isSubmitting: Boolean,
    notice: String?,
    error: String?,
    onRequest: () -> Unit,
    primaryActionFocusRequester: FocusRequester,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop, scrimmed hard enough that body copy stays legible over the
        // busiest frame. Absent artwork simply leaves the background colour.
        requestBackdropUrl(detail.backdropPath)?.let { url ->
            ThumbhashImage(
                url = url,
                thumbhash = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background,
                        0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.86f),
                        0.4f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        start = SafeHorizontal,
                        end = SafeHorizontal,
                        top = ShellNavInset,
                        bottom = 48.dp,
                    ),
                ),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            requestPosterUrl(detail.posterPath)?.let { url ->
                ThumbhashImage(
                    url = url,
                    thumbhash = null,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(RowDimens.PosterWidth.cardScaled(), RowDimens.PosterHeight.cardScaled())
                        .clip(RoundedCornerShape(10.dp)),
                )
            }

            Column(
                // Held to the scrimmed side so the backdrop stays visible on the
                // right rather than sitting behind the text.
                modifier = Modifier.widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "REQUEST",
                    style = sectionEyebrow,
                    color = SiloBlue.copy(alpha = 0.92f),
                )
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                RequestMetaLine(detail)

                if (detail.tagline.isNotBlank()) {
                    Text(
                        text = detail.tagline,
                        style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (detail.overview.isNotBlank()) {
                    Text(
                        text = detail.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        maxLines = OverviewMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // One pill in every state, never swapped for another node. The
                // status used to be plain text, which left the page with nothing
                // to focus, and replacing Request with it after a submit dropped
                // the focused node the same way. Only Request is enabled; the
                // status or reason renders disabled so it reads as not
                // actionable. A disabled TV Surface still takes focus (its
                // clickable is focusable regardless of enabled), so the page
                // keeps a focus target in every state, like tvOS
                // RequestDetailView's single primary action.
                val request = detail.request
                TvRequestActionPill(
                    label = request.primaryActionLabel(isSubmitting),
                    icon = Icons.Filled.Add.takeIf { request.requestable },
                    onClick = onRequest,
                    enabled = request.requestable && !isSubmitting,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .focusRequester(primaryActionFocusRequester),
                )

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** `2024 · 128 min · PG-13 · TMDB 7.8 · Drama · Crime`; past the width, whole facts drop from the end. */
@Composable
private fun RequestMetaLine(detail: RequestMediaDetail) {
    val tokens = buildList {
        detail.year?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken(it.toString())) }
        detail.runtime?.takeIf { it > 0 }?.let { add(TvHeroFactToken.TextToken("$it min")) }
        detail.contentRating.takeIf { it.isNotBlank() }?.let { add(TvHeroFactToken.TextToken(it)) }
        ExternalRatings.tmdb(detail.voteAverage)?.let { add(TvHeroFactToken.ExternalRating(it)) }
        detail.genres.take(3).forEach { add(TvHeroFactToken.TextToken(it)) }
    }
    if (tokens.isEmpty()) return
    TvFactsRow(
        tokens = tokens,
        style = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        spacing = 10.dp,
    )
}

/**
 * Label for the detail's primary action: Request while the title is
 * requestable, otherwise the existing request's status, otherwise the reason
 * it cannot be requested. Status tokens use [requestDisplayLabel]; reasons use
 * the shared [reasonMessage] policy for readable sentences and unknown codes.
 */
private fun RequestState.primaryActionLabel(isSubmitting: Boolean): String {
    if (requestable) return if (isSubmitting) "Requesting…" else "Request"
    val status = status?.takeIf { it.isNotBlank() }
    return when {
        status != null -> "Request status: ${status.requestDisplayLabel()}"
        else -> reasonMessage() ?: "Unavailable"
    }
}
