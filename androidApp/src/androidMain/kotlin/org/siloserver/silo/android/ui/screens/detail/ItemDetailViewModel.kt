package org.siloserver.silo.android.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.initialSeasonDisplayPlan
import org.siloserver.silo.model.catalog.sortedForDisplay
import org.siloserver.silo.model.download.DownloadCapability
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.ApiResult
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
import kotlinx.coroutines.flow.combine
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
    val similarItems: List<ItemDetail> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val selectedSeasonNumber: Int = 1,
    val episodes: List<EpisodeListItem> = emptyList(),
    /** Episode selected inside a series page. Its full detail powers the hero play target and selectors. */
    val selectedEpisodeContentId: String? = null,
    val selectedEpisodeDetail: ItemDetail? = null,
    val isLoadingSelectedEpisodeDetail: Boolean = false,
    /** Parent-series portrait art used when an episode's own artwork is a wide still. */
    val episodeSeriesPosterUrl: String? = null,
    val episodeSeriesPosterThumbhash: String? = null,
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
    metadataAiRepository: MetadataAiRepository,
    savedStateHandle: SavedStateHandle,
    private val userItemState: org.siloserver.silo.repository.port.UserItemStatePort =
        org.siloserver.silo.repository.port.NoOpUserItemStatePort,
) : ViewModel() {

    private val contentId: String = savedStateHandle.get<String>("contentId") ?: ""
    private val initialSeasonNumber: Int? =
        savedStateHandle.get<String>("seasonNumber")?.toIntOrNull()
    private val initialEpisodeContentId: String? =
        savedStateHandle.get<String>("episodeContentId")?.takeIf { it.isNotBlank() }
    private var pendingInitialEpisodeContentId: String? = initialEpisodeContentId

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()
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

    private val descriptionTranslation = DescriptionTranslationController(
        repository = metadataAiRepository,
        delayMs = { kotlinx.coroutines.delay(it) },
    )
    val translationPhase: StateFlow<DescriptionTranslationPhase> = descriptionTranslation.phase

    init {
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
                    startDownload(version, displayTitle, downloadQuality)
                }
            }
            DetailDownloadTapAction.Start -> viewModelScope.launch {
                startDownload(version, displayTitle, downloadQuality)
            }
        }
    }

    /** True = download started, false = registration failed. Drives the
     *  download-start haptic (the only haptic iOS mobile has). */
    private val _downloadStartEvents = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val downloadStartEvents: kotlinx.coroutines.flow.SharedFlow<Boolean> = _downloadStartEvents

    private suspend fun startDownload(
        version: FileVersion,
        displayTitle: String,
        downloadQuality: DownloadQuality? = null,
    ) {
        // wifiOnly read from per-profile PlayerSettingsStore inside
        // DownloadEnqueuer.start; default true.
        val result = downloadEnqueuer.start(
            contentId = contentId,
            fileId = version.fileId,
            displayTitle = displayTitle,
            downloadQualityOverride = downloadQuality,
        )
        _downloadStartEvents.emit(result is ApiResult.Success)
    }

    /**
     * Per-episode download tap. Picks the best file for the episode (first
     * entry in the server-sorted files list) and queues it. If the episode
     * has no files (rare — orphaned record), no-ops.
     */
    fun onEpisodeDownloadTapped(
        episode: EpisodeListItem,
        downloadQuality: DownloadQuality? = null,
    ) {
        val fileId = episode.files.firstOrNull()?.fileId ?: return
        val detail = _uiState.value.detail ?: return
        // Branch on current state like the movie/audiobook path: a downloaded
        // episode is a no-op (manage via the Downloads tab); an in-flight one
        // cancels; otherwise start. Previously it always re-enqueued.
        val existing = downloads.value.firstOrNull { it.mediaFileId == fileId }
        when (detailDownloadTapAction(existing?.statusEnum(), forceRedownloadMissingLocal = false)) {
            DetailDownloadTapAction.Ignore -> Unit
            DetailDownloadTapAction.Cancel -> existing?.let { record ->
                val cancelScope = downloadEnqueuer.captureCancelScope()
                viewModelScope.launch {
                    downloadEnqueuer.cancel(record.id, fileId, cancelScope)
                    downloadsRepository.delete(record.id)
                }
            }
            DetailDownloadTapAction.Start, DetailDownloadTapAction.ReplaceAndStart -> viewModelScope.launch {
                // Episode pages load the episode itself as `detail`, so the
                // parent reference supplies the series id/title for grouping.
                downloadEnqueuer.startEpisode(
                    seriesContentId = if (detail.type == "series") {
                        detail.contentId
                    } else {
                        detail.seriesId ?: detail.contentId
                    },
                    episodeContentId = episode.contentId,
                    fileId = fileId,
                    seriesTitle = if (detail.type == "series") {
                        detail.title
                    } else {
                        detail.seriesTitle ?: detail.title
                    },
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    posterUrl = detail.posterUrl,
                    downloadQualityOverride = downloadQuality,
                )
            }
        }
    }

    /** Series-level "Download series" — uses the server's batch endpoint
     *  (one POST → N records sharing a batchId). */
    fun onSeriesDownloadTapped(downloadQuality: DownloadQuality? = null) {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            downloadEnqueuer.startSeries(
                seriesContentId = detail.contentId,
                downloadQualityOverride = downloadQuality,
            )
        }
    }

    /** Per-season "Download season" — server has no season-batch endpoint
     *  so this loops POST-per-episode locally inside the enqueuer. */
    fun onSeasonDownloadTapped(
        seasonNumber: Int,
        downloadQuality: DownloadQuality? = null,
    ) {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            downloadEnqueuer.startSeason(
                seriesContentId = detail.contentId,
                seasonNumber = seasonNumber,
                downloadQualityOverride = downloadQuality,
            )
        }
    }

    init {
        if (contentId.isNotBlank()) {
            loadDetail()
            loadUserState()
        }
    }

    fun loadDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            // Start the live request immediately. The durable cache read can
            // still paint an instant first frame, but it no longer delays the
            // network request that supplies fresh movie/series metadata.
            val liveDetail = async { catalogRepository.getItemDetail(contentId) }
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
                    viewModelScope.launch { loadSimilar(detail) }
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

    private suspend fun loadSimilar(detail: ItemDetail) {
        if (detail.type == "episode" || _uiState.value.similarItems.isNotEmpty()) return

        val scored = when (
            val result = recommendationRepository.getSimilar(detail.contentId, limit = 12)
        ) {
            is ApiResult.Success -> result.data.items
            else -> return
        }
        if (scored.isEmpty()) return

        val items = coroutineScope {
            scored
                .map { ref ->
                    async {
                        when (val result = catalogRepository.getItemDetail(ref.mediaItemId)) {
                            is ApiResult.Success -> result.data
                            else -> null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        if (items.isNotEmpty()) {
            _uiState.update { it.copy(similarItems = items) }
        }
    }

    /**
     * Quiet refresh for returning to an already-loaded detail screen (e.g.
     * backing out of the player): re-reads userData so the Play button's
     * resume label and the episode list reflect the session that just ended.
     * Deliberately NOT [loadDetail] — no loading flashes, and the user's
     * season selection is preserved.
     */
    fun refreshOnReturn() {
        val current = _uiState.value.detail ?: return
        viewModelScope.launch {
            // Local overlay first: the player's final position write is already
            // on disk, so the label corrects before the server round-trip.
            val overlaid = withLocalProgress(current)
            if (overlaid != current) {
                _uiState.update { it.copy(detail = overlaid) }
            }
            when (val result = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val detail = withLocalProgress(result.data)
                    _uiState.update {
                        it.copy(
                            detail = detail,
                            userRating = detail.userRating,
                        )
                    }
                }
                // Quiet refresh: on failure keep showing what we have.
                is ApiResult.Error,
                is ApiResult.NetworkError -> Unit
            }
        }
        if (current.type == "series") {
            loadEpisodes(
                current.contentId,
                _uiState.value.selectedSeasonNumber,
                forceRefresh = true,
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
        val cached = catalogRepository.getCachedItemDetail(contentId)?.let { withLocalProgress(it) } ?: return
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
            // Local optimistic favorite wins and is applied IMMEDIATELY (isFavorite
            // is a network-only read — stale/unavailable offline or right after an
            // offline toggle; don't let a slow/failed network call delay or clobber it).
            val localFavorite = runCatching {
                userItemState.localContentStates(listOf(contentId))[contentId]?.favorite
            }.getOrNull()
            if (localFavorite != null) {
                _uiState.update { it.copy(isFavorite = localFavorite) }
            } else {
                val favResult = personalDataRepository.isFavorite(contentId)
                if (favResult is ApiResult.Success) {
                    _uiState.update { it.copy(isFavorite = favResult.data) }
                }
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
                catalogRepository.getSeasonsForPrefetch(seriesId)
            } else {
                catalogRepository.getSeasons(seriesId)
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
                when (val r = catalogRepository.getEpisodes(seriesId, season.seasonNumber)) {
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
            when (val result = catalogRepository.getSeasons(seriesId)) {
                is ApiResult.Success -> {
                    val seasons = result.data.seasons.sortedForDisplay()
                    _uiState.update { it.copy(seasons = seasons) }
                }
                else -> { /* Season load failure is non-critical */ }
            }

            // Resolve seasons before the series fallback. Otherwise a cache-fast
            // series poster can paint for a frame and then be replaced by the
            // selected season poster when its request completes.
            when (val result = catalogRepository.getItemDetailForPrefetch(seriesId)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            episodeSeriesPosterUrl = result.data.posterUrl,
                            episodeSeriesPosterThumbhash = result.data.posterThumbhash,
                        )
                    }
                }
                else -> { /* Series poster fallback is optional. */ }
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
                catalogRepository.getItemDetailForPrefetch(contentId)
            } else {
                catalogRepository.getItemDetail(contentId)
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
                catalogRepository.getEpisodesForPrefetch(seriesId, seasonNumber)
            } else {
                catalogRepository.getEpisodes(seriesId, seasonNumber)
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
        viewModelScope.launch {
            val current = _uiState.value.isFavorite
            val newState = !current
            // Optimistic update
            _uiState.update { it.copy(isFavorite = newState) }
            when (personalDataRepository.toggleFavorite(contentId, newState)) {
                is ApiResult.Success -> { /* already updated */ }
                else -> {
                    // Revert on failure
                    _uiState.update { it.copy(isFavorite = current) }
                }
            }
        }
    }

    /**
     * Sets the user's star rating, clamped to 1..5. Mirrors the
     * [toggleFavorite] optimistic-update pattern: update state, call the
     * repository, revert on any non-Success result.
     */
    fun setRating(stars: Int) {
        val target = stars.coerceIn(1, 5)
        viewModelScope.launch {
            val previous = _uiState.value.userRating
            // Optimistic update
            _uiState.update { it.copy(userRating = target) }
            when (personalDataRepository.setRating(contentId, target)) {
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
        viewModelScope.launch {
            val previous = _uiState.value.userRating ?: return@launch
            // Optimistic update
            _uiState.update { it.copy(userRating = null) }
            when (personalDataRepository.deleteRating(contentId)) {
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

    /**
     * Toggles the watchlist state for this item.
     */
    fun toggleWatchlist() {
        viewModelScope.launch {
            val current = _uiState.value.isInWatchlist
            val newState = !current
            // Optimistic update
            _uiState.update { it.copy(isInWatchlist = newState) }
            when (personalDataRepository.toggleWatchlist(contentId, newState)) {
                is ApiResult.Success -> { /* already updated */ }
                else -> {
                    // Revert on failure
                    _uiState.update { it.copy(isInWatchlist = current) }
                }
            }
        }
    }

    fun toggleWatched() {
        val currentDetail = _uiState.value.detail ?: return
        val current = currentDetail.userData?.played == true
        val target = !current
        val generation = ++watchedMutationGeneration
        updatePlayedState(target)
        viewModelScope.launch {
            when (personalDataRepository.setWatched(contentId, target)) {
                is ApiResult.Success -> { /* already updated */ }
                else -> if (generation == watchedMutationGeneration) updatePlayedState(current)
            }
        }
    }

    /** Marks one episode from the in-page rail without navigating away. */
    fun setEpisodeWatched(episodeContentId: String, watched: Boolean) {
        val state = _uiState.value
        val previous = state.episodes.firstOrNull { it.contentId == episodeContentId }
            ?.userData?.played
            ?: state.episodesBySeason.values.asSequence()
                .flatten()
                .firstOrNull { it.contentId == episodeContentId }
                ?.userData?.played
            ?: false
        if (previous == watched) return

        val generation = (episodeWatchedMutationGenerations[episodeContentId] ?: 0) + 1
        episodeWatchedMutationGenerations[episodeContentId] = generation
        updateEpisodePlayedState(episodeContentId, watched)
        viewModelScope.launch {
            when (personalDataRepository.setWatched(episodeContentId, watched)) {
                is ApiResult.Success -> Unit
                else -> if (episodeWatchedMutationGenerations[episodeContentId] == generation) {
                    updateEpisodePlayedState(episodeContentId, previous)
                }
            }
        }
    }

    private fun updateEpisodePlayedState(episodeContentId: String, played: Boolean) {
        fun EpisodeListItem.updated(): EpisodeListItem =
            if (contentId != episodeContentId) this else copy(
                userData = (userData ?: LeafItemUserData()).copy(played = played),
            )

        _uiState.update { state ->
            val selectedDetail = state.selectedEpisodeDetail?.let { episodeDetail ->
                if (episodeDetail.contentId != episodeContentId) episodeDetail else episodeDetail.copy(
                    userData = (episodeDetail.userData ?: LeafItemUserData()).copy(played = played),
                )
            }
            val ownDetail = state.detail?.let { itemDetail ->
                if (itemDetail.contentId != episodeContentId) itemDetail else itemDetail.copy(
                    userData = (itemDetail.userData ?: LeafItemUserData()).copy(played = played),
                )
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

    private fun updatePlayedState(played: Boolean) {
        _uiState.update { state ->
            val detail = state.detail ?: return@update state
            val userData = detail.userData ?: LeafItemUserData()
            state.copy(detail = detail.copy(userData = userData.copy(played = played)))
        }
    }
}
