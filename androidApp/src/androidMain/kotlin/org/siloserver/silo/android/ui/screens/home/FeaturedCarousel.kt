package org.siloserver.silo.android.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.PillShape
import org.siloserver.silo.android.ui.util.playbackResumePosition
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.SectionItem
import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Hero carousel rendered at the top of the Home screen.
 *
 * Mirrors iOS `FeaturedCarousel` (FeaturedCarousel.swift): a centered deck
 * of landscape cards with rounded corners. Inactive cards peek to either
 * side at reduced scale and opacity, the active card sits on top with full
 * emphasis, and a soft gradient on the artwork keeps the bottom-aligned
 * title block legible. The page-level blurred backdrop owned by the parent
 * [HomeScreen] continues to bleed past the cards and behind the rest of
 * the screen.
 *
 * @param onActiveBackdropChange invoked with the active hero's backdrop URL
 *  + thumbhash whenever the page changes, so the parent can update its
 *  blurred page-level backdrop.
 */
@Composable
fun FeaturedCarousel(
    items: List<SectionItem>,
    onPlayClick: (String, Double?) -> Unit,
    onInfoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onActiveBackdropChange: ((url: String?, thumbhash: String?) -> Unit)? = null,
    topInset: androidx.compose.ui.unit.Dp = 16.dp,
) {
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.toFloat()

    // Match iOS metrics: card width is screen minus 32pt margin (capped at 780),
    // and height is 84% of screen width clamped between 300–390.
    val cardWidth = (screenWidthDp - 32f).coerceAtMost(780f).dp
    val cardHeight = (screenWidthDp * 0.84f).coerceIn(300f, 390f).dp
    val sideInset = ((screenWidthDp - cardWidth.value) / 2f).coerceAtLeast(16f).dp
    val cardCornerRadius = 28.dp

    LaunchedEffect(pagerState, items.size) {
        if (items.size > 1) {
            while (true) {
                delay(8000)
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    if (onActiveBackdropChange != null) {
        LaunchedEffect(pagerState, items) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    val item = items.getOrNull(page) ?: return@collect
                    onActiveBackdropChange(item.backdropUrl, item.backdropThumbhash)
                }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(topInset))

        HorizontalPager(
            state = pagerState,
            pageSize = PageSize.Fixed(cardWidth),
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = sideInset),
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight),
        ) { page ->
            val item = items[page]
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            ).absoluteValue.coerceIn(0f, 1f)
            val emphasis = 1f - pageOffset

            FeaturedCard(
                item = item,
                emphasis = emphasis,
                cornerRadius = cardCornerRadius,
                onPlayClick = { onPlayClick(item.contentId, playbackResumePosition(item)) },
                onInfoClick = { onInfoClick(item.contentId) },
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight),
            )
        }

        if (items.size > 1) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, _ ->
                    val isSelected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 22.dp else 8.dp,
                        animationSpec = tween(300),
                        label = "dotWidth",
                    )
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width)
                            .clip(PillShape)
                            .background(
                                if (isSelected) Color.White
                                else Color.White.copy(alpha = 0.34f),
                            ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FeaturedCard(
    item: SectionItem,
    emphasis: Float,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(cornerRadius)

    // iOS card emphasis curve: inactive cards drop to 0.92 scale, 0.46 opacity,
    // and shift 18pt down. Active card snaps to full scale/opacity at emphasis = 1.
    val inactiveScale = 0.92f
    val inactiveOpacity = 0.46f
    val inactiveYOffsetDp = 18f

    Box(
        modifier = modifier
            .graphicsLayer {
                val scale = inactiveScale + (1f - inactiveScale) * emphasis
                scaleX = scale
                scaleY = scale
                alpha = inactiveOpacity + (1f - inactiveOpacity) * emphasis
                translationY = inactiveYOffsetDp.dp.toPx() * (1f - emphasis)
            }
            .clip(shape)
            .clickable(enabled = emphasis > 0.5f) { onInfoClick() },
    ) {
        ThumbhashImage(
            url = item.backdropUrl,
            thumbhash = item.backdropThumbhash,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Card overlay: vertical darkening for title legibility plus a leading
        // horizontal scrim so copy stays readable on bright artwork.
        // Mirrors iOS `cardOverlay` lines 466–502.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.34f to Color.Black.copy(alpha = 0.26f),
                        0.78f to Color.Black.copy(alpha = 0.72f),
                        1.0f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black.copy(alpha = 0.62f),
                        0.38f to Color.Black.copy(alpha = 0.18f),
                        0.78f to Color.Transparent,
                    ),
                ),
        )

        FeaturedCardContent(
            item = item,
            visibility = emphasis,
            onPlayClick = onPlayClick,
            onInfoClick = onInfoClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
        )

        // Hairline border on top of the clipped content. Mirrors iOS
        // `shape.strokeBorder(Color.white.opacity(0.08 + 0.10 * emphasis))`.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = (0.75f + 0.25f * emphasis).dp,
                    color = Color.White.copy(alpha = 0.08f + 0.10f * emphasis),
                    shape = shape,
                ),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FeaturedCardContent(
    item: SectionItem,
    visibility: Float,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.graphicsLayer { alpha = visibility.coerceIn(0f, 1f) },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val eyebrow = remember(item) { eyebrowFor(item) }
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.76f),
                letterSpacing = 1.0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!item.logoUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = item.logoUrl,
                thumbhash = null,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                transparent = true,
                modifier = Modifier
                    .height(64.dp)
                    .widthIn(max = 240.dp),
            )
        } else {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                letterSpacing = (-0.5).sp,
            )
        }

        if (!item.overview.isNullOrBlank()) {
            Text(
                text = item.overview!!,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val chips = remember(item) { featuredHeroMetadata(item) }
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxLines = 2,
            ) {
                chips.forEach { chip -> MetadataChip(chip) }
            }
        }

        FeaturedActionRow(
            item = item,
            onPlayClick = onPlayClick,
            onInfoClick = onInfoClick,
        )
    }
}

