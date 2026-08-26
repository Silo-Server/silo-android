package org.siloserver.silo.android.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.section.SectionItem

private const val HeroAdvanceMillis = 10_000
private const val HeroVirtualCycles = 1_000
private val HeroCardShape = RoundedCornerShape(14.dp)
private val HeroButtonShape = RoundedCornerShape(8.dp)

/**
 * Phone-only featured cards. The configured top featured row supplies the
 * cards and the dot count; duplicated virtual pages make swiping endless in
 * both directions without exposing duplicates to the indicator.
 */
@Composable
fun MobileFeaturedHero(
    items: List<SectionItem>,
    textlessPosterUrls: Map<String, String> = emptyMap(),
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    key(items.map { it.contentId }) {
        MobileFeaturedHeroContent(
            items = items,
            textlessPosterUrls = textlessPosterUrls,
            onPlayClick = onPlayClick,
            onInfoClick = onInfoClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun MobileFeaturedHeroContent(
    items: List<SectionItem>,
    textlessPosterUrls: Map<String, String>,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
    modifier: Modifier,
) {
    val configuration = LocalConfiguration.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerRunway = statusBar + 56.dp
    val cardWidth = (configuration.screenWidthDp - 24).dp
    val cardHeight = ((configuration.screenWidthDp - 24) * 1.10f).coerceIn(372f, 470f).dp
    val totalHeight = headerRunway + cardHeight + 38.dp

    // An Int.MAX_VALUE pager loses adjacent-page precision once its pixel
    // offset becomes huge. A centred thousand-cycle ring is functionally
    // endless to a person while keeping every snap on an exact card boundary.
    val virtualCount = if (items.size > 1) items.size * HeroVirtualCycles else 1
    val initialPage = remember(items.size) {
        if (items.size == 1) 0 else {
            val middle = virtualCount / 2
            middle - positiveModulo(middle, items.size)
        }
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { virtualCount },
    )
    val timerProgress = remember(items.size) { Animatable(0f) }
    // Keep the ten-second progress animation in the render phase. Reading the
    // Animatable from the parent composition would recompose the entire hero
    // every frame, including the pager and its full-size artwork.
    val timerProgressProvider = remember(timerProgress) { { timerProgress.value } }

    LaunchedEffect(pagerState, items.size) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collectLatest { (_, scrolling) ->
                timerProgress.snapTo(0f)
                if (!scrolling) {
                    timerProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(HeroAdvanceMillis, easing = LinearEasing),
                    )
                }
            }
    }
    LaunchedEffect(pagerState, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            val page = pagerState.settledPage
            delay(HeroAdvanceMillis.toLong())
            // A manual move during the interval starts a fresh ten seconds on
            // the next loop. Crucially, the auto animation itself is not owned
            // by a collectLatest block that cancels when scrolling begins.
            if (!pagerState.isScrollInProgress && pagerState.settledPage == page) {
                pagerState.animateScrollToPage(page + 1)
            }
        }
    }

    val activeIndex = positiveModulo(pagerState.settledPage, items.size)
    val activeItem = items[activeIndex]
    val activeArtwork = preferredArtwork(activeItem, textlessPosterUrls)
    val background = MaterialTheme.colorScheme.background
    val dominantColor by rememberDominantColor(activeArtwork, fallback = background)
    val darkTint = remember(dominantColor) {
        dominantColor.copy(
            red = dominantColor.red * 0.46f,
            green = dominantColor.green * 0.46f,
            blue = dominantColor.blue * 0.46f,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .background(
                Brush.verticalGradient(
                    0.00f to background,
                    0.66f to background,
                    0.88f to darkTint.copy(alpha = 0.24f),
                    1.00f to background,
                ),
            ),
    ) {
        // A deliberately dark sampled radial gradient makes the card colour
        // feel present beyond its edge. The gradient supplies its own soft edge;
        // avoiding a card-sized runtime blur keeps real devices smooth.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = headerRunway - 8.dp)
                .width(cardWidth + 18.dp)
                .height(cardHeight + 34.dp)
                .clip(HeroCardShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            darkTint.copy(alpha = 0.38f),
                            darkTint.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 10.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .offset(y = headerRunway),
        ) { virtualPage ->
            val item = items[positiveModulo(virtualPage, items.size)]
            SpotlightCard(
                item = item,
                artworkUrl = preferredArtwork(item, textlessPosterUrls),
                onPlayClick = onPlayClick,
                onInfoClick = onInfoClick,
                modifier = Modifier.fillMaxSize(),
            )
        }

        HeroTimerDots(
            count = items.size,
            activeIndex = activeIndex,
            progress = timerProgressProvider,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = headerRunway + cardHeight + 13.dp),
        )
    }
}

