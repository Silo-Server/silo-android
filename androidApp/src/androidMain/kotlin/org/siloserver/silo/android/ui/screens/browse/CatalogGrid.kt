package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.overlays.OverlayDataExtractor

/**
 * A vertical grid of media cards with infinite-scroll support.
 *
 * Uses the shared iOS-style adaptive poster grid with automatic load-more
 * triggering when the user scrolls near the bottom. [header] is a spanning
 * row that scrolls with the grid (sort/filter controls); [topContentInset]
 * lets the grid start below floating chrome and scroll under it.
 *
 * When [onNamePrefixSelected] is given, an A–Z name-prefix index lives on
 * the trailing edge: hidden behind a small handle by default, press-and-hold
 * (or tap) slides it in and a drag along it picks a letter, shown in a
 * bubble; it slides away again once you let go.
 */
@Composable
fun CatalogGrid(
    items: List<BrowseItem>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onItemClick: (String) -> Unit,
    /** Per-card caption overriding the year (e.g. date for date sorts). */
    cardSubtitle: ((BrowseItem) -> String?)? = null,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    selectedNamePrefix: String? = null,
    onNamePrefixSelected: ((String?) -> Unit)? = null,
    viewDensity: CatalogViewDensity = CatalogViewDensity.Normal,
    bottomContentInset: Dp = 0.dp,
    topContentInset: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val cardWidth = viewDensity.minCardWidth

    // Trigger load more when scrolled near bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            hasMore && !isLoadingMore && lastVisibleItem >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    Box(modifier = modifier) {
        LazyVerticalGrid(
            // iOS phone: adaptive poster grid, 110pt minimum card width, 12pt
            // column spacing, 16pt row spacing, 16pt horizontal page padding.
            columns = GridCells.Adaptive(cardWidth),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp + topContentInset,
                // A little extra on the trailing side keeps the index handle
                // off the posters' edge.
                end = if (onNamePrefixSelected != null) 24.dp else 16.dp,
                bottom = 8.dp + bottomContentInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }) {
                    header()
                }
            }

            items(
                items = items,
                key = { it.contentId },
                contentType = { item -> item.type },
            ) { item ->
                val (actions, userState) = rememberBrowseItemCardActions(item)
                MediaCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    year = item.year,
                    subtitle = cardSubtitle?.invoke(item),
                    type = item.type,
                    userState = userState,
                    onClick = { onItemClick(item.contentId) },
                    width = cardWidth,
                    overlay = OverlayDataExtractor.fromBrowseItem(item),
                    actions = actions,
                )
            }

            // Loading indicator at bottom
            if (isLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        onNamePrefixSelected?.let { onSelected ->
            CatalogLetterIndex(
                selectedNamePrefix = selectedNamePrefix,
                onNamePrefixSelected = onSelected,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = topContentInset + 8.dp, bottom = bottomContentInset + 8.dp),
            )
        }
    }
}

// MARK: - A–Z index

private val CatalogLetterOptions: List<String?> = listOf(null) + ('A'..'Z').map { it.toString() }
private const val IndexAutoHideMillis = 1_600L
private val IndexHandleSize = 28.dp
private val IndexRailWidth = 26.dp
private val IndexBubbleSize = 64.dp

/**
 * Trailing-edge name-prefix index. At rest only a small round handle shows
 * (the active letter, or "A–Z"). Press-and-hold anywhere along the edge
 * slides the rail in and turns the hold into a scrub — drag up and down and
 * the letter under the finger is previewed in a bubble beside the rail,
 * applied on release. A plain tap on the handle toggles the rail for direct
 * letter taps. The rail slides away after a moment of no interaction.
 */
@Composable
private fun CatalogLetterIndex(
    selectedNamePrefix: String?,
    onNamePrefixSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var railVisible by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var previewPrefix by remember { mutableStateOf<String?>(null) }
    var railHeightPx by remember { mutableIntStateOf(0) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val currentOnSelected by rememberUpdatedState(onNamePrefixSelected)

    // Auto-hide once nothing has touched the index for a moment.
    LaunchedEffect(railVisible, scrubbing, interactionTick) {
        if (railVisible && !scrubbing) {
            delay(IndexAutoHideMillis)
            railVisible = false
        }
    }

    fun prefixAt(y: Float): String? {
        if (railHeightPx <= 0) return null
        val slot = railHeightPx.toFloat() / CatalogLetterOptions.size
        val index = (y / slot).toInt().coerceIn(0, CatalogLetterOptions.lastIndex)
        return CatalogLetterOptions[index]
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        val railHeight = maxHeight

        // Preview bubble while scrubbing, beside the rail.
        AnimatedVisibility(
            visible = scrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = IndexRailWidth + 20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(IndexBubbleSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = previewPrefix ?: "All",
                    color = MaterialTheme.colorScheme.background,
                    fontSize = if (previewPrefix == null) 16.sp else 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // The rail itself.
        AnimatedVisibility(
            visible = railVisible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .width(IndexRailWidth)
                    .height(railHeight)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .onSizeChanged { railHeightPx = it.height }
                    .pointerInput(Unit) {
                        // Direct taps once the rail is open.
                        detectTapGestures { offset ->
                            val prefix = prefixAt(offset.y)
                            currentOnSelected(prefix)
                            interactionTick++
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CatalogLetterOptions.forEach { prefix ->
                    val active = if (scrubbing) previewPrefix == prefix else selectedNamePrefix == prefix
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = prefix ?: "•",
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Edge touch zone: hold to open + scrub; tap the handle to toggle.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(IndexRailWidth + 8.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            railVisible = true
                            scrubbing = true
                            previewPrefix = prefixAt(offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val next = prefixAt(change.position.y)
                            if (next != previewPrefix) {
                                previewPrefix = next
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            currentOnSelected(previewPrefix)
                            scrubbing = false
                            interactionTick++
                        },
                        onDragCancel = { scrubbing = false },
                    )
                },
        )
        // A visible handle so the index is discoverable: the active letter,
        // or "A–Z" at rest. Sits over the edge zone so tapping it toggles.
        AnimatedVisibility(
            visible = !railVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(IndexHandleSize)
                    .clip(CircleShape)
                    .background(
                        if (selectedNamePrefix != null) MaterialTheme.colorScheme.onSurface
                        else Color.White.copy(alpha = 0.10f),
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { railVisible = true; interactionTick++ },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = selectedNamePrefix ?: "A–Z",
                    color = if (selectedNamePrefix != null) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (selectedNamePrefix != null) 12.sp else 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
