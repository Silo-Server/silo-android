package org.siloserver.silo.common.diagnostics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class DiagnosticsUploadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: DiagnosticsCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val reportId = inputData.getString(KEY_REPORT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        coordinator.start()
        coordinator.refresh()
        return when (coordinator.uploadAutomatically(reportId)) {
            DiagnosticsUploadDecision.KeptRetryable,
            DiagnosticsUploadDecision.KeptUnavailable,
            DiagnosticsUploadDecision.KeptIdentityChanged,
            is DiagnosticsUploadDecision.HostedProcessing,
            -> Result.retry()
            is DiagnosticsUploadDecision.Uploaded,
            DiagnosticsUploadDecision.KeptTooLarge,
            DiagnosticsUploadDecision.KeptServerUpdateRequired,
            DiagnosticsUploadDecision.KeptInvalid,
            -> Result.success()
            DiagnosticsUploadDecision.KeptConsentReviewRequired -> {
                coordinator.refresh()
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_REPORT_ID = "report_id"
        private const val UNIQUE_PREFIX = "diagnostics-upload-"

        fun enqueue(context: Context, reportId: String) {
            require(reportId.isNotBlank())
            val request = OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
                .setInputData(workDataOf(KEY_REPORT_ID to reportId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_PREFIX + reportId,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
