package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.metadata.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*

interface MetadataAiApi {
    suspend fun status(): ApiResult<MetadataAiStatus>
    suspend fun translateDescription(contentId: String, targetLanguage: String, scope: AuthScopeSnapshot? = null): ApiResult<MetadataTranslationJob>
    suspend fun captureAuthority(): AuthScopeSnapshot? = null
    suspend fun isCurrent(scope: AuthScopeSnapshot?): Boolean = true
    suspend fun refreshDetail(contentId: String, scope: AuthScopeSnapshot?): ApiResult<ItemDetail> =
        ApiResult.Error(0, "unavailable", "Detail refresh is unavailable.")
}

@Serializable private data class MetadataCapability(val state: String, @Serializable(with = DetailStringIdSerializer::class) val revision: String, @SerialName("on_view") val onView: String)

/** Viewer on-view action only; active-job coalescing is not durable replay safety. */
class DefaultMetadataAiApi(
    private val client: HttpClient,
    private val tokens: TokenManager? = null,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) : MetadataAiApi {
    override suspend fun captureAuthority() = tokens?.snapshotCurrentScope()
    override suspend fun isCurrent(scope: AuthScopeSnapshot?): Boolean {
        val now = captureAuthority()
        return scope != null && !scope.profileId.isNullOrBlank() && scope.isSameIdentityAs(now) &&
            scope.profileId == now?.profileId && scope.profileToken == now?.profileToken
    }
    private fun changed() = ApiResult.Error(0, "identity_changed", "The metadata account or profile changed.")

    override suspend fun status(): ApiResult<MetadataAiStatus> {
        val owner = captureAuthority()
        if (!isCurrent(owner)) return changed()
        val result = safeApiV2Call<MetadataCapability>(gate) {
            client.get("/api/v2/capabilities/metadata-ai") { authScope(owner!!); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!isCurrent(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> {
                val mode = when (result.data.onView) {
                    "button" -> MetadataAiOnView.Button
                    "auto" -> MetadataAiOnView.Auto
                    else -> MetadataAiOnView.Off
                }
                ApiResult.Success(MetadataAiStatus(
                    enabled = result.data.state == "available",
                    state = result.data.state,
                    revision = result.data.revision,
                    onView = if (result.data.state == "available") mode else MetadataAiOnView.Off,
                ))
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun translateDescription(contentId: String, targetLanguage: String, scope: AuthScopeSnapshot?): ApiResult<MetadataTranslationJob> {
        val owner = scope ?: captureAuthority()
        if (!isCurrent(owner)) return changed()
        val result = safeApiV2Call<MetadataTranslationJob>(gate) {
            client.post("/api/v2/catalog/items/${contentId.encodeURLPathPart()}/translate-description") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(TranslateDescriptionRequest(targetLanguage))
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.Accepted) }
        }
        if (!isCurrent(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> {
                val job = result.data
                if (job.id.isBlank() || job.contentId != contentId || job.targetKind !in setOf("item", "season", "episode") ||
                    job.status !in setOf("pending", "running", "completed", "failed", "canceled") ||
                    !job.progress.isFinite() || job.progress !in 0.0..1.0) {
                    ApiResult.Error(0,"invalid_metadata_job","The server returned an unsupported metadata job.")
                } else result
            }
            else -> result
        }
    }

    /** Fresh authorized detail only: a cached value cannot prove translation completed. */
    override suspend fun refreshDetail(contentId: String, scope: AuthScopeSnapshot?): ApiResult<ItemDetail> {
        if (!isCurrent(scope)) return changed()
        val result = safeApiV2Call<ItemDetailReadV2>(gate) {
            client.get("/api/v2/catalog/items/${contentId.encodeURLPathPart()}") { authScope(scope!!); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!isCurrent(scope)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val detail = result.data.toDomain()
                require(detail.contentId == contentId)
                ApiResult.Success(detail)
            } catch (_: IllegalArgumentException) { ApiResult.Error(0,"invalid_detail","The detail response did not match this item.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
}
