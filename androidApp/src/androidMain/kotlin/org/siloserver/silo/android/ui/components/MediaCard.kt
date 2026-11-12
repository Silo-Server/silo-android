package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.ui.components.SpoilerImage
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.BoxSetSleeveColors
import org.siloserver.silo.common.ui.components.boxSetSleeves
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.android.ui.navigation.heroSharedKeyPrefix
import org.siloserver.silo.android.ui.navigation.heroSource
import java.util.UUID
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.CardWatchStatusBadge
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.overlays.CardWatchStatus
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale

object MediaGridDefaults {
    // Same minimum as the Library grid's Normal density (CatalogViewDensity),
    // so collections and saved lists break into the same column count as
    // Library on every screen width — 110dp tipped them into one fewer column
    // on scaled-up displays.
    val PosterGridMinWidth = 104.dp
    val PosterGridHorizontalSpacing = 12.dp
    val PosterGridVerticalSpacing = 16.dp

    /**
     * [PosterGridMinWidth] scaled by the card-presentation poster size.
     * Adaptive grids scale the *minimum* cell width, so the preference
     * shifts the column count rather than the card width (a smaller min
     * width fits more columns).
     */
    val scaledPosterGridMinWidth: Dp
        @Composable get() =
            PosterGridMinWidth * LocalCardPresentation.current.posterSize.posterScale
}

