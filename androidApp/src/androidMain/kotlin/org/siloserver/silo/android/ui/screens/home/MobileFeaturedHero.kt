package org.siloserver.silo.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.SectionItem

/**
 * Full-bleed phone spotlight supplied by the server's featured Home section.
 * Its bottom scrim reaches the page background so the first row grows out of
 * the artwork without a rectangular banner edge.
 */
@Composable
fun MobileFeaturedHero(
    items: List<SectionItem>,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val configuration = LocalConfiguration.current
    val heroHeight = (configuration.screenHeightDp * 0.69f).coerceIn(610f, 740f).dp

    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        // Keep one stable loop. Keying this effect to currentPage cancels the
        // animation as soon as the pager crosses its halfway threshold,
        // leaving the carousel stranded between two items.
        while (true) {
            delay(10_000)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            // Keep the blurred cover layer from painting into the LazyColumn's
            // section gap. The internal gradient supplies the seamless fade.
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            SpotlightSlide(
                item = items[page],
                onPlayClick = onPlayClick,
                onInfoClick = onInfoClick,
            )
        }
    }
}

@Composable
private fun SpotlightSlide(
    item: SectionItem,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onInfoClick(item.contentId) },
        contentAlignment = Alignment.BottomStart,
    ) {
        val artworkUrl = item.backdropUrl ?: item.posterUrl
        val artworkHash = item.backdropThumbhash ?: item.posterThumbhash

        // A soft cover layer extends the artwork through the tall phone stage,
        // while the crisp layer uses only the upper 74%. The shorter crop shows
        // substantially more of a 16:9 backdrop instead of zooming it until the
        // subject disappears.
        ThumbhashImage(
            url = artworkUrl,
            thumbhash = artworkHash,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f
                    scaleY = 1.08f
                }
                .blur(26.dp),
        )
        ThumbhashImage(
            url = artworkUrl,
            thumbhash = artworkHash,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.74f),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.28f),
                        0.22f to Color.Transparent,
                        0.50f to Color.Black.copy(alpha = 0.32f),
                        0.78f to background.copy(alpha = 0.86f),
                        1.00f to background,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color.Black.copy(alpha = 0.49f),
                        0.55f to Color.Black.copy(alpha = 0.14f),
                        1.00f to Color.Transparent,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
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
                        .height(92.dp)
                        .widthIn(max = 270.dp)
                        .align(Alignment.CenterHorizontally),
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.2).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val metadata = remember(item) { featuredMetadata(item) }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onPlayClick(item.contentId, item.positionSeconds) },
                    shape = RoundedCornerShape(100),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
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
                    shape = RoundedCornerShape(100),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("More Info", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun featuredMetadata(item: SectionItem): List<String> = buildList {
    if (item.type.equals("episode", ignoreCase = true)) {
        if (item.seasonNumber != null && item.episodeNumber != null) {
            add("S${item.seasonNumber} E${item.episodeNumber}")
        }
    } else if (item.year > 0) {
        add(item.year.toString())
    }
    item.runtime?.takeIf { it > 0 }?.let { add(formatFeaturedRuntime(it)) }
    addAll(item.genres.filter { it.isNotBlank() }.take(2))
    item.contentRating?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
}

private fun formatFeaturedRuntime(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val remainder = minutes % 60
    return if (remainder == 0) "${minutes / 60}h" else "${minutes / 60}h ${remainder}m"
}
