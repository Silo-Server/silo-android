package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.common.settings.titleLogoUrl
import org.siloserver.silo.common.ui.components.MachineTranslatedLabel
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.hasMachineTranslatedOverview
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs

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
    /** The rested item's description translation; like [detailLine], it
     *  updates the block in place instead of replaying the crossfade. */
    translation: TvMarqueeTranslation? = null,
    modifier: Modifier = Modifier,
    startPadding: androidx.compose.ui.unit.Dp = 44.dp,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    animateTransition: Boolean = true,
    /** An extra line under the block for pages that preview more than catalog
     *  items (the Requests page's status and stage track). */
    footer: (@Composable (TvMarqueeContent) -> Unit)? = null,
    /** The air date opening the detail line as "Aired …"; travels with [detailLine]. */
    airDate: String? = content?.airDate,
) {
    val shownDetailLine = tvMarqueeDetailLine(airDate, detailLine)
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
        val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
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
                        // A layer for the item still shown takes its current
                        // copy at once, so a synopsis protection now hides
                        // doesn't fade out.
                        TvMarqueeBlock(
                            content = if (content != null && value.id == content.id) {
                                content
                            } else {
                                value.underPrefs(spoilerPrefs)
                            },
                            detailLine = shownDetailLine.takeIf { value.id == content?.id },
                            translation = translation.takeIf { it?.contentId == value.contentId },
                            footer = footer,
                        )
                    }
                }
            }
        } else if (content != null) {
            TvMarqueeBlock(
                content = content,
                detailLine = shownDetailLine,
                translation = translation.takeIf { it?.contentId == content.contentId },
                footer = footer,
            )
        }
    }
}

