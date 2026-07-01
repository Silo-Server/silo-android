package com.continuum.app.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.common.settings.PlayerSettingsStore
import com.continuum.app.model.catalog.BrowseItem
import com.continuum.app.model.catalog.CastMember
import com.continuum.app.model.catalog.EpisodeListItem
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.catalog.Season
import com.continuum.app.model.catalog.isAudiobookItemType
import com.continuum.app.model.catalog.sortedForDisplay
import com.continuum.app.model.section.SectionItem
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.repository.PersonalDataRepository
import com.continuum.app.tv.ui.util.isTvHiddenMediaType
import com.continuum.app.tv.ui.util.visibleOnTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val seasonsLoading: Boolean = false,
    val episodesLoading: Boolean = false,
    // Version selection for multi-file items.
    val selectedFileId: Int? = null,
    // Pre-playback track selection (null = use server/auto default). Subtitle
    // index -1 means "Off". Reset whenever the version changes since each file
    // has its own track lists.
    val selectedAudioIndex: Int? = null,
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
    val selectedNextUpSubtitleIndex: Int? = null,
    val preferredQuality: String = "auto",
)

/**
 * Drives the enhanced TV item detail screen. Loads the full [ItemDetail] plus
 * the current user's favorite/watchlist state in parallel. For series, pulls
 * seasons once the main detail lands and lazily loads episodes whenever the
 * user switches seasons.
 *
 * Receives `contentId` via Koin `parametersOf()` (see
 * [com.continuum.app.tv.di.androidTvModule]).
 */
