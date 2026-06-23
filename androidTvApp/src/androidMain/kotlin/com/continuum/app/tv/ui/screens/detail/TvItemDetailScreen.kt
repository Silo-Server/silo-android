package com.continuum.app.tv.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.continuum.app.model.audiobook.AudiobookNarration
import com.continuum.app.model.catalog.EpisodeListItem
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.catalog.VersionChapter
import com.continuum.app.model.catalog.isAudiobookItemType
import com.continuum.app.model.ebook.MediaRelatedItem
import com.continuum.app.model.section.SectionItem
import com.continuum.app.model.watchtogether.RoomSnapshot
import com.continuum.app.tv.ui.components.TvDialogOption
import com.continuum.app.tv.ui.components.TvErrorScreen
import com.continuum.app.tv.ui.components.TvHeroActionPill
import com.continuum.app.tv.ui.components.TvLoadingScreen
import com.continuum.app.tv.ui.components.TvMediaRow
import com.continuum.app.tv.ui.components.TvOptionDialog
import com.continuum.app.tv.ui.components.TvPrimaryPillButton
import com.continuum.app.tv.ui.components.TvSecondaryPillButton
import com.continuum.app.tv.ui.components.TvSquareToggleButton
import com.continuum.app.tv.ui.components.TvPillVariant
import com.continuum.app.tv.ui.components.TvRowStyle
import com.continuum.app.tv.ui.screens.audiobook.formatAudiobookTime
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.TvSmoothBringIntoViewSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TvItemDetailScreen(
    contentId: String,
    seasonNumber: Int? = null,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, subtitleTrackIndex: Int?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onItemDetail: (contentId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
    onWatchTogether: (RoomSnapshot) -> Unit,
    onOpenPerson: (personId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: TvItemDetailViewModel = koinViewModel(
        key = "item-detail-$contentId-${seasonNumber ?: "default"}",
        parameters = { parametersOf(contentId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(state.detail?.contentId, seasonNumber, state.seasons, state.selectedSeason) {
        val detail = state.detail ?: return@LaunchedEffect
        if (detail.type != "series" || seasonNumber == null) return@LaunchedEffect
        if (state.selectedSeason == seasonNumber) return@LaunchedEffect
        if (state.seasons.any { it.seasonNumber == seasonNumber }) {
            viewModel.onSeasonSelected(seasonNumber)
        }
    }

    when {
        state.isLoading && state.detail == null -> TvLoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.error != null && state.detail == null -> TvErrorScreen(
            message = state.error!!,
            onRetry = viewModel::loadAll,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.detail != null -> TvDetailContent(
            detail = state.detail!!,
            state = state,
            viewModel = viewModel,
            onPlay = onPlay,
            onItemDetail = onItemDetail,
            onSeriesClick = onSeriesClick,
            onSeasonClick = onSeasonClick,
            onOpenPerson = onOpenPerson,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvDetailContent(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    viewModel: TvItemDetailViewModel,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, subtitleTrackIndex: Int?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onItemDetail: (contentId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
    onOpenPerson: (personId: Long) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val firstCastFocus = remember { FocusRequester() }
    val firstSimilarFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isAudiobook = isAudiobookItemType(detail.type)

    // Default focus → Play (user-initiated), mirroring Apple's
    // `defaultFocus($playFocused, true, priority: .userInitiated)`.
    LaunchedEffect(detail.contentId) {
        runCatching { playFocus.requestFocus() }
    }

    val showsEpisodeRail = detail.type in setOf("series", "season", "episode") &&
        state.episodes.isNotEmpty()
    val showsSeasonChips = detail.type in setOf("series", "season", "episode") && state.seasons.size > 1
    val showsCastSection = !isAudiobook && detail.cast.isNotEmpty()
    val showsSimilarRail = !isAudiobook && detail.type != "episode" && state.moreLikeThis.isNotEmpty()
    val showsDetailsSection = !isAudiobook && remember(detail) { detail.hasTvDetailFacts() }
    val audiobookParts = remember(detail.versions) { detail.versions }
    val audiobookChapters = remember(detail.versions) { audiobookDisplayChapters(detail.versions) }
    val audiobookSeries = detail.audiobook?.series
    val audiobookOtherNarrations = detail.audiobook?.otherNarrations.orEmpty()
    val audiobookAlsoByAuthor = detail.audiobook?.related?.alsoByAuthor.orEmpty()
    val audiobookRelated = detail.audiobook?.related?.similar.orEmpty()
    val audiobookFallbackRelated = remember(isAudiobook, audiobookRelated, state.moreLikeThis) {
        if (isAudiobook && audiobookRelated.isEmpty()) {
            state.moreLikeThis.map(::sectionItemToAudiobookRelatedItem)
        } else {
            emptyList()
        }
    }
    var chaptersDialogOpen by remember(detail.contentId) { mutableStateOf(false) }

    // The first focusable body rail (episode rail, else cast). An Up press from
    // it scrolls back to the hero and returns focus into the action cluster —
    // the Compose analogue of Apple's `focusScope` Up traversal into the hero
    // actions section.
    val returnToHero: () -> Boolean = {
        coroutineScope.launch {
            listState.animateScrollToItem(0)
            runCatching { playFocus.requestFocus() }
        }
        true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                item(key = "hero", contentType = "detail-hero") {
                    if (isAudiobook) {
                        TvAudiobookDetailHero(
                            detail = detail,
                            state = state,
                            playFocus = playFocus,
                            onPlay = onPlay,
                            overview = detail.overview,
                        )
                    } else {
                        TvDetailHero(
                            title = detail.title,
                            seriesTitle = if (detail.type == "episode") detail.seriesTitle else null,
                            logoUrl = detail.logoUrl,
                            backdropUrl = detail.backdropUrl,
                            backdropThumbhash = detail.backdropThumbhash,
                            eyebrow = if (detail.type == "episode") null else TvDetailMetadata.eyebrow(detail),
                            sourceTokens = TvDetailMetadata.sourceTokens(detail),
                            ratingChip = TvDetailMetadata.ratingChip(detail),
                            overview = detail.overview,
                            tagline = detail.tagline,
                            factsLine = TvDetailMetadata.factsLine(detail),
                            starringText = TvDetailMetadata.starringText(detail),
                            actions = {
                                HeroActionRow(
                                    detail = detail,
                                    state = state,
                                    viewModel = viewModel,
                                    playFocus = playFocus,
                                    onPlay = onPlay,
                                    onSeriesClick = onSeriesClick,
                                    onSeasonClick = onSeasonClick,
                                )
                            },
                        )
                    }
                }

                // Body = VStack(spacing 72), horizontal safeArea, hero→body 48.
                item(key = "body", contentType = "detail-body") {
                    Column(
                        modifier = Modifier.padding(top = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(72.dp),
                    ) {
                        if (isAudiobook && audiobookParts.size > 1) {
                            TvAudiobookPartsSection(
                                parts = audiobookParts,
                                onPartSelected = { part ->
                                    onPlay(
                                        detail.contentId,
                                        part.fileId,
                                        state.selectedAudioIndex,
                                        state.selectedSubtitleIndex,
                                        detail.type,
                                        0.0,
                                    )
                                },
                                firstRowUpFocusRequester = playFocus,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (isAudiobook && audiobookChapters.isNotEmpty()) {
                            TvAudiobookChaptersSection(
                                chapters = audiobookChapters,
                                onOpenChapters = { chaptersDialogOpen = true },
                                upFocusRequester = if (audiobookParts.size > 1) null else playFocus,
                                onDirectionUp = returnToHero,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (isAudiobook && audiobookSeries?.entries?.isNotEmpty() == true) {
                            TvAudiobookRelatedRailSection(
                                title = audiobookSeries.name.takeIf { it.isNotBlank() } ?: "Series",
                                items = audiobookSeries.entries,
                                onItemDetail = onItemDetail,
                                upFocusRequester = playFocus,
                                onDirectionUp = returnToHero,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (isAudiobook && audiobookOtherNarrations.isNotEmpty()) {
                            TvAudiobookNarrationsSection(
                                narrations = audiobookOtherNarrations,
                                onNarrationSelected = { narration -> onItemDetail(narration.contentId) },
                                firstRowUpFocusRequester = playFocus,
                                onDirectionUp = returnToHero,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (isAudiobook && audiobookAlsoByAuthor.isNotEmpty()) {
                            TvAudiobookRelatedRailSection(
                                title = "More by Author",
                                items = audiobookAlsoByAuthor,
                                onItemDetail = onItemDetail,
                                upFocusRequester = playFocus,
                                onDirectionUp = returnToHero,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (isAudiobook && (audiobookRelated.isNotEmpty() || audiobookFallbackRelated.isNotEmpty())) {
                            TvAudiobookRelatedRailSection(
                                title = "Related",
                                items = audiobookRelated.ifEmpty { audiobookFallbackRelated },
                                onItemDetail = onItemDetail,
                                upFocusRequester = playFocus,
                                onDirectionUp = returnToHero,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (showsEpisodeRail) {
                            EpisodesSection(
                                detail = detail,
                                state = state,
                                showsSeasonChips = showsSeasonChips,
                                onReturnToHero = returnToHero,
                                onSeasonSelected = { season ->
                                    if (detail.type == "series") {
                                        viewModel.onSeasonSelected(season.seasonNumber)
                                    } else if (season.contentId != detail.contentId) {
                                        onItemDetail(season.contentId)
                                    }
                                },
                                // tvOS parity: OK navigates to the episode's own
                                // detail page (version pick / mark watched / play)
                                // rather than launching playback directly.
                                onEpisodeSelected = { episode ->
                                    onItemDetail(episode.contentId)
                                },
                            )
                        }

                        if (showsCastSection) {
                            TvCastCrewSection(
                                cast = detail.cast,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                                firstItemFocusRequester = firstCastFocus,
                                // Cast is the first body rail only when there is no
                                // episode rail above it; Up then returns to the hero.
                                onDirectionUp = if (showsEpisodeRail) null else returnToHero,
                                onCastMemberClick = { member ->
                                    viewModel.openPerson(member, onOpenPerson)
                                },
                            )
                        }

                        if (showsDetailsSection) {
                            DetailsSection(
                                detail = detail,
                                modifier = Modifier.padding(horizontal = Spacing.safeArea),
                            )
                        }

                        if (showsSimilarRail) {
                            // tvOS `TVSimilarRail`: an editorial detail section
                            // header (Recommended / More Like This) over a bare
                            // poster rail — no See-all on the detail page.
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                TvDetailSectionHeader(
                                    eyebrow = "Recommended",
                                    title = "More Like This",
                                    modifier = Modifier.padding(horizontal = Spacing.safeArea),
                                )
                                TvMediaRow(
                                    title = "More Like This",
                                    showHeader = false,
                                    items = state.moreLikeThis,
                                    onItemClick = onItemDetail,
                                    style = TvRowStyle.Poster,
                                    horizontalPadding = Spacing.safeArea,
                                    rowTopPadding = 0.dp,
                                    firstItemFocusRequester = firstSimilarFocus,
                                    onDirectionUp = if (!showsEpisodeRail && !showsCastSection) {
                                        returnToHero
                                    } else {
                                        null
                                    },
                                    // When Similar is the first body rail (movie with no
                                    // episode rail and no cast), Up returns to the hero
                                    // Play button instead of relying on geometry.
                                    upFocusRequester = if (!showsEpisodeRail && !showsCastSection) {
                                        playFocus
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (chaptersDialogOpen && audiobookChapters.isNotEmpty()) {
            TvOptionDialog(
                title = "Chapters",
                options = audiobookChapters.mapIndexed { index, chapter ->
                    TvDialogOption(
                        key = "chapter-$index-${chapter.fileId}",
                        title = chapter.title,
                        subtitle = listOf(
                            chapter.partTitle,
                            formatAudiobookTime(chapter.startSeconds),
                        )
                            .filter { it.isNotBlank() }
                            .joinToString("  "),
                        onClick = {
                            chaptersDialogOpen = false
                            onPlay(
                                detail.contentId,
                                chapter.fileId,
                                state.selectedAudioIndex,
                                state.selectedSubtitleIndex,
                                detail.type,
                                chapter.startSeconds,
                            )
                        },
                    )
                },
                onDismiss = { chaptersDialogOpen = false },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HeroActionRow(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    viewModel: TvItemDetailViewModel,
    playFocus: FocusRequester,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, subtitleTrackIndex: Int?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
) {
    var moreOpen by remember(detail.contentId) { mutableStateOf(false) }
    // Series / season detail target the *next-up episode* rather than the
    // container itself (mirrors silo-apple's TVSeriesDetailView /
    // TVSeasonDetailView). For those types the hero Play button, the resume
    // position, and the inline selector row all bind to the next-up episode's
    // own playback detail; movie / episode detail keep the container behavior.
    val isSeriesOrSeason = detail.type == "series" || detail.type == "season"
    val nextUp = state.nextUpEpisode.takeIf { isSeriesOrSeason }
    val nextUpDetail = state.nextUpPlaybackDetail

    // The contentId / type / versions / resume the Play action actually uses.
    val playContentId = nextUp?.contentId ?: detail.contentId
    val playType = if (nextUp != null) "episode" else detail.type
    // For series/season we must NOT fall back to playing the container — Play is
    // a no-op until the next-up episode resolves (episodes still loading, or an
    // empty/error season). Movie/episode are always ready.
    val playReady = !isSeriesOrSeason || nextUp != null
    val containerResume = remember(detail.userData) { detail.resumePositionSeconds() }
    val nextUpResume = remember(nextUp?.userData) { nextUp?.userData?.resumePositionSeconds() }
    val resumePosition = if (isSeriesOrSeason) nextUpResume else containerResume
    val hasResume = resumePosition != null

    // tvOS overflow: episode Go-to-Series / Go-to-Season navigation and season
    // Go-to-Series (mirrors `TVMovieDetailView.moreMenu` /
    // `TVSeasonDetailView.moreMenu`). Movies do not show an overflow button.
    val hasSeriesNavigation = detail.type in setOf("episode", "season") && detail.seriesId != null
    val hasOverflowNavigation = hasSeriesNavigation
    val hasOverflowMenu = hasOverflowNavigation

    // Version set + selection state driving the selector row / Play file id.
    // Series/season use the next-up episode's versions + the next-up selection;
    // everything else uses the container's.
    val selectorVersions = if (isSeriesOrSeason) (nextUpDetail?.versions ?: emptyList()) else detail.versions
    val selectorSelectedFileId = if (isSeriesOrSeason) state.selectedNextUpFileId else state.selectedFileId
    val selectorAudioIndex = if (isSeriesOrSeason) state.selectedNextUpAudioIndex else state.selectedAudioIndex
    val selectorSubtitleIndex =
        if (isSeriesOrSeason) state.selectedNextUpSubtitleIndex else state.selectedSubtitleIndex
    val selectedFileId = selectorSelectedFileId ?: selectorVersions.firstOrNull()?.fileId
    // The effective playable version drives the inline playback selector row.
    val selectedVersion = remember(selectorVersions, selectedFileId) {
        selectorVersions.firstOrNull { it.fileId == selectedFileId } ?: selectorVersions.firstOrNull()
    }
    val isAudiobook = isAudiobookItemType(detail.type)
    // Down from the action cluster lands on the selector row (when shown) rather
    // than skipping into the body. Mirrors Apple's full-width `.focusSection()`.
    val selectorFocus = remember { FocusRequester() }
    // While the next-up playback detail is still loading we hold a placeholder in
    // the selector slot (Apple's `TVVersionPillPlaceholder`); once resolved the
    // real selector binds to the next-up versions/tracks.
    val showsNextUpPlaceholder = isSeriesOrSeason && nextUp != null &&
        (state.isLoadingNextUpPlaybackDetail ||
            (!state.didLoadNextUpPlaybackDetail && nextUpDetail == null))
    val showsSelectorRow = !isAudiobook && !showsNextUpPlaceholder && selectedVersion != null

    // No separate next-up autofocus: the Play button is always rendered and
    // focusable, and the screen already focuses it on detail load, so re-focusing
    // when next-up resolves would only risk yanking focus back if the viewer had
    // already moved into the seasons/episodes rails.

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Action row — tvOS `HStack(spacing: 36)` mapped through the same
        // half-scale dp port as the button internals. One
        // focusGroup; Down from the far-right toggle is redirected onto the
        // selector row via focusProperties.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .then(
                    if (showsSelectorRow) {
                        Modifier.focusProperties { down = selectorFocus }
                    } else {
                        Modifier
                    },
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TvPrimaryPillButton(
                icon = Icons.Filled.PlayArrow,
                title = playButtonLabel(
                    isSeriesOrSeason = isSeriesOrSeason,
                    seriesContainer = detail.type == "series",
                    nextUp = nextUp,
                    hasResume = hasResume,
                    resumePosition = resumePosition,
                ),
                onClick = {
                    if (playReady) {
                        onPlay(
                            playContentId, selectedFileId,
                            selectorAudioIndex, selectorSubtitleIndex,
                            playType, resumePosition,
                        )
                    }
                },
                focusRequester = playFocus,
            )

            if (hasResume) {
                TvSecondaryPillButton(
                    icon = Icons.Filled.Replay,
                    title = "Start Over",
                    onClick = {
                        if (playReady) {
                            onPlay(
                                playContentId, selectedFileId,
                                selectorAudioIndex, selectorSubtitleIndex,
                                playType, 0.0,
                            )
                        }
                    },
                )
            }

            TvSquareToggleButton(
                icon = Icons.Outlined.FavoriteBorder,
                iconActive = Icons.Filled.Favorite,
                isActive = state.isFavorite,
                contentDescription = if (state.isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = viewModel::onToggleFavorite,
            )

            TvSquareToggleButton(
                icon = Icons.Outlined.BookmarkBorder,
                iconActive = Icons.Filled.BookmarkAdded,
                isActive = state.inWatchlist,
                contentDescription = if (state.inWatchlist) "Remove from watchlist" else "Add to watchlist",
                onClick = viewModel::onToggleWatchlist,
            )

            TvSquareToggleButton(
                icon = Icons.Outlined.CheckCircle,
                iconActive = Icons.Filled.CheckCircle,
                isActive = state.isWatched,
                contentDescription = if (state.isWatched) watchedUnmarkLabel(detail) else watchedMarkLabel(detail),
                onClick = viewModel::onToggleWatched,
            )

            if (hasOverflowMenu) {
                TvSquareToggleButton(
                    icon = Icons.Filled.MoreHoriz,
                    iconActive = Icons.Filled.MoreHoriz,
                    isActive = false,
                    contentDescription = "More options",
                    onClick = { moreOpen = true },
                )
            }
        }

        // Audiobooks have no meaningful video "version"/quality (the "720p" was
        // the cover-art mjpeg stream); hide the selector row for them. For
        // series/season detail the row binds to the *next-up* episode's
        // versions/tracks; while that detail loads we hold a placeholder pill
        // (Apple's `TVVersionPillPlaceholder`). Movie / episode detail bind to
        // the container's own versions.
        if (showsNextUpPlaceholder) {
            TvVersionPillPlaceholder()
        } else if (showsSelectorRow) {
            TvPlaybackSelectorRow(
                modifier = Modifier.focusRequester(selectorFocus),
                versions = selectorVersions,
                currentVersion = selectedVersion,
                selectedVersionFileId = selectorSelectedFileId,
                selectedAudioTrackIndex = selectorAudioIndex,
                selectedSubtitleTrackIndex = selectorSubtitleIndex,
                onSelectVersion = if (isSeriesOrSeason) {
                    viewModel::onNextUpVersionSelected
                } else {
                    viewModel::onVersionSelected
                },
                onSelectAudioTrack = if (isSeriesOrSeason) {
                    viewModel::onNextUpAudioTrackSelected
                } else {
                    viewModel::onAudioTrackSelected
                },
                onSelectSubtitleTrack = if (isSeriesOrSeason) {
                    viewModel::onNextUpSubtitleTrackSelected
                } else {
                    viewModel::onSubtitleTrackSelected
                },
            )
        }
    }

    if (moreOpen && hasOverflowMenu) {
        val options = buildList {
            if (hasOverflowNavigation) {
                detail.seriesId?.let { seriesId ->
                    // "Go to Season" is episode-only — a season page is already at
                    // the season level, so it offers just "Go to Series".
                    if (detail.type == "episode") {
                        detail.seasonNumber?.takeIf { it > 0 }?.let { season ->
                            add(
                                TvDialogOption(
                                    key = "season-$season",
                                    title = "Go to Season $season",
                                    subtitle = detail.seriesTitle,
                                    onClick = {
                                        moreOpen = false
                                        onSeasonClick(seriesId, season)
                                    },
                                ),
                            )
                        }
                    }
                    add(
                        TvDialogOption(
                            key = "series",
                            title = "Go to Series",
                            subtitle = detail.seriesTitle,
                            onClick = {
                                moreOpen = false
                                onSeriesClick(seriesId)
                            },
                        ),
                    )
                }
            }
        }
        TvOptionDialog(
            title = "More Actions",
            options = options,
            onDismiss = { moreOpen = false },
        )
    }
}

private fun watchedMarkLabel(detail: ItemDetail): String =
    if (detail.type == "episode") "Mark Episode Watched" else "Mark as Watched"

private fun watchedUnmarkLabel(detail: ItemDetail): String =
    if (detail.type == "episode") "Mark Episode Unwatched" else "Mark as Unwatched"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CircleAction(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    isActive: Boolean,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = CircleShape

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isActive) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.34f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.32f)),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.0.dp, Color.Black.copy(alpha = 0.82f)),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.16f),
                elevation = 14.dp,
            ),
        ),
        modifier = Modifier
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.98f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .size(38.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EpisodesSection(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    showsSeasonChips: Boolean,
    onReturnToHero: () -> Boolean,
    onSeasonSelected: (com.continuum.app.model.catalog.Season) -> Unit,
    onEpisodeSelected: (EpisodeListItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.safeArea),
            verticalAlignment = Alignment.Bottom,
        ) {
            TvDetailSectionHeader(
                eyebrow = episodeEyebrowLabel(detail, state),
                title = "Episodes",
            )
            Spacer(modifier = Modifier.weight(1f))
            val count = state.episodes.size
            if (count > 0) {
                Text(
                    text = "$count episode${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    ),
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }

        if (showsSeasonChips) {
            TvSeasonPicker(
                seasons = state.seasons,
                selectedSeason = state.selectedSeason,
                onSeasonSelected = onSeasonSelected,
                onDirectionUp = onReturnToHero,
                modifier = Modifier.padding(horizontal = Spacing.safeArea),
            )
        }

        TvDetailEpisodeRail(
            episodes = state.episodes,
            currentContentId = currentEpisodeRailContentId(detail, state),
            onEpisodeSelected = onEpisodeSelected,
            // Up returns to the hero only when the season chips aren't above the
            // rail (the chips own the Up traversal when present).
            onDirectionUp = if (showsSeasonChips) null else onReturnToHero,
        )
    }
}

private fun currentEpisodeRailContentId(detail: ItemDetail, state: TvItemDetailUiState): String? =
    when (detail.type) {
        "episode" -> detail.contentId
        "series",
        "season",
        -> state.nextUpEpisode
            ?.takeIf { it.userData?.isInProgress == true }
            ?.contentId
        else -> null
    }

private fun episodeEyebrowLabel(detail: ItemDetail, state: TvItemDetailUiState): String {
    state.selectedSeason?.takeIf { it > 0 }?.let { return "Season $it" }
    if (detail.type == "episode") {
        detail.seasonNumber?.takeIf { it > 0 }?.let { return "Season $it" }
    }
    return "This Season"
}

/**
 * Static, non-focusable placeholder shown in the selector slot while the
 * next-up episode's playback detail loads. Compose-for-TV analogue of
 * silo-apple's `TVVersionPillPlaceholder` — a dimmed "Version" pill.
 */
@Composable
private fun TvVersionPillPlaceholder(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.42f), shape)
            .border(0.6.dp, Color.White.copy(alpha = 0.16f), shape)
            .widthIn(min = 150.dp)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.HighQuality,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.58f),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Version",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.58f),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DetailsSection(
    detail: ItemDetail,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Info", title = "Details")
        TvDetailFactsTable(detail = detail)
    }
}

@Composable
private fun TvAudiobookPartsSection(
    parts: List<FileVersion>,
    onPartSelected: (FileVersion) -> Unit,
    firstRowUpFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Audiobook", title = "Parts")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEachIndexed { index, part ->
                TvAudiobookDetailActionRow(
                    title = "Part ${index + 1}",
                    subtitle = audiobookPartSubtitle(part),
                    trailing = audiobookDurationLabel(part.duration),
                    onClick = { onPartSelected(part) },
                    upFocusRequester = if (index == 0) firstRowUpFocusRequester else null,
                )
            }
        }
    }
}

@Composable
private fun TvAudiobookChaptersSection(
    chapters: List<TvAudiobookDisplayChapter>,
    onOpenChapters: () -> Unit,
    upFocusRequester: FocusRequester?,
    onDirectionUp: (() -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Audiobook", title = "Chapters")
        TvAudiobookDetailActionRow(
            title = "Chapter list",
            subtitle = "${chapters.size} chapters",
            trailing = "Open",
            onClick = onOpenChapters,
            upFocusRequester = upFocusRequester,
            onDirectionUp = onDirectionUp,
        )
    }
}

@Composable
private fun TvAudiobookNarrationsSection(
    narrations: List<AudiobookNarration>,
    onNarrationSelected: (AudiobookNarration) -> Unit,
    firstRowUpFocusRequester: FocusRequester?,
    onDirectionUp: (() -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Audiobook", title = "Alternate Narrations")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            narrations.forEachIndexed { index, narration ->
                TvAudiobookDetailActionRow(
                    title = narration.title,
                    subtitle = narration.narrators.joinToString(", ").takeIf { it.isNotBlank() },
                    trailing = narration.year?.takeIf { it > 0 }?.toString(),
                    onClick = { onNarrationSelected(narration) },
                    upFocusRequester = if (index == 0) firstRowUpFocusRequester else null,
                    onDirectionUp = if (index == 0) onDirectionUp else null,
                )
            }
        }
    }
}

@Composable
private fun TvAudiobookRelatedRailSection(
    title: String,
    items: List<MediaRelatedItem>,
    onItemDetail: (String) -> Unit,
    upFocusRequester: FocusRequester,
    onDirectionUp: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val rowItems = remember(items) { items.mapNotNull(::audiobookRelatedItemToSectionItem) }
    if (rowItems.isEmpty()) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TvDetailSectionHeader(eyebrow = "Audiobook", title = title)
        TvMediaRow(
            title = title,
            showHeader = false,
            items = rowItems,
            onItemClick = onItemDetail,
            style = TvRowStyle.Poster,
            horizontalPadding = 0.dp,
            rowTopPadding = 0.dp,
            rowBottomPadding = 0.dp,
            upFocusRequester = upFocusRequester,
            onDirectionUp = onDirectionUp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAudiobookDetailActionRow(
    title: String,
    subtitle: String?,
    trailing: String?,
    onClick: () -> Unit,
    upFocusRequester: FocusRequester? = null,
    onDirectionUp: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val secondaryColor = if (isFocused) Color.Black.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.58f)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(8.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.Black.copy(alpha = 0.18f)),
                shape = RoundedCornerShape(8.dp),
            ),
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onDirectionUp != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onDirectionUp.invoke()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Helpers

private data class TvAudiobookDisplayChapter(
    val fileId: Int,
    val title: String,
    val partTitle: String,
    val startSeconds: Double,
)

private fun audiobookDisplayChapters(parts: List<FileVersion>): List<TvAudiobookDisplayChapter> =
    parts.flatMapIndexed { partIndex, part ->
        val partTitle = "Part ${partIndex + 1}"
        part.chapters.orEmpty().mapIndexed { chapterIndex, chapter ->
            TvAudiobookDisplayChapter(
                fileId = part.fileId,
                title = audiobookChapterTitle(chapterIndex, chapter),
                partTitle = partTitle,
                startSeconds = chapter.startSeconds,
            )
        }
    }

private fun audiobookChapterTitle(
    fallbackIndex: Int,
    chapter: VersionChapter,
): String = chapter.title.trim().takeIf { it.isNotBlank() } ?: "Chapter ${fallbackIndex + 1}"

private fun audiobookPartSubtitle(part: FileVersion): String? =
    listOfNotNull(
        part.codecAudio?.takeIf { it.isNotBlank() }?.uppercase(),
        part.container?.takeIf { it.isNotBlank() }?.uppercase(),
        part.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
    )
        .joinToString("  ")
        .takeIf { it.isNotBlank() }

private fun audiobookDurationLabel(seconds: Double): String? =
    seconds.takeIf { it.isFinite() && it > 0.0 }?.let(::formatAudiobookTime)

private fun audiobookRelatedItemToSectionItem(item: MediaRelatedItem): SectionItem? {
    val contentId = item.contentId.takeIf { it.isNotBlank() } ?: return null
    val title = item.title.takeIf { it.isNotBlank() } ?: return null
    return SectionItem(
        contentId = contentId,
        type = "audiobook",
        title = title,
        year = item.year ?: 0,
        posterUrl = item.posterUrl,
    )
}

private fun sectionItemToAudiobookRelatedItem(item: SectionItem): MediaRelatedItem =
    MediaRelatedItem(
        contentId = item.contentId,
        title = item.title,
        year = item.year.takeIf { it > 0 },
        posterUrl = item.posterUrl,
    )

private fun ItemDetail.resumePositionSeconds(): Double? = userData?.resumePositionSeconds()

private fun com.continuum.app.model.catalog.LeafItemUserData.resumePositionSeconds(): Double? {
    val pos = positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    if (pos <= 30 || dur <= 0 || pos >= dur - 5) return null
    return pos
}

/**
 * Hero Play button label. Movie / episode detail keep the plain Play /
 * Resume<hms> form; series / season detail target the next-up episode and read
 * "Play S2 · E3" / "Resume S2 · E3" (series) or "Play E4" / "Resume E4"
 * (season), mirroring silo-apple's `playButtonLabel(for:)`.
 */
private fun playButtonLabel(
    isSeriesOrSeason: Boolean,
    seriesContainer: Boolean,
    nextUp: EpisodeListItem?,
    hasResume: Boolean,
    resumePosition: Double?,
): String {
    if (isSeriesOrSeason && nextUp != null) {
        val verb = if (hasResume) "Resume" else "Play"
        return if (seriesContainer) {
            "$verb S${nextUp.seasonNumber} · E${nextUp.episodeNumber}"
        } else {
            "$verb E${nextUp.episodeNumber}"
        }
    }
    return if (hasResume && resumePosition != null) "Resume ${resumePosition.formatHms()}" else "Play"
}

private fun Double.formatHms(): String {
    val totalSeconds = roundToInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
