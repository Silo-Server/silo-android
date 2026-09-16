package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable
internal data class AiJobV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
    @Serializable(with = DetailStringIdSerializer::class) @SerialName("media_file_id") val mediaFileId: String,
    val kind: String,
    @SerialName("source_index") val sourceIndex: Int,
    @SerialName("source_language") val sourceLanguage: String,
    @SerialName("target_language") val targetLanguage: String,
    val engine: String, val model: String, val status: String, val progress: Double,
    @SerialName("progress_message") val progressMessage: String,
    @Serializable(with = DetailStringIdSerializer::class) @SerialName("result_subtitle_id") val resultSubtitleId: String?,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
) {
    fun project(): SubtitleAiJob {
        val job = id.toLongOrNull()
        require(job != null && job > 0 && job.toString() == id)
        require(kind in setOf(SubtitleAiJobKind.Translate, SubtitleAiJobKind.Transcribe, SubtitleAiJobKind.TranscribeTranslate))
        require(progress.isFinite() && progress in 0.0..1.0)
        return SubtitleAiJob(job, checkedPositiveId(mediaFileId), kind, sourceIndex, sourceLanguage, targetLanguage, engine, model,
            status, progress, progressMessage, resultSubtitleId?.let(::checkedPositiveId), errorMessage, createdAt, updatedAt)
    }
}
@Serializable private data class AiJobEnvelope(val job: AiJobV2)
@Serializable private data class AiJobsEnvelope(val jobs: List<AiJobV2>)

/** Quota, job reads and job cancellation. Cancellation acknowledges a request; job polling determines the terminal outcome. */
class SubtitleAiReadsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    /** Sends [block] bound to [scope] (or the current scope) and discards the reply if the identity moved meanwhile. */
    private suspend inline fun <reified T, R> bound(scope: AuthScopeSnapshot?, expected: HttpStatusCode,
        crossinline block: suspend (AuthScopeSnapshot) -> HttpResponse, crossinline project: (T) -> R): ApiResult<R> {
        val captured = scope ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, captured, OwnerPolicy.PROFILE, expected, { block(captured) }, project)
    }
    private suspend inline fun <reified T, R> read(path: String, scope: AuthScopeSnapshot?,
        noinline configure: HttpRequestBuilder.() -> Unit = {}, crossinline project: (T) -> R): ApiResult<R> =
        bound<T, R>(scope, HttpStatusCode.OK, { captured -> client.get(path) { authScope(captured); requireSiloAuth(); configure() } }, project)
    suspend fun quota(): ApiResult<SubtitleAiQuota> = read<SubtitleAiQuota, SubtitleAiQuota>("/api/v2/subtitles/ai/quota", null) { it }
    suspend fun job(id: Long, scope: AuthScopeSnapshot? = null): ApiResult<SubtitleAiJobResponse> =
        read<AiJobEnvelope, SubtitleAiJobResponse>("/api/v2/subtitles/ai/jobs/$id", scope) {
            val job = it.job.project(); require(job.id == id); SubtitleAiJobResponse(job)
        }
    suspend fun jobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> =
        read<AiJobsEnvelope, SubtitleAiJobsResponse>("/api/v2/subtitles/ai/jobs", null, { parameter("media_file_id", mediaFileId.toString()) }) {
            val jobs = it.jobs.map { wire -> wire.project() }
            require(jobs.all { job -> job.mediaFileId == mediaFileId } && jobs.map { job -> job.id }.distinct().size == jobs.size)
            SubtitleAiJobsResponse(jobs)
        }
    suspend fun cancel(id: Long, scope: AuthScopeSnapshot? = null): ApiResult<Unit> {
        if (id <= 0) return ApiResult.Error(0, "invalid_subtitle_job", "A positive job identifier is required.")
        return bound<Unit, Unit>(scope, HttpStatusCode.NoContent, { captured ->
            // Declared natural-idempotent for this exact immutable job ID.
            client.post("/api/v2/subtitles/ai/jobs/$id/cancel") { authScope(captured); requireSiloAuth() }
        }) { }
    }
}
