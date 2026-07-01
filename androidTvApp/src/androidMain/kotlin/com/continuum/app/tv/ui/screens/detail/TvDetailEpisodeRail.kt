package com.continuum.app.tv.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.model.catalog.EpisodeListItem
import com.continuum.app.tv.ui.theme.ContinuumOnSurface
import com.continuum.app.tv.ui.theme.ContinuumSecondaryText
import com.continuum.app.tv.ui.theme.DarkSurfaceElevated
import com.continuum.app.tv.ui.theme.ProgressFill
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.capsuleCaps

/**
 * Horizontal rail of episode cards for the series/season/episode detail
 * screens — a direct port of tvOS `TVEpisodeRail`. Pressing OK on a card
 * navigates to that episode's own detail page (where the user picks a
 * version, marks watched and starts playback); the rail is a browsing
 * surface, NOT a direct play launcher.
 *
 * When `currentContentId` is non-null the matching card is highlighted
 * (white 2dp ring + full-color title), scrolled to the horizontal center
 * on first appearance, and made the default focus target so d-padding down
 * into the rail lands on the episode the user is already viewing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvDetailEpisodeRail(
    episodes: List<EpisodeListItem>,
    onEpisodeSelected: (EpisodeListItem) -> Unit,
    modifier: Modifier = Modifier,
    currentContentId: String? = null,
    onDirectionUp: (() -> Boolean)? = null,
) {
    if (episodes.isEmpty()) return

    val listState = rememberLazyListState()
    val currentIndex = remember(currentContentId, episodes) {
        episodes.indexOfFirst { it.contentId == currentContentId }.takeIf { it >= 0 }
    }
    // Default focus target: the current episode if present. Mirrors tvOS
    // `defaultFocus(..., priority: .userInitiated)` so d-pad entry into the
    // rail lands on the current episode rather than the first card.
    val defaultFocusRequester = remember { FocusRequester() }

    // Auto-center the current episode on first appearance (parity with the
    // tvOS `proxy.scrollTo(id, anchor: .center)` on appear). Same true-center
    // approach as TvSeasonPicker: bring the item into view, then nudge by the
    // delta between the item center and the viewport center.
    LaunchedEffect(currentContentId, episodes.size) {
        val target = currentIndex ?: return@LaunchedEffect
        listState.scrollToItem(target)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        listState.animateScrollBy(itemCenter - viewportCenter)
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onDirectionUp != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onDirectionUp()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .focusGroup()
            .then(
                if (currentIndex != null) {
                    Modifier.focusProperties { enter = { defaultFocusRequester } }
                } else {
                    Modifier
                },
            ),
        state = listState,
        contentPadding = PaddingValues(
            horizontal = Spacing.safeArea,
            vertical = 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(
            episodes,
            key = { it.contentId },
            contentType = { "episode-card" },
        ) { episode ->
            val isCurrent = episode.contentId == currentContentId
            TvDetailEpisodeCard(
                episode = episode,
                isCurrent = isCurrent,
                onClick = { onEpisodeSelected(episode) },
                modifier = if (isCurrent) {
                    Modifier.focusRequester(defaultFocusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun TvDetailEpisodeCard(
    episode: EpisodeListItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardWidth = 230.dp
    val stillHeight = 130.dp
    val cornerRadius = 5.dp
    val shape = RoundedCornerShape(cornerRadius)

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Scale + shadow only — no TV Material focus halo. The white ring on the
    // still (driven by isFocused below) is the focus cue. Matches tvOS
    // `EpisodeCardStyle`.
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "episodeCardScale",
    )

    Column(
        modifier = modifier
            .width(cardWidth)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Still
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(stillHeight)
                .shadow(
                    elevation = if (isFocused) 18.dp else 8.dp,
                    shape = shape,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                .clip(shape)
                .background(DarkSurfaceElevated)
                .then(
                    when {
                        isFocused -> Modifier.border(3.dp, Color.White.copy(alpha = 0.9f), shape)
                        isCurrent -> Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), shape)
                        else -> Modifier
                    },
                ),
        ) {
            if (!episode.stillUrl.isNullOrBlank()) {
                ThumbhashImage(
                    url = episode.stillUrl,
                    thumbhash = episode.stillThumbhash,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = ContinuumSecondaryText,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                )
            }

            if (episode.userData?.played == true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                )
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }

            episode.progressFraction()?.let { fraction ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.6f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(5.dp)
                            .background(ProgressFill),
                    )
                }
            }
        }

        // Text block mirrors tvOS vertical rhythm at Android canvas scale, with
        // a readability floor for episode metadata and descriptions.
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = episodeEyebrow(episode),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                    color = ContinuumOnSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
                if (isCurrent) {
                    NowViewingTag()
                }
            }

            Text(
                text = episode.title ?: "Episode ${episode.episodeNumber}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    isCurrent -> ContinuumOnSurface
                    isFocused -> ContinuumOnSurface
                    else -> ContinuumOnSurface.copy(alpha = 0.92f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                // tvOS uses lineLimit(3, reservesSpace: true). Reserve a fixed
                // 3-line height (20sp line + 3sp spacing ≈ 23sp/line) and pad
                // the top by 4dp so single/empty descriptions don't shift the
                // card metrics.
                Text(
                    text = overview,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = ContinuumSecondaryText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .height(42.dp),
                )
            }
        }
    }
}

@Composable
private fun NowViewingTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(Color.White)
            .padding(horizontal = 4.dp, vertical = 1.5.dp),
    ) {
        Text(
            text = "NOW VIEWING",
            style = capsuleCaps.copy(
                fontSize = 13.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.8.sp,
            ),
            color = Color.Black,
        )
    }
}

private fun episodeEyebrow(episode: EpisodeListItem): String {
    val base = "EPISODE ${episode.episodeNumber}"
    if (episode.runtime <= 0) return base
    val runtime = if (episode.runtime >= 60) {
        "${episode.runtime / 60}h ${episode.runtime % 60}m"
    } else {
        "${episode.runtime}m"
    }
    return "$base  ·  $runtime"
}

private fun EpisodeListItem.progressFraction(): Float? {
    val user = userData ?: return null
    val pos = user.positionSeconds ?: return null
    val dur = user.durationSeconds ?: return null
    if (dur <= 0 || pos <= 0 || pos >= dur) return null
    return (pos / dur).toFloat().coerceIn(0f, 1f)
}
