package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.common.ui.components.ThumbhashImage
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.tv.ui.theme.ProgressTrack
import org.siloserver.silo.tv.ui.theme.ProgressFill
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.tv.ui.theme.siloCardDefaults
import org.siloserver.silo.tv.ui.util.tvArtworkAspectRatioForMediaType

/**
 * The core focusable media card used throughout the TV app.
 *
 * Displays a 2:3 poster with progress bar overlay, watched badge, favorite
 * indicator, and a title/year caption below. Uses Compose for TV's [Card]
 * so the native `.card` focus behavior (scale + shadow lift) activates
 * when the user moves a D-pad onto it.
 *
 * This is the TV counterpart to the phone app's `MediaCard`. Deliberately
 * doesn't call into the phone class — keeping the two apps' UI layers
 * isolated so we can iterate on TV sizing/focus without breaking mobile.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMediaCard(
    title: String,
    posterUrl: String?,
    posterThumbhash: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    year: Int? = null,
    userState: MediaItemUserState? = null,
    progress: Float? = null,
    mediaType: String? = null,
    width: Dp = tvCardWidth(),
    fillWidth: Boolean = false,
    artworkAspectRatio: Float? = null,
    focusRequester: FocusRequester? = null,
    cardModifier: Modifier = Modifier,
    overlay: OverlayData? = null,
    actions: TvMediaCardActions = TvMediaCardActions(),
    onLongClick: (() -> Unit)? = null,
) {
    val overlayState = LocalCardOverlayUiState.current
    val caption = LocalCardPresentation.current.caption
    val effectiveAspectRatio = artworkAspectRatio
        ?: tvArtworkAspectRatioForMediaType(mediaType)
        ?: (2f / 3f)
    val height = width / effectiveAspectRatio
    val watchedBadgeSize = (width * RowDimens.WatchedBadgeSizeFraction)
        .coerceIn(RowDimens.WatchedBadgeMinSize, RowDimens.WatchedBadgeMaxSize)
    val watchedBadgeIconSize = watchedBadgeSize * RowDimens.WatchedBadgeIconSizeFraction
    val watchedBadgePadding = (width * RowDimens.WatchedBadgePaddingFraction)
        .coerceIn(RowDimens.WatchedBadgeMinPadding, RowDimens.WatchedBadgeMaxPadding)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val cardShape = TvMediaCardShape
    val cardFocus = siloCardDefaults(shape = cardShape)

    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = if (fillWidth) {
            modifier
        } else {
            modifier.width(width)
        },
    ) {
        TvMediaCardContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = actions,
            isPlayed = userState?.played == true,
            isFavorite = userState?.isFavorite == true,
            isInWatchlist = userState?.inWatchlist == true,
        )

        Card(
            onClick = onClick,
            onLongClick = onLongClick ?: if (actions.isEmpty) null else { { menuExpanded = true } },
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = cardShape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = if (fillWidth) {
                cardModifier
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .fillMaxWidth()
                    .aspectRatio(effectiveAspectRatio)
            } else {
                cardModifier
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .size(width, height)
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ThumbhashImage(
                    url = posterUrl,
                    thumbhash = posterThumbhash,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Card-overlay badge layer. Over the poster, under the
                // watched badge + progress bar. Never intercepts focus.
                if (overlayState.enabled && overlay != null) {
                    CardOverlays(
                        data = overlay,
                        prefs = overlayState.prefs,
                        variant = CardOverlayVariant.Poster,
                        forceOpaqueBackground = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (userState?.played == true) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(watchedBadgePadding)
                            .size(watchedBadgeSize)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Watched",
                            tint = Color.Black,
                            modifier = Modifier.size(watchedBadgeIconSize),
                        )
                    }
                }

                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomStart)
                            .background(ProgressTrack),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(ProgressFill),
                        )
                    }
                }
            }
        }

        if (caption.showsTitle) {
            Spacer(modifier = Modifier.height(11.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.5.sp,
                    lineHeight = 18.5.sp,
                ),
                color = if (isFocused) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.78f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            if (caption.showsMetadata && year != null && year > 0) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = Color.White.copy(alpha = 0.70f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

    }
}

/**
 * Default width used by the TV media card. tvOS uses 260×390pt; Skyline maps
 * that to Android TV as 130×195dp.
 */
val TvCardWidth: Dp = RowDimens.PosterWidth

/** [TvCardWidth] scaled by the active poster-size preference. */
@Composable
fun tvCardWidth(): Dp = TvCardWidth.cardScaled()

/** Optical scale for wide TV thumbnails; poster cards scale from their actual width. */
const val TvCardOverlayScale: Float = 0.7f

/** Hoisted so every card shares one instance instead of allocating a shape per composition. */
private val TvMediaCardShape = RoundedCornerShape(8.dp)
