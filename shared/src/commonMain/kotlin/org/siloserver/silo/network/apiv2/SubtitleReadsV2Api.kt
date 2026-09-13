package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable private data class StoredListV2(val subtitles: List<StoredSubtitleV2>)
@Serializable private data class SearchBodyV2(@SerialName("media_file_id") val mediaFileId: String, val languages: List<String>)
@Serializable private data class SearchResultV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
    val provider: String, val language: String,
    @SerialName("release_name") val releaseName: String,
    val format: String, val score: Double, val downloads: Int,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean,
    @SerialName("upload_date") val uploadDate: String? = null,
) {
    fun project() = SubtitleResult(id, provider, language, releaseName, format, score, downloads, hearingImpaired, uploadDate)
}
@Serializable private data class SearchResultsV2(val results: List<SearchResultV2>, val warnings: List<String>)

class SubtitleReadsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    private suspend inline fun <reified T, R> read(path: String, method: HttpMethod = HttpMethod.Get,
        noinline configure: HttpRequestBuilder.() -> Unit = {}, crossinline project: (T) -> R): ApiResult<R> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.request(path) { this.method = method; authScope(owner!!); requireSiloAuth(); configure() }
        }, project)
    }
    suspend fun list(file: Int): ApiResult<DownloadedSubtitlesResponse> {
        if (file <= 0) return ApiResult.Error(0,"invalid_file","A media file is required.")
        return read<StoredListV2, DownloadedSubtitlesResponse>("/api/v2/subtitles/$file") { value ->
            val rows = value.subtitles.map { it.project(file) }
            require(rows.map { it.id }.toSet().size == rows.size)
            DownloadedSubtitlesResponse(rows)
        }
    }
    suspend fun search(request: SubtitleSearchRequest): ApiResult<SubtitleSearchResponse> {
        if (request.mediaFileId <= 0 || request.languages.size > 100) return ApiResult.Error(0,"invalid_search","The subtitle search is invalid.")
        return read<SearchResultsV2, SubtitleSearchResponse>("/api/v2/subtitles/search", HttpMethod.Post, {
            contentType(ContentType.Application.Json)
            setBody(SearchBodyV2(request.mediaFileId.toString(),request.languages))
        }) { SubtitleSearchResponse(it.results.map { row -> row.project() },it.warnings) }
    }
    suspend fun aiStatus(): ApiResult<SubtitleAiStatus> = read<SubtitleAiStatus, SubtitleAiStatus>("/api/v2/subtitles/ai/status") { it }
}
