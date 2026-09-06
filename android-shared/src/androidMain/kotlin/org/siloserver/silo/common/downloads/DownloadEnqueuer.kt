package org.siloserver.silo.common.downloads

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.model.download.DownloadSidecar
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.DownloadsRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Thin Android-side helper that combines the cross-platform
 * [DownloadsRepository.create] call with the platform-specific
 * [DownloadWorker.enqueue] handoff. Centralises the (serverId, profileId)
 * resolution so ViewModels stay free of Android `Context` references.
 *
 * Also responsible for stashing the catalog metadata on disk at download
 * start: we resolve the [ItemDetail] here (one network call) and write the
 * initial [DownloadSidecar] before the worker even starts streaming bytes.
 * This is the only place we have both the catalog row and the download
 * record at the same time, so an offline app launch later doesn't need a
 * network round-trip to render the row's title and poster.
 */
class DownloadEnqueuer(
    private val context: Context,
    private val repository: DownloadsRepository,
    private val serverRegistry: ServerRegistry,
    private val profileRepository: ProfileRepository,
    private val playerSettingsStore: PlayerSettingsStore,
    private val catalogRepository: CatalogRepository,
    private val storage: DownloadStorage,
    private val metadataStore: DownloadMetadataStore,
    private val authorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
    private val transitions: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
    private val devices: org.siloserver.silo.network.DeviceMetadataProvider? = null,
) {

    /**
     * Single-file download (movie or individual episode by fileId). Server
     * creates the record, we stash the sidecar, then the [DownloadWorker]
     * picks it up. On server failure, returns the [ApiResult.Error] /
     * [ApiResult.NetworkError] unchanged so the caller can surface a toast.
     */
    suspend fun start(
        contentId: String,
        fileId: Int,
        displayTitle: String,
        downloadQualityOverride: DownloadQuality? = null,
    ): ApiResult<Unit> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (authorities != null && authority == null) return ApiResult.Error(0, "identity_changed", "Downloads need a saved login.")
        Log.i(TAG, "start: contentId=$contentId fileId=$fileId title=$displayTitle")
        if (activeDownloadExists(fileId)) {
            Log.i(TAG, "start: fileId=$fileId already queued/downloading — skipping duplicate")
            return alreadyActive()
        }
        val record = when (val r = repository.create(
            downloadRequest(
                contentId = contentId,
                fileId = fileId,
                downloadQualityOverride = downloadQualityOverride,
            ),
            expectedAuthority = authority,
        )) {
            is ApiResult.Success -> r.data.also { Log.i(TAG, "start: server record id=${it.id} status=${it.status}") }
            is ApiResult.Error -> { Log.w(TAG, "start: server error ${r.code} ${r.message}"); return ApiResult.Error(r.code, r.error, r.message) }
            is ApiResult.NetworkError -> { Log.w(TAG, "start: network error", r.exception); return ApiResult.NetworkError(r.exception) }
        }
        val sidecar = buildInitialSidecar(record, contentId, displayTitle)
        finalizeAndEnqueue(record, sidecar, displayTitle, authority)
        return ApiResult.Success(Unit)
    }

    /**
     * Single-episode download. Resolves the episode's catalog row for rich
     * sidecar metadata (series title, S/E numbers, still image). The
     * `seriesContentId` is what gets associated on the server side; the
     * `fileId` chosen here is the user-preferred quality.
     */
    suspend fun startEpisode(
        seriesContentId: String,
        episodeContentId: String,
        fileId: Int,
        seriesTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        posterUrl: String? = null,
        downloadQualityOverride: DownloadQuality? = null,
    ): ApiResult<Unit> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (authorities != null && authority == null) return ApiResult.Error(0, "identity_changed", "Downloads need a saved login.")
        Log.i(TAG, "startEpisode: series=$seriesContentId ep=$episodeContentId fileId=$fileId S${seasonNumber}E${episodeNumber}")
        if (activeDownloadExists(fileId)) {
            Log.i(TAG, "startEpisode: fileId=$fileId already queued/downloading — skipping duplicate")
            return alreadyActive()
        }
        val displayTitle = "$seriesTitle S${seasonNumber}E${episodeNumber}" +
            (episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        val record = when (val r = repository.create(
            downloadRequest(
                contentId = episodeContentId,
                fileId = fileId,
                downloadQualityOverride = downloadQualityOverride,
            ),
            expectedAuthority = authority,
        )) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> { Log.w(TAG, "startEpisode: server error ${r.code} ${r.message}"); return ApiResult.Error(r.code, r.error, r.message) }
            is ApiResult.NetworkError -> { Log.w(TAG, "startEpisode: network error", r.exception); return ApiResult.NetworkError(r.exception) }
        }
        val episodeDetail = (catalogRepository.getItemDetail(episodeContentId) as? ApiResult.Success)?.data
        val version = episodeDetail?.versionFor(record.mediaFileId)
        val sidecar = DownloadSidecar(
            record = record,
            title = seriesTitle,
            subtitle = "S${seasonNumber}E${episodeNumber}" + (episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            posterUrl = posterUrl,
            seriesTitle = seriesTitle,
            seriesContentId = seriesContentId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            fileName = version?.fileName,
            container = version?.container,
            mediaType = DownloadMediaType.TvShow.wire,
            updatedAtMs = System.currentTimeMillis(),
        )
        finalizeAndEnqueue(record, sidecar, displayTitle, authority)
        return ApiResult.Success(Unit)
    }

    /**
     * Whole-series download. One POST with `series=true` returns N records
     * sharing a `batch_id`. We then look up the series detail + every
     * episode's metadata to enrich each record's sidecar (so the Downloads
     * tab can show "Severance · S01E03 · Defiant Jazz" rather than just a
     * file id), and enqueue one worker per record.
     *
     * Best-effort metadata: if catalog calls fail mid-way through, the
     * remaining sidecars get bare-bones titles instead of failing the
     * whole batch.
     */
    suspend fun startSeries(
        seriesContentId: String,
        downloadQualityOverride: DownloadQuality? = null,
    ): ApiResult<Unit> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (authorities != null && authority == null) return ApiResult.Error(0, "identity_changed", "Downloads need a saved login.")
        Log.i(TAG, "startSeries: contentId=$seriesContentId")
        val created = when (val r = repository.createBatch(
            downloadRequest(
                contentId = seriesContentId,
                series = true,
                // Batch (series/season) downloads are original-only: the server
                // returns 501 bulk_quality_unavailable for any other quality
                // (docs §5 + error table). Force Original regardless of the
                // override the caller passed (issue #20 GAP 3).
                downloadQualityOverride = DownloadQuality.Original,
            ),
            expectedAuthority = authority,
        )) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> { Log.w(TAG, "startSeries: server error ${r.code} ${r.message}"); return ApiResult.Error(r.code, r.error, r.message) }
            is ApiResult.NetworkError -> { Log.w(TAG, "startSeries: network error", r.exception); return ApiResult.NetworkError(r.exception) }
        }
        val records = created.downloads
        if (records.isEmpty()) return batchOutcome(created.skipped)

        val seriesDetail = (catalogRepository.getItemDetail(seriesContentId) as? ApiResult.Success)?.data
        val episodeByFileId = buildEpisodeIndexByFileId(seriesContentId)
        val seriesTitle = seriesDetail?.title ?: "Series"
        val posterUrl = seriesDetail?.posterUrl

        for (record in records) {
            // Same duplicate guard as start/startEpisode: a second worker for
            // an already-active fileId would wipe the first one's partial.
            if (activeDownloadExists(record.mediaFileId) || completedLocalExists(record, authority)) {
                Log.i(TAG, "startSeries: fileId=${record.mediaFileId} already queued/downloading — skipping duplicate")
                continue
            }
            val ep = episodeByFileId[record.mediaFileId]
            val episodeDetail = ep?.let { (catalogRepository.getItemDetail(it.contentId) as? ApiResult.Success)?.data }
            val version = episodeDetail?.versionFor(record.mediaFileId)
            val sidecar = DownloadSidecar(
                record = record,
                title = seriesTitle,
                subtitle = ep?.let {
                    "S${it.seasonNumber}E${it.episodeNumber}" +
                        (it.title?.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: "")
                } ?: "Series episode",
                posterUrl = posterUrl,
                posterThumbhash = seriesDetail?.posterThumbhash,
                seriesTitle = seriesTitle,
                seriesContentId = seriesContentId,
                seasonNumber = ep?.seasonNumber,
                episodeNumber = ep?.episodeNumber,
                fileName = version?.fileName,
                container = version?.container ?: ep?.files?.firstOrNull { it.fileId == record.mediaFileId }?.container,
                mediaType = DownloadMediaType.TvShow.wire,
                updatedAtMs = System.currentTimeMillis(),
            )
            val displayTitle = "$seriesTitle ${ep?.let { "S${it.seasonNumber}E${it.episodeNumber}" } ?: ""}".trim()
            finalizeAndEnqueue(record, sidecar, displayTitle, authority)
        }
        return batchOutcome(created.skipped)
    }

    /**
     * Whole-season download. Server doesn't currently expose a season-batch
     * endpoint, so we loop: one POST per episode (chosen file = best
     * available). Sequential rather than parallel to avoid hammering the
     * server with N concurrent record creations, and so a partial failure
     * (mid-season network blip) leaves an obvious "downloaded N of K"
     * state rather than scattered ghost records.
     *
     * Returns success if at least one episode was queued; on total failure
     * returns the first error encountered.
     */
    suspend fun startSeason(
        seriesContentId: String,
        seasonNumber: Int,
        downloadQualityOverride: DownloadQuality? = null,
    ): ApiResult<Unit> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (authorities != null && authority == null) return ApiResult.Error(0, "identity_changed", "Downloads need a saved login.")
        Log.i(TAG, "startSeason: series=$seriesContentId season=$seasonNumber")
        val episodes = when (val r = catalogRepository.getEpisodes(seriesContentId, seasonNumber)) {
            is ApiResult.Success -> r.data.episodes
            is ApiResult.Error -> return ApiResult.Error(r.code, r.error, r.message)
            is ApiResult.NetworkError -> return ApiResult.NetworkError(r.exception)
        }
        if (episodes.isEmpty()) return ApiResult.Success(Unit)

        val seriesDetail = (catalogRepository.getItemDetail(seriesContentId) as? ApiResult.Success)?.data
        val seriesTitle = seriesDetail?.title ?: "Series"
        val posterUrl = seriesDetail?.posterUrl

        var firstError: ApiResult<Unit>? = null
        var queued = 0
        for (ep in episodes) {
            if (authorities != null && authority != authorities.snapshotDurableLoginAuthority()) return ApiResult.Error(0, "identity_changed", "The download owner changed.")
            // Pick the highest-resolution file (last entry after best-pick
            // would be ideal, but the server already sorts; use first).
            val fileId = ep.files.firstOrNull()?.fileId ?: continue
            val result = startEpisode(
                seriesContentId = seriesContentId,
                episodeContentId = ep.contentId,
                fileId = fileId,
                seriesTitle = seriesTitle,
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                episodeTitle = ep.title,
                posterUrl = posterUrl,
                // Season batch is original-only (server rejects a non-original
                // batch with 501 bulk_quality_unavailable). Force Original even
                // though startEpisode itself honors quality for single episodes.
                downloadQualityOverride = DownloadQuality.Original,
            )
            // An episode that is already in flight counts as queued: the season
            // is in the state the user asked for, so a re-tap must not report
            // the whole season as failed.
            if (result is ApiResult.Success || result.isAlreadyActive()) queued++
            else if (firstError == null) firstError = result
        }
        Log.i(TAG, "startSeason: queued $queued of ${episodes.size}")
        return if (queued > 0) ApiResult.Success(Unit) else (firstError ?: ApiResult.Success(Unit))
    }

    private fun batchOutcome(skipped: List<org.siloserver.silo.model.download.SkippedDownload>): ApiResult<Unit> =
        if (skipped.isEmpty()) ApiResult.Success(Unit) else ApiResult.Error(0, "downloads_partially_skipped",
            "${skipped.size} episodes could not be downloaded. Available episodes were queued.")

    private suspend fun completedLocalExists(
        record: org.siloserver.silo.model.download.DownloadRecord,
        authority: org.siloserver.silo.network.DurableLoginAuthority?,
    ): Boolean {
        val owner = authority ?: return false
        if (owner != authorities?.snapshotDurableLoginAuthority()) return false
        val profile = owner.scope.profileId ?: return false
        val local = metadataStore.readSidecar(owner.scope.serverId, profile, record.mediaFileId) ?: return false
        return local.record.id == record.id && local.record.statusEnum() == DownloadStatus.Completed &&
            storage.exists(owner.scope.serverId, profile, record.mediaFileId)
    }

    /** Builds a fileId → EpisodeListItem map across every season of [seriesContentId].
     *  Used by [startSeries] to enrich each batched sidecar with episode metadata. */
    private suspend fun buildEpisodeIndexByFileId(
        seriesContentId: String,
    ): Map<Int, org.siloserver.silo.model.catalog.EpisodeListItem> {
        val seasons = when (val r = catalogRepository.getSeasons(seriesContentId)) {
            is ApiResult.Success -> r.data.seasons
            else -> return emptyMap()
        }
        val map = HashMap<Int, org.siloserver.silo.model.catalog.EpisodeListItem>()
        for (season in seasons) {
            val eps = when (val r = catalogRepository.getEpisodes(seriesContentId, season.seasonNumber)) {
                is ApiResult.Success -> r.data.episodes
                else -> continue
            }
            for (ep in eps) {
                for (f in ep.files) {
                    map[f.fileId] = ep
                }
            }
        }
        return map
    }

    /**
     * True when the active scope already has a queued/downloading record for
     * [fileId]. Two workers for one fileId share the same on-disk target, and
     * [DownloadStorage.prepareWrite] recreates that directory — so a duplicate
     * enqueue lets the second worker wipe the first one's partial mid-stream.
     * The sidecar store is the local source of truth for per-scope status —
     * but only while a worker is actually alive for it: a row can outlive its
     * work (cancel, process death mid-stream, WorkManager pruning), and a
     * stale Queued/Downloading row would otherwise block every future enqueue
     * for that file forever. So a row with no live work is dropped, not honoured.
     */
    private suspend fun activeDownloadExists(fileId: Int): Boolean {
        val serverId = serverRegistry.activeServerId.value ?: DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DEFAULT_PROFILE_ID
        val existing = runCatching { metadataStore.readSidecar(serverId, profileId, fileId) }.getOrNull()
            ?: return false
        val claimsActive = when (existing.record.statusEnum()) {
            DownloadStatus.Queued, DownloadStatus.Downloading -> true
            else -> false
        }
        if (!claimsActive) return false
        // WorkManager commits an enqueue on its own executor, so a just-written
        // row can briefly have no visible work. Trust the row inside that window
        // rather than racing a double-tap into a second worker.
        if (System.currentTimeMillis() - existing.updatedAtMs < WORK_VISIBILITY_GRACE_MS) return true
        if (hasLiveWork(existing.record.id)) return true
        Log.i(TAG, "activeDownloadExists: fileId=$fileId sidecar is stale (no live work) — dropping")
        runCatching { metadataStore.deleteSidecar(serverId, profileId, fileId) }
            .onFailure { Log.w(TAG, "activeDownloadExists: stale deleteSidecar failed for fileId=$fileId", it) }
        return false
    }

    /** True when WorkManager still holds a non-terminal worker for [downloadId].
     *  Unknown (query threw) is reported as live so an unreadable WorkManager
     *  can't turn the duplicate guard into a partial-wiping double enqueue. */
    private suspend fun hasLiveWork(downloadId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadWorker.tagFor(downloadId))
                .get()
                .any { !it.state.isFinished }
        }.getOrElse {
            Log.w(TAG, "hasLiveWork: WorkManager query failed for id=$downloadId", it)
            true
        }
    }

    private suspend fun downloadRequest(
        contentId: String,
        fileId: Int? = null,
        episodeId: String? = null,
        series: Boolean = false,
        downloadQualityOverride: DownloadQuality? = null,
    ): DownloadRequest {
        val quality = downloadQualityOverride ?: DownloadQuality.fromWire(playerSettingsStore.defaultDownloadQualityFlow.first())
        return DownloadRequest(
            contentId = contentId,
            episodeId = episodeId,
            fileId = fileId,
            series = series,
            quality = quality.wire,
            targetBitrateKbps = quality.targetBitrateKbps,
        )
    }

    /**
     * Common tail: persists the sidecar and kicks off the
     * [DownloadWorker]. Pulls (serverId, profileId, wifiOnly) here once so
     * each entry point doesn't repeat the lookup.
     */
    private suspend fun finalizeAndEnqueue(
        record: org.siloserver.silo.model.download.DownloadRecord,
        sidecar: DownloadSidecar,
        displayTitle: String,
        authority: org.siloserver.silo.network.DurableLoginAuthority?,
    ) {
        check(authorities == null || authority == authorities.snapshotDurableLoginAuthority()) { "The download owner changed" }
        val serverId = serverRegistry.activeServerId.value ?: DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DEFAULT_PROFILE_ID
        val wifiOnly = playerSettingsStore.downloadsWifiOnlyFlow.first()
        val deviceId = devices?.current()?.id
        check(authority == null || !deviceId.isNullOrBlank()) { "The download device is unavailable" }
        // The Room metadata row is what makes the download visible in the
        // Downloads tab (it's the source of truth there). A write failure would
        // create an invisible download — log loudly. (Recovery: the worker
        // re-asserts the row on status transitions; full create-if-missing is a
        // tracked follow-up.)
        suspend fun persistAndEnqueue() {
        metadataStore.writeSidecar(serverId, profileId, sidecar)
        DownloadWorker.enqueue(
            context = context,
            downloadId = record.id,
            fileId = record.mediaFileId,
            serverId = serverId,
            profileId = profileId,
            fileName = sidecar.fileName,
            container = sidecar.container,
            mediaType = sidecar.mediaType,
            displayTitle = displayTitle,
            wifiOnly = wifiOnly,
            deviceId = deviceId,
            loginId = authority?.loginId,
            origin = authority?.scope?.serverUrl,
        )
        }
        if (authority == null) persistAndEnqueue()
        else check(transitions?.withCurrentGeneration(authority.scope.identityGeneration) {
            persistAndEnqueue(); true
        } == true) { "The download owner changed" }
    }

    private suspend fun buildInitialSidecar(
        record: org.siloserver.silo.model.download.DownloadRecord,
        contentId: String,
        fallbackTitle: String,
    ): DownloadSidecar {
        val detail = when (val r = catalogRepository.getItemDetail(contentId)) {
            is ApiResult.Success -> r.data
            else -> null
        }
        val version = detail?.versionFor(record.mediaFileId)
        return DownloadSidecar(
            record = record,
            title = detail?.title ?: fallbackTitle,
            posterUrl = detail?.posterUrl,
            posterThumbhash = detail?.posterThumbhash,
            year = detail?.year?.takeIf { it > 0 },
            seriesTitle = detail?.seriesTitle,
            // Stable TV grouping key — present for episode detail downloads via the
            // main detail button (this path), so they group like startEpisode's.
            seriesContentId = detail?.seriesId,
            seasonNumber = detail?.seasonNumber,
            episodeNumber = detail?.episodeNumber,
            subtitle = detail?.let { d ->
                when {
                    d.seriesTitle != null && d.seasonNumber != null && d.episodeNumber != null ->
                        "${d.seriesTitle} · S${d.seasonNumber}E${d.episodeNumber}"
                    d.year > 0 -> "${d.year}"
                    else -> null
                }
            },
            fileName = version?.fileName,
            container = version?.container,
            mediaType = DownloadMediaType.fromCatalogType(detail?.type).wire,
            overview = detail?.overview,
            // Author drives the Downloads-tab books tiering (author → book) — capture
            // it for ebooks too, not just audiobooks.
            author = detail?.audiobook?.authorNames ?: detail?.ebook?.authorNames,
            narrator = detail?.audiobook?.narratorNames,
            // Truthful PER-FILE duration: a download is one file, and for a
            // multi-part audiobook the offline player treats this as the
            // stream's length — the whole-book total would run the slider and
            // resume in whole-book space against a single part's bytes. Fall
            // back to the audiobook total only when the file has no probed
            // duration.
            durationSeconds = version?.duration?.takeIf { it > 0.0 }
                ?: detail?.audiobook?.totalDurationSeconds?.toDouble(),
            chapters = version?.chapters,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun ItemDetail.versionFor(fileId: Int): FileVersion? =
        versions.firstOrNull { it.fileId == fileId }

    /** Cancel a queued / in-flight download by record id (worker-tag).
     *  Callers that own the local metadata (Downloads tab) clean the sidecar up
     *  themselves; everyone else should use the [fileId] overload. */
    suspend fun cancel(downloadId: String) {
        Log.i(TAG, "cancel: downloadId=$downloadId")
        DownloadWorker.cancelAndAwait(context, downloadId)
    }

    data class CancelScope(
        val serverId: String,
        val profileId: String,
    )

    /** Capture identity synchronously with the UI record being acted on. */
    fun captureCancelScope(): CancelScope {
        val active = serverRegistry.activeEntry.value
        return CancelScope(
            serverId = active?.id ?: DEFAULT_SERVER_ID,
            profileId = active?.profileId ?: DEFAULT_PROFILE_ID,
        )
    }

    /**
     * Cancel and forget in the explicitly captured identity scope. Leaving the
     * row behind made a cancelled download un-restartable; resolving scope
     * after a coroutine dispatch could instead delete the next profile's row.
     */
    suspend fun cancel(
        downloadId: String,
        fileId: Int,
        scope: CancelScope,
    ) {
        cancel(downloadId)
        runCatching { metadataStore.deleteSidecar(scope.serverId, scope.profileId, fileId) }
            .onFailure { Log.w(TAG, "cancel: deleteSidecar failed for fileId=$fileId", it) }
    }

    /** The duplicate-guard skip result. Deliberately not [ApiResult.Success]:
     *  nothing was enqueued, and callers key their "download started" feedback
     *  off success. */
    private fun alreadyActive(): ApiResult<Unit> =
        ApiResult.Error(409, ALREADY_ACTIVE_ERROR, "Already downloading")

    private fun ApiResult<Unit>.isAlreadyActive(): Boolean =
        this is ApiResult.Error && error == ALREADY_ACTIVE_ERROR

    companion object {
        private const val TAG = "DownloadEnqueuer"
        const val DEFAULT_SERVER_ID = "default"
        const val DEFAULT_PROFILE_ID = "default"

        /** Local-only error code: this file already has a live download worker. */
        const val ALREADY_ACTIVE_ERROR = "download_already_active"

        private const val WORK_VISIBILITY_GRACE_MS = 5_000L
    }
}
