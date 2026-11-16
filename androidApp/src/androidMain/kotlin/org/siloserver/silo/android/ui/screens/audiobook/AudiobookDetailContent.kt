package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.SiloDropdownMenuItem
import org.siloserver.silo.android.ui.screens.detail.AdaptiveDetailHero
import org.siloserver.silo.android.ui.screens.detail.DetailFactsList
import org.siloserver.silo.android.ui.screens.detail.DetailPageSurface
import org.siloserver.silo.android.ui.screens.detail.DownloadCircleButton
import org.siloserver.silo.android.ui.screens.detail.HeaderSettledDp
import org.siloserver.silo.android.ui.screens.detail.HeroActionStack
import org.siloserver.silo.android.ui.screens.detail.HeroToggleLabels
import org.siloserver.silo.android.ui.screens.detail.LocalDetailActionsReady
import org.siloserver.silo.android.ui.screens.detail.LocalDetailScrollState
import org.siloserver.silo.android.ui.screens.detail.SafePadding
import org.siloserver.silo.android.ui.screens.detail.SectionHeader
import org.siloserver.silo.android.ui.screens.detail.CastCrewSection
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.audiobook.buildAudiobookTimeline
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.audiobook.AudiobookText
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.common.i18n.localizedResources
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.audiobook.AudiobookNarration
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.ebook.MediaRelatedItem
import android.content.res.Resources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.TextStyle
import org.siloserver.silo.model.ebook.MediaPerson

/**
 * Phone audiobook detail, drawn on the same hero, action row, section headers
 * and Details list as movies and series, after Apple's
 * `AudiobookDetailContent`. Book-specific pieces are the cover-forward hero
 * (a book without a backdrop washes the page with its cover), the listening
 * Play pill, chapters and parts, the authors and narrators rail, and the
 * series, author and similar cover rails.
 */
