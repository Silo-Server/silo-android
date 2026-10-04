package org.siloserver.silo.common.downloads

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.download.statusEnum
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.managedDownloadAuth
import java.util.concurrent.TimeUnit

/**
 * Keeps saved subtitle sidecars in step with the server's timing corrections.
 *
 * A completed video download keeps the subtitle sidecars it fetched when it
 * finished. When the server later retimes a stored subtitle (an automatic or
 * manual sync, or a timing reset), the download's manifest lists that subtitle
 * at a new `revision`; this worker re-fetches those sidecars in place so the
 * next offline playback uses the corrected timing.
 *
 * It runs periodically while the device has a network, for the downloads of
 * the login and profile that are active when it fires, like
 * [DownloadSubscriptionWorker]. Every failure is left for the next run.
 */
class OfflineSubtitleRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
    private val metadataStore: DownloadMetadataStore,
    storage: DownloadStorage,
    httpClient: HttpClient,
    private val authorities: DurableLoginAuthorityProvider,
    private val transitions: IdentityTransitionBarrier,
    private val gate: ApiV2Gate,
) : CoroutineWorker(appContext, params) {

    private val fetcher = OfflineTrackAssetFetcher(httpClient, storage)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { refresh() }

    private suspend fun refresh(): Result {
        // A server this client must not speak v2 to is left alone until the next period.
        if (gate.blocked() != null) return Result.success()
        val authority = authorities.snapshotDurableLoginAuthority() ?: return Result.success()
        val scope = authority.scope
        val profileId = scope.profileId ?: return Result.success()
        for (sidecar in metadataStore.listSidecars(scope.serverId, profileId)) {
            if (sidecar.record.statusEnum() != DownloadStatus.Completed) continue
            val tracks = sidecar.offlineTracks ?: continue
            val downloadId = sidecar.record.id
            val fileId = sidecar.record.mediaFileId
            val refreshed = try {
                fetcher.refreshSubtitles(downloadId, tracks) { managedDownloadAuth(scope) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "subtitle refresh failed id=$downloadId", e)
                null
            } ?: continue
            // The files are already replaced; record their revisions unless the
            // owner changed or the download was replaced or deleted meanwhile.
            transitions.withCurrentGeneration(scope.identityGeneration) {
                if (authorities.snapshotDurableLoginAuthority() != authority) return@withCurrentGeneration
                val current = metadataStore.readSidecar(scope.serverId, profileId, fileId)
                    ?.takeIf { it.record.id == downloadId && it.offlineTracks == tracks }
                    ?: return@withCurrentGeneration
                metadataStore.writeSidecar(
                    scope.serverId,
                    profileId,
                    current.copy(offlineTracks = refreshed, updatedAtMs = System.currentTimeMillis()),
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "OfflineSubtitleRefresh"
        private const val PERIODIC_UNIQUE_NAME = "offline-subtitle-refresh-periodic"
        private const val PERIODIC_INTERVAL_HOURS = 12L

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflineSubtitleRefreshWorker>(
                PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
