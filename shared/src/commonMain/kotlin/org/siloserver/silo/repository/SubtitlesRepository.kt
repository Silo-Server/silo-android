// shared/src/commonMain/kotlin/org/siloserver/silo/repository/SubtitlesRepository.kt
package org.siloserver.silo.repository

import org.siloserver.silo.model.subtitles.DownloadedSubtitlesResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJob
import org.siloserver.silo.model.subtitles.SubtitleAiJobResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobsResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobStatus
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleDownloadResponse
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleSearchResponse
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SubtitlesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Subtitle provider search/download + AI translation. Thin pass-throughs over
 * [SubtitlesApi] plus [pollJob], a suspend loop modeled on
 * [DeviceLoginRepository.runPollLoop]:
 *  - polls immediately (don't wait the first interval)
 *  - swallows transient errors (non-404 [ApiResult.Error], [ApiResult.NetworkError])
 *    and retries after the interval
 *  - 404 = the job row is gone → terminal [SubtitleJobOutcome.Failed]
 *  - terminal job statuses (completed/failed/cancelled) end the loop
 *  - rethrows [CancellationException] so callers can cancel via structured
 *    concurrency (player exit cancels the viewModelScope job)
 */
class SubtitlesRepository(private val api: SubtitlesApi, private val tokens: org.siloserver.silo.network.TokenManager? = null) {

    /** Terminal result of [pollJob]. */
    sealed class SubtitleJobOutcome {
        data class Completed(val resultSubtitleId: Int?) : SubtitleJobOutcome()
        data class Failed(val message: String?) : SubtitleJobOutcome()
        object Cancelled : SubtitleJobOutcome() {
            override fun toString(): String = "Cancelled"
        }
    }

    suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse> =
        api.search(request)

    suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse> =
        api.download(request)

    suspend fun list(mediaFileId: Int): ApiResult<DownloadedSubtitlesResponse> =
        api.list(mediaFileId)

    suspend fun aiStatus(): ApiResult<SubtitleAiStatus> = api.aiStatus()

    suspend fun aiQuota(): ApiResult<SubtitleAiQuota> = api.aiQuota()

    suspend fun translate(request: SubtitleTranslateRequest): ApiResult<SubtitleAiJobResponse> =
        api.translate(request)

    suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> =
        api.listJobs(mediaFileId)

    suspend fun getJob(jobId: Long): ApiResult<SubtitleAiJobResponse> = api.getJob(jobId)

    suspend fun cancelJob(jobId: Long): ApiResult<Unit> = api.cancelJob(jobId)

    /**
     * Polls GET /ai/jobs/{id} every [intervalMs] until the job reaches a
     * terminal status, invoking [onUpdate] with every successfully fetched
     * snapshot (including the terminal one, so progress UIs can show 100%
     * before dismissing).
     */
    suspend fun pollJob(
        jobId: Long,
        intervalMs: Long = 1_000L,
        onUpdate: (SubtitleAiJob) -> Unit = {},
    ): SubtitleJobOutcome {
        val scope = tokens?.snapshotCurrentScope()
        if (tokens != null && scope == null) return SubtitleJobOutcome.Failed("The subtitle job's account or profile changed.")
        while (true) {
            try {
                val job = when (val r = api.getJob(jobId, scope)) {
                    is ApiResult.Success -> r.data.job
                    is ApiResult.Error -> {
                        if (r.code == 0) return SubtitleJobOutcome.Failed(r.message)
                        if (r.code == 404) {
                            return SubtitleJobOutcome.Failed(
                                "This job no longer exists on the server.",
                            )
                        }
                        // Transient — keep trying.
                        delay(intervalMs)
                        continue
                    }
                    is ApiResult.NetworkError -> {
                        // Transient network blip — keep trying.
                        delay(intervalMs)
                        continue
                    }
                }

                onUpdate(job)

                when (job.status) {
                    SubtitleAiJobStatus.Completed ->
                        return SubtitleJobOutcome.Completed(job.resultSubtitleId)
                    SubtitleAiJobStatus.Failed ->
                        return SubtitleJobOutcome.Failed(job.errorMessage)
                    SubtitleAiJobStatus.Cancelled ->
                        return SubtitleJobOutcome.Cancelled
                    else ->
                        // pending / running (and forward-compatible unknowns).
                        delay(intervalMs)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
