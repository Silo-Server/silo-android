package org.siloserver.silo.common.downloads

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadStatusEvent
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.repository.DownloadsRepository
import java.util.concurrent.TimeUnit

/**
 * Tells the server's managed registry what this device holds for one download
 * (`PATCH /api/v2/downloads/{id}`: `downloading` when bytes start, `completed`
 * once the file is published). The file route never marks completion itself,
 * so without this report the server row stays `ready`.
 *
 * Runs as its own network-constrained unique job per download, separate from
 * the transfer, so an event recorded offline or cut short by process death is
 * sent once the device is back online. A newer event replaces a pending older
 * one; a retry resends the same event, which the server acknowledges without
 * changing the entry.
 */
class DownloadStatusWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: DownloadsRepository,
    private val authorities: DurableLoginAuthorityProvider,
    private val devices: DeviceMetadataProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val event = DownloadStatusEvent(
            status = inputData.getString(KEY_STATUS) ?: return Result.failure(),
            updatedAt = inputData.getString(KEY_UPDATED_AT) ?: return Result.failure(),
            revision = inputData.getInt(KEY_REVISION, 0),
        )
        val serverId = inputData.getString(KEY_SERVER_ID) ?: return Result.failure()
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        if (runAttemptCount >= MAX_ATTEMPTS) {
            Log.w(TAG, "status report gave up id=$downloadId status=${event.status} after $runAttemptCount attempts")
            return Result.failure()
        }
        // Only the owner the download ran for can report it. Another login or
        // profile may be active for now; wait for it to come back.
        val authority = authorities.snapshotDurableLoginAuthority() ?: return Result.retry()
        if (!downloadWorkMatchesOwner(inputData.getString(KEY_LOGIN_ID), inputData.getString(KEY_ORIGIN),
                inputData.getString(KEY_DEVICE_ID), serverId, profileId, authority, devices.current()?.id)) return Result.retry()

        val result = repository.reportStatus(downloadId, event, authority)
        return when (downloadStatusReportOutcome(result)) {
            DownloadStatusReportOutcome.Settled -> {
                Log.i(TAG, "status report settled id=$downloadId status=${event.status} answer=${result.describe()}")
                Result.success()
            }
            DownloadStatusReportOutcome.Reconcile -> {
                // The entry's revision moved on, so this event describes replaced
                // bytes. Drop it and read the registry instead.
                Log.i(TAG, "status report superseded id=$downloadId revision=${event.revision}")
                repository.refresh()
                Result.success()
            }
            DownloadStatusReportOutcome.RetryLater -> {
                Log.i(TAG, "status report retry id=$downloadId status=${event.status} answer=${result.describe()}")
                Result.retry()
            }
        }
    }

    private fun ApiResult<*>.describe(): String = when (this) {
        is ApiResult.Success -> "ok"
        is ApiResult.Error -> "$code ${error.ifBlank { message }}"
        is ApiResult.NetworkError -> "network"
    }

    companion object {
        private const val TAG = "DownloadStatusWorker"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_STATUS = "status"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_REVISION = "revision"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LOGIN_ID = "login_id"
        const val KEY_ORIGIN = "origin"

        /** Exponential from [BACKOFF_SECONDS] (WorkManager caps each wait at
         *  5 hours), so this spans a few days before the report is dropped. */
        private const val MAX_ATTEMPTS = 20
        private const val BACKOFF_SECONDS = 30L

        fun uniqueName(downloadId: String): String = "download_status_$downloadId"

        /** Queue [event] for [downloadId], replacing a report still waiting to go out. */
        fun enqueue(
            context: Context,
            downloadId: String,
            event: DownloadStatusEvent,
            serverId: String,
            profileId: String,
            deviceId: String?,
            loginId: String?,
            origin: String?,
        ) {
            val request = OneTimeWorkRequestBuilder<DownloadStatusWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOWNLOAD_ID to downloadId,
                        KEY_STATUS to event.status,
                        KEY_UPDATED_AT to event.updatedAt,
                        KEY_REVISION to event.revision,
                        KEY_SERVER_ID to serverId,
                        KEY_PROFILE_ID to profileId,
                        KEY_DEVICE_ID to deviceId,
                        KEY_LOGIN_ID to loginId,
                        KEY_ORIGIN to origin,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            Log.i(TAG, "enqueue id=$downloadId status=${event.status} revision=${event.revision}")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName(downloadId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Drop a report still waiting to go out: the transfer it describes was
         *  cancelled or failed, so the server row must not move to `downloading`. */
        fun cancel(context: Context, downloadId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(downloadId))
        }
    }
}

internal enum class DownloadStatusReportOutcome { Settled, Reconcile, RetryLater }

/**
 * What the server's answer means for a queued status event.
 *
 * - `409`: the revision changed; the event describes replaced bytes.
 * - `400`/`422`: most often the event time is ahead of the server's clock
 *   (future times are rejected), which a later retry of the same event fixes.
 * - `404`/`403` and other client errors: the entry is gone, not reportable
 *   (preparing, failed, revoked), or downloads are off; resending cannot help.
 * - Profile PIN verification, auth, throttling, server errors, network loss and identity changes are transient.
 */
internal fun downloadStatusReportOutcome(result: ApiResult<DownloadRecord>): DownloadStatusReportOutcome = when (result) {
    is ApiResult.Success -> DownloadStatusReportOutcome.Settled
    is ApiResult.NetworkError -> DownloadStatusReportOutcome.RetryLater
    is ApiResult.Error -> when (result.code) {
        409 -> DownloadStatusReportOutcome.Reconcile
        403 -> if (result.error == "profile_verification_required") DownloadStatusReportOutcome.RetryLater else DownloadStatusReportOutcome.Settled
        0, 400, 401, 408, 422, 429 -> DownloadStatusReportOutcome.RetryLater
        in 500..599 -> DownloadStatusReportOutcome.RetryLater
        else -> DownloadStatusReportOutcome.Settled
    }
}
