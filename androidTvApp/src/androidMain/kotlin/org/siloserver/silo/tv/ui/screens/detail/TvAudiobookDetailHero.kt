package org.siloserver.silo.tv.ui.screens.detail

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.siloserver.silo.common.audiobook.AudiobookText
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.localizedResources
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.tv.ui.components.TvPrimaryPillButton
import org.siloserver.silo.tv.ui.components.TvSecondaryPillButton
import org.siloserver.silo.tv.ui.components.TvSquareToggleButton
import org.siloserver.silo.tv.ui.navigation.explicitTvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.theme.Spacing

/**
 * The tvOS "Lounge" audiobook hero (`TVAudiobookDetailView`): one first
 * screen holding the cover beside the book's identity (eyebrow, title, gold
 * series line, credits), its listening state and the action row, over a
 * quiet wash of the cover. It ends a little above the screen's bottom edge
 * so the About heading shows beneath it and the page reads as scrollable.
 *
 * tvOS points map to these dp at half scale (a 1920-point screen is 960dp).
 */
@Composable
internal fun TvAudiobookDetailHero(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    playFocus: FocusRequester,
    onPlay: (contentId: String, fileId: Int?, versionChosen: Boolean, audioTrackIndex: Int?, audioPickedThisSession: Boolean, subtitleSelection: TvSubtitleLaunchSelection?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    /** Whole-book chapter starts with their display titles, in order. */
    chapters: List<Pair<String, Double>>,
    totalSeconds: Double,
    pageSurface: Color,
    onOpenChapters: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = localizedResources()
    // Play/Resume launch with a book-global position and NO part fileId: the
    // shared player VM stitches the whole-book timeline and resolves which part
    // contains the position. Mirrors Apple `TVAudiobookViewModel.performPrimary`.
    val position = (detail.userData?.positionSeconds ?: 0.0).coerceAtLeast(0.0)
    val isFinished = state.isWatched ||
        (totalSeconds > 0 && position > 0 && position >= totalSeconds - 5)
    val resumePosition = position.takeIf { it > 30 && !isFinished && totalSeconds > 0 }
    // The chapter dialog lists chapters only; parts have their own section below.
    val showsChapters = chapters.isNotEmpty()

    fun play(startPosition: Double?) {
        if (state.isProvisional) return
        onPlay(
            detail.contentId,
            null,
            false,
            state.selectedAudioIndex,
            state.audioPickedThisSession,
            explicitTvSubtitleLaunchSelection(state.selectedSubtitleIndex),
            detail.type,
            startPosition,
        )
    }

    // tvOS heroHeight 860 of 1080: centers the hero like a first screen
    // while the About heading peeks in below it.
    val heroHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * (860f / 1080f)).toDp() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight),
    ) {
        AudiobookBackdrop(detail = detail, pageSurface = pageSurface, modifier = Modifier.matchParentSize())

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Spacing.safeArea + 6.dp, end = Spacing.safeArea),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(42.dp),
        ) {
            ThumbhashImage(
                url = detail.posterUrl,
                thumbhash = detail.posterThumbhash,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                defaultArtwork = DefaultArtworkKind.Audiobook,
                // Keeps the card's cover while the full-size one loads.
                holdPreviousImage = true,
                modifier = Modifier
                    .size(230.dp)
                    .shadow(20.dp, RoundedCornerShape(CoverCorner), ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(RoundedCornerShape(CoverCorner))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(CoverCorner)),
            )

            // The text keeps tvOS's 520dp measure; the action row may use the
            // hero's full width (see below).
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(text = eyebrowText(resources, detail))

                Text(
                    text = AudiobookText.title(detail),
                    fontSize = 42.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).widthIn(max = IdentityWidth),
                )

                AudiobookText.seriesLine(resources, detail)?.let { series ->
                    Text(
                        text = series.uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = SeriesGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp).widthIn(max = IdentityWidth),
                    )
                }

                creditsText(resources, detail)?.let { credits ->
                    Text(
                        text = credits,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 9.dp).widthIn(max = IdentityWidth),
                    )
                }

                StateCluster(
                    resources = resources,
                    position = position,
                    totalSeconds = totalSeconds,
                    resumePosition = resumePosition,
                    isFinished = isFinished,
                    chapters = chapters,
                    modifier = Modifier.padding(top = 25.dp).widthIn(max = IdentityWidth),
                )

                // Every control keeps its size on focus, as on the movie and
                // series pages, so moving along the row never reflows it. Five
                // controls, with a toggle widened by its focused label, can
                // outgrow the hero in a longer language, so the row scrolls
                // with focus; the inner padding keeps focus rings unclipped.
                Row(
                    modifier = Modifier
                        .padding(top = 25.dp - FocusRoom)
                        .offset(x = -FocusRoom)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = FocusRoom, vertical = FocusRoom),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvPrimaryPillButton(
                        icon = if (resumePosition == null && isFinished) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                        title = when {
                            state.isProvisional -> stringResource(CommonR.string.common_loading)
                            resumePosition != null -> stringResource(CommonR.string.detail_resume)
                            isFinished -> stringResource(CommonR.string.detail_playAgain)
                            else -> stringResource(CommonR.string.common_play)
                        },
                        focusRequester = playFocus,
                        onClick = {
                            when {
                                resumePosition != null -> play(resumePosition)
                                isFinished -> play(0.0)
                                // The VM resolves a fresh start from its own snapshot.
                                else -> play(null)
                            }
                        },
                    )
                    if (showsChapters) {
                        TvSecondaryPillButton(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = stringResource(CommonR.string.detail_audiobook_chapters),
                            onClick = { if (!state.isProvisional) onOpenChapters() },
                        )
                    }
                    // Only with progress to discard: before the first listen Play
                    // starts at the beginning, and after the last Play Again does.
                    if (resumePosition != null) {
                        TvSecondaryPillButton(
                            icon = Icons.Filled.Replay,
                            title = stringResource(CommonR.string.detail_startOver),
                            onClick = { play(0.0) },
                        )
                    }
                    TvSquareToggleButton(
                        icon = Icons.Filled.FavoriteBorder,
                        iconActive = Icons.Filled.Favorite,
                        isActive = state.isFavorite,
                        contentDescription = stringResource(
                            if (state.isFavorite) CommonR.string.detail_more_removeFavorite else CommonR.string.detail_more_addFavorite,
                        ),
                        titleOnFocus = stringResource(CommonR.string.detail_favorite),
                        onClick = { if (!state.isProvisional) onToggleFavorite() },
                    )
                    TvSquareToggleButton(
                        icon = Icons.Filled.CheckCircleOutline,
                        iconActive = Icons.Filled.CheckCircle,
                        // The saved flag the toggle flips, not the near-the-end guess.
                        isActive = state.isWatched,
                        contentDescription = stringResource(
                            if (state.isWatched) CommonR.string.detail_audiobook_markNotFinished else CommonR.string.detail_audiobook_markFinished,
                        ),
                        titleOnFocus = stringResource(CommonR.string.detail_audiobook_finished),
                        onClick = { if (!state.isProvisional) onToggleFinished() },
                    )
                }
            }
        }
    }
}

