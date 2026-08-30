package org.siloserver.silo.tv.ui.components

import org.siloserver.silo.common.ui.components.ThumbhashImage
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.tv.ui.theme.ProgressFill
import org.siloserver.silo.tv.ui.theme.ProgressTrack
import org.siloserver.silo.tv.ui.theme.RowDimens
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

/**
 * 16:9 thumbnail card for "Continue Watching", "Next Up", and episode list rows.
 *
 * Uses the same focus treatment as [TvMediaCard]: native `.card` button style
 * from Compose for TV handles scale/shadow lift; the caption bolds on focus.
 * The play-button circle is an affordance rather than a hit target — the whole
 * card is clickable and the D-pad OK button triggers [onClick].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvEpisodeCard(
    title: String,
    stillUrl: String?,
    stillThumbhash: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seriesTitle: String? = null,
    year: Int? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    progress: Float? = null,
    width: Dp = tvEpisodeCardWidth(),
    focusRequester: FocusRequester? = null,
    cardModifier: Modifier = Modifier,
    userState: org.siloserver.silo.model.catalog.MediaItemUserState? = null,
    overlay: OverlayData? = null,
    actions: TvMediaCardActions = TvMediaCardActions(),
) {
    val overlayState = LocalCardOverlayUiState.current
    val caption = LocalCardPresentation.current.caption
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val cardShape = TvEpisodeCardShape
    val cardFocus = siloCardDefaults(shape = cardShape, focusedScale = 1.04f)
    val episodeBadge = formatEpisodeTag(seasonNumber, episodeNumber)

    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.width(width),
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
            onLongClick = if (actions.isEmpty) null else { { menuExpanded = true } },
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = cardShape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = cardModifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ThumbhashImage(
                    url = stillUrl,
                    thumbhash = stillThumbhash,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.Transparent,
                                1f to Color(0x99000000),
                            ),
                        ),
                )

                // Card-overlay badge layer. Over the still + scrim, under the
                // play affordance + progress bar. Never intercepts focus.
                if (overlayState.enabled && overlay != null) {
                    CardOverlays(
                        data = overlay,
                        prefs = overlayState.prefs,
                        variant = CardOverlayVariant.Wide,
                        scale = TvCardOverlayScale,
                        forceOpaqueBackground = false,
                        modifier = Modifier.fillMaxSize(),
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

                if (episodeBadge != null) {
                    Text(
                        text = episodeBadge,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(7.dp)
                            .background(
                                Color.Black.copy(alpha = 0.65f),
                                RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 7.dp, vertical = 3.5.dp),
                    )
                }
            }
        }

        if (caption.showsTitle) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = seriesTitle ?: title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 18.5.sp,
                    ),
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                val secondaryLine = if (seriesTitle != null) title else year?.toString()
                if (caption.showsMetadata && secondaryLine != null) {
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                        ),
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

    }
}

private fun formatEpisodeTag(season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val s = season?.let { "S$it" }
    val e = episode?.let { "E$it" }
    return listOfNotNull(s, e).joinToString(" · ")
}

/**
 * Default width for episode / next-up cards. tvOS uses 360×200pt; Skyline maps
 * that to Android TV as 180×100dp.
 */
val TvEpisodeCardWidth: Dp = RowDimens.BackdropWidth

/** [TvEpisodeCardWidth] scaled by the active poster-size preference. */
@Composable
fun tvEpisodeCardWidth(): Dp = TvEpisodeCardWidth.cardScaled()

/** Hoisted so every card shares one instance instead of allocating a shape per composition. */
private val TvEpisodeCardShape = RoundedCornerShape(8.dp)
