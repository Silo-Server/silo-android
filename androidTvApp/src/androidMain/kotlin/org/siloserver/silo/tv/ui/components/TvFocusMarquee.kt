package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
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
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    animateTransition: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, bottom = bottomPadding),
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
    Column(
        modifier = Modifier.widthIn(max = MarqueeContentWidth),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title slot — when logo artwork exists, never substitute the plain
        // title into this slot. The fallback-to-logo swap reads as a rescale;
        // preloading supplies the actual artwork while this fixed slot keeps
        // the rest of the marquee geometrically stable.
        if (!content.logoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .height(MarqueeLogoMaxHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                ThumbhashImage(
                    url = content.logoUrl,
                    thumbhash = null,
                    contentDescription = content.title,
                    contentScale = ContentScale.Fit,
                    transparent = true,
                    crossfadeMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Text(
                text = content.title,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = MarqueeTitleSize,
                lineHeight = MarqueeTitleSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

        // Keep three synopsis lines even when logo art is present. The whole
        // marquee is raised above the row band to preserve that extra line.
        content.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Text(
                text = synopsis,
                color = SiloSecondaryText,
                fontSize = MarqueeSynopsisSize,
                lineHeight = MarqueeSynopsisSize * 1.35f,
                maxLines = 3,
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
                        color = SiloOnSurface.copy(alpha = 0.5f),
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
    }
}

@Composable
private fun MarqueeBadge(label: String) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.24f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = MarqueeBadgeSize,
            lineHeight = MarqueeBadgeSize,
            letterSpacing = MarqueeBadgeSize * 0.08f,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Skyline marquee metrics, scaled ~0.5x from the tvOS 1920×1080 tokens.
private val MarqueeContentWidth = 440.dp
private val MarqueeSynopsisMaxWidth = 390.dp
private val MarqueeLogoMaxWidth = 440.dp
private val MarqueeLogoMaxHeight = 95.dp
private val MarqueeDetailLineHeight = 14.dp
private val MarqueeTitleSize = 44.sp
private val MarqueeMetaSize = 12.5.sp
private val MarqueeDetailSize = 12.sp
private val MarqueeSynopsisSize = 13.sp
private val MarqueeBadgeSize = 8.5.sp
