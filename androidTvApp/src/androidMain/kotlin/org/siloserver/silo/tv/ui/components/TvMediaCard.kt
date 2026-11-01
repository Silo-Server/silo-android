package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import org.siloserver.silo.common.ui.components.BoxSetSleeveColors
import org.siloserver.silo.common.ui.components.boxSetSleeves
import org.siloserver.silo.common.ui.components.SpoilerImage
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.common.ui.components.ThumbhashImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
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
    hideArtwork: Boolean = false,
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
    /** A pill in the artwork's top-start corner, such as a box set's "Box set". */
    badge: String? = null,
    /** A tag in the artwork's bottom-end corner, such as a box set's film count. */
    cornerLabel: String? = null,
    /** Replaces the year line under the title. */
    caption: String? = null,
    /** Draws a box set's sleeves above the artwork; see [boxSetSleeves]. */
    sleeves: BoxSetSleeveColors? = null,
    /** Outlines the artwork at rest, such as the movie being viewed in its box set row. */
    highlighted: Boolean = false,
) {
    val overlayState = LocalCardOverlayUiState.current
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
    // Focus keeps the standard lavender border; at rest a highlighted card
    // keeps a white outline.
    val cardBorder = if (highlighted) {
        CardDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.70f)),
                shape = cardShape,
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, org.siloserver.silo.tv.ui.theme.AccentLavender),
                shape = cardShape,
            ),
        )
    } else {
        cardFocus.border
    }

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

        Box {
            if (sleeves != null) {
                // The card scales inside its own layer, out of reach of anything
                // passed in, so the sleeves sit on a layer behind it that scales
                // the same way (tv-material SurfaceScaleTokens) and grows with it.
                val isPressed by interactionSource.collectIsPressedAsState()
                val targetScale = if (isFocused && !isPressed) cardFocus.focusedScale else 1f
                val sleeveScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = tween(
                        durationMillis = when {
                            isPressed -> 120
                            isFocused -> 300
                            else -> 500
                        },
                        easing = SleeveScaleEasing,
                    ),
                    label = "box-set-sleeves",
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            scaleX = sleeveScale
                            scaleY = sleeveScale
                        }
                        .boxSetSleeves(sleeves),
                )
            }
            Card(
                onClick = onClick,
                onLongClick = onLongClick ?: if (actions.isEmpty) null else { { menuExpanded = true } },
                interactionSource = interactionSource,
                shape = CardDefaults.shape(shape = cardShape),
                scale = cardFocus.scale,
                border = cardBorder,
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
                    SpoilerImage(
                        hidden = hideArtwork,
                        url = posterUrl,
                        thumbhash = posterThumbhash,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        defaultArtwork = DefaultArtworkKind.forItemType(mediaType),
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

                    if (badge != null) {
                        Text(
                            text = badge.uppercase(),
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    if (cornerLabel != null) {
                        Text(
                            text = cornerLabel,
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
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
        }

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

        val metadata = caption ?: year?.takeIf { it > 0 }?.toString()
        if (metadata != null) {
            Text(
                text = metadata,
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

/** tv-material's focus scale easing (SurfaceScaleTokens.enterEasing). */
private val SleeveScaleEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/** Hoisted so every card shares one instance instead of allocating a shape per composition. */
private val TvMediaCardShape = RoundedCornerShape(8.dp)
