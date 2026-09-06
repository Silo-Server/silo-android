package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import org.siloserver.silo.tv.ui.theme.SuccessGreen

/**
 * Tokens for the hero facts row, mirroring tvOS `TVHeroFactToken`.
 *
 * - [TextToken] plain text (year / runtime / ★rating); consecutive text
 *   tokens get a "·" divider between them.
 * - [Rating] a maturity/check token: green check icon + label.
 * - [Chip] a playback-format value (4K / HDR / DOLBY VISION / ATMOS / CC).
 *   Detail renders these with the same quiet monospaced treatment as Home's
 *   format line rather than promoting every value to an outlined badge.
 */
internal sealed class TvHeroFactToken {
    data class TextToken(val value: String) : TvHeroFactToken()
    data class Rating(val value: String) : TvHeroFactToken()
    data class Chip(val value: String) : TvHeroFactToken()
}

/**
 * Approved tvOS detail hero, mapped onto Android TV's half-scale layout
 * canvas. The page owns the sampled opaque tint; this composable draws only
 * the crisp top-trailing artwork and its alpha masks, so the image naturally
 * scrolls away with the hero instead of behaving like a fixed backdrop.
 *
 * The editorial and action stack is top-leading (50dp / 58dp = tvOS
 * 100pt / 116pt), while the artwork occupies the trailing 64 percent and
 * dissolves into the page surface at its leading and lower edges. The action
 * cluster remains full-width and focus-grouped; Version and Subtitles live in
 * that same row as circular actions.
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
    directorText: String?,
    actions: @Composable () -> Unit,
    playbackSummary: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Series reserves its editorial and disclosure slots as episodes change. */
    compactSeries: Boolean = false,
    // Optional description-translation affordance (Apple tvOS parity),
    // rendered as its own focus stop directly under the synopsis.
    translation: (@Composable () -> Unit)? = null,
) {
    // tvOS 690pt on a 1080pt canvas. Android TV's 1920×1080 emulator reports
    // a 960×540dp layout canvas, so the same fraction produces 345dp.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val heroHeight = screenHeight * HERO_HEIGHT_FRACTION

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .then(if (compactSeries) Modifier else Modifier.clipToBounds()),
    ) {
        val artworkWidth = maxWidth * ARTWORK_WIDTH_FRACTION
        val artworkHeight = maxWidth * 9f / 16f * ARTWORK_HEIGHT_FRACTION

        // Crisp artwork in the top-right. DstIn multiplies the horizontal and
        // vertical alpha ramps without painting black over the sampled page.
        if (!backdropUrl.isNullOrEmpty() || !backdropThumbhash.isNullOrEmpty()) {
            ThumbhashImage(
                url = backdropUrl,
                thumbhash = backdropThumbhash,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Movie and Series artwork share the same top edge. A
                    // layout offset here exposed the sampled page tint as a
                    // grey strip above only the Series image.
                    .width(artworkWidth)
                    .height(artworkHeight)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.00f to Color.Transparent,
                                0.68f to Color.Black,
                                1.00f to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.Black,
                                0.38f to Color.Black,
                                0.52f to Color.Black.copy(alpha = 0.88f),
                                0.68f to Color.Black.copy(alpha = 0.58f),
                                0.81f to Color.Black.copy(alpha = 0.24f),
                                0.90f to Color.Transparent,
                            ),
                            size = Size(size.width, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = HERO_TOP_INSET, start = TvDetailHorizontalInset, end = TvDetailHorizontalInset),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(if (compactSeries) 2.dp else HERO_CONTENT_SPACING),
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
                directorText = directorText,
                contentMaxWidth = HERO_CONTENT_MAX_WIDTH,
                verticalSpacing = EDITORIAL_SPACING,
                collapsedSynopsisLines = 3,
                compactSeries = compactSeries,
                translation = translation.takeUnless { compactSeries },
                playbackSummary = playbackSummary,
            )

            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .fillMaxWidth()
                    .focusGroup(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (compactSeries && translation != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { actions() }
                        translation()
                    }
                } else {
                    actions()
                }
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
    directorText: String?,
    contentMaxWidth: androidx.compose.ui.unit.Dp,
    verticalSpacing: androidx.compose.ui.unit.Dp,
    collapsedSynopsisLines: Int,
    compactSeries: Boolean,
    translation: (@Composable () -> Unit)? = null,
    playbackSummary: (@Composable () -> Unit)? = null,
) {
    val isCombinedSeriesEpisode = compactSeries && !seriesTitle.isNullOrBlank()
    Column(
        modifier = Modifier.widthIn(max = contentMaxWidth)
            // tvOS reserves 435pt for Series editorial content and its disclosure block.
            .then(if (compactSeries) Modifier.height(217.5.dp) else Modifier),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(if (compactSeries) 2.dp else verticalSpacing),
    ) {
        Column(
            modifier = if (compactSeries) Modifier.weight(1f).clipToBounds() else Modifier,
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        ) {
            TitleBlock(
                // A focused episode must not shrink or move the Show identity.
                // Its name belongs to the bounded synopsis slot below instead.
                title = if (isCombinedSeriesEpisode) seriesTitle!! else title,
                seriesTitle = seriesTitle.takeUnless { isCombinedSeriesEpisode },
                logoUrl = logoUrl,
            )

            val hasEpisodeHierarchy = !compactSeries && !seriesTitle.isNullOrBlank()
            if (hasEpisodeHierarchy && sourceTokens.isNotEmpty()) SourceRow(tokens = sourceTokens)
            val metadataSourceTokens = sourceTokens.takeUnless { hasEpisodeHierarchy }.orEmpty()
            val hasMetadata = factsLine.isNotEmpty() || !ratingChip.isNullOrBlank() || metadataSourceTokens.isNotEmpty()
            if (compactSeries || hasMetadata) {
                Box(
                    modifier = Modifier.height(SERIES_METADATA_SLOT_HEIGHT).clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (hasMetadata) {
                        MetadataRow(
                            tokens = factsLine,
                            sourceTokens = metadataSourceTokens,
                            ratingChip = ratingChip,
                            compactRating = true,
                        )
                    }
                }
            }

            // The synopsis opens its full text without resizing the hero.
            if (compactSeries) {
                // Reserve three lines even when the focused episode has no synopsis.
                Box(
                    modifier = Modifier
                        .height(SERIES_EPISODE_SYNOPSIS_HEIGHT)
                        .clipToBounds(),
                ) {
                    overview?.takeIf { it.isNotBlank() }?.let { line ->
                        TvExpandableSynopsis(
                            overview = line,
                            tagline = tagline.takeUnless { isCombinedSeriesEpisode },
                            collapsedMaxLines = 3,
                            dialogTitle = title,
                            previewText = if (isCombinedSeriesEpisode) "$title · $line" else line,
                        )
                    }
                }
            } else {
                overview?.takeIf { it.isNotBlank() }?.let { line ->
                    TvExpandableSynopsis(
                        overview = line,
                        tagline = tagline,
                        collapsedMaxLines = collapsedSynopsisLines,
                        dialogTitle = title,
                    )
                }
            }
            translation?.invoke()
        }
        // Bottom-lock the credit and playback readout independently of synopsis length.
        Column(verticalArrangement = Arrangement.spacedBy(if (compactSeries) 4.dp else verticalSpacing)) {
            if (compactSeries) {
                // Keep this slot mounted even while episode data changes so the
                // playback readout and action row remain completely locked.
                Box(
                    modifier = Modifier
                        .height(SERIES_CREDIT_SLOT_HEIGHT)
                        .clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    directorText?.takeIf { it.isNotBlank() }?.let { line ->
                        HeroCreditLine(line)
                    }
                }
            } else {
                directorText?.takeIf { it.isNotBlank() }?.let { line ->
                    HeroCreditLine(line)
                }
            }
            playbackSummary?.invoke()
        }
    }
}

