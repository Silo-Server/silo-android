package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.ui.components.ThumbhashImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.android.ui.navigation.heroSharedKeyPrefix
import org.siloserver.silo.android.ui.navigation.heroSource
import java.util.UUID
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.overlays.OverlayData

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
 * 120dp × 180dp poster (2:3, matching server poster artwork) with a white
 * watched-checkmark circle in the top-right and a thin progress bar at the
 * bottom of the artwork.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
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
                // Hero morph source. Must precede .clip() — sharedBounds renders
                // into the transition overlay and clipping is applied to its child.
                .heroSource(heroKey)
                .clip(MaterialTheme.shapes.small),
        ) {
            ThumbhashImage(
                url = posterUrl,
                thumbhash = posterThumbhash,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Card-overlay badge layer (resolution / audio / ratings, etc.).
            // Sits over the image but under the watched-check + progress so
            // those remain visually on top. Gated on the admin/user enable
            // flag and only when the caller supplied overlay data.
            if (overlayState.enabled && overlay != null) {
                CardOverlays(
                    data = overlay,
                    prefs = overlayState.prefs,
                    variant = CardOverlayVariant.Poster,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Watched indicator: white circle + checkmark, top-right.
            // Mirrors iOS MediaCard.swift:104–119.
            if (userState?.played == true) {
                WatchedBadge(modifier = Modifier.align(Alignment.TopEnd))
            }

            // Progress bar at bottom of artwork.
            if (progress != null && progress > 0f) {
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

        val cardCaption = LocalCardPresentation.current.caption
        if (cardCaption.showsTitle) {
            // iOS titleText: .siloSubheadline (14sp), 2 lines.
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val caption = subtitle ?: year?.takeIf { it > 0 }?.toString()
        if (cardCaption.showsMetadata && caption != null) {
            // iOS yearText: .siloCaption (12sp) at secondary text.
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
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
