package org.siloserver.silo.android.ui.screens.downloads

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Leaf row in the Downloads tab. Movies / ebooks / audiobooks / single
 * episodes all render as a single [DownloadItem]. Progress is
 * `bytesSent / fileSize` clamped to [0, 1]; `isComplete` derives from
 * server status plus local file presence (so stale completed records whose
 * public file was removed by another app do not render as ready).
 */
data class DownloadItem(
    val id: String,
    val contentId: String,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val posterThumbhash: String? = null,
    val fileSizeBytes: Long = 0,
    val progress: Float = 0f,
    val isComplete: Boolean = false,
    val status: DownloadStatus = DownloadStatus.Unknown,
    /** True when the record finished in a non-success terminal state
     *  (failed / cancelled / unknown). The UI shows a red badge. */
    val isFailed: Boolean = false,
    val isMissingLocal: Boolean = false,
    val mediaType: DownloadMediaType = DownloadMediaType.Unknown,
    val fileId: Int? = null,
    val localUri: String? = null,
    val displayName: String? = null,
    val container: String? = null,
    /** Quality preset wire string (effective when the server reports one,
     *  else requested). Null for original-only servers / older sidecars. */
    val quality: String? = null,
)

internal data class DownloadItemFileState(
    val isComplete: Boolean,
    val isMissingLocal: Boolean,
    val isFailed: Boolean,
)

internal fun downloadItemFileState(
    status: DownloadStatus,
    hasLocalMedia: Boolean,
): DownloadItemFileState {
    val isMissingLocal = status == DownloadStatus.Completed && !hasLocalMedia
    return DownloadItemFileState(
        isComplete = status == DownloadStatus.Completed && hasLocalMedia,
        isMissingLocal = isMissingLocal,
        isFailed = isMissingLocal ||
            status in setOf(
                DownloadStatus.Failed,
                DownloadStatus.Cancelled,
                DownloadStatus.Unknown,
            ),
    )
}

internal fun downloadItemDisplayProgress(
    status: DownloadStatus,
    rawProgress: Float,
    hasLocalMedia: Boolean,
): Float {
    if (status == DownloadStatus.Completed && !hasLocalMedia) return 0f
    return rawProgress.coerceIn(0f, 1f)
}

/**
 * Recursive hierarchy of downloaded content for the Downloads tab.
 *
 * - [Single] — a movie, ebook, audiobook, or standalone episode.
 * - [Season] — a season of a TV series; children are [Single] episodes.
 * - [Series] — a TV series; children are [Season]s (just one in the
 *   single-season case).
 *
 * Every node aggregates `totalBytesUsed`, `recordIds`, and
 * `progress` from its children so the renderer can show storage +
 * delete-all at every level.
 */
sealed class DownloadEntry {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String?
    abstract val posterUrl: String?
    abstract val posterThumbhash: String?
    abstract val totalBytesUsed: Long
    abstract val progress: Float
    abstract val isComplete: Boolean
    /** Flat list of every server record id under this node — used by
     *  the delete-at-this-level handler. */
    abstract val recordIds: List<String>

    data class Single(val item: DownloadItem) : DownloadEntry() {
        override val id = item.id
        override val title = item.title
        override val subtitle = item.subtitle
        override val posterUrl = item.posterUrl
        override val posterThumbhash = item.posterThumbhash
        override val totalBytesUsed = item.fileSizeBytes
        override val progress = item.progress
        override val isComplete = item.isComplete
        override val recordIds = listOf(item.id)
    }

    data class Season(
        /** Stable grouping key (series contentId, else title) — keeps the
         *  LazyColumn key + expansion state stable across renames/title collisions. */
        val seriesKey: String,
        val seriesTitle: String,
        val seasonNumber: Int,
        val episodes: List<Single>,
        override val posterUrl: String?,
        override val posterThumbhash: String?,
    ) : DownloadEntry() {
        override val id = "season:$seriesKey:$seasonNumber"
        override val title = "Season $seasonNumber"
        val completedCount = episodes.count { it.isComplete }
        override val subtitle = "$completedCount of ${episodes.size} episodes"
        override val totalBytesUsed = episodes.sumOf { it.totalBytesUsed }
        override val progress = if (episodes.isEmpty()) 0f
            else episodes.map { it.progress }.average().toFloat()
        override val isComplete = episodes.isNotEmpty() && episodes.all { it.isComplete }
        override val recordIds = episodes.flatMap { it.recordIds }
    }

