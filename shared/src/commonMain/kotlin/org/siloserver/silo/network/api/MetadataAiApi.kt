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
    private val gate: ApiV2Gate,
) : MetadataAiApi {
    override suspend fun captureAuthority() = tokens?.snapshotCurrentScope()
    override suspend fun isCurrent(scope: AuthScopeSnapshot?): Boolean =
        scope != null && !scope.profileId.isNullOrBlank() && scope.stillOwns(tokens, OwnerPolicy.PROFILE)

    override suspend fun status(): ApiResult<MetadataAiStatus> {
        val owner = captureAuthority()?.takeIf { !it.profileId.isNullOrBlank() } ?: return identityChanged()
        return ownedV2Call<MetadataCapability, MetadataAiStatus>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/capabilities/metadata-ai") { authScope(scope!!); requireSiloAuth() }
        }) { capability ->
            val mode = when (capability.onView) {
                "button" -> MetadataAiOnView.Button
                "auto" -> MetadataAiOnView.Auto
                else -> MetadataAiOnView.Off
            }
            MetadataAiStatus(
                enabled = capability.state == "available",
                state = capability.state,
                revision = capability.revision,
                onView = if (capability.state == "available") mode else MetadataAiOnView.Off,
            )
        }
    }

    override suspend fun translateDescription(contentId: String, targetLanguage: String, scope: AuthScopeSnapshot?): ApiResult<MetadataTranslationJob> {
        val owner = (scope ?: captureAuthority())?.takeIf { !it.profileId.isNullOrBlank() } ?: return identityChanged()
        return ownedV2Call<MetadataTranslationJob, MetadataTranslationJob>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.Accepted, { pinned ->
            client.post("/api/v2/catalog/items/${contentId.encodeURLPathPart()}/translate-description") {
                authScope(pinned!!); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(TranslateDescriptionRequest(targetLanguage))
            }
        }) { job ->
            require(job.id.isNotBlank() && job.contentId == contentId && job.targetKind in setOf("item", "season", "episode") &&
                job.status in setOf("pending", "running", "completed", "failed", "canceled") &&
                job.progress.isFinite() && job.progress in 0.0..1.0) { "The server returned an unsupported metadata job." }
            job
        }
    }

    /** Fresh authorized detail only: a cached value cannot prove translation completed. */
    override suspend fun refreshDetail(contentId: String, scope: AuthScopeSnapshot?): ApiResult<ItemDetail> {
        val owner = scope?.takeIf { !it.profileId.isNullOrBlank() } ?: return identityChanged()
        return ownedV2Call<ItemDetailReadV2, ItemDetail>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { pinned ->
            client.get("/api/v2/catalog/items/${contentId.encodeURLPathPart()}") { authScope(pinned!!); requireSiloAuth() }
        }) { wire ->
            val detail = wire.toDomain()
            require(detail.contentId == contentId) { "The detail response did not match this item." }
            detail
        }
    }
}
