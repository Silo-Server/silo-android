package org.siloserver.silo.android.ui.screens.detail

import kotlinx.coroutines.flow.stateIn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.SeasonUserData
import org.siloserver.silo.model.catalog.initialSeasonDisplayPlan
import org.siloserver.silo.model.catalog.sortedForDisplay
import org.siloserver.silo.model.download.DownloadCapability
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.network.isAccessRefusal
import org.siloserver.silo.model.catalog.isBookLikeItemType
import org.siloserver.silo.metadata.DescriptionTranslationController
import org.siloserver.silo.metadata.DescriptionTranslationPhase
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.MetadataAiRepository
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.repository.EbookReaderRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.RecommendationRepository
import org.siloserver.silo.viewmodel.applyLocalPlaybackProgress
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.playback.resolveSubtitleTrackOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI state for the item detail screen.
 */
data class ItemDetailUiState(
    val isLoading: Boolean = true,
    val detail: ItemDetail? = null,
    val similarItems: List<org.siloserver.silo.model.catalog.BrowseItem> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val selectedSeasonNumber: Int = 1,
    val episodes: List<EpisodeListItem> = emptyList(),
    /** Episode selected inside a series page. Its full detail powers the hero play target and selectors. */
    val selectedEpisodeContentId: String? = null,
    val selectedEpisodeDetail: ItemDetail? = null,
    val isLoadingSelectedEpisodeDetail: Boolean = false,
    /** Parent-series portrait art used when an episode's own artwork is a wide still. */
    val episodeSeriesPosterUrl: String? = null,
    /** Parent series title on a standalone episode page, for download grouping. */
    val episodeSeriesTitle: String? = null,
    val episodeSeriesPosterThumbhash: String? = null,
    /** The parent-series poster lookup has finished, with or without a poster. */
    val episodeSeriesPosterResolved: Boolean = false,
    /**
     * Route-scoped episode lists keyed by season. Unlike the repository's
     * durable network-fallback cache, this map is UI-first: once a season has
     * loaded, chip taps and pager swipes can reuse it without another request.
     */
    val episodesBySeason: Map<Int, List<EpisodeListItem>> = emptyMap(),
    val isLoadingEpisodes: Boolean = false,
    /** First-file ids of EVERY episode across ALL seasons (loaded once for the
     *  series-level downloaded roll-up — the per-season `episodes` only covers
     *  the selected season). Empty until the background load completes. */
    val allEpisodeFileIds: List<Int> = emptyList(),
    /** True only when EVERY season's episodes loaded successfully — the series
     *  hero may show ✓ only then (a failed season would shrink the denominator
     *  and falsely complete the roll-up). */
    val allEpisodeIdsComplete: Boolean = false,
    val isFavorite: Boolean = false,
    val isInWatchlist: Boolean = false,
    val userRating: Int? = null,
    val error: String? = null,
    val selectedVersionIndex: Int = 0,
    val selectedAudioIndex: Int = 0,
    val selectedSubtitleIndex: Int = -1,
    val hasExplicitVersionSelection: Boolean = false,
    val hasExplicitAudioSelection: Boolean = false,
    val hasExplicitSubtitleSelection: Boolean = false,
    /** Server converts Kindle (mobi/azw/azw3) to EPUB, so they read in-app. */
    val kindleConversionAvailable: Boolean = false,
)

internal class EpisodeRollupAccumulator(
    val seriesId: String,
    private val seasonNumbers: Set<Int>,
) {
    private val completedSeasonNumbers = linkedSetOf<Int>()
    private val accumulatedFileIds = linkedSetOf<Int>()

    val fileIds: List<Int>
        get() = accumulatedFileIds.toList()

    val isComplete: Boolean
        get() = completedSeasonNumbers.containsAll(seasonNumbers)

    fun matches(seriesId: String, seasonNumbers: Set<Int>): Boolean =
        this.seriesId == seriesId && this.seasonNumbers == seasonNumbers

    fun recordSeason(seasonNumber: Int, episodes: List<EpisodeListItem>) {
        episodes.forEach { episode ->
            episode.files.firstOrNull()?.fileId?.let(accumulatedFileIds::add)
        }
        completedSeasonNumbers += seasonNumber
    }

    fun remainingSeasons(seasons: List<Season>): List<Season> =
        seasons.filterNot { it.seasonNumber in completedSeasonNumbers }
}

/**
 * ViewModel for the item detail screen.
 *
 * Fetches item metadata, user state (favorite/watchlist), and for series,
 * also fetches seasons and episodes. Supports toggling favorite and watchlist.
 */
