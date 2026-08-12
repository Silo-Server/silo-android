package org.siloserver.silo.android.downloads

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.siloserver.silo.common.data.sync.SyncEngine
import org.siloserver.silo.common.data.sync.SyncWorker
import org.siloserver.silo.common.downloads.DownloadStorage
import org.siloserver.silo.common.downloads.DownloadSubscriptionEvaluatorFactory
import org.siloserver.silo.common.downloads.DownloadSubscriptionWorker
import org.siloserver.silo.common.downloads.DownloadWorker
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadWorker
import org.siloserver.silo.common.diagnostics.HostedDiagnosticsDeletionWorker
import org.siloserver.silo.common.diagnostics.HostedDiagnosticsReportDeleter
import org.siloserver.silo.common.diagnostics.PendingReportStore
import org.siloserver.silo.repository.DownloadSubscriptionRepository
import org.siloserver.silo.repository.DownloadsRepository
import io.ktor.client.HttpClient
import org.koin.core.context.GlobalContext

/**
 * Hand-rolled WorkerFactory that constructs DI-dependent workers via Koin.
 *
 * Replaces koin-androidx-workmanager's `workManagerFactory()` /
 * `KoinWorkerFactory` because that path was silently returning null for
 * DownloadWorker on this codebase (WM 2.10 + Koin 4.1.0), forcing
 * WorkerFactory to fall back to reflection — which crashed because
 * DownloadWorker takes injected dependencies, not the default
 * `(Context, WorkerParameters)` constructor.
 *
 * Add a `when` branch per new worker class.
 */
class AppWorkerFactory : WorkerFactory() {
    init {
        Log.i(TAG, "AppWorkerFactory constructed")
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        Log.i(TAG, "createWorker called for $workerClassName")
        val koin = GlobalContext.get()
        return when (workerClassName) {
            DownloadWorker::class.java.name -> {
                Log.i(TAG, "Building DownloadWorker via Koin")
                DownloadWorker(
                    appContext = appContext,
                    params = workerParameters,
                    repository = koin.get<DownloadsRepository>(),
                    storage = koin.get<DownloadStorage>(),
                    metadataStore = koin.get<org.siloserver.silo.common.downloads.DownloadMetadataStore>(),
                    httpClient = koin.get<HttpClient>(),
                    activeScope = {
                        koin.get<org.siloserver.silo.network.ServerRegistry>().activeServerId.value to
                            koin.get<org.siloserver.silo.repository.ProfileRepository>().getActiveProfileId()
                    },
                )
            }
            DownloadSubscriptionWorker::class.java.name -> {
                Log.i(TAG, "Building DownloadSubscriptionWorker via Koin")
                DownloadSubscriptionWorker(
                    appContext = appContext,
                    params = workerParameters,
                    repository = koin.get<DownloadSubscriptionRepository>(),
                    evaluatorFactory = koin.get<DownloadSubscriptionEvaluatorFactory>(),
                    serverRegistry = koin.get<org.siloserver.silo.network.ServerRegistry>(),
                    profileRepository = koin.get<org.siloserver.silo.repository.ProfileRepository>(),
                )
            }
            SyncWorker::class.java.name -> {
                Log.i(TAG, "Building SyncWorker via Koin")
                SyncWorker(
                    appContext = appContext,
                    params = workerParameters,
                    syncEngine = koin.get<SyncEngine>(),
                )
            }
            DiagnosticsUploadWorker::class.java.name -> {
                Log.i(TAG, "Building DiagnosticsUploadWorker via Koin")
                DiagnosticsUploadWorker(
                    appContext = appContext,
                    params = workerParameters,
                    coordinator = koin.get<DiagnosticsCoordinator>(),
                )
            }
            HostedDiagnosticsDeletionWorker::class.java.name -> {
                Log.i(TAG, "Building HostedDiagnosticsDeletionWorker via Koin")
                HostedDiagnosticsDeletionWorker(
                    appContext = appContext,
                    params = workerParameters,
                    reports = koin.get<PendingReportStore>(),
                    deleter = koin.get<HostedDiagnosticsReportDeleter>(),
                )
            }
            else -> {
                Log.w(TAG, "No factory match for $workerClassName — returning null")
                null
            }
        }
    }

    companion object {
        private const val TAG = "AppWorkerFactory"
    }
}
