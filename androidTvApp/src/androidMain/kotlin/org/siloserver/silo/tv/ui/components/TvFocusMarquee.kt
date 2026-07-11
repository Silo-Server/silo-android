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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
        if (animateTransition) {
            Crossfade(
                targetState = content,
                animationSpec = tween(TvMarqueeCrossfadeMs),
                label = "tvFocusMarquee",
            ) { value ->
                if (value != null) {
                    TvMarqueeBlock(content = value)
                } else {
                    Box(modifier = Modifier)
                }
            }
        } else if (content != null) {
            TvMarqueeBlock(content = content)
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
                crossfadeMillis = 0,
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

        // Quieter detail line (cast / air-date) when carried by the payload.
        content.detailLine?.takeIf { it.isNotBlank() }?.let { line ->
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
private val MarqueeLogoMaxHeight = 100.dp
private val MarqueeTitleSize = 42.sp
private val MarqueeMetaSize = 11.5.sp
private val MarqueeDetailSize = 11.sp
private val MarqueeSynopsisSize = 12.sp
private val MarqueeBadgeSize = 8.sp