class TvItemDetailViewModel(
    private val catalogRepository: CatalogRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val contentId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvItemDetailUiState())
    val uiState: StateFlow<TvItemDetailUiState> = _uiState.asStateFlow()

    init {
        observePreferredQuality()
        if (contentId.isNotBlank()) loadAll()
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

    fun loadAll() {
        viewModelScope.launch {
            runCatching { playerSettingsStore.refreshFromServer() }
        }
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
        val cached = catalogRepository.getCachedItemDetail(contentId) ?: return
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
    }

    private fun loadDetail() {
        viewModelScope.launch {
            when (val result = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    val detail = result.data
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
                    when (detail.type.lowercase()) {
                        "series" -> loadSeasons(seriesContentId = detail.contentId)
                        "season",
                        "episode",
                        -> detail.seriesId?.takeIf { it.isNotBlank() }?.let { seriesId ->
                            loadSeasons(
                                seriesContentId = seriesId,
                                preferredSeasonNumber = detail.seasonNumber?.takeIf { it > 0 },
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
        _uiState.update { it.copy(isTogglingWatched = true, isWatched = target) }
        viewModelScope.launch {
            val result = personalDataRepository.setWatched(contentId, target)
            if (result !is ApiResult.Success) {
                // Roll back on error.
                _uiState.update {
                    it.copy(isTogglingWatched = false, isWatched = !target)
                }
            } else {
                _uiState.update { it.copy(isTogglingWatched = false) }
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
            it.copy(selectedFileId = fileId, selectedAudioIndex = null, selectedSubtitleIndex = null)
        }
    }

    /** Pre-select an audio track for the next Play (index into the version's audioTracks). */
    fun onAudioTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedAudioIndex = index) }
    }

    /** Pre-select a subtitle track for the next Play (-1 = Off, null = auto). */
    fun onSubtitleTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
    }

    fun onSeasonSelected(seasonNumber: Int) {
        if (_uiState.value.selectedSeason == seasonNumber) return
        _uiState.update { it.copy(selectedSeason = seasonNumber) }
        val detail = _uiState.value.detail ?: return
        val seriesContentId = when (detail.type.lowercase()) {
            "series" -> detail.contentId
            "season" -> detail.seriesId
            "episode" -> detail.seriesId
            else -> null
        } ?: return
        loadEpisodes(seriesContentId, seasonNumber)
    }

    private fun loadSeasons(
        seriesContentId: String,
        preferredSeasonNumber: Int? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(seasonsLoading = true) }
            when (val r = catalogRepository.getSeasons(seriesContentId)) {
                is ApiResult.Success -> {
                    val seasons = r.data.seasons.sortedForDisplay()
                    val selectedSeason = preferredSeasonNumber
                        ?.let { seasonNumber -> seasons.firstOrNull { it.seasonNumber == seasonNumber } }
                    val firstRegular = selectedSeason
                        ?: seasons.firstOrNull { !it.isSpecials }
                        ?: seasons.firstOrNull()
                    _uiState.update {
                        it.copy(
                            seasonsLoading = false,
                            seasons = seasons,
                            selectedSeason = firstRegular?.seasonNumber,
                        )
                    }
                    if (firstRegular != null) loadEpisodes(seriesContentId, firstRegular.seasonNumber)
                }
                else -> _uiState.update { it.copy(seasonsLoading = false) }
            }
        }
    }

    private var episodeLoadJob: kotlinx.coroutines.Job? = null
    private var moreLikeThisJob: Job? = null

    private fun loadEpisodes(seriesContentId: String, seasonNumber: Int) {
        // Cancel any in-flight episode load so a slower response for a
        // previously-selected season can't overwrite episodes/next-up for the
        // season the user is now on (rapid season switches / the initial
        // firstRegular load racing a route-driven season load).
        episodeLoadJob?.cancel()
        episodeLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(episodesLoading = true) }
            when (val r = catalogRepository.getEpisodes(seriesContentId, seasonNumber)) {
                is ApiResult.Success -> {
                    val episodes = r.data.episodes.sortedBy { ep -> ep.episodeNumber }
                    _uiState.update { it.copy(episodesLoading = false, episodes = episodes) }
                    refreshNextUp(episodes)
                }
                else -> {
                    _uiState.update { it.copy(episodesLoading = false, episodes = emptyList()) }
                    refreshNextUp(emptyList())
                }
            }
        }
    }

    /**
     * Resolves the next-up episode for the selected season (series/season detail
     * only) and kicks off its playback-detail load when it changes. Mirrors
     * silo-apple's `nextUpEpisode` + the `.task(id:)`-driven
     * `loadSeriesNextUpPlaybackDetail` / `loadSeasonNextUpPlaybackDetail`.
     */
    private fun refreshNextUp(episodes: List<EpisodeListItem>) {
        val detail = _uiState.value.detail
        val type = detail?.type?.lowercase()
        if (detail == null || (type != "series" && type != "season")) {
            // Movie / episode detail does not drive next-up; clear any state.
            if (_uiState.value.nextUpEpisode != null || _uiState.value.nextUpPlaybackDetail != null) {
                _uiState.update {
                    it.copy(
                        nextUpEpisode = null,
                        nextUpPlaybackDetail = null,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = false,
                        selectedNextUpFileId = null,
                        selectedNextUpAudioIndex = null,
                        selectedNextUpSubtitleIndex = null,
                    )
                }
            }
            return
        }

        val nextUp = resolveNextUpEpisode(episodes)
        val previousId = _uiState.value.nextUpEpisode?.contentId
        if (nextUp?.contentId == previousId && _uiState.value.nextUpEpisode != null) {
            // Same target — just refresh the snapshot (userData may have changed)
            // without re-loading playback detail.
            _uiState.update { it.copy(nextUpEpisode = nextUp) }
            return
        }

        if (nextUp == null) {
            _uiState.update {
                it.copy(
                    nextUpEpisode = null,
                    nextUpPlaybackDetail = null,
                    isLoadingNextUpPlaybackDetail = false,
                    didLoadNextUpPlaybackDetail = false,
                    selectedNextUpFileId = null,
                    selectedNextUpAudioIndex = null,
                    selectedNextUpSubtitleIndex = null,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                nextUpEpisode = nextUp,
                nextUpPlaybackDetail = null,
                isLoadingNextUpPlaybackDetail = true,
                didLoadNextUpPlaybackDetail = false,
                selectedNextUpFileId = null,
                selectedNextUpAudioIndex = null,
                selectedNextUpSubtitleIndex = null,
            )
        }
        loadNextUpPlaybackDetail(nextUp.contentId)
    }

    private fun resolveNextUpEpisode(episodes: List<EpisodeListItem>): EpisodeListItem? {
        episodes.firstOrNull { it.userData?.isInProgress == true }?.let { return it }
        episodes.firstOrNull { it.userData?.played != true }?.let { return it }
        return episodes.firstOrNull()
    }

    private fun loadNextUpPlaybackDetail(episodeContentId: String) {
        viewModelScope.launch {
            val result = catalogRepository.getItemDetail(episodeContentId)
            // Ignore a late result if the next-up target moved on.
            if (_uiState.value.nextUpEpisode?.contentId != episodeContentId) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        nextUpPlaybackDetail = result.data,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = true,
                    )
                }
                else -> _uiState.update {
                    it.copy(
                        nextUpPlaybackDetail = null,
                        isLoadingNextUpPlaybackDetail = false,
                        didLoadNextUpPlaybackDetail = true,
                    )
                }
            }
        }
    }

    fun onNextUpVersionSelected(fileId: Int?) {
        _uiState.update {
            it.copy(
                selectedNextUpFileId = fileId,
                selectedNextUpAudioIndex = null,
                selectedNextUpSubtitleIndex = null,
            )
        }
    }

    fun onNextUpAudioTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedNextUpAudioIndex = index) }
    }

    fun onNextUpSubtitleTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedNextUpSubtitleIndex = index) }
    }

    private fun loadMoreLikeThis(detail: ItemDetail) {
        val primaryGenre = detail.genres.firstOrNull { it.isNotBlank() }
        val mediaType = detail.type.takeIf { it in setOf("movie", "series", "episode") || isAudiobookItemType(it) }
        if (primaryGenre == null && mediaType == null) return

        moreLikeThisJob?.cancel()
        moreLikeThisJob = viewModelScope.launch {
            // This shelf is secondary. Let the hero, seasons, and episode rail settle
            // before starting another browse request during item-open.
            delay(300)
            _uiState.update { it.copy(moreLikeThisLoading = true) }
            when (val result = catalogRepository.browse(
                mediaType = mediaType,
                genre = primaryGenre,
                sort = "rating_imdb",
                order = "desc",
                limit = 18,
            )) {
                is ApiResult.Success -> {
                    val items = result.data.items
                        .visibleOnTv()
                        .filterNot { it.contentId == detail.contentId }
                        .take(16)
                        .map { it.toSectionItem() }
                    _uiState.update {
                        it.copy(
                            moreLikeThisLoading = false,
                            moreLikeThis = items,
                        )
                    }
                }
                else -> _uiState.update {
                    it.copy(moreLikeThisLoading = false, moreLikeThis = emptyList())
                }
            }
        }
    }
}

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
