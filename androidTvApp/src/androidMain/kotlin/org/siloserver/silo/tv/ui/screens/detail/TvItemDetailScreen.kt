package org.siloserver.silo.tv.ui.screens.detail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.audiobook.AudioPlaybackTrack
import org.siloserver.silo.audiobook.AudiobookTimeline
import org.siloserver.silo.audiobook.buildAudiobookTimeline
import org.siloserver.silo.common.ui.movieDirectorCredit
import org.siloserver.silo.metadata.DescriptionTranslationPhase
import org.siloserver.silo.model.audiobook.AudiobookNarration
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.ItemVideo
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.isSpecialsForDisplay
import org.siloserver.silo.model.ebook.MediaRelatedItem
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED
import org.siloserver.silo.model.feature.MetadataAiFeatureStore
import org.siloserver.silo.model.metadata.MetadataAiOnView
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.navigation.explicitTvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.components.TvPrimaryPillButton
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvSquareToggleButton
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.screens.audiobook.formatAudiobookTime
import org.siloserver.silo.tv.ui.screens.watchtogether.TvJoinCodeDialog
import org.siloserver.silo.tv.ui.screens.watchtogether.TvSuggestToRoomViewModel
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherEntryDialog
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherViewModel
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec

internal data class TvSeriesDetailRedirect(
    val seriesContentId: String,
    val seasonNumber: Int,
    val episodeContentId: String?,
)

/**
 * Maps a standalone season or episode detail onto the combined Series page.
 * Incomplete hierarchy data deliberately returns null so the existing detail
 * remains available as a resilient fallback.
 */
internal fun tvSeriesDetailRedirect(detail: ItemDetail): TvSeriesDetailRedirect? {
    val type = detail.type.trim().lowercase()
    if (type != "season" && type != "episode") return null

    val seriesContentId = detail.seriesId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != detail.contentId }
        ?: return null
    val seasonNumber = detail.seasonNumber ?: return null

    return TvSeriesDetailRedirect(
        seriesContentId = seriesContentId,
        seasonNumber = seasonNumber,
        episodeContentId = detail.contentId.takeIf { type == "episode" },
    )
}

internal fun ItemDetail?.isMatchingSeriesDetail(expectedContentId: String): Boolean =
    this != null &&
        type.equals("series", ignoreCase = true) &&
        contentId == expectedContentId

