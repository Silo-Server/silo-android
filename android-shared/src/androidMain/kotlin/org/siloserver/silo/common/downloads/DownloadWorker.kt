package org.siloserver.silo.common.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import org.siloserver.silo.common.diagnostics.DiagnosticsDownloadLogger
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.repository.DownloadsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal fun DownloadRecord.withWorkerStatus(
    status: String,
    bytesSent: Long? = null,
    fileSize: Long? = null,
): DownloadRecord = copy(
    status = status,
    bytesSent = bytesSent ?: this.bytesSent,
    fileSize = fileSize ?: this.fileSize,
)

/**
 * Streams `GET /api/v1/downloads/{id}/file` to the local
 * `<filesDir>/downloads/<serverId>/<profileId>/<fileId>/<original-name>`
 * location via [DownloadStorage], reporting progress to WorkManager at
 * most every ~200ms; the foreground notification is rebuilt only when
 * the integer percent actually changes.
 *
 * Constructed by Koin's [org.koin.androidx.workmanager.factory.KoinWorkerFactory]
 * — see the `worker { ... }` registration in `androidModule`.
 *
 * **Failure handling.** On a transient IO error we return [Result.retry] so
 * WorkManager schedules a fresh attempt; the partial bytes on disk are
 * deleted first (no resume in v1) so the next attempt starts clean.
 */
