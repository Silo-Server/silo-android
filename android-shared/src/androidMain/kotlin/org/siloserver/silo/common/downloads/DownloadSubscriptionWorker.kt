package org.siloserver.silo.common.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.DownloadSubscriptionRepository
import org.siloserver.silo.repository.ProfileRepository

// A one-shot enqueue pins the scope via input data; the periodic refresh
// carries none and evaluates whatever scope is active when it fires, so a
// server/profile switch never needs a reschedule. A lone input value is
// stale data and must not mix scopes with the active session.
internal fun downloadSubscriptionScope(
    inputServerId: String?,
    inputProfileId: String?,
    activeServerId: String?,
    activeProfileId: String?,
): Pair<String, String> =
    if (inputServerId != null && inputProfileId != null) {
        inputServerId to inputProfileId
    } else {
        (activeServerId ?: DownloadEnqueuer.DEFAULT_SERVER_ID) to
            (activeProfileId ?: DownloadEnqueuer.DEFAULT_PROFILE_ID)
    }

class DownloadSubscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: DownloadSubscriptionRepository,
    private val evaluatorFactory: DownloadSubscriptionEvaluatorFactory,
    private val serverRegistry: ServerRegistry,
    private val profileRepository: ProfileRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val (serverId, profileId) = downloadSubscriptionScope(
            inputServerId = inputData.getString(KEY_SERVER_ID),
            inputProfileId = inputData.getString(KEY_PROFILE_ID),
            activeServerId = serverRegistry.activeServerId.value,
            activeProfileId = profileRepository.getActiveProfileId(),
        )
        val now = System.currentTimeMillis()
        val evaluator = evaluatorFactory.create()

        repository.active(serverId, profileId).forEach { subscription ->
            // A server/profile switch mid-run would make the enqueue tail file
            // downloads under the NEW scope while the dedup snapshot belongs
            // to the old one — stop; the next periodic fire covers the rest.
            val liveServer = serverRegistry.activeServerId.value
            val liveProfile = profileRepository.getActiveProfileId()
            if (inputData.getString(KEY_SERVER_ID) == null &&
                (liveServer != serverId || liveProfile != profileId)
            ) {
                return Result.success()
            }
            runCatching {
                evaluator.evaluate(subscription)
                repository.updateEvaluation(serverId, profileId, subscription.id, now, null, now)
            }.onFailure { error ->
                repository.updateEvaluation(
                    serverId = serverId,
                    profileId = profileId,
                    id = subscription.id,
                    evaluatedAt = now,
                    error = error.message ?: error::class.simpleName,
                    updatedAt = now,
                )
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_SERVER_ID = "server_id"
        const val KEY_PROFILE_ID = "profile_id"
        private const val PERIODIC_UNIQUE_NAME = "download-subscriptions-periodic"
        private const val PERIODIC_INTERVAL_HOURS = 6L

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DownloadSubscriptionWorker>(
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
