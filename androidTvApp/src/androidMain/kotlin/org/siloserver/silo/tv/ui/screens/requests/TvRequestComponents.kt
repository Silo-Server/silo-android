package org.siloserver.silo.tv.ui.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.requests.color
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.model.request.requestPosterUrl
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

internal fun RequestStatusTint.tvColor(): Color = color(neutral = SiloSecondaryText)

/** Dense Skyline poster width, scaled by the card presentation preference. */
@Composable
internal fun tvRequestCardWidth(): Dp = RowDimens.DensePosterWidth * LocalCardPresentation.current.posterSize.posterScale

/**
 * Poster card for a TMDB search/discover result in the Skyline rows' grammar
 * (`TvMediaCard`): a title already requested or in the library gets a small
 * corner badge, and the caption keeps its year.
 */
@Composable
fun TvRequestCard(
    result: RequestMediaResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Applied to the focusable card itself rather than the card-and-caption column. */
    cardModifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    width: Dp = tvRequestCardWidth(),
) {
    TvRequestPosterCard(
        title = result.title,
        year = result.year,
        posterPath = result.posterPath,
        progress = RequestProgress.of(result.availability, result.request),
        showsStatusInCaption = false,
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        modifier = modifier,
        cardModifier = cardModifier,
        width = width,
    )
}

/** Poster card for a request record (the user's own, or one an admin decides on): its status is the caption. */
@Composable
fun TvRequestRecordCard(
    record: MediaRequest,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    width: Dp = tvRequestCardWidth(),
) {
    TvRequestPosterCard(
        title = record.title,
        year = record.year,
        posterPath = record.posterPath,
        progress = RequestProgress.of(record),
        showsStatusInCaption = true,
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        modifier = modifier,
        width = width,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvRequestPosterCard(
    title: String,
    year: Int?,
    posterPath: String?,
    progress: RequestProgress?,
    showsStatusInCaption: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    focusRequester: FocusRequester?,
    modifier: Modifier,
    width: Dp,
    cardModifier: Modifier = Modifier,
) {
    val caption = LocalCardPresentation.current.caption
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val focus = siloCardDefaults(shape = shape)
    val label = listOfNotNull(title, year?.takeIf { it > 0 }?.toString(), progress?.shortLabel).joinToString(", ")
    Column(modifier = modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = shape),
            scale = focus.scale,
            border = focus.border,
            glow = focus.glow,
            modifier = cardModifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(width, width * 1.5f)
                .semantics { contentDescription = label },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f))) {
                ThumbhashImage(
                    url = requestPosterUrl(posterPath),
                    thumbhash = null,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (!showsStatusInCaption && progress != null) {
                    val badgeSize = (width * RowDimens.WatchedBadgeSizeFraction)
                        .coerceIn(RowDimens.WatchedBadgeMinSize, RowDimens.WatchedBadgeMaxSize)
                    val badgePadding = (width * RowDimens.WatchedBadgePaddingFraction)
                        .coerceIn(RowDimens.WatchedBadgeMinPadding, RowDimens.WatchedBadgeMaxPadding)
                    TvRequestPosterBadge(
                        state = progress.display,
                        size = badgeSize,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(badgePadding),
                    )
                }
            }
        }
        if (caption.showsTitle) {
            Spacer(modifier = Modifier.height(11.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.5.sp, lineHeight = 18.5.sp),
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                showsStatusInCaption && progress != null -> TvRequestStatusLabel(
                    progress = progress,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.70f),
                )
                caption.showsMetadata && year != null && year > 0 -> Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
        }
    }
}

/** A white glyph on a filled status disc, in the watched badge's geometry. */
@Composable
internal fun TvRequestPosterBadge(state: RequestDisplayState, size: Dp, modifier: Modifier = Modifier) {
    val icon = when (state) {
        RequestDisplayState.Pending -> Icons.Filled.Schedule
        RequestDisplayState.OnTheWay -> Icons.Filled.ArrowDownward
        RequestDisplayState.InLibrary -> Icons.Filled.Check
        is RequestDisplayState.NeedsAttention -> Icons.Filled.PriorityHigh
        is RequestDisplayState.Unavailable -> return
    }
    Box(
        modifier = modifier.size(size).background(state.tint.tvColor(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Status dot and label. */
@Composable
internal fun TvRequestStatusLabel(
    progress: RequestProgress,
    modifier: Modifier = Modifier,
    text: String = progress.shortLabel,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = Color.White.copy(alpha = 0.70f),
    dotSize: Dp = 6.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dotSize),
    ) {
        Box(modifier = Modifier.size(dotSize).background(progress.tint.tvColor(), CircleShape))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize, lineHeight = fontSize * 1.3f),
            fontWeight = fontWeight,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
