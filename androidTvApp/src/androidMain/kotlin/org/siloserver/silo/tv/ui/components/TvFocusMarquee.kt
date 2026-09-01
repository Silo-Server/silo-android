package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText

/**
 * Passive Skyline billboard, anchored bottom-left, that previews whichever row
 * card currently holds focus. Faithful Android port of tvOS `TVFocusMarquee` /
 * `TVMarqueeBlock` (§5.4): logo-or-title, a badge + meta line, a 3-line
 * synopsis, and a quieter detail line. It is never focusable or hit-testable —
 * the rows below own all focus. Content crossfades (~240 ms) when the focused
 * item changes; the parent debounces the focus-rest by ~150 ms before swapping.
 *
 * Metrics are scaled ~0.5x from the tvOS 1920×1080 tokens to the Android TV
 * ~960dp layout, matching the rest of the Skyline chrome (see [TvSkyline]).
 */
@Composable
fun TvFocusMarquee(
    content: TvMarqueeContent?,
    detailLine: String? = content?.detailLine,
    modifier: Modifier = Modifier,
    startPadding: androidx.compose.ui.unit.Dp = 44.dp,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    animateTransition: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // topPadding reserves the top-menu-bar zone: the block is
            // bottom-anchored, so any growth (typography floors) pushes it
            // upward — without this clamp it renders beneath the bar.
            .padding(start = startPadding, top = topPadding, bottom = bottomPadding)
            .clipToBounds(),
        contentAlignment = Alignment.BottomStart,
    ) {
        // tvOS first-frame parity: the FIRST content snaps in with no
        // animation — a cold entry paints the finished marquee block instead
        // of fading it up. Focus-driven swaps keep the crossfade.
        var hasDisplayedContent by remember { mutableStateOf(false) }
        val snapInitialContent = content != null && !hasDisplayedContent
        LaunchedEffect(content != null) {
            if (content != null) hasDisplayedContent = true
        }
        if (animateTransition) {
            Crossfade(
                targetState = content,
                animationSpec = tween(
                    if (snapInitialContent) 0 else TvMarqueeCrossfadeMs,
                    easing = TvMarqueeEasing,
                ),
                label = "tvFocusMarquee",
                // Keep the transition viewport fixed. A wrapping Crossfade
                // animates its own measured size between differently tall hero
                // blocks, which moves bottom-anchored copy while it fades.
                modifier = Modifier.fillMaxSize(),
            ) { value ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    if (value != null) {
                        TvMarqueeBlock(
                            content = value,
                            detailLine = detailLine.takeIf { value.id == content?.id },
                        )
                    }
                }
            }
        } else if (content != null) {
            TvMarqueeBlock(content = content, detailLine = detailLine)
        }
    }
}