class ItemDetailViewModel(
    private val catalogRepository: CatalogRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val downloadsRepository: DownloadsRepository,
    private val downloadEnqueuer: DownloadEnqueuer,
    private val ebookReaderRepository: EbookReaderRepository,
    private val recommendationRepository: RecommendationRepository,
    private val metadataAiRepository: MetadataAiRepository,
    savedStateHandle: SavedStateHandle,
    private val userItemState: org.siloserver.silo.repository.port.UserItemStatePort =
        org.siloserver.silo.repository.port.NoOpUserItemStatePort,
) : ViewModel() {
    /** Access changes this ViewModel has applied, kept while its screen is away. */
    val accessChanges = org.siloserver.silo.network.AccessChangeCursor()

    private var similarGeneration = 0L
    private var similarJob: kotlinx.coroutines.Job? = null
    private var detailLoadJob: Job? = null
    private var quietDetailJob: Job? = null
    /** Whether [quietDetailJob] clears the page on a refusal (an access-change refresh). */
    private var quietDetailShowsRefusal = false
    private val libraryId: Int? = savedStateHandle.get<String>("libraryId")?.toIntOrNull()
    private val contentId: String = savedStateHandle.get<String>("contentId") ?: ""
    private val initialSeasonNumber: Int? =
        savedStateHandle.get<String>("seasonNumber")?.toIntOrNull()
    private val initialEpisodeContentId: String? =
        savedStateHandle.get<String>("episodeContentId")?.takeIf { it.isNotBlank() }
    private var pendingInitialEpisodeContentId: String? = initialEpisodeContentId

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = kotlinx.coroutines.flow.combine(_uiState, personalDataRepository.memberships.actions) { state, actions ->
        var projected = state
        actions.values.filter { it.baseline != null && it.intent.key.itemId == contentId && personalDataRepository.memberships.current(it.intent) }.forEach {
            projected = if (it.intent.key.kind == org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE)
                projected.copy(isFavorite = it.baseline!!.present) else projected.copy(isInWatchlist = it.baseline!!.present)
        }
        projected
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, ItemDetailUiState())
    private var episodeLoadJob: Job? = null
    private var selectedEpisodeLoadJob: Job? = null
    private var allEpisodeFileIdsJob: Job? = null
    private data class EpisodeRollupRequest(
        val seriesId: String,
        val seasons: List<Season>,
        val seedEpisodes: List<EpisodeListItem>,
        val skipSeasonNumber: Int?,
    )
    private var routeActive = true
    private var pendingEpisodeRollup: EpisodeRollupRequest? = null
    private var episodeRollupAccumulator: EpisodeRollupAccumulator? = null
    // The season number the currently-shown episodes actually belong to. A
    // failed season switch reverts the optimistic selection to THIS season —
    // not merely the previously-selected one, which may itself have failed —
    // so the chips and the episode list stay in agreement (TV parity: T15).
    private var loadedSeasonNumber: Int? = null

    /** Live mirror of the shared records flow; the screen reads this to
     *  derive per-version download state (isDownloaded / progress). */
    val downloads: StateFlow<List<DownloadRecord>> = downloadsRepository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Server download capability (issue #20 §3). The screen reads this to gate
     *  which quality presets the picker offers. Null until the first fetch. */
    val downloadCapability: StateFlow<DownloadCapability?> = downloadsRepository.capability

    private var watchedMutationGeneration = 0
    private val episodeWatchedMutationGenerations = mutableMapOf<String, Int>()
    private val seasonWatchedMutationGenerations = mutableMapOf<Int, Int>()
    private var seasonsRefreshGeneration = 0
    private val failedEpisodeWatchedGenerations = mutableMapOf<String, Int>()
    private val succeededEpisodeWatchedGenerations = mutableMapOf<String, Int>()
    private var failedSeriesWatchedGeneration = -1

    private val descriptionTranslation = DescriptionTranslationController(
        repository = metadataAiRepository,
        delayMs = { kotlinx.coroutines.delay(it) },
    )
    val translationPhase: StateFlow<DescriptionTranslationPhase> = descriptionTranslation.phase

    init {
        viewModelScope.launch {
            var previous = personalDataRepository.memberships.generation.value
            personalDataRepository.memberships.generation.collect { generation ->
                if (generation != previous) {
                    previous = generation
                    _uiState.update { it.copy(isFavorite = false, isInWatchlist = false) }
                    loadUserState()
                }
            }
        }

        // Refresh once so server-side records are visible when the user
        // lands on the detail screen (e.g., to show 'Downloaded' on a file
        // that was downloaded in a previous app session).
        viewModelScope.launch { downloadsRepository.refresh() }
        // Fetch download capability so the quality picker only offers presets
        // this account may request (issue #20 GAP 4). Best-effort: on failure
        // the picker falls back to an optimistic list; the server still rejects
        // a disallowed quality. Cached in the repo, so this is cheap per detail.
        viewModelScope.launch { downloadsRepository.refreshCapability() }
    }

    /**
     * Returns the download record for the given [version]'s fileId, or
     * null when nothing has been requested for that version yet.
     */
    fun downloadRecordFor(version: FileVersion): DownloadRecord? =
        downloads.value.firstOrNull { it.mediaFileId == version.fileId }

    /**
     * Tap action for the download button. Branches on current record state:
     *  - None / failed / cancelled → start a new download
     *  - Queued / downloading → cancel via WorkManager + delete the record
     *  - Completed → no-op (user deletes from the Downloads tab)
     *  - Completed but local file missing → delete stale server row, then start again
     */
    fun onDownloadTapped(
        version: FileVersion,
        displayTitle: String,
        forceRedownloadMissingLocal: Boolean = false,
        downloadQuality: DownloadQuality? = null,
        episode: ItemDetail? = null,
    ) {
        val existing = downloadRecordFor(version)
        when (
            detailDownloadTapAction(
                status = existing?.statusEnum(),
                forceRedownloadMissingLocal = forceRedownloadMissingLocal,
            )
        ) {
            DetailDownloadTapAction.Cancel -> {
                existing?.let { record ->
                    val cancelScope = downloadEnqueuer.captureCancelScope()
                    viewModelScope.launch {
                        downloadEnqueuer.cancel(record.id, version.fileId, cancelScope)
                        downloadsRepository.delete(record.id)
                    }
                }
            }
            DetailDownloadTapAction.Ignore -> Unit  // Manage via Downloads tab.
            DetailDownloadTapAction.ReplaceAndStart -> viewModelScope.launch {
                val staleRecord = existing
                if (staleRecord == null || downloadsRepository.delete(staleRecord.id) is ApiResult.Success) {
                    startDownload(version, displayTitle, downloadQuality, episode)
                }
            }
            DetailDownloadTapAction.Start -> viewModelScope.launch {
                startDownload(version, displayTitle, downloadQuality, episode)
            }
        }
    }

    /** True = download started, false = registration failed. Drives the
     *  download-start haptic (the only haptic iOS mobile has). */
    private val _downloadStartEvents = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val downloadStartEvents: kotlinx.coroutines.flow.SharedFlow<Boolean> = _downloadStartEvents

    /** Why a download couldn't start, such as the server's "concurrent
     *  download limit reached". The screen shows it; without it a rejected
     *  request, and above all a rejected series batch, looked like nothing
     *  happened. */
    private val _downloadFailureMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val downloadFailureMessages: kotlinx.coroutines.flow.SharedFlow<String> = _downloadFailureMessages

    private suspend fun reportDownloadStart(result: ApiResult<*>) {
        _downloadStartEvents.emit(result is ApiResult.Success)
        if (result !is ApiResult.Success) {
            _downloadFailureMessages.emit(result.errorMessage("Couldn't start the download."))
        }
    }

    private suspend fun startDownload(
        version: FileVersion,
        displayTitle: String,
        downloadQuality: DownloadQuality?,
        episode: ItemDetail?,
    ) {
        // The series page names its selected episode; an episode page is
        // itself the episode. Episodes register under their series, which the
        // server requires, so they go through startEpisode.
        val pageDetail = _uiState.value.detail
        val item = episode ?: pageDetail
        val seriesPage = pageDetail?.takeIf { it.type == "series" }
        // On the series page the page itself is the parent when the episode
        // row omits its series id.
        val seriesId = item?.seriesId?.takeIf { it.isNotBlank() }
            ?: seriesPage?.takeIf { episode != null }?.contentId
        // wifiOnly read from per-profile PlayerSettingsStore inside
        // DownloadEnqueuer; default true.
        val result = when {
            item?.type == "episode" && seriesId != null -> {
                // Never the episode's own title or art: the Downloads tab
                // groups episodes under the series' name and poster.
                val knownTitle = item.seriesTitle?.takeIf { it.isNotBlank() }
                    ?: seriesPage?.title
                    ?: _uiState.value.episodeSeriesTitle?.takeIf { it.isNotBlank() }
                val knownPoster = seriesPage?.posterUrl ?: _uiState.value.episodeSeriesPosterUrl
                // On an episode page the parent load may not have finished (or
                // may not run at all); the title and poster are stored with the
                // download, so fetch the parent now when either is missing. A
                // series page already is the parent, so it never refetches.
                val parent = if (seriesPage == null && (knownTitle == null || knownPoster == null)) {
                    (catalogRepository.getItemDetailForPrefetch(seriesId, libraryId = libraryId) as? ApiResult.Success)?.data
                } else {
                    null
                }
                downloadEnqueuer.startEpisode(
                    seriesContentId = seriesId,
                    episodeContentId = item.contentId,
                    fileId = version.fileId,
                    seriesTitle = knownTitle ?: parent?.title?.takeIf { it.isNotBlank() } ?: "Series",
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.title,
                    posterUrl = knownPoster ?: parent?.posterUrl ?: pageDetail?.posterUrl,
                    posterIsEpisodeStill = if (knownPoster != null || parent?.posterUrl != null) false
                        else pageDetail?.posterIsEpisodeStill,
                    downloadQualityOverride = downloadQuality,
                )
            }
            // The server rejects an episode sent without its series.
            item?.type == "episode" -> ApiResult.Error(0, "missing_series", "This episode isn't linked to a series.")
            else -> downloadEnqueuer.start(
                contentId = item?.contentId ?: contentId,
                fileId = version.fileId,
                displayTitle = displayTitle,
                downloadQualityOverride = downloadQuality,
            )
        }
        reportDownloadStart(result)
    }

    /** Series-level "Download series" — uses the server's batch endpoint
     *  (one POST → N records sharing a batchId). */
    fun onSeriesDownloadTapped(downloadQuality: DownloadQuality? = null) {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            reportDownloadStart(
                downloadEnqueuer.startSeries(
                    seriesContentId = detail.contentId,
                    downloadQualityOverride = downloadQuality,
                ),
            )
        }
    }

    init {
        if (contentId.isNotBlank()) {
            loadDetail()
            loadUserState()
        }
    }

    suspend fun hasSeriesDetailForRedirect(seriesContentId: String): Boolean {
        fun ItemDetail?.matchesParent(): Boolean =
            this != null && contentId == seriesContentId && type.equals("series", ignoreCase = true)

        if (catalogRepository.getCachedItemDetail(seriesContentId, libraryId = libraryId).matchesParent()) return true
        return when (val result = catalogRepository.getItemDetail(seriesContentId, libraryId = libraryId)) {
            is ApiResult.Success -> result.data.matchesParent()
            else -> false
        }
    }

    fun loadDetail() = loadDetail(afterAccessChange = false)

    /**
     * [afterAccessChange] skips joining a Home warm-up still in flight, whose
     * answer may predate the change.
     */
    private fun loadDetail(afterAccessChange: Boolean) {
        val similarRun = ++similarGeneration
        similarJob?.cancel()
        // A newer load replaces an unfinished one, so a response the server
        // gave under an older access policy never lands after it.
        detailLoadJob?.cancel()
        quietDetailJob?.cancel()
        _uiState.update { it.copy(similarItems = emptyList()) }
        detailLoadJob = viewModelScope.launch {
            val similarOwner = recommendationRepository.captureSimilarAuthority()
            _uiState.update { it.copy(isLoading = true, error = null) }
            // Start the live request immediately. The durable cache read can
            // still paint an instant first frame, but it no longer delays the
            // network request that supplies fresh movie/series metadata.
            val liveDetail = async {
                catalogRepository.getItemDetail(contentId, libraryId = libraryId, joinWarmup = !afterAccessChange)
            }
            seedCachedDetail()

            when (val result = liveDetail.await()) {
                is ApiResult.Success -> {
                    val detail = withLocalProgress(result.data)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = detail,
                            userRating = detail.userRating,
                            error = null,
                        )
                    }
                    // Restore a persisted audio/subtitle override for this item.
                    seedPersistedTrackSelection()
                    if (similarRun == similarGeneration) {
                        similarJob = viewModelScope.launch { loadSimilar(detail, similarOwner, similarRun) }
                    }
                    // For series, load seasons
                    if (detail.type == "series") {
                        loadSeasons(detail.contentId)
                    }
                    // For episodes, load the parent series' seasons + this
                    // season's siblings so the page can offer the selector.
                    if (detail.type == "episode") {
                        val seriesId = detail.seriesId
                        val seasonNumber = detail.seasonNumber
                        if (seriesId != null && seasonNumber != null) {
                            loadEpisodeSiblings(seriesId, seasonNumber)
                        } else {
                            // No parent to look up, so no series poster is coming.
                            _uiState.update { it.copy(episodeSeriesPosterResolved = true) }
                        }
                    }
                    // For books, learn whether the server converts Kindle formats to
                    // EPUB, so the "Read" affordance can offer mobi/azw/azw3 in-app.
                    if (isBookLikeItemType(detail.type)) {
                        viewModelScope.launch {
                            if (ebookReaderRepository.isKindleConversionAvailable()) {
                                _uiState.update { it.copy(kindleConversionAvailable = true) }
                            }
                        }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // The durable cache still holds a title the server
                            // now refuses; drop the copy seedCachedDetail painted
                            // so the screen shows the error, not stale actions.
                            detail = if (dropsDetailOn(result)) null else it.detail,
                            error = result.message.ifBlank { "Failed to load details" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Network error. Check your connection.",
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadSimilar(detail: ItemDetail, owner: org.siloserver.silo.network.AuthScopeSnapshot?, run: Long) {
        if (owner == null || detail.type == "episode") return
        recommendationRepository.loadSimilarCards(detail.contentId, owner,
            stillCurrent = { run == similarGeneration && _uiState.value.detail?.contentId == detail.contentId },
            publish = { cards -> _uiState.update { it.copy(similarItems = cards) } })
    }

    /**
     * Quiet refresh for returning to an already-loaded detail screen (e.g.
     * backing out of the player): re-reads userData so the Play button's
     * resume label and the episode list reflect the session that just ended.
     * Deliberately NOT [loadDetail] — no loading flashes, and the user's
     * season selection is preserved.
     */
    fun refreshOnReturn() = quietRefresh()

    /**
     * [refreshOnReturn] after the server reports an access change, except that
     * a refusal ([isAccessRefusal]) replaces the detail with the error the
     * initial load shows. Transient failures still keep the current detail.
     * With no detail on screen (an earlier change refused it, or the first
     * load failed), it runs the full load again, as Retry does, so a title the
     * viewer regains access to comes back. A load still in flight is replaced
     * the same way, because its answer may predate the change.
     */
    fun refreshAfterAccessChange() {
        val state = _uiState.value
        // A load still in flight may have been answered under the old policy:
        // replace it rather than letting the change pass unapplied.
        if (state.isLoading || state.detail == null) {
            loadDetail(afterAccessChange = true)
        } else {
            quietRefresh(showAccessRefusal = true)
        }
    }

    /**
     * Whether a failed read should clear the page: an access refusal, unless
     * the title has a completed download. The page's local play and delete
     * actions stay usable for a file the server has since deleted or renamed.
     */
    private fun dropsDetailOn(result: ApiResult.Error): Boolean =
        result.isAccessRefusal() && downloads.value.none { record ->
            record.statusEnum() == DownloadStatus.Completed &&
                (record.contentId == contentId || record.episodeId == contentId)
        }

    /**
     * [afterWatchedChange] reads the season list and episodes fresh from the
     * server: a coalesced request or cached fallback could still hold the
     * state from before the write.
     * [showAccessRefusal]: see [refreshAfterAccessChange].
     */
    private fun quietRefresh(afterWatchedChange: Boolean = false, showAccessRefusal: Boolean = false) {
        val current = _uiState.value.detail ?: return
        // A newer detail read replaces an unfinished one, so an older answer
        // cannot land after it, for example restoring a title the newer read
        // found refused. The replacement inherits an access-change refresh's
        // refusal handling, so a return refresh cannot drop it.
        val showRefusal = showAccessRefusal || (quietDetailShowsRefusal && quietDetailJob?.isActive == true)
        quietDetailJob?.cancel()
        quietDetailShowsRefusal = showRefusal
        quietDetailJob = viewModelScope.launch {
            // Local overlay first: the player's final position write is already
            // on disk, so the label corrects before the server round-trip.
            val overlaid = withLocalProgress(current)
            if (overlaid != current) {
                _uiState.update { it.copy(detail = overlaid) }
            }
            when (val result = catalogRepository.getItemDetail(
                contentId,
                libraryId = libraryId,
                // An access-change refresh must not reuse a pre-change warm-up.
                joinWarmup = !showRefusal,
            )) {
                is ApiResult.Success -> {
                    val detail = withLocalProgress(result.data)
                    _uiState.update {
                        it.copy(
                            detail = detail,
                            userRating = detail.userRating,
                        )
                    }
                }
                is ApiResult.Error -> if (showRefusal && dropsDetailOn(result)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = null,
                            error = result.message.ifBlank { "Failed to load details" },
                        )
                    }
                }
                // Quiet refresh: on failure keep showing what we have.
                is ApiResult.NetworkError -> Unit
            }
        }
        if (current.type == "series") {
            refreshSeasonsQuietly(current.contentId, fresh = afterWatchedChange)
            loadEpisodes(
                current.contentId,
                _uiState.value.selectedSeasonNumber,
                forceRefresh = true,
                fresh = afterWatchedChange,
            )
        } else if (current.type == "episode") {
            current.seriesId?.let {
                loadEpisodes(
                    it,
                    _uiState.value.selectedSeasonNumber,
                    forceRefresh = true,
                )
            }
        }
    }

    private suspend fun seedCachedDetail() {
        val cached = catalogRepository.getCachedItemDetail(contentId, libraryId = libraryId)?.let { withLocalProgress(it) } ?: return
        _uiState.update {
            it.copy(
                isLoading = true,
                detail = cached,
                userRating = cached.userRating,
                error = null,
            )
        }
    }

    private fun loadUserState() {
        viewModelScope.launch {
            val favResult = personalDataRepository.isFavorite(contentId)
            if (favResult is ApiResult.Success) {
                _uiState.update { it.copy(isFavorite = favResult.data) }
            }
        }
        viewModelScope.launch {
            val wlResult = personalDataRepository.isInWatchlist(contentId)
            if (wlResult is ApiResult.Success) {
                _uiState.update { it.copy(isInWatchlist = wlResult.data) }
            }
        }
    }

    private fun loadSeasons(seriesId: String) {
        viewModelScope.launch {
            // Continue Watching warms the parent series before navigation. Use
            // that fresh cache immediately only for that targeted route; direct
            // card opens retain their normal live season refresh.
            val result = if (initialEpisodeContentId != null) {
                catalogRepository.getSeasonsForPrefetch(seriesId, libraryId = libraryId)
            } else {
                catalogRepository.getSeasons(seriesId, libraryId = libraryId)
            }
            when (result) {
                is ApiResult.Success -> {
                    val plan = result.data.seasons.initialSeasonDisplayPlan(initialSeasonNumber)
                    _uiState.update {
                        it.copy(
                            seasons = plan.seasons,
                            selectedSeasonNumber = plan.selectedSeasonNumber ?: 1,
                        )
                    }
                    plan.episodeRequestSeasonNumber?.let { seasonNumber ->
                        loadEpisodes(
                            seriesId = seriesId,
                            seasonNumber = seasonNumber,
                            seasonsForDownloadRollup = plan.seasons,
                            preferPrefetched = initialEpisodeContentId != null,
                        )
                    } ?: run {
                        loadAllEpisodeFileIds(seriesId, plan.seasons)
                    }
                }
                else -> { /* Season load failure is non-critical */ }
            }
        }
    }

    /** Loads every season's episodes once to compute the series-level downloaded
     *  roll-up (✓ only when ALL episodes are downloaded). Best-effort: a season
     *  that fails to load just contributes no ids. Episode reads are cache-backed. */
    private fun loadAllEpisodeFileIds(
        seriesId: String,
        seasons: List<Season>,
        seedEpisodes: List<EpisodeListItem> = emptyList(),
        skipSeasonNumber: Int? = null,
    ) {
        pendingEpisodeRollup = EpisodeRollupRequest(
            seriesId = seriesId,
            seasons = seasons,
            seedEpisodes = seedEpisodes,
            skipSeasonNumber = skipSeasonNumber,
        )
        allEpisodeFileIdsJob?.cancel()
        if (!routeActive) return
        allEpisodeFileIdsJob = viewModelScope.launch {
            val seasonNumbers = seasons.mapTo(linkedSetOf()) { it.seasonNumber }
            val accumulator = episodeRollupAccumulator
                ?.takeIf { it.matches(seriesId, seasonNumbers) }
                ?: EpisodeRollupAccumulator(seriesId, seasonNumbers).also {
                    episodeRollupAccumulator = it
                }
            val canSkipSeedSeason = skipSeasonNumber != null && seedEpisodes.isNotEmpty()
            if (canSkipSeedSeason) {
                accumulator.recordSeason(checkNotNull(skipSeasonNumber), seedEpisodes)
            }
            _uiState.update {
                it.copy(
                    allEpisodeFileIds = accumulator.fileIds,
                    allEpisodeIdsComplete = accumulator.isComplete,
                )
            }
            if (accumulator.isComplete) {
                pendingEpisodeRollup = null
                return@launch
            }

            // This roll-up is only for the detail download badge. Let the selected
            // season render and become interactive before crawling the rest.
            delay(350)
            if (!routeActive) return@launch

            for (season in accumulator.remainingSeasons(seasons)) {
                if (!routeActive) return@launch
                when (val r = catalogRepository.getEpisodes(seriesId, season.seasonNumber, libraryId = libraryId)) {
                    is ApiResult.Success -> {
                        val episodes = withLocalProgress(r.data.episodes)
                        cacheEpisodes(season.seasonNumber, episodes)
                        accumulator.recordSeason(season.seasonNumber, episodes)
                        _uiState.update {
                            it.copy(
                                allEpisodeFileIds = accumulator.fileIds,
                                allEpisodeIdsComplete = accumulator.isComplete,
                            )
                        }
                    }
                    // Leave a failed season incomplete so a later route resume retries it.
                    else -> Unit
                }
            }
            if (!routeActive) return@launch
            _uiState.update {
                it.copy(
                    allEpisodeFileIds = accumulator.fileIds,
                    allEpisodeIdsComplete = accumulator.isComplete,
                )
            }
            if (accumulator.isComplete) pendingEpisodeRollup = null
        }
    }

    fun onRoutePaused() {
        routeActive = false
        allEpisodeFileIdsJob?.cancel()
    }

    fun onRouteResumed() {
        val wasPaused = !routeActive
        routeActive = true
        refreshOnReturn()
        if (wasPaused && !_uiState.value.allEpisodeIdsComplete) {
            pendingEpisodeRollup?.let { request ->
                loadAllEpisodeFileIds(
                    seriesId = request.seriesId,
                    seasons = request.seasons,
                    seedEpisodes = request.seedEpisodes,
                    skipSeasonNumber = request.skipSeasonNumber,
                )
            }
        }
    }

    /** Episode pages: seasons + this season's siblings, mirroring iOS's
     *  episode detail (the episode's own season stays selected; no series
     *  download roll-up — that's a series-page concern). */
    private fun loadEpisodeSiblings(seriesId: String, seasonNumber: Int) {
        _uiState.update { it.copy(selectedSeasonNumber = seasonNumber) }
        loadEpisodes(seriesId, seasonNumber)
        viewModelScope.launch {
            when (val result = catalogRepository.getSeasons(seriesId, libraryId = libraryId)) {
                is ApiResult.Success -> {
                    val seasons = result.data.seasons.sortedForDisplay()
                    _uiState.update { it.copy(seasons = seasons) }
                }
                else -> { /* Season load failure is non-critical */ }
            }

            // Resolve seasons before the series fallback. Otherwise a cache-fast
            // series poster can paint for a frame and then be replaced by the
            // selected season poster when its request completes.
            when (val result = catalogRepository.getItemDetailForPrefetch(seriesId, libraryId = libraryId)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            episodeSeriesPosterUrl = result.data.posterUrl,
                            episodeSeriesPosterThumbhash = result.data.posterThumbhash,
                            episodeSeriesTitle = result.data.title,
                            episodeSeriesPosterResolved = true,
                        )
                    }
                }
                // Series poster fallback is optional.
                else -> _uiState.update { it.copy(episodeSeriesPosterResolved = true) }
            }
        }
    }

    /**
     * Selects a season and loads its episodes. Works from both the series
     * page and an episode page (where the loaded detail is the episode and
     * the series id comes from its parent reference).
     */
    fun selectSeason(seasonNumber: Int) {
        // Optimistic write; a failed load reverts to [loadedSeasonNumber] so
        // the new season header can't sit above the old season's still-loaded
        // episodes (see loadEpisodes' error branches).
        _uiState.update {
            val cachedEpisodes = it.episodesBySeason[seasonNumber]
            it.copy(
                selectedSeasonNumber = seasonNumber,
                episodes = cachedEpisodes.orEmpty(),
                isLoadingEpisodes = cachedEpisodes == null,
                selectedEpisodeContentId = null,
                selectedEpisodeDetail = null,
                isLoadingSelectedEpisodeDetail = false,
            )
        }
        val detail = _uiState.value.detail ?: return
        val seriesId = if (detail.type == "series") detail.contentId else detail.seriesId ?: return
        loadEpisodes(seriesId, seasonNumber)
    }

    /** Selects an episode in place instead of pushing a separate episode route. */
    fun selectSeriesEpisode(contentId: String) {
        if (_uiState.value.selectedEpisodeContentId == contentId &&
            _uiState.value.selectedEpisodeDetail != null
        ) return
        selectedEpisodeLoadJob?.cancel()
        _uiState.update {
            it.copy(
                selectedEpisodeContentId = contentId,
                selectedEpisodeDetail = null,
                isLoadingSelectedEpisodeDetail = true,
                selectedVersionIndex = 0,
                selectedAudioIndex = 0,
                selectedSubtitleIndex = -1,
                hasExplicitVersionSelection = false,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
            )
        }
        loadSelectedEpisodeDetail(contentId)
    }

    fun ensureSelectedEpisodeDetailLoaded() {
        val state = _uiState.value
        val contentId = state.selectedEpisodeContentId ?: return
        if (state.selectedEpisodeDetail != null || state.isLoadingSelectedEpisodeDetail) return
        _uiState.update { it.copy(isLoadingSelectedEpisodeDetail = true) }
        loadSelectedEpisodeDetail(contentId)
    }

    private fun loadSelectedEpisodeDetail(contentId: String) {
        selectedEpisodeLoadJob = viewModelScope.launch {
            val result = if (contentId == initialEpisodeContentId) {
                catalogRepository.getItemDetailForPrefetch(contentId, libraryId = libraryId)
            } else {
                catalogRepository.getItemDetail(contentId, libraryId = libraryId)
            }
            when (result) {
                is ApiResult.Success -> _uiState.update { state ->
                    if (state.selectedEpisodeContentId != contentId) state else state.copy(
                        selectedEpisodeDetail = withLocalProgress(result.data),
                        isLoadingSelectedEpisodeDetail = false,
                    )
                }
                else -> _uiState.update { state ->
                    if (state.selectedEpisodeContentId != contentId) state else state.copy(
                        isLoadingSelectedEpisodeDetail = false,
                    )
                }
            }
        }
    }

    private fun ensureSeriesEpisodeSelection(episodes: List<EpisodeListItem>) {
        if (episodes.isEmpty()) return
        val current = _uiState.value.selectedEpisodeContentId
        if (episodes.any { it.contentId == current }) return
        val routedEpisode = pendingInitialEpisodeContentId?.let { requestedContentId ->
            episodes.firstOrNull { it.contentId == requestedContentId }
        }
        // The route's target applies only to its initial season load. If stale
        // metadata names an episode outside that season, fall back normally
        // instead of unexpectedly selecting it after a later manual chip tap.
        pendingInitialEpisodeContentId = null
        val preferred = routedEpisode ?: episodes.firstOrNull {
            (it.userData?.positionSeconds ?: 0.0) > 0.0 && it.userData?.played != true
        } ?: episodes.firstOrNull { it.userData?.played != true }
            ?: episodes.first()
        _uiState.update {
            it.copy(
                selectedEpisodeContentId = preferred.contentId,
                selectedEpisodeDetail = null,
                isLoadingSelectedEpisodeDetail = false,
                selectedVersionIndex = 0,
                selectedAudioIndex = 0,
                selectedSubtitleIndex = -1,
                hasExplicitVersionSelection = false,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
            )
        }
    }

    private fun loadEpisodes(
        seriesId: String,
        seasonNumber: Int,
        seasonsForDownloadRollup: List<Season>? = null,
        forceRefresh: Boolean = false,
        preferPrefetched: Boolean = false,
        fresh: Boolean = false,
    ) {
        episodeLoadJob?.cancel()
        val cachedEpisodes = _uiState.value.episodesBySeason[seasonNumber]
        if (!forceRefresh && cachedEpisodes != null) {
            loadedSeasonNumber = seasonNumber
            _uiState.update {
                it.copy(
                    selectedSeasonNumber = seasonNumber,
                    episodes = cachedEpisodes,
                    isLoadingEpisodes = false,
                )
            }
            ensureSeriesEpisodeSelection(cachedEpisodes)
            seasonsForDownloadRollup?.let { seasons ->
                loadAllEpisodeFileIds(
                    seriesId = seriesId,
                    seasons = seasons,
                    seedEpisodes = cachedEpisodes,
                    skipSeasonNumber = seasonNumber,
                )
            }
            return
        }
        episodeLoadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingEpisodes = true,
                    episodes = if (it.selectedSeasonNumber == seasonNumber) {
                        cachedEpisodes.orEmpty()
                    } else {
                        it.episodes
                    },
                )
            }
            val result = if (!forceRefresh && preferPrefetched) {
                catalogRepository.getEpisodesForPrefetch(seriesId, seasonNumber, libraryId = libraryId)
            } else {
                catalogRepository.getEpisodes(seriesId, seasonNumber, libraryId = libraryId, fresh = fresh)
            }
            when (result) {
                is ApiResult.Success -> {
                    val episodes = withLocalProgress(result.data.episodes)
                    loadedSeasonNumber = seasonNumber
                    _uiState.update {
                        val cache = it.episodesBySeason + (seasonNumber to episodes)
                        it.copy(
                            isLoadingEpisodes = false,
                            episodesBySeason = cache,
                            episodes = if (it.selectedSeasonNumber == seasonNumber) episodes else it.episodes,
                        )
                    }
                    ensureSeriesEpisodeSelection(episodes)
                    seasonsForDownloadRollup?.let { seasons ->
                        loadAllEpisodeFileIds(
                            seriesId = seriesId,
                            seasons = seasons,
                            seedEpisodes = episodes,
                            skipSeasonNumber = seasonNumber,
                        )
                    }
                }
                // Failed season switch: revert the optimistic selection to the
                // season whose episodes are actually on screen. A
                // successful-but-empty season keeps the new selection (empty state).
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update {
                        val fallbackSeasonNumber = loadedSeasonNumber ?: it.selectedSeasonNumber
                        it.copy(
                            isLoadingEpisodes = false,
                            selectedSeasonNumber = fallbackSeasonNumber,
                            episodes = it.episodesBySeason[fallbackSeasonNumber].orEmpty(),
                        )
                    }
                    seasonsForDownloadRollup?.let { loadAllEpisodeFileIds(seriesId, it) }
                }
            }
        }
    }

    private fun cacheEpisodes(
        seasonNumber: Int,
        episodes: List<EpisodeListItem>,
    ) {
        _uiState.update {
            val cache = it.episodesBySeason + (seasonNumber to episodes)
            it.copy(
                episodesBySeason = cache,
                episodes = if (it.selectedSeasonNumber == seasonNumber) episodes else it.episodes,
            )
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
     * Toggles the favorite state for this item.
     */
    fun toggleFavorite() {
        val intent = personalDataRepository.memberships.begin(contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE, !uiState.value.isFavorite)
        viewModelScope.launch { personalDataRepository.memberships.perform(intent) }
    }

    /**
     * Sets the user's star rating, clamped to 1..5. Mirrors the
     * [toggleFavorite] optimistic-update pattern: update state, call the
     * repository, revert on any non-Success result.
     */
    fun setRating(stars: Int) {
        val target = stars.coerceIn(1, 5)
        val writeIntent = personalDataRepository.beginRating(contentId, target)
        viewModelScope.launch {
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            val previous = _uiState.value.userRating
            // Optimistic update
            _uiState.update { it.copy(userRating = target) }
            val writeResult = personalDataRepository.performPersonalWrite(writeIntent)
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            when (writeResult) {
                is ApiResult.Success -> { /* already updated */ }
                else -> {
                    // Revert on failure
                    _uiState.update { it.copy(userRating = previous) }
                }
            }
        }
    }

    /** Removes the user's rating with optimistic update + revert on failure. */
    fun clearRating() {
        val writeIntent = personalDataRepository.beginRating(contentId, null)
        viewModelScope.launch {
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            val previous = _uiState.value.userRating ?: return@launch
            // Optimistic update
            _uiState.update { it.copy(userRating = null) }
            val writeResult = personalDataRepository.performPersonalWrite(writeIntent)
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            when (writeResult) {
                is ApiResult.Success -> { /* already updated */ }
                else -> {
                    // Revert on failure
                    _uiState.update { it.copy(userRating = previous) }
                }
            }
        }
    }

    fun selectVersion(index: Int) {
        _uiState.update {
            it.copy(
                selectedVersionIndex = index,
                selectedAudioIndex = 0,
                selectedSubtitleIndex = -1,
                hasExplicitVersionSelection = true,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
            )
        }
        // Restore any saved audio/subtitle override for the newly-selected file.
        seedPersistedTrackSelection()
    }

    /** Back to Auto — clears the version override and, like [selectVersion],
     *  the file-specific audio/subtitle overrides with it. */
    fun selectAutoVersion() {
        _uiState.update {
            it.copy(
                selectedVersionIndex = 0,
                selectedAudioIndex = 0,
                selectedSubtitleIndex = -1,
                hasExplicitVersionSelection = false,
                hasExplicitAudioSelection = false,
                hasExplicitSubtitleSelection = false,
            )
        }
        seedPersistedTrackSelection()
    }

    fun selectAudioTrack(index: Int) {
        _uiState.update {
            it.copy(
                selectedAudioIndex = index,
                hasExplicitAudioSelection = true,
            )
        }
        persistTrackSelection()
    }

    /** Back to Auto — playback falls through to the file's default track. */
    fun selectAutoAudioTrack() {
        _uiState.update {
            it.copy(
                selectedAudioIndex = 0,
                hasExplicitAudioSelection = false,
            )
        }
        persistTrackSelection()
    }

    fun selectSubtitle(index: Int) {
        _uiState.update {
            it.copy(
                selectedSubtitleIndex = index,
                hasExplicitSubtitleSelection = true,
            )
        }
        persistTrackSelection()
    }

    /** Back to Auto — distinct from an explicit -1 ("Off") selection. */
    fun selectAutoSubtitle() {
        _uiState.update {
            it.copy(
                selectedSubtitleIndex = -1,
                hasExplicitSubtitleSelection = false,
            )
        }
        persistTrackSelection()
    }

    /**
     * Persist the current audio/subtitle override for the selected version's
     * file, so it survives leaving and re-opening the detail page (and lines up
     * with the player, which records the same fingerprints against the same
     * port). Auto (no explicit pick) writes null to clear any prior override;
     * an explicit "Off" writes the shared off sentinel; a concrete pick writes
     * the catalog track's fingerprint. Keyed on (contentId, fileId) — different
     * versions carry independent selections, matching [selectVersion]'s reset.
     */
    private fun persistTrackSelection() {
        val state = _uiState.value
        val detail = state.detail ?: return
        val version = detail.versions.getOrNull(state.selectedVersionIndex) ?: return
        val subtitleFingerprint = when {
            !state.hasExplicitSubtitleSelection -> null
            state.selectedSubtitleIndex == -1 -> SUBTITLE_OFF_FINGERPRINT
            else -> version.subtitleTracks.orEmpty()
                .getOrNull(state.selectedSubtitleIndex)
                ?.let(::subtitleTrackFingerprint)
        }
        val audioFingerprint = when {
            !state.hasExplicitAudioSelection -> null
            else -> version.audioTracks.orEmpty()
                .getOrNull(state.selectedAudioIndex)
                ?.let(::audioTrackFingerprint)
        }
        viewModelScope.launch {
            userItemState.recordSubtitleTrackSelection(detail.contentId, version.fileId, subtitleFingerprint)
            userItemState.recordAudioTrackSelection(detail.contentId, version.fileId, audioFingerprint)
        }
    }

    /**
     * Seed the audio/subtitle selectors from a previously persisted override
     * for the selected version's file, so re-opening the detail page restores
     * what the user last chose. Fingerprints map back to the catalog list via
     * the shared resolvers (Off -> -1). Only applies a dimension when a saved
     * fingerprint actually matches a current track, leaving Auto otherwise.
     */
    private fun seedPersistedTrackSelection() {
        val state = _uiState.value
        val detail = state.detail ?: return
        val version = detail.versions.getOrNull(state.selectedVersionIndex) ?: return
        viewModelScope.launch {
            val saved = userItemState.localTrackSelection(detail.contentId, version.fileId) ?: return@launch
            val subtitleOrdinal = resolveSubtitleTrackOrdinal(
                version.subtitleTracks.orEmpty(),
                saved.subtitleFingerprint,
            )
            val audioOrdinal = resolveAudioTrackOrdinal(
                version.audioTracks.orEmpty(),
                saved.audioFingerprint,
            )
            if (subtitleOrdinal == null && audioOrdinal == null) return@launch
            _uiState.update {
                // The ordinals were resolved against `version`; if the user
                // switched versions while this suspended, they'd index into the
                // wrong track list — leave the new version untouched.
                if (it.detail?.versions?.getOrNull(it.selectedVersionIndex)?.fileId != version.fileId) {
                    return@update it
                }
                // Don't clobber a pick the user already made this session.
                it.copy(
                    selectedSubtitleIndex = if (it.hasExplicitSubtitleSelection) it.selectedSubtitleIndex
                    else subtitleOrdinal ?: it.selectedSubtitleIndex,
                    hasExplicitSubtitleSelection = it.hasExplicitSubtitleSelection || subtitleOrdinal != null,
                    selectedAudioIndex = if (it.hasExplicitAudioSelection) it.selectedAudioIndex
                    else audioOrdinal ?: it.selectedAudioIndex,
                    hasExplicitAudioSelection = it.hasExplicitAudioSelection || audioOrdinal != null,
                )
            }
        }
    }

    /**
     * Fire (or auto-fire once, when [auto] is set) the description
     * translation for the loaded item, then poll detail until the server
     * clears `pending_translation_language` — the refetch delivers the
     * translated overview into uiState.
     */
    fun translateDescription(auto: Boolean = false) {
        val detail = _uiState.value.detail ?: return
        val target = detail.pendingTranslationLanguage ?: return
        if (auto) {
            if (!descriptionTranslation.shouldAutoFire(detail.contentId, target)) return
            descriptionTranslation.markAutoFired(detail.contentId, target)
        }
        descriptionTranslation.resetFailure()
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            descriptionTranslation.translate(
                contentId = detail.contentId,
                targetLanguage = target,
                refetchPendingLanguage = { owner ->
                    when (val result = metadataAiRepository.refreshDetail(detail.contentId, owner)) {
                        is ApiResult.Success -> {
                            val refreshed = withLocalProgress(result.data)
                            if (metadataAiRepository.isCurrent(owner) && _uiState.value.detail?.contentId == detail.contentId) {
                                _uiState.update { it.copy(detail = refreshed) }
                                refreshed.pendingTranslationLanguage
                            } else target
                        }
                        else -> target // transient refetch failure: keep polling
                    }
                },
                onTranslated = { },
            )
        }
    }

    /**
     * Toggles the watchlist state for this item.
     */
    fun toggleWatchlist() {
        val intent = personalDataRepository.memberships.begin(contentId, org.siloserver.silo.repository.port.MembershipPort.Kind.WATCHLIST, !uiState.value.isInWatchlist)
        viewModelScope.launch { personalDataRepository.memberships.perform(intent) }
    }

    fun toggleWatched() {
        val currentDetail = _uiState.value.detail ?: return
        val previous = currentDetail.userData
        val target = previous?.played != true
        val generation = ++watchedMutationGeneration
        val isSeries = currentDetail.type == "series"
        if (isSeries) seasonsRefreshGeneration++
        val episodeGenerationsAtStart = episodeWatchedMutationGenerations.toMap()
        val admittedAtMs = System.currentTimeMillis()
        updateOwnUserData { it.withPlayed(target) }
        val writeIntent = personalDataRepository.beginWatched(contentId, target)
        viewModelScope.launch {
            val writeResult = personalDataRepository.performPersonalWrite(writeIntent)
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            when (writeResult) {
                // The server applied a series change to every episode; re-read
                // the seasons and episodes so their checkmarks follow.
                is ApiResult.Success -> if (isSeries) {
                    // Episodes written on their own since this began keep that state:
                    // any successful write since, or a newer write that has not failed.
                    val changedSince = episodeWatchedMutationGenerations
                        .filter { (id, generation) ->
                            (succeededEpisodeWatchedGenerations[id] ?: 0) > (episodeGenerationsAtStart[id] ?: 0) ||
                                (episodeGenerationsAtStart[id] != generation && failedEpisodeWatchedGenerations[id] != generation)
                        }
                        .keys
                    updateSeasonPlayedState(seasonNumber = null, played = target, skipEpisodeIds = changedSince)
                    val loaded = _uiState.value.episodesBySeason
                    userItemState.clearLocalPlaybackProgressBefore(
                        loaded.values.flatten().map { it.contentId },
                        admittedAtMs,
                        writeIntent.identityGeneration,
                    )
                    refreshAfterSeasonWatchedChange(currentDetail.contentId, changedSeasonNumber = null)
                    // Seasons not loaded yet are read after the visible refresh starts.
                    val unloaded = _uiState.value.seasons.map { it.seasonNumber }.filter { it !in loaded }
                    userItemState.clearLocalPlaybackProgressBefore(
                        unloaded.flatMap { seasonEpisodeIds(currentDetail.contentId, it) },
                        admittedAtMs,
                        writeIntent.identityGeneration,
                    )
                }
                else -> if (generation == watchedMutationGeneration) {
                    if (isSeries) failedSeriesWatchedGeneration = generation
                    updateOwnUserData { it.withoutPlayed(target, previous) }
                }
            }
        }
    }

    /**
     * Marks every episode of [season] from its chip or the series overflow
     * menu. The server applies the change to the season's episodes.
     */
    fun setSeasonWatched(season: Season, watched: Boolean) {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (detail.type != "series") return
        val seriesId = detail.contentId
        val seasonNumber = season.seasonNumber
        val previousSeason = state.seasons.firstOrNull { it.seasonNumber == seasonNumber } ?: return
        val previousEpisodes = state.episodesBySeason[seasonNumber]
        val episodeGenerationsAtStart = episodeWatchedMutationGenerations.toMap()
        val admittedAtMs = System.currentTimeMillis()

        val seriesGenerationAtStart = watchedMutationGeneration
        val generation = (seasonWatchedMutationGenerations[seasonNumber] ?: 0) + 1
        seasonWatchedMutationGenerations[seasonNumber] = generation
        // A season list read already in flight predates this change.
        seasonsRefreshGeneration++
        updateSeasonPlayedState(seasonNumber, watched)
        val writeIntent = personalDataRepository.beginWatched(season.contentId, watched)
        viewModelScope.launch {
            val writeResult = personalDataRepository.performPersonalWrite(writeIntent)
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            if (seasonWatchedMutationGenerations[seasonNumber] != generation) return@launch
            when (writeResult) {
                is ApiResult.Success -> {
                    // Both lists in case the cached page changed while the write was pending.
                    val episodeIds = (_uiState.value.episodesBySeason[seasonNumber].orEmpty() + previousEpisodes.orEmpty())
                        .map { it.contentId }
                        .distinct()
                        .ifEmpty { seasonEpisodeIds(seriesId, seasonNumber) }
                    userItemState.clearLocalPlaybackProgressBefore(episodeIds, admittedAtMs, writeIntent.identityGeneration)
                    refreshAfterSeasonWatchedChange(seriesId, seasonNumber)
                }
                // A series write since this one began (and not failed) also
                // covers this season; the snapshot is stale, so re-read the server.
                else -> if (watchedMutationGeneration != seriesGenerationAtStart &&
                    failedSeriesWatchedGeneration != watchedMutationGeneration
                ) {
                    refreshAfterSeasonWatchedChange(seriesId, changedSeasonNumber = null)
                } else {
                    restoreSeasonPlayedState(
                        previousSeason,
                        previousEpisodes,
                        episodeGenerationsAtStart,
                    )
                }
            }
        }
    }

    /**
     * Applies a season ([seasonNumber]) or whole-series (null) watched change to
     * the loaded seasons and episodes. Like the server, both marking and
     * unmarking clear an episode's resume point, so a failed follow-up read
     * cannot leave Resume offering the old position.
     */
    private fun updateSeasonPlayedState(
        seasonNumber: Int?,
        played: Boolean,
        skipEpisodeIds: Set<String> = emptySet(),
    ) {
        fun EpisodeListItem.updated(): EpisodeListItem = if (contentId in skipEpisodeIds) this else copy(
            userData = (userData ?: LeafItemUserData()).copy(
                played = played,
                isInProgress = false,
                positionSeconds = null,
            ),
        )
        fun matches(number: Int) = seasonNumber == null || number == seasonNumber
        _uiState.update { state ->
            state.copy(
                seasons = state.seasons.map { season ->
                    if (!matches(season.seasonNumber)) season else season.copy(
                        userData = (season.userData ?: SeasonUserData()).copy(played = played),
                    )
                },
                episodes = if (matches(state.selectedSeasonNumber)) {
                    state.episodes.map { it.updated() }
                } else {
                    state.episodes
                },
                episodesBySeason = state.episodesBySeason.mapValues { (number, episodes) ->
                    if (matches(number)) episodes.map { it.updated() } else episodes
                },
            )
        }
    }

    private fun restoreSeasonPlayedState(
        previousSeason: Season,
        previousEpisodes: List<EpisodeListItem>?,
        episodeGenerationsAtStart: Map<String, Int>,
    ) {
        val seasonNumber = previousSeason.seasonNumber
        // An episode marked on its own while the season write was pending keeps
        // that newer state; only the season write's own change is undone.
        val previousById = previousEpisodes.orEmpty()
            .filter {
                val latest = episodeWatchedMutationGenerations[it.contentId]
                // No episode write admitted since the season write began: restore.
                // Otherwise restore only if none of those writes succeeded and the
                // latest failed, having rolled back to this season's optimistic state.
                val atStart = episodeGenerationsAtStart[it.contentId] ?: 0
                latest == episodeGenerationsAtStart[it.contentId] ||
                    ((succeededEpisodeWatchedGenerations[it.contentId] ?: 0) <= atStart &&
                        failedEpisodeWatchedGenerations[it.contentId] == latest)
            }
            .associateBy { it.contentId }
        fun List<EpisodeListItem>.restored() = map { previousById[it.contentId] ?: it }
        _uiState.update { state ->
            val episodesBySeason = if (previousEpisodes != null) {
                state.episodesBySeason.mapValues { (number, episodes) ->
                    if (number == seasonNumber) episodes.restored() else episodes
                }
            } else {
                state.episodesBySeason - seasonNumber
            }
            state.copy(
                seasons = state.seasons.map { if (it.seasonNumber == seasonNumber) previousSeason else it },
                episodes = if (state.selectedSeasonNumber == seasonNumber) state.episodes.restored() else state.episodes,
                episodesBySeason = episodesBySeason,
            )
        }
    }

    /** Episode ids of a season whose page is not loaded; empty if the read fails. */
    private suspend fun seasonEpisodeIds(seriesId: String, seasonNumber: Int): List<String> =
        when (val result = catalogRepository.getEpisodes(seriesId, seasonNumber, libraryId = libraryId, fresh = true)) {
            is ApiResult.Success -> result.data.episodes.map { it.contentId }
            is ApiResult.Error,
            is ApiResult.NetworkError -> emptyList()
        }

    /**
     * Re-reads what a season or series watched change affected: the season
     * list (season watched state), the series hero, and the visible season's
     * episodes. Other seasons' cached episodes are dropped when they may be
     * stale so selecting them later loads fresh checkmarks.
     */
    private fun refreshAfterSeasonWatchedChange(seriesId: String, changedSeasonNumber: Int?) {
        _uiState.update { state ->
            state.copy(
                episodesBySeason = state.episodesBySeason.filterKeys { number ->
                    // The loaded page is the fallback if the selected season's reload fails.
                    number == state.selectedSeasonNumber || number == loadedSeasonNumber ||
                        (changedSeasonNumber != null && number != changedSeasonNumber)
                },
            )
        }
        // Re-reads the series hero, the season list, and the visible episodes.
        quietRefresh(afterWatchedChange = true)
    }

    /**
     * Replaces the season list (and its watched state) without touching the
     * selection. Only the latest request publishes, and a season watched
     * change bumps the generation so an older read cannot undo it.
     */
    private fun refreshSeasonsQuietly(seriesId: String, fresh: Boolean = false) {
        val generation = ++seasonsRefreshGeneration
        viewModelScope.launch {
            when (val result = catalogRepository.getSeasons(seriesId, libraryId = libraryId, fresh = fresh)) {
                is ApiResult.Success -> {
                    val seasons = result.data.seasons.sortedForDisplay()
                    _uiState.update { state ->
                        if (generation != seasonsRefreshGeneration || state.detail?.contentId != seriesId ||
                            seasons.isEmpty()
                        ) state else state.copy(seasons = seasons)
                    }
                }
                // Quiet refresh: on failure keep the season state on screen.
                is ApiResult.Error,
                is ApiResult.NetworkError -> Unit
            }
        }
    }

    /** Marks one episode from the in-page rail without navigating away. */
    fun setEpisodeWatched(episodeContentId: String, watched: Boolean) {
        val state = _uiState.value
        val previous = (state.episodes.firstOrNull { it.contentId == episodeContentId }
            ?: state.episodesBySeason.values.asSequence()
                .flatten()
                .firstOrNull { it.contentId == episodeContentId })
            ?.userData
        if ((previous?.played ?: false) == watched) return

        val generation = (episodeWatchedMutationGenerations[episodeContentId] ?: 0) + 1
        episodeWatchedMutationGenerations[episodeContentId] = generation
        updateEpisodeUserData(episodeContentId) { it.withPlayed(watched) }
        val writeIntent = personalDataRepository.beginWatched(episodeContentId, watched)
        viewModelScope.launch {
            val writeResult = personalDataRepository.performPersonalWrite(writeIntent)
            if (!personalDataRepository.isCurrent(writeIntent)) return@launch
            when (writeResult) {
                // The season's watched state may have flipped with this episode.
                is ApiResult.Success -> {
                    succeededEpisodeWatchedGenerations[episodeContentId] = generation
                    _uiState.value.detail
                        ?.takeIf { it.type == "series" }
                        ?.let { refreshSeasonsQuietly(it.contentId) }
                }
                else -> if (episodeWatchedMutationGenerations[episodeContentId] == generation) {
                    failedEpisodeWatchedGenerations[episodeContentId] = generation
                    updateEpisodeUserData(episodeContentId) { it.withoutPlayed(watched, previous) }
                }
            }
        }
    }

    /**
     * Watched and unwatched both reset resume progress, as the server does, so
     * spoiler protection sees the reset episode as not started.
     */
    private fun LeafItemUserData?.withPlayed(played: Boolean): LeafItemUserData =
        (this ?: LeafItemUserData()).copy(played = played, isInProgress = false, positionSeconds = null)

    /** Undoes a failed [withPlayed] only where it still shows; a newer reload wins. */
    private fun LeafItemUserData?.withoutPlayed(played: Boolean, previous: LeafItemUserData?): LeafItemUserData? {
        if (this == null || this != withPlayed(played)) return this
        return copy(
            played = previous?.played ?: false,
            isInProgress = previous?.isInProgress,
            positionSeconds = previous?.positionSeconds,
        )
    }

    private fun updateEpisodeUserData(episodeContentId: String, transform: (LeafItemUserData?) -> LeafItemUserData?) {
        fun EpisodeListItem.updated(): EpisodeListItem =
            if (contentId != episodeContentId) this else copy(userData = transform(userData))

        _uiState.update { state ->
            val selectedDetail = state.selectedEpisodeDetail?.let { episodeDetail ->
                if (episodeDetail.contentId != episodeContentId) episodeDetail
                else episodeDetail.copy(userData = transform(episodeDetail.userData))
            }
            val ownDetail = state.detail?.let { itemDetail ->
                if (itemDetail.contentId != episodeContentId) itemDetail
                else itemDetail.copy(userData = transform(itemDetail.userData))
            }
            state.copy(
                detail = ownDetail,
                episodes = state.episodes.map { it.updated() },
                episodesBySeason = state.episodesBySeason.mapValues { (_, episodes) ->
                    episodes.map { it.updated() }
                },
                selectedEpisodeDetail = selectedDetail,
            )
        }
    }

    private fun updateOwnUserData(transform: (LeafItemUserData?) -> LeafItemUserData?) {
        _uiState.update { state ->
            val detail = state.detail ?: return@update state
            state.copy(detail = detail.copy(userData = transform(detail.userData)))
        }
    }
}
