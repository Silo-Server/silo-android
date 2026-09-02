@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.TrackSelectionPresets
import org.siloserver.silo.common.player.video.EpisodeSelectionHandoff
import org.siloserver.silo.common.player.video.EpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.EpisodeSubtitleMode
import org.siloserver.silo.common.player.video.captureEpisodeSourceIntent
import org.siloserver.silo.common.player.video.captureEpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.resolveEpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.resolveEpisodeSourceIntent
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.initialSeasonDisplayPlan
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.playback.resolveSubtitleTrackOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.tv.ui.util.isTvHiddenMediaType
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.viewmodel.applyLocalPlaybackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class TvItemDetailUiState(
    val isLoading: Boolean = true,
    val detail: ItemDetail? = null,
    val error: String? = null,
    // User state toggles.
    val isFavorite: Boolean = false,
    val inWatchlist: Boolean = false,
    val isWatched: Boolean = false,
    val isTogglingFavorite: Boolean = false,
    val isTogglingWatchlist: Boolean = false,
    val isTogglingWatched: Boolean = false,
    val userRating: Int? = null,
    val isTogglingRating: Boolean = false,
    // Series navigation (only relevant when detail.type == "series").
    val seasons: List<Season> = emptyList(),
    val selectedSeason: Int? = null,
    val episodes: List<EpisodeListItem> = emptyList(),
    val episodeFavoriteStates: Map<String, Boolean> = emptyMap(),
    /** The route's one-shot episode target has been applied (or safely rejected). */
    val entryEpisodeSelectionApplied: Boolean = false,
    val seasonsLoading: Boolean = false,
    val episodesLoading: Boolean = false,
    // Version selection for multi-file items.
    val selectedFileId: Int? = null,
    // Pre-playback track selection (null = use server/auto default). Subtitle
    // index -1 means "Off". Reset whenever the version changes since each file
    // has its own track lists.
    val selectedAudioIndex: Int? = null,
    /**
     * True only when the viewer picked the audio here, this session.
     *
     * A restored durable value and a fresh pick both land in
     * [selectedAudioIndex], and the player cannot tell them apart from the
     * ordinal alone — so a restore would masquerade as a new decision and pin
     * itself onto every later episode.
     */
    val audioPickedThisSession: Boolean = false,
    val selectedSubtitleIndex: Int? = null,
    // Catalog-backed related shelf. This is a same-type / same-primary-genre
    // browse query until the server exposes an item-specific related endpoint.
    val moreLikeThis: List<SectionItem> = emptyList(),
    val moreLikeThisLoading: Boolean = false,
    // --- Next-up episode (series / season detail only) ---
    // The episode the hero Play button targets: an in-progress episode if one
    // exists, else the first unwatched, else the first. Mirrors silo-apple's
    // `nextUpEpisode`.
    val nextUpEpisode: EpisodeListItem? = null,
    /**
     * Whether [nextUpEpisode] belongs to the currently selected season and is
     * safe to launch. During a season swap the old episode remains only as a
     * geometry placeholder so the stable action row does not jump.
     */
    val nextUpTargetReady: Boolean = false,
    // The next-up episode's loaded playback detail (versions / tracks). Loaded
    // asynchronously whenever the next-up episode changes — analogue of Apple's
    // `nextUpPlaybackDetail`.
    val nextUpPlaybackDetail: ItemDetail? = null,
    val isLoadingNextUpPlaybackDetail: Boolean = false,
    val didLoadNextUpPlaybackDetail: Boolean = false,
    // Per-next-up version / track overrides (separate from the container's
    // selectedFileId/audio/subtitle, which series/season detail does not use).
    val selectedNextUpFileId: Int? = null,
    val selectedNextUpAudioIndex: Int? = null,
    /** As [audioPickedThisSession], for the series/season next-up selector. */
    val nextUpAudioPickedThisSession: Boolean = false,
    val selectedNextUpSubtitleIndex: Int? = null,
    val preferredQuality: String = "auto",
    val preferredAudioLanguage: String? = null,
    val audioSelectionCapabilities: ClientCodecCapabilities? = null,
    // Cascaded subtitle preferences that annotate the selector row's Auto
    // preview ("Auto - <track>" / "Auto - None") so it previews the SAME track
    // the player would auto-select. Resolved canonically — see
    // [loadSubtitlePreferences]; the profile columns are only the fallback for
    // a server that cannot resolve. `preferredSubtitleLanguage` null is "no
    // preference" and "" is "no subtitles"; showForced defaults to true when
    // unset, as the player state does.
    val preferredSubtitleLanguage: String? = null,
    val subtitleMode: String? = null,
    val showForcedSubtitles: Boolean = true,
)

internal data class TvTrackSelectionPersistence(
    val contentId: String,
    val fileId: Int,
    val audioFingerprint: String?,
    val subtitleFingerprint: String?,
)

internal fun resolveTvTrackSelectionVersion(
    detail: ItemDetail,
    selectedFileId: Int?,
    preferredQuality: String?,
): FileVersion? = selectTvDetailDisplayVersion(
    versions = detail.versions,
    selectedFileId = selectedFileId,
    lastFileId = detail.userData?.lastFileId,
    preferredQuality = preferredQuality,
)

internal fun buildTrackSelectionPersistence(
    targetContentId: String,
    version: FileVersion,
    selectedAudioIndex: Int?,
    selectedSubtitleIndex: Int?,
): TvTrackSelectionPersistence {
    val tracks = version.subtitleTracks.orEmpty()
    val subtitleFingerprint = when (selectedSubtitleIndex) {
        null -> null
        -1 -> SUBTITLE_OFF_FINGERPRINT
        else -> tracks
            .getOrNull(combinedSubtitleSelectionIndexes(tracks).indexOf(selectedSubtitleIndex))
            ?.let(::subtitleTrackFingerprint)
    }
    val audioFingerprint = selectedAudioIndex
        ?.let(version.audioTracks.orEmpty()::getOrNull)
        ?.let(::audioTrackFingerprint)
    return TvTrackSelectionPersistence(
        contentId = targetContentId,
        fileId = version.fileId,
        audioFingerprint = audioFingerprint,
        subtitleFingerprint = subtitleFingerprint,
    )
}

internal suspend fun recordTrackSelection(
    port: UserItemStatePort,
    selection: TvTrackSelectionPersistence,
) {
    port.recordSubtitleTrackSelection(
        selection.contentId,
        selection.fileId,
        selection.subtitleFingerprint,
    )
    port.recordAudioTrackSelection(
        selection.contentId,
        selection.fileId,
        selection.audioFingerprint,
    )
}

internal data class TvRestoredTrackSelection(
    val audioIndex: Int?,
    val subtitleIndex: Int?,
)

internal fun restoreTrackSelection(
    version: FileVersion,
    saved: LocalTrackSelection,
): TvRestoredTrackSelection {
    val tracks = version.subtitleTracks.orEmpty()
    val subtitleIndex = resolveSubtitleTrackOrdinal(tracks, saved.subtitleFingerprint)
        ?.let { catalogOrdinal ->
            if (catalogOrdinal == -1) -1
            else combinedSubtitleSelectionIndexes(tracks).getOrNull(catalogOrdinal)
        }
    return TvRestoredTrackSelection(
        audioIndex = resolveAudioTrackOrdinal(version.audioTracks.orEmpty(), saved.audioFingerprint),
        subtitleIndex = subtitleIndex,
    )
}

internal fun mergeTrackSelection(
    currentAudioIndex: Int?,
    currentSubtitleIndex: Int?,
    durable: TvRestoredTrackSelection,
): TvRestoredTrackSelection = TvRestoredTrackSelection(
    audioIndex = currentAudioIndex ?: durable.audioIndex,
    subtitleIndex = currentSubtitleIndex ?: durable.subtitleIndex,
)

internal fun shouldApplyNextUpTrackRestore(
    currentContentId: String?,
    requestedContentId: String,
    currentSelectedFileId: Int?,
    requestedSelectedFileId: Int?,
): Boolean = currentContentId == requestedContentId &&
    currentSelectedFileId == requestedSelectedFileId

/**
 * How many `GET /favorites/{id}` probes may be in flight at once.
 *
 * A season is commonly 10-25 episodes and every one needs its own probe, so
 * unbounded parallelism put a whole season on the wire in one burst — measured
 * at 150-520 ms each, with the slowest arriving well after the rail had been
 * drawn. A small window keeps the first rows filling promptly without the
 * burst.
 */
internal const val EPISODE_FAVORITE_PROBE_CONCURRENCY = 6

/**
 * Whether a revalidation pass answered everything it was asked to.
 *
 * Only ids this season actually shows count: the signal is process-wide, so it
 * routinely names episodes from a season that is not on screen, and waiting for
 * those would mean never catching up.
 *
 * [requested] of null means the signal could not produce a delta, so every
 * visible episode had to answer. A caller that records itself caught up while
 * one of its probes failed leaves that row stale with nothing left to retry it,
 * which is why this is separate from "the reload succeeded".
 */
/**
 * Cached favourite answers that must be forgotten because they changed
 * elsewhere and are not on screen to be re-probed.
 *
 * The cache spans seasons but a refresh only probes the visible one, so a
 * changed episode belonging to another season would otherwise keep its stale
 * answer for the life of this screen — the visible season's refresh would
 * record the change as handled and move on. Dropping the entry instead means
 * the season that does show it probes it when it next loads.
 *
 * Only OFF-SCREEN entries are dropped. A visible one is revalidated in place,
 * because removing it would render that row as "not a favourite" until its
 * probe answered.
 *
 * [requested] of null means the change list could not be produced, so every
 * cached answer that is not on screen is suspect.
 */
