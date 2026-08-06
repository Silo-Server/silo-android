package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.tv.ui.theme.CardShadowColor
import org.siloserver.silo.tv.ui.theme.OutfitFamily
import org.siloserver.silo.tv.ui.theme.heroDisplay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HeroCardShape = RoundedCornerShape(17.dp)
private const val HOME_HERO_AUTO_ADVANCE_MS = 8_000L
private val HOME_HERO_TOP_INSET = 64.dp
private val HOME_HERO_INDICATOR_HEIGHT = 13.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeHeroCarousel(
    items: List<SectionItem>,
    onItemClick: (contentId: String) -> Unit,
    onPlayItem: (SectionItem) -> Unit = {},
    modifier: Modifier = Modifier,
    heroHeight: Dp = 300.dp,
    heroCardWidthFraction: Float = 0.82f,
    centerBias: Dp = 18.dp,
    autoFocus: Boolean = false,
    focusRequest: Int = 0,
    initialFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onDirectionDown: (() -> Boolean)? = null,
    onAutoFocusClaimed: () -> Unit = {},
    onFocusEntered: () -> Unit = {},
    onActiveItemChanged: (SectionItem) -> Unit = {},
) {
    // See TvCatalogGrid: a repeated key is fatal to a keyed lazy list. Every
    // index below addresses this list, not the caller's, so the card the
    // carousel reports as active is the card it actually drew.
    val uniqueItems = remember(items) { items.distinctBy { it.contentId } }
    if (uniqueItems.isEmpty()) return

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val internalInitialFocusRequester = remember { FocusRequester() }
    val targetInitialFocusRequester = initialFocusRequester ?: internalInitialFocusRequester
    var activeIndex by remember(uniqueItems.map { it.contentId }) { mutableIntStateOf(0) }
    var heroHasFocus by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(uniqueItems.map { it.contentId }) {
        activeIndex = activeIndex.coerceIn(0, uniqueItems.lastIndex)
        listState.scrollToItem(activeIndex)
        onActiveItemChanged(uniqueItems[activeIndex])
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { targetInitialFocusRequester.requestFocus() }
            onAutoFocusClaimed()
        }
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            runCatching { targetInitialFocusRequester.requestFocus() }
            onFocusEntered()
        }
    }

    LaunchedEffect(activeIndex) {
        onActiveItemChanged(uniqueItems[activeIndex])
        scope.launch { listState.animateScrollToItem(activeIndex) }
    }

    LaunchedEffect(activeIndex, heroHasFocus, uniqueItems.size) {
        if (heroHasFocus || uniqueItems.size <= 1) return@LaunchedEffect
        delay(HOME_HERO_AUTO_ADVANCE_MS)
        activeIndex = (activeIndex + 1) % uniqueItems.size
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(HOME_HERO_TOP_INSET + heroHeight + HOME_HERO_INDICATOR_HEIGHT)
            .onFocusChanged { state ->
                val next = state.hasFocus
                if (next && !heroHasFocus) {
                    onFocusEntered()
                }
                heroHasFocus = next
            },
    ) {
        val cardWidth = minOf(maxWidth * heroCardWidthFraction, 780.dp)
        val centeredInset = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        val startInset = (centeredInset - centerBias).coerceAtLeast(0.dp)
        val endInset = centeredInset + centerBias

        LazyRow(
            state = listState,
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = startInset, end = endInset),
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = HOME_HERO_TOP_INSET)
                .height(heroHeight),
        ) {
            itemsIndexed(
                // See TvMediaRow: a repeated contentId is fatal to a keyed
                // lazy list.
                uniqueItems,
                key = { _, item -> item.contentId },
                contentType = { _, _ -> "hero-card" },
            ) { index, item ->
                TvHomeHeroCard(
                    item = item,
                    active = index == activeIndex,
                    modifier = Modifier
                        .width(cardWidth)
                        .height(heroHeight),
                    focusRequester = if (index == 0) targetInitialFocusRequester else null,
                    downFocusRequester = downFocusRequester,
                    onDirectionDown = onDirectionDown,
                    onFocused = {
                        if (activeIndex != index) {
                            activeIndex = index
                        }
                    },
                    // OK plays/resumes (primary CTA, like the phone hero's Play
                    // button); long-press opens detail (the phone's Info).
                    onClick = { onPlayItem(item) },
                    onLongClick = { onItemClick(item.contentId) },
                )
            }
        }

        HeroPageIndicator(
            total = uniqueItems.size,
            activeIndex = activeIndex,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-2).dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TvHomeHeroCard(
    item: SectionItem,
    active: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onDirectionDown: (() -> Boolean)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val emphasis by animateFloatAsState(
        targetValue = when {
            isFocused -> 1f
            active -> 0.9f
            else -> 0.56f
        },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
        label = "homeHeroCardEmphasis",
    )
    val scale by animateFloatAsState(
        targetValue = when {
            isFocused -> 1f
            active -> 0.98f
            else -> 0.91f
        },
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "homeHeroCardScale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shape = HeroCardShape
                clip = false
                shadowElevation = if (isFocused) 21f else 0f
                ambientShadowColor = CardShadowColor
                spotShadowColor = CardShadowColor
            }
            .clip(HeroCardShape)
            .background(Color.Black)
            .border(
                border = BorderStroke(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) {
                        Color.White.copy(alpha = 0.98f)
                    } else {
                        Color.White.copy(alpha = 0.10f + (0.10f * emphasis))
                    },
                ),
                shape = HeroCardShape,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (downFocusRequester != null) {
                    Modifier.focusProperties { down = downFocusRequester }
                } else {
                    Modifier
                },
            )
            .then(
                if (onDirectionDown != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            onDirectionDown()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocused()
                }
            }
            .focusable(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        ThumbhashImage(
            url = heroArtworkUrl(item),
            thumbhash = heroArtworkThumbhash(item),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.04f),
                        0.36f to Color.Black.copy(alpha = 0.14f),
                        0.72f to Color.Black.copy(alpha = 0.68f),
                        1f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.72f),
                        0.42f to Color.Black.copy(alpha = 0.28f),
                        0.78f to Color.Transparent,
                    ),
                ),
        )

        AnimatedVisibility(
            visible = active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 360.dp),
            ) {
                item.seriesTitle
                    ?.takeIf { item.type.equals("episode", ignoreCase = true) }
                    ?.let { seriesTitle ->
                        Text(
                            text = seriesTitle.uppercase(),
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = OutfitFamily),
                            color = Color.White.copy(alpha = 0.74f),
                        )
                    }

                HeroTitle(item = item)

                item.overview
                    ?.takeIf { it.isNotBlank() }
                    ?.let { overview ->
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
        }
    }
}

@Composable
private fun HeroTitle(item: SectionItem) {
    if (!item.logoUrl.isNullOrBlank()) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
                modifier = Modifier
                .height(68.dp)
                .widthIn(max = 260.dp),
        )
    } else {
        Text(
            text = item.title,
            style = heroDisplay.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroPageIndicator(
    total: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (total <= 1) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .height(5.dp)
                    .width(if (index == activeIndex) 19.dp else 6.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .background(
                        if (index == activeIndex) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.28f)
                        },
                    ),
            )
        }
    }
}

private fun heroArtworkUrl(item: SectionItem): String? = item.backdropUrl ?: item.posterUrl

private fun heroArtworkThumbhash(item: SectionItem): String? =
    if (!item.backdropUrl.isNullOrBlank()) item.backdropThumbhash else item.posterThumbhash
