package org.siloserver.silo.tv.watchnext

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.common.settings.EpisodeSpoilerStore
import org.siloserver.silo.common.settings.EpisodeSpoilerSupport
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.tv.data.preferences.TvProfileLaunchPreferences
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
    private val spoilerStore: EpisodeSpoilerStore,
    private val catalogRepository: CatalogRepository,
    /** Profile Selection's verdict; see [TvProfileLaunchPreferences.allowsWatchNext]. */
    private val allowsWatchNext: () -> Boolean = { true },
    /**
     * A phone's cast identity is active; its titles never reach this TV's
     * launcher. Returning to this TV's profile after the cast seeds again.
     */
    private val hasTemporaryScope: suspend () -> Boolean = { false },
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (wipeIfHidden()) return@withContext Result.success()
        if (inputData.getBoolean(KEY_POLICY_CHECK_ONLY, false)) return@withContext Result.success()
        val stopped = { isStopped || !allowsWatchNext() }
        val completed = syncWatchNextHome(
            sectionRepository, repository.writeGate, stopped,
            spoilerPreferences = {
                spoilerStore.refresh()
                spoilerStore.state.value.takeUnless { it.support == EpisodeSpoilerSupport.Unknown }?.prefs
            },
            preferencesCurrent = { prefs ->
                spoilerStore.state.value.let { it.support != EpisodeSpoilerSupport.Unknown && it.prefs == prefs }
            },
            seriesArtwork = { id -> (catalogRepository.getItemDetail(id) as? ApiResult.Success)?.data },
            borrowedIdentity = hasTemporaryScope,
        ) { fields, run, authority ->
            repository.diffAndApply(fields, run, authority)
        }
        // The verdict can flip mid-run (a timed choice expiring); wipe what
        // this run already wrote instead of leaving it for a retry.
        if (wipeIfHidden()) return@withContext Result.success()
        if (completed) Result.success() else Result.retry()
    }

    /**
     * Profile Selection hides the previous viewer's titles from the launcher
     * (silo-apple TopShelfProfilePolicy): wipe instead of writing.
     */
    private suspend fun wipeIfHidden(): Boolean {
        if (allowsWatchNext()) return false
        repository.invalidate()
        repository.clearAll()
        return true
    }

    companion object {
        const val UNIQUE_NAME_PERIODIC = "watch_next_sync_periodic"
        const val UNIQUE_NAME_ONESHOT = "watch_next_sync_oneshot"
        const val UNIQUE_NAME_PROFILE_EXPIRY = "watch_next_profile_expiry"

        /** Input flag: only apply Profile Selection's verdict, never sync. */
        const val KEY_POLICY_CHECK_ONLY = "policy_check_only"
    }
}