@Composable
private fun HeroCreditLine(line: String) {
    Text(
        text = line,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        color = Color.White.copy(alpha = 0.62f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TitleBlock(
    title: String,
    seriesTitle: String?,
    logoUrl: String?,
) {
    val seriesContext = seriesTitle?.trim()?.takeIf { it.isNotEmpty() }

    when {
        seriesContext != null -> EpisodeHierarchyTitle(
            seriesTitle = seriesContext,
            episodeTitle = title,
            logoUrl = logoUrl,
        )
        !logoUrl.isNullOrBlank() -> AsyncImage(
            model = logoUrl,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            // Reserve the framed logo area (approved tvOS maxHeight 160pt) so a
            // loading/failed logo can't measure as 0 and collapse the editorial
            // stack. Fixed height + Fit keeps the logo's aspect within it.
            modifier = Modifier
                .height(80.dp)
                .widthIn(max = 325.dp),
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
private fun EpisodeHierarchyTitle(
    seriesTitle: String,
    episodeTitle: String,
    logoUrl: String?,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = seriesTitle,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .height(64.dp)
                    .widthIn(max = 325.dp),
            )
        } else {
            Text(
                text = seriesTitle.uppercase(),
                style = heroDisplayHero,
                color = Color.White,
                textAlign = TextAlign.Start,
                maxLines = 2,
            )
        }
        Text(
            text = episodeTitle,
            style = heroDisplayHero.copy(
                fontWeight = FontWeight.ExtraBold,
                // The episode hierarchy shares the compact Series hero with
                // its persistent actions. A slightly smaller two-line title
                // keeps long episode names readable without colliding with
                // Play or the selector below.
                fontSize = 20.sp,
                lineHeight = 22.sp,
            ),
            color = Color.White.copy(alpha = 0.94f),
            textAlign = TextAlign.Start,
            maxLines = 2,
        )
    }
}

@Composable
private fun SourceRow(tokens: List<String>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(
                    text = "·",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
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
    }
}

@Composable
private fun MetadataRow(
    tokens: List<TvHeroFactToken>,
    sourceTokens: List<String>,
    ratingChip: String?,
    compactRating: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ratingChip?.takeIf { it.isNotBlank() }?.let { rating ->
            RatingChip(text = rating, compact = compactRating)
        }
        tokens.forEachIndexed { index, token ->
            if (index > 0) MetadataDivider()
            when (token) {
                is TvHeroFactToken.TextToken -> Text(
                    text = token.value,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
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
                        modifier = Modifier.height(12.dp),
                    )
                    Text(
                        text = token.value,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
                is TvHeroFactToken.Chip -> Text(
                    text = homeStyleFormatLabel(token.value),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.52.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
        }
        sourceTokens.forEachIndexed { index, token ->
            if (tokens.isNotEmpty() || index > 0) MetadataDivider()
            Text(
                text = token,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                color = Color.White.copy(alpha = 0.90f),
                maxLines = 1,
            )
        }

    }
}

@Composable
private fun MetadataDivider() {
    Text(
        text = "·",
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        color = Color.White.copy(alpha = 0.45f),
    )
}

@Composable
private fun RatingChip(text: String, compact: Boolean) {
    Box(
        modifier = Modifier
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.7f),
                shape = RoundedCornerShape(2.5.dp),
            )
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 3.dp,
            ),
    ) {
        // Compact detail ratings use the tvOS 20pt badge mapped to Android's
        // half-scale canvas.
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = if (compact) 10.5.sp else 14.sp,
            lineHeight = if (compact) 12.sp else 16.sp,
            letterSpacing = if (compact) 0.35.sp else 0.5.sp,
            color = Color.White,
            maxLines = 1,
        )
    }
}

private fun homeStyleFormatLabel(value: String): String = when (value.uppercase()) {
    "DOLBY VISION" -> "Dolby Vision"
    "ATMOS" -> "Atmos"
    else -> value
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

/** Approved tvOS detail geometry mapped onto Android TV's half-scale canvas. */
private const val HERO_HEIGHT_FRACTION = 690f / 1080f
private const val ARTWORK_WIDTH_FRACTION = 0.64f
private const val ARTWORK_HEIGHT_FRACTION = 0.70f
private val HERO_TOP_INSET = 58.dp
private val HERO_CONTENT_MAX_WIDTH = 540.dp
private val HERO_CONTENT_SPACING = 9.dp
private val EDITORIAL_SPACING = 7.dp
private val SERIES_METADATA_SLOT_HEIGHT = 18.dp
private val SERIES_EPISODE_SYNOPSIS_HEIGHT = 56.dp
private val SERIES_CREDIT_SLOT_HEIGHT = 14.dp

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
