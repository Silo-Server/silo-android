package org.siloserver.silo.common.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.DownloadsRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder

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
) : CoroutineWorker(appContext, params) {

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

        Log.i(TAG, "doWork start id=$downloadId fileId=$fileId title=$displayTitle")
        runCatching {
            setForeground(buildForegroundInfo(downloadId, displayTitle, progress = 0, indeterminate = true))
        }.onFailure { Log.w(TAG, "setForeground initial failed", it) }

        var activeUri: String? = null

        try {
            // Transcoded downloads: the server answers create with
            // status=preparing while it produces the artifact; the file GET
            // only serves once the row flips to ready. Poll the list until
            // then (original-quality rows come back ready/queued immediately
            // and skip the loop entirely).
            awaitServerPreparation(downloadId, fileId, displayTitle)
        } catch (e: CancellationException) {
            Log.i(TAG, "doWork cancelled during preparation id=$downloadId")
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "doWork preparation poll IO error id=$downloadId → retry", e)
            return@withContext Result.retry()
        } catch (e: Throwable) {
            Log.e(TAG, "doWork preparation failed id=$downloadId", e)
            repository.recordForFile(fileId)?.let { record ->
                repository.upsertLocal(record.copy(status = DownloadStatus.Failed.wire))
            }
            updateSidecarStatus(
                serverId, profileId, fileId,
                status = DownloadStatus.Failed.wire,
                bytesSent = 0,
                fileSize = 0,
            )
            return@withContext Result.failure()
        }

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
                                repository.recordForFile(fileId)?.let { existing ->
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
            }

            // Server flips status → completed when its serve handler returns;
            // a refresh here ensures the cache reflects that before the
            // worker exits and the UI re-renders.
            val finalUri = activeUri ?: error("download target was not created")
            val finalBytes = storage.partialSize(finalUri)
            storage.completeWrite(finalUri)
            Log.i(TAG, "doWork success id=$downloadId bytes=$finalBytes")
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
            withContext(NonCancellable) {
                // Delete by scope+fileId (not just activeUri): a cancel before the
                // response is classified leaves activeUri null but a prior attempt's
                // partial may still be on disk.
                runCatching { storage.delete(serverId, profileId, fileId) }
            }
            throw e
        } catch (e: IOException) {
            // Transient — let WorkManager retry. KEEP the partial bytes + the
            // persisted localUri/validator so the retry RESUMES via HTTP Range
            // instead of re-downloading from zero. Sidecar stays "downloading".
            Log.w(TAG, "doWork IO error id=$downloadId → retry (resume from partial)", e)
            Result.retry()
        } catch (e: Throwable) {
            // Permanent — clean up local file and let the user retry manually.
            Log.e(TAG, "doWork fatal id=$downloadId", e)
            // Delete by scope+fileId so a partial from any attempt is cleaned up
            // even if this attempt failed before activeUri was assigned.
            runCatching { storage.delete(serverId, profileId, fileId) }
            // Best-effort: publish failed state into the repo + sidecar.
            val record = repository.recordForFile(fileId)
            if (record != null) {
                repository.upsertLocal(record.copy(status = DownloadStatus.Failed.wire))
            }
            updateSidecarStatus(
                serverId, profileId, fileId,
                status = DownloadStatus.Failed.wire,
                bytesSent = 0,
                fileSize = 0,
                localUri = activeUri,
            )
            Result.failure()
        }
    }

    /**
     * Block until the server finishes preparing a transcoded artifact for
     * [downloadId] (record status "preparing" → "ready"/"queued"). Mirrors the
     * Apple client's 15s reconcile poll. No-op when the record is already past
     * preparation (every original-quality download). Throws:
     *  - [IOException] on poll network errors / preparation timeout → retry,
     *  - [IllegalStateException] when the server reports the record failed,
     *    was cancelled, or was revoked → permanent failure.
     */
    private suspend fun awaitServerPreparation(
        downloadId: String,
        fileId: Int,
        displayTitle: String,
    ) {
        // Cheap fast path: nothing known locally, or already past preparing.
        if (repository.recordForFile(fileId)?.statusEnum() != DownloadStatus.Preparing) {
            // After process death the repo cache may be empty while the server
            // is still preparing — one refresh re-learns the real status.
            if (repository.recordForFile(fileId) != null) return
            if (repository.refresh() is ApiResult.NetworkError) {
                throw IOException("could not learn download status before streaming")
            }
        }
        val deadline = System.currentTimeMillis() + PREPARING_TIMEOUT_MS
        var announced = false
        while (true) {
            val record = repository.recordForFile(fileId) ?: return
            when {
                record.status.lowercase() == "revoked" ->
                    throw IllegalStateException("download was revoked by the server")
                record.statusEnum() == DownloadStatus.Failed ||
                    record.statusEnum() == DownloadStatus.Cancelled ->
                    throw IllegalStateException("server reports download ${record.status}")
                record.statusEnum() != DownloadStatus.Preparing -> return
            }
            if (!announced) {
                announced = true
                Log.i(TAG, "doWork waiting on server preparation id=$downloadId")
                runCatching {
                    setForeground(buildForegroundInfo(downloadId, displayTitle, progress = 0, indeterminate = true, statusText = "Preparing…"))
                }.onFailure { Log.w(TAG, "setForeground preparing failed", it) }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("server preparation timed out")
            }
            delay(PREPARING_POLL_INTERVAL_MS)
            if (repository.refresh() is ApiResult.NetworkError) {
                throw IOException("network error while polling preparation status")
            }
        }
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
        bytesSent: Long,
        fileSize: Long,
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
                    record = existing.record.copy(
                        status = status,
                        bytesSent = if (bytesSent > 0) bytesSent else existing.record.bytesSent,
                        fileSize = if (fileSize > 0) fileSize else existing.record.fileSize,
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
            bytesSent = 0,
            fileSize = 0,
            localUri = localUri,
            resumeValidator = validator?.takeIf { it.isNotBlank() } ?: "",
        )
    }

    private fun buildForegroundInfo(
        downloadId: String,
        title: String,
        progress: Int,
        indeterminate: Boolean,
        statusText: String? = null,
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(appContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText ?: if (indeterminate) "Starting…" else "$progress%")
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

        /** Poll cadence while the server transcodes (matches Apple's 15s). */
        private const val PREPARING_POLL_INTERVAL_MS = 15_000L

        /** Generous cap on server-side preparation; past this → retry. */
        private const val PREPARING_TIMEOUT_MS = 30L * 60_000L

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
                .addTag(tagFor(downloadId))
                .build()
            Log.i(TAG, "enqueue id=$downloadId fileId=$fileId wifiOnly=$wifiOnly")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(tagFor(downloadId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, downloadId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(downloadId))
        }
    }
}

internal fun downloadHttpStatusFailure(status: HttpStatusCode): Throwable? = when {
    status.isSuccess() -> null
    status.value >= 500 -> IOException("HTTP ${status.value} while downloading")
    else -> IllegalStateException("HTTP ${status.value} while downloading")
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