class DownloadWorker(
    private val appContext: Context,
    params: androidx.work.WorkerParameters,
    private val repository: DownloadsRepository,
    private val storage: DownloadStorage,
    private val metadataStore: DownloadMetadataStore,
    private val httpClient: HttpClient,
    // Scope guard for the in-memory UI pushes: `repository` only mirrors the
    // ACTIVE scope's records, and record lookup is by mediaFileId alone — a
    // worker running for a background scope with a colliding fileId would
    // paint progress/Failed onto the wrong scope's card. Null = legacy call
    // sites keep the old (unguarded) behavior.
    private val activeScope: (suspend () -> Pair<String?, String?>)? = null,
) : CoroutineWorker(appContext, params) {

    private suspend fun uiPushAllowed(serverId: String, profileId: String): Boolean {
        val scope = activeScope ?: return true
        // Resolving the active scope must never abort an otherwise-healthy
        // download — this is called inside doWork()'s own catch block, where a
        // throw would escape uncaught. A failure means "can't confirm this is
        // the active scope", so skip the UI push (safe: the record just isn't
        // repainted; the download itself is unaffected).
        val (activeServer, activeProfile) = runCatching { scope() }.getOrNull() ?: return false
        return activeServer == serverId && activeProfile == profileId
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return@withContext Result.failure()
        val fileId = inputData.getInt(KEY_FILE_ID, -1)
        val serverId = inputData.getString(KEY_SERVER_ID)
            ?: return@withContext Result.failure()
        val profileId = inputData.getString(KEY_PROFILE_ID)
            ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME)
        val container = inputData.getString(KEY_CONTAINER)
        val mediaType = inputData.getString(KEY_MEDIA_TYPE)
        val displayTitle = inputData.getString(KEY_DISPLAY_TITLE) ?: "Download"
        if (fileId < 0) return@withContext Result.failure()
        val lifetimeLease = DownloadWorkerLifetime.acquire(downloadId)
            ?: return@withContext Result.failure()

        try {
        Log.i(TAG, "doWork start id=$downloadId fileId=$fileId title=$displayTitle")
        DiagnosticsDownloadLogger.event("download started")
        runCatching {
            setForeground(buildForegroundInfo(downloadId, displayTitle, progress = 0, indeterminate = true))
        }.onFailure { Log.w(TAG, "setForeground initial failed", it) }

        var activeUri: String? = null

        // Resume state (survives process death + WorkManager retries): the partial's
        // uri + the validator captured at download start. Resume offset is the REAL
        // on-disk size (fd stat), never the metadata SIZE column (stale while pending).
        val existing = runCatching { metadataStore.readSidecar(serverId, profileId, fileId) }.getOrNull()
        val resumeUri = existing?.localUri
        val resumeFrom = resumeUri?.let { storage.partialSize(it) } ?: 0L
        val resumeValidator = existing?.resumeValidator
        // Resume ONLY with a validator: an unvalidated Range append would silently
        // corrupt the file if the source changed (the server can't tell us). No
        // validator → behave as a fresh download (no Range header).
        val canResume = resumeFrom > 0 && resumeUri != null && !resumeValidator.isNullOrBlank()

        try {
            httpClient.prepareGet("/api/v1/downloads/$downloadId/file") {
                // Streaming download: drop the global 60s TOTAL-request timeout (it
                // guillotines large files mid-transfer) and keep only a socket/idle
                // timeout so a genuinely stalled connection still fails → retry.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = IDLE_TIMEOUT_MS
                }
                // Byte ranges index the identity-coded entity; refuse transfer
                // re-encoding so written bytes line up with requested offsets.
                header(HttpHeaders.AcceptEncoding, "identity")
                if (canResume) {
                    header(HttpHeaders.Range, "bytes=$resumeFrom-")
                    // If-Range: server returns 206 only if the source is unchanged;
                    // a changed file yields a 200 (full) → we restart cleanly.
                    header(HttpHeaders.IfRange, resumeValidator!!)
                }
            }.execute { response ->
                // 416 = our partial is invalid against the current server file
                // (shrank/changed). Drop it and retry fresh (no Range next time).
                if (canResume && response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    // Drop the partial; partialSize→0 makes the retry a fresh GET.
                    storage.delete(serverId, profileId, fileId)
                    throw IOException("range not satisfiable — restarting fresh")
                }
                // A non-original (remux/transcode) row is still `preparing`: the
                // /file endpoint answers 409 `download_inactive` until the
                // artifact is `ready` (docs §4.5). That is transient, NOT the
                // fatal "revoked" case — throw the preparing sentinel so we wait
                // (WorkManager backoff) and re-probe on the next attempt instead
                // of deleting the download. Other 409s stay fatal below.
                if (response.status == HttpStatusCode.Conflict) {
                    val errorCode = runCatching { response.bodyAsText() }
                        .getOrNull()
                        ?.let { extractDownloadErrorCode(it) }
                    if (errorCode == DOWNLOAD_INACTIVE_ERROR) throw DownloadPreparingException()
                }
                downloadHttpStatusFailure(response.status)?.let { throw it }

                val rangeInfo = parseContentRange(response.headers[HttpHeaders.ContentRange])
                val resuming = canResume &&
                    response.status == HttpStatusCode.PartialContent &&
                    rangeInfo != null && rangeInfo.start == resumeFrom

                val total: Long
                var written: Long
                val out: java.io.OutputStream
                if (resuming) {
                    // 206 with a matching range → append to the existing partial.
                    val append = storage.openAppend(resumeUri!!)
                        ?: throw IOException("could not open partial for append")
                    activeUri = resumeUri
                    total = rangeInfo!!.total ?: -1L
                    written = resumeFrom
                    Log.i(TAG, "doWork resume id=$downloadId from=$resumeFrom total=$total")
                    DiagnosticsDownloadLogger.event("download resumed")
                    out = append
                } else {
                    // Fresh (200, or no usable partial): (re)create the target and
                    // persist its uri + validator NOW so a later attempt can resume.
                    val resolvedFileName = downloadFileNameForTarget(
                        catalogFileName = fileName,
                        contentDisposition = response.headers["Content-Disposition"],
                    )
                    val fresh = storage.prepareWrite(serverId, profileId, fileId, resolvedFileName, container, mediaType)
                    activeUri = fresh.uriString
                    total = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    written = 0L
                    persistResumeStart(serverId, profileId, fileId, fresh.uriString, captureValidator(response))
                    out = fresh.openOutputStream()
                }

                val channel = response.bodyAsChannel()
                val throttle = DownloadProgressThrottle()

                val buf = ByteArray(BUFFER_BYTES)
                // Ktor 3.x ByteReadChannel → java.io.InputStream bridge.
                // Avoids version-fragile ByteReadChannel read APIs and keeps
                // the streaming copy + progress reporting on the same thread.
                channel.toInputStream().use { input ->
                    out.use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n

                            val decision = throttle.onBytes(System.currentTimeMillis(), written, total)
                            if (decision.report) {
                                setProgress(workDataOf(KEY_BYTES_WRITTEN to written, KEY_TOTAL_BYTES to total))
                                // Rebuilding the notification (and its cancel
                                // PendingIntent) is the expensive part — only
                                // do it when the visible percent changed.
                                if (decision.updateForeground) {
                                    setForeground(buildForegroundInfo(downloadId, displayTitle, progress = decision.percent, indeterminate = total <= 0))
                                }
                                // Push progress into the shared repo so any
                                // currently-foregrounded UI re-renders without
                                // a round-trip GET /downloads.
                                if (uiPushAllowed(serverId, profileId)) repository.recordForFile(fileId)?.let { existing ->
                                    repository.upsertLocal(
                                        existing.copy(
                                            bytesSent = written,
                                            fileSize = if (total > 0) total else existing.fileSize,
                                            status = DownloadStatus.Downloading.wire,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // A stream that ends cleanly short of the declared length is a
                // truncated transfer, not a completed download — throw the
                // retriable IOException so the resume logic fetches the rest
                // instead of committing a short file as Completed. Unknown
                // length (total <= 0) can't be verified; keep current behavior.
                if (total > 0 && written != total) {
                    throw IOException("truncated download: $written of $total bytes")
                }
            }

            // Server flips status → completed when its serve handler returns;
            // a refresh here ensures the cache reflects that before the
            // worker exits and the UI re-renders.
            val pendingUri = activeUri ?: error("download target was not created")
            val finalBytes = storage.partialSize(pendingUri)
            val finalUri = storage.completeWrite(pendingUri)
            Log.i(TAG, "doWork success id=$downloadId bytes=$finalBytes")
            DiagnosticsDownloadLogger.event("download completed")
            repository.refresh()
            // Update the sidecar to status=completed. Enqueuer wrote the
            // initial sidecar with title + poster; we just flip status here
            // so it survives an offline app launch. Clear the resume validator
            // (download is done — nothing to resume).
            updateSidecarStatus(
                serverId, profileId, fileId,
                status = org.siloserver.silo.model.download.DownloadStatus.Completed.wire,
                bytesSent = finalBytes,
                fileSize = finalBytes,
                localUri = finalUri,
                resumeValidator = "",
            )
            Result.success(workDataOf(KEY_BYTES_WRITTEN to finalBytes, KEY_TOTAL_BYTES to finalBytes))
        } catch (e: CancellationException) {
            // Worker stopped — user cancel (notification action /
            // DownloadEnqueuer.cancel → cancelAllWorkByTag) or a
            // constraint / quota stop. Not a failure: drop the partial
            // bytes but leave the repo record + sidecar status alone.
            // A constraint-stop must keep "downloading" state so the
            // WorkManager retry restarts cleanly; a user cancel is
            // finalized by the record-delete path, not here. Writing
            // Failed here is what used to paint cancelled / paused
            // downloads with a red badge and delete-then-fail them.
            Log.i(TAG, "doWork cancelled id=$downloadId")
            DiagnosticsDownloadLogger.event("download cancelled")
            withContext(NonCancellable) {
                // Delete by scope+fileId (not just activeUri): a cancel before the
                // response is classified leaves activeUri null but a prior attempt's
                // partial may still be on disk.
                runCatching { storage.delete(serverId, profileId, fileId) }
            }
            throw e
        } catch (e: DownloadPreparingException) {
            // Server is still building the remux/transcode artifact. No bytes
            // reached disk (we never got past the range GET), so there is
            // nothing to clean up and the sidecar stays as-is. Bound the wait:
            // a genuinely stuck prepare eventually fails instead of retrying
            // forever. The server keeps any finished artifact, so a manual retry
            // after this cap finds it `ready` and downloads instantly.
            if (runAttemptCount >= MAX_PREPARE_ATTEMPTS) {
                Log.w(TAG, "doWork prepare gave up id=$downloadId after $runAttemptCount attempts")
                DiagnosticsDownloadLogger.error("download preparation failed")
                failPermanently(e, downloadId, serverId, profileId, fileId, activeUri)
            } else {
                Log.i(TAG, "doWork preparing id=$downloadId attempt=$runAttemptCount → retry (awaiting ready)")
                DiagnosticsDownloadLogger.event("download waiting for preparation")
                Result.retry()
            }
        } catch (e: IOException) {
            if (isDiskFullError(e)) {
                // Disk full — retrying can never succeed while the partial
                // itself squats on the remaining space. Fail permanently so
                // the bytes are released and the user sees the failure.
                failPermanently(e, downloadId, serverId, profileId, fileId, activeUri)
            } else {
                // Transient — let WorkManager retry. KEEP the partial bytes + the
                // persisted localUri/validator so the retry RESUMES via HTTP Range
                // instead of re-downloading from zero. Sidecar stays "downloading".
                Log.w(TAG, "doWork IO error id=$downloadId → retry (resume from partial)", e)
                DiagnosticsDownloadLogger.warning("download retry scheduled")
                Result.retry()
            }
        } catch (e: Throwable) {
            failPermanently(e, downloadId, serverId, profileId, fileId, activeUri)
        }
        } finally {
            lifetimeLease.close()
        }
    }

    /** Permanent failure — clean up local file and let the user retry manually. */
    private suspend fun failPermanently(
        e: Throwable,
        downloadId: String,
        serverId: String,
        profileId: String,
        fileId: Int,
        activeUri: String?,
    ): Result {
        Log.e(TAG, "doWork fatal id=$downloadId", e)
        DiagnosticsDownloadLogger.error("download failed")
        // Delete by scope+fileId so a partial from any attempt is cleaned up
        // even if this attempt failed before activeUri was assigned.
        runCatching { storage.delete(serverId, profileId, fileId) }
        // Best-effort: publish failed state into the repo + sidecar.
        val record = if (uiPushAllowed(serverId, profileId)) repository.recordForFile(fileId) else null
        if (record != null) {
            repository.upsertLocal(
                record.withWorkerStatus(
                    status = DownloadStatus.Failed.wire,
                    bytesSent = 0,
                    fileSize = 0,
                ),
            )
        }
        updateSidecarStatus(
            serverId, profileId, fileId,
            status = DownloadStatus.Failed.wire,
            bytesSent = 0,
            fileSize = 0,
            localUri = activeUri,
        )
        return Result.failure()
    }

    /**
     * Read-modify-write the sidecar on disk so its status / bytesSent /
     * fileSize match the worker's current view. No-op if the sidecar
     * doesn't exist yet (Enqueuer always writes it at download start, so
     * this should only happen for legacy / corrupted state).
     */
    private suspend fun updateSidecarStatus(
        serverId: String,
        profileId: String,
        fileId: Int,
        status: String,
        bytesSent: Long? = null,
        fileSize: Long? = null,
        localUri: String? = null,
        fileName: String? = null,
        // null = keep existing; "" = clear (download finished/failed); else set.
        resumeValidator: String? = null,
    ) {
        runCatching {
            val existing = metadataStore.readSidecar(serverId, profileId, fileId) ?: return@runCatching
            metadataStore.writeSidecar(
                serverId, profileId,
                existing.copy(
                    record = existing.record.withWorkerStatus(
                        status = status,
                        bytesSent = bytesSent,
                        fileSize = fileSize,
                    ),
                    localUri = localUri ?: existing.localUri,
                    fileName = fileName?.takeIf { it.isNotBlank() } ?: existing.fileName,
                    resumeValidator = when {
                        resumeValidator == null -> existing.resumeValidator
                        resumeValidator.isBlank() -> null
                        else -> resumeValidator
                    },
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "updateSidecarStatus failed for fileId=$fileId", it) }
    }

    /**
     * Persist the partial's uri + resume validator the moment a fresh download
     * starts, so a WorkManager retry — or a relaunch after process death — can
     * resume via HTTP Range instead of re-downloading from zero.
     */
    private suspend fun persistResumeStart(
        serverId: String,
        profileId: String,
        fileId: Int,
        localUri: String,
        validator: String?,
    ) {
        updateSidecarStatus(
            serverId, profileId, fileId,
            status = DownloadStatus.Downloading.wire,
            localUri = localUri,
            resumeValidator = validator?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    private fun buildForegroundInfo(
        downloadId: String,
        title: String,
        progress: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(appContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Starting…" else "$progress%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .build()

        val notificationId = notificationIdFor(downloadId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val NOTIFICATION_CHANNEL_ID = "silo_downloads"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_FILE_ID = "file_id"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_CONTAINER = "container"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_DISPLAY_TITLE = "display_title"
        const val KEY_BYTES_WRITTEN = "bytes"
        const val KEY_TOTAL_BYTES = "total"

        private const val BUFFER_BYTES = 64 * 1024

        /** Idle (socket) timeout for the streaming download. The total-request
         *  timeout is disabled per-request; this still fails a stalled connection. */
        private const val IDLE_TIMEOUT_MS = 60_000L

        /**
         * Max WorkManager attempts to spend waiting on a `preparing` artifact
         * (409 `download_inactive`) before failing. With [PREPARE_BACKOFF_SECONDS]
         * linear backoff this is roughly `MAX_PREPARE_ATTEMPTS × backoff` of
         * polling (~15 min at the defaults) — enough for a typical transcode,
         * bounded so a stuck prepare doesn't retry forever.
         */
        private const val MAX_PREPARE_ATTEMPTS = 30
        private const val PREPARE_BACKOFF_SECONDS = 30L

        fun tagFor(downloadId: String): String = "download_$downloadId"
        private fun notificationIdFor(downloadId: String): Int =
            // Stable per download, avoids collisions across concurrent workers.
            ("dl_$downloadId").hashCode() and 0x7FFFFFFF

        /**
         * Enqueue a unique one-time download for [downloadId]. Caller supplies
         * the `(serverId, profileId, fileId)` triple + a display title for
         * the notification. The [wifiOnly] flag drives the work constraint.
         *
         * Cancel via `WorkManager.cancelAllWorkByTag(tagFor(downloadId))`.
         */
        fun enqueue(
            context: Context,
            downloadId: String,
            fileId: Int,
            serverId: String,
            profileId: String,
            fileName: String?,
            container: String?,
            mediaType: String?,
            displayTitle: String,
            wifiOnly: Boolean,
        ) {
            val data = workDataOf(
                KEY_DOWNLOAD_ID to downloadId,
                KEY_FILE_ID to fileId,
                KEY_SERVER_ID to serverId,
                KEY_PROFILE_ID to profileId,
                KEY_FILE_NAME to fileName,
                KEY_CONTAINER to container,
                KEY_MEDIA_TYPE to mediaType,
                KEY_DISPLAY_TITLE to displayTitle,
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // Linear backoff so a `preparing` transcode is re-probed on a
                // predictable cadence (see MAX_PREPARE_ATTEMPTS) rather than the
                // exponentially-widening default that would over-wait a ready row.
                .setBackoffCriteria(BackoffPolicy.LINEAR, PREPARE_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(tagFor(downloadId))
                .build()
            Log.i(TAG, "enqueue id=$downloadId fileId=$fileId wifiOnly=$wifiOnly")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(tagFor(downloadId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, downloadId: String) {
            DownloadWorkerLifetime.beginCancellation(downloadId)
            WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(downloadId))
        }

        /**
         * Cancellation barrier for destructive identity cleanup. The normal UI
         * API remains fire-and-forget, but a server purge must know the worker
         * has stopped before deleting the Room rows it can otherwise recreate.
         */
        suspend fun cancelAndAwait(context: Context, downloadId: String) {
            DownloadWorkerLifetime.beginCancellation(downloadId)
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context)
                    .cancelUniqueWork(tagFor(downloadId))
                    .result
                    .get(CANCEL_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            DownloadWorkerLifetime.awaitIdle(downloadId)
        }

        private const val CANCEL_ACK_TIMEOUT_SECONDS = 30L
    }
}

/** Server error code (docs §12) meaning the row's artifact is not servable
 *  yet — for a fresh remux/transcode row that means "still preparing". */
internal const val DOWNLOAD_INACTIVE_ERROR = "download_inactive"

/**
 * A 409 `download_inactive` while the server is still preparing a remux/
 * transcode artifact (issue #20). Retriable — wait for the row to reach
 * `ready` — NOT a permanent failure. Subclasses [IOException] so it flows
 * through the retry-friendly plumbing, but doWork catches it FIRST to apply
 * the bounded preparing-retry cap instead of resuming/deleting a partial.
 */
private class DownloadPreparingException : IOException("download artifact still preparing")

/**
 * Pull the `error` code out of the server's flat error envelope
 * (`{"error":"download_inactive","message":"..."}`, docs §12). Returns null
 * when absent/malformed. Pure + string-only so it stays unit-testable without
 * a live HTTP response.
 */
internal fun extractDownloadErrorCode(body: String): String? =
    downloadErrorCodeRegex.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

private val downloadErrorCodeRegex = Regex("""(?i)"error"\s*:\s*"([^"]*)"""")

internal fun downloadHttpStatusFailure(status: HttpStatusCode): Throwable? = when {
    status.isSuccess() -> null
    status.value >= 500 -> IOException("HTTP ${status.value} while downloading")
    // Transient client statuses — matches SyncEngine's classification
    // (401 auth refresh, 408 request timeout, 429 rate limit): retry
    // instead of deleting the partial and marking the download Failed.
    status.value == 401 || status.value == 408 || status.value == 429 ->
        IOException("HTTP ${status.value} while downloading")
    else -> IllegalStateException("HTTP ${status.value} while downloading")
}

/**
 * True when [e] (or anything in its cause chain) is a disk-full error.
 * Android surfaces ENOSPC as an IOException whose message contains either
 * the errno name or the strerror text — there is no typed exception for it.
 * Depth-capped so a pathological self-referential cause chain can't spin.
 */
internal fun isDiskFullError(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < 16) {
        val message = cause.message.orEmpty()
        if (message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true)
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}

/** Parsed `Content-Range: bytes start-end/total` (total null for `*`). Returns
 *  null when malformed or inconsistent (end<start, or total<=end). */
internal data class ContentRangeInfo(val start: Long, val end: Long, val total: Long?)

internal fun parseContentRange(header: String?): ContentRangeInfo? {
    val match = contentRangeRegex.find(header?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val totalToken = match.groupValues[3]
    val total = if (totalToken == "*") null else (totalToken.toLongOrNull() ?: return null)
    if (total != null && total <= end) return null
    return ContentRangeInfo(start, end, total)
}

private val contentRangeRegex = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

/** Strong HTTP validator for `If-Range`: a strong ETag, else `Last-Modified`.
 *  Weak ETags (`W/"…"`) are skipped — they're invalid for byte-range If-Range. */
internal fun captureValidator(response: HttpResponse): String? {
    val etag = response.headers[HttpHeaders.ETag]?.trim()?.takeIf { it.isNotEmpty() }
    if (etag != null && !etag.startsWith("W/")) return etag
    return response.headers[HttpHeaders.LastModified]?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun downloadFileNameForTarget(
    catalogFileName: String?,
    contentDisposition: String?,
): String? =
    catalogFileName?.trim()?.takeIf { it.isNotBlank() }
        ?: contentDispositionFileName(contentDisposition)

private fun contentDispositionFileName(value: String?): String? {
    val header = value?.takeIf { it.isNotBlank() } ?: return null
    encodedFileNameRegex.find(header)?.groupValues?.getOrNull(1)
        ?.trim()
        ?.unquoteHttpValue()
        ?.decodeRfc5987()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return plainFileNameRegex.find(header)?.groupValues?.getOrNull(1)
        ?.trim()
        ?.unquoteHttpValue()
        ?.takeIf { it.isNotBlank() }
}

private val encodedFileNameRegex = Regex("""(?i)(?:^|;)\s*filename\*\s*=\s*("[^"]*"|[^;]*)""")
private val plainFileNameRegex = Regex("""(?i)(?:^|;)\s*filename\s*=\s*("(?:\\.|[^"])*"|[^;]*)""")

private fun String.unquoteHttpValue(): String {
    val trimmed = trim()
    if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') return trimmed
    return trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
}

private fun String.decodeRfc5987(): String {
    val encoded = substringAfter("''", missingDelimiterValue = this)
    return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(encoded)
}
