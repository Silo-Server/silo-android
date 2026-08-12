package org.siloserver.silo.common.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class HostedDiagnosticsDeletionWorker(
    appContext: Context,
    params: WorkerParameters,
    private val reports: PendingReportStore,
    private val deleter: HostedDiagnosticsReportDeleter,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        var completedAll = true
        val reportIds = try {
            reports.hostedDeletionIntents()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        reportIds.forEach { reportId ->
            val deleted = try {
                deleter.delete(reportId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (deleted) {
                try {
                    reports.completeHostedDeletion(reportId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    completedAll = false
                }
            } else {
                completedAll = false
            }
        }
        return if (completedAll) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK = "hosted-diagnostics-deletion"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HostedDiagnosticsDeletionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