@Composable
fun AudiobookDetailContent(
    detail: ItemDetail,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    selectedFileId: Int? = null,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    // Primary play/resume: launches with the BOOK-GLOBAL resume position (the
    // player VM resolves which part contains it) — never paired with a part's
    // fileId. Mirrors Apple `audioStore.play(contentId:restart:false)`.
    onPlayClick: () -> Unit,
    // Restart the whole book from 0:00 (Apple `play(restart: true)`).
    onPlayFromStartClick: () -> Unit = {},
    // Play from a whole-book (global) offset — a part's start offset or a
    // chapter's global start (Apple `play(startPosition:)`).
    onPlayFromPositionClick: (startPositionSeconds: Double) -> Unit = {},
    onChapterClick: (VersionChapter) -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onToggleFinished: () -> Unit = {},
    onPersonClick: (String) -> Unit = {},
    /** Opens another book: a series entry, another narration, or a related title. */
    onItemClick: (String) -> Unit = {},
    onDownloadClick: (() -> Unit)? = null,
    translation: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val resources = localizedResources()
    val meta = detail.audiobook
    val playableVersion = selectedFileId
        ?.let { fileId -> detail.versions.firstOrNull { it.fileId == fileId } }
        ?: detail.versions.firstOrNull()
    // Whole-book timeline stitched from the item's audiobook-part files — the
    // same math the player VM uses. Null when there are no audio parts.
    val timeline = remember(detail) {
        buildAudiobookTimeline(
            versions = detail.versions,
            serverTotalSeconds = meta?.totalDurationSeconds?.toDouble(),
        )
    }
    val totalSeconds = AudiobookText.totalDurationSeconds(detail, timeline?.totalSeconds ?: playableVersion?.duration)
    val positionSeconds = (detail.userData?.positionSeconds ?: 0.0).coerceAtLeast(0.0)
    val isFinished = detail.userData?.played == true ||
        (totalSeconds > 0.0 && positionSeconds > 0.0 && positionSeconds >= totalSeconds - 5.0)
    // Resume only past 30s and before the end; a finished book plays again.
    val resumeSeconds = positionSeconds.takeIf { it > 30.0 && !isFinished }
    // Multi-part books get the stitched whole-book chapters (each starts at its
    // global offset); a single file keeps its own chapters.
    val chapters: List<VersionChapter> = remember(detail, timeline, playableVersion) {
        if (timeline != null && !timeline.isSingle) {
            timeline.chapters.map { ch ->
                VersionChapter(
                    index = ch.index,
                    title = ch.title.orEmpty(),
                    startSeconds = ch.startSeconds,
                    endSeconds = ch.endSeconds ?: ch.startSeconds,
                )
            }
        } else {
            playableVersion?.chapters.orEmpty()
        }.sortedBy { it.startSeconds }
    }
    val parts = timeline?.tracks.orEmpty().takeIf { timeline?.isSingle == false }.orEmpty()
    val otherNarrations = meta?.otherNarrations.orEmpty().filter { it.title.isNotBlank() }
    val credits = remember(detail, resources) { creditMembers(resources, detail) }

    val hasBackdrop = !detail.backdropUrl.isNullOrBlank()
    // Without a backdrop the cover washes the page.
    val surfaceUrl = if (hasBackdrop) detail.backdropUrl else detail.posterUrl
    val surfaceThumbhash = if (hasBackdrop) detail.backdropThumbhash else detail.posterThumbhash
    val dominantColor by rememberDominantColor(
        imageUrl = surfaceUrl,
        fallback = SiloBackground,
        thumbhash = surfaceThumbhash,
    )

    val actionsReady = LocalDetailActionsReady.current
    val primaryLabel = when {
        playableVersion == null && actionsReady ->
            stringResource(CommonR.string.audiobook_detail_unavailable)
        resumeSeconds != null -> {
            val left = AudiobookText.runtime(resources, (totalSeconds - resumeSeconds).coerceAtLeast(0.0))
            if (left.isEmpty()) {
                stringResource(CommonR.string.detail_resume)
            } else {
                stringResource(CommonR.string.detail_resumeTimeLeft, left)
            }
        }
        isFinished -> stringResource(CommonR.string.detail_playAgain)
        else -> stringResource(CommonR.string.common_play)
    }
    val resumeFraction = resumeSeconds
        ?.takeIf { totalSeconds > 0 }
        ?.let { (it / totalSeconds).coerceIn(0.0, 1.0).toFloat() }

    val factsLine = listOfNotNull(
        detail.year?.takeIf { it > 0 }?.let { LocaleFormat.integer(it, currentNumberLocale()) },
        AudiobookText.runtime(resources, totalSeconds).takeIf { it.isNotEmpty() },
    )
    // Series position, the book's equivalent of an episode code, or else the
    // lead genre; both together usually wrap the hero line.
    val sourceTokens = listOfNotNull(
        AudiobookText.seriesLine(resources, detail)
            ?: detail.genres.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
    )

    val feedState = rememberLazyListState()
    // Feed the pinned header (see DetailScrollState).
    val detailScroll = LocalDetailScrollState.current
    if (detailScroll != null) {
        val density = LocalDensity.current
        LaunchedEffect(feedState, detailScroll, density) {
            snapshotFlow {
                if (feedState.firstVisibleItemIndex > 0) {
                    HeaderSettledDp
                } else {
                    with(density) { feedState.firstVisibleItemScrollOffset.toDp().value }
                }
            }.collect { detailScroll.update(it) }
        }
    }

    DetailPageSurface(
        backdropUrl = surfaceUrl,
        backdropThumbhash = surfaceThumbhash,
        tint = dominantColor,
    ) {
    DeferImagePresentationWhileScrolling(feedState) {
    LazyColumn(
        state = feedState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(contentType = "audiobook-hero") {
            AdaptiveDetailHero(
                detail = detail,
                eyebrow = stringResource(CommonR.string.detail_type_audiobook),
                sourceTokens = sourceTokens,
                factsLine = factsLine,
                dominantColor = dominantColor,
                directorText = AudiobookText.creditLine(resources, detail),
                overviewText = detail.overview,
                translation = translation,
                displayTitle = AudiobookText.title(detail),
                coverAspectRatio = if (hasBackdrop) null else 1f,
            ) {
                HeroActionStack(
                    primaryLabel = primaryLabel,
                    onPlay = {
                        when {
                            playableVersion == null -> Unit
                            // The player picks the resume point: the server's, or
                            // this device's newer one for a downloaded book.
                            resumeSeconds != null -> onPlayClick()
                            isFinished -> onPlayFromStartClick()
                            else -> onPlayClick()
                        }
                    },
                    primaryProgress = resumeFraction,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    isWatched = isFinished,
                    onToggleFavorite = onFavoriteClick,
                    onToggleWatchlist = onWatchlistClick,
                    onToggleWatched = onToggleFinished,
                    watchlistLabels = HeroToggleLabels(
                        caption = stringResource(CommonR.string.detail_audiobook_wantToListen),
                        turnOn = stringResource(CommonR.string.detail_audiobook_wantToListenAdd),
                        turnOff = stringResource(CommonR.string.detail_audiobook_wantToListenRemove),
                    ),
                    watchedLabels = HeroToggleLabels(
                        caption = stringResource(CommonR.string.detail_audiobook_finished),
                        turnOn = stringResource(CommonR.string.detail_audiobook_markFinished),
                        turnOff = stringResource(CommonR.string.detail_audiobook_markNotFinished),
                    ),
                    // Apple offers Download only for a book stored as one file.
                    downloadSlot = if (onDownloadClick != null && parts.size <= 1) {
                        {
                            DownloadCircleButton(
                                isDownloaded = isDownloaded,
                                progress = downloadProgress,
                                onClick = onDownloadClick,
                            )
                        }
                    } else {
                        null
                    },
                    overflow = { dismiss ->
                        SiloDropdownMenuItem(
                            text = { Text(stringResource(CommonR.string.detail_startOver)) },
                            leadingIcon = { Icon(Icons.Filled.Replay, contentDescription = null) },
                            onClick = {
                                dismiss()
                                onPlayFromStartClick()
                            },
                        )
                        otherNarrations.forEach { narration ->
                            SiloDropdownMenuItem(
                                text = { Text(narrationLabel(narration)) },
                                leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    onItemClick(narration.contentId)
                                },
                            )
                        }
                    },
                )
            }
        }

        if (chapters.isNotEmpty()) {
            item(contentType = "audiobook-chapters") {
                ChaptersSection(chapters = chapters, onChapterClick = onChapterClick)
            }
        }

        if (parts.size > 1) {
            item(contentType = "audiobook-parts") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(
                        title = stringResource(CommonR.string.detail_audiobook_parts),
                        trailingText = LocaleFormat.integer(parts.size),
                    )
                    Column(modifier = Modifier.padding(horizontal = SafePadding)) {
                        parts.forEachIndexed { index, track ->
                            val version = detail.versions.firstOrNull { it.fileId == track.fileId }
                            TimelineRow(
                                icon = { Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(13.dp)) },
                                title = partTitle(version, index),
                                trailing = AudiobookText.runtime(resources, track.durationSeconds),
                                showsDivider = index > 0,
                                onClick = { onPlayFromPositionClick(track.startOffsetSeconds) },
                            )
                        }
                    }
                }
            }
        }

        if (credits.isNotEmpty()) {
            item(contentType = "audiobook-credits") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(title = creditsTitle(detail))
                    CastCrewSection(cast = credits, onPersonClick = onPersonClick)
                }
            }
        }

        meta?.series?.takeIf { it.entries.isNotEmpty() }?.let { series ->
            item(contentType = "audiobook-series") {
                CoverRail(
                    title = series.name.takeIf { it.isNotBlank() }
                        ?: stringResource(CommonR.string.detail_audiobook_series),
                    items = series.entries,
                    onSelect = onItemClick,
                )
            }
        }

        if (otherNarrations.isNotEmpty()) {
            item(contentType = "audiobook-narrations") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(title = stringResource(CommonR.string.audiobook_detail_otherNarrations))
                    Column(modifier = Modifier.padding(horizontal = SafePadding)) {
                        otherNarrations.forEachIndexed { index, narration ->
                            NarrationRow(
                                narration = narration,
                                showsDivider = index > 0,
                                onClick = { onItemClick(narration.contentId) },
                            )
                        }
                    }
                }
            }
        }

        meta?.related?.alsoByAuthor?.takeIf { it.isNotEmpty() }?.let { alsoByAuthor ->
            item(contentType = "audiobook-more-by-author") {
                CoverRail(title = moreByAuthorTitle(detail), items = alsoByAuthor, onSelect = onItemClick)
            }
        }

        // A card seed's facts are partial; the fetched detail lists them all.
        if (actionsReady) {
            item(contentType = "audiobook-facts") {
                Column {
                    DetailFactsList(detail = detail, facts = AudiobookText.facts(resources, detail, totalSeconds, parts.size))
                }
            }
        }

        meta?.related?.similar?.takeIf { it.isNotEmpty() }?.let { similar ->
            item(contentType = "audiobook-similar") {
                CoverRail(
                    title = stringResource(CommonR.string.detail_moreLikeThis),
                    items = similar,
                    onSelect = onItemClick,
                )
            }
        }

        item(contentType = "audiobook-spacer") {
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    }
    }
}