    data class Series(
        /** Stable grouping key (series contentId, else title). */
        val seriesKey: String,
        val seriesTitle: String,
        val seasons: List<Season>,
        override val posterUrl: String?,
        override val posterThumbhash: String?,
    ) : DownloadEntry() {
        override val id = "series:$seriesKey"
        override val title = seriesTitle
        val totalEpisodes = seasons.sumOf { it.episodes.size }
        val completedEpisodes = seasons.sumOf { it.completedCount }
        override val subtitle = "$completedEpisodes of $totalEpisodes episodes"
        override val totalBytesUsed = seasons.sumOf { it.totalBytesUsed }
        override val progress = if (totalEpisodes == 0) 0f
            else seasons.sumOf { it.episodes.sumOf { ep -> ep.progress.toDouble() } }
                .div(totalEpisodes).toFloat()
        override val isComplete = seasons.isNotEmpty() && seasons.all { it.isComplete }
        override val recordIds = seasons.flatMap { it.recordIds }
    }

    /** Books grouped under an author (audiobooks/ebooks). One level: author →
     *  book. Only used when an author has 2+ downloaded books — a lone book
     *  renders as a flat [Single] (no needless drill-down). */
    data class Author(
        val authorName: String,
        val books: List<Single>,
        override val posterUrl: String?,
        override val posterThumbhash: String?,
    ) : DownloadEntry() {
        override val id = "author:$authorName"
        override val title = authorName
        private val completedCount = books.count { it.isComplete }
        override val subtitle = "$completedCount of ${books.size} books"
        override val totalBytesUsed = books.sumOf { it.totalBytesUsed }
        override val progress = if (books.isEmpty()) 0f
            else books.map { it.progress }.average().toFloat()
        override val isComplete = books.isNotEmpty() && books.all { it.isComplete }
        override val recordIds = books.flatMap { it.recordIds }
    }
}

/**
 * A media-type section: one of Movies, TV Shows, Audiobooks, eBooks,
 * or Other. Each section's [entries] is type-shaped — TV has [Series]
 * roots, everything else is flat [Single]s.
 */
data class DownloadTypeSection(
    val mediaType: DownloadMediaType,
    val entries: List<DownloadEntry>,
    val totalBytesUsed: Long,
    val recordIds: List<String>,
) {
    val displayName: String get() = mediaType.displayName
    val itemCount: Int get() = entries.sumOf { it.recordIds.size }
}

