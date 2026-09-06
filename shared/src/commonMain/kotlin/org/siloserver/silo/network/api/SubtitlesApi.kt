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
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Subtitle provider search/download + AI translation endpoints. Kept behind
 * an interface so repository and ViewModel tests can fake the transport,
 * matching the CalendarApi/RequestsApi shape.
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

    /** POST /api/v1/subtitles/ai/translate — 202 with the queued job; 429 quota; 503 unconfigured. */
    suspend fun translate(request: SubtitleTranslateRequest): ApiResult<SubtitleAiJobResponse>
    suspend fun translate(request: SubtitleTranslateRequest, scope: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<SubtitleAiJobResponse> = translate(request)

    /** GET /api/v2/subtitles/ai/jobs?media_file_id=N */
    suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse>

    /** GET /api/v2/subtitles/ai/jobs/{id} — 404 once the job row is gone. */
    suspend fun getJob(jobId: Long): ApiResult<SubtitleAiJobResponse>
    suspend fun getJob(jobId: Long, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> = getJob(jobId)

    /** POST /api/v2/subtitles/ai/jobs/{id}/cancel — 204 acknowledges cancellation. */
    suspend fun cancelJob(jobId: Long): ApiResult<Unit>
    suspend fun cancelJob(jobId: Long, scope: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<Unit> = cancelJob(jobId)
}

class DefaultSubtitlesApi(private val client: HttpClient, private val aiReads: org.siloserver.silo.network.apiv2.SubtitleAiReadsV2Api? = null, private val downloads: org.siloserver.silo.network.apiv2.SubtitleDownloadV2Api? = null, private val reads: org.siloserver.silo.network.apiv2.SubtitleReadsV2Api? = null, private val cancellation: org.siloserver.silo.network.apiv2.SubtitleAiCancelV2Api? = null, private val creation: org.siloserver.silo.network.apiv2.SubtitleAiCreateV2Api? = null) : SubtitlesApi {

    override suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse> =
        reads?.search(request) ?: safeApiCall {
            client.post("/api/v1/subtitles/search") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse> =
        downloads?.download(request) ?: safeApiCall {
            client.post("/api/v1/subtitles/download") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun list(mediaFileId: Int): ApiResult<DownloadedSubtitlesResponse> =
        reads?.list(mediaFileId) ?: safeApiCall {
            client.get("/api/v1/subtitles/$mediaFileId")
        }

    override suspend fun aiStatus(): ApiResult<SubtitleAiStatus> = reads?.aiStatus() ?: safeApiCall {
        client.get("/api/v1/subtitles/ai/status")
    }

    override suspend fun aiQuota(): ApiResult<SubtitleAiQuota> = aiReads?.quota() ?: safeApiCall {
        client.get("/api/v1/subtitles/ai/quota")
    }

    override suspend fun translate(request: SubtitleTranslateRequest): ApiResult<SubtitleAiJobResponse> =
        creation?.create(request) ?: safeApiCall {
            client.post("/api/v1/subtitles/ai/translate") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun translate(request: SubtitleTranslateRequest, scope: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<SubtitleAiJobResponse> =
        creation?.create(request, scope) ?: translate(request)

    override suspend fun listJobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> =
        aiReads?.jobs(mediaFileId) ?: safeApiCall {
            client.get("/api/v1/subtitles/ai/jobs") {
                parameter("media_file_id", mediaFileId)
            }
        }

    override suspend fun getJob(jobId: Long): ApiResult<SubtitleAiJobResponse> = aiReads?.job(jobId) ?: safeApiCall {
        client.get("/api/v1/subtitles/ai/jobs/$jobId")
    }

    override suspend fun getJob(jobId: Long, scope: org.siloserver.silo.network.AuthScopeSnapshot?): ApiResult<SubtitleAiJobResponse> =
        aiReads?.job(jobId, scope) ?: getJob(jobId)

    override suspend fun cancelJob(jobId: Long): ApiResult<Unit> = cancellation?.cancel(jobId) ?: safeApiCall {
        client.post("/api/v1/subtitles/ai/jobs/$jobId/cancel")
    }

    override suspend fun cancelJob(jobId: Long, scope: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<Unit> =
        cancellation?.cancel(jobId, scope) ?: cancelJob(jobId)

}