internal fun staleOffScreenFavorites(
    requested: Set<String>?,
    cachedIds: Set<String>,
    visibleIds: Set<String>,
): Set<String> = when (requested) {
    null -> cachedIds - visibleIds
    else -> requested.intersect(cachedIds) - visibleIds
}

internal fun revalidationSatisfied(
    requested: Set<String>?,
    visibleIds: List<String>,
    answered: Set<String>,
): Boolean {
    val visible = visibleIds.toSet()
    val required = requested?.intersect(visible) ?: visible
    return required.all { it in answered }
}

/**
 * Resolves the favourite flag for the episodes whose state is not already
 * known, at most [concurrency] probes in flight at once.
 *
 * [onResolved] fires for each episode the moment its own probe answers, so the
 * rail fills as results arrive. Waiting for the whole set would mean one slow
 * probe holding back every answer that already landed — and bounding the
 * requests makes that wait longer, not shorter, because the work is now spread
 * over several waves instead of one burst.
 *
 * Reports only episodes that answered successfully: a failed probe is left out
 * entirely rather than reported as `false`, so a transient error does not stick
 * as a cached "not a favourite" for the rest of the visit. The returned list is
 * every pair that resolved, for callers that want the whole outcome.
 */
internal suspend fun probeEpisodeFavorites(
    episodeIds: List<String>,
    knownIds: Set<String>,
    concurrency: Int = EPISODE_FAVORITE_PROBE_CONCURRENCY,
    onResolved: (String, Boolean) -> Unit = { _, _ -> },
    probe: suspend (String) -> ApiResult<Boolean>,
): List<Pair<String, Boolean>> {
    val unknown = episodeIds.filterNot { it in knownIds }
    if (unknown.isEmpty()) return emptyList()
    val gate = Semaphore(concurrency)
    return coroutineScope {
        unknown.map { id ->
            async {
                val favorite = gate.withPermit { probe(id) }
                (favorite as? ApiResult.Success)?.let { success ->
                    onResolved(id, success.data)
                    id to success.data
                }
            }
        }.awaitAll()
    }.filterNotNull()
}

/**
 * Drives the enhanced TV item detail screen. Loads the full [ItemDetail] plus
 * the current user's favorite/watchlist state in parallel. For series, pulls
 * seasons once the main detail lands and lazily loads episodes whenever the
 * user switches seasons.
 *
 * Receives `contentId` via Koin `parametersOf()` (see
 * [org.siloserver.silo.tv.di.androidTvModule]).
 */