private val CoverCorner = 8.dp

/** tvOS's 1040pt identity column at half scale. */
private val IdentityWidth = 520.dp

/** Room for a focused control's ring and scale inside the scrolling action row. */
private val FocusRoom = 8.dp

// Sizes are tvOS points at half scale, raised to the TV app's 14sp floor except
// the uppercase tracked chrome (eyebrow, series line), which keeps the
// detail hero eyebrow's approved 12sp (TvTypographyReadabilityTest).

/** tvOS `TVAudiobookStyle.gold`: the warm gold of the series locator. */
private val SeriesGold = Color(0.913f, 0.835f, 0.635f).copy(alpha = 0.8f)

/**
 * tvOS `TVAudiobookBackdrop`: the cover blurred and dimmed under a dark teal
 * glow, fading to near-black, then into the page surface the sections sit on.
 */
@Composable
private fun AudiobookBackdrop(detail: ItemDetail, pageSurface: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        if (!detail.posterUrl.isNullOrBlank() || !detail.posterThumbhash.isNullOrBlank()) {
            // The cover's thumbhash is already a soft few-pixel image, so
            // stretched it reads as tvOS's 70pt blur without a blur effect,
            // which costs a large surface every frame on TV chips and does
            // nothing below API 31. Without one, a tiny decode stands in.
            val thumbhash = detail.posterThumbhash?.takeIf { it.isNotBlank() }
            ThumbhashImage(
                url = if (thumbhash != null) null else detail.posterUrl,
                thumbhash = thumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                decodeSizePx = 24,
                crossfadeMillis = 0,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.35f),
            )
        }
        val glowCenter = with(LocalDensity.current) {
            Offset(maxWidth.toPx() * 0.22f, maxHeight.toPx() * 0.4f)
        }
        val glowRadius = with(LocalDensity.current) { 600.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0.07f, 0.25f, 0.235f).copy(alpha = 0.9f), Color.Transparent),
                        center = glowCenter,
                        radius = glowRadius,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.35f),
                        0.50f to Color.Black.copy(alpha = 0.72f),
                        0.86f to Color.Black.copy(alpha = 0.90f),
                        1.00f to pageSurface,
                    ),
                ),
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 17.dp, height = 2.dp)
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(1.dp)),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}

