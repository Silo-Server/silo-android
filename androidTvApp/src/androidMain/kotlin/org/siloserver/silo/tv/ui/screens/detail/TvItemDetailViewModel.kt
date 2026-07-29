package org.siloserver.silo.tv.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.settings.ProfileSettingsController
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CastMember
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.sortedForDisplay
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.playback.resolveSubtitleTrackOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.tv.ui.util.isTvHiddenMediaType
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.viewmodel.applyLocalPlaybackProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val episodeFavoriteStates: Map<String, Boolean> = emptyMap(),
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
        observePreferredQuality()
        if (contentId.isNotBlank()) {
            // Restore this title's pre-play track choices (QA 2026-07-08: a
            // manual subtitle selection reset on every return to the page —
            // season switches and detail re-entry build a fresh ViewModel).
            TvDetailTrackSelectionSession.recall(contentId)?.let { saved ->
                _uiState.update {
                    it.copy(
                        selectedFileId = saved.fileId,
                        selectedAudioIndex = saved.audio,
                        selectedSubtitleIndex = saved.subtitle,
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
                    // Restore a durably-persisted audio/subtitle override (TM4).
                    seedPersistedTrackSelection(detail)
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

    /**
     * Quiet refresh for returning to an already-loaded detail screen (e.g.
     * backing out of playback): re-reads userData so the Play button's resume
     * label, the episode rail, and next-up reflect the session that just
     * ended. Deliberately NOT [loadAll] — no loading flashes, and the user's
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
        if (seriesId != null && season != null) loadEpisodes(seriesId, season, quiet = true)
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
            it.copy(selectedFileId = fileId, selectedAudioIndex = null, selectedSubtitleIndex = null)
        }
        TvDetailTrackSelectionSession.remember(contentId, fileId, audio = null, subtitle = null)
        // Do NOT persist here: a version switch resets the indexes to null, and
        // persisting null clears the durable row — which would wipe the newly
        // selected file's saved override before seedPersistedTrackSelection can
        // restore it. Only explicit audio/subtitle picks persist.
        _uiState.value.detail?.let(::seedPersistedTrackSelection)
    }

    /** Pre-select an audio track for the next Play (index into the version's audioTracks). */
    fun onAudioTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedAudioIndex = index) }
        val state = _uiState.value
        TvDetailTrackSelectionSession.remember(contentId, state.selectedFileId, index, state.selectedSubtitleIndex)
        persistTrackSelection()
    }

    /** Pre-select a subtitle track for the next Play (-1 = Off, null = auto). */
    fun onSubtitleTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
        val state = _uiState.value
        TvDetailTrackSelectionSession.remember(contentId, state.selectedFileId, state.selectedAudioIndex, index)
        persistTrackSelection()
    }

    /** The version behind [TvItemDetailUiState.selectedFileId], or the default
     *  (first) version when nothing is explicitly selected. */
    private fun selectedVersionFor(state: TvItemDetailUiState, detail: ItemDetail): FileVersion? {
        val fileId = state.selectedFileId
        return if (fileId != null) detail.versions.firstOrNull { it.fileId == fileId }
        else detail.versions.firstOrNull()
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
        )
    }

    private fun persistTrackSelectionFor(
        targetContentId: String,
        detail: ItemDetail,
        selectedFileId: Int?,
        selectedAudioIndex: Int?,
        selectedSubtitleIndex: Int?,
    ) {
        val version = selectedFileId
            ?.let { fileId -> detail.versions.firstOrNull { it.fileId == fileId } }
            ?: detail.versions.firstOrNull()
            ?: return
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
    // The season number the currently-shown episodes/next-up actually belong to.
    // Lets a failed load revert the optimistic season selection so the chips and
    // the rail stay consistent (T15).
    private var loadedSeason: Int? = null
    private var episodeListGeneration: Long = 0
    private var nextEpisodeWatchMutationGeneration: Long = 0
    private val episodeWatchMutationGenerations = mutableMapOf<String, Long>()
    private var nextEpisodeFavoriteMutationGeneration: Long = 0
    private val episodeFavoriteMutationGenerations = mutableMapOf<String, Long>()

    /**
     * Loads a season's episodes. [quiet] suppresses the loading spinner and is
     * used by [refreshOnReturn], whose contract is a no-flash background refresh
     * of the season already on screen.
     */
    private fun loadEpisodes(seriesContentId: String, seasonNumber: Int, quiet: Boolean = false) {
        // Cancel any in-flight episode load so a slower response for a
        // previously-selected season can't overwrite episodes/next-up for the
        // season the user is now on (rapid season switches / the initial
        // firstRegular load racing a route-driven season load).
        episodeLoadJob?.cancel()
        episodeLoadJob = viewModelScope.launch {
            if (!quiet) _uiState.update { it.copy(episodesLoading = true) }
            when (val r = catalogRepository.getEpisodes(seriesContentId, seasonNumber)) {
                is ApiResult.Success -> {
                    val episodes = withLocalProgress(r.data.episodes.sortedBy { ep -> ep.episodeNumber })
                    loadedSeason = seasonNumber
                    episodeListGeneration += 1
                    _uiState.update { it.copy(episodesLoading = false, episodes = episodes) }
                    refreshNextUp(episodes)
                    refreshEpisodeFavoriteStates(episodes)
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
                }
            }
        }
    }

    private suspend fun refreshEpisodeFavoriteStates(episodes: List<EpisodeListItem>) {
        if (episodes.isEmpty()) {
            _uiState.update { it.copy(episodeFavoriteStates = emptyMap()) }
            return
        }
        val knownStates = _uiState.value.episodeFavoriteStates
        val states = coroutineScope {
            episodes.map { episode ->
                async {
                    val favorite = personalDataRepository.isFavorite(episode.contentId)
                    episode.contentId to when (favorite) {
                        is ApiResult.Success -> favorite.data
                        else -> knownStates[episode.contentId] ?: false
                    }
                }
            }.awaitAll().toMap()
        }
        val currentIds = _uiState.value.episodes.mapTo(mutableSetOf()) { it.contentId }
        if (currentIds == episodes.mapTo(mutableSetOf()) { it.contentId }) {
            _uiState.update { it.copy(episodeFavoriteStates = states) }
        }
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
                is ApiResult.Success -> {
                    val playbackDetail = withLocalProgress(result.data)
                    _uiState.update {
                        it.copy(
                            nextUpPlaybackDetail = playbackDetail,
                            isLoadingNextUpPlaybackDetail = false,
                            didLoadNextUpPlaybackDetail = true,
                        )
                    }
                    restoreNextUpTrackSelection(episodeContentId, playbackDetail)
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
        _uiState.update {
            it.copy(
                selectedNextUpFileId = fileId,
                selectedNextUpAudioIndex = null,
                selectedNextUpSubtitleIndex = null,
            )
        }
        rememberNextUpTrackSelection()
        seedPersistedNextUpTrackSelection()
    }

    fun onNextUpAudioTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedNextUpAudioIndex = index) }
        rememberNextUpTrackSelection()
        persistNextUpTrackSelection()
    }

    fun onNextUpSubtitleTrackSelected(index: Int?) {
        _uiState.update { it.copy(selectedNextUpSubtitleIndex = index) }
        rememberNextUpTrackSelection()
        persistNextUpTrackSelection()
    }

    private fun rememberNextUpTrackSelection() {
        val state = _uiState.value
        val nextUpContentId = state.nextUpEpisode?.contentId ?: return
        TvDetailTrackSelectionSession.remember(
            nextUpContentId,
            state.selectedNextUpFileId,
            state.selectedNextUpAudioIndex,
            state.selectedNextUpSubtitleIndex,
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
        )
    }

    private fun restoreNextUpTrackSelection(episodeContentId: String, detail: ItemDetail) {
        val session = TvDetailTrackSelectionSession.recall(episodeContentId)
        if (session != null) {
            _uiState.update {
                if (it.nextUpEpisode?.contentId != episodeContentId) it else it.copy(
                    selectedNextUpFileId = session.fileId,
                    selectedNextUpAudioIndex = session.audio,
                    selectedNextUpSubtitleIndex = session.subtitle,
                )
            }
        }
        // A session can intentionally select a file before its durable track
        // fingerprints have loaded (file B / null / null). Always merge the
        // selected file's durable dimensions after applying session state;
        // the guarded update below preserves any non-null session choices.
        seedPersistedNextUpTrackSelection(episodeContentId, detail)
    }

    private fun seedPersistedNextUpTrackSelection(
        episodeContentId: String? = _uiState.value.nextUpEpisode?.contentId,
        detail: ItemDetail? = _uiState.value.nextUpPlaybackDetail,
    ) {
        val targetContentId = episodeContentId ?: return
        val playbackDetail = detail ?: return
        val selectedFileId = _uiState.value.selectedNextUpFileId
        val version = selectedFileId
            ?.let { fileId -> playbackDetail.versions.firstOrNull { it.fileId == fileId } }
            ?: playbackDetail.versions.firstOrNull()
            ?: return
        viewModelScope.launch {
            val saved = userItemState.localTrackSelection(targetContentId, version.fileId) ?: return@launch
            val restored = restoreTrackSelection(version, saved)
            _uiState.update {
                if (!shouldApplyNextUpTrackRestore(
                        currentContentId = it.nextUpEpisode?.contentId,
                        requestedContentId = targetContentId,
                        currentSelectedFileId = it.selectedNextUpFileId,
                        requestedSelectedFileId = selectedFileId,
                    )
                ) {
                    it
                } else {
                    val merged = mergeTrackSelection(
                        currentAudioIndex = it.selectedNextUpAudioIndex,
                        currentSubtitleIndex = it.selectedNextUpSubtitleIndex,
                        durable = restored,
                    )
                    it.copy(
                        selectedNextUpSubtitleIndex = merged.subtitleIndex,
                        selectedNextUpAudioIndex = merged.audioIndex,
                    )
                }
            }
            rememberNextUpTrackSelection()
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
internal object TvDetailTrackSelectionSession {
    internal data class Saved(val fileId: Int?, val audio: Int?, val subtitle: Int?)

    private val byContent = HashMap<String, Saved>()

    fun remember(contentId: String, fileId: Int?, audio: Int?, subtitle: Int?) {
        if (contentId.isBlank()) return
        byContent[contentId] = Saved(fileId, audio, subtitle)
    }

    fun recall(contentId: String): Saved? = byContent[contentId]
}