/**
 * The core media poster card used throughout the app.
 *
 * 120dp × 180dp poster (2:3, matching server poster artwork) with the watch
 * status (a check, or the episodes left) in the top-right and a thin progress
 * bar at the bottom of the artwork.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
    hideArtwork: Boolean = false,
    posterUrl: String?,
    posterThumbhash: String?,
    year: Int? = null,
    /** Replaces the year caption when set (e.g. the active sort's date). */
    subtitle: String? = null,
    type: String? = null,
    userState: MediaItemUserState? = null,
    progress: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp * LocalCardPresentation.current.posterSize.posterScale,
    artworkAspectRatio: Float = 2f / 3.3f,
    overlay: OverlayData? = null,
    actions: MediaCardActions = MediaCardActions(),
    /**
     * Content id to enroll this poster as the source of a shared-element hero
     * morph into the item-detail backdrop. Null (the default) leaves the card
     * a plain tap target — used where there is no matching hero to morph into.
     */
    sharedContentId: String? = null,
    detailBackdropUrl: String? = null,
    detailBackdropThumbhash: String? = null,
    hideDetailArtwork: Boolean = hideArtwork,
    /** A pill in the artwork's top-start corner, such as a box set's "Box set". */
    badge: String? = null,
    /** A tag in the artwork's bottom-end corner, such as a box set's film count. */
    cornerLabel: String? = null,
    /** Draws a box set's sleeves above the artwork; see [boxSetSleeves]. */
    sleeves: BoxSetSleeveColors? = null,
    /** Outlines the artwork, such as the movie being viewed in its box set row. */
    highlighted: Boolean = false,
    /**
     * Set by hosts whose layout always gives the card exactly [width], such
     * as a lazy row (unbounded width). A grid cell or a fill modifier
     * overrides [width], so those hosts leave it false.
     */
    widthIsExact: Boolean = false,
    /**
     * Always takes two title lines and a caption line, so every card in a grid
     * is one height and a [MediaCardPlaceholder] beside it matches.
     */
    reserveTextLines: Boolean = false,
) {
    val overlayState = LocalCardOverlayUiState.current
    var menuExpanded by remember { mutableStateOf(false) }
    // Unique per-placement hero key. Two placements of the same content id (e.g.
    // Continue Watching + a genre row) get distinct keys, so they never collide
    // in the shared-transition layout (the old flicker). Recomputed when this
    // slot is recycled for a different item. Null when the card isn't a hero.
    val heroHandoff = LocalHeroSourceHandoff.current
    val heroKey = remember(sharedContentId) {
        sharedContentId?.let { "${heroSharedKeyPrefix(it)}-${UUID.randomUUID()}" }
    }
    // iOS MediaCard.swift: VStack(alignment: .leading, spacing: 4).
    Column(
        modifier = modifier
            .width(width)
            .combinedClickable(
                onClick = {
                    // Record which exact placement was tapped so the detail hero
                    // pairs with this card, not a duplicate elsewhere on screen.
                    if (heroKey != null) heroHandoff?.pendingKey = heroKey
                    // Match the detail hero from its first loading frame.
                    heroHandoff?.pendingArtworkUrl = (detailBackdropUrl ?: posterUrl).takeUnless { hideDetailArtwork }
                    heroHandoff?.pendingArtworkThumbhash = if (hideDetailArtwork) null else if (detailBackdropUrl != null) {
                        detailBackdropThumbhash
                    } else {
                        posterThumbhash
                    }
                    onClick()
                },
                onLongClick = if (actions.isEmpty) null else { { menuExpanded = true } },
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // iOS posterAspectRatio = 2 : 3.3 (posterCardWidth 120 / height 198).
                .aspectRatio(artworkAspectRatio)
                // Outside the clip below, so they show above the artwork.
                .then(if (sleeves != null) Modifier.boxSetSleeves(sleeves) else Modifier)
                // Hero morph source. Must precede .clip() — sharedBounds renders
                // into the transition overlay and clipping is applied to its child.
                .heroSource(heroKey)
                .clip(MaterialTheme.shapes.small)
                .then(
                    if (highlighted) {
                        Modifier.border(2.dp, Color.White.copy(alpha = 0.70f), MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                ),
        ) {
            SpoilerImage(
                hidden = hideArtwork,
                url = posterUrl,
                thumbhash = posterThumbhash,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                defaultArtwork = DefaultArtworkKind.forItemType(type),
            )

            // Card-overlay badge layer (resolution / audio / ratings, etc.).
            // Sits over the image but under the watch status and progress so
            // those remain visually on top. Gated on the admin/user enable
            // flag and only when the caller supplied overlay data.
            val hasProgressBar = progress != null && progress > 0f
            if (overlayState.enabled && overlay != null) {
                CardOverlays(
                    data = overlay,
                    prefs = overlayState.prefs,
                    variant = CardOverlayVariant.Poster,
                    hasProgressBar = hasProgressBar,
                    modifier = Modifier.fillMaxSize(),
                    cardWidth = width.takeIf { widthIsExact && it.isSpecified },
                )
            }

            // Watched check or episodes left, top-right, on poster-shaped
            // artwork whether or not badges are on (web CardWatchStatus).
            if (artworkAspectRatio <= 1f) {
                CardWatchStatusBadge(CardWatchStatus.of(userState), Modifier.fillMaxSize())
            }

            if (badge != null) {
                Text(
                    text = badge.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (cornerLabel != null) {
                Text(
                    text = cornerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // Progress bar at bottom of artwork.
            if (hasProgressBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }

        val caption = subtitle ?: year?.takeIf { it > 0 }?.let { LocaleFormat.integer(it, currentNumberLocale()) }
        if (reserveTextLines) {
            // Always as tall as two title lines and a caption, so rows keep one
            // height; a short title keeps its caption under it and the spare
            // room goes below.
            Box {
                MediaCardTextLinesTemplate()
                MediaCardTitleAndCaption(title, caption, captionMaxLines = 1)
            }
        } else {
            MediaCardTitleAndCaption(title, caption)
        }

        MediaCardContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = actions,
            isPlayed = userState?.played == true,
            isFavorite = userState?.isFavorite == true,
            isInWatchlist = userState?.inWatchlist == true,
        )
    }
}

@Composable
private fun MediaCardTitleAndCaption(title: String, caption: String?, captionMaxLines: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // iOS titleText: .siloSubheadline (14sp), 2 lines.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (caption != null) {
            // iOS yearText: .siloCaption (12sp) at secondary text.
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = captionMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Invisible lines the height of two title lines and a caption, for fixed-height cards. */
@Composable
private fun MediaCardTextLinesTemplate() {
    Column(
        modifier = Modifier
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = " ", style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2) // i18n-ignore
        Text(text = " ", style = MaterialTheme.typography.bodySmall, maxLines = 1) // i18n-ignore
    }
}

/**
 * A card-shaped stand-in for a title that has not arrived yet, the height of a
 * [MediaCard] with `reserveTextLines`, so rows keep their size when it fills in.
 */
@Composable
fun MediaCardPlaceholder(
    modifier: Modifier = Modifier,
    width: Dp = 120.dp * LocalCardPresentation.current.posterSize.posterScale,
    artworkAspectRatio: Float = 2f / 3.3f,
) {
    Column(
        modifier = modifier.width(width),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(artworkAspectRatio)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        )
        MediaCardTextLinesTemplate()
    }
}