data class DownloadsUiState(
    val sections: List<DownloadTypeSection> = emptyList(),
    val totalBytesUsed: Long = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    /** "Is there anything?" — drives the Downloads-tab visibility in
     *  the bottom nav + the empty-state branch. */
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * Disk-as-truth Downloads orchestrator with hierarchical grouping.
 *
 * Boot sequence:
 *   1. Seed cache from on-disk sidecars so offline launches populate
 *      instantly.
 *   2. Best-effort server refresh — merges, never overwrites.
 *   3. Render UI tree.
 *
 * Render tree:
 *   sections [Movies / TV / Audiobooks / eBooks / Other]
 *     → entries (Single for leaves, Series → Season → Single for TV)
 *
 * Sidecar metadata is re-read on every records emission so an active
 * download's poster + title surface the moment its sidecar lands
 * (previously the row showed bare contentId until the next launch).
 */
class DownloadsViewModel(
    private val repository: DownloadsRepository,
    private val storage: DownloadStorage,
    private val metadataStore: org.siloserver.silo.common.downloads.DownloadMetadataStore,
    private val serverRegistry: ServerRegistry,
    private val profileRepository: ProfileRepository,
    private val downloadEnqueuer: DownloadEnqueuer,
    private val legacyImporter: org.siloserver.silo.common.downloads.LegacyDownloadImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DownloadsUiState(isLoading = true))
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    /** Sidecar map keyed by record id. Refreshed on every records
     *  emission via [reloadSidecarMetadata]. */
    @Volatile private var metadataByRecordId: Map<String, DownloadSidecar> = emptyMap()

    /** `(serverId, profileId)` scope each fileId's sidecar lives under,
     *  captured during the same walk that loads [metadataByRecordId] so
     *  [toItem] doesn't re-walk the filesystem per record per emission. */
    @Volatile private var scopeByFileId: Map<Int, Pair<String, String>> = emptyMap()

    init {
        viewModelScope.launch {
            // Bootstrap: finish the one-time legacy sidecar→Room import before the
            // first metadata read, so pre-cutover downloads aren't briefly missing
            // on a cold start. Memoized — a no-op once the app-start pass has run.
            legacyImporter.awaitImport(System.currentTimeMillis())
            // Finish any offline delete interrupted by a crash between the durable
            // tombstone and the on-device cleanup (idempotent byte/metadata delete).
            finishPendingDeletions()
            // Backfill + initial sidecar read.
            reloadSidecarMetadata()
            val seeded = metadataByRecordId.values.toList()
            repository.seedFromSidecars(seeded.map { it.record })

            val keep = seeded.map { it.record.id }.toSet()
            val (refreshServerId, refreshProfileId) = activeDownloadScope()
            launch {
                repository.refresh(
                    keepIdsAbsentFromServer = keep,
                    serverId = refreshServerId,
                    profileId = refreshProfileId,
                )
            }

            repository.records.collect { records ->
                // Room is the source of truth for what's downloaded locally; the
                // tab is built from the Room sidecars (which always carry
                // title/poster/mediaType/series), NOT from server records joined
                // to a map that can be empty (the old path rendered a bare
                // contentId + MOVIES + "missing file" whenever the join missed).
                // Live byte-progress/status is overlaid from the in-memory server
                // records for in-flight items. Reload sidecars when a record the
                // map doesn't know about appears (newly enqueued).
                val (sections, bytesUsed) = withContext(Dispatchers.IO) {
                    if (records.any { it.id !in metadataByRecordId }) {
                        reloadSidecarMetadata()
                    }
                    val sects = buildSections(records.associateBy { it.id })
                    sects to sects.sumOf { it.totalBytesUsed }
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        // Preserve any delete/refresh failure — refresh() and
                        // clearError() manage the error lifecycle explicitly;
                        // a progress tick must not wipe it before it's shown.
                        error = it.error,
                        sections = sections,
                        totalBytesUsed = bytesUsed,
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            reloadSidecarMetadata()
            val keep = metadataByRecordId.keys
            val (serverId, profileId) = activeDownloadScope()
            when (val r = repository.refresh(keepIdsAbsentFromServer = keep, serverId = serverId, profileId = profileId)) {
                is ApiResult.Success -> _uiState.update { it.copy(error = null) }
                is ApiResult.Error, is ApiResult.NetworkError ->
                    _uiState.update { it.copy(error = r.errorMessage("Failed to refresh downloads")) }
            }
        }
    }

    /** Idempotently complete on-device cleanup for any durable delete tombstone
     *  in the active scope — covers a crash between tombstone enqueue and byte
     *  deletion. The server side is replayed by [repository.refresh]'s reconcile. */
    private suspend fun finishPendingDeletions() {
        val (serverId, profileId) = activeDownloadScope()
        val pending = runCatching { repository.pendingDeletionsForScope(serverId, profileId) }
            .getOrElse { emptyList() }
        for (p in pending) {
            val fileId = p.mediaFileId ?: continue
            withContext(Dispatchers.IO) { storage.delete(p.serverId, p.profileId, fileId) }
            metadataStore.deleteSidecar(p.serverId, p.profileId, fileId)
        }
    }

    /** Clear a surfaced error once the UI has shown it (e.g. after the
     *  snackbar is dismissed). */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Remove a single record by id (leaf row). */
    fun removeDownload(id: String) {
        viewModelScope.launch { removeRecords(listOf(id)) }
    }

    /** Remove every record under an entry (Series, Season, or single
     *  leaf). [DownloadEntry.recordIds] aggregates across all
     *  descendants, so this works at every level. */
    fun removeEntry(entry: DownloadEntry) {
        viewModelScope.launch { removeRecords(entry.recordIds) }
    }

    /** Remove every record in a media-type section (e.g. "Delete all
     *  Movies", "Delete all TV"). */
    fun removeSection(section: DownloadTypeSection) {
        viewModelScope.launch { removeRecords(section.recordIds) }
    }

    private suspend fun removeRecords(ids: List<String>) {
        if (ids.isEmpty()) return
        val activeScope = activeDownloadScope()

        var firstError: String? = null
        for (id in ids) {
            val record = repository.records.value.firstOrNull { it.id == id }
            val sidecar = metadataByRecordId[id]
            val fileId = record?.mediaFileId ?: sidecar?.record?.mediaFileId
            val (serverId, profileId) = fileId?.let { scopeByFileId[it] } ?: activeScope
            Log.i(TAG, "remove($id): record=${record?.status ?: "(missing)"} fileId=$fileId")

            // Local-first, offline-safe delete. A download delete only removes the
            // on-device copy + the server's *download record* (never the library
            // media), so it must not require the network. Cancel any active worker,
            // write a DURABLE tombstone (so the record can't resurrect as a ghost on
            // the next online refresh — written BEFORE byte deletion so a crash can't
            // lose the server-delete intent), then drop the bytes + metadata.
            val status = record?.statusEnum() ?: sidecar?.record?.statusEnum()
            if (status == DownloadStatus.Queued || status == DownloadStatus.Downloading) {
                downloadEnqueuer.cancel(id)
            }
            repository.enqueueDurableDelete(serverId, profileId, id, fileId)
            if (fileId != null) {
                withContext(Dispatchers.IO) {
                    storage.delete(serverId, profileId, fileId)
                }
                metadataStore.deleteSidecar(serverId, profileId, fileId)
                scopeByFileId = scopeByFileId - fileId
            }
            metadataByRecordId = metadataByRecordId - id

            // Best-effort server reconcile now. Offline → NetworkError: the durable
            // tombstone replays the DELETE on the next online refresh, so the local
            // delete still "succeeds" — no blocking error. A real server error (4xx)
            // is surfaced but the local copy is already gone.
            when (val result = repository.delete(id)) {
                is ApiResult.Success -> Log.i(TAG, "remove($id): server delete OK")
                is ApiResult.Error -> {
                    Log.w(TAG, "remove($id): server returned ${result.code} ${result.message}")
                    if (firstError == null) firstError = result.errorMessage("Delete failed (${result.code})")
                }
                is ApiResult.NetworkError -> {
                    Log.i(TAG, "remove($id): offline — tombstoned, will reconcile on reconnect")
                }
            }
        }
        val (serverId, profileId) = activeDownloadScope()
        val bytesUsed = withContext(Dispatchers.IO) { storage.totalBytesUsed(serverId, profileId) }
        _uiState.update {
            it.copy(
                error = firstError,
                totalBytesUsed = bytesUsed,
            )
        }
    }

    /** One filesystem walk loads both lookup maps. Call on Dispatchers.IO. */
    private suspend fun reloadSidecarMetadata() {
        val (activeServerId, activeProfileId) = activeDownloadScope()
        val scoped = run {
            runCatching { metadataStore.listAllSidecarsWithScope() }.getOrElse { emptyList() }
                .filter { (serverId, profileId, _) ->
                    serverId == activeServerId && profileId == activeProfileId
                }
        }
        metadataByRecordId = scoped.associate { (_, _, sidecar) -> sidecar.record.id to sidecar }
        scopeByFileId = scoped.associate { (serverId, profileId, sidecar) ->
            sidecar.record.mediaFileId to (serverId to profileId)
        }
    }

    private suspend fun activeDownloadScope(): Pair<String, String> {
        val serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID
        val profileId = withContext(Dispatchers.IO) {
            profileRepository.getActiveProfileId()
        } ?: DownloadEnqueuer.DEFAULT_PROFILE_ID
        return serverId to profileId
    }

    companion object {
        private const val TAG = "DownloadsViewModel"
    }

    // ── Sectioning + grouping ─────────────────────────────────────────────

    /**
     * Build a [DownloadItem] from a Room sidecar (the authoritative local
     * picture — always carries title/poster/mediaType/series). Live status +
     * bytes are overlaid from [live] (the in-memory server record) for in-flight
     * items; a completed local-only download (server forgot it) uses the sidecar.
     */
    private fun DownloadSidecar.toDownloadItem(live: DownloadRecord?): DownloadItem {
        val rec = live ?: record
        val mediaType = resolveSidecarMediaType()
        val located = scopeByFileId[record.mediaFileId]?.let { (serverId, profileId) ->
            storage.locateLocalMedia(serverId, profileId, record.mediaFileId)
        }
        val knownSize = if (rec.fileSize > 0) rec.fileSize else record.fileSize
        val progress = if (knownSize > 0) {
            (rec.bytesSent.toFloat() / knownSize.toFloat()).coerceIn(0f, 1f)
        } else 0f
        val status = rec.statusEnum()
        val fileState = downloadItemFileState(status = status, hasLocalMedia = located != null)
        val displayProgress = downloadItemDisplayProgress(
            status = status,
            rawProgress = progress,
            hasLocalMedia = located != null,
        )
        // Shown size = REAL on-disk bytes via fd-stat (MediaStore SIZE is stale/0
        // while the item is pending, so don't trust located.sizeBytes mid-download),
        // falling back to the live record's bytesSent. Never the sidecar's claimed
        // fileSize, which overstates queued/failed/missing rows (Codex).
        val sizeUri = localUri ?: located?.uriString
        val shownBytes = (sizeUri?.let { storage.partialSize(it) }?.takeIf { it > 0 })
        // No real bytes on disk: a completed-but-missing row is 0 (don't show a
        // stale size or inflate the header); an in-flight row uses live bytesSent.
            ?: if (status == DownloadStatus.Completed) 0L else rec.bytesSent
        return DownloadItem(
            id = record.id,
            contentId = record.contentId,
            title = title,
            subtitle = subtitle,
            posterUrl = posterUrl,
            posterThumbhash = posterThumbhash,
            fileSizeBytes = shownBytes,
            progress = displayProgress,
            isComplete = fileState.isComplete,
            status = status,
            isFailed = fileState.isFailed,
            isMissingLocal = fileState.isMissingLocal,
            mediaType = mediaType,
            fileId = record.mediaFileId,
            localUri = located?.uriString,
            displayName = located?.displayName ?: fileName,
            container = container,
            quality = rec.effectiveQuality ?: rec.quality
                ?: record.effectiveQuality ?: record.quality,
        )
    }

    private fun DownloadSidecar.resolveSidecarMediaType(): DownloadMediaType {
        val explicit = DownloadMediaType.fromWire(mediaType)
        if (explicit != DownloadMediaType.Unknown) return explicit
        return if (!seriesTitle.isNullOrBlank()) DownloadMediaType.TvShow else DownloadMediaType.Movie
    }

    /**
     * Top-level: build per-mediaType sections from the Room sidecars (source of
     * truth), in stable display order. [liveById] overlays in-flight progress.
     */
    private fun buildSections(liveById: Map<String, DownloadRecord>): List<DownloadTypeSection> {
        val sidecars = metadataByRecordId.values.toList()
        if (sidecars.isEmpty()) return emptyList()
        val byType = sidecars.groupBy { it.resolveSidecarMediaType() }
        val sectionOrder = listOf(
            DownloadMediaType.Movie,
            DownloadMediaType.TvShow,
            DownloadMediaType.Audiobook,
            DownloadMediaType.Ebook,
            DownloadMediaType.Unknown,
        )
        return sectionOrder.mapNotNull { type ->
            val group = byType[type].orEmpty()
            if (group.isEmpty()) return@mapNotNull null
            val entries = when (type) {
                DownloadMediaType.TvShow -> group.toTvEntries(liveById)
                DownloadMediaType.Audiobook, DownloadMediaType.Ebook -> group.toBookEntries(liveById)
                else -> group.sortedByDescending { it.updatedAtMs }
                    .map { DownloadEntry.Single(it.toDownloadItem(liveById[it.record.id])) }
            }
            DownloadTypeSection(
                mediaType = type,
                entries = entries,
                totalBytesUsed = entries.sumOf { it.totalBytesUsed },
                recordIds = entries.flatMap { it.recordIds },
            )
        }
    }

    /**
     * TV branch: sidecars → [Series] → [Season] → [Single]. Grouped by STABLE
     * series content id (rename-safe), falling back to seriesTitle only when the
     * id is absent (rows written before seriesContentId existed). A sidecar with
     * no series at all surfaces as a Single so it's still deletable.
     */
    private fun List<DownloadSidecar>.toTvEntries(liveById: Map<String, DownloadRecord>): List<DownloadEntry> {
        val bySeries = LinkedHashMap<String, MutableList<DownloadSidecar>>()
        val orphans = mutableListOf<DownloadSidecar>()
        for (sc in this) {
            val key = sc.seriesContentId?.takeIf { it.isNotBlank() }
                ?: sc.seriesTitle?.takeIf { it.isNotBlank() }
            if (key == null) orphans += sc else bySeries.getOrPut(key) { mutableListOf() } += sc
        }
        val seriesEntries = bySeries.map { (seriesKey, group) ->
            val seriesTitle = group.firstNotNullOfOrNull { it.seriesTitle?.takeIf { t -> t.isNotBlank() } } ?: "Series"
            val poster = group.firstNotNullOfOrNull { it.posterUrl }
            val thumb = group.firstNotNullOfOrNull { it.posterThumbhash }
            val bySeason = LinkedHashMap<Int, MutableList<DownloadSidecar>>()
            for (sc in group) bySeason.getOrPut(sc.seasonNumber ?: -1) { mutableListOf() } += sc
            val seasons = bySeason.toSortedMap().map { (season, seasonScs) ->
                val episodes = seasonScs
                    .sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
                    .map { DownloadEntry.Single(it.toDownloadItem(liveById[it.record.id])) }
                DownloadEntry.Season(
                    seriesKey = seriesKey,
                    seriesTitle = seriesTitle,
                    seasonNumber = season,
                    episodes = episodes,
                    posterUrl = poster,
                    posterThumbhash = thumb,
                )
            }
            DownloadEntry.Series(
                seriesKey = seriesKey,
                seriesTitle = seriesTitle,
                seasons = seasons,
                posterUrl = poster,
                posterThumbhash = thumb,
            )
        }
        return seriesEntries + orphans.map { DownloadEntry.Single(it.toDownloadItem(liveById[it.record.id])) }
    }

    /**
     * Books branch (audiobooks/ebooks): group by author → book. An author with a
     * single downloaded book renders as a flat [Single] (no drill-down clutter);
     * 2+ become an [Author] aggregate. Books with no author surface as Singles.
     */
    private fun List<DownloadSidecar>.toBookEntries(liveById: Map<String, DownloadRecord>): List<DownloadEntry> {
        val byAuthor = LinkedHashMap<String, MutableList<DownloadSidecar>>()
        val orphans = mutableListOf<DownloadSidecar>()
        for (sc in this) {
            val author = sc.author?.trim()?.takeIf { it.isNotBlank() }
            if (author == null) orphans += sc else byAuthor.getOrPut(author) { mutableListOf() } += sc
        }
        val authorEntries = byAuthor.map { (author, group) ->
            val books = group
                .sortedBy { it.title }
                .map { DownloadEntry.Single(it.toDownloadItem(liveById[it.record.id])) }
            if (books.size == 1) {
                books.first()
            } else {
                DownloadEntry.Author(
                    authorName = author,
                    books = books,
                    posterUrl = group.firstNotNullOfOrNull { it.posterUrl },
                    posterThumbhash = group.firstNotNullOfOrNull { it.posterThumbhash },
                )
            }
        }
        return authorEntries + orphans
            .sortedByDescending { it.updatedAtMs }
            .map { DownloadEntry.Single(it.toDownloadItem(liveById[it.record.id])) }
    }
}
