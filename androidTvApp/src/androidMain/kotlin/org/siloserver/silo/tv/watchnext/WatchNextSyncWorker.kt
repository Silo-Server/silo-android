package org.siloserver.silo.tv.watchnext

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.siloserver.silo.repository.SectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic (and on-demand) sync worker that mirrors the server's home-screen
 * "continue watching" / "next up" sections into Android TV's Watch Next channel
 * via [WatchNextRepository.diffAndApply].
 *
 * Constructed by [TvWorkerFactory], installed via `WorkManager.initialize`
 * in `SiloTvApplication` (KoinWorkerFactory was silently returning
 * null on WM 2.10 + Koin 4.1.0 — see androidApp's AppWorkerFactory).
 */
class WatchNextSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sectionRepository: SectionRepository,
    private val repository: WatchNextRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val completed = syncWatchNextHome(sectionRepository, repository.writeGate, { isStopped }) { fields, run, authority ->
            repository.diffAndApply(fields, run, authority)
        }
        if (completed) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME_PERIODIC = "watch_next_sync_periodic"
        const val UNIQUE_NAME_ONESHOT = "watch_next_sync_oneshot"
    }
}
