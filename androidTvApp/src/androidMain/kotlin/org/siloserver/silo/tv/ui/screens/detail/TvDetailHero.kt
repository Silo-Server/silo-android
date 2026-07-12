package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import coil3.compose.AsyncImage
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.DarkSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.SuccessGreen

/**
 * Tokens for the hero facts row, mirroring tvOS `TVHeroFactToken`.
 *
 * - [TextToken] plain text (year / runtime / ★rating); consecutive text
 *   tokens get a "·" divider between them.
 * - [Rating] a maturity/check token: green check icon + label.
 * - [Chip] an outlined squared pill (4K / HDR / DOLBY VISION / ATMOS / CC).
 */
internal sealed class TvHeroFactToken {
    data class TextToken(val value: String) : TvHeroFactToken()
    data class Rating(val value: String) : TvHeroFactToken()
    data class Chip(val value: String) : TvHeroFactToken()
}

/**
 * Full-bleed cinematic hero for the Android TV detail screen. Mirrors the
 * tvOS `TVDetailHero` 1:1.
 *
 * Layout = a `ZStack(bottomLeading)`: a near-full-viewport backdrop, a
 * 4-stop horizontal darkening on the left, a soft vertical fade into the
 * rail body at the bottom, then the bottom-anchored editorial + action
 * column on the left. The "Starring …" credit floats upper-right as its own
 * trailing overlay (tvOS `starringOverlay`), NOT inside the editorial column.
 *
 * Apple sizes the hero relative to the viewport (`heroHeight = 980` of a
 * 1080-pt canvas ≈ 0.907×), so we compute the height as a fraction of the
 * screen height rather than a fixed dp — see [HERO_HEIGHT_FRACTION].
 *
 * The action cluster is given the full hero width (leading-aligned) and
 * wrapped in its own focus group so the selector row inside can stretch
 * its own focus section full-width for Down navigation, and lower rails can
 * move "up" into the cluster from a far-right card.
 */
