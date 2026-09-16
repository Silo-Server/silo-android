package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable
private data class ProviderDownloadBody(
    @SerialName("media_file_id") val mediaFileId: String,
    val provider: String,
    @SerialName("subtitle_id") val subtitleId: String,
    val language: String,
    @SerialName("release_name") val releaseName: String,
    val score: Double,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean,
)
@Serializable
internal data class StoredSubtitleV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
    @Serializable(with = DetailStringIdSerializer::class) @SerialName("media_file_id") val mediaFileId: String,
    val provider: String, val language: String, val format: String,
    @SerialName("release_name") val releaseName: String,
    val score: Double,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean,
    @SerialName("created_at") val createdAt: String,
) {
    fun project(expectedFile: Int): DownloadedSubtitle {
        require(mediaFileId == expectedFile.toString())
        return DownloadedSubtitle(checkedPositiveId(id), expectedFile, provider, language, format, releaseName, score, hearingImpaired, createdAt)
    }
}
@Serializable private data class DownloadEnvelopeV2(val subtitle: StoredSubtitleV2)

/** A provider request is sent once; content deduplication is not a replay receipt. */
class SubtitleDownloadV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun download(request: SubtitleDownloadRequest): ApiResult<SubtitleDownloadResponse> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        if (request.mediaFileId <= 0) return ApiResult.Error(0, "invalid_file", "A media file is required.")
        return ownedV2Call<DownloadEnvelopeV2, SubtitleDownloadResponse>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.post("/api/v2/subtitles/download") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(ProviderDownloadBody(request.mediaFileId.toString(), request.provider, request.subtitleId,
                    request.language, request.releaseName, request.score, request.hearingImpaired))
            }
        }) { SubtitleDownloadResponse(it.subtitle.project(request.mediaFileId)) }
    }
}
