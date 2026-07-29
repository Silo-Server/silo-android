package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.android.ui.navigation.heroTarget
import org.siloserver.silo.android.ui.theme.PillShape
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season

// ── Tokens ────────────────────────────────────────────────────

internal val DetailPrimaryText = SiloOnSurface
internal val DetailSecondaryText = SiloOnSurface.copy(alpha = 0.78f)
internal val DetailTertiaryText = SiloOnSurface.copy(alpha = 0.55f)

internal val SafePadding = 16.dp
internal val SmallPadding = 8.dp
internal val LargePadding = 24.dp

// ── Dynamic palette ───────────────────────────────────────────

fun detailScreenBackgroundBrush(dominantColor: Color): Brush =
    Brush.verticalGradient(
        0.00f to dominantColor.copy(alpha = 0.10f),
        0.22f to Color.Transparent,
        1.00f to Color.Transparent,
    )

// ── Hero ──────────────────────────────────────────────────────

/**
 * iOS-aligned phone detail hero: backdrop on top fading into the page
 * background, then a centered editorial column on the dark surface.
 *
 * Action content is supplied by the caller — typically a full-width Play
 * pill, a row of circle toggles, and an optional version pill.
 */
@Composable
fun DetailHero(
    detail: ItemDetail,
    eyebrow: String?,
    sourceTokens: List<String>,
    factsLine: List<String>,
    modifier: Modifier = Modifier,
    dominantColor: Color = SiloBackground,
    directorText: String? = null,
    // Optional viewer-facing description-translation affordance, rendered
    // directly under the overview (Apple parity: DescriptionTranslationView).
    translation: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Backdrop(
            backdropUrl = detail.backdropUrl,
            backdropThumbhash = detail.backdropThumbhash,
            contentDescription = detail.title,
            contentId = detail.contentId,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SafePadding)
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!eyebrow.isNullOrBlank()) {
                EyebrowChip(text = eyebrow)
            }
            HeroTitle(detail = detail)
            if (sourceTokens.isNotEmpty() || detail.contentRating != null) {
                SourceRow(
                    tokens = sourceTokens,
                    ratingChip = detail.contentRating,
                )
            }
            actions()
            val overview = detail.overview
            if (!overview.isNullOrBlank()) {
                OverviewBlock(text = overview)
            }
            translation?.invoke()
            directorText?.takeIf { it.isNotBlank() }?.let { line ->
                Text(
                    text = line,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.62f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (factsLine.isNotEmpty()) {
                FactsRow(tokens = factsLine)
            }
        }
    }
}