class TvItemDetailViewModel(
    private val catalogRepository: CatalogRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val profileRepository: ProfileRepository,
    private val profileSettings: ProfileSettingsController,
    metadataAiRepository: org.siloserver.silo.repository.MetadataAiRepository,
    private val contentId: String,
    private val userItemState: UserItemStatePort = NoOpUserItemStatePort,
    private val recommendationRepository: org.siloserver.silo.repository.RecommendationRepository? = null,
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier,
    private val capabilityDetector: PlaybackCapabilityDetector? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvItemDetailUiState())
    val uiState: StateFlow<TvItemDetailUiState> = _uiState.asStateFlow()

    private val descriptionTranslation =
        org.siloserver.silo.metadata.DescriptionTranslationController(
            repository = metadataAiRepository,
            delayMs = { kotlinx.coroutines.delay(it) },
        )
    val translationPhase: StateFlow<org.siloserver.silo.metadata.DescriptionTranslationPhase> =
        descriptionTranslation.phase

    init {
        viewModelScope.launch {
            identityTransitions.transitions.collect { transition ->
                if (transition.phase == IdentityTransitionPhase.WILL_CHANGE) {
                    pendingNextUpSelectionHandoff = null
                }
            }
        }
        observePreferredQuality()
        capabilityDetector?.let(::observeAutomaticAudioPolicy)
        if (contentId.isNotBlank()) {
            // Restore this title's pre-play track choices (QA 2026-07-08: a
            // manual subtitle selection reset on every return to the page —
            // season switches and detail re-entry build a fresh ViewModel).
            TvDetailTrackSelectionSession.recall(contentId)?.let { saved ->
                _uiState.update {
                    it.copy(
                        selectedFileId = saved.fileId,
                    )
                }
            }
            loadAll()
        }
    }

    /**
     * Loads the cascaded subtitle preferences that annotate the selector row's
     * Auto preview.
     *
     * These resolve canonically, through the same [ProfileSettingsController]
     * the settings screen writes with. The `user_profiles` columns
     * `GET /profiles` serves are NOT equivalent: the settings screen writes
     * `playback.subtitle_language` / `subtitle_mode` / `show_forced_subtitles`
     * at `scope=profile` and the server does not mirror a canonical write back
     * into those columns, so reading them here previewed the preference the
     * user had *before* their last edit while
     * [org.siloserver.silo.tv.ui.screens.player.TvVideoPlaybackStarter] — which
     * reads WatchDetail's server-resolved `effective_*` fields — played the new
     * one. The columns stay as the fallback for a server that cannot resolve
     * canonically. showForced defaults to true when unset, matching the player
     * state and the starter's `?: true`.
     */
    private fun loadSubtitlePreferences() {
        viewModelScope.launch {
            val resolved = runCatching { profileSettings.load() }.getOrNull()?.snapshot
            if (resolved != null) {
                _uiState.update {
                    it.copy(
                        // The snapshot spells "no preference" as "", the Auto
                        // preview spells it as null (it reads "" as "no subs",
                        // matching the profile column, which the server omits
                        // when empty). Translate rather than leak the wrong one.
                        preferredSubtitleLanguage = resolved.subtitleLanguage.ifBlank { null },
                        subtitleMode = resolved.subtitleMode,
                        showForcedSubtitles = resolved.showForcedSubtitles,
                    )
                }
                return@launch
            }
            val profile = runCatching { profileRepository.getActiveProfile() }.getOrNull()
            _uiState.update {
                it.copy(
                    preferredSubtitleLanguage = profile?.subtitleLanguage,
                    subtitleMode = profile?.subtitleMode,
                    showForcedSubtitles = profile?.showForcedSubtitles ?: true,
                )
            }
        }
    }

    private fun observePreferredQuality() {
        viewModelScope.launch {
            playerSettingsStore.preferredQualityFlow.collect { quality ->
                _uiState.update {
                    it.copy(preferredQuality = quality.trim().ifBlank { "auto" })
                }
            }
        }
    }

    private fun observeAutomaticAudioPolicy(detector: PlaybackCapabilityDetector) {
        viewModelScope.launch {
            combine(playerSettingsStore.audioLanguageFlow, detector.outputRouteGeneration) { language, _ -> language }
                .collectLatest { settingsLanguage ->
                    val profileLanguage = runCatching {
                        profileRepository.getActiveProfile()?.language
                    }.getOrNull()
                    val preferredLanguage = TrackSelectionPresets.effectivePreferredAudioLanguage(
                        settingsLanguage = settingsLanguage,
                        profileLanguage = profileLanguage,
                    )
                    val capabilities = runCatching {
                        withContext(Dispatchers.Default) {
                            detector.detect(
                                dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot(),
                            )
                        }
                    }.getOrNull()
                    _uiState.update {
                        it.copy(
                            preferredAudioLanguage = preferredLanguage,
                            audioSelectionCapabilities = capabilities,
                        )
                    }
                }
        }
    }

    fun openPerson(member: CastMember, onOpenPerson: (Long) -> Unit) {
        member.personId?.trim()?.toLongOrNull()?.let(onOpenPerson) ?: viewModelScope.launch {
            when (val result = catalogRepository.searchPeople(member.name)) {
                is ApiResult.Success -> {
                    val resolved = result.data.firstOrNull { it.name.equals(member.name, ignoreCase = true) }
                        ?: result.data.firstOrNull()
                    resolved?.id?.takeIf { it > 0L }?.let(onOpenPerson)
                }
                is ApiResult.Error,
                is ApiResult.NetworkError -> Unit
            }
        }
    }

    /**
     * Resolves the parent before replacing a standalone season/episode route.
     * A failed or malformed parent leaves the current detail on screen instead
     * of turning incomplete hierarchy metadata into a dead-end Series page.
     */
    suspend fun hasSeriesDetailForRedirect(seriesContentId: String): Boolean {
        val cached = catalogRepository.getCachedItemDetail(seriesContentId)
        if (cached.isMatchingSeriesDetail(seriesContentId)) return true

        return when (val resolved = catalogRepository.getItemDetail(seriesContentId)) {
            is ApiResult.Success -> resolved.data.isMatchingSeriesDetail(seriesContentId)
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> false
        }
    }

    fun loadAll() {
        viewModelScope.launch {
            runCatching { playerSettingsStore.refreshFromServer() }
        }
        loadSubtitlePreferences()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            seedCachedDetail()
            // Kick off user-state fetches in parallel — they aren't load-blocking;
            // the detail must succeed before we render, but favorite/watchlist
            // state can trickle in afterward.
            loadUserState()
            loadDetail()
        }
    }

    private suspend fun seedCachedDetail() {
        val cached = catalogRepository.getCachedItemDetail(contentId)?.let { withLocalProgress(it) } ?: return
        if (isTvHiddenMediaType(cached.type)) return
        _uiState.update {
            it.copy(
                isLoading = true,
                detail = cached,
                userRating = cached.userRating,
                isWatched = cached.userData?.played == true,
                error = null,
            )
        }
        seedCachedSeriesNavigation(cached)
    }

    /**
     * Joins the focused-card prefetch to the first Series frame. Continue
     * Watching warms Series + season + episode data before navigation; reading
     * those durable rows here lets the existing detail design appear without
     * waiting for the freshness requests that still follow in [loadDetail].
     */
    private suspend fun seedCachedSeriesNavigation(detail: ItemDetail) {
        val seriesId = when (detail.type.lowercase()) {
            "series" -> detail.contentId
            "season", "episode" -> detail.seriesId?.takeIf { it.isNotBlank() }
            else -> null
        } ?: return
        val cachedSeasons = catalogRepository.getCachedSeasons(seriesId) ?: return
        val plan = cachedSeasons.seasons.initialSeasonDisplayPlan(detail.seasonNumber)
        if (_uiState.value.detail?.contentId != detail.contentId) return
        _uiState.update {
            if (it.detail?.contentId != detail.contentId) {
                it
            } else {
                it.copy(
                    seasons = plan.seasons,
                    selectedSeason = plan.selectedSeasonNumber,
                    seasonsLoading = false,
                )
            }
        }
        plan.episodeRequestSeasonNumber?.let { seasonNumber ->
            seedCachedEpisodes(seriesId, seasonNumber)
        }
    }

    /** Publishes a cached season immediately; the caller still refreshes it. */
    private suspend fun seedCachedEpisodes(seriesContentId: String, seasonNumber: Int): Boolean {
        val cached = catalogRepository.getCachedEpisodes(seriesContentId, seasonNumber) ?: return false
        if (_uiState.value.selectedSeason != seasonNumber) return false
        val episodes = withLocalProgress(cached.episodes.sortedBy { it.episodeNumber })
        if (_uiState.value.selectedSeason != seasonNumber) return false
        loadedSeason = seasonNumber
        episodeListGeneration += 1
        _uiState.update {
            if (it.selectedSeason == seasonNumber) {
                it.copy(episodesLoading = false, episodes = episodes)
            } else {
                it
            }
        }
        if (_uiState.value.selectedSeason != seasonNumber) return false
        refreshNextUp(episodes)
        return true
    }

    private fun loadDetail() {
        viewModelScope.launch {
            when (val result = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val detail = withLocalProgress(result.data)
                    if (isTvHiddenMediaType(detail.type)) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                detail = null,
                                error = "This title is not available on Android TV.",
                            )
                        }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = detail,
                            userRating = detail.userRating,
                            isWatched = detail.userData?.played == true,
                            error = null,
                        )
                    }
                    seedSessionTrackSelection(detail)
                    // Restore a durably-persisted audio/subtitle override (TM4).
                    seedPersistedTrackSelection(detail)
                    when (detail.type.lowercase()) {
                        "series" -> loadSeasons(
                            seriesContentId = detail.contentId,
                            // Cached navigation may already have applied an
                            // entry-route season before this fresh detail
                            // response arrives. Carry it into the refresh so
                            // the default/in-progress season cannot replace it.
                            preferredSeasonNumber = _uiState.value.selectedSeason,
                        )
                        "season",
                        "episode",
                        -> detail.seriesId?.takeIf { it.isNotBlank() }?.let { seriesId ->
                            loadSeasons(
                                seriesContentId = seriesId,
                                preferredSeasonNumber = detail.seasonNumber,
                            )
                        }
                    }
                    loadMoreLikeThis(detail)
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.message.ifBlank { "Failed to load details" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoading = false, error = "Network error. Check your connection.")
                }
            }
        }
    }

    private fun loadUserState() {
        viewModelScope.launch {
            val fav = personalDataRepository.isFavorite(contentId)
            if (fav is ApiResult.Success) {
                _uiState.update { it.copy(isFavorite = fav.data) }
            }
        }
        viewModelScope.launch {
            val watch = personalDataRepository.isInWatchlist(contentId)
            if (watch is ApiResult.Success) {
                _uiState.update { it.copy(inWatchlist = watch.data) }
            }
        }
    }

    /**
     * Quiet refresh for returning to an already-loaded detail screen (e.g.
     * backing out of playback): re-reads userData so the Play button's resume
     * label, the episode rail, and next-up reflect the session that just
     * ended. Deliberately NOT [loadAll] — no loading flashes, and the user's
     * season selection is preserved.
     */
    fun refreshOnReturn() {
        val current = _uiState.value.detail ?: return
        val playbackReturn = TvDetailTrackSelectionSession.consumePlaybackReturn(contentId)
        playbackReturn?.let { saved ->
            _uiState.update {
                val returnedDetail = it.detail?.withPlaybackReturn(saved)
                val resolvedFileId = returnedDetail?.let { detail ->
                    resolveTvTrackSelectionVersion(detail, saved.fileId, it.preferredQuality)?.fileId
                }
                val tracksStillMatch = saved.trackFileId == null || saved.trackFileId == resolvedFileId
                it.copy(
                    detail = returnedDetail,
                    selectedFileId = saved.fileId,
                    selectedAudioIndex = saved.audio.takeIf { tracksStillMatch },
                    selectedSubtitleIndex = saved.subtitle.takeIf { tracksStillMatch },
                )
            }
        }
        viewModelScope.launch {
            // Local overlay first: the player's final position write is already
            // on disk, so the label corrects before the server round-trip.
            val overlaid = withLocalProgress(current)
                .let { refreshed -> playbackReturn?.let(refreshed::withPlaybackReturn) ?: refreshed }
            if (overlaid != current) {
                _uiState.update { it.copy(detail = overlaid) }
            }
            when (val result = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val detail = withLocalProgress(result.data)
                        .let { refreshed -> playbackReturn?.let(refreshed::withPlaybackReturn) ?: refreshed }
                    if (!isTvHiddenMediaType(detail.type)) {
                        _uiState.update {
                            it.copy(
                                detail = detail,
                                userRating = detail.userRating,
                                isWatched = detail.userData?.played == true,
                            )
                        }
                    }
                }
                // Quiet refresh: on failure keep showing what we have.
                is ApiResult.Error,
                is ApiResult.NetworkError -> Unit
            }
        }
        val seriesId = when (current.type.lowercase()) {
            "series" -> current.contentId
            "season", "episode" -> current.seriesId?.takeIf { it.isNotBlank() }
            else -> null
        }
        val season = _uiState.value.selectedSeason
        // Coming back from an episode's own screen: the favourite may have been
        // toggled in there, and this view model still holds the old answer. Only
        // the items actually changed are re-asked about.
        val favoritesVersion = TvFavoriteRevalidationSession.currentVersion()
        // Null: too far behind to be given a delta, so re-check the lot.
        val favoritesToRecheck =
            TvFavoriteRevalidationSession.changedSince(favoritesRevalidatedThrough)
        if (seriesId != null && season != null) {
            loadEpisodes(
                seriesId,
                season,
                quiet = true,
                revalidateFavorites = favoritesToRecheck,
                favoritesVersion = favoritesVersion,
            )
        }
    }

    fun onToggleFavorite() {
        val current = _uiState.value
        if (current.isTogglingFavorite) return
        val target = !current.isFavorite
        _uiState.update { it.copy(isTogglingFavorite = true, isFavorite = target) }
        viewModelScope.launch {
            val result = personalDataRepository.toggleFavorite(contentId, target)
            if (result !is ApiResult.Success) {
                // Roll back on error.
                _uiState.update {
                    it.copy(isTogglingFavorite = false, isFavorite = !target)
                }
            } else {
                _uiState.update { it.copy(isTogglingFavorite = false) }
                // A series rail one screen up may be holding a stale answer for
                // this item. Tell it exactly which one changed rather than
                // making it re-ask about the whole season.
                TvFavoriteRevalidationSession.markChanged(contentId)
            }
        }
    }

    fun onToggleWatchlist() {
        val current = _uiState.value
        if (current.isTogglingWatchlist) return
        val target = !current.inWatchlist
        _uiState.update { it.copy(isTogglingWatchlist = true, inWatchlist = target) }
        viewModelScope.launch {
            val result = personalDataRepository.toggleWatchlist(contentId, target)
            if (result !is ApiResult.Success) {
                _uiState.update {
                    it.copy(isTogglingWatchlist = false, inWatchlist = !target)
                }
            } else {
                _uiState.update { it.copy(isTogglingWatchlist = false) }
            }
        }
    }

    fun onToggleWatched() {
        val current = _uiState.value
        if (current.isTogglingWatched) return
        val target = !current.isWatched
        val previousDetail = current.detail
        _uiState.update {
            it.copy(
                isTogglingWatched = true,
                isWatched = target,
                detail = it.detail?.withWatchedPlaybackState(target),
            )
        }
        viewModelScope.launch {
            val result = personalDataRepository.setWatched(contentId, target)
            if (result !is ApiResult.Success) {
                // Roll back on error.
                _uiState.update {
                    it.copy(
                        isTogglingWatched = false,
                        isWatched = !target,
                        detail = previousDetail,
                    )
                }
            } else {
                _uiState.update { it.copy(isTogglingWatched = false) }
                // Re-read server-resolved state (including series/season episode
                // resolution) without flashing the full detail loading screen.
                refreshOnReturn()
            }
        }
    }

    fun onSetRating(stars: Int) {
        val current = _uiState.value
        if (current.isTogglingRating) return
        val target = stars.coerceIn(1, 5)
        val previous = current.userRating
        _uiState.update { it.copy(isTogglingRating = true, userRating = target) }
        viewModelScope.launch {
            val result = personalDataRepository.setRating(contentId, target)
            if (result !is ApiResult.Success) {
                // Roll back on error.
                _uiState.update {
                    it.copy(isTogglingRating = false, userRating = previous)
                }
            } else {
                _uiState.update { it.copy(isTogglingRating = false) }
            }
        }
    }

    fun onClearRating() {
        val current = _uiState.value
        if (current.isTogglingRating) return
        val previous = current.userRating ?: return
        _uiState.update { it.copy(isTogglingRating = true, userRating = null) }
        viewModelScope.launch {
            val result = personalDataRepository.deleteRating(contentId)
            if (result !is ApiResult.Success) {
                // Roll back on error.
                _uiState.update {
                    it.copy(isTogglingRating = false, userRating = previous)
                }
            } else {
                _uiState.update { it.copy(isTogglingRating = false) }
            }
        }
    }

    fun onVersionSelected(fileId: Int?) {
        // Track indexes are file-specific; clear them so a stale index can't
        // carry over to a different version's track list.
        _uiState.update {
            it.copy(
                selectedFileId = fileId,
                selectedAudioIndex = null,
                audioPickedThisSession = false,
                selectedSubtitleIndex = null,
            )
        }
        val state = _uiState.value
        TvDetailTrackSelectionSession.remember(
            contentId,
            fileId,
            audio = null,
            subtitle = null,
            trackFileId = state.detail?.let { selectedVersionFor(state, it)?.fileId },
        )
        // Do NOT persist here: a version switch resets the indexes to null, and
        // persisting null clears the durable row — which would wipe the newly
        // selected file's saved override before seedPersistedTrackSelection can
        // restore it. Only explicit audio/subtitle picks persist.
        _uiState.value.detail?.let(::seedPersistedTrackSelection)
    }

    /** Pre-select an audio track for the next Play (index into the version's audioTracks). */
    fun onAudioTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedAudioIndex = index, audioPickedThisSession = index != null) }
        val state = _uiState.value
        TvDetailTrackSelectionSession.remember(
            contentId,
            state.selectedFileId,
            index,
            state.selectedSubtitleIndex,
            trackFileId = state.detail?.let { selectedVersionFor(state, it)?.fileId },
        )
        persistTrackSelection()
    }

    /** Pre-select a subtitle track for the next Play (-1 = Off, null = auto). */
    fun onSubtitleTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
        val state = _uiState.value
        TvDetailTrackSelectionSession.remember(
            contentId,
            state.selectedFileId,
            state.selectedAudioIndex,
            index,
            trackFileId = state.detail?.let { selectedVersionFor(state, it)?.fileId },
        )
        persistTrackSelection()
    }

    /** The exact version the Auto/display/player policy currently resolves. */
    private fun selectedVersionFor(state: TvItemDetailUiState, detail: ItemDetail): FileVersion? {
        return resolveTvTrackSelectionVersion(detail, state.selectedFileId, state.preferredQuality)
    }

    /**
     * Durably persist the current audio/subtitle override for the selected
     * version's file, so it survives leaving/re-opening the page AND the process
     * (the in-memory [TvDetailTrackSelectionSession] only covers this process).
     * Mirrors the phone I7 fix and tvOS TrackSelectionPersistence: record the
     * catalog track's fingerprint against the shared UserItemStatePort keyed on
     * (contentId, fileId); Auto (null) clears, explicit Off writes the off
     * sentinel.
     */
    private fun persistTrackSelection() {
        val state = _uiState.value
        val detail = state.detail ?: return
        persistTrackSelectionFor(
            targetContentId = contentId,
            detail = detail,
            selectedFileId = state.selectedFileId,
            selectedAudioIndex = state.selectedAudioIndex,
            selectedSubtitleIndex = state.selectedSubtitleIndex,
            preferredQuality = state.preferredQuality,
        )
    }

    private fun persistTrackSelectionFor(
        targetContentId: String,
        detail: ItemDetail,
        selectedFileId: Int?,
        selectedAudioIndex: Int?,
        selectedSubtitleIndex: Int?,
        preferredQuality: String?,
    ) {
        val version = resolveTvTrackSelectionVersion(detail, selectedFileId, preferredQuality) ?: return
        val selection = buildTrackSelectionPersistence(
            targetContentId = targetContentId,
            version = version,
            selectedAudioIndex = selectedAudioIndex,
            selectedSubtitleIndex = selectedSubtitleIndex,
        )
        viewModelScope.launch {
            recordTrackSelection(userItemState, selection)
        }
    }

    /**
     * Seed the audio/subtitle selection from a previously persisted override for
     * the selected version's file. Skips when a selection is already set (the
     * in-memory session recall or the current pick wins), and only applies a
     * dimension when a saved fingerprint matches a current track.
     */
    private fun seedPersistedTrackSelection(detail: ItemDetail) {
        val state = _uiState.value
        if (state.selectedSubtitleIndex != null || state.selectedAudioIndex != null) return
        val version = selectedVersionFor(state, detail) ?: return
        viewModelScope.launch {
            val saved = userItemState.localTrackSelection(contentId, version.fileId) ?: return@launch
            val restored = restoreTrackSelection(version, saved)
            val subOrdinal = restored.subtitleIndex
            val audOrdinal = restored.audioIndex
            if (subOrdinal == null && audOrdinal == null) return@launch
            _uiState.update {
                // The ordinals were resolved against `version`; if the user
                // switched versions while this suspended, they'd index into the
                // wrong track list — leave the new version untouched.
                if (selectedVersionFor(it, detail)?.fileId != version.fileId) return@update it
                it.copy(
                    selectedSubtitleIndex = it.selectedSubtitleIndex ?: subOrdinal,
                    selectedAudioIndex = it.selectedAudioIndex ?: audOrdinal,
                )
            }
        }
    }

    private fun seedSessionTrackSelection(detail: ItemDetail) {
        val saved = TvDetailTrackSelectionSession.recall(contentId) ?: return
        val state = _uiState.value
        val version = resolveTvTrackSelectionVersion(detail, saved.fileId, state.preferredQuality)
        val tracksBelongToVersion = saved.trackFileId == null || saved.trackFileId == version?.fileId
        _uiState.update {
            it.copy(
                selectedFileId = saved.fileId?.takeIf { id -> detail.versions.any { version -> version.fileId == id } },
                selectedAudioIndex = saved.audio.takeIf { tracksBelongToVersion },
                selectedSubtitleIndex = saved.subtitle.takeIf { tracksBelongToVersion },
            )
        }
    }

    fun onSeasonSelected(seasonNumber: Int) {
        if (_uiState.value.selectedSeason == seasonNumber) return
        seasonSelectionGeneration += 1
        // A focused episode belongs to the old season. The newly loaded rail
        // chooses its own suggested/current episode, exactly like tvOS.
        activeSeriesEpisodeContentId = null
        _uiState.update {
            it.copy(
                selectedSeason = seasonNumber,
                episodesLoading = true,
                // Keep the previous episode as a layout placeholder, but never
                // let Play launch it for the newly selected season.
                nextUpTargetReady = false,
            )
        }
        val detail = _uiState.value.detail ?: return
        val seriesContentId = when (detail.type.lowercase()) {
            "series" -> detail.contentId
            "season" -> detail.seriesId
            "episode" -> detail.seriesId
            else -> null
        } ?: return
        loadEpisodes(seriesContentId, seasonNumber)
    }

    /**
     * Series is one in-place browsing page: focus, not a pushed episode-detail
     * route, owns the active episode. `null` restores Show mode's suggested
     * episode while a concrete id updates the hero and playback selector.
     */
    fun onSeriesEpisodeActivated(contentId: String?) {
        val state = _uiState.value
        if (state.detail?.type?.lowercase() != "series") return
        val active = contentId?.let { id -> state.episodes.firstOrNull { it.contentId == id } }
        activeSeriesEpisodeContentId = active?.contentId
        updateNextUp(active ?: resolveNextUpEpisode(state.episodes))
    }

    /**
     * Applies an episode carried by a Continue Watching detail route exactly
     * once. Keeping the latch in the ViewModel survives player round-trips but
     * resets correctly if Android recreates the detail entry after process loss.
     */
    fun onEntrySeriesEpisodeRequested(contentId: String) {
        val state = _uiState.value
        if (state.entryEpisodeSelectionApplied || state.detail?.type?.lowercase() != "series") return
        val active = state.episodes.firstOrNull { it.contentId == contentId }
        _uiState.update { it.copy(entryEpisodeSelectionApplied = true) }
        if (active != null) {
            activeSeriesEpisodeContentId = active.contentId
            updateNextUp(active)
        }
    }

    private fun loadSeasons(
        seriesContentId: String,
        preferredSeasonNumber: Int? = null,
    ) {
        val selectionGenerationAtRequest = seasonSelectionGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(seasonsLoading = true) }
            when (val r = catalogRepository.getSeasons(seriesContentId)) {
                is ApiResult.Success -> {
                    val plan = r.data.seasons.initialSeasonDisplayPlan(preferredSeasonNumber)
                    val currentSelection = _uiState.value.selectedSeason
                    val preservesNewerSelection =
                        seasonSelectionGeneration != selectionGenerationAtRequest &&
                            currentSelection != null &&
                            plan.seasons.any { it.seasonNumber == currentSelection }
                    val selectedSeasonNumber = if (preservesNewerSelection) {
                        currentSelection
                    } else {
                        plan.selectedSeasonNumber
                    }
                    _uiState.update {
                        it.copy(
                            seasonsLoading = false,
                            seasons = plan.seasons,
                            selectedSeason = selectedSeasonNumber,
                        )
                    }
                    if (!preservesNewerSelection) {
                        selectedSeasonNumber?.let { seasonNumber ->
                            loadEpisodes(seriesContentId, seasonNumber)
                        }
                    }
                }
                else -> _uiState.update { it.copy(seasonsLoading = false) }
            }
        }
    }

    private var episodeLoadJob: kotlinx.coroutines.Job? = null
    private var episodeLoadRequestGeneration: Long = 0

    /**
     * How far through [TvFavoriteRevalidationSession] this screen has caught up.
     * Starts at the current version: anything toggled before this screen existed
     * is already reflected in the data it is about to load.
     */
    private var favoritesRevalidatedThrough: Long = TvFavoriteRevalidationSession.currentVersion()
    private var moreLikeThisJob: Job? = null
    private var nextUpDetailJob: Job? = null
    private var activeSeriesEpisodeContentId: String? = null
    // A seasons refresh may complete after the viewer has already changed the
    // selected chip. Preserve that newer choice instead of replaying the
    // refresh request's initial display plan.
    private var seasonSelectionGeneration: Long = 0
    // The season number the currently-shown episodes/next-up actually belong to.
    // Lets a failed load revert the optimistic season selection so the chips and
    // the rail stay consistent (T15).
    private var loadedSeason: Int? = null
    private var episodeListGeneration: Long = 0
    private var nextEpisodeWatchMutationGeneration: Long = 0
    private val episodeWatchMutationGenerations = mutableMapOf<String, Long>()
    private var nextEpisodeFavoriteMutationGeneration: Long = 0
    private val episodeFavoriteMutationGenerations = mutableMapOf<String, Long>()
    private var nextUpPlaybackDetailGeneration: Long = 0
    private var nextUpSelectorRevision: Long = 0
    private var pendingNextUpSelectionHandoff: PendingNextUpSelectionHandoff? = null

    private data class NextUpIdentity(
        val serverId: String?,
        val profileId: String?,
        val generation: Long,
    )

    private data class PendingNextUpSelectionHandoff(
        val targetContentId: String,
        val refreshGeneration: Long,
        val selectorRevision: Long,
        val identity: NextUpIdentity,
        val handoff: EpisodeSelectionHandoff,
    )

    private data class ResolvedNextUpTrackSelection(
        val fileId: Int?,
        val audioIndex: Int?,
        val subtitleIndex: Int?,
    )

    /**
     * Loads a season's episodes. [quiet] suppresses the loading spinner and is
     * used by [refreshOnReturn], whose contract is a no-flash background refresh
     * of the season already on screen.
     */
    private fun loadEpisodes(
        seriesContentId: String,
        seasonNumber: Int,
        quiet: Boolean = false,
        revalidateFavorites: Set<String>? = emptySet(),
        favoritesVersion: Long? = null,
    ) {
        // Cancel any in-flight episode load so a slower response for a
        // previously-selected season can't overwrite episodes/next-up for the
        // season the user is now on (rapid season switches / the initial
        // selected-season load racing a route-driven season load). Cancellation
        // alone is insufficient because the network wrapper converts a caught
        // CancellationException into an error result; the generation remains
        // the authoritative owner of the network result publications below.
        episodeLoadRequestGeneration += 1
        val requestGeneration = episodeLoadRequestGeneration
        episodeLoadJob?.cancel()
        episodeLoadJob = viewModelScope.launch {
            fun ownsRequest(): Boolean =
                requestGeneration == episodeLoadRequestGeneration &&
                    _uiState.value.selectedSeason == seasonNumber

            if (!ownsRequest()) return@launch
            if (!quiet) _uiState.update { it.copy(episodesLoading = true) }
            seedCachedEpisodes(seriesContentId, seasonNumber)
            if (!ownsRequest()) return@launch
            val result = catalogRepository.getEpisodes(seriesContentId, seasonNumber)
            if (!ownsRequest()) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val episodes = withLocalProgress(
                        result.data.episodes.sortedBy { episode -> episode.episodeNumber },
                    )
                    if (!ownsRequest()) return@launch
                    loadedSeason = seasonNumber
                    episodeListGeneration += 1
                    _uiState.update { it.copy(episodesLoading = false, episodes = episodes) }
                    refreshNextUp(episodes)
                    val revalidationComplete =
                        refreshEpisodeFavoriteStates(episodes, revalidate = revalidateFavorites)
                    // Caught up only now, and only if every id we were asked to
                    // re-check actually answered. Advancing on read would drop
                    // the signal when the reload failed; advancing after a
                    // FAILED probe would drop it just as permanently, leaving
                    // that one episode stale with nothing left to retry it.
                    if (revalidationComplete && ownsRequest()) {
                        favoritesVersion?.let { favoritesRevalidatedThrough = it }
                    }
                }
                else -> {
                    // Quiet-failure contract (T15): a failed season load must NOT
                    // wipe the episode rail / next-up already on screen (that
                    // kills the hero Play button and the rail). Keep what's shown
                    // and revert the optimistic season selection to the season the
                    // loaded episodes actually belong to, so the chips and the
                    // rail stay in agreement.
                    _uiState.update {
                        it.copy(
                            episodesLoading = false,
                            selectedSeason = loadedSeason ?: it.selectedSeason,
                        )
                    }
                    refreshNextUp(_uiState.value.episodes)
                }
            }
        }
    }

    /**
     * Fills in the favourite flag for episodes whose state this screen does not
     * already know.
     *
     * There is no favourite field on an episode payload, so each one has to be
     * asked for individually — `GET /favorites/{id}`, answering 404 for "not a
     * favourite". Two things made that expensive enough to see in the field:
     * every episode was asked on every season load even when the answer was
     * already on screen, and all of them were asked at once. One series on a
     * tester's Fire TV produced 116 such 404s at 150-520 ms each.
     *
     * So: ask only about episodes with no answer yet, and ask a few at a time.
     * The map accumulates for the life of this view model, which is one visit
     * to one item — leaving the screen and coming back still re-reads, so a
     * favourite toggled on another device is picked up on the next visit
     * rather than being cached indefinitely.
     */
    /**
     * @param revalidate ids to re-ask about even though an answer is already
     * held, because they were toggled on a screen further down. This view model
     * is retained across that trip, so its answer for them is stale but
     * present. Deliberately a targeted SET rather than a blanket flag:
     * ON_RESUME also fires for returning from playback and for foregrounding
     * the app, and re-probing a whole season on each of those would restore the
     * request volume this window exists to prevent.
     *
     * Existing entries stay until a fresh answer replaces them — clearing first
     * would render every episode as "not a favourite" for the length of a round
     * trip, and permanently so for any probe that fails.
     */
    private suspend fun refreshEpisodeFavoriteStates(
        episodes: List<EpisodeListItem>,
        revalidate: Set<String>? = emptySet(),
    ): Boolean {
        // An empty season leaves the accumulated answers alone: rendering is
        // keyed by the visible episode ids, so nothing stale can show, and
        // clearing would make returning to a populated season re-probe it.
        val episodeIds = episodes.map { it.contentId }
        val visibleIds = episodeIds.toSet()

        // Apply the change list to entries this screen holds but is not showing,
        // before deciding anything else. Recording those as handled without
        // acting on them is how a stale answer survives a season switch.
        val stale = staleOffScreenFavorites(
            requested = revalidate,
            cachedIds = _uiState.value.episodeFavoriteStates.keys,
            visibleIds = visibleIds,
        )
        if (stale.isNotEmpty()) {
            _uiState.update { it.copy(episodeFavoriteStates = it.episodeFavoriteStates - stale) }
        }

        // Now safe: the only changes left to account for are visible ones, and
        // an empty season has none.
        if (episodes.isEmpty()) return true
        val generation = episodeListGeneration
        // A null delta means the signal could not tell us what changed, so
        // nothing is treated as already known.
        val knownIds =
            if (revalidate == null) emptySet() else _uiState.value.episodeFavoriteStates.keys - revalidate
        val resolved = probeEpisodeFavorites(
            episodeIds = episodeIds,
            knownIds = knownIds,
            onResolved = { id, favorite ->
                // Publish per answer rather than per batch. Guarded by the
                // generation the probes were started for, so a season the
                // viewer has already left cannot write into the one on screen.
                if (episodeListGeneration == generation) {
                    _uiState.update {
                        it.copy(episodeFavoriteStates = it.episodeFavoriteStates + (id to favorite))
                    }
                }
            },
        ) { personalDataRepository.isFavorite(it) }

        return revalidationSatisfied(
            requested = revalidate,
            visibleIds = episodeIds,
            answered = resolved.mapTo(mutableSetOf()) { it.first },
        )
    }

    fun onSetEpisodeWatched(episodeContentId: String, watched: Boolean) {
        val current = _uiState.value
        val previousEpisodes = current.episodes
        val previousEpisode = previousEpisodes.firstOrNull { it.contentId == episodeContentId }
        val mutationSeason = current.selectedSeason
        val listGeneration = episodeListGeneration
        val mutationGeneration = ++nextEpisodeWatchMutationGeneration
        episodeWatchMutationGenerations[episodeContentId] = mutationGeneration
        val updatedEpisodes = previousEpisodes.map { episode ->
            if (episode.contentId == episodeContentId) episode.withWatchedPlaybackState(watched) else episode
        }
        _uiState.update { it.copy(episodes = updatedEpisodes) }
        refreshNextUp(updatedEpisodes)

        viewModelScope.launch {
            val result = personalDataRepository.setWatched(episodeContentId, watched)
            val isCurrentMutation = episodeWatchMutationGenerations[episodeContentId] == mutationGeneration
            if (result !is ApiResult.Success) {
                if (
                    isCurrentMutation &&
                    previousEpisode != null &&
                    episodeListGeneration == listGeneration
                ) {
                    val live = _uiState.value
                    if (live.selectedSeason == mutationSeason) {
                        val restored = live.episodes.map { episode ->
                            if (episode.contentId == episodeContentId) previousEpisode else episode
                        }
                        if (_uiState.compareAndSet(live, live.copy(episodes = restored))) {
                            refreshNextUp(restored)
                        }
                    }
                }
                if (isCurrentMutation) episodeWatchMutationGenerations.remove(episodeContentId)
            } else if (isCurrentMutation) {
                episodeWatchMutationGenerations.remove(episodeContentId)
                // Re-read the server-resolved season state without collapsing
                // the rail or flashing its loading placeholder.
                val detail = _uiState.value.detail
                val seriesId = when (detail?.type?.lowercase()) {
                    "series" -> detail.contentId
                    "season", "episode" -> detail.seriesId
                    else -> null
                }
                val season = _uiState.value.selectedSeason
                if (!seriesId.isNullOrBlank() && season != null) {
                    loadEpisodes(seriesId, season, quiet = true)
                }
            }
        }
    }

    fun onSetEpisodeFavorite(episodeContentId: String, favorite: Boolean) {
        val current = _uiState.value
        val previousFavorite = current.episodeFavoriteStates[episodeContentId] ?: false
        val isCurrentDetail = episodeContentId == current.detail?.contentId
        val mutationGeneration = ++nextEpisodeFavoriteMutationGeneration
        episodeFavoriteMutationGenerations[episodeContentId] = mutationGeneration
        _uiState.update {
            it.copy(
                episodeFavoriteStates = it.episodeFavoriteStates + (episodeContentId to favorite),
                isFavorite = if (isCurrentDetail) favorite else it.isFavorite,
            )
        }
        viewModelScope.launch {
            val result = personalDataRepository.toggleFavorite(episodeContentId, favorite)
            val isCurrentMutation = episodeFavoriteMutationGenerations[episodeContentId] == mutationGeneration
            if (result !is ApiResult.Success && isCurrentMutation) {
                _uiState.update {
                    it.copy(
                        episodeFavoriteStates = it.episodeFavoriteStates +
                            (episodeContentId to previousFavorite),
                        isFavorite = if (isCurrentDetail && it.detail?.contentId == episodeContentId) {
                            previousFavorite
                        } else {
                            it.isFavorite
                        },
                    )
                }
            }
            if (isCurrentMutation) episodeFavoriteMutationGenerations.remove(episodeContentId)
        }
    }

    private suspend fun withLocalProgress(detail: ItemDetail): ItemDetail =
        applyLocalPlaybackProgress(detail, userItemState.localPlaybackProgress(detail.contentId))

    private suspend fun withLocalProgress(episodes: List<EpisodeListItem>): List<EpisodeListItem> {
        if (episodes.isEmpty()) return episodes
        val progress = userItemState.localPlaybackProgressForContent(episodes.map { it.contentId })
        if (progress.isEmpty()) return episodes
        return episodes.map { episode -> applyLocalPlaybackProgress(episode, progress[episode.contentId]) }
    }

    /**
     * Resolves the next-up episode for the selected season (series/season detail
     * only) and kicks off its playback-detail load when it changes. Mirrors
     * silo-apple's `nextUpEpisode` + the `.task(id:)`-driven
     * `loadSeriesNextUpPlaybackDetail` / `loadSeasonNextUpPlaybackDetail`.
     */
    private fun refreshNextUp(episodes: List<EpisodeListItem>) {
        val active = activeSeriesEpisodeContentId
            ?.let { contentId -> episodes.firstOrNull { it.contentId == contentId } }
        updateNextUp(active ?: resolveNextUpEpisode(episodes))
    }

    private fun updateNextUp(nextUp: EpisodeListItem?) {
        val oldState = _uiState.value
        val detail = oldState.detail
        val type = detail?.type?.lowercase()
        if (detail == null || (type != "series" && type != "season")) {
            invalidateNextUpPlaybackDetailRequest()
            // Movie / episode detail does not drive next-up; clear any state.
            if (_uiState.value.nextUpEpisode != null || _uiState.value.nextUpPlaybackDetail != null) {
                _uiState.update {
                    it.copy(
                        nextUpEpisode = null,
                        nextUpTargetReady = false,
                        nextUpPlaybackDetail = null,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = false,
                        selectedNextUpFileId = null,
                        selectedNextUpAudioIndex = null,
                        nextUpAudioPickedThisSession = false,
                        selectedNextUpSubtitleIndex = null,
                    )
                }
            }
            return
        }

        val previousId = _uiState.value.nextUpEpisode?.contentId
        if (nextUp?.contentId == previousId && _uiState.value.nextUpEpisode != null) {
            // Same target — just refresh the snapshot (userData may have changed)
            // without re-loading playback detail.
            _uiState.update { it.copy(nextUpEpisode = nextUp, nextUpTargetReady = true) }
            return
        }

        if (nextUp == null) {
            invalidateNextUpPlaybackDetailRequest()
            _uiState.update {
                it.copy(
                    nextUpEpisode = null,
                    nextUpTargetReady = false,
                    nextUpPlaybackDetail = null,
                    isLoadingNextUpPlaybackDetail = false,
                    didLoadNextUpPlaybackDetail = false,
                    selectedNextUpFileId = null,
                    selectedNextUpAudioIndex = null,
                    nextUpAudioPickedThisSession = false,
                    selectedNextUpSubtitleIndex = null,
                )
            }
            return
        }

        // Capture portable intent while the old target's selected version and
        // combined subtitle index are still available. Raw file IDs and indexes
        // never cross the episode boundary.
        val handoff = captureNextUpSelectionHandoff(oldState)
        val refreshGeneration = ++nextUpPlaybackDetailGeneration
        val selectorRevision = nextUpSelectorRevision
        val identityGeneration = identityTransitions.generation.value
        pendingNextUpSelectionHandoff = null
        _uiState.update {
            it.copy(
                nextUpEpisode = nextUp,
                nextUpTargetReady = true,
                nextUpPlaybackDetail = null,
                isLoadingNextUpPlaybackDetail = true,
                didLoadNextUpPlaybackDetail = false,
                selectedNextUpFileId = null,
                selectedNextUpAudioIndex = null,
                nextUpAudioPickedThisSession = false,
                selectedNextUpSubtitleIndex = null,
            )
        }
        loadNextUpPlaybackDetail(
            episodeContentId = nextUp.contentId,
            refreshGeneration = refreshGeneration,
            selectorRevision = selectorRevision,
            identityGeneration = identityGeneration,
            handoff = handoff,
        )
    }

    private fun resolveNextUpEpisode(episodes: List<EpisodeListItem>): EpisodeListItem? {
        episodes.firstOrNull { it.userData?.isInProgress == true }?.let { return it }
        episodes.firstOrNull { it.userData?.played != true }?.let { return it }
        return episodes.firstOrNull()
    }

    private fun loadNextUpPlaybackDetail(
        episodeContentId: String,
        refreshGeneration: Long,
        selectorRevision: Long,
        identityGeneration: Long,
        handoff: EpisodeSelectionHandoff?,
    ) {
        nextUpDetailJob?.cancel()
        nextUpDetailJob = viewModelScope.launch {
            val requestIdentity = captureNextUpIdentity(identityGeneration)
            if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration) || requestIdentity == null) {
                clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                _uiState.update {
                    if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration)) it else it.copy(
                        nextUpPlaybackDetail = null,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = true,
                    )
                }
                return@launch
            }
            if (handoff != null && nextUpSelectorRevision == selectorRevision) {
                pendingNextUpSelectionHandoff = PendingNextUpSelectionHandoff(
                    targetContentId = episodeContentId,
                    refreshGeneration = refreshGeneration,
                    selectorRevision = selectorRevision,
                    identity = requestIdentity,
                    handoff = handoff,
                )
            }
            val result = catalogRepository.getItemDetail(episodeContentId)
            if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration)) {
                clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                return@launch
            }
            val completionIdentity = captureNextUpIdentity(identityGeneration)
            if (completionIdentity != requestIdentity) {
                clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                _uiState.update {
                    if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration)) it else it.copy(
                        nextUpPlaybackDetail = null,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = true,
                    )
                }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> {
                    val playbackDetail = withLocalProgress(result.data)
                    if (
                        !ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration) ||
                        captureNextUpIdentity(identityGeneration) != requestIdentity
                    ) {
                        clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                        return@launch
                    }
                    val pending = pendingNextUpSelectionHandoff?.takeIf {
                        it.targetContentId == episodeContentId &&
                            it.refreshGeneration == refreshGeneration &&
                            it.selectorRevision == nextUpSelectorRevision &&
                            it.identity == requestIdentity
                    }
                    val selectionRevision = nextUpSelectorRevision
                    val selection = resolveNextUpTrackSelection(
                        episodeContentId = episodeContentId,
                        detail = playbackDetail,
                        handoff = pending?.handoff,
                        preferredQuality = _uiState.value.preferredQuality,
                    )
                    if (
                        !ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration) ||
                        captureNextUpIdentity(identityGeneration) != requestIdentity
                    ) {
                        clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                        return@launch
                    }
                    _uiState.update {
                        if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration)) {
                            it
                        } else if (nextUpSelectorRevision != selectionRevision) {
                            // An explicit selector callback ran while a durable
                            // read was suspended. Finish loading the target but
                            // leave that newer explicit choice untouched.
                            it.copy(
                                nextUpPlaybackDetail = playbackDetail,
                                isLoadingNextUpPlaybackDetail = false,
                                didLoadNextUpPlaybackDetail = true,
                            )
                        } else {
                            it.copy(
                                nextUpPlaybackDetail = playbackDetail,
                                isLoadingNextUpPlaybackDetail = false,
                                didLoadNextUpPlaybackDetail = true,
                                selectedNextUpFileId = selection.fileId,
                                selectedNextUpAudioIndex = selection.audioIndex,
                                nextUpAudioPickedThisSession = false,
                                selectedNextUpSubtitleIndex = selection.subtitleIndex,
                            )
                        }
                    }
                    // This transition resolution is display/session input only.
                    // Selector callbacks remain the sole writers to session/Room.
                    clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                }
                else -> {
                    clearPendingNextUpHandoff(episodeContentId, refreshGeneration)
                    _uiState.update {
                        if (!ownsNextUpPlaybackDetailRequest(episodeContentId, refreshGeneration)) it else it.copy(
                            nextUpPlaybackDetail = null,
                            isLoadingNextUpPlaybackDetail = false,
                            didLoadNextUpPlaybackDetail = true,
                        )
                    }
                }
            }
        }
    }

    private fun invalidateNextUpPlaybackDetailRequest() {
        nextUpDetailJob?.cancel()
        nextUpDetailJob = null
        nextUpPlaybackDetailGeneration += 1
        pendingNextUpSelectionHandoff = null
    }

    private fun ownsNextUpPlaybackDetailRequest(episodeContentId: String, refreshGeneration: Long): Boolean =
        nextUpPlaybackDetailGeneration == refreshGeneration &&
            _uiState.value.nextUpEpisode?.contentId == episodeContentId

    private fun clearPendingNextUpHandoff(episodeContentId: String, refreshGeneration: Long) {
        pendingNextUpSelectionHandoff = pendingNextUpSelectionHandoff?.takeUnless {
            it.targetContentId == episodeContentId && it.refreshGeneration == refreshGeneration
        }
    }

    private suspend fun captureNextUpIdentity(expectedGeneration: Long): NextUpIdentity? {
        if (identityTransitions.generation.value != expectedGeneration) return null
        // A snapshot is the only internally-consistent server/profile read.
        // Null means this TokenManager cannot pin the active identity; fail
        // closed instead of composing separately-timed getters.
        val scope = tokenManager.snapshotCurrentScope() ?: return null
        if (identityTransitions.generation.value != expectedGeneration) return null
        return NextUpIdentity(scope.serverId, scope.profileId, expectedGeneration)
    }

    private fun captureNextUpSelectionHandoff(state: TvItemDetailUiState): EpisodeSelectionHandoff? {
        val detail = state.nextUpPlaybackDetail ?: return null
        val selectedVersion = state.selectedNextUpFileId
            ?.let { fileId -> detail.versions.firstOrNull { it.fileId == fileId } }
        val activeVersion = selectTvDetailDisplayVersion(
            versions = detail.versions,
            selectedFileId = state.selectedNextUpFileId,
            lastFileId = detail.userData?.lastFileId,
            preferredQuality = state.preferredQuality,
        )
        val subtitleChoices = buildPlaybackSubtitleChoices(
            catalogTracks = activeVersion?.subtitleTracks.orEmpty(),
            plannedTracks = emptyList(),
        )
        // Source intent is carried only for an explicit version choice; an
        // automatic last-file/quality choice must remain automatic on the
        // target. Subtitle intent uses the displayed version because its
        // combined index belongs to that version's track list.
        val handoff = EpisodeSelectionHandoff(
            source = selectedVersion?.let(::captureEpisodeSourceIntent),
            subtitle = captureEpisodeSubtitleIntent(state.selectedNextUpSubtitleIndex, subtitleChoices),
        )
        return handoff.takeIf {
            it.source != null || it.subtitle.mode != EpisodeSubtitleMode.AUTO
        }
    }

    private suspend fun resolveNextUpTrackSelection(
        episodeContentId: String,
        detail: ItemDetail,
        handoff: EpisodeSelectionHandoff?,
        preferredQuality: String,
    ): ResolvedNextUpTrackSelection {
        val session = TvDetailTrackSelectionSession.recall(episodeContentId)
        val sourceSpecified = handoff?.source != null
        val carriedFileId = resolveEpisodeSourceIntent(handoff?.source, detail.versions)
        val sessionFileId = session?.fileId?.takeIf { fileId -> detail.versions.any { it.fileId == fileId } }
        val selectedFileId = if (sourceSpecified) carriedFileId else sessionFileId
        val selectedVersion = selectTvDetailDisplayVersion(
            versions = detail.versions,
            selectedFileId = selectedFileId,
            lastFileId = detail.userData?.lastFileId,
            preferredQuality = preferredQuality,
        )
            ?: return ResolvedNextUpTrackSelection(selectedFileId, null, null)

        val sessionMatchesSelectedVersion = session != null &&
            (session.trackFileId == null || session.trackFileId == selectedVersion.fileId)
        val targetSubtitleChoices = buildPlaybackSubtitleChoices(
            catalogTracks = selectedVersion.subtitleTracks.orEmpty(),
            plannedTracks = emptyList(),
        )
        val carriedSubtitle = resolveEpisodeSubtitleIntent(
            intent = handoff?.subtitle ?: EpisodeSubtitleIntent.auto(),
            targetSubtitles = targetSubtitleChoices,
        )
        val durable = userItemState.localTrackSelection(episodeContentId, selectedVersion.fileId)
            ?.let { restoreTrackSelection(selectedVersion, it) }

        val sessionAudio = session?.audio.takeIf { sessionMatchesSelectedVersion }
        val sessionSubtitle = session?.subtitle.takeIf { sessionMatchesSelectedVersion }
        return ResolvedNextUpTrackSelection(
            fileId = selectedFileId,
            audioIndex = sessionAudio ?: durable?.audioIndex,
            subtitleIndex = if (carriedSubtitle.intentSpecified) {
                carriedSubtitle.trackIndex
            } else {
                sessionSubtitle ?: durable?.subtitleIndex
            },
        )
    }


    /** Mirror of the phone flow: fire (auto = once per content+language), then
     *  poll detail until `pending_translation_language` clears. */
    fun translateDescription(auto: Boolean = false) {
        val detail = _uiState.value.detail ?: return
        val target = detail.pendingTranslationLanguage ?: return
        if (auto) {
            if (!descriptionTranslation.shouldAutoFire(detail.contentId, target)) return
            descriptionTranslation.markAutoFired(detail.contentId, target)
        }
        descriptionTranslation.resetFailure()
        viewModelScope.launch {
            descriptionTranslation.translate(
                contentId = detail.contentId,
                targetLanguage = target,
                refetchPendingLanguage = {
                    when (val result = catalogRepository.getItemDetail(contentId)) {
                        is ApiResult.Success -> {
                            val refreshed = withLocalProgress(result.data)
                            _uiState.update { it.copy(detail = refreshed) }
                            refreshed.pendingTranslationLanguage
                        }
                        else -> target // transient refetch failure: keep polling
                    }
                },
                onTranslated = { },
            )
        }
    }

    fun onNextUpVersionSelected(fileId: Int?) {
        markNextUpSelectorInput()
        _uiState.update {
            it.copy(
                selectedNextUpFileId = fileId,
                selectedNextUpAudioIndex = null,
                nextUpAudioPickedThisSession = false,
                selectedNextUpSubtitleIndex = null,
            )
        }
        rememberNextUpTrackSelection()
        seedPersistedNextUpTrackSelection()
    }

    fun onNextUpAudioTrackSelected(index: Int?) {
        markNextUpSelectorInput()
        _uiState.update {
            it.copy(
                selectedNextUpAudioIndex = index,
                nextUpAudioPickedThisSession = index != null,
            )
        }
        rememberNextUpTrackSelection()
        persistNextUpTrackSelection()
    }

    fun onNextUpSubtitleTrackSelected(index: Int?) {
        markNextUpSelectorInput()
        _uiState.update { it.copy(selectedNextUpSubtitleIndex = index) }
        rememberNextUpTrackSelection()
        persistNextUpTrackSelection()
    }

    private fun markNextUpSelectorInput() {
        nextUpSelectorRevision += 1
        pendingNextUpSelectionHandoff = null
    }

    private fun rememberNextUpTrackSelection() {
        val state = _uiState.value
        val nextUpContentId = state.nextUpEpisode?.contentId ?: return
        TvDetailTrackSelectionSession.remember(
            nextUpContentId,
            state.selectedNextUpFileId,
            state.selectedNextUpAudioIndex,
            state.selectedNextUpSubtitleIndex,
            trackFileId = state.nextUpPlaybackDetail?.let { detail ->
                resolveTvTrackSelectionVersion(
                    detail,
                    state.selectedNextUpFileId,
                    state.preferredQuality,
                )?.fileId
            },
        )
    }

    private fun persistNextUpTrackSelection() {
        val state = _uiState.value
        val nextUpContentId = state.nextUpEpisode?.contentId ?: return
        val detail = state.nextUpPlaybackDetail ?: return
        persistTrackSelectionFor(
            targetContentId = nextUpContentId,
            detail = detail,
            selectedFileId = state.selectedNextUpFileId,
            selectedAudioIndex = state.selectedNextUpAudioIndex,
            selectedSubtitleIndex = state.selectedNextUpSubtitleIndex,
            preferredQuality = state.preferredQuality,
        )
    }

    private fun seedPersistedNextUpTrackSelection(
        episodeContentId: String? = _uiState.value.nextUpEpisode?.contentId,
        detail: ItemDetail? = _uiState.value.nextUpPlaybackDetail,
    ) {
        val targetContentId = episodeContentId ?: return
        val playbackDetail = detail ?: return
        val selectedFileId = _uiState.value.selectedNextUpFileId
        val selectorRevision = nextUpSelectorRevision
        val refreshGeneration = nextUpPlaybackDetailGeneration
        val version = resolveTvTrackSelectionVersion(
            playbackDetail,
            selectedFileId,
            _uiState.value.preferredQuality,
        ) ?: return
        viewModelScope.launch {
            val saved = userItemState.localTrackSelection(targetContentId, version.fileId) ?: return@launch
            val restored = restoreTrackSelection(version, saved)
            var remembered: TvDetailTrackSelectionSession.Saved? = null
            while (true) {
                val current = _uiState.value
                if (
                    nextUpPlaybackDetailGeneration != refreshGeneration ||
                    nextUpSelectorRevision != selectorRevision ||
                    !shouldApplyNextUpTrackRestore(
                        currentContentId = current.nextUpEpisode?.contentId,
                        requestedContentId = targetContentId,
                        currentSelectedFileId = current.selectedNextUpFileId,
                        requestedSelectedFileId = selectedFileId,
                    )
                ) {
                    break
                }
                val merged = mergeTrackSelection(
                    currentAudioIndex = current.selectedNextUpAudioIndex,
                    currentSubtitleIndex = current.selectedNextUpSubtitleIndex,
                    durable = restored,
                )
                val updated = current.copy(
                    selectedNextUpSubtitleIndex = merged.subtitleIndex,
                    selectedNextUpAudioIndex = merged.audioIndex,
                )
                if (_uiState.compareAndSet(current, updated)) {
                    remembered = TvDetailTrackSelectionSession.Saved(
                        fileId = updated.selectedNextUpFileId,
                        audio = updated.selectedNextUpAudioIndex,
                        subtitle = updated.selectedNextUpSubtitleIndex,
                        trackFileId = version.fileId,
                    )
                    break
                }
            }
            // Remember exactly the successfully-owned target snapshot. Never
            // reread the now-current UI after a suspension: it may be a carried
            // selection for a different episode.
            remembered?.let { selection ->
                TvDetailTrackSelectionSession.remember(
                    contentId = targetContentId,
                    fileId = selection.fileId,
                    audio = selection.audio,
                    subtitle = selection.subtitle,
                    trackFileId = selection.trackFileId,
                )
            }
        }
    }

    private fun loadMoreLikeThis(detail: ItemDetail) {
        // Apple parity (PhoneSimilarRail + QA 2026-07-08): the shelf shows REAL
        // engine recommendations from /recommendations/similar, and simply
        // doesn't render when the server has recommendations/embeddings
        // disabled (error or empty response). The previous genre browse sorted
        // by rating was not a recommendation. Episodes never show the shelf —
        // viewers want the next episode, not a tangent (Apple showsSimilarRail).
        if (detail.type.lowercase() == "episode") return
        val recommendations = recommendationRepository ?: return

        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            // This shelf is secondary. Let the hero, seasons, and episode rail settle
            // before starting more requests during item-open.
            delay(300)
            _uiState.update { it.copy(moreLikeThisLoading = true) }
            val scored = recommendations.getSimilar(detail.contentId, limit = 12)
            if (scored !is ApiResult.Success || scored.data.items.isEmpty()) {
                _uiState.update { it.copy(moreLikeThisLoading = false, moreLikeThis = emptyList()) }
                return@launch
            }
            // Resolve refs to renderable items in parallel, preserving the
            // engine's ranking; failed resolutions drop silently (Apple's
            // withTaskGroup + zip-back-to-index).
            val resolved = scored.data.items.map { ref ->
                async {
                    (catalogRepository.getItemDetail(ref.mediaItemId) as? ApiResult.Success)?.data
                }
            }.awaitAll()
            val items = resolved
                .filterNotNull()
                .filterNot { isTvHiddenMediaType(it.type) || it.contentId == detail.contentId }
                .take(16)
                .map { it.toSectionItem() }
            _uiState.update {
                it.copy(moreLikeThisLoading = false, moreLikeThis = items)
            }
        }
    }
}

