package org.siloserver.silo.tv.watchnext

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import org.siloserver.silo.common.data.sync.SyncEngine
import org.siloserver.silo.common.data.sync.SyncWorker
import org.siloserver.silo.common.diagnostics.DiagnosticsCoordinator
import org.siloserver.silo.common.diagnostics.DiagnosticsUploadWorker
import org.siloserver.silo.common.diagnostics.HostedDiagnosticsDeletionWorker
import org.siloserver.silo.common.diagnostics.HostedDiagnosticsReportDeleter
import org.siloserver.silo.common.diagnostics.PendingReportStore
import org.siloserver.silo.repository.SectionRepository
import org.koin.core.context.GlobalContext

/**
 * Hand-rolled WorkerFactory that constructs DI-dependent workers via Koin.
 *
 * TV twin of androidApp's `AppWorkerFactory`: koin-androidx-workmanager's
 * `workManagerFactory()` / `KoinWorkerFactory` silently returns null on
 * this codebase (WM 2.10 + Koin 4.1.0), forcing WorkerFactory to fall back
 * to reflection — which crashes because [WatchNextSyncWorker] takes
 * injected dependencies, not the default `(Context, WorkerParameters)`
 * constructor.
 *
 * Add a `when` branch per new worker class.
 */
class TvWorkerFactory : WorkerFactory() {
    init {
        Log.i(TAG, "TvWorkerFactory constructed")
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        Log.i(TAG, "createWorker called for $workerClassName")
        val koin = GlobalContext.get()
        return when (workerClassName) {
            WatchNextSyncWorker::class.java.name -> {
                Log.i(TAG, "Building WatchNextSyncWorker via Koin")
                WatchNextSyncWorker(
                    appContext = appContext,
                    params = workerParameters,
                    sectionRepository = koin.get<SectionRepository>(),
                    repository = koin.get<WatchNextRepository>(),
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
        private const val TAG = "TvWorkerFactory"
    }
}