@Composable
private fun Backdrop(
    backdropUrl: String?,
    backdropThumbhash: String?,
    contentDescription: String,
    contentId: String?,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Hero height tracks iOS — 360dp on compact, 420 on wider screens.
        val heroHeight: Dp = if (maxWidth >= 600.dp) 420.dp else 360.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                // Hero morph target — pairs with the exact poster placement the
                // user tapped (read from the hero hand-off) so the artwork bounds
                // carry from that list card into this backdrop.
                .heroTarget(),
        ) {
            ThumbhashImage(
                url = backdropUrl,
                thumbhash = backdropThumbhash,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Soft single-direction fade — dissolves into the page
            // background exactly so there's no seam where the artwork
            // ends. Mirrors iOS `PhoneDetailHero.bottomFade`.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.55f to Color.Transparent,
                            0.85f to SiloBackground.copy(alpha = 0.6f),
                            1.00f to SiloBackground,
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun HeroTitle(detail: ItemDetail) {
    val isEpisode = detail.type == "episode"
    val seriesTitle = detail.seriesTitle?.takeIf { it.isNotBlank() }

    if (isEpisode && seriesTitle != null) {
        // iOS PhoneEpisodeHierarchyTitle: series 34pt heavy, episode 22pt
        // semibold, optional subtitle 13pt heavy tracked, spacing 6.
        val (episodePrimary, episodeSubtitle) = splitHeroTitle(detail.title)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = seriesTitle,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DetailPrimaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = episodePrimary,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = DetailPrimaryText.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeSubtitle != null) {
                Text(
                    text = episodeSubtitle.uppercase(),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.0.sp,
                    color = DetailPrimaryText.copy(alpha = 0.76f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    val logoUrl = detail.logoUrl
    if (!logoUrl.isNullOrBlank()) {
        // iOS hero logo height — 160pt on compact phones, full width.
        ThumbhashImage(
            url = logoUrl,
            thumbhash = null,
            contentDescription = detail.title,
            contentScale = ContentScale.Fit,
            transparent = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
        return
    }

    // iOS PhoneHeroTitle: primary 30pt heavy, optional subtitle 13pt heavy
    // tracked, spacing 4.
    val (primary, subtitle) = splitHeroTitle(detail.title)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = primary,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = DetailPrimaryText,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle.uppercase(),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp,
                color = DetailPrimaryText.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Mirrors `PhoneHeroMetadata.splitTitle` — splits "Monarch: Legacy of
 * Monsters" into a heavier primary line over a lighter subtitle.
 */
private fun splitHeroTitle(raw: String): Pair<String, String?> {
    val separators = listOf(": ", " — ", " – ", " - ")
    for (sep in separators) {
        val idx = raw.indexOf(sep)
        if (idx >= 0) {
            val head = raw.substring(0, idx).trim()
            val tail = raw.substring(idx + sep.length).trim()
            if (head.isNotEmpty() && tail.isNotEmpty()) {
                return head to tail
            }
        }
    }
    return raw to null
}

@Composable
private fun EyebrowChip(text: String) {
    Surface(
        shape = PillShape,
        color = SiloSurfaceElevated,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = DetailPrimaryText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SourceRow(tokens: List<String>, ratingChip: String?) {
    // iOS PhoneDetailHero.sourceRow: HStack spacing 8, tokens 14pt medium
    // (0.85 alpha), middle-dot separators 14pt semibold (0.4 alpha).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(
                    text = "·",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailPrimaryText.copy(alpha = 0.4f),
                )
            }
            Text(
                text = token,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DetailPrimaryText.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
        if (!ratingChip.isNullOrBlank()) {
            ContentRatingChip(text = ratingChip)
        }
    }
}

@Composable
private fun ContentRatingChip(text: String) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = DetailPrimaryText.copy(alpha = 0.55f),
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp,
            color = DetailPrimaryText,
        )
    }
}

@Composable
private fun OverviewBlock(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    val canExpand = text.length > 140

    // iOS overviewBlock: 15pt regular (0.78 alpha), lineSpacing 3, top pad 8.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable(enabled = canExpand) { expanded = !expanded },
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Normal,
            color = DetailPrimaryText.copy(alpha = 0.78f),
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun FactsRow(tokens: List<String>) {
    // iOS FlowingFactsRow: tokens 13pt medium (0.78 alpha), middle-dot
    // separators 13pt semibold (0.4 alpha), spacing 8, top pad 4.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tokens.forEachIndexed { index, token ->
            if (index > 0) {
                Text(
                    text = "·",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailPrimaryText.copy(alpha = 0.4f),
                )
            }
            Text(
                text = token,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DetailPrimaryText.copy(alpha = 0.78f),
            )
        }
    }
}

// ── Action buttons ────────────────────────────────────────────

/**
 * Solid white capsule used for the primary "Play" CTA in the hero.
 * Full width, 52dp tall, label inline with the icon.
 */
@Composable
fun PrimaryPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        // iOS PhonePrimaryPillButton: icon 17pt bold, label 17pt semibold, spacing 10.
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 44dp circle toggle used for favorite, watchlist, watched. Active
 * state lifts the fill and stroke alpha — same energy as the iOS pill.
 */
@Composable
fun CircleActionButton(
    icon: ImageVector,
    activeIcon: ImageVector,
    isActive: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    activeTint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (isActive) 0.18f else 0.10f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isActive) 0.55f else 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // iOS PhoneCircleActionButton: icon 16pt semibold.
        Icon(
            imageVector = if (isActive) activeIcon else icon,
            contentDescription = contentDescription,
            tint = if (isActive) activeTint else Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Overflow circle button that opens a dropdown — used for episode →
 * series/season jumps and for grouped audio/subtitle/info actions.
 */
@Composable
fun CircleOverflowButton(
    contentDescription: String = "More options",
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            menuContent { expanded = false }
        }
    }
}

/**
 * Compact dark capsule sitting under the action row that surfaces the
 * currently selected video version and opens the picker on tap.
 */
@Composable
fun VersionPillButton(
    currentValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Version",
) {
    Surface(
        shape = PillShape,
        color = Color.White.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        modifier = modifier
            .height(36.dp)
            .clickable(onClick = onClick),
    ) {
        // iOS PhoneVersionPillButton: leading stack icon 13pt (0.78), label
        // 12pt medium (0.7), value 13pt semibold, chevron 10pt (0.6).
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                text = currentValue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/**
 * Full action stack: Play pill on top, circle row below, optional version pill.
 *
 * `downloadSlot` is rendered as a sibling of the favorite / watchlist /
 * watched buttons when non-null. Pass-through slot rather than baked-in
 * params keeps HeroActionStack agnostic of download concepts; the slot
 * function decides icon, state, and click behavior.
 */
@Composable
fun HeroActionStack(
    primaryLabel: String,
    onPlay: () -> Unit,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    isWatched: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onToggleWatched: () -> Unit,
    userRating: Int? = null,
    onRateClick: (() -> Unit)? = null,
    versionLabel: String? = null,
    onVersionClick: (() -> Unit)? = null,
    overflow: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    downloadSlot: (@Composable () -> Unit)? = null,
    // When non-null there is resume progress: tapping the primary button opens
    // the "Continue Watching?" dialog (Resume / Play from Beginning) instead of
    // resuming immediately, matching iOS. Null → the button plays directly.
    onPlayFromBeginning: (() -> Unit)? = null,
    resumeStoppedAtLabel: String? = null,
) {
    var showResumeDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrimaryPillButton(
            icon = Icons.Filled.PlayArrow,
            label = primaryLabel,
            onClick = { if (onPlayFromBeginning != null) showResumeDialog = true else onPlay() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleActionButton(
                icon = Icons.Filled.FavoriteBorder,
                activeIcon = Icons.Filled.Favorite,
                isActive = isFavorite,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = onToggleFavorite,
            )
            CircleActionButton(
                icon = Icons.Filled.BookmarkBorder,
                activeIcon = Icons.Filled.Bookmark,
                isActive = isInWatchlist,
                contentDescription = if (isInWatchlist) "Remove from watchlist" else "Add to watchlist",
                onClick = onToggleWatchlist,
            )
            CircleActionButton(
                icon = Icons.Filled.CheckCircleOutline,
                activeIcon = Icons.Filled.CheckCircle,
                isActive = isWatched,
                contentDescription = if (isWatched) "Mark as unwatched" else "Mark as watched",
                onClick = onToggleWatched,
            )
            if (onRateClick != null) {
                CircleActionButton(
                    icon = Icons.Filled.StarBorder,
                    activeIcon = Icons.Filled.Star,
                    isActive = userRating != null,
                    contentDescription = userRating?.let { "Rated $it of 5" } ?: "Rate",
                    onClick = onRateClick,
                )
            }
            if (downloadSlot != null) {
                downloadSlot()
            }
            if (overflow != null) {
                CircleOverflowButton(menuContent = overflow)
            }
        }
        if (versionLabel != null && onVersionClick != null) {
            VersionPillButton(
                currentValue = versionLabel,
                onClick = onVersionClick,
            )
        }
    }

    if (showResumeDialog && onPlayFromBeginning != null) {
        val stoppedAt = resumeStoppedAtLabel
        AlertDialog(
            onDismissRequest = { showResumeDialog = false },
            title = { Text("Continue Watching?") },
            text = if (stoppedAt != null) {
                { Text("You stopped at $stoppedAt.") }
            } else {
                null
            },
            confirmButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    onPlay()
                }) { Text("Resume") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showResumeDialog = false
                    onPlayFromBeginning()
                }) { Text("Play from Beginning") }
            },
        )
    }
}