private fun ItemDetail.withWatchedPlaybackState(watched: Boolean): ItemDetail {
    val current = userData ?: LeafItemUserData()
    return copy(
        userData = current.copy(
            played = watched,
            isInProgress = if (watched) false else current.isInProgress,
            positionSeconds = if (watched) null else current.positionSeconds,
        ),
    )
}

private fun EpisodeListItem.withWatchedPlaybackState(watched: Boolean): EpisodeListItem {
    val current = userData ?: LeafItemUserData()
    return copy(
        userData = current.copy(
            played = watched,
            isInProgress = false,
            positionSeconds = null,
        ),
    )
}

private fun ItemDetail.toSectionItem(): SectionItem = SectionItem(
    contentId = contentId,
    type = type,
    title = title,
    year = year,
    genres = genres,
    status = status,
    ratingImdb = ratingImdb,
    contentRating = contentRating,
    overview = overview,
    posterUrl = posterUrl,
    posterThumbhash = posterThumbhash,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
)

private fun BrowseItem.toSectionItem(): SectionItem = SectionItem(
    contentId = contentId,
    type = type,
    title = title,
    year = year,
    genres = genres,
    status = status,
    ratingImdb = ratingImdb,
    contentRating = contentRating,
    overlaySummary = overlaySummary,
    overview = overview,
    posterUrl = posterUrl,
    posterThumbhash = posterThumbhash,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
    userState = userState,
)

