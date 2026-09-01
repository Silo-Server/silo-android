package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import org.siloserver.silo.android.ui.util.formatCardDate
import org.siloserver.silo.android.ui.util.playbackResumePosition
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.EpisodeListItem
import kotlin.math.abs

/**
 * PR #212's phone episode carousel: a 240dp card settles in the centre and,
 * when scrolling becomes idle, that centred episode becomes the series Play
 * and playback-selector target.
 */
@Composable
fun EpisodeList(
    episodes: List<EpisodeListItem>,
    onEpisodePlayClick: ((String, Double?) -> Unit)? = null,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeWatchedChange: ((String, Boolean) -> Unit)? = null,
    highlightContentId: String? = null,
    selectsCenteredEpisode: Boolean = false,
    /** Expanded tablet/fold cards include their full editorial caption. */
    showsEpisodeDetails: Boolean = false,
    /** Expanded rails select on tap and never force the card into centre. */
    tapToFocusEpisode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        val cardWidth = 240.dp
        val listState = rememberLazyListState()
        val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
        val currentEpisodes by rememberUpdatedState(episodes)
        val currentHighlightId by rememberUpdatedState(highlightContentId)
        val currentOnSelect by rememberUpdatedState(onEpisodeDetailClick)
        var wasScrolling by remember { mutableStateOf(false) }

        LaunchedEffect(episodes.map(EpisodeListItem::contentId), highlightContentId) {
            val selectedIndex = episodes.indexOfFirst { it.contentId == highlightContentId }
            if (selectedIndex >= 0) {
                val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
                val needsScroll = if (tapToFocusEpisode) {
                    !isVisible
                } else {
                    centeredItemIndex(listState) != selectedIndex
                }
                if (needsScroll) listState.animateScrollToItem(selectedIndex)
            }
        }

        // Do not act on the initial idle emission. A real scroll or centring
        // animation must finish before the visible card can select an episode.
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { isScrolling ->
                    if (isScrolling) {
                        wasScrolling = true
                    } else if (wasScrolling) {
                        wasScrolling = false
                        val episode = centeredItemIndex(listState)
                            ?.let(currentEpisodes::getOrNull)
                        if (
                            selectsCenteredEpisode &&
                            episode != null &&
                            episode.contentId != currentHighlightId
                        ) {
                            currentOnSelect(episode.contentId)
                        }
                    }
                }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = SafePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            flingBehavior = flingBehavior,
        ) {
            items(episodes, key = EpisodeListItem::contentId) { episode ->
                EpisodeRailCard(
                    episode = episode,
                    onPlayClick = onEpisodePlayClick?.let { play -> {
                        play(
                            episode.contentId,
                            playbackResumePosition(episode),
                        )
                    } },
                    onSelect = { onEpisodeDetailClick(episode.contentId) },
                    onWatchedChange = onEpisodeWatchedChange?.let { change ->
                        { watched -> change(episode.contentId, watched) }
                    },
                    isCurrent = episode.contentId == highlightContentId,
                    cardWidth = cardWidth,
                    showsEpisodeDetails = showsEpisodeDetails,
                )
            }
        }
    }
}

private fun centeredItemIndex(state: LazyListState): Int? {
    val layout = state.layoutInfo
    val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRailCard(
    episode: EpisodeListItem,
    onPlayClick: (() -> Unit)?,
    onSelect: () -> Unit,
    onWatchedChange: ((Boolean) -> Unit)?,
    isCurrent: Boolean,
    cardWidth: Dp,
    showsEpisodeDetails: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isWatched = episode.userData?.played == true
    val stillShape = RoundedCornerShape(8.dp)
    val progress = episodeProgressFraction(
        positionSeconds = episode.userData?.positionSeconds,
        durationSeconds = episode.userData?.durationSeconds,
    )

    Column(
        modifier = Modifier
            .width(cardWidth)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onWatchedChange?.let { { menuExpanded = true } },
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(stillShape)
                .border(
                    width = 2.dp,
                    color = if (isCurrent) Color.White.copy(alpha = 0.70f) else Color.Transparent,
                    shape = stillShape,
                ),
        ) {
            ThumbhashImage(
                url = episode.stillUrl,
                thumbhash = episode.stillThumbhash,
                contentDescription = episode.title,
                modifier = Modifier.fillMaxSize(),
            )

            if (episode.userData?.played == true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Watched",
                        tint = Color.Black,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            if (onPlayClick != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.94f))
                        .clickable(onClick = onPlayClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play Season ${episode.seasonNumber}, Episode ${episode.episodeNumber}",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            progress?.takeIf { it > 0f }?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color.White,
                    trackColor = Color.Black.copy(alpha = 0.60f),
                )
            }
        }

        Column(
            modifier = if (showsEpisodeDetails) Modifier.height(132.dp) else Modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "EPISODE ${episode.episodeNumber}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color.White.copy(alpha = 0.55f),
                )
                if (isCurrent) {
                    Text(
                        text = "NOW VIEWING",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.8.sp,
                        color = Color.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                            .padding(
                                horizontal = 5.dp,
                                vertical = if (showsEpisodeDetails) 1.dp else 2.dp,
                            ),
                    )
                }
            }
            if (showsEpisodeDetails) {
                Text(
                    text = episode.title?.takeIf { it.isNotBlank() }
                        ?: "Episode ${episode.episodeNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailPrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episodeMetadataLine(episode)?.let { metadata ->
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = DetailSecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = DetailSecondaryText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (isWatched) "Mark as Unwatched" else "Mark as Watched") },
                onClick = {
                    menuExpanded = false
                    onWatchedChange?.invoke(!isWatched)
                },
            )
        }
    }
}

internal fun episodeProgressFraction(positionSeconds: Double?, durationSeconds: Double?): Float? {
    val position = positionSeconds?.takeIf { it.isFinite() && it > 0 } ?: return null
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0 } ?: return null
    if (position >= duration) return null
    return (position / duration).toFloat().coerceIn(0f, 1f)
}

private fun episodeMetadataLine(episode: EpisodeListItem): String? = buildList {
    val airDate = formatCardDate(episode.airDate)
        ?: episode.airDate?.takeIf { it.isNotBlank() }
    if (airDate != null) add(airDate)
    if (episode.runtime > 0) add("${episode.runtime}m")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Static loading footprint matching the artwork and compact identity row. */
@Composable
internal fun EpisodeListSkeleton(
    showsEpisodeDetails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        val cardWidth = 240.dp
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = SafePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(3) { index ->
                Column(
                    modifier = Modifier.width(cardWidth),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.09f + index * 0.01f)),
                    )
                    EpisodeSkeletonLine(width = 82.dp, height = 9.dp)
                    if (showsEpisodeDetails) {
                        EpisodeSkeletonLine(width = 150.dp, height = 14.dp)
                        EpisodeSkeletonLine(width = 112.dp, height = 10.dp)
                        EpisodeSkeletonLine(width = 224.dp, height = 10.dp)
                        EpisodeSkeletonLine(width = 196.dp, height = 10.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeSkeletonLine(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.09f)),
    )
}

internal fun episodeNumberText(episode: EpisodeListItem): String =
    "S${episode.seasonNumber}·E${episode.episodeNumber}"