@Composable
private fun TvMarqueeBlock(
    content: TvMarqueeContent,
    detailLine: String?,
) {
    // tvOS parity (TVFocusMarquee): when the text-fallback title wraps to two
    // lines the synopsis drops to one, keeping the bottom-anchored block's
    // height bounded so it never climbs into the top-menu-bar zone.
    var titleLineCount by remember(content.id) { mutableStateOf(1) }
    var logoLoaded by remember(content.logoUrl) { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(
        targetValue = if (logoLoaded) 1f else 0f,
        animationSpec = tween(TvMarqueeCrossfadeMs, easing = TvMarqueeEasing),
        label = "marqueeLogoAlpha",
    )
    // 6dp rows and an 84dp logo slot: the marquee viewport (screen minus the
    // top-bar zone minus the row band) fits five text rows only if the block
    // stays under ~206dp, and the format spec line is the fifth row.
    Column(
        modifier = Modifier.widthIn(max = MarqueeContentWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Keep the semantic text title visible until transparent logo artwork
        // has actually decoded. A bad/slow URL therefore never creates a blank
        // title slot; successful artwork fades over the fixed-height fallback.
        if (!content.logoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .height(MarqueeLogoMaxHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!logoLoaded || logoAlpha < 1f) {
                    Text(
                        text = content.title,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = MarqueeTitleSize,
                        lineHeight = MarqueeTitleSize * 1.15f,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleLineCount = it.lineCount },
                        modifier = Modifier.alpha(1f - logoAlpha),
                    )
                }
                ThumbhashImage(
                    url = content.logoUrl,
                    thumbhash = null,
                    contentDescription = content.title,
                    contentScale = ContentScale.Fit,
                    // Flush with the editorial text below it; the default
                    // centre alignment floated wide logos toward the middle
                    // of the block, away from the meta/synopsis left edge.
                    alignment = Alignment.CenterStart,
                    transparent = true,
                    crossfadeMillis = 0,
                    onSuccess = { logoLoaded = true },
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = MarqueeLogoMaxWidth)
                        .alpha(logoAlpha),
                )
            }
        } else {
            Text(
                text = content.title,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = MarqueeTitleSize,
                lineHeight = MarqueeTitleSize * 1.15f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleLineCount = it.lineCount },
            )
        }

        // Badge + meta line.
        if (content.badges.isNotEmpty() || content.metaParts.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content.badges.forEach { badge -> MarqueeBadge(badge) }
                if (content.metaParts.isNotEmpty()) {
                    Text(
                        text = content.metaParts.joinToString(" · "),
                        color = SiloSecondaryText,
                        fontSize = MarqueeMetaSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Two synopsis lines (one when a text title wraps): the raised
        // typography floors made the old three-line block tall enough to
        // climb under the top menu bar, and the bar zone wins.
        content.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Text(
                text = synopsis,
                color = SiloSecondaryText,
                fontSize = MarqueeSynopsisSize,
                lineHeight = MarqueeSynopsisSize * 1.35f,
                maxLines = if (titleLineCount > 1) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MarqueeSynopsisMaxWidth),
            )
        }

        // Reserve the detail row before the async item-detail response lands.
        // The real line fades over this fixed slot, so the bottom-anchored
        // title/meta/synopsis never jump upward on a cold first focus.
        Box(
            modifier = Modifier
                .height(MarqueeDetailLineHeight)
                .widthIn(max = MarqueeSynopsisMaxWidth),
            contentAlignment = Alignment.TopStart,
        ) {
            Crossfade(
                targetState = detailLine?.takeIf { it.isNotBlank() },
                animationSpec = tween(TvMarqueeCrossfadeMs, easing = TvMarqueeEasing),
                label = "tvMarqueeDetailLine",
            ) { line ->
                if (line != null) {
                    Text(
                        text = line,
                        color = SiloOnSurface.copy(alpha = 0.7f),
                        fontSize = MarqueeDetailSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .offset(y = (-4).dp)
                            .widthIn(max = MarqueeSynopsisMaxWidth),
                    )
                }
            }
        }

        // tvOS keeps resolution, dynamic range and audio as separate outlined
        // badges below the aired/cast line, rather than a dot-joined spec line.
        content.specLine
            ?.split(" · ")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { badges ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.offset(y = (-6).dp),
                ) {
                    badges.forEach { badge -> MarqueeBadge(badge.uppercase()) }
                }
            }
    }
}

@Composable
private fun MarqueeBadge(label: String) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.08f), shape)
            .border(
                width = 0.5.dp,
                color = Color.White.copy(alpha = 0.24f),
                shape = shape,
            )
            .padding(horizontal = 6.dp, vertical = 2.5.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = MarqueeBadgeSize,
            lineHeight = MarqueeBadgeSize * 1.25f,
            letterSpacing = 0.55.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// Skyline marquee metrics. Geometry stays ~0.5x of the tvOS 1920×1080 tokens;
// body text stays at the 10-ft legibility floor; the compact outlined badges
// intentionally follow the smaller tvOS metadata treatment.
private val MarqueeContentWidth = 440.dp
private val MarqueeSynopsisMaxWidth = 390.dp
private val MarqueeLogoMaxWidth = 440.dp
private val MarqueeLogoMaxHeight = 84.dp
private val MarqueeDetailLineHeight = 20.dp
private val MarqueeTitleSize = 44.sp
private val MarqueeMetaSize = 14.sp
private val MarqueeDetailSize = 14.sp
private val MarqueeSynopsisSize = 16.sp
private val MarqueeBadgeSize = 10.5.sp