/**
 * Session-scoped memory of the detail page's pre-play track choices, keyed by
 * contentId. The detail ViewModel is nav-entry scoped, so any navigation that
 * rebuilds the entry (season switch, re-opening the item) silently dropped a
 * manual audio/subtitle pre-selection (QA 2026-07-08). In-memory on purpose:
 * durable per-playback preferences are recorded by the player itself.
 */
/**
 * Favourites toggled on one detail screen that other retained detail screens
 * may still be showing the old answer for.
 *
 * Versioned rather than consume-once. Consume-once loses the signal whenever
 * more than one screen can read it, and more than one always can: every detail
 * screen refreshes on resume, so an episode screen returning from playback
 * would swallow the marker meant for the series rail behind it. It also loses
 * the signal when the read succeeds but the reload meant to act on it fails.
 *
 * So nothing is consumed. Each change gets a monotonically increasing version,
 * and each reader remembers the version it has caught up to, advancing that
 * mark only once a revalidation has actually succeeded. Any number of readers
 * each see every change, and a failed reload simply tries again next resume.
 *
 * A targeted set remains the point: re-asking about every visible episode on
 * every resume would restore the request volume the probe window exists to
 * prevent, and ON_RESUME also fires for returning from playback and for
 * foregrounding the app.
 */