@Composable
fun TvItemDetailScreen(
    contentId: String,
    seasonNumber: Int? = null,
    initialEpisodeContentId: String? = null,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, audioPickedThisSession: Boolean, subtitleSelection: TvSubtitleLaunchSelection?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onItemDetail: (contentId: String) -> Unit,
    onItemDetailReplace: (contentId: String) -> Unit = onItemDetail,
    onSeriesDetailReplace: (
        seriesContentId: String,
        seasonNumber: Int,
        episodeContentId: String?,
    ) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
    onWatchTogether: (RoomSnapshot) -> Unit,
    onOpenPerson: (personId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: TvItemDetailViewModel = koinViewModel(
        key = "item-detail-$contentId-${seasonNumber ?: "default"}-${initialEpisodeContentId ?: "default"}",
        parameters = { parametersOf(contentId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val seriesRedirect = remember(state.detail) {
        state.detail?.let(::tvSeriesDetailRedirect)
    }
    var seriesRedirectFailed by rememberSaveable(
        contentId,
        seriesRedirect?.seriesContentId,
        seriesRedirect?.seasonNumber,
        seriesRedirect?.episodeContentId,
    ) {
        mutableStateOf(false)
    }
    var pendingEntrySeasonNumber by rememberSaveable(contentId, seasonNumber) {
        mutableStateOf(seasonNumber)
    }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(seriesRedirect, seriesRedirectFailed) {
        val redirect = seriesRedirect ?: return@LaunchedEffect
        if (seriesRedirectFailed) return@LaunchedEffect

        if (viewModel.hasSeriesDetailForRedirect(redirect.seriesContentId)) {
            onSeriesDetailReplace(
                redirect.seriesContentId,
                redirect.seasonNumber,
                redirect.episodeContentId,
            )
        } else {
            // Match tvOS's defensive behavior: if the parent is unavailable or
            // does not resolve as a Series, keep the standalone page usable.
            seriesRedirectFailed = true
        }
    }

    // Refresh on return (e.g. backing out of the player): the ViewModel loads
    // once in init, so without this the Play button keeps the resume label
    // computed before playback. Fires on every ON_RESUME (like TvHomeScreen);
    // no first-entry guard — the composable is recreated on back-stack pop, so
    // any effect-local "skip the first" flag would reset and swallow exactly
    // the resume we care about. refreshOnReturn() no-ops while detail is still
    // null, which covers the initial load.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOnReturn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.detail?.contentId, pendingEntrySeasonNumber, state.seasons) {
        val detail = state.detail ?: return@LaunchedEffect
        val entrySeason = pendingEntrySeasonNumber ?: return@LaunchedEffect
        if (detail.type != "series") {
            pendingEntrySeasonNumber = null
            return@LaunchedEffect
        }
        if (state.selectedSeason == entrySeason) {
            pendingEntrySeasonNumber = null
            return@LaunchedEffect
        }
        // Wait only until the season list resolves, then consume the route hint
        // exactly once. Later focus-driven season changes must never bounce back
        // to the season that originally opened this page.
        if (state.seasons.isEmpty()) return@LaunchedEffect
        pendingEntrySeasonNumber = null
        if (state.seasons.any { it.seasonNumber == entrySeason }) {
            viewModel.onSeasonSelected(entrySeason)
        }
    }

    LaunchedEffect(
        state.detail?.contentId,
        seasonNumber,
        initialEpisodeContentId,
        state.selectedSeason,
        state.episodes,
        state.episodesLoading,
        state.entryEpisodeSelectionApplied,
    ) {
        val detail = state.detail ?: return@LaunchedEffect
        val episodeContentId = initialEpisodeContentId ?: return@LaunchedEffect
        if (detail.type != "series" || state.entryEpisodeSelectionApplied) return@LaunchedEffect
        if (seasonNumber != null && state.selectedSeason != seasonNumber) return@LaunchedEffect
        if (state.episodesLoading || state.episodes.isEmpty()) return@LaunchedEffect
        viewModel.onEntrySeriesEpisodeRequested(episodeContentId)
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
        seriesRedirect != null && !seriesRedirectFailed -> TvLoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.detail != null -> TvDetailContent(
            detail = state.detail!!,
            state = state,
            initialSeasonNumber = seasonNumber,
            initialEpisodeContentId = initialEpisodeContentId,
            viewModel = viewModel,
            onPlay = onPlay,
            onItemDetail = onItemDetail,
            onItemDetailReplace = onItemDetailReplace,
            onSeriesClick = onSeriesClick,
            onSeasonClick = onSeasonClick,
            onWatchTogether = onWatchTogether,
            onOpenPerson = onOpenPerson,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvDetailContent(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    initialSeasonNumber: Int?,
    initialEpisodeContentId: String?,
    viewModel: TvItemDetailViewModel,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, audioPickedThisSession: Boolean, subtitleSelection: TvSubtitleLaunchSelection?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onItemDetail: (contentId: String) -> Unit,
    onItemDetailReplace: (contentId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
    onWatchTogether: (RoomSnapshot) -> Unit,
    onOpenPerson: (personId: Long) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    // The circular Version control in the hero action cluster. Hoisted here so
    // an Up press from the body can return to the bottom-most playback control
    // without skipping straight to Play.
    val selectorFocus = remember { FocusRequester() }
    val firstCastFocus = remember { FocusRequester() }
    // Focus restore for the cast rail: remembers which cast card pushed the
    // person page so the return trip lands back on it instead of Play.
    // Saveable because the composable is recreated on back-stack pop.
    val castReturnFocus = remember { FocusRequester() }
    var pendingCastFocusIndex by rememberSaveable(detail.contentId) { mutableStateOf(-1) }
    // Flipped by the rail when the launch card genuinely gains focus — the
    // restore loop exits on it instead of re-requesting for a fixed window,
    // which held focus hostage on that card for ~a second after returning.
    val castRestoreFocused = remember { mutableStateOf(false) }
    // Same treatment for More Like This. Returning from a related item only
    // became reachable once item-detail navigation stopped reusing this entry;
    // before that you never came back to this page, so the generic
    // snap-to-hero below was the only outcome that existed.
    //
    // Stores the CONTENT ID, not the list index. After process death the saved
    // index could outlive the list it indexed: the rail reloads over the
    // network, so the index can arrive before the list does, and the reloaded
    // list can come back in a different order — restoring focus to whichever
    // title now happens to sit at that position.
    var pendingSimilarContentId by rememberSaveable(detail.contentId) {
        mutableStateOf<String?>(null)
    }
    // Paired with the id because the id alone is an ABA token: a newer request
    // for the SAME content passes every ownership check the old coroutine
    // makes, letting it clear or override the new one.
    var pendingSimilarGeneration by rememberSaveable(detail.contentId) { mutableStateOf(0) }
    val similarReturnFocus = remember { FocusRequester() }
    val similarRestoreFocused = remember { mutableStateOf(false) }
    // Bumped once the target resolves, so the row scrolls its own LazyRow to
    // that card: a card outside the composed window leaves the requester
    // unattached and every retry doomed.
    var similarRestoreRequest by remember { mutableStateOf(0) }
    // Resolved against the CURRENT list, so it simply stays -1 until the rail
    // has loaded and becomes correct if the order changed.
    val pendingSimilarIndex = pendingSimilarContentId?.let { pendingId ->
        state.moreLikeThis.indexOfFirst { it.contentId == pendingId }
    } ?: -1
    val pendingSimilarIndexNow = rememberUpdatedState(pendingSimilarIndex)
    val firstSimilarFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isAudiobook = isAudiobookItemType(detail.type)
    val isSeriesDetail = detail.type == "series"
    var isShowingSeriesOverview by rememberSaveable(
        detail.contentId,
        initialSeasonNumber,
        initialEpisodeContentId,
    ) {
        mutableStateOf(true)
    }
    LaunchedEffect(
        initialSeasonNumber,
        initialEpisodeContentId,
        state.selectedSeason,
        state.episodesLoading,
        state.nextUpEpisode?.seasonNumber,
    ) {
        if (
            initialSeasonNumber != null &&
            initialEpisodeContentId == null &&
            state.selectedSeason == initialSeasonNumber &&
            !state.episodesLoading &&
            (state.nextUpEpisode == null || state.nextUpEpisode.seasonNumber == initialSeasonNumber)
        ) {
            isShowingSeriesOverview = false
        }
    }
    LaunchedEffect(
        initialEpisodeContentId,
        state.entryEpisodeSelectionApplied,
        state.nextUpEpisode?.contentId,
    ) {
        if (
            initialEpisodeContentId != null &&
            state.entryEpisodeSelectionApplied &&
            state.nextUpEpisode?.contentId == initialEpisodeContentId
        ) {
            isShowingSeriesOverview = false
        }
    }
    // tvOS treats Show / Season tabs, Episodes and the playback selector as a
    // single fixed first viewport. Supporting rails only begin vertical page
    // movement after focus leaves that complete primary region.
    val seriesPrimaryViewportActive = remember(detail.contentId) { mutableStateOf(false) }
    var seriesPrimaryFocusGeneration by remember(detail.contentId) { mutableStateOf(0) }
    var seriesHeroHeightPx by remember(detail.contentId) { mutableStateOf(0) }
    val trailerEntries = remember(detail.videos, detail.extras) {
        tvDetailTrailerEntries(detail)
    }
    val detailContext = LocalContext.current

    // Default focus → Play (user-initiated), mirroring Apple's
    // `defaultFocus($playFocused, true, priority: .userInitiated)`. This
    // re-runs whenever the page re-enters composition (fresh load, or
    // returning from the player / a pushed detail): the saved LazyListState
    // may restore the window at the episodes section while focus goes to
    // Play, so snap the window back to the hero first to keep them in sync.
    LaunchedEffect(detail.contentId) {
        // Returning from a person page opened via the cast rail: the saved
        // LazyListState already restores the window at the cast section, so
        // land focus back on the card that opened it instead of snapping to
        // Play. The requester only attaches once the restored rail
        // recomposes — retry across a few frames, then fall back to Play.
        if (pendingCastFocusIndex >= 0) {
            // requestFocus() can report success yet silently roll back when
            // it crosses the rail's enter redirect, so don't trust the return
            // value alone: keep the pending window open (the rail's enter
            // targets the launch card while it is) across the pop transition,
            // re-requesting every couple of frames.
            // Success comes ONLY from the rail's focus callback. Accumulating
            // requestFocus()'s return value defeated the very rollback this
            // loop exists to survive: one transient true skipped the Play
            // fallback even though focus had bounced back off the redirect.
            // This loop was already judging on observed focus, which is why it
            // worked; it just open-coded the pacing. The policy does the same
            // thing, and the two-frame cadence is preserved.
            var restored = requestFocusUntilObserved(
                maxAttempts = CAST_RESTORE_MAX_ATTEMPTS,
                awaitAttempt = {
                    withFrameNanos { }
                    withFrameNanos { }
                },
                requestFocus = castReturnFocus::requestFocus,
                isFocused = { castRestoreFocused.value },
            ) == TvObservedFocusResult.Focused
            // The last attempt's request can land after the loop's final check,
            // so re-read before giving up — otherwise Play immediately steals
            // focus from a restore that actually succeeded.
            if (!restored) restored = castRestoreFocused.value
            pendingCastFocusIndex = -1
            if (restored) {
                // Don't leave the other rail's requester armed.
                pendingSimilarContentId = null
                return@LaunchedEffect
            }
        }
        // A pending More Like This restore is owned by the effect below, which
        // can outlive this one while it waits for the rail to load. It performs
        // the hero fallback itself if the target never turns up.
        if (pendingSimilarContentId != null) return@LaunchedEffect
        listState.scrollToItem(0)
        playFocus.claimFocusOrReport(target = "detail_play", action = "entry_fallback")
        // Play's initial focus claim can enqueue its own bring-into-view before
        // heroHasFocus observes the node. Let that request settle, then restore
        // the approved top framing (most visible on the compact Series hero).
        withFrameNanos { }
        withFrameNanos { }
        listState.scrollToItem(0)
    }

    // Returning from a related item: land back on the card that opened it
    // rather than snapping to the hero.
    //
    // Keyed on the CONTENT ID alone, deliberately. Keying it on the pending id
    // as well fired this on the way OUT — the moment the click recorded it —
    // so it spun its whole window against a page being navigated away from,
    // cleared the pending id, and left nothing to restore on the way back.
    // Content id only means it runs once per entry to this page, which is
    // exactly when a restore is due.
    LaunchedEffect(detail.contentId) {
        // The exact request this coroutine owns. Every step below re-checks it,
        // because clearing or replacing the pending id does NOT cancel this
        // coroutine — without the token it could keep requesting focus for the
        // rest of its window on behalf of a return nobody is waiting for.
        val ownedContentId = pendingSimilarContentId ?: return@LaunchedEffect
        val ownedGeneration = pendingSimilarGeneration
        fun stillOwned() =
            pendingSimilarContentId == ownedContentId &&
                pendingSimilarGeneration == ownedGeneration

        // Two different waits, on two different clocks.
        //
        // First the DATA. After process death the rail reloads over the network
        // — debounced, then several requests — so the target may not exist yet.
        // Counting frames for that was measuring the wrong thing entirely: a
        // ~120-frame budget is one or two seconds depending on refresh rate,
        // and a load finishing just past it silently became a hero fallback.
        val result = restoreMoreLikeThisFocus(
            awaitTarget = {
                snapshotFlow { pendingSimilarIndexNow.value }.first { it >= 0 }
            },
            stillOwned = ::stillOwned,
            // Once the target exists, ask the row to scroll it into its composed
            // window before focus requests begin.
            onTargetResolved = { similarRestoreRequest += 1 },
            isTargetFocused = { similarRestoreFocused.value },
            // The return value is not evidence: the row's enter redirect can
            // roll an accepted request back. Only its focus callback counts.
            requestTargetFocus = {
                similarReturnFocus.claimFocusOrReport(
                    target = "detail_similar_card",
                    action = "return_restore",
                )
            },
            awaitFocusAttempt = {
                withFrameNanos { }
                withFrameNanos { }
            },
            // Never turned up, or focus kept rolling back — leave the viewer
            // somewhere usable. The policy holds ownership across this
            // suspension and re-checks it before requesting Play focus.
            scrollToFallback = { listState.scrollToItem(0) },
            requestFallbackFocus = {
                playFocus.claimFocusOrReport(
                    target = "detail_play",
                    action = "similar_restore_fallback",
                )
            },
            dataTimeoutMillis = RESTORE_DATA_TIMEOUT_MS,
            attachmentTimeoutMillis = RESTORE_ATTACH_TIMEOUT_MS,
        )
        if (result != TvSimilarFocusRestoreResult.Revoked && stillOwned()) {
            pendingSimilarContentId = null
        }
    }

    val isEpisodicType = detail.type in setOf("series", "season", "episode")
    val showsEpisodeRail = isEpisodicType && state.episodes.isNotEmpty()
    val showsSeasonChips = isEpisodicType && if (isSeriesDetail) {
        state.seasons.isNotEmpty()
    } else {
        state.seasons.size > 1
    }
    // Keep the whole Episodes section — and, crucially, the season chips —
    // mounted whenever the series has seasons, so selecting an empty or failed
    // season can't unmount the chips and strand the user (T15). The rail itself
    // still renders only when there are episodes; otherwise the section shows a
    // loading spinner or a "No episodes available" empty state.
    val showsEpisodesSection = isEpisodicType && (state.seasons.isNotEmpty() || state.episodes.isNotEmpty())
    // Whether a focusable episode-navigation element (the rail or the season
    // chips) sits above the cast / similar rails — drives their
    // Up-return-to-hero fallback.
    val hasEpisodeNavAbove = showsEpisodeRail || showsSeasonChips
    val showsCastSection = !isAudiobook && detail.cast.isNotEmpty()
    val showsTrailersSection = !isAudiobook && trailerEntries.isNotEmpty()
    val showsSimilarRail = !isAudiobook && detail.type != "episode" && state.moreLikeThis.isNotEmpty()
    val showsDetailsSection = !isAudiobook && remember(detail) { detail.hasTvDetailFacts() }
    // Whole-book timeline stitched from the item's audiobook-part files — the
    // same math the player VM uses. Drives the Parts section (one row per track)
    // and the stitched, globally-offset chapter list. Null when there are no
    // audio parts; single-part books yield a single track (no Parts section).
    val audiobookTimeline = remember(detail.versions, detail.audiobook?.totalDurationSeconds) {
        buildAudiobookTimeline(
            versions = detail.versions,
            serverTotalSeconds = detail.audiobook?.totalDurationSeconds?.toDouble(),
        )
    }
    val audiobookParts = audiobookTimeline?.tracks.orEmpty()
    val audiobookChapters = remember(audiobookTimeline) {
        audiobookDisplayChapters(audiobookTimeline, detail.versions)
    }
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
    val heroSelectedFileId = if (detail.type == "series" || detail.type == "season") {
        state.selectedNextUpFileId
    } else {
        state.selectedFileId
    }
    val activeSeriesEpisode = state.nextUpEpisode.takeIf {
        isSeriesDetail && !isShowingSeriesOverview
    }
    val activeSeriesPlaybackDetail = state.nextUpPlaybackDetail.takeIf { playbackDetail ->
        activeSeriesEpisode?.contentId == playbackDetail?.contentId
    }
    val heroTitle = activeSeriesEpisode?.let { episode ->
        activeSeriesPlaybackDetail?.title
            ?: episode.title
            ?: "Episode ${episode.episodeNumber}"
    } ?: detail.title
    val heroOverview = activeSeriesEpisode?.let { episode ->
        activeSeriesPlaybackDetail?.overview ?: episode.overview
    } ?: detail.overview
    // Always derive the Series credit from the Show itself. Episode focus can
    // replace the synopsis and playback target, but must never make Starring or
    // the controls underneath it jump to a different position.
    val heroCreditText = if (isSeriesDetail) {
        seriesStarringCredit(detail)
    } else {
        movieDirectorCredit(detail).takeIf { activeSeriesEpisode == null }
    }
    val heroSourceTokens = activeSeriesEpisode?.let(TvDetailMetadata::seriesEpisodeSourceTokens)
        ?: TvDetailMetadata.sourceTokens(detail)
    val heroFactsLine = activeSeriesEpisode?.let { episode ->
        TvDetailMetadata.seriesEpisodeFactsLine(episode)
    } ?: TvDetailMetadata.factsLine(
        detail = detail,
        preferredQuality = state.preferredQuality,
        selectedFileId = heroSelectedFileId,
        includePlaybackFormats = false,
    )
    val heroArtwork = resolveTvDetailHeroArtwork(detail, state.nextUpEpisode)
    val detailPageTint = rememberAmbientBackdropTintState()
    LaunchedEffect(heroArtwork.url) {
        detailPageTint.set(item = null, url = heroArtwork.url)
    }
    val pageSurfaceColor = tvDetailPageSurfaceColor(detailPageTint.accent)
    var chaptersDialogOpen by remember(detail.contentId) { mutableStateOf(false) }

    // The first focusable body rail (episode rail, else cast). An Up press from
    // it scrolls back to the hero and returns focus into the action cluster —
    // the Compose analogue of Apple's `focusScope` Up traversal into the hero
    // actions section.
    val returnToHero: () -> Boolean = {
        coroutineScope.launch {
            // Land on the selector row (the bottom-most primary control) when
            // it is composed; otherwise fall back to Play
            // (audiobooks, or the next-up placeholder pill). Focus moves
            // BEFORE the scroll so the highlight travels with the window
            // instead of appearing only after it settles; when the hero has
            // been disposed off-screen the requests fail and we re-focus
            // after the scroll composes it again.
            // runCatching{}.isSuccess was true whenever the call did not THROW,
            // so a requestFocus that returned false still counted as focused —
            // the scroll then ran as though the highlight had already moved.
            // These report the request's own answer.
            val focusedImmediately =
                selectorFocus.claimFocusOrReport(
                    target = "detail_selector",
                    action = "return_to_top",
                ) || playFocus.claimFocusOrReport(
                    target = "detail_play",
                    action = "return_to_top",
                )
            if (focusedImmediately) {
                // Let the focus system enqueue its automatic bring-into-view
                // first, then cancel/replace that scroll with the paced
                // return-to-top anchor.
                withFrameNanos { }
            }
            listState.animateScrollToItemPaced(0)
            if (!focusedImmediately) {
                if (!selectorFocus.claimFocusOrReport(
                        target = "detail_selector",
                        action = "return_to_top_retry",
                    )
                ) {
                    playFocus.claimFocusOrReport(
                        target = "detail_play",
                        action = "return_to_top_retry",
                    )
                }
            }
        }
        true
    }

    val metadataAiStore: MetadataAiFeatureStore = koinInject()
    val metadataAiStatus by metadataAiStore.status.collectAsState()
    val translationPhase by viewModel.translationPhase.collectAsState()
    // `auto` on-view mode fires once per (content, language); the
    // controller latches so recompositions can't re-queue jobs.
    LaunchedEffect(detail.contentId, detail.pendingTranslationLanguage, metadataAiStatus.onView) {
        if (metadataAiStatus.onView == MetadataAiOnView.Auto &&
            detail.pendingTranslationLanguage != null
        ) {
            viewModel.translateDescription(auto = true)
        }
    }
    val translationSlot: (@Composable () -> Unit)? =
        if (detail.pendingTranslationLanguage != null &&
            (metadataAiStatus.onView == MetadataAiOnView.Button ||
                translationPhase != DescriptionTranslationPhase.Idle)
        ) {
            {
                TvDescriptionTranslationSection(
        phase = translationPhase,
        onTranslate = { viewModel.translateDescription() },
                )
            }
        } else {
            null
        }

    // While focus is anywhere inside the hero (actions, selectors, synopsis)
    // the window must stay put — every hero↔body transition already runs an
    // explicit paced anchor scroll, so the only thing the automatic
    // bring-into-view would add is a small gutter-correction jiggle when
    // hopping Play ↔ selector row. Suppress it for hero-origin requests and
    // keep the smooth spec for body rails.
    val heroHasFocus = remember { mutableStateOf(false) }
    val detailBringIntoViewSpec = remember(heroHasFocus, seriesPrimaryViewportActive, isSeriesDetail) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = DetailAnchorScrollSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = if (
                heroHasFocus.value ||
                (isSeriesDetail && seriesPrimaryViewportActive.value)
            ) {
                0f
            } else {
                TvSmoothBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // A pending restore is a convenience, and the moment the viewer
            // steers for themselves it stops being one. This is the only signal
            // that a move was genuinely user-initiated — a focus-gain callback
            // is not, because the rail's own enter redirect produces one.
            // Returns false throughout: this observes, it never consumes.
            .onPreviewKeyEvent { event ->
                if (
                    pendingSimilarContentId != null &&
                    event.type == KeyEventType.KeyDown &&
                    event.key in tvDirectionalKeys
                ) {
                    pendingSimilarContentId = null
                }
                false
            }
            .background(pageSurfaceColor),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides detailBringIntoViewSpec) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = if (isAudiobook) 160.dp else TvDetailPageBottomPadding),
            ) {
                item(key = "hero", contentType = "detail-hero") {
                    Box(
                        modifier = Modifier
                            .onSizeChanged { seriesHeroHeightPx = it.height }
                            .onFocusChanged { focusState ->
                                heroHasFocus.value = focusState.hasFocus
                            },
                    ) {
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
                            title = heroTitle,
                            seriesTitle = when {
                                activeSeriesEpisode != null -> detail.title
                                detail.type == "episode" -> detail.seriesTitle
                                else -> null
                            },
                            logoUrl = detail.logoUrl,
                            backdropUrl = heroArtwork.url,
                            backdropThumbhash = heroArtwork.thumbhash,
                            sourceTokens = heroSourceTokens,
                            ratingChip = TvDetailMetadata.ratingChip(detail),
                            overview = heroOverview,
                            tagline = detail.tagline.takeIf { activeSeriesEpisode == null },
                            factsLine = heroFactsLine,
                            directorText = heroCreditText,
                            translation = translationSlot.takeIf { activeSeriesEpisode == null },
                            compactSeries = isSeriesDetail,
                            playbackSummary = {
                                TvDetailPlaybackSelectionSummary(
                                    detail = detail,
                                    state = state,
                                )
                            },
                            actions = {
                                // Series keeps the full action row in both Show
                                // and Season browsing modes. Because episode
                                // focus updates state.nextUpEpisode, Play/Resume
                                // always follows the focused episode while the
                                // other Show-level actions stay available.
                                HeroActionRow(
                                    detail = detail,
                                    state = state,
                                    viewModel = viewModel,
                                    playFocus = playFocus,
                                    selectorFocus = selectorFocus,
                                    onPlay = onPlay,
                                    onSeriesClick = onSeriesClick,
                                    onSeasonClick = onSeasonClick,
                                    onWatchTogether = onWatchTogether,
                                )
                            },
                        )
                    }
                    }
                }

                // Approved hero→body rhythm: the artwork ends with the hero;
                // the first section begins one standard body gap below it.
                item(key = "body", contentType = "detail-body") {
                    Column(
                        // tvOS starts the first body section immediately after
                        // the hero; the 64pt token is only BETWEEN sections.
                        modifier = Modifier,
                        verticalArrangement = Arrangement.spacedBy(TvDetailSectionGap),
                    ) {
                        if (isAudiobook && audiobookParts.size > 1) {
                            TvAudiobookPartsSection(
                                tracks = audiobookParts,
                                versions = detail.versions,
                                // Tap plays from the part's whole-book (global)
                                // start offset with no part fileId — the VM
                                // resolves the part. (Pinning the part's fileId +
                                // 0.0 would resolve to Part 1 at global 0.)
                                onPartSelected = { track ->
                                    onPlay(
                                        detail.contentId,
                                        null,
                                        state.selectedAudioIndex,
                                        state.audioPickedThisSession,
                                        explicitTvSubtitleLaunchSelection(state.selectedSubtitleIndex),
                                        detail.type,
                                        track.startOffsetSeconds,
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

                        if (showsEpisodesSection) {
                            // Series owns a fixed first viewport through the
                            // selector. Entering it from Cast (or another
                            // supporting rail) restores the hero top with one
                            // smooth curve; internal mode/episode/selector
                            // moves then suppress native reveal entirely.
                            Box(
                                modifier = if (isSeriesDetail) {
                                    Modifier.onFocusChanged { focusState ->
                                        seriesPrimaryFocusGeneration += 1
                                        val generation = seriesPrimaryFocusGeneration
                                        if (focusState.hasFocus) {
                                            val needsRestore = !seriesPrimaryViewportActive.value
                                            seriesPrimaryViewportActive.value = true
                                            if (needsRestore) {
                                                coroutineScope.launch {
                                                    withFrameNanos { }
                                                    if (
                                                        seriesPrimaryViewportActive.value &&
                                                        seriesPrimaryFocusGeneration == generation
                                                    ) {
                                                        listState.animateSeriesPrimaryViewport(seriesHeroHeightPx)
                                                    }
                                                }
                                            }
                                        } else {
                                            // Preserve the lock across the tiny
                                            // hand-off gap between descendants.
                                            coroutineScope.launch {
                                                delay(180)
                                                if (seriesPrimaryFocusGeneration == generation) {
                                                    seriesPrimaryViewportActive.value = false
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier.detailSectionAnchor(listState, coroutineScope) { height, viewport ->
                                        (viewport - height) / 2f
                                    }
                                },
                            ) {
                            EpisodesSection(
                                detail = detail,
                                state = state,
                                entryEpisodeContentId = initialEpisodeContentId
                                    ?.takeUnless { state.entryEpisodeSelectionApplied },
                                showsSeasonChips = showsSeasonChips,
                                isShowingSeriesOverview = isShowingSeriesOverview,
                                onShowSelected = {
                                    isShowingSeriesOverview = true
                                    viewModel.onSeriesEpisodeActivated(null)
                                },
                                onReturnToHero = returnToHero,
                                onReturnToSeriesSelector = if (isSeriesDetail) {
                                    {
                                        selectorFocus.claimFocusOrReport(
                                            target = "detail_series_selector",
                                            action = "season_up",
                                        ) || returnToHero()
                                    }
                                } else {
                                    null
                                },
                                onSeasonSelected = { season ->
                                    if (detail.type == "series") {
                                        isShowingSeriesOverview = false
                                        viewModel.onSeasonSelected(season.seasonNumber)
                                    } else if (season.contentId != detail.contentId) {
                                        // Season/episode pages own their hero and
                                        // metadata. Replace that detail entry so
                                        // the selected rail cannot appear under
                                        // a stale header, and repeated paging
                                        // does not grow the back stack.
                                        onItemDetailReplace(season.contentId)
                                    }
                                },
                                onEpisodeSelected = { episode ->
                                    if (isSeriesDetail) {
                                        // The combined tvOS Series page never
                                        // pushes an episode-detail route. OK on
                                        // the focused card quick-plays it.
                                        isShowingSeriesOverview = false
                                        viewModel.onSeriesEpisodeActivated(episode.contentId)
                                        val launch = seriesEpisodePlaybackLaunch(state, episode)
                                        onPlay(
                                            episode.contentId,
                                            launch.fileId,
                                            launch.audioTrackIndex,
                                            launch.audioPickedThisSession,
                                            launch.subtitleSelection,
                                            "episode",
                                            launch.resumePositionSeconds,
                                        )
                                    } else {
                                        // Season/episode routes use the same
                                        // direct-play contract: no legacy
                                        // episode-detail page is pushed.
                                        val launch = seriesEpisodePlaybackLaunch(state, episode)
                                        onPlay(
                                            episode.contentId,
                                            launch.fileId,
                                            launch.audioTrackIndex,
                                            launch.audioPickedThisSession,
                                            launch.subtitleSelection,
                                            "episode",
                                            launch.resumePositionSeconds,
                                        )
                                    }
                                },
                                onEpisodeFocused = { episode ->
                                    if (isSeriesDetail) {
                                        // Entering the rail swaps the hero's
                                        // editorial and Play target to the
                                        // focused episode without hiding the
                                        // action row.
                                        isShowingSeriesOverview = false
                                        viewModel.onSeriesEpisodeActivated(episode.contentId)
                                    }
                                },
                                onSetEpisodeWatched = viewModel::onSetEpisodeWatched,
                                onSetEpisodeFavorite = viewModel::onSetEpisodeFavorite,
                            )
                            }
                        }

                        if (showsCastSection) {
                            Box(
                                modifier = Modifier
                                    .onFocusChanged { focusState ->
                                        if (focusState.hasFocus && isSeriesDetail) {
                                            seriesPrimaryFocusGeneration += 1
                                            seriesPrimaryViewportActive.value = false
                                        }
                                    }
                                    .detailBodySectionAnchor(listState, coroutineScope),
                            ) {
                            TvCastCrewSection(
                                cast = detail.cast,
                                horizontalContentPadding = TvDetailHorizontalInset,
                                firstItemFocusRequester = firstCastFocus,
                                // Cast is the first body rail only when there is no
                                // episode rail or season chips above it; Up then
                                // returns to the hero.
                                onDirectionUp = if (hasEpisodeNavAbove) null else returnToHero,
                                restoreFocusIndex = pendingCastFocusIndex,
                                // Only while the return is pending, so the
                                // rail's enter redirect reverts to card 0 for
                                // normal browsing afterwards.
                                restoreFocusRequester = castReturnFocus
                                    .takeIf { pendingCastFocusIndex >= 0 },
                                onRestoreCardFocused = { castRestoreFocused.value = true },
                                onCastMemberClick = { index, member ->
                                    // Record the index only once navigation
                                    // actually fires — openPerson can no-op when
                                    // the person can't be resolved.
                                    viewModel.openPerson(member) { personId ->
                                        pendingSimilarContentId = null
                                        pendingCastFocusIndex = index
                                        castRestoreFocused.value = false
                                        onOpenPerson(personId)
                                    }
                                },
                            )
                            }
                        }

                        if (showsTrailersSection) {
                            TvDetailTrailersSection(
                                entries = trailerEntries,
                                onSelectRemote = { video ->
                                    openTvYoutubeTrailer(detailContext, video)
                                },
                                onSelectLocal = { extra ->
                                    onPlay(
                                        extra.contentId,
                                        extra.fileId,
                                        null,
                                        false,
                                        null,
                                        "extra",
                                        0.0,
                                    )
                                },
                                onDirectionUp = if (!hasEpisodeNavAbove && !showsCastSection) {
                                    returnToHero
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .onFocusChanged { focusState ->
                                        if (focusState.hasFocus && isSeriesDetail) {
                                            seriesPrimaryFocusGeneration += 1
                                            seriesPrimaryViewportActive.value = false
                                        }
                                    }
                                    .detailBodySectionAnchor(listState, coroutineScope),
                            )
                        }

                        if (showsSimilarRail) {
                            // tvOS `TVSimilarRail`: an editorial detail section
                            // header (Recommended / More Like This) over a bare
                            // poster rail — no See-all on the detail page.
                            Column(
                                modifier = Modifier
                                    .onFocusChanged { focusState ->
                                        if (focusState.hasFocus && isSeriesDetail) {
                                            seriesPrimaryFocusGeneration += 1
                                            seriesPrimaryViewportActive.value = false
                                        }
                                    }
                                    .detailBodySectionAnchor(listState, coroutineScope),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                val similarTitle = if (isSeriesDetail) "Recommended Series" else "Related Movies"
                                TvDetailSectionHeader(
                                    title = similarTitle,
                                    modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
                                )
                                TvMediaRow(
                                    title = similarTitle,
                                    showHeader = false,
                                    items = state.moreLikeThis,
                                    onItemClick = { clickedContentId ->
                                        pendingCastFocusIndex = -1
                                        pendingSimilarGeneration += 1
                                        pendingSimilarContentId = clickedContentId
                                        similarRestoreFocused.value = false
                                        onItemDetail(clickedContentId)
                                    },
                                    restoreFocusIndex = pendingSimilarIndex,
                                    // Only while a return is pending, so ordinary
                                    // re-entry stops being forced at the return
                                    // target and goes back to the row restorer's
                                    // own remembered card.
                                    restoreFocusRequester = similarReturnFocus
                                        .takeIf { pendingSimilarIndex >= 0 },
                                    restoreFocusRequest = similarRestoreRequest
                                        .takeIf { pendingSimilarIndex >= 0 } ?: 0,
                                    onItemFocusedAtIndex = if (pendingSimilarIndex >= 0) {
                                        { focusedItem, _ ->
                                            // ONLY the target counts. Revoking
                                            // when some other card gains focus
                                            // was self-defeating: the row's own
                                            // enter redirect lands on card 0
                                            // first, so the restore cancelled
                                            // itself on the way to card N.
                                            // onFocusChanged carries no evidence
                                            // that a move was user-initiated —
                                            // the key handler on the root does.
                                            // Identity, not position: index
                                            // arithmetic is what broke when the
                                            // row learned to deduplicate, and
                                            // the item is right here anyway.
                                            if (focusedItem.contentId == pendingSimilarContentId) {
                                                similarRestoreFocused.value = true
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    style = TvRowStyle.Poster,
                                    horizontalPadding = TvDetailHorizontalInset,
                                    rowTopPadding = 0.dp,
                                    firstItemFocusRequester = firstSimilarFocus,
                                    onDirectionUp = if (!hasEpisodeNavAbove && !showsCastSection && !showsTrailersSection) {
                                        returnToHero
                                    } else {
                                        null
                                    },
                                    // When Similar is the first body rail (movie with no
                                    // episode rail and no cast), Up returns to the hero
                                    // Play button instead of relying on geometry.
                                    upFocusRequester = if (!hasEpisodeNavAbove && !showsCastSection && !showsTrailersSection) {
                                        playFocus
                                    } else {
                                        null
                                    },
                                )
                            }
                        }

                        if (showsDetailsSection) {
                            DetailsSection(
                                detail = detail,
                                modifier = Modifier
                                    .onFocusChanged { focusState ->
                                        if (focusState.hasFocus && isSeriesDetail) {
                                            seriesPrimaryFocusGeneration += 1
                                            seriesPrimaryViewportActive.value = false
                                        }
                                    }
                                    .detailBodySectionAnchor(listState, coroutineScope)
                                    // The section pads its own inner inset so the
                                    // focus highlight box extends past the text
                                    // instead of starting flush at its left edge.
                                    .padding(horizontal = TvDetailHorizontalInset - TvDetailsFocusInset),
                            )
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
                        key = "chapter-$index",
                        title = chapter.title,
                        subtitle = listOf(
                            chapter.partTitle,
                            formatAudiobookTime(chapter.startSeconds),
                        )
                            .filter { it.isNotBlank() }
                            .joinToString("  "),
                        onClick = {
                            chaptersDialogOpen = false
                            // Jump to the chapter's whole-book (global) start with
                            // no part fileId — the VM resolves the part.
                            onPlay(
                                detail.contentId,
                                null,
                                state.selectedAudioIndex,
                                state.audioPickedThisSession,
                                explicitTvSubtitleLaunchSelection(state.selectedSubtitleIndex),
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

/**
 * Quiet, non-pill playback readout directly under the fixed credit line.
 * It follows the same selected file and next-up episode state as Play, so a
 * Series episode focus change updates this line and the circular menus together.
 */
@Composable
private fun TvDetailPlaybackSelectionSummary(
    detail: ItemDetail,
    state: TvItemDetailUiState,
) {
    val usesNextUp = detail.type == "series" || detail.type == "season"
    val playbackDetail = if (usesNextUp) {
        state.nextUpPlaybackDetail.takeIf { state.nextUpTargetReady }
    } else {
        detail
    }
    val versions = playbackDetail?.versions.orEmpty()
    val selectedFileId = (if (usesNextUp) state.selectedNextUpFileId else state.selectedFileId)
        ?.takeIf { fileId -> versions.any { it.fileId == fileId } }
    val selectedAudioIndex = if (usesNextUp) state.selectedNextUpAudioIndex else state.selectedAudioIndex
    val selectedSubtitleIndex =
        if (usesNextUp) state.selectedNextUpSubtitleIndex else state.selectedSubtitleIndex
    val selectedVersion = remember(
        versions,
        selectedFileId,
        playbackDetail?.userData?.lastFileId,
        state.preferredQuality,
    ) {
        selectTvDetailDisplayVersion(
            versions = versions,
            selectedFileId = selectedFileId,
            lastFileId = playbackDetail?.userData?.lastFileId,
            preferredQuality = state.preferredQuality,
        )
    }
    val compactVersion = selectedVersion?.let(TvPlaybackFormatting::versionCompactLabel)
    val versionValue = if (selectedVersion == null) {
        null
    } else if (selectedFileId == null && compactVersion != "Auto") {
        "Auto · $compactVersion"
    } else {
        compactVersion
    }
    val automaticAudioTrackOrdinal = resolveTvAutomaticAudioTrackOrdinal(
        version = selectedVersion,
        preferredAudioLanguage = state.preferredAudioLanguage,
        capabilities = state.audioSelectionCapabilities,
    )
    val automaticAudioResolutionKnown = state.audioSelectionCapabilities != null
    val audioValue = selectedVersion?.let { version ->
        if (selectedAudioIndex == null && !automaticAudioResolutionKnown) {
            "Auto"
        } else {
            TvPlaybackFormatting.audioValueLabel(
                version = version,
                selectedAudioTrackIndex = selectedAudioIndex,
                automaticAudioTrackOrdinal = automaticAudioTrackOrdinal,
            ).replace("Auto: ", "Auto · ")
        }
    }
    val subtitleValue = selectedVersion?.let { version ->
        TvPlaybackFormatting.subtitleValueLabel(
            version = version,
            selectedSubtitleTrackIndex = selectedSubtitleIndex,
            autoContext = if (selectedAudioIndex != null || automaticAudioResolutionKnown) {
                TvPlaybackFormatting.SubtitleAutoContext(
                    preferredLanguage = state.preferredSubtitleLanguage,
                    mode = state.subtitleMode,
                    showForced = state.showForcedSubtitles,
                    audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                        version,
                        selectedAudioIndex,
                        automaticAudioTrackOrdinal,
                    ),
                )
            } else {
                null
            },
        ).replace("Auto - ", "Auto · ")
    }

    Row(
        modifier = Modifier.height(TV_PLAYBACK_SUMMARY_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TvPlaybackSummaryItem(
            label = "VERSION",
            value = versionValue,
            itemWidth = TV_PLAYBACK_VERSION_ITEM_WIDTH,
        )
        TvPlaybackSummaryItem(
            label = "AUDIO",
            value = audioValue,
            itemWidth = TV_PLAYBACK_AUDIO_ITEM_WIDTH,
        )
        TvPlaybackSummaryItem(
            label = "SUBTITLES",
            value = subtitleValue,
            itemWidth = TV_PLAYBACK_SUBTITLE_ITEM_WIDTH,
        )
    }
}

@Composable
private fun TvPlaybackSummaryItem(
    label: String,
    value: String?,
    itemWidth: androidx.compose.ui.unit.Dp,
) {
    // Items size to their text instead of a fixed width so long audio or
    // subtitle labels are never cut off. The skeleton keeps a fixed width so
    // the loading row stays stable until the values arrive.
    Row(
        modifier = if (value == null) Modifier.width(itemWidth) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = Color.White.copy(alpha = 0.48f),
            maxLines = 1,
        )
        if (value == null) {
            TvPlaybackSummarySkeleton(modifier = Modifier.weight(1f))
        } else {
            Text(
                text = value,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvPlaybackSummarySkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(7.dp)
            .background(
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(2.dp),
            ),
    )
}

private val TV_PLAYBACK_SUMMARY_HEIGHT = 20.dp
private val TV_PLAYBACK_VERSION_ITEM_WIDTH = 120.dp
private val TV_PLAYBACK_AUDIO_ITEM_WIDTH = 160.dp
private val TV_PLAYBACK_SUBTITLE_ITEM_WIDTH = 130.dp

internal fun seriesStarringCredit(detail: ItemDetail): String? {
    val names = detail.cast
        .asSequence()
        .filter { it.name.isNotBlank() }
        .sortedBy { it.order }
        .map { it.name.trim() }
        .distinct()
        .take(3)
        .toList()
    return names.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Starring ")
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HeroActionRow(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    viewModel: TvItemDetailViewModel,
    playFocus: FocusRequester,
    selectorFocus: FocusRequester,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, audioPickedThisSession: Boolean, subtitleSelection: TvSubtitleLaunchSelection?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onSeasonClick: (seriesId: String, seasonNumber: Int) -> Unit,
    onWatchTogether: (RoomSnapshot) -> Unit,
) {
    val suggestViewModel: TvSuggestToRoomViewModel = koinViewModel()
    val activeRoom by suggestViewModel.room.collectAsState()
    val suggestState by suggestViewModel.uiState.collectAsState()
    val suggestContext = LocalContext.current
    LaunchedEffect(suggestState.notice, suggestState.error) {
        val message = suggestState.notice ?: suggestState.error
        if (message != null) {
            Toast.makeText(suggestContext, message, Toast.LENGTH_SHORT).show()
            suggestViewModel.consumeNotice()
            suggestViewModel.clearError()
        }
    }
    var moreOpen by remember(detail.contentId) { mutableStateOf(false) }
    var watchTogetherOpen by remember(detail.contentId) { mutableStateOf(false) }
    var joinCodeOpen by remember(detail.contentId) { mutableStateOf(false) }
    var playLaunchPending by remember(detail.contentId) { mutableStateOf(false) }
    LaunchedEffect(playLaunchPending) {
        if (playLaunchPending) {
            // Navigation is synchronous, but TV remotes can deliver a second
            // Select activation while the destination is still settling.
            // Keep the action latched briefly so one intent creates one player.
            delay(750)
            playLaunchPending = false
        }
    }
    // Series / season detail target the *next-up episode* rather than the
    // container itself (mirrors silo-apple's TVSeriesDetailView /
    // TVSeasonDetailView). For those types the hero Play button, the resume
    // position, and the circular selectors all bind to the next-up episode's
    // own playback detail; movie / episode detail keep the container behavior.
    val isSeriesOrSeason = detail.type == "series" || detail.type == "season"
    val nextUp = state.nextUpEpisode.takeIf { isSeriesOrSeason }
    val nextUpDetail = state.nextUpPlaybackDetail.takeIf { state.nextUpTargetReady }

    // The contentId / type / versions / resume the Play action actually uses.
    val playContentId = nextUp?.contentId ?: detail.contentId
    val playType = if (nextUp != null) "episode" else detail.type
    // For series/season we must NOT fall back to playing the container — Play is
    // a no-op until the next-up episode resolves (episodes still loading, or an
    // empty/error season). Movie/episode are always ready.
    val playReady = !isSeriesOrSeason || (nextUp != null && state.nextUpTargetReady)
    val containerResume = remember(detail.userData) { detail.resumePositionSeconds() }
    val nextUpResume = remember(nextUp?.userData) { nextUp?.userData?.resumePositionSeconds() }
    val resumePosition = if (isSeriesOrSeason) nextUpResume else containerResume
    val hasResume = resumePosition != null

    // Every video detail owns a More menu. Favorite now lives there while the
    // higher-frequency Watchlist toggle is visible in the stable action row.
    val hasSeriesNavigation = detail.type in setOf("episode", "season") && detail.seriesId != null
    val hasOverflowNavigation = hasSeriesNavigation
    val hasWatchTogether =
        CLIENT_WATCH_TOGETHER_SURFACE_ENABLED && !isAudiobookItemType(detail.type)
    val hasSuggestionTarget = detail.type in setOf("movie", "episode") || nextUp != null
    val canSuggestToRoom =
        CLIENT_WATCH_TOGETHER_SURFACE_ENABLED && activeRoom != null && hasSuggestionTarget

    // Version set + selection state driving the selector row / Play file id.
    // Series/season use the next-up episode's versions + the next-up selection;
    // everything else uses the container's.
    val selectorVersions = if (isSeriesOrSeason) (nextUpDetail?.versions ?: emptyList()) else detail.versions
    val selectorSelectedFileId = (if (isSeriesOrSeason) state.selectedNextUpFileId else state.selectedFileId)
        // Drop a persisted/explicit fileId that no longer exists in the current
        // version set (e.g. the file was deleted since it was pinned). Otherwise
        // Play would launch a nonexistent fileId while the pill shows a valid
        // fallback version — so playback and the UI diverge (T22a). Falling to
        // null lets selectTvDetailDisplayVersion pick the pill's displayed
        // version, keeping Play and the UI in agreement.
        ?.takeIf { fileId -> selectorVersions.any { it.fileId == fileId } }
    val selectorAudioIndex = if (isSeriesOrSeason) state.selectedNextUpAudioIndex else state.selectedAudioIndex
    // Provenance has to follow the same branch as the ordinal: a fresh next-up
    // pick was otherwise reported using the unrelated container-level flag.
    val selectorAudioPicked =
        if (isSeriesOrSeason) state.nextUpAudioPickedThisSession else state.audioPickedThisSession
    val selectorSubtitleIndex =
        if (isSeriesOrSeason) state.selectedNextUpSubtitleIndex else state.selectedSubtitleIndex
    val selectorLastFileId = if (isSeriesOrSeason) {
        nextUpDetail?.userData?.lastFileId
    } else {
        detail.userData?.lastFileId
    }
    val selectedVersion = remember(
        selectorVersions,
        selectorSelectedFileId,
        selectorLastFileId,
        state.preferredQuality,
    ) {
        selectTvDetailDisplayVersion(
            versions = selectorVersions,
            selectedFileId = selectorSelectedFileId,
            lastFileId = selectorLastFileId,
            preferredQuality = state.preferredQuality,
        )
    }
    val selectedFileId = selectedVersion?.fileId
    val automaticAudioTrackOrdinal = resolveTvAutomaticAudioTrackOrdinal(
        version = selectedVersion,
        preferredAudioLanguage = state.preferredAudioLanguage,
        capabilities = state.audioSelectionCapabilities,
    )
    val automaticAudioResolutionKnown = state.audioSelectionCapabilities != null
    val hasTrackOverride = selectorAudioIndex != null || selectorSubtitleIndex != null
    // Exactly what the Subtitles pill is displaying — including the Auto
    // preview — so playback starts on that track instead of re-deciding from
    // the tracks Media3 happens to have mounted. Built from the SAME version
    // and the SAME context the pill renders from.
    val subtitleLaunchSelection = TvPlaybackFormatting.subtitleLaunchSelection(
        version = selectedVersion,
        selectedSubtitleTrackIndex = selectorSubtitleIndex,
        // No displayed version means no displayed pill: stay silent and let the
        // player resolve, rather than asserting an "Auto - None" nobody saw.
        autoContext = selectedVersion?.takeIf {
            selectorAudioIndex != null || automaticAudioResolutionKnown
        }?.let { version ->
            TvPlaybackFormatting.SubtitleAutoContext(
                preferredLanguage = state.preferredSubtitleLanguage,
                mode = state.subtitleMode,
                showForced = state.showForcedSubtitles,
                audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                    version,
                    selectorAudioIndex,
                    automaticAudioTrackOrdinal,
                ),
            )
        },
    )
    val playFileId = selectorSelectedFileId ?: selectedFileId.takeIf { hasTrackOverride }
    // No separate next-up autofocus: the Play button is always rendered and
    // focusable, and the screen already focuses it on detail load, so re-focusing
    // when next-up resolves would only risk yanking focus back if the viewer had
    // already moved into the seasons/episodes rails.

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Action row — approved tvOS `HStack(spacing: 18)` mapped through the
        // half-scale dp port as the button internals. Version and Subtitles use
        // the same circular chrome as the utility actions, so this cluster never
        // changes shape while focus moves across it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties {
                    // Entering the cluster from outside (Down from the
                    // synopsis/facts, Up from the body) always
                    // lands on Play — geometric nearest-child search would
                    // otherwise pick whichever toggle sits closest.
                    enter = { playFocus }
                }
                .focusGroup(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
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
                        if (playReady && !playLaunchPending) {
                            playLaunchPending = true
                            onPlay(
                                playContentId, playFileId,
                                selectorAudioIndex ?: automaticAudioTrackOrdinal,
                                selectorAudioPicked,
                                subtitleLaunchSelection,
                                playType, resumePosition,
                            )
                        }
                    },
                    // tvOS reserves 340pt for the Series Play/Resume label.
                    // Android's half-scale canvas maps that to 170dp, keeping
                    // every circular action on a fixed x-position as episode
                    // titles and progress state change.
                    modifier = if (detail.type == "series") Modifier.width(170.dp) else Modifier,
                    focusRequester = playFocus,
                )

                TvPlaybackActionSelectors(
                    versions = selectorVersions,
                    currentVersion = selectedVersion,
                    selectedVersionFileId = selectorSelectedFileId,
                    selectedAudioTrackIndex = selectorAudioIndex,
                    selectedSubtitleTrackIndex = selectorSubtitleIndex,
                    automaticAudioTrackOrdinal = automaticAudioTrackOrdinal,
                    automaticAudioResolutionKnown = automaticAudioResolutionKnown,
                    preferredSubtitleLanguage = state.preferredSubtitleLanguage,
                    subtitleMode = state.subtitleMode,
                    showForcedSubtitles = state.showForcedSubtitles,
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
                    versionFocusRequester = selectorFocus,
                )

                if (hasResume) {
                    // tvOS Start Over uses `backward.end.fill` (skip-to-start),
                    // not a replay arrow. It is an icon-only circular companion
                    // to the primary Play/Resume pill.
                    TvSquareToggleButton(
                        icon = Icons.Filled.SkipPrevious,
                        iconActive = Icons.Filled.SkipPrevious,
                        contentDescription = "Start Over",
                        isActive = false,
                        onClick = {
                            if (playReady && !playLaunchPending) {
                                playLaunchPending = true
                                onPlay(
                                    playContentId, playFileId,
                                    selectorAudioIndex ?: automaticAudioTrackOrdinal,
                                    selectorAudioPicked,
                                    subtitleLaunchSelection,
                                    playType, 0.0,
                                )
                            }
                        },
                    )
                }

                TvSquareToggleButton(
                    icon = Icons.Filled.BookmarkBorder,
                    iconActive = Icons.Filled.Bookmark,
                    isActive = state.inWatchlist,
                    contentDescription = if (state.inWatchlist) {
                        "Remove from watchlist"
                    } else {
                        "Add to watchlist"
                    },
                    onClick = viewModel::onToggleWatchlist,
                )

                TvSquareToggleButton(
                    icon = Icons.Filled.MoreHoriz,
                    iconActive = Icons.Filled.MoreHoriz,
                    isActive = false,
                    contentDescription = "More options",
                    onClick = { moreOpen = true },
                )
            }
        }
    }

    if (moreOpen) {
        val options = buildList {
            add(
                TvDialogOption(
                    key = "favorite",
                    title = if (state.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    selected = state.isFavorite,
                    onClick = {
                        moreOpen = false
                        viewModel.onToggleFavorite()
                    },
                ),
            )
            add(
                TvDialogOption(
                    key = "watched",
                    title = if (state.isWatched) watchedUnmarkLabel(detail) else watchedMarkLabel(detail),
                    selected = state.isWatched,
                    onClick = {
                        moreOpen = false
                        viewModel.onToggleWatched()
                    },
                ),
            )
            if (canSuggestToRoom) {
                add(
                    TvDialogOption(
                        key = "suggest-to-room",
                        title = "Suggest to Watch Together",
                        subtitle = "Add to the room you are in",
                        onClick = {
                            moreOpen = false
                            suggestViewModel.suggest(
                                contentId = playContentId,
                                contentType = playType,
                                title = nextUp?.title ?: detail.title,
                                subtitle = if (nextUp != null) detail.title else detail.seriesTitle,
                                posterUrl = nextUp?.stillUrl ?: detail.posterUrl,
                            )
                        },
                    ),
                )
            }
            if (hasWatchTogether) {
                add(
                    TvDialogOption(
                        key = "watch-together",
                        title = "Watch Together",
                        subtitle = "Host a room or join by code",
                        onClick = {
                            moreOpen = false
                            watchTogetherOpen = true
                        },
                    ),
                )
            }
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

    if (watchTogetherOpen && hasWatchTogether) {
        val watchTogetherViewModel: TvWatchTogetherViewModel = koinViewModel()
        val watchTogetherState by watchTogetherViewModel.uiState.collectAsState()

        LaunchedEffect(watchTogetherState.result) {
            val room = watchTogetherState.result ?: return@LaunchedEffect
            watchTogetherViewModel.consumeResult()
            watchTogetherOpen = false
            joinCodeOpen = false
            onWatchTogether(room)
        }

        if (joinCodeOpen) {
            TvJoinCodeDialog(
                isBusy = watchTogetherState.isBusy,
                error = watchTogetherState.error,
                onJoin = watchTogetherViewModel::joinRoom,
                onDismiss = {
                    watchTogetherViewModel.clearError()
                    joinCodeOpen = false
                },
            )
        } else {
            TvWatchTogetherEntryDialog(
                isBusy = watchTogetherState.isBusy,
                error = watchTogetherState.error,
                onHost = { watchTogetherViewModel.createRoom(playContentId, playFileId) },
                onHostVote = watchTogetherViewModel::createEmptyVoteRoom,
                onJoin = {
                    watchTogetherViewModel.clearError()
                    joinCodeOpen = true
                },
                onDismiss = {
                    watchTogetherViewModel.clearError()
                    watchTogetherOpen = false
                },
            )
        }
    }
}

internal data class SeriesEpisodePlaybackLaunch(
    val fileId: Int?,
    val audioTrackIndex: Int?,
    val audioPickedThisSession: Boolean,
    val subtitleSelection: TvSubtitleLaunchSelection?,
    val resumePositionSeconds: Double?,
)

/**
 * Builds the quick-play intent for one Series episode. Selection state is used
 * only when it belongs to this exact episode; a fast OK during an episode
 * focus hand-off must never carry the previous episode's file or track IDs.
 */
internal fun seriesEpisodePlaybackLaunch(
    state: TvItemDetailUiState,
    episode: EpisodeListItem,
): SeriesEpisodePlaybackLaunch {
    val selectionMatchesEpisode =
        state.nextUpEpisode?.contentId == episode.contentId &&
            state.nextUpPlaybackDetail?.contentId == episode.contentId
    val playbackDetail = state.nextUpPlaybackDetail.takeIf { selectionMatchesEpisode }
    val versions = playbackDetail?.versions.orEmpty()
    val explicitFileId = state.selectedNextUpFileId
        ?.takeIf { selectionMatchesEpisode }
        ?.takeIf { selected -> versions.any { it.fileId == selected } }
    val audioTrackIndex = state.selectedNextUpAudioIndex.takeIf { selectionMatchesEpisode }
    val subtitleTrackIndex = state.selectedNextUpSubtitleIndex.takeIf { selectionMatchesEpisode }
    val selectedVersion = selectTvDetailDisplayVersion(
        versions = versions,
        selectedFileId = explicitFileId,
        lastFileId = playbackDetail?.userData?.lastFileId,
        preferredQuality = state.preferredQuality,
    )
    val automaticAudioTrackOrdinal = resolveTvAutomaticAudioTrackOrdinal(
        version = selectedVersion,
        preferredAudioLanguage = state.preferredAudioLanguage,
        capabilities = state.audioSelectionCapabilities,
    )
    val automaticAudioResolutionKnown = state.audioSelectionCapabilities != null
    val hasTrackOverride = audioTrackIndex != null || subtitleTrackIndex != null
    val subtitleSelection = TvPlaybackFormatting.subtitleLaunchSelection(
        version = selectedVersion,
        selectedSubtitleTrackIndex = subtitleTrackIndex,
        autoContext = selectedVersion?.takeIf {
            audioTrackIndex != null || automaticAudioResolutionKnown
        }?.let { version ->
            TvPlaybackFormatting.SubtitleAutoContext(
                preferredLanguage = state.preferredSubtitleLanguage,
                mode = state.subtitleMode,
                showForced = state.showForcedSubtitles,
                audioLanguage = TvPlaybackFormatting.resolvedAudioLanguage(
                    version,
                    audioTrackIndex,
                    automaticAudioTrackOrdinal,
                ),
            )
        },
    )
    return SeriesEpisodePlaybackLaunch(
        fileId = explicitFileId ?: selectedVersion?.fileId?.takeIf { hasTrackOverride },
        audioTrackIndex = audioTrackIndex ?: automaticAudioTrackOrdinal,
        audioPickedThisSession = selectionMatchesEpisode && state.nextUpAudioPickedThisSession,
        subtitleSelection = subtitleSelection,
        resumePositionSeconds = episode.userData?.resumePositionSeconds(),
    )
}

private fun openTvYoutubeTrailer(context: android.content.Context, video: ItemVideo) {
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${video.siteKey}"))
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.youtube.com/watch?v=${video.siteKey}"),
    )
    runCatching { context.startActivity(appIntent) }
        .recoverCatching { context.startActivity(webIntent) }
        .onFailure {
            Toast.makeText(context, "No app is available to open this trailer", Toast.LENGTH_SHORT).show()
        }
}

private fun watchedMarkLabel(detail: ItemDetail): String = when (detail.type) {
    "series" -> "Mark Series Watched"
    "season" -> "Mark Season Watched"
    "episode" -> "Mark Episode Watched"
    "movie" -> "Mark Movie Watched"
    else -> "Mark as Watched"
}

private fun watchedUnmarkLabel(detail: ItemDetail): String = when (detail.type) {
    "series" -> "Mark Series Unwatched"
    "season" -> "Mark Season Unwatched"
    "episode" -> "Mark Episode Unwatched"
    "movie" -> "Mark Movie Unwatched"
    else -> "Mark as Unwatched"
}

/** Tiny breathing room between the fixed hero controls and the series modes. */
private val SeriesSeasonPickerTopPadding = 3.dp

private data class SeriesEpisodeRailPage(
    val seasonNumber: Int,
    val episodes: List<EpisodeListItem>,
)

private const val SERIES_EPISODE_CAROUSEL_DURATION_MS = 360

internal fun seriesEpisodeCarouselDirection(
    fromSeasonNumber: Int,
    toSeasonNumber: Int,
    seasons: List<Season>,
): Int {
    if (fromSeasonNumber == toSeasonNumber) return 0
    val fromIndex = seasons.indexOfFirst { it.seasonNumber == fromSeasonNumber }
    val toIndex = seasons.indexOfFirst { it.seasonNumber == toSeasonNumber }
    return if (fromIndex >= 0 && toIndex >= 0) {
        if (toIndex > fromIndex) 1 else -1
    } else {
        if (toSeasonNumber > fromSeasonNumber) 1 else -1
    }
}

@Composable
private fun EpisodesSection(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    entryEpisodeContentId: String?,
    showsSeasonChips: Boolean,
    isShowingSeriesOverview: Boolean,
    onShowSelected: () -> Unit,
    onReturnToHero: () -> Boolean,
    onReturnToSeriesSelector: (() -> Boolean)? = null,
    onSeasonSelected: (org.siloserver.silo.model.catalog.Season) -> Unit,
    onEpisodeSelected: (EpisodeListItem) -> Unit,
    onEpisodeFocused: (EpisodeListItem) -> Unit = {},
    onSetEpisodeWatched: (contentId: String, watched: Boolean) -> Unit,
    onSetEpisodeFavorite: (contentId: String, favorite: Boolean) -> Unit,
) {
    val isSeries = detail.type == "series"
    Column(verticalArrangement = Arrangement.spacedBy(if (isSeries) 7.dp else 20.dp)) {
        if (!isSeries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TvDetailHorizontalInset),
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
        }

        if (showsSeasonChips) {
            if (isSeries) {
                TvSeriesModePicker(
                    seasons = state.seasons,
                    isShowingSeriesOverview = isShowingSeriesOverview,
                    selectedSeason = state.selectedSeason,
                    onShowSelected = onShowSelected,
                    onSeasonSelected = onSeasonSelected,
                    // Keep the approved row geometry, adding only the small
                    // requested breathing room below the compact hero.
                    modifier = Modifier.padding(top = SeriesSeasonPickerTopPadding),
                    // Return to the circular Version control in the hero; if
                    // it is not attached yet, fall back to Play.
                    onDirectionUp = onReturnToSeriesSelector ?: onReturnToHero,
                    horizontalContentPadding = TvDetailHorizontalInset,
                )
            } else {
                TvSeasonPicker(
                    seasons = state.seasons,
                    selectedSeason = state.selectedSeason,
                    onSeasonSelected = onSeasonSelected,
                    onDirectionUp = onReturnToHero,
                    horizontalContentPadding = TvDetailHorizontalInset,
                )
            }
        }

        // Reserve the rail's measured height while a newly-selected season
        // loads (and for empty seasons), so the spinner/empty states don't
        // collapse the section and flash Cast & Crew up into the viewport.
        var railHeightPx by remember { mutableStateOf(0) }
        val railMinHeight = with(LocalDensity.current) { railHeightPx.toDp() }
        Box(
            modifier = Modifier
                .heightIn(min = railMinHeight)
                // Keep a small separation from Seasons while reclaiming the
                // space previously occupied by the selector band.
                .padding(top = if (isSeries && showsSeasonChips) 10.dp else 0.dp),
        ) {
            when {
                // The first season still gets a loading indicator. Once a Series rail
                // exists, retain it until the requested season is ready so the two
                // complete rails can perform the directional carousel hand-off below.
                // Non-Series detail routes retain their established spinner behavior.
                state.episodesLoading && (!isSeries || state.episodes.isEmpty()) -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TvDetailHorizontalInset, vertical = 24.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                // A season that legitimately has zero episodes (or a load that failed
                // and left nothing to show): keep the section and chips mounted and
                // say so, rather than unmounting everything and stranding the user
                // (T15a).
                state.episodes.isEmpty() -> {
                    Text(
                        text = "No episodes available",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = TvDetailHorizontalInset, vertical = 8.dp),
                    )
                }
                else -> {
                    val renderEpisodeRail: @Composable (List<EpisodeListItem>) -> Unit = { episodes ->
                        TvDetailEpisodeRail(
                            episodes = episodes,
                            currentContentId = currentEpisodeRailContentId(
                                detail = detail,
                                state = state,
                                entryEpisodeContentId = entryEpisodeContentId,
                            ),
                            favoriteStates = state.episodeFavoriteStates,
                            onEpisodeSelected = onEpisodeSelected,
                            onEpisodeFocused = onEpisodeFocused.takeIf { isSeries },
                            onSetWatched = onSetEpisodeWatched,
                            onSetFavorite = onSetEpisodeFavorite,
                            // Up returns to the hero only when the season chips aren't above
                            // the rail (the chips own the Up traversal when present).
                            onDirectionUp = if (showsSeasonChips) null else onReturnToHero,
                            hidesEpisodeTitle = isSeries,
                            usesSeriesGeometry = isSeries,
                            modifier = Modifier.onSizeChanged { railHeightPx = it.height },
                        )
                    }

                    if (isSeries) {
                        // Retain the outgoing season while the next one loads,
                        // then move both complete rails as one viewport-wide
                        // carousel page. Card geometry and each rail's own
                        // focus/scroll state remain entirely unchanged.
                        val railPage = SeriesEpisodeRailPage(
                            seasonNumber = state.episodes.first().seasonNumber,
                            episodes = state.episodes,
                        )
                        AnimatedContent(
                            targetState = railPage,
                            contentKey = { it.seasonNumber },
                            transitionSpec = {
                                val direction = seriesEpisodeCarouselDirection(
                                    fromSeasonNumber = initialState.seasonNumber,
                                    toSeasonNumber = targetState.seasonNumber,
                                    seasons = state.seasons,
                                )
                                (
                                    slideInHorizontally(
                                        animationSpec = tween(
                                            durationMillis = SERIES_EPISODE_CAROUSEL_DURATION_MS,
                                            easing = FastOutSlowInEasing,
                                        ),
                                        initialOffsetX = { width -> width * direction },
                                    ) togetherWith slideOutHorizontally(
                                        animationSpec = tween(
                                            durationMillis = SERIES_EPISODE_CAROUSEL_DURATION_MS,
                                            easing = FastOutSlowInEasing,
                                        ),
                                        targetOffsetX = { width -> -width * direction },
                                    )
                                ).using(SizeTransform(clip = false))
                            },
                            label = "seriesEpisodeCarousel",
                            modifier = Modifier.fillMaxWidth(),
                        ) { page ->
                            renderEpisodeRail(page.episodes)
                        }
                    } else {
                        renderEpisodeRail(state.episodes)
                    }
                }
            }
        }

    }
}

internal fun currentEpisodeRailContentId(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    entryEpisodeContentId: String? = null,
): String? =
    when (detail.type) {
        "episode" -> detail.contentId
        "series",
        "season",
        -> entryEpisodeContentId ?: state.nextUpEpisode?.contentId
        else -> null
    }

internal fun episodeEyebrowLabel(detail: ItemDetail, state: TvItemDetailUiState): String {
    state.seasons
        .firstOrNull { it.seasonNumber == state.selectedSeason }
        ?.let { season ->
            return if (season.isSpecialsForDisplay()) "Specials" else "Season ${season.seasonNumber}"
        }
    state.selectedSeason?.let { return if (it == 0) "Specials" else "Season $it" }
    if (detail.type == "episode") {
        detail.seasonNumber?.let { return if (it == 0) "Specials" else "Season $it" }
    }
    return "This Season"
}

@Composable
private fun DetailsSection(
    detail: ItemDetail,
    modifier: Modifier = Modifier,
) {
    // Focusable (QA 2026-07-08): without a focus stop the D-pad drives from
    // Cast & Crew straight past Details to the Recommended rail, so the facts
    // can never be brought into view deliberately. The section is a passive
    // stop — focusing it just scroll-anchors and highlights subtly.
    var factsFocused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .onFocusChanged { factsFocused = it.isFocused }
            .focusable()
            .background(
                color = if (factsFocused) Color.White.copy(alpha = 0.06f) else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = TvDetailsFocusInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        TvDetailSectionHeader(title = "Details")
        TvDetailFactsTable(detail = detail)
    }
}

/**
 * Inner inset between the Details focus-highlight box and its text; the
 * caller subtracts it from the safe-area padding so the text stays aligned
 * with the other sections while the box breathes around it.
 */
private val TvDetailsFocusInset = 20.dp

@Composable
private fun TvAudiobookPartsSection(
    tracks: List<AudioPlaybackTrack>,
    versions: List<FileVersion>,
    onPartSelected: (AudioPlaybackTrack) -> Unit,
    firstRowUpFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvDetailSectionHeader(title = "Parts")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tracks.forEachIndexed { index, track ->
                val version = versions.firstOrNull { it.fileId == track.fileId }
                TvAudiobookDetailActionRow(
                    title = audiobookPartTitle(version, track.index),
                    subtitle = audiobookPartSubtitle(version),
                    trailing = audiobookDurationLabel(track.durationSeconds),
                    onClick = { onPartSelected(track) },
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
        TvDetailSectionHeader(title = "Chapters")
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
        TvDetailSectionHeader(title = "Alternate Narrations")
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
        TvDetailSectionHeader(title = title)
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
    val title: String,
    val partTitle: String,
    /** Whole-book (global) start offset; the chapter tap seeks here. */
    val startSeconds: Double,
)

/**
 * Chapter list for the detail dialog, in whole-book (global) space.
 *
 * Multi-part books use the stitched [AudiobookTimeline.chapters] (each chapter's
 * `startSeconds` already offset by its part's start) so a tap jumps to the right
 * place in the right part. Single-part / no-timeline books keep the single
 * file's own chapters unchanged, so their behaviour is exactly as before.
 */
private fun audiobookDisplayChapters(
    timeline: AudiobookTimeline?,
    versions: List<FileVersion>,
): List<TvAudiobookDisplayChapter> {
    if (timeline != null && !timeline.isSingle) {
        return timeline.chapters.map { chapter ->
            TvAudiobookDisplayChapter(
                title = chapter.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${chapter.index + 1}",
                partTitle = "Part ${chapter.trackIndex + 1}",
                startSeconds = chapter.startSeconds,
            )
        }
    }
    val file = timeline?.tracks?.firstOrNull()?.fileId
        ?.let { fileId -> versions.firstOrNull { it.fileId == fileId } }
        ?: versions.firstOrNull()
    return file?.chapters.orEmpty().mapIndexed { index, chapter ->
        TvAudiobookDisplayChapter(
            title = audiobookChapterTitle(index, chapter),
            partTitle = "",
            startSeconds = chapter.startSeconds,
        )
    }
}

private fun audiobookChapterTitle(
    fallbackIndex: Int,
    chapter: VersionChapter,
): String = chapter.title.trim().takeIf { it.isNotBlank() } ?: "Chapter ${fallbackIndex + 1}"

/** Part row label: the file name when present, else "Part {presentationPartIndex}"
 *  (falling back to the 0-based track [position], displayed 1-based). Mirrors the
 *  phone AudiobookDetailContent `partTitle`. */
private fun audiobookPartTitle(version: FileVersion?, position: Int): String {
    version?.fileName?.takeIf { it.isNotBlank() }?.let { return it }
    val rawIndex = version?.presentationPartIndex ?: position
    val displayIndex = if (rawIndex <= 0) rawIndex + 1 else rawIndex
    return "Part $displayIndex"
}

private fun audiobookPartSubtitle(part: FileVersion?): String? =
    listOfNotNull(
        part?.codecAudio?.takeIf { it.isNotBlank() }?.uppercase(),
        part?.container?.takeIf { it.isNotBlank() }?.uppercase(),
        part?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" },
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

private fun org.siloserver.silo.model.catalog.LeafItemUserData.resumePositionSeconds(): Double? {
    val pos = positionSeconds ?: return null
    val dur = durationSeconds ?: return null
    if (pos <= 30 || dur <= 0 || pos >= dur - 5) return null
    return pos
}

/**
 * Hero Play button label. Movie / episode detail keep the plain Play /
 * Resume<hms> form; series / season detail target the next-up episode and read
 * "Play S2:E3" / "Resume S2:E3" (series) or "Play E4" / "Resume E4"
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
            "$verb S${nextUp.seasonNumber}:E${nextUp.episodeNumber}"
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

/** Approved tvOS 64pt body rhythm and 140pt page tail at Android's 0.5 scale. */
internal val TvDetailSectionGap = 32.dp
internal val TvDetailPageBottomPadding = 70.dp
internal val TvDetailHorizontalInset = 50.dp

private val TvDetailDefaultTint = Color(red = 0.04f, green = 0.12f, blue = 0.14f)
private const val TvDetailTintOpacity = 0.42f

/**
 * The sampled tint is composited over black into a fully opaque color. Keeping
 * this pure makes it explicit that detail never uses a live blur/material.
 */
internal fun tvDetailPageSurfaceColor(sampledTint: Color?): Color {
    val tint = sampledTint ?: TvDetailDefaultTint
    return Color(
        red = tint.red * TvDetailTintOpacity,
        green = tint.green * TvDetailTintOpacity,
        blue = tint.blue * TvDetailTintOpacity,
        alpha = 1f,
    )
}

internal data class TvDetailHeroArtwork(
    val url: String?,
    val thumbhash: String?,
)

/**
 * Item detail responses can legitimately omit series/season artwork even
 * though their loaded episode rows carry landscape stills. Prefer the real
 * backdrop, then an episodic landscape still; never stretch a portrait poster
 * across a movie/series hero.
 */
internal fun resolveTvDetailHeroArtwork(
    detail: ItemDetail,
    nextUpEpisode: EpisodeListItem?,
): TvDetailHeroArtwork {
    if (!detail.backdropUrl.isNullOrBlank() || !detail.backdropThumbhash.isNullOrBlank()) {
        return TvDetailHeroArtwork(detail.backdropUrl, detail.backdropThumbhash)
    }
    return when (detail.type.lowercase()) {
        "series", "season" -> TvDetailHeroArtwork(
            nextUpEpisode?.stillUrl,
            nextUpEpisode?.stillThumbhash,
        )
        "episode" -> TvDetailHeroArtwork(detail.posterUrl, detail.posterThumbhash)
        else -> TvDetailHeroArtwork(null, null)
    }
}

/**
 * The ONE motion spec for every scroll on the detail page — section anchors,
 * return-to-hero, and the fallback bring-into-view. tvOS's detail focus
 * choreography runs `easeInOut(0.45)` (`TVDetailFocusScroll.swift`); here a
 * slightly quicker fast-out/slow-in reads calmer over long distances than
 * ease-in-out (which lurches mid-flight) and the page no longer mixes a
 * 260ms anchor with a 620ms rail reveal.
 */
private val DetailAnchorScrollSpec = tween<Float>(durationMillis = 400, easing = FastOutSlowInEasing)

/** tvOS Series uses a slower, single smooth curve when supporting content
 * hands focus back to the fixed Show/Season/Episodes/Version viewport. */
private val SeriesPrimaryScrollSpec = tween<Float>(durationMillis = 800, easing = FastOutSlowInEasing)

/**
 * Where a body section's top edge lands when focus enters it, as a fraction
 * of the viewport height. Anchoring the SECTION (not the focused card) means
 * Cast, Details and More Like This all frame identically, so each Down is a
 * uniform section-sized step instead of a gutter nudge of a different size.
 */
private const val DetailSectionAnchorFraction = 0.18f

/**
 * Anchors the section to a fixed viewport line the moment focus ENTERS it
 * (moving within the section does nothing). [targetY] receives the section's
 * height and the viewport height and returns the y (in viewport px) its top
 * should sit at. The generalisation of the episodes-section centering, so
 * every body section shares one choreography.
 */
private fun Modifier.detailSectionAnchor(
    listState: LazyListState,
    scope: CoroutineScope,
    targetY: (sectionHeight: Float, viewportHeight: Float) -> Float,
): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    var topInRoot by remember { mutableStateOf<Float?>(null) }
    var height by remember { mutableStateOf(0f) }
    this
        .onGloballyPositioned { coords ->
            topInRoot = coords.positionInRoot().y
            height = coords.size.height.toFloat()
        }
        .onFocusChanged { focusState ->
            val nowFocused = focusState.hasFocus
            if (nowFocused && !hasFocus) {
                scope.launch {
                    // Let the focus system enqueue its automatic bring-into-view
                    // first, then cancel/replace that scroll with the anchor.
                    withFrameNanos { }
                    if (!hasFocus) return@launch
                    val top = topInRoot ?: return@launch
                    val viewport = listState.layoutInfo.viewportSize.height.toFloat()
                    listState.animateScrollBy(
                        value = top - targetY(height, viewport),
                        animationSpec = DetailAnchorScrollSpec,
                    )
                }
            }
            hasFocus = nowFocused
        }
}

/** Section-top anchor shared by Cast, Details and More Like This. */
private fun Modifier.detailBodySectionAnchor(listState: LazyListState, scope: CoroutineScope): Modifier =
    detailSectionAnchor(listState, scope) { _, viewport -> viewport * DetailSectionAnchorFraction }

/**
 * Paced anchor scroll used for the return-to-hero jump.
 * `animateScrollToItem` animates with a fixed internal spec that can't be
 * customized, so when the target item is already composed we animate the
 * exact remaining distance with our own spec; otherwise (deep off-screen
 * target) fall back to the stock jump.
 */
private suspend fun LazyListState.animateScrollToItemPaced(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        animateScrollBy(
            value = item.offset.toFloat(),
            animationSpec = DetailAnchorScrollSpec,
        )
    } else {
        animateScrollToItem(index)
    }
}

/**
 * Restores the complete Series primary viewport without the stock LazyList
 * snap that otherwise appears when the hero item has already been disposed.
 * This page has two vertical list items (hero + body), so the measured hero
 * height plus the body's current offset gives the exact distance back to zero.
 */
private suspend fun LazyListState.animateSeriesPrimaryViewport(heroHeightPx: Int) {
    if (heroHeightPx <= 0) {
        animateScrollToItemPaced(0)
        return
    }
    val distance = when (firstVisibleItemIndex) {
        0 -> -firstVisibleItemScrollOffset.toFloat()
        1 -> -(heroHeightPx + firstVisibleItemScrollOffset).toFloat()
        else -> {
            animateScrollToItem(0)
            return
        }
    }
    if (distance != 0f) {
        animateScrollBy(
            value = distance,
            animationSpec = SeriesPrimaryScrollSpec,
        )
    }
}

/**
 * Wall-clock budget for the More Like This rail to load a restore target.
 *
 * Deliberately short. Landing back on the card you came from is a nicety, and
 * one that stops being welcome the moment the user has started doing something
 * else — a restore that fires seconds later reads as the app yanking focus, not
 * as helpfulness. Past this the ordinary hero fallback runs instead.
 *
 * Bounds the DATA wait only. Once the target resolves, focus attachment gets a
 * further ~80 frames, so the whole restore can outlast this value.
 */
/**
 * Frames the cast return-restore will keep trying for. Was an open-coded
 * `for (attempt in 0 until 40)`; the number is preserved so the window is the
 * same length it has always been.
 */
private const val CAST_RESTORE_MAX_ATTEMPTS = 40

private const val RESTORE_DATA_TIMEOUT_MS = 1_500L

/**
 * Wall-clock ceiling on the focus-attachment retries.
 *
 * The retry budget is a frame count because attachment is a composition
 * concern, but a frame count is not a duration — at 24Hz eighty frames is over
 * three seconds, and with frame production paused it is unbounded. This caps
 * how long the viewer can be fighting a restore for.
 */
private const val RESTORE_ATTACH_TIMEOUT_MS = 2_000L

/** Direction keys that count as the viewer steering for themselves. */
private val tvDirectionalKeys = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
)