@Composable
private fun MetadataChip(chip: FeaturedHeroMetadataChip) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(PillShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(
                width = 0.8.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = PillShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (chip.kind == FeaturedHeroMetadataKind.Rating) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = Color(0xFFFFCA28),
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 1,
        )
    }
}

@Composable
private fun FeaturedActionRow(
    item: SectionItem,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
) {
    val posSeconds = item.positionSeconds
    val durSeconds = item.durationSeconds
    val resumeProgress: Float? =
        if (posSeconds != null && durSeconds != null && durSeconds > 0 && posSeconds > 60) {
            (posSeconds / durSeconds).toFloat().coerceIn(0.01f, 0.99f)
        } else null
    val remainingMinutes: Int? = if (resumeProgress != null && posSeconds != null && durSeconds != null) {
        ((durSeconds - posSeconds) / 60.0).toInt().coerceAtLeast(1)
    } else null

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            Button(
                onClick = onPlayClick,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (remainingMinutes != null) "Resume · ${remainingMinutes}m left" else "Play",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (resumeProgress != null) {
                LinearProgressIndicator(
                    progress = { resumeProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .clip(PillShape),
                    color = Color.Black.copy(alpha = 0.78f),
                    trackColor = Color.Black.copy(alpha = 0.22f),
                )
            }
        }
        OutlinedButton(
            onClick = onInfoClick,
            shape = PillShape,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Black.copy(alpha = 0.42f),
                contentColor = Color.White,
            ),
            border = BorderStroke(
                width = 0.8.dp,
                color = Color.White.copy(alpha = 0.18f),
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "More Info",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun eyebrowFor(item: SectionItem): String? {
    if (!item.type.equals("episode", ignoreCase = true)) return null
    val seriesTitle = item.seriesTitle
    return if (!seriesTitle.isNullOrBlank()) seriesTitle else null
}
