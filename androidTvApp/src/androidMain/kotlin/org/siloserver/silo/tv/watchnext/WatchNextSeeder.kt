package org.siloserver.silo.tv.watchnext

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager surface for the Watch Next channel.
 *
 * - [seedNow] enqueues an expedited one-shot sync — call right after
 *   auth-success and profile-select so the launcher row reflects the
 *   user's actual continue-watching / next-up immediately.
 * - [enqueuePeriodic] keeps a 1h cadence refresh in place; uses KEEP so
 *   repeated calls don't reset the interval.
 * - [clear] wipes our rows from the WatchNextPrograms provider and
 *   cancels the periodic work — call on profile switch and sign-out so
 *   the previous user's progress doesn't linger on the launcher.
 */
class WatchNextSeeder(
    private val context: Context,
    private val repository: WatchNextRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The most recent [clear]'s in-flight provider wipe. [seedNow] joins it so
    // a clear() → seedNow() sequence (server switch, profile select) can't run
    // the seed worker while the wipe is still deleting — the wipe would land
    // AFTER the seed and erase the fresh rows it just inserted.
    private var clearJob: Job? = null

    fun seedNow() {
        val pending = clearJob
        scope.launch {
            pending?.join()
            val request = OneTimeWorkRequestBuilder<WatchNextSyncWorker>()
                .setConstraints(networkConstraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WatchNextSyncWorker.UNIQUE_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<WatchNextSyncWorker>(
            1, TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WatchNextSyncWorker.UNIQUE_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun clear() {
        repository.invalidate()
        // Cancel FIRST, then wipe. Cancel BOTH the periodic refresh and any
        // in-flight one-shot seed — without cancelling before the wipe, a
        // seedNow() that's mid-flight when the user signs out (or switches
        // profile) races [WatchNextRepository.clearAll] and can re-insert the
        // previous user's tiles onto the shared launcher after the wipe. The
        // generation fence rejects old work even before WorkManager delivers
        // cancellation. The repository orders the wipe after any dispatched
        // write, and rejects old writers waiting behind that wipe.
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WatchNextSyncWorker.UNIQUE_NAME_PERIODIC)
            cancelUniqueWork(WatchNextSyncWorker.UNIQUE_NAME_ONESHOT)
        }
        // Cross-process ContentResolver deletes are binder I/O: run them off
        // the caller's (main) thread so clear() stays cheap for call sites.
        // Track the wipe so [seedNow] can join it before enqueuing a seed.
        clearJob = scope.launch { repository.clearAll() }
    }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
