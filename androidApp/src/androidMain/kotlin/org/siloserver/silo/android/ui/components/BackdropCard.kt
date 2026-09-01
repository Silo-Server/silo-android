package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.ui.components.ThumbhashImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.overlays.OverlayData

/**
 * Reserved height for the text block under a backdrop card: title (bodySmall)
 * plus up to two label lines (episode subtitle + "Xm left") when the caption
 * preference shows metadata. Computed from the ACTUAL line heights at the
 * current density & font scale — a fixed dp constant doesn't grow with
 * fontScale, so accessibility text sizes (>=~1.25) sheared the last line.
 * Every card in a row resolves the identical value (theme-driven, not
 * content-driven), so mixed rows still align. Reserves the tallest stack (an
 * episode card with a remaining-time line) so shorter movie cards fit too.
 */
@Composable
private fun backdropInfoBlockHeight(showsMetadata: Boolean): Dp =
    with(LocalDensity.current) {
        val typography = MaterialTheme.typography
        typography.bodySmall.lineHeight.toDp() +
            if (showsMetadata) typography.labelSmall.lineHeight.toDp() * 2 else 0.dp
    }

/**
 * Landscape card for "Continue Watching" / "Next Up" sections.
 * Shows a 16:9 backdrop with a play button overlay, progress bar, and title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackdropCard(
    title: String,
    backdropUrl: String?,
    backdropThumbhash: String?,
    seriesTitle: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    progress: Float? = null,
    remainingMinutes: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 280.dp * LocalCardPresentation.current.posterSize.posterScale,
    userState: MediaItemUserState? = null,
    overlay: OverlayData? = null,
    actions: MediaCardActions = MediaCardActions(),
    // Center overlay glyph — play for watch/listen, a book for reading.
    overlayIcon: ImageVector = Icons.Default.PlayArrow,
    overlayContentDescription: String = "Play",
    /** Non-null makes the center glyph a direct action (resume playback);
     *  null keeps it decorative and the whole card opens the item page. */
    onOverlayClick: (() -> Unit)? = null,
) {
    val overlayState = LocalCardOverlayUiState.current
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(width)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (actions.isEmpty) null else { { menuExpanded = true } },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            ThumbhashImage(
                url = backdropUrl,
                thumbhash = backdropThumbhash,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Bottom scrim gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xAA000000)),
                        ),
                    ),
            )

            // Continue Watching / Next Up use the same server-configured
            // badges as Apple, tvOS, and Android TV. The wide preset leaves
            // the lower corners clear of the resume progress treatment.
            if (overlayState.enabled && overlay != null) {
                CardOverlays(
                    data = overlay,
                    prefs = overlayState.prefs,
                    variant = CardOverlayVariant.Wide,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Play button circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .then(
                        if (onOverlayClick != null) {
                            Modifier.clickable(onClick = onOverlayClick)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = overlayIcon,
                    contentDescription = overlayContentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Progress bar at bottom
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = Color.White,
                    trackColor = Color.Black.copy(alpha = 0.4f),
                )
            }
        }

        val cardCaption = LocalCardPresentation.current.caption
        if (cardCaption.showsTitle) {
        Spacer(modifier = Modifier.height(6.dp))

        // Fixed-height info block: episode cards draw up to three text lines,
        // movie cards as few as one. In a mixed row (Continue Watching) the
        // LazyRow's height would otherwise change with whichever items are
        // visible, bouncing every row below it during horizontal scrolls.
        Column(modifier = Modifier.height(backdropInfoBlockHeight(cardCaption.showsMetadata))) {
        if (seriesTitle != null) {
            // Episode card: show series title, then episode tag + episode title
            Text(
                text = seriesTitle,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (cardCaption.showsMetadata) {
                val episodeTag = formatEpisodeTag(seasonNumber, episodeNumber)
                val subtitle = if (episodeTag != null) "$episodeTag \u2022 $title" else title
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            // Movie card: just show the title
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Remaining time
        if (cardCaption.showsMetadata && remainingMinutes != null && remainingMinutes > 0) {
            Text(
                text = "${remainingMinutes}m left",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        }
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

private fun formatEpisodeTag(season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val s = season?.let { "S${it.toString().padStart(2, '0')}" } ?: ""
    val e = episode?.let { "E${it.toString().padStart(2, '0')}" } ?: ""
    return "$s$e"
}