internal object TvFavoriteRevalidationSession {
    private val lock = Any()
    private var version = 0L
    private val changedAt = LinkedHashMap<String, Long>()

    /**
     * Highest version dropped by the cap. A reader behind this cannot be told
     * what it missed, so it is told to re-check everything instead of being
     * silently handed an incomplete delta.
     */
    private var evictedThrough = 0L

    /** Ample for any one visit; oldest entries fall off rather than grow forever. */
    private const val MAX_TRACKED = 256

    fun markChanged(contentId: String) {
        if (contentId.isBlank()) return
        synchronized(lock) {
            version += 1
            changedAt.remove(contentId)
            changedAt[contentId] = version
            while (changedAt.size > MAX_TRACKED) {
                val oldest = changedAt.entries.first()
                evictedThrough = maxOf(evictedThrough, oldest.value)
                changedAt.remove(oldest.key)
            }
        }
    }

    /** The mark a reader stores once it has caught up. */
    fun currentVersion(): Long = synchronized(lock) { version }

    /**
     * Ids changed after [sinceVersion]; readers pass the mark they last stored.
     *
     * Null means "cannot say": this reader is behind entries the cap has since
     * dropped, so a delta would be incomplete. Callers re-check everything
     * visible rather than trusting a partial answer — being slow must cost a
     * round of extra probes, never a silently missed change.
     */
    fun changedSince(sinceVersion: Long): Set<String>? = synchronized(lock) {
        if (sinceVersion < evictedThrough) return null
        changedAt.entries
            .filter { it.value > sinceVersion }
            .mapTo(LinkedHashSet()) { it.key }
    }

