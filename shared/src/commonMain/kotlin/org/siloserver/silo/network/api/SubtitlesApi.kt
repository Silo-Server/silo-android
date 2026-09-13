package org.siloserver.silo.network.api

import org.siloserver.silo.model.subtitles.DownloadedSubtitlesResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobResponse
import org.siloserver.silo.model.subtitles.SubtitleAiJobsResponse
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleDownloadResponse
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleSearchResponse
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.SubtitleAiCreateV2Api
import org.siloserver.silo.network.apiv2.SubtitleAiReadsV2Api
import org.siloserver.silo.network.apiv2.SubtitleDownloadV2Api
import org.siloserver.silo.network.apiv2.SubtitleReadsV2Api

/**
 * Subtitle provider search/download + AI translation endpoints on API v2.
 * Kept behind an interface so repository and ViewModel tests can fake the
 * transport, matching the CalendarApi/RequestsApi shape.
 */
interface SubtitlesApi {

    /** POST /api/v2/subtitles/search — errors with server text when no providers are configured. */
    suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse>

    /** POST /api/v2/subtitles/download — send the selected provider identity once. */
    suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse>

    /** GET /api/v2/subtitles/{media_file_id} — subtitles already stored server-side. */
    suspend fun list(mediaFileId: Int): ApiResult<DownloadedSubtitlesResponse>

    /** GET /api/v2/subtitles/ai/status — both flags false when AI is unconfigured. */
    suspend fun aiStatus(): ApiResult<SubtitleAiStatus>

    /** GET /api/v2/subtitles/ai/quota — transcribe-kind budget; admins are exempt. */
    suspend fun aiQuota(): ApiResult<SubtitleAiQuota>

    /** POST /api/v2/subtitles/ai/translate — 202 with the queued job; 429 quota; 503 unconfigured. */
    suspend fun translate(request: SubtitleTranslateRequest, scope: AuthScopeSnapshot? = null): ApiResult<SubtitleAiJobResponse>

    /** GET /api/v2/subtitles/ai/jobs?media_file_id=N */
    suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse>

    /** GET /api/v2/subtitles/ai/jobs/{id} — 404 once the job row is gone. */
    suspend fun getJob(jobId: Long, scope: AuthScopeSnapshot? = null): ApiResult<SubtitleAiJobResponse>

    /** POST /api/v2/subtitles/ai/jobs/{id}/cancel — 204 acknowledges cancellation. */
    suspend fun cancelJob(jobId: Long, scope: AuthScopeSnapshot? = null): ApiResult<Unit>
}

class DefaultSubtitlesApi(
    private val reads: SubtitleReadsV2Api,
    private val downloads: SubtitleDownloadV2Api,
    private val aiReads: SubtitleAiReadsV2Api,
    private val creation: SubtitleAiCreateV2Api,
) : SubtitlesApi {

    override suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse> =
        reads.search(request)

    override suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse> =
        downloads.download(request)

    override suspend fun list(mediaFileId: Int): ApiResult<DownloadedSubtitlesResponse> =
        reads.list(mediaFileId)

    override suspend fun aiStatus(): ApiResult<SubtitleAiStatus> = reads.aiStatus()

    override suspend fun aiQuota(): ApiResult<SubtitleAiQuota> = aiReads.quota()

    override suspend fun translate(request: SubtitleTranslateRequest, scope: AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> =
        creation.create(request, scope)

    override suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> =
        aiReads.jobs(mediaFileId)

    override suspend fun getJob(jobId: Long, scope: AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> =
        aiReads.job(jobId, scope)

    override suspend fun cancelJob(jobId: Long, scope: AuthScopeSnapshot?): ApiResult<Unit> =
        aiReads.cancel(jobId, scope)
}
