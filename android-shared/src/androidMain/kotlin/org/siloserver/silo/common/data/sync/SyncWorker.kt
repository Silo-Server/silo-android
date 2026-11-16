package org.siloserver.silo.common.data.sync

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox via [SyncEngine] in the background. Constructor-injected by
 * the per-app hand-rolled WorkerFactory (the Koin `worker{}` DSL is dead at
 * runtime on WM 2.10 + Koin 4.1.0 — see `AppWorkerFactory`).
 *
 * Returns [Result.retry] while any op for the scope remains (failed-and-backing-
 * off or not-yet-processed) so WorkManager keeps re-running with its own
 * exponential backoff — a clean partial batch or a backoff row can never strand
 * the outbox. The chain ends only when the scope's outbox is empty.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = inputData.getBoolean(KEY_NOW, false)
        return try {
            // One drain at a time: the main chain and a drain-now request never
            // reset or claim each other's in-flight ops.
            val result = drainLock.withLock { syncEngine.drainOnce() }
            Log.i(
                TAG,
                "drain${if (now) " (now)" else ""}: synced=${result.synced} dropped=${result.dropped} " +
                    "retriable=${result.retriable} remaining=${result.remaining}",
            )
            when {
                // A drain-now request never starts its own retry chain; the main
                // chain carries whatever is left.
                now -> Result.success().also { if (result.hasPendingWork) enqueueFollowUp(applicationContext) }
                result.hasPendingWork -> Result.retry()
                else -> Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "drain failed; will retry", t)
            if (now) Result.success().also { enqueueFollowUp(applicationContext) } else Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_NAME = "silo-outbox-sync"
        private const val NOW_UNIQUE_NAME = "silo-outbox-sync-now"
        private const val KEY_NOW = "now"

        private val drainLock = Mutex()

        /**
         * Drain now, for example once the server is reachable again. [enqueue]
         * keeps a retry already waiting in WorkManager's backoff, which can be
         * hours, so this runs as its own work instead of replacing that chain:
         * nothing running is cancelled (a cancelled membership send waits for
         * the next app start). It waits for a drain already running and then
         * drains once more, so a request made while one finishes isn't lost.
         */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW_UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(now = true))
        }

        /**
         * A main-chain drain after whatever that chain is doing now: KEEP would
         * drop it while a drain finishes, leaving no retry for the work left.
         */
        private fun enqueueFollowUp(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }

        /**
         * Enqueue a single outbox drain. [ExistingWorkPolicy.KEEP] coalesces
         * bursts of mutations into one drain; the DB-backed outbox means a
         * queued duplicate would add no value.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
        }

        private fun request(now: Boolean = false) = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_NOW to now))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
