package com.continuum.app.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.tv.ui.theme.ContinuumOnSurface
import com.continuum.app.tv.ui.theme.ContinuumSecondaryText

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
    modifier: Modifier = Modifier,
    startPadding: androidx.compose.ui.unit.Dp = 44.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(start = startPadding, bottom = bottomPadding),
        contentAlignment = Alignment.BottomStart,
    ) {
        AnimatedContent(
            targetState = content,
            transitionSpec = {
                fadeIn(tween(TvMarqueeCrossfadeMs)) togetherWith
                    fadeOut(tween(TvMarqueeCrossfadeMs))
            },
            contentKey = { it?.id },
            label = "tvFocusMarquee",
        ) { value ->
            if (value != null) {
                TvMarqueeBlock(content = value)
            } else {
                Box(modifier = Modifier)
            }
        }
    }
}

@Composable
private fun TvMarqueeBlock(content: TvMarqueeContent) {
    Column(
        modifier = Modifier.widthIn(max = MarqueeContentWidth),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Title slot — logo art when available, else heavy text title.
        if (!content.logoUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = content.logoUrl,
                thumbhash = null,
                contentDescription = content.title,
                contentScale = ContentScale.Fit,
                transparent = true,
                modifier = Modifier
                    .height(MarqueeLogoMaxHeight)
                    .widthIn(max = MarqueeLogoMaxWidth),
            )
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
                        color = ContinuumSecondaryText,
                        fontSize = MarqueeMetaSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 3-line synopsis (2 when a logo is shown, mirroring tvOS).
        content.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Text(
                text = synopsis,
                color = ContinuumSecondaryText,
                fontSize = MarqueeSynopsisSize,
                lineHeight = MarqueeSynopsisSize * 1.35f,
                maxLines = if (content.logoUrl.isNullOrBlank()) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MarqueeSynopsisMaxWidth),
            )
        }

        // Quieter detail line (cast / air-date) when carried by the payload.
        content.detailLine?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                text = line,
                color = ContinuumOnSurface.copy(alpha = 0.5f),
                fontSize = MarqueeMetaSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MarqueeSynopsisMaxWidth),
            )
        }
    }
}

@Composable
private fun MarqueeBadge(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = MarqueeBadgeSize,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// Skyline marquee metrics, scaled ~0.5x from the tvOS 1920×1080 tokens.
private val MarqueeContentWidth = 440.dp
private val MarqueeSynopsisMaxWidth = 390.dp
private val MarqueeLogoMaxWidth = 440.dp
private val MarqueeLogoMaxHeight = 100.dp
private val MarqueeTitleSize = 42.sp
private val MarqueeMetaSize = 14.sp
private val MarqueeSynopsisSize = 15.sp
private val MarqueeBadgeSize = 13.sp