@Composable
private fun SpotlightCard(
    item: SectionItem,
    artworkUrl: String?,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(HeroCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color.White.copy(alpha = 0.08f), HeroCardShape)
            .clickable { onInfoClick(item.contentId) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        ThumbhashImage(
            url = artworkUrl,
            thumbhash = item.posterThumbhash ?: item.backdropThumbhash,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.05f),
                        0.46f to Color.Transparent,
                        0.66f to Color.Black.copy(alpha = 0.48f),
                        1.00f to Color.Black.copy(alpha = 0.96f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!item.logoUrl.isNullOrBlank()) {
                ThumbhashImage(
                    url = item.logoUrl,
                    thumbhash = null,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    transparent = true,
                    modifier = Modifier
                        .height(76.dp)
                        .widthIn(max = 220.dp),
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = remember(item) { featuredQuote(item) },
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            val metadata = remember(item) { featuredMetadata(item) }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { dispatchFeaturedHeroPlay(item, onPlayClick, onInfoClick) },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = HeroButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if ((item.positionSeconds ?: 0.0) > 60.0) "Resume" else "Play",
                        fontWeight = FontWeight.Bold,
                    )
                }

                Button(
                    onClick = { onInfoClick(item.contentId) },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = HeroButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.48f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("More Info", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HeroTimerDots(
    count: Int,
    activeIndex: Int,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            if (index == activeIndex) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.24f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                scaleX = progress().coerceIn(0f, 1f)
                            }
                            .background(Color.White.copy(alpha = 0.86f)),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.42f)),
                )
            }
        }
    }
}

/**
 * Keeps audiobook containers out of the video player. Home section items do
 * not carry the file id required by audiobook playback, so their primary hero
 * action opens details where the existing audiobook flow resolves the part.
 */
internal fun dispatchFeaturedHeroPlay(
    item: SectionItem,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
) {
    if (isAudiobookItemType(item.type)) {
        onInfoClick(item.contentId)
    } else {
        onPlayClick(item.contentId, item.positionSeconds)
    }
}

internal fun featuredQuote(item: SectionItem): String {
    item.tagline?.trim()?.takeIf { it.isNotEmpty() }?.let { return compactFeaturedQuote(it) }
    item.overview?.trim()?.takeIf { it.isNotEmpty() }?.let { overview ->
        val sentenceEnd = overview.indexOfFirst { it == '.' || it == '!' || it == '?' }
        val sentence = if (sentenceEnd >= 0) overview.substring(0, sentenceEnd + 1) else overview
        return compactFeaturedQuote(sentence)
    }
    return "Ready when you are."
}

private fun compactFeaturedQuote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 46) return trimmed

    val clauseEnd = trimmed.indexOfFirst { it == ',' || it == ';' }
    if (clauseEnd in 8..46) {
        val clause = trimmed.substring(0, clauseEnd).trim()
        if (clause.split(Regex("\\s+")).size >= 3) return clause
    }

    val words = mutableListOf<String>()
    for (word in trimmed.split(Regex("\\s+")).take(6)) {
        if ((words + word).joinToString(" ").length > 46) break
        words += word
    }
    return words.joinToString(" ").trim(' ', '.', ',', ';', ':', '!', '?')
        .ifEmpty { "Ready when you are." }
}

private fun featuredMetadata(item: SectionItem): List<String> = buildList {
    (item.ratingImdb ?: item.ratingTmdb)?.takeIf { it > 0.0 }?.let {
        add("★ ${"%.1f".format(it)}")
    }
    addAll(item.genres.filter { it.isNotBlank() }.take(2))
    item.runtime?.takeIf { it > 0 }?.let { add(formatFeaturedRuntime(it)) }
    item.contentRating?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
}

private fun formatFeaturedRuntime(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val remainder = minutes % 60
    return if (remainder == 0) "${minutes / 60}h" else "${minutes / 60}h ${remainder}m"
}

private fun preferredArtwork(
    item: SectionItem,
    textlessPosterUrls: Map<String, String>,
): String? = textlessPosterUrls[item.contentId]
    ?.takeIf { it.isNotBlank() }
    ?: item.posterUrl?.takeIf { it.isNotBlank() }
    ?: item.backdropUrl?.takeIf { it.isNotBlank() }

private fun positiveModulo(value: Int, modulus: Int): Int =
    ((value % modulus) + modulus) % modulus