@Composable
internal fun TvDetailHero(
    title: String,
    seriesTitle: String?,
    logoUrl: String?,
    backdropUrl: String?,
    backdropThumbhash: String?,
    sourceTokens: List<String>,
    ratingChip: String?,
    overview: String?,
    tagline: String?,
    factsLine: List<TvHeroFactToken>,
    starringText: String?,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    // Optional description-translation affordance (Apple tvOS parity),
    // rendered as its own focus stop directly under the synopsis.
    translation: (@Composable () -> Unit)? = null,
) {
    // heroHeight = 980 of a 1080-pt tvOS canvas ≈ 0.907 × viewport height.
    // The TV theme keeps dp geometry at device density, so screenHeightDp maps
    // directly to the Android TV layout canvas.
    val heroHeight = LocalConfiguration.current.screenHeightDp.dp * HERO_HEIGHT_FRACTION
    val contentMaxWidth = 600.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Minimum, NOT a fixed height. The bottom-anchored editorial + action
            // column can measure taller than 0.907×viewport (large display title,
            // 3-line synopsis, action row + selector row). A fixed `.height`
            // clamps the Column's measure constraints, so `Column` hands the
            // trailing action/selector rows ~0 remaining height and collapses them
            // — they stay focusable but paint nothing. A min height lets the hero
            // grow to fit; the backdrop/gradients track the final size via
            // `matchParentSize` (they can't `fillMaxSize` under an unbounded max).
            .heightIn(min = heroHeight),
    ) {
        // Backdrop (fill; else siloSurface).
        if (!backdropUrl.isNullOrEmpty() || !backdropThumbhash.isNullOrEmpty()) {
            ThumbhashImage(
                url = backdropUrl,
                thumbhash = backdropThumbhash,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(DarkSurface))
        }

        // Heavy left-side darkening — clears toward the right so the imagery
        // breathes while text stays legible. (leading → trailing)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to Color.Black.copy(alpha = 0.92f),
                        0.22f to Color.Black.copy(alpha = 0.70f),
                        0.55f to Color.Black.copy(alpha = 0.35f),
                        0.88f to Color.Transparent,
                    ),
                ),
        )

        // Soft bottom fade that hands off into the rail body underneath, so a
        // hint of the next rail peeks through the seam. (top → bottom)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.85f to DarkBackground.copy(alpha = 0.55f),
                        1.00f to DarkBackground,
                    ),
                ),
        )

        // "Starring …" floats in the upper-right of the hero, right-aligned —
        // tvOS `.overlay(alignment: .trailing)` + `.padding(.bottom, heroHeight
        // * 0.45)` (the bottom padding on the vertically-centered overlay
        // pushes the credit into the top-right region). 2-line limit; sized up
        // from the raw tvOS ~2x mapping (24pt → 12sp) per design review, with
        // maxWidth widened to match so casts don't ellipsize sooner.
        starringText?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                text = line,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.55f),
                        offset = Offset(0f, 2f),
                        blurRadius = 6f,
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Spacing.safeArea, bottom = heroHeight * 0.45f)
                    .widthIn(max = 280.dp),
            )
        }

        // Bottom-anchored editorial column + action cluster. Bottom inset +
        // inter-row gaps kept tight so the full stack (tagline → title →
        // synopsis → facts → actions → selector) fits within heroHeight on a
        // 540dp-tall canvas instead of overflowing and clipping the tagline off
        // the top. (tvOS point values were ~2x these.) Bottom inset is part of
        // the TvDetailSectionGap handoff math on the detail page.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.safeArea, end = Spacing.safeArea, bottom = TvDetailHeroBottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditorialColumn(
                title = title,
                seriesTitle = seriesTitle,
                logoUrl = logoUrl,
                sourceTokens = sourceTokens,
                ratingChip = ratingChip,
                overview = overview,
                tagline = tagline,
                factsLine = factsLine,
                contentMaxWidth = contentMaxWidth,
                translation = translation,
            )

            // Full-width, leading-aligned focus container for the action +
            // selector cluster (own focus section).
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .focusGroup(),
                contentAlignment = Alignment.CenterStart,
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun EditorialColumn(
    title: String,
    seriesTitle: String?,
    logoUrl: String?,
    sourceTokens: List<String>,
    ratingChip: String?,
    overview: String?,
    tagline: String?,
    factsLine: List<TvHeroFactToken>,
    contentMaxWidth: androidx.compose.ui.unit.Dp,
    translation: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.widthIn(max = contentMaxWidth),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleBlock(
            title = title,
            seriesTitle = seriesTitle,
            logoUrl = logoUrl,
        )

        if (sourceTokens.isNotEmpty() || !ratingChip.isNullOrBlank()) {
            SourceRow(tokens = sourceTokens, ratingChip = ratingChip)
        }

        // Synopsis slot — the hero's only text focus stop. A focusable leaf
        // that clamps the overview to 3 lines and, on OK/Select, expands to
        // the full overview with the tagline revealed above it.
        overview?.takeIf { it.isNotBlank() }?.let { line ->
            TvExpandableSynopsis(overview = line, tagline = tagline)
        }
        translation?.invoke()

        if (factsLine.isNotEmpty()) {
            FactsRow(tokens = factsLine)
        }
    }
}

@Composable
private fun TitleBlock(
    title: String,
    seriesTitle: String?,
    logoUrl: String?,
) {
    val seriesContext = seriesTitle?.trim()?.takeIf { it.isNotEmpty() }

    when {
        seriesContext != null -> EpisodeHierarchyTitle(seriesTitle = seriesContext, episodeTitle = title)
        !logoUrl.isNullOrBlank() -> AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            // Reserve the framed logo area (Apple frames it maxHeight 220) so a
            // loading/failed logo can't measure as 0 and collapse the editorial
            // stack. Fixed height + Fit keeps the logo's aspect within it.
            modifier = Modifier
                .height(110.dp)
                .widthIn(max = 310.dp),
        )
        else -> HeroTextTitle(title = title)
    }
}

@Composable
private fun HeroTextTitle(title: String) {
    val parts = remember(title) { splitDisplayTitle(title) }
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = parts.first.uppercase(),
            style = heroDisplayHero,
            color = Color.White,
            textAlign = TextAlign.Start,
            maxLines = 2,
        )
        parts.second?.let { sub ->
            Text(
                text = sub.uppercase(),
                style = heroDisplayHero.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    lineHeight = 22.sp,
                    // tvOS `.tracking(1.5)` → 0.75sp at half scale.
                    letterSpacing = 0.75.sp,
                ),
                color = Color.White.copy(alpha = 0.95f),
                textAlign = TextAlign.Start,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EpisodeHierarchyTitle(seriesTitle: String, episodeTitle: String) {
    val parts = remember(episodeTitle) { splitDisplayTitle(episodeTitle) }
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = seriesTitle.uppercase(),
            style = heroDisplayHero,
            color = Color.White,
            textAlign = TextAlign.Start,
            maxLines = 2,
        )
        Text(
            text = parts.first,
            style = heroDisplayHero.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 23.sp,
                lineHeight = 25.sp,
            ),
            color = Color.White.copy(alpha = 0.94f),
            textAlign = TextAlign.Start,
            maxLines = 2,
        )
        parts.second?.let { sub ->
            Text(
                text = sub.uppercase(),
                style = heroDisplayHero.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    // tvOS `.tracking(1.2)` → 0.6sp at half scale.
                    letterSpacing = 0.6.sp,
                ),
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Start,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun SourceRow(tokens: List<String>, ratingChip: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(
                    text = "·",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
            Text(
                text = token,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
            )
        }
        if (!ratingChip.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(2.dp))
            RatingChip(text = ratingChip)
        }
    }
}