    fun reset() = synchronized(lock) {
        version = 0L
        evictedThrough = 0L
        changedAt.clear()
    }
}

internal object TvDetailTrackSelectionSession {
    internal data class Saved(
        val fileId: Int?,
        val audio: Int?,
        val subtitle: Int?,
        /** File whose track ordinals belong to; [fileId] remains null for Auto. */
        val trackFileId: Int? = fileId,
        val positionSeconds: Double? = null,
        val durationSeconds: Double? = null,
    )

    private val byContent = HashMap<String, Saved>()

    fun remember(
        contentId: String,
        fileId: Int?,
        audio: Int?,
        subtitle: Int?,
        trackFileId: Int? = fileId,
    ) {
        if (contentId.isBlank()) return
        byContent[contentId] = Saved(fileId, audio, subtitle, trackFileId)
    }

    fun rememberPlaybackReturn(
        contentId: String,
        fileId: Int?,
        audio: Int?,
        subtitle: Int?,
        positionSeconds: Double,
        durationSeconds: Double?,
    ) {
        if (contentId.isBlank() || !positionSeconds.isFinite() || positionSeconds < 0.0) return
        val previous = byContent[contentId]
        byContent[contentId] = Saved(
            // A playback return reports the resolved backing file, not a new
            // manual Version choice. Preserve Auto/explicit UI intent.
            fileId = previous?.fileId,
            // The player currently reports subtitle selection on exit but not
            // audio selection. Keep the detail page's explicit audio choice
            // instead of replacing it with an unknown/null value.
            audio = audio ?: previous?.audio,
            // A null player result means the mounted track could not be
            // resolved to a stable server index (keep current), not Off.
            subtitle = subtitle ?: previous?.subtitle,
            trackFileId = fileId ?: previous?.trackFileId,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds?.takeIf { it.isFinite() && it > 0.0 },
        )
    }

    fun recall(contentId: String): Saved? = byContent[contentId]

    /**
     * Returns the pending player-exit progress once, while retaining the
     * session's file and track choices for later detail-screen recreation.
     */
    fun consumePlaybackReturn(contentId: String): Saved? {
        val saved = byContent[contentId]
            ?.takeIf { it.positionSeconds != null }
            ?: return null
        byContent[contentId] = saved.copy(
            positionSeconds = null,
            durationSeconds = null,
        )
        return saved
    }
}

private fun ItemDetail.withPlaybackReturn(saved: TvDetailTrackSelectionSession.Saved): ItemDetail {
    val position = saved.positionSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return this
    val current = userData ?: LeafItemUserData()
    return copy(
        userData = current.copy(
            isInProgress = position > 0.0,
            positionSeconds = position.takeIf { it > 0.0 },
            durationSeconds = saved.durationSeconds ?: current.durationSeconds,
            lastFileId = saved.trackFileId ?: current.lastFileId,
        ),
    )
}