// ── Section header ────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    label: String? = null,
    trailingText: String? = null,
    modifier: Modifier = Modifier,
) {
    // iOS PhoneSectionHeader: optional eyebrow label 11pt bold tracking 1.6
    // (0.55 alpha), title 22pt semibold single-line, trailing 13pt medium,
    // column spacing 4.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SafePadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label.uppercase(),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    color = SiloOnSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = DetailPrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SiloSecondaryText,
            )
        }
    }
}

// ── Season chips ──────────────────────────────────────────────

@Composable
fun SeasonChips(
    seasons: List<Season>,
    selectedSeasonNumber: Int,
    onSeasonSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (seasons.size <= 1) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = SafePadding),
        horizontalArrangement = Arrangement.spacedBy(SmallPadding),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            seasons,
            key = { it.contentId },
            contentType = { "season-chip" },
        ) { season ->
            val isSelected = season.seasonNumber == selectedSeasonNumber
            val label = if (season.isSpecials) "Specials" else "Season ${season.seasonNumber}"
            // iOS PhoneSeasonChips: 14pt (semibold selected / medium
            // unselected), hpad 16, height 36, unselected fill white-0.06.
            Surface(
                shape = PillShape,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.06f),
                border = if (isSelected) {
                    null
                } else {
                    androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                },
                modifier = Modifier
                    .height(36.dp)
                    .clickable { onSeasonSelected(season.seasonNumber) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) Color.Black else Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

// ── Hero metadata helpers (mirror PhoneHeroMetadata.swift) ────

object HeroMetadata {

    fun movieEyebrow(detail: ItemDetail): String? {
        val rating = detail.ratingImdb?.let { "IMDb %.1f".format(it) }
            ?: detail.ratingTmdb?.let { "TMDB %.1f".format(it) }
        return rating
    }

    fun seriesEyebrow(detail: ItemDetail): String? = movieEyebrow(detail)

    fun episodeEyebrow(detail: ItemDetail): String? {
        val s = detail.seasonNumber
        val e = detail.episodeNumber
        return when {
            s != null && e != null -> "Season $s · Episode $e"
            s != null -> "Season $s"
            else -> null
        }
    }

    fun movieSourceTokens(detail: ItemDetail): List<String> = buildList {
        if (detail.year > 0) add(detail.year.toString())
        if (detail.runtime > 0) add(formatRuntime(detail.runtime))
        detail.studios.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    fun seriesSourceTokens(detail: ItemDetail): List<String> = buildList {
        if (detail.year > 0) add(detail.year.toString())
        detail.seasonCount?.takeIf { it > 0 }?.let {
            add("$it Season${if (it > 1) "s" else ""}")
        }
        detail.networks.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    fun movieFactsLine(detail: ItemDetail): List<String> = buildList {
        if (detail.genres.isNotEmpty()) {
            add(detail.genres.take(3).joinToString(" · "))
        }
        detail.ratingImdb?.let { add("IMDb %.1f".format(it)) }
    }

    fun seriesFactsLine(detail: ItemDetail): List<String> = buildList {
        if (detail.genres.isNotEmpty()) {
            add(detail.genres.take(3).joinToString(" · "))
        }
        detail.ratingImdb?.let { add("IMDb %.1f".format(it)) }
    }

    private fun formatRuntime(minutes: Int): String {
        if (minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}

// ── Play label helper ─────────────────────────────────────────

// iOS parity: the play-button label stays neutral ("Play" / "Play S·E") even
// when there's resume progress. The resume-vs-restart choice is offered in the
// "Continue Watching?" dialog on tap (see HeroActionStack), not baked into the
// label — mirrors silo-apple MovieDetailContent.swift +
// ViewExtensions.continuumResumePlaybackAlert (Jim phone QA 2026-07-10).
fun computePlayLabel(
    detail: ItemDetail,
    nextEpisodeLabel: String? = null,
): String {
    if (detail.type == "series" && nextEpisodeLabel != null) {
        return "Play $nextEpisodeLabel"
    }
    if (detail.type == "episode") {
        val s = detail.seasonNumber
        val e = detail.episodeNumber
        if (s != null && e != null) {
            return if (s == 0) "Play E$e" else "Play S$s·E$e"
        }
    }
    return "Play"
}

/** Clock for the "Continue Watching?" dialog's "You stopped at …" line. */
fun formatResumeStoppedAt(positionSeconds: Double): String {
    val totalSec = positionSeconds.toInt().coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Details list (key/value facts) ────────────────────────────

@Composable
fun DetailFactsList(
    detail: ItemDetail,
    modifier: Modifier = Modifier,
) {
    val rows = buildDetailFacts(detail)
    if (rows.isEmpty()) return

    SectionHeader(title = "Details")
    Spacer(modifier = Modifier.height(14.dp))

    // iOS PhoneDetailFactsSection: thin 1px dividers (white 0.08) between
    // rows, label 11pt bold tracking 1.2 (0.5 alpha) width 100, value 14pt
    // regular, top-aligned, hspacing 16, row vpad 12.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SafePadding),
    ) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = label.uppercase(),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = SiloOnSurface.copy(alpha = 0.7f),
                    modifier = Modifier.width(100.dp),
                )
                Text(
                    text = value,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = DetailPrimaryText,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun buildDetailFacts(detail: ItemDetail): List<Pair<String, String>> = buildList {
    detail.releaseDate?.takeIf { it.isNotBlank() }?.let { add("Release date" to it) }
    detail.firstAirDate?.takeIf { it.isNotBlank() }?.let { add("First aired" to it) }
    detail.lastAirDate?.takeIf { it.isNotBlank() }?.let { add("Last aired" to it) }
    if (detail.runtime > 0) add("Runtime" to runtimeText(detail.runtime))
    detail.contentRating?.takeIf { it.isNotBlank() }?.let { add("Rated" to it) }
    if (detail.studios.isNotEmpty()) add("Studio" to detail.studios.joinToString(", "))
    if (detail.networks.isNotEmpty()) add("Network" to detail.networks.joinToString(", "))
    if (detail.countries.isNotEmpty()) add("Country" to detail.countries.joinToString(", "))
    // Genres are already carried by the hero facts line (HeroMetadata); iOS's
    // detail facts list omits them, so listing them here duplicated them.
}

private fun runtimeText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