/** "AUDIOBOOK · ROMANCE", or "AUDIOBOOK" without a genre. */
private fun eyebrowText(resources: Resources, detail: ItemDetail): String {
    val genre = detail.genres.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: return resources.getString(CommonR.string.detail_audiobook_label)
    // A genre is server text, so it capitalizes without the interface language.
    return resources.getString(CommonR.string.detail_audiobook_labelWithGenre, genre.uppercase())
}

/**
 * "by **A** · read by **N**": one catalog message, so translations can
 * reorder it, with every occurrence of each name in semibold white.
 */
private fun creditsText(resources: Resources, detail: ItemDetail): AnnotatedString? {
    val author = AudiobookText.authors(resources, detail, visible = 2)
    val narrator = AudiobookText.narrators(resources, detail, visible = 2)
    val line = when {
        author != null && narrator != null ->
            resources.getString(CommonR.string.detail_audiobook_byAuthorReadBy, author, narrator)
        author != null -> resources.getString(CommonR.string.detail_audiobook_byAuthor, author)
        narrator != null -> resources.getString(CommonR.string.detail_audiobook_readBy, narrator)
        else -> return null
    }
    return buildAnnotatedString {
        append(line)
        listOfNotNull(author, narrator).toSet().filter { it.isNotEmpty() }.forEach { name ->
            var start = line.indexOf(name)
            while (start >= 0) {
                addStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold), start, start + name.length)
                start = line.indexOf(name, start + name.length)
            }
        }
    }
}

/**
 * tvOS state cluster: a progress ring with the chapter and time left while
 * listening, the length and chapter count before the first listen, and a
 * full ring once finished.
 */
@Composable
private fun StateCluster(
    resources: Resources,
    position: Double,
    totalSeconds: Double,
    resumePosition: Double?,
    isFinished: Boolean,
    chapters: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
) {
    val runtime = AudiobookText.runtime(resources, totalSeconds)
    when {
        resumePosition != null -> {
            val fraction = (position / totalSeconds).coerceIn(0.0, 1.0)
            val chapterIndex = chapters.indexOfLast { it.second <= position }.takeIf { it >= 0 }
            val line1 = chapterIndex?.let {
                resources.getString(
                    CommonR.string.detail_audiobook_currentChapter,
                    LocaleFormat.integer(it + 1),
                    chapters[it].first,
                )
            } ?: AudiobookText.runtime(resources, position).takeIf { it.isNotEmpty() }
                ?.let { resources.getString(CommonR.string.detail_audiobook_listened, it) }
                ?: resources.getString(CommonR.string.detail_audiobook_inProgress)
            val line2 = listOfNotNull(
                chapterIndex?.let {
                    resources.getString(
                        CommonR.string.detail_audiobook_intoChapter,
                        AudiobookText.clock(position - chapters[it].second),
                    )
                },
                AudiobookText.runtime(resources, (totalSeconds - position).coerceAtLeast(0.0)).takeIf { it.isNotEmpty() }
                    ?.let { resources.getString(CommonR.string.detail_audiobook_leftInBook, it) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(fraction = fraction.toFloat(), label = LocaleFormat.percent((fraction * 100 + 0.5).toInt()))
                Spacer(Modifier.width(11.dp))
                StateLines(line1, line2)
            }
        }
        isFinished -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(fraction = 1f, label = LocaleFormat.percent(100))
            Spacer(Modifier.width(11.dp))
            StateLines(stringResource(CommonR.string.detail_audiobook_finished), runtime.takeIf { it.isNotEmpty() })
        }
        else -> {
            val line2 = listOfNotNull(
                runtime.takeIf { it.isNotEmpty() },
                chapters.size.takeIf { it > 0 }?.let {
                    pluralStringResource(CommonR.plurals.detail_audiobook_chapterCount, it, it, LocaleFormat.integer(it))
                },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            Box(modifier = modifier) {
                StateLines(stringResource(CommonR.string.detail_audiobook_notStarted), line2)
            }
        }
    }
}

@Composable
private fun StateLines(line1: String, line2: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = line1,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        line2?.let {
            Text(
                text = it,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float, label: String) {
    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.16f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