@Composable
private fun FactsRow(tokens: List<TvHeroFactToken>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            val previous = tokens.getOrNull(index - 1)
            if (index > 0 && token is TvHeroFactToken.TextToken && previous is TvHeroFactToken.TextToken) {
                Text(
                    text = "·",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
            when (token) {
                is TvHeroFactToken.TextToken -> Text(
                    text = token.value,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                )
                is TvHeroFactToken.Rating -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen.copy(alpha = 0.9f),
                        modifier = Modifier.height(9.dp),
                    )
                    Text(
                        text = token.value,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
                is TvHeroFactToken.Chip -> QualityChip(text = token.value)
            }
        }
    }
}

@Composable
private fun RatingChip(text: String) {
    Box(
        modifier = Modifier
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.7f),
                shape = RoundedCornerShape(2.5.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        // tvOS: 20pt heavy, tracking 1.0 → 10sp / 0.5sp, +1 per design review.
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun QualityChip(text: String) {
    Box(
        modifier = Modifier
            .border(
                width = 0.6.dp,
                color = Color.White.copy(alpha = 0.65f),
                shape = RoundedCornerShape(2.dp),
            )
            .padding(horizontal = 4.5.dp, vertical = 2.dp),
    ) {
        // tvOS: 16pt heavy, tracking 1.0 → 8sp / 0.5sp, +1 per design review.
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp,
            color = Color.White,
            maxLines = 1,
        )
    }
}

private fun splitDisplayTitle(raw: String): Pair<String, String?> {
    val separators = listOf(": ", " — ", " – ", " - ")
    for (sep in separators) {
        val idx = raw.indexOf(sep)
        if (idx > 0) {
            val head = raw.substring(0, idx).trim()
            val tail = raw.substring(idx + sep.length).trim()
            if (head.isNotEmpty() && tail.isNotEmpty()) return head to tail
        }
    }
    return raw to null
}

/** heroHeight = 980 of a 1080-pt tvOS canvas ≈ 0.907. */
private const val HERO_HEIGHT_FRACTION = 0.907f

/**
 * Condensed family for the hero display title. tvOS renders the title in SF
 * Pro `.width(.compressed)` at `.black`; Inter has no width axis, so this
 * uses Inter Tight Black — the official narrow Inter companion — keeping the
 * hero on-brand with the app's Inter type system. (The most condensed system
 * alternative is `DeviceFontFamilyName("sans-serif-condensed")` / Roboto
 * Condensed, which is narrower but off-brand.)
 */
private val heroCondensedFamily = FontFamily(
    Font(R.font.inter_tight_black, weight = FontWeight.Bold),
    Font(R.font.inter_tight_black, weight = FontWeight.ExtraBold),
    Font(R.font.inter_tight_black, weight = FontWeight.Black),
)

/**
 * Hero display title — primary line. Mirrors tvOS `TVHeroTitle` /
 * `TVEpisodeHierarchyTitle` (92pt `.black` `.width(.compressed)`) on Android
 * TV's half-scale layout canvas, via the condensed device family above. Kept
 * local so the shared home `heroDisplay` token stays unchanged.
 */
private val heroDisplayHero = TextStyle(
    fontFamily = heroCondensedFamily,
    fontWeight = FontWeight.Black,
    // tvOS uses 92pt in its 1920x1080 POINT canvas; Android TV is a 960x540 DP
    // canvas (≈half) → 46sp, trimmed to 42sp per design review (2026-07-11).
    fontSize = 42.sp,
    lineHeight = 46.sp,
    letterSpacing = 0.sp,
    // Apple shadows the hero title (black@0.55, r16, y4) for legibility on
    // bright backdrops. Inherited by the subtitle/episode `.copy()` variants.
    shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 4f), blurRadius = 16f),
)
