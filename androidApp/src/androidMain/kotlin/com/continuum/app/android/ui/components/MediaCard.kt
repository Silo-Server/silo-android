package com.continuum.app.android.ui.components

import com.continuum.app.common.ui.components.ThumbhashImage
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
import com.continuum.app.android.ui.navigation.heroSharedBounds
import com.continuum.app.common.overlays.CardOverlayVariant
import com.continuum.app.common.overlays.CardOverlays
import com.continuum.app.common.overlays.LocalCardOverlayUiState
import com.continuum.app.model.catalog.MediaItemUserState
import com.continuum.app.overlays.OverlayData

object MediaGridDefaults {
    val PosterGridMinWidth = 110.dp
    val PosterGridHorizontalSpacing = 12.dp
    val PosterGridVerticalSpacing = 16.dp
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
    type: String? = null,
    userState: MediaItemUserState? = null,
    progress: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
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
    // iOS MediaCard.swift: VStack(alignment: .leading, spacing: 4).
    Column(
        modifier = modifier
            .width(width)
            .combinedClickable(
                onClick = onClick,
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
                .heroSharedBounds(sharedContentId)
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

        // iOS titleText: .continuumSubheadline (14sp), 2 lines.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (year != null && year > 0) {
            // iOS yearText: .continuumCaption (12sp) at secondary text.
            Text(
                text = year.toString(),
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
