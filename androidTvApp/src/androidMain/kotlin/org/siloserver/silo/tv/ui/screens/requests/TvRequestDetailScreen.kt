package org.siloserver.silo.tv.ui.screens.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.requestBackdropUrl
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
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
 * (mediaType, tmdbId). Shows title/metadata/genres/overview and a Request
 * action when the title is requestable, plus the current request status.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestDetailScreen(
    mediaType: String,
    tmdbId: Int,
    onBack: () -> Unit,
    viewModel: RequestDetailViewModel = koinViewModel { parametersOf(mediaType, tmdbId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.detail == null -> TvLoadingScreen()
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
            )
        }
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

                val meta = buildList {
                    detail.year?.takeIf { it > 0 }?.let { add(it.toString()) }
                    detail.runtime?.takeIf { it > 0 }?.let { add("${it} min") }
                    detail.contentRating.takeIf { it.isNotBlank() }?.let { add(it) }
                    detail.voteAverage?.takeIf { it > 0 }?.let { add("★ ${"%.1f".format(it)}") }
                    detail.genres.take(3).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
                }.joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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

                val request = detail.request
                when {
                    request.requestable -> {
                        TvRequestActionPill(
                            label = if (isSubmitting) "Requesting…" else "Request",
                            icon = Icons.Filled.Add,
                            onClick = onRequest,
                            enabled = !isSubmitting,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    !request.status.isNullOrBlank() -> {
                        Text(
                            text = "Request status: ${request.status}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    request.reason.isNotBlank() -> {
                        Text(
                            text = request.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

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
