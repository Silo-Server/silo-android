package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable
private data class AiJobV2(
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
        val file = mediaFileId.toIntOrNull()
        val result = resultSubtitleId?.toIntOrNull()
        require(job != null && job > 0 && job.toString() == id)
        require(file != null && file > 0 && file.toString() == mediaFileId)
        require(resultSubtitleId == null || (result != null && result > 0 && result.toString() == resultSubtitleId))
        require(kind in setOf(SubtitleAiJobKind.Translate, SubtitleAiJobKind.Transcribe, SubtitleAiJobKind.TranscribeTranslate))
        require(progress.isFinite() && progress in 0.0..1.0)
        return SubtitleAiJob(job, file, kind, sourceIndex, sourceLanguage, targetLanguage, engine, model,
            status, progress, progressMessage, result, errorMessage, createdAt, updatedAt)
    }
}
@Serializable private data class AiJobEnvelope(val job: AiJobV2)
@Serializable private data class AiJobsEnvelope(val jobs: List<AiJobV2>)
@Serializable private data class AiQuotaV2(val limited: Boolean, val limit: Int, val used: Int, val remaining: Int, val period: String)

class SubtitleAiReadsV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    private suspend inline fun <reified T> read(path: String, scope: AuthScopeSnapshot?,
        noinline configure: HttpRequestBuilder.() -> Unit = {}): ApiResult<T> {
        val captured = scope ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!captured.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        val result = safeApiV2Call<T>(gate) {
            client.get(path) { authScope(captured); requireSiloAuth(); configure() }
        }
        return if (captured.isSameIdentityAs(tokens.snapshotCurrentScope())) result else changed()
    }
    private fun changed() = ApiResult.Error(0, "identity_changed", "The subtitle job's account or profile changed.")
    private inline fun <T, R> project(result: ApiResult<T>, block: (T) -> R): ApiResult<R> = when (result) {
        is ApiResult.Success -> try { ApiResult.Success(block(result.data)) }
            catch (_: IllegalArgumentException) { ApiResult.Error(0, "invalid_subtitle_job", "The server returned an unsupported subtitle job identity.") }
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }
    suspend fun quota(): ApiResult<SubtitleAiQuota> = project(read<AiQuotaV2>("/api/v2/subtitles/ai/quota", null)) {
        SubtitleAiQuota(it.limited, it.limit, it.used, it.remaining, it.period)
    }
    suspend fun job(id: Long, scope: AuthScopeSnapshot? = null): ApiResult<SubtitleAiJobResponse> =
        project(read<AiJobEnvelope>("/api/v2/subtitles/ai/jobs/$id", scope)) {
            val job = it.job.project(); require(job.id == id); SubtitleAiJobResponse(job)
        }
    suspend fun jobs(mediaFileId: Int): ApiResult<SubtitleAiJobsResponse> =
        project(read<AiJobsEnvelope>("/api/v2/subtitles/ai/jobs", null) { parameter("media_file_id", mediaFileId.toString()) }) {
            require(it.jobs.size <= 50)
            val jobs = it.jobs.map { wire -> wire.project() }
            require(jobs.all { job -> job.mediaFileId == mediaFileId } && jobs.map { job -> job.id }.distinct().size == jobs.size)
            SubtitleAiJobsResponse(jobs)
        }
}
