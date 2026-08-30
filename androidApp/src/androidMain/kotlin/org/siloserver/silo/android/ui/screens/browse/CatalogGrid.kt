package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyAnomalyLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyCollection
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
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
    // The session density picks the base cell; the server-driven poster-size
    // preference multiplies it, shifting the adaptive column count.
    val cardWidth = viewDensity.minCardWidth * LocalCardPresentation.current.posterSize.posterScale
    val diagnosticsKeySnapshot = remember(items) {
        DiagnosticsListSnapshot.fromKeys(items.map { it.contentId })
    }
    LaunchedEffect(diagnosticsKeySnapshot) {
        DiagnosticsKeyAnomalyLogger.snapshot(
            DiagnosticsKeyCollection.PHONE_CATALOG_GRID,
            diagnosticsKeySnapshot,
        )
    }

    // Trigger load more when scrolled near bottom. Keyed on the flags: a
    // keyless remember would freeze their first-composition values inside the
    // derived lambda (they are plain params, not snapshot state).
    val shouldLoadMore by remember(hasMore, isLoadingMore) {
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
        DeferImagePresentationWhileScrolling(gridState) {
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
        }

        onNamePrefixSelected?.let { onSelected ->
            CatalogLetterIndex(
                selectedNamePrefix = selectedNamePrefix,
                onNamePrefixSelected = onSelected,
                revealWhileScrolling = gridState.isScrollInProgress,
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
private const val IndexAutoHideMillis = 1_400L
private val IndexTabWidth = 22.dp
private val IndexTabHeight = 64.dp
private val IndexRailWidth = 26.dp
private val IndexBubbleSize = 64.dp
private val IndexPullThreshold = 24.dp

/**
 * Trailing-edge name-prefix index.
 *
 * At rest a small "pull tab" sits half-docked on the edge (showing the
 * active letter, or "A–Z"). Drag it leftward and it stretches like a drop
 * as you pull; past [IndexPullThreshold] the rail springs open with a little
 * overshoot and, without lifting, the same finger scrubs up and down the
 * letters with a preview bubble — applied on release. A tap on the tab opens
 * the rail for direct letter taps. The rail also fades in while the grid is
 * scrolling ([revealWhileScrolling]) so it is easy to discover, and tucks
 * away after a moment of no interaction.
 */
@Composable
private fun CatalogLetterIndex(
    selectedNamePrefix: String?,
    onNamePrefixSelected: (String?) -> Unit,
    revealWhileScrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pullThresholdPx = with(density) { IndexPullThreshold.toPx() }
    val railWidthPx = with(density) { (IndexRailWidth + 8.dp).toPx() }

    // 0 = tucked away, 1 = fully open. Driven by the pull while dragging,
    // then animated to a resting state.
    val railProgress = remember { Animatable(0f) }
    var open by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var previewPrefix by remember { mutableStateOf<String?>(null) }
    var railHeightPx by remember { mutableIntStateOf(0) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val currentOnSelected by rememberUpdatedState(onNamePrefixSelected)

    fun prefixAt(y: Float): String? {
        if (railHeightPx <= 0) return null
        val slot = railHeightPx.toFloat() / CatalogLetterOptions.size
        val index = (y / slot).toInt().coerceIn(0, CatalogLetterOptions.lastIndex)
        return CatalogLetterOptions[index]
    }

    fun openRail() {
        open = true
        interactionTick++
        scope.launch {
            railProgress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    fun closeRail() {
        open = false
        scope.launch { railProgress.animateTo(0f, tween(durationMillis = 220)) }
    }

    // Reveal while the grid scrolls; tuck away once everything is quiet.
    LaunchedEffect(revealWhileScrolling) {
        if (revealWhileScrolling && !open) openRail()
    }
    LaunchedEffect(open, scrubbing, pulling, revealWhileScrolling, interactionTick) {
        if (open && !scrubbing && !pulling && !revealWhileScrolling) {
            delay(IndexAutoHideMillis)
            closeRail()
        }
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

        // The rail: slides in from the edge as railProgress rises.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(IndexRailWidth)
                .height(railHeight)
                .graphicsLayer {
                    val p = railProgress.value.coerceIn(0f, 1.2f)
                    translationX = (1f - p) * railWidthPx
                    alpha = p.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .onSizeChanged { railHeightPx = it.height },
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

        // The pull tab + gesture surface. Drag left to pull the rail open
        // (stretching like a drop), keep dragging vertically to scrub; tap
        // to open. The tab's own patch of the edge is excluded from the
        // system back gesture (small regions are allowed) so a touch that
        // starts on the tab is ours; the rest of the edge stays the OS's.
        var pullPx by remember { mutableStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(IndexRailWidth + 12.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            pulling = true
                            pullPx = 0f
                            interactionTick++
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            if (!open) {
                                pullPx = (pullPx - drag.x).coerceAtLeast(0f)
                                val p = (pullPx / pullThresholdPx).coerceIn(0f, 1f)
                                scope.launch { railProgress.snapTo(p) }
                                // Open once pulled far enough — or as soon as the
                                // finger turns vertical with the rail mostly out,
                                // so nobody has to hunt for the exact distance.
                                val turnedVertical = p > 0.4f && kotlin.math.abs(drag.y) > kotlin.math.abs(drag.x)
                                if (pullPx >= pullThresholdPx || turnedVertical) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    openRail()
                                    scrubbing = true
                                    previewPrefix = prefixAt(change.position.y)
                                }
                            } else {
                                if (!scrubbing) {
                                    scrubbing = true
                                    previewPrefix = prefixAt(change.position.y)
                                }
                                val next = prefixAt(change.position.y)
                                if (next != previewPrefix) {
                                    previewPrefix = next
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = {
                            pulling = false
                            if (scrubbing) {
                                currentOnSelected(previewPrefix)
                                scrubbing = false
                            } else if (!open) {
                                // A decent tug that stopped short still opens the
                                // rail for tapping; a nudge snaps the tab back.
                                if (railProgress.value > 0.4f) openRail()
                                else scope.launch { railProgress.animateTo(0f, spring(dampingRatio = 0.5f)) }
                            }
                            pullPx = 0f
                            interactionTick++
                        },
                        onDragCancel = {
                            pulling = false
                            scrubbing = false
                            pullPx = 0f
                            if (!open) scope.launch { railProgress.animateTo(0f) }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // The zone sits over the rail, so it owns taps too: on the
                    // open rail a tap picks the letter under it (the rail and
                    // zone share the same height, so y maps directly); on the
                    // closed tab a tap opens the rail.
                    detectTapGestures(
                        onTap = { offset ->
                            if (open) {
                                currentOnSelected(prefixAt(offset.y))
                                interactionTick++
                            } else {
                                openRail()
                            }
                        },
                    )
                },
        )

        // The tab: half-docked pill on the edge that stretches as it is
        // pulled and hides once the rail is open.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .systemGestureExclusion()
                .graphicsLayer {
                    val p = railProgress.value.coerceIn(0f, 1f)
                    val stretch = 1f + 0.35f * p
                    translationX = -pullPx * 0.6f
                    scaleY = stretch
                    scaleX = 1f - 0.2f * p
                    alpha = if (open) (1f - p) else 1f
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .width(IndexTabWidth)
                .height(IndexTabHeight)
                .clip(RoundedCornerShape(topStart = 11.dp, bottomStart = 11.dp))
                .background(
                    if (selectedNamePrefix != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = selectedNamePrefix ?: "A\nZ",
                color = if (selectedNamePrefix != null) MaterialTheme.colorScheme.background
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = if (selectedNamePrefix != null) 12.sp else 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
