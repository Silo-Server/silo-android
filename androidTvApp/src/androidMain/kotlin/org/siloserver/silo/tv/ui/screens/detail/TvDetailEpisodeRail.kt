package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.tv.ui.components.TvMediaCardActions
import org.siloserver.silo.tv.ui.components.TvMediaCardContextMenu
import org.siloserver.silo.tv.ui.components.tvEpisodeCardWidth
import org.siloserver.silo.tv.ui.theme.TvRailScrollBehavior
import org.siloserver.silo.tv.ui.theme.tvRailPinOnFocus
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.ProgressFill
import org.siloserver.silo.tv.ui.theme.capsuleCaps
import org.siloserver.silo.tv.ui.theme.cardScaled

/**
 * Horizontal rail of episode cards for the series/season/episode detail
 * screens — a direct port of tvOS `TVEpisodeRail`. Pressing OK on a card
 * navigates to that episode's own detail page (where the user picks a
 * version, marks watched and starts playback). Container detail pages may use
 * the rail as a browser; the combined Series page uses OK as direct playback,
 * matching tvOS without pushing a second episode-detail page.
 *
 * When `currentContentId` is non-null the matching card is highlighted
 * (white 2dp ring + full-color title), scrolled to the horizontal center
 * on first appearance, and made the default focus target so d-padding down
 * into the rail lands on the episode the user is already viewing.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun TvDetailEpisodeRail(
    episodes: List<EpisodeListItem>,
    onEpisodeSelected: (EpisodeListItem) -> Unit,
    favoriteStates: Map<String, Boolean>,
    onSetWatched: (contentId: String, watched: Boolean) -> Unit,
    onSetFavorite: (contentId: String, favorite: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    currentContentId: String? = null,
    onEpisodeFocused: ((EpisodeListItem) -> Unit)? = null,
    onDirectionUp: (() -> Boolean)? = null,
    hidesEpisodeTitle: Boolean = false,
    usesSeriesGeometry: Boolean = false,
) {
    if (episodes.isEmpty()) return

    val listState = rememberLazyListState()
    val currentIndex = remember(currentContentId, episodes) {
        episodes.indexOfFirst { it.contentId == currentContentId }.takeIf { it >= 0 }
    }
    // The first entry uses next-up/current. Once the viewer browses sideways,
    // retain that exact episode for every Up/Down round-trip through the
    // selector and supporting rails. Keying by the first episode resets this
    // memory when a different season's rail replaces the current one.
    val episodeSetKey = episodes.first().contentId
    var rememberedFocusedContentId by remember(episodeSetKey) { mutableStateOf<String?>(null) }
    val entryIndex = remember(rememberedFocusedContentId, currentContentId, episodes) {
        val targetId = rememberedFocusedContentId
            ?.takeIf { remembered -> episodes.any { it.contentId == remembered } }
            ?: currentContentId
        episodes.indexOfFirst { it.contentId == targetId }.takeIf { it >= 0 }
    }
    // Default focus target: the remembered episode, falling back to current.
    // Mirrors tvOS's focusedEpisodeContentId plus its suggestedEpisode entry.
    val defaultFocusRequester = remember { FocusRequester() }

    // Auto-center the suggested episode ONCE when this season's rail appears
    // (parity with tvOS `scrollTo(..., anchor: .center)`). Do not key this on
    // currentContentId: Series focus intentionally updates that id for every
    // Left/Right step. Re-running this centering animation would race the
    // one-shot tvRailPinOnFocus glide and make Right visually travel backward
    // even though focus reached the correct episode.
    LaunchedEffect(episodeSetKey) {
        val target = currentIndex ?: return@LaunchedEffect
        listState.scrollToItem(target)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        listState.animateScrollBy(itemCenter - viewportCenter)
    }

    TvRailScrollBehavior {
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
                if (entryIndex != null) {
                    Modifier.focusProperties { enter = { defaultFocusRequester } }
                } else {
                    Modifier
                },
            ),
        state = listState,
        contentPadding = PaddingValues(
            horizontal = TvDetailHorizontalInset,
            // Compact Series padding keeps the lowered episode band within the
            // fixed primary viewport beneath the selector and season controls.
            vertical = if (usesSeriesGeometry) 6.dp else 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (usesSeriesGeometry) 16.dp else 18.dp),
    ) {
        itemsIndexed(
            episodes,
            key = { _, episode -> episode.contentId },
            contentType = { _, _ -> "episode-card" },
        ) { index, episode ->
            val isCurrent = episode.contentId == currentContentId
            val isEntryTarget = index == entryIndex
            TvDetailEpisodeCard(
                episode = episode,
                isCurrent = isCurrent,
                isFavorite = favoriteStates[episode.contentId] ?: false,
                onClick = { onEpisodeSelected(episode) },
                onSetWatched = { watched -> onSetWatched(episode.contentId, watched) },
                onSetFavorite = { favorite -> onSetFavorite(episode.contentId, favorite) },
                hidesEpisodeTitle = hidesEpisodeTitle,
                usesSeriesGeometry = usesSeriesGeometry,
                modifier = Modifier
                    .tvRailPinOnFocus(listState, index, TvDetailHorizontalInset)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            rememberedFocusedContentId = episode.contentId
                            onEpisodeFocused?.invoke(episode)
                        }
                    }
                    .then(
                        if (isEntryTarget) {
                            Modifier.focusRequester(defaultFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TvDetailEpisodeCard(
    episode: EpisodeListItem,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onSetFavorite: (Boolean) -> Unit,
    hidesEpisodeTitle: Boolean,
    usesSeriesGeometry: Boolean,
    modifier: Modifier = Modifier,
) {
    // The combined Series page uses the exact same card footprint as Home's
    // Continue Watching rail. Legacy season/episode routes keep their existing
    // geometry so this targeted parity change cannot disturb those layouts.
    val cardWidth = if (usesSeriesGeometry) tvEpisodeCardWidth() else 230.dp.cardScaled()
    val stillHeight = if (usesSeriesGeometry) cardWidth * (9f / 16f) else 130.dp.cardScaled()
    val eyebrowFontSize = if (usesSeriesGeometry) 11.sp else 14.sp
    val eyebrowLineHeight = if (usesSeriesGeometry) 14.sp else 17.sp
    val caption = LocalCardPresentation.current.caption
    val cornerRadius = if (usesSeriesGeometry) 8.dp else 5.dp
    val shape = RoundedCornerShape(cornerRadius)

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var menuExpanded by remember(episode.contentId) { mutableStateOf(false) }
    val actions = remember(episode.contentId, onSetWatched, onSetFavorite) {
        TvMediaCardActions(
            onSetWatched = onSetWatched,
            onToggleFavorite = onSetFavorite,
        )
    }

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
            // Continue Watching lifts only its artwork; keep the inline label
            // locked while the focused still hovers above it.
            .then(if (usesSeriesGeometry) Modifier else Modifier.scale(scale))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            ),
        verticalArrangement = Arrangement.spacedBy(if (usesSeriesGeometry) 7.dp else 9.dp),
    ) {
        TvMediaCardContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = actions,
            isPlayed = episode.userData?.played == true,
            isFavorite = isFavorite,
            isInWatchlist = false,
        )

        // Still
        Box(
            modifier = Modifier
                .width(cardWidth)
                .height(stillHeight)
                .then(if (usesSeriesGeometry) Modifier.scale(scale) else Modifier)
                .shadow(
                    elevation = if (isFocused && usesSeriesGeometry) 12.dp else if (isFocused) 18.dp else 8.dp,
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
                    tint = SiloSecondaryText,
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

        // Series keeps the compact tvOS-style single caption line:
        // "EPISODE 1 · Episode name". Other episode rails retain their richer
        // title/metadata/synopsis hierarchy.
        if (caption.showsTitle) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "EPISODE ${episode.episodeNumber}",
                        fontSize = eyebrowFontSize,
                        lineHeight = eyebrowLineHeight,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = if (usesSeriesGeometry) 0.55.sp else 0.75.sp,
                        color = SiloOnSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                    if (hidesEpisodeTitle) {
                        episode.title?.takeIf { it.isNotBlank() }?.let { inlineTitle ->
                            Text(
                                text = "·",
                                fontSize = eyebrowFontSize,
                                lineHeight = eyebrowLineHeight,
                                fontWeight = FontWeight.Bold,
                                color = SiloOnSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                            Text(
                                text = inlineTitle,
                                modifier = Modifier.weight(1f),
                                fontSize = 11.5.sp,
                                lineHeight = eyebrowLineHeight,
                                fontWeight = FontWeight.SemiBold,
                                color = SiloOnSurface.copy(alpha = 0.86f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else if (isCurrent) {
                        NowViewingTag()
                    }
                }

                if (!hidesEpisodeTitle) {
                    Text(
                        text = episode.title ?: "Episode ${episode.episodeNumber}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            isCurrent -> SiloOnSurface
                            isFocused -> SiloOnSurface
                            else -> SiloOnSurface.copy(alpha = 0.92f)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!hidesEpisodeTitle && caption.showsMetadata) {
                    episodeMetadataLine(episode)?.let { metadata ->
                        Text(
                            text = metadata,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = SiloOnSurface.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // tvOS uses lineLimit(3, reservesSpace: true): always reserve
                    // exactly 3 text lines (minLines/maxLines rather than a fixed dp
                    // clamp so accessibility text scaling can't clip glyphs), and
                    // render even when there is no overview so every card keeps
                    // identical vertical metrics.
                    Text(
                        text = episode.overview?.takeIf { it.isNotBlank() }.orEmpty(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        color = SiloSecondaryText,
                        minLines = 3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = "NOW VIEWING",
            style = capsuleCaps.copy(
                fontSize = 14.sp,
                lineHeight = 17.sp,
                letterSpacing = 0.55.sp,
            ),
            color = Color.Black,
        )
    }
}

private fun episodeMetadataLine(episode: EpisodeListItem): String? {
    val parts = buildList {
        abbreviatedEpisodeDate(episode.airDate)?.let(::add)
        episodeRuntime(episode.runtime)?.let(::add)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

private fun episodeRuntime(runtimeMinutes: Int): String? {
    if (runtimeMinutes <= 0) return null
    return if (runtimeMinutes >= 60) {
        "${runtimeMinutes / 60}h ${runtimeMinutes % 60}m"
    } else {
        "${runtimeMinutes}m"
    }
}

// Shared UTC-instant → device-local formatting (tvOS parity) so the card
// dates agree with the hero facts row.
private fun abbreviatedEpisodeDate(raw: String?): String? =
    TvDetailMetadata.abbreviatedDate(raw)

private fun EpisodeListItem.progressFraction(): Float? {
    val user = userData ?: return null
    val pos = user.positionSeconds ?: return null
    val dur = user.durationSeconds ?: return null
    if (dur <= 0 || pos <= 0 || pos >= dur) return null
    return (pos / dur).toFloat().coerceIn(0f, 1f)
}