@Composable
private fun TvMarqueeBlock(
    content: TvMarqueeContent,
    detailLine: String?,
    translation: TvMarqueeTranslation? = null,
    footer: (@Composable (TvMarqueeContent) -> Unit)? = null,
) {
    // tvOS parity (TVFocusMarquee): when the text-fallback title wraps to two
    // lines the synopsis drops to one, keeping the bottom-anchored block's
    // height bounded so it never climbs into the top-menu-bar zone.
    var titleLineCount by remember(content.id) { mutableStateOf(1) }
    // "Show title art" off: the marquee always names the title in text.
    val logoUrl = titleLogoUrl(content.logoUrl)
    var logoLoaded by remember(logoUrl) { mutableStateOf(false) }
    // The text fallback stays invisible while the logo gets a short grace
    // period, so a cached or fast logo never flashes the text title first. A
    // failed or slow logo still falls back to the text.
    var showTitleFallback by remember(logoUrl) { mutableStateOf(false) }
    LaunchedEffect(logoUrl) {
        delay(MarqueeLogoGraceMs)
        // A logo that decoded inside the grace period fades in alone.
        if (!logoLoaded) showTitleFallback = true
    }
    // Read the fade only through drawAlpha (draw phase): reading it during
    // composition recomposed the whole block on every frame of the fade.
    val logoAlpha = animateFloatAsState(
        targetValue = if (logoLoaded) 1f else 0f,
        animationSpec = tween(TvMarqueeCrossfadeMs, easing = TvMarqueeEasing),
        label = "marqueeLogoAlpha",
    )
    val logoFadeDone by remember(logoAlpha) { derivedStateOf { logoAlpha.value >= 1f } }
    // 6dp rows and an 84dp logo slot: the marquee viewport (screen minus the
    // top-bar zone minus the row band) fits five text rows only if the block
    // stays under ~206dp, and the format spec line is the fifth row.
    Column(
        modifier = Modifier.widthIn(max = MarqueeContentWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The text title stays composed (for layout and line count) until the
        // logo decodes, but only becomes visible after the grace period or on
        // error, so a bad/slow URL never leaves a blank title slot.
        if (!logoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .height(MarqueeLogoMaxHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!logoLoaded || !logoFadeDone) {
                    Text(
                        text = content.title,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = MarqueeTitleSize,
                        lineHeight = MarqueeTitleSize * 1.15f,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { titleLineCount = it.lineCount },
                        modifier = Modifier.drawAlpha {
                            if (showTitleFallback) 1f - logoAlpha.value else 0f
                        },
                    )
                }
                ThumbhashImage(
                    url = logoUrl,
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
                    onError = { showTitleFallback = true },
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = MarqueeLogoMaxWidth)
                        .drawAlpha { logoAlpha.value },
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

        val hidesSynopsis = content.isEpisode && LocalEpisodeSpoilerPrefs.current.hidesOverview(
            org.siloserver.silo.model.settings.EpisodeSpoilers.isUnwatched(content.source),
        )
        val synopsisText = (translation?.synopsis ?: content.synopsis)
            ?.takeIf { it.isNotBlank() && !hidesSynopsis }
        val translating = translation?.translating == true
        val machineTranslated = !translating && synopsisText != null &&
            (translation?.machineTranslated ?: hasMachineTranslatedOverview(content.source.machineTranslatedFields))

        // Badge + meta line.
        if (content.badges.isNotEmpty() || content.metaParts.isNotEmpty() || machineTranslated) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content.badges.forEach { badge -> MarqueeBadge(badge) }
                if (content.metaParts.isNotEmpty()) {
                    TvFactsRow(
                        tokens = content.metaParts,
                        style = LocalTextStyle.current.merge(
                            TextStyle(
                                color = SiloSecondaryText,
                                fontSize = MarqueeMetaSize,
                                fontWeight = FontWeight.Medium,
                            ),
                        ),
                        spacing = MarqueeMetaGap,
                    )
                }
                // The block has no spare row, so the machine-translation
                // marker is the compact icon at the end of the meta line.
                if (machineTranslated) {
                    MachineTranslatedLabel(
                        color = SiloSecondaryText,
                        compact = true,
                        iconSize = 14.dp,
                    )
                }
            }
        }

        // Two synopsis lines (one when a text title wraps): the raised
        // typography floors made the old three-line block tall enough to
        // climb under the top menu bar, and the bar zone wins.
        synopsisText?.let { synopsis ->
            // A running translation pulses the original text until the
            // translated description replaces it.
            val pulse: State<Float>? = if (translating) {
                rememberInfiniteTransition(label = "marqueeTranslating").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.7f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "marqueeTranslatingAlpha",
                )
            } else {
                null
            }
            Text(
                text = synopsis,
                color = SiloSecondaryText,
                fontSize = MarqueeSynopsisSize,
                lineHeight = MarqueeSynopsisSize * 1.35f,
                maxLines = if (titleLineCount > 1) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = MarqueeSynopsisMaxWidth)
                    .drawAlpha { pulse?.value ?: 1f },
            )
        }

        // Reserve the detail row before the async item-detail response lands.
        // The real line fades over this fixed slot, so the bottom-anchored
        // title/meta/synopsis never jump upward on a cold first focus.
        // The lift sits on the slot, not the Text: Crossfade draws each line
        // in an alpha layer clipped to its bounds, which would cut the top
        // off a Text shifted above them for the whole fade.
        Box(
            modifier = Modifier
                .offset(y = (-4).dp)
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
                        modifier = Modifier.widthIn(max = MarqueeSynopsisMaxWidth),
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

        footer?.invoke(content)
    }
}

/**
 * [Modifier.alpha] that reads [alpha] in the draw phase, so an animated value
 * redraws the layer without recomposing its caller. Like [Modifier.alpha], it
 * clips only while translucent.
 */
private fun Modifier.drawAlpha(alpha: () -> Float): Modifier = graphicsLayer {
    val value = alpha()
    this.alpha = value
    clip = value != 1f
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

/** About a space at [MarqueeMetaSize], so `·` spacing matches the joined text. */
private val MarqueeMetaGap = 4.dp
private val MarqueeDetailSize = 14.sp
private val MarqueeSynopsisSize = 16.sp
private val MarqueeBadgeSize = 10.5.sp

/** How long the marquee waits on a logo before showing the text title. */
private const val MarqueeLogoGraceMs = 700L
