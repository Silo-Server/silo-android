package com.continuum.app.android.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.common.downloads.DownloadEnqueuer
import com.continuum.app.model.catalog.EpisodeListItem
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.catalog.LeafItemUserData
import com.continuum.app.model.catalog.Season
import com.continuum.app.model.catalog.sortedForDisplay
import com.continuum.app.model.download.DownloadRecord
import com.continuum.app.model.download.statusEnum
import com.continuum.app.network.ApiResult
import com.continuum.app.model.catalog.isBookLikeItemType
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.repository.DownloadsRepository
import com.continuum.app.repository.EbookReaderRepository
import com.continuum.app.repository.PersonalDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI state for the item detail screen.
 */
data class ItemDetailUiState(
    val isLoading: Boolean = true,
    val detail: ItemDetail? = null,
    val seasons: List<Season> = emptyList(),
    val selectedSeasonNumber: Int = 1,
    val episodes: List<EpisodeListItem> = emptyList(),
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
    savedStateHandle: SavedStateHandle,
    private val userItemState: com.continuum.app.repository.port.UserItemStatePort =
        com.continuum.app.repository.port.NoOpUserItemStatePort,
) : ViewModel() {

    private val contentId: String = savedStateHandle.get<String>("contentId") ?: ""
    private val initialSeasonNumber: Int? =
        savedStateHandle.get<String>("seasonNumber")?.toIntOrNull()

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()
    private var episodeLoadJob: Job? = null
    private var allEpisodeFileIdsJob: Job? = null

    /** Live mirror of the shared records flow; the screen reads this to
     *  derive per-version download state (isDownloaded / progress). */
    val downloads: StateFlow<List<DownloadRecord>> = downloadsRepository.records
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Refresh once so server-side records are visible when the user
        // lands on the detail screen (e.g., to show 'Downloaded' on a file
        // that was downloaded in a previous app session).
        viewModelScope.launch { downloadsRepository.refresh() }
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
                    downloadEnqueuer.cancel(record.id)
                    viewModelScope.launch { downloadsRepository.delete(record.id) }
                }
            }
            DetailDownloadTapAction.Ignore -> Unit  // Manage via Downloads tab.
            DetailDownloadTapAction.ReplaceAndStart -> viewModelScope.launch {
                val staleRecord = existing
                if (staleRecord == null || downloadsRepository.delete(staleRecord.id) is ApiResult.Success) {
                    startDownload(version, displayTitle)
                }
            }
            DetailDownloadTapAction.Start -> viewModelScope.launch {
                startDownload(version, displayTitle)
            }
        }
    }

    private suspend fun startDownload(version: FileVersion, displayTitle: String) {
        // wifiOnly read from per-profile PlayerSettingsStore inside
        // DownloadEnqueuer.start; default true.
        downloadEnqueuer.start(
            contentId = contentId,
            fileId = version.fileId,
            displayTitle = displayTitle,
        )
    }

    /**
     * Per-episode download tap. Picks the best file for the episode (first
     * entry in the server-sorted files list) and queues it. If the episode
     * has no files (rare — orphaned record), no-ops.
     */
    fun onEpisodeDownloadTapped(episode: EpisodeListItem) {
        val fileId = episode.files.firstOrNull()?.fileId ?: return
        val detail = _uiState.value.detail ?: return
        // Branch on current state like the movie/audiobook path: a downloaded
        // episode is a no-op (manage via the Downloads tab); an in-flight one
        // cancels; otherwise start. Previously it always re-enqueued.
        val existing = downloads.value.firstOrNull { it.mediaFileId == fileId }
        when (detailDownloadTapAction(existing?.statusEnum(), forceRedownloadMissingLocal = false)) {
            DetailDownloadTapAction.Ignore -> Unit
            DetailDownloadTapAction.Cancel -> existing?.let { record ->
                downloadEnqueuer.cancel(record.id)
                viewModelScope.launch { downloadsRepository.delete(record.id) }
            }
            DetailDownloadTapAction.Start, DetailDownloadTapAction.ReplaceAndStart -> viewModelScope.launch {
                downloadEnqueuer.startEpisode(
                    seriesContentId = detail.contentId,
                    episodeContentId = episode.contentId,
                    fileId = fileId,
                    seriesTitle = detail.title,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    posterUrl = detail.posterUrl,
                )
            }
        }
    }

    /** Series-level "Download series" — uses the server's batch endpoint
     *  (one POST → N records sharing a batchId). */
    fun onSeriesDownloadTapped() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch { downloadEnqueuer.startSeries(detail.contentId) }
    }

    /** Per-season "Download season" — server has no season-batch endpoint
     *  so this loops POST-per-episode locally inside the enqueuer. */
    fun onSeasonDownloadTapped(seasonNumber: Int) {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch { downloadEnqueuer.startSeason(detail.contentId, seasonNumber) }
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
            seedCachedDetail()

            when (val result = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val detail = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = detail,
                            userRating = detail.userRating,
                            error = null,
                        )
                    }
                    // For series, load seasons
                    if (detail.type == "series") {
                        loadSeasons(detail.contentId)
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

    private suspend fun seedCachedDetail() {
        val cached = catalogRepository.getCachedItemDetail(contentId) ?: return
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
            when (val result = catalogRepository.getSeasons(seriesId)) {
                is ApiResult.Success -> {
                    val seasons = result.data.seasons.sortedForDisplay()
                    val selectedSeason = seasons.firstOrNull { it.seasonNumber == initialSeasonNumber }
                        ?: seasons.firstOrNull()
                    _uiState.update {
                        it.copy(
                            seasons = seasons,
                            selectedSeasonNumber = selectedSeason?.seasonNumber ?: 1,
                        )
                    }
                    if (selectedSeason != null) {
                        loadEpisodes(
                            seriesId = seriesId,
                            seasonNumber = selectedSeason.seasonNumber,
                            seasonsForDownloadRollup = seasons,
                        )
                    } else {
                        loadAllEpisodeFileIds(seriesId, seasons)
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
        allEpisodeFileIdsJob?.cancel()
        allEpisodeFileIdsJob = viewModelScope.launch {
            val fileIds = mutableListOf<Int>()
            seedEpisodes.forEach { ep -> ep.files.firstOrNull()?.fileId?.let { fileIds += it } }
            val canSkipSeedSeason = skipSeasonNumber != null && seedEpisodes.isNotEmpty()
            if (fileIds.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        allEpisodeFileIds = fileIds.distinct(),
                        allEpisodeIdsComplete = canSkipSeedSeason && seasons.size <= 1,
                    )
                }
            }

            // This roll-up is only for the detail download badge. Let the selected
            // season render and become interactive before crawling the rest.
            delay(350)

            var complete = true
            for (season in seasons) {
                if (canSkipSeedSeason && season.seasonNumber == skipSeasonNumber) continue
                when (val r = catalogRepository.getEpisodes(seriesId, season.seasonNumber)) {
                    is ApiResult.Success -> r.data.episodes.forEach { ep ->
                        ep.files.firstOrNull()?.fileId?.let { fileIds += it }
                    }
                    // A season we couldn't load means we can't prove series-completeness.
                    else -> complete = false
                }
            }
            _uiState.update {
                it.copy(
                    allEpisodeFileIds = fileIds.distinct(),
                    allEpisodeIdsComplete = complete,
                )
            }
        }
    }

    /**
     * Selects a season and loads its episodes.
     */
    fun selectSeason(seasonNumber: Int) {
        _uiState.update { it.copy(selectedSeasonNumber = seasonNumber) }
        val seriesId = _uiState.value.detail?.contentId ?: return
        loadEpisodes(seriesId, seasonNumber)
    }

    private fun loadEpisodes(
        seriesId: String,
        seasonNumber: Int,
        seasonsForDownloadRollup: List<Season>? = null,
    ) {
        episodeLoadJob?.cancel()
        episodeLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEpisodes = true) }
            when (val result = catalogRepository.getEpisodes(seriesId, seasonNumber)) {
                is ApiResult.Success -> {
                    val episodes = result.data.episodes
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodes = false,
                            episodes = episodes,
                        )
                    }
                    seasonsForDownloadRollup?.let { seasons ->
                        loadAllEpisodeFileIds(
                            seriesId = seriesId,
                            seasons = seasons,
                            seedEpisodes = episodes,
                            skipSeasonNumber = seasonNumber,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(isLoadingEpisodes = false) }
                    seasonsForDownloadRollup?.let { loadAllEpisodeFileIds(seriesId, it) }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingEpisodes = false) }
                    seasonsForDownloadRollup?.let { loadAllEpisodeFileIds(seriesId, it) }
                }
            }
        }
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
    }

    fun selectAudioTrack(index: Int) {
        _uiState.update {
            it.copy(
                selectedAudioIndex = index,
                hasExplicitAudioSelection = true,
            )
        }
    }

    fun selectSubtitle(index: Int) {
        _uiState.update {
            it.copy(
                selectedSubtitleIndex = index,
                hasExplicitSubtitleSelection = true,
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
        updatePlayedState(target)
        viewModelScope.launch {
            when (personalDataRepository.setWatched(contentId, target)) {
                is ApiResult.Success -> { /* already updated */ }
                else -> updatePlayedState(current)
            }
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
