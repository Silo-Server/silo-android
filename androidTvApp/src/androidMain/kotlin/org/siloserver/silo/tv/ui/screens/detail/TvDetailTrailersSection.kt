package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.ItemExtra
import org.siloserver.silo.model.catalog.ItemVideo
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus

internal sealed interface TvDetailTrailerEntry {
    val key: String
    val title: String
    val kind: String

    data class Remote(val video: ItemVideo) : TvDetailTrailerEntry {
        override val key = "remote:${video.site}:${video.siteKey}"
        override val title = video.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: trailerKindLabel(video.kind)
        override val kind = video.kind
    }

    data class Local(val extra: ItemExtra) : TvDetailTrailerEntry {
        override val key = "local:${extra.contentId}"
        override val title = extra.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: trailerKindLabel(extra.kind)
        override val kind = extra.kind
    }
}

internal fun tvDetailTrailerEntries(detail: ItemDetail): List<TvDetailTrailerEntry> = buildList {
    detail.videos.orEmpty()
        .filter { it.site.equals("youtube", ignoreCase = true) && it.siteKey.isNotBlank() }
        .forEach { add(TvDetailTrailerEntry.Remote(it)) }
    detail.extras.orEmpty().forEach { add(TvDetailTrailerEntry.Local(it)) }
}.distinctBy(TvDetailTrailerEntry::key)

@Composable
internal fun TvDetailTrailersSection(
    entries: List<TvDetailTrailerEntry>,
    onSelectRemote: (ItemVideo) -> Unit,
    onSelectLocal: (ItemExtra) -> Unit,
    modifier: Modifier = Modifier,
    onDirectionUp: (() -> Boolean)? = null,
) {
    if (entries.isEmpty()) return

    val listState = rememberLazyListState()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        TvDetailSectionHeader(
            title = "Trailers & More",
            modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
        )
        TvRailScrollBehavior {
            LazyRow(
                state = listState,
                modifier = Modifier
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
                    .focusGroup(),
                contentPadding = PaddingValues(
                    horizontal = TvDetailHorizontalInset,
                    vertical = 6.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entry.key },
                    contentType = { _, entry -> if (entry is TvDetailTrailerEntry.Remote) "remote-trailer" else "local-extra" },
                ) { index, entry ->
                    TvDetailTrailerCard(
                        entry = entry,
                        onClick = {
                            when (entry) {
                                is TvDetailTrailerEntry.Remote -> onSelectRemote(entry.video)
                                is TvDetailTrailerEntry.Local -> onSelectLocal(entry.extra)
                            }
                        },
                        modifier = Modifier.tvRailPinOnFocus(listState, index, TvDetailHorizontalInset),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvDetailTrailerCard(
    entry: TvDetailTrailerEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(180),
        label = "trailerCardScale",
    )
    val cardShape = RoundedCornerShape(9.dp)

    Column(
        modifier = modifier
            .width(235.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .width(235.dp)
                .height(132.dp)
                .shadow(if (isFocused) 16.dp else 7.dp, cardShape, clip = false)
                .clip(cardShape)
                .background(DarkSurfaceElevated)
                .then(
                    if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.94f), cardShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (entry) {
                is TvDetailTrailerEntry.Remote -> AsyncImage(
                    model = "https://i.ytimg.com/vi/${entry.video.siteKey}/hqdefault.jpg",
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                is TvDetailTrailerEntry.Local -> Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = SiloSecondaryText,
                    modifier = Modifier.size(30.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (isFocused) 0.72f else 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Text(
            text = entry.title,
            color = if (isFocused) SiloOnSurface else SiloOnSurface.copy(alpha = 0.92f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailerSecondaryLine(entry)?.let { secondary ->
            Text(
                text = secondary,
                color = SiloSecondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private fun trailerSecondaryLine(entry: TvDetailTrailerEntry): String? = when (entry) {
    is TvDetailTrailerEntry.Remote -> "YouTube"
    is TvDetailTrailerEntry.Local -> entry.extra.durationSeconds
        ?.takeIf { it > 0 }
        ?.let(::formatTrailerDuration)
}

private fun formatTrailerDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun trailerKindLabel(kind: String): String = when (kind.lowercase()) {
    "trailer" -> "Trailer"
    "teaser" -> "Teaser"
    "featurette" -> "Featurette"
    "behind_the_scenes", "behind-the-scenes" -> "Behind the Scenes"
    "deleted_scene", "deleted-scene" -> "Deleted Scene"
    "interview" -> "Interview"
    else -> kind.replace('_', ' ').replace('-', ' ').trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { "Extra" }
}