private const val ChapterCollapseLimit = 8

/** Chapters with a play glyph and start time, the first eight until "Show all". */
@Composable
private fun ChaptersSection(
    chapters: List<VersionChapter>,
    onChapterClick: (VersionChapter) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) chapters else chapters.take(ChapterCollapseLimit)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(
            title = stringResource(CommonR.string.detail_audiobook_chapters),
            trailingText = LocaleFormat.integer(chapters.size),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = SafePadding)
                .animateContentSize(),
        ) {
            visible.forEachIndexed { index, chapter ->
                TimelineRow(
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    title = chapter.title.takeIf { it.isNotBlank() }
                        ?: stringResource(CommonR.string.detail_audiobook_chapter, LocaleFormat.integer(chapter.index + 1)),
                    trailing = AudiobookText.clock(chapter.startSeconds),
                    showsDivider = index > 0,
                    onClick = { onChapterClick(chapter) },
                )
            }
            if (chapters.size > ChapterCollapseLimit) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { showAll = !showAll }
                        .padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (showAll) {
                            stringResource(CommonR.string.detail_audiobook_chaptersShowLess)
                        } else {
                            pluralStringResource(
                                CommonR.plurals.detail_audiobook_chaptersShowAll,
                                chapters.size,
                                chapters.size,
                                LocaleFormat.integer(chapters.size),
                            )
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SiloOnSurface.copy(alpha = 0.6f),
                    )
                    Icon(
                        imageVector = if (showAll) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = SiloOnSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Apple `timelineRow`: a small glyph in a faint circle, the title, a quiet time or length. */
@Composable
private fun TimelineRow(
    icon: @Composable () -> Unit,
    title: String,
    trailing: String,
    showsDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showsDivider) RowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            GlyphCircle(icon)
            Text(
                text = title,
                fontSize = 15.sp,
                color = SiloOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = trailing,
                fontSize = 13.sp,
                style = TextStyle(fontFeatureSettings = TabularNumbers),
                color = SiloOnSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NarrationRow(narration: AudiobookNarration, showsDivider: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showsDivider) RowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            GlyphCircle { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(14.dp)) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = narrationLabel(narration),
                    fontSize = 15.sp,
                    color = SiloOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                narration.year?.let { year ->
                    Text(
                        text = LocaleFormat.integer(year, currentNumberLocale()),
                        fontSize = 13.sp,
                        color = SiloOnSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SiloOnSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun GlyphCircle(icon: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides SiloOnSurface,
        ) { icon() }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

/** Apple `PhonePosterRail` with square covers: title, then "Book N" or the year. */
@Composable
private fun CoverRail(title: String, items: List<MediaRelatedItem>, onSelect: (String) -> Unit) {
    val rowState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(title = title)
        DeferImagePresentationWhileScrolling(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = SafePadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(items, key = { it.contentId }, contentType = { "audiobook-cover" }) { item ->
                    CoverCard(item = item, onClick = { onSelect(item.contentId) })
                }
            }
        }
    }
}

private val CoverCardWidth = 132.dp

@Composable
private fun CoverCard(item: MediaRelatedItem, onClick: () -> Unit) {
    val subtitle = item.seriesIndex
        ?.let { index ->
            val number = if (index % 1.0 == 0.0) {
                LocaleFormat.integer(index.toInt(), currentNumberLocale())
            } else {
                LocaleFormat.decimal(index, maxFractionDigits = 1)
            }
            stringResource(CommonR.string.audiobook_series_bookNumber, number)
        }
        ?: item.year?.takeIf { it > 0 }?.let { LocaleFormat.integer(it, currentNumberLocale()) }
    Column(
        modifier = Modifier
            .width(CoverCardWidth)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThumbhashImage(
            url = item.posterUrl,
            thumbhash = null,
            contentDescription = item.title,
            defaultArtwork = DefaultArtworkKind.Audiobook,
            modifier = Modifier
                .size(CoverCardWidth)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            color = SiloOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = SiloOnSurface.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Authors then narrators as cast-rail entries, the role in the caption. A
 * person who wrote and reads the book appears once, as "Author & Narrator".
 */
private fun creditMembers(resources: Resources, detail: ItemDetail): List<CastMember> {
    data class Credit(val person: MediaPerson, var author: Boolean, var narrator: Boolean)
    val people = LinkedHashMap<String, Credit>()
    val credited = detail.audiobook?.authors.orEmpty().map { it to true } +
        detail.audiobook?.narrators.orEmpty().map { it to false }
    credited.forEach { (person, isAuthor) ->
        if (person.name.isBlank()) return@forEach
        val credit = people.getOrPut(person.personId ?: person.name) { Credit(person, false, false) }
        if (isAuthor) credit.author = true else credit.narrator = true
    }
    return people.values.mapIndexed { order, credit ->
        val role = when {
            credit.author && credit.narrator -> CommonR.string.detail_audiobook_roleAuthorAndNarrator
            credit.author -> CommonR.string.detail_audiobook_roleAuthor
            else -> CommonR.string.detail_audiobook_roleNarrator
        }
        CastMember(
            name = credit.person.name,
            character = resources.getString(role),
            order = order,
            personId = credit.person.personId,
            photoUrl = credit.person.photoUrl,
            photoThumbhash = credit.person.photoThumbhash,
        )
    }
}

@Composable
private fun creditsTitle(detail: ItemDetail): String {
    val hasAuthors = detail.audiobook?.authors.orEmpty().any { it.name.isNotBlank() }
    val hasNarrators = detail.audiobook?.narrators.orEmpty().any { it.name.isNotBlank() }
    return stringResource(
        when {
            hasAuthors && !hasNarrators -> CommonR.string.detail_audiobook_authors
            !hasAuthors && hasNarrators -> CommonR.string.detail_audiobook_narrators
            else -> CommonR.string.detail_audiobook_authorsAndNarrators
        },
    )
}

@Composable
private fun moreByAuthorTitle(detail: ItemDetail): String {
    val authors = detail.audiobook?.authors.orEmpty()
    val name = authors.singleOrNull()?.name
    return if (name != null && name.isNotBlank() && !name.contains(",")) {
        stringResourceWithNames(CommonR.string.detail_audiobook_moreByNamedAuthor, name)
    } else {
        stringResource(CommonR.string.detail_audiobook_moreByTheseAuthors)
    }
}

private fun narrationLabel(narration: AudiobookNarration): String =
    narration.narrators.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: narration.title

/** Part row label: the file name when present, else "Part {index}", as Apple's `partTitle`. */
@Composable
private fun partTitle(version: FileVersion?, position: Int): String {
    version?.fileName?.takeIf { it.isNotBlank() }?.let { return it }
    val rawIndex = version?.presentationPartIndex ?: position
    val displayIndex = if (rawIndex <= 0) rawIndex + 1 else rawIndex
    return stringResource(CommonR.string.audiobook_detail_partNumber, displayIndex)
}
