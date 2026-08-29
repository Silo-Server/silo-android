package org.siloserver.silo.tv.ui.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestAvailability
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.model.request.badgeStatus
import org.siloserver.silo.model.request.requestDisplayLabel
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.model.request.targetSummary
import org.siloserver.silo.tv.ui.components.TvCardWidth
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.DarkOnPrimary
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestCard(
    result: RequestMediaResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val cardShape = RoundedCornerShape(8.dp)
    val cardFocus = siloCardDefaults(shape = cardShape)
    val caption = LocalCardPresentation.current.caption

    Column(modifier = modifier.width(TvCardWidth.cardScaled())) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape = cardShape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = Modifier
                .then(cardModifier)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(RowDimens.PosterWidth.cardScaled(), RowDimens.PosterHeight.cardScaled()),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ThumbhashImage(
                    url = requestPosterUrl(result.posterPath),
                    thumbhash = null,
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                TvRequestStatusChip(
                    status = result.badgeStatus(),
                    outcome = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
        }
        if (caption.showsTitle) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (caption.showsMetadata) {
                RequestMetaLine(mediaType = result.mediaType, year = result.year)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestListCard(
    request: MediaRequest,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            focusedContentColor = Color.White,
            pressedContainerColor = Color.White.copy(alpha = 0.18f),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(2f / 3f)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
            ) {
                ThumbhashImage(
                    url = requestPosterUrl(request.posterPath),
                    thumbhash = null,
                    contentDescription = request.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RequestMetaLine(mediaType = request.mediaType, year = request.year)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRequestStatusChip(status = request.status, outcome = null)
                    TvRequestStatusChip(status = request.outcome, outcome = request.outcome)
                }
                request.targetSummary()?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                request.lastError.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun TvRequestStatusChip(
    status: String,
    outcome: String?,
    modifier: Modifier = Modifier,
) {
    val normalized = status.ifBlank { outcome.orEmpty() }
    val (background, foreground) = when (normalized.lowercase()) {
        RequestAvailability.Available, RequestStatus.Completed -> SiloBlue to DarkOnPrimary
        RequestStatus.Pending, RequestOutcome.Active -> Color(0xFFFFD166).copy(alpha = 0.92f) to Color.Black
        RequestStatus.Failed, RequestOutcome.Failed -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        RequestOutcome.Cancelled, RequestOutcome.Declined -> Color.White.copy(alpha = 0.14f) to Color.White.copy(alpha = 0.80f)
        else -> Color.Black.copy(alpha = 0.64f) to Color.White
    }
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(100.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = normalized.requestDisplayLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRequestActionPill(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(100.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.92f),
            focusedContentColor = DarkOnPrimary,
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = DarkOnPrimary,
            disabledContainerColor = Color.White.copy(alpha = 0.05f),
            disabledContentColor = Color.White.copy(alpha = 0.42f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RequestMetaLine(
    mediaType: String,
    year: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = mediaType.icon(),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.62f),
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = listOfNotNull(mediaType.requestDisplayLabel(), year?.toString()).joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun RequestMediaResult.canOpenLibraryDetail(): Boolean =
    availability == RequestAvailability.Available && !libraryContentId.isNullOrBlank()

fun RequestMediaResult.canRequest(): Boolean =
    availability != RequestAvailability.Available && request.requestable

private fun String.icon(): ImageVector = when (this) {
    RequestMediaType.Series -> Icons.Default.Tv
    else -> Icons.Default.Movie
}
