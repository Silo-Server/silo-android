package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.download.*
import org.siloserver.silo.network.*

@Serializable
internal data class DownloadEntryV2(
    val id: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("batch_id") val batchId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @Serializable(with = DetailStringIdSerializer::class) @SerialName("media_file_id") val mediaFileId: String,
    @SerialName("file_size") val fileSize: Long,
    @SerialName("bytes_sent") val bytesSent: Long,
    val kind: String, val status: String, val quality: String,
    @SerialName("effective_quality") val effectiveQuality: String,
    @SerialName("delivery_format") val deliveryFormat: String,
    @SerialName("target_bitrate_kbps") val targetBitrateKbps: Int,
    val revision: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("status_event_at") val statusEventAt: String? = null,
) {
    fun project(expectedDevice: String): DownloadRecord {
        require(id.isNotBlank() && deviceId == expectedDevice && revision > 0)
        return DownloadRecord(id, contentId, episodeId, batchId, checkedPositiveId(mediaFileId), fileSize, bytesSent, kind, status,
            createdAt, completedAt, quality, effectiveQuality, deliveryFormat, targetBitrateKbps,
            deviceId, revision, statusEventAt)
    }
}
@Serializable private data class DownloadPageV2(val items: List<DownloadEntryV2>, val page: PageInfo)

/** Registry reads are all-or-nothing; local absence reconciliation must never see a prefix. */
class DownloadRegistryV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val devices: DeviceMetadataProvider, private val gate: ApiV2Gate) {
    private fun invalid() = ApiResult.Error(0, "invalid_download_registry", "The server returned an incomplete or unsupported download registry.")
    private suspend inline fun <reified T> exchange(scope: AuthScopeSnapshot, device: String, method: HttpMethod,
        path: String, noinline configure: HttpRequestBuilder.() -> Unit = {}): ApiResult<T> {
        if (devices.current()?.id != device) return identityChanged()
        val result = ownedV2Call<T, T>(gate, tokens, scope, OwnerPolicy.IDENTITY,
            if (method == HttpMethod.Delete) HttpStatusCode.NoContent else HttpStatusCode.OK, { owner ->
            client.request(path) {
                this.method = method; authScope(owner!!); requireSiloAuth()
                // The existing auth plugin attaches the installation's device metadata.
                if (method != HttpMethod.Get) singleAttempt()
                configure()
            }
        }) { it }
        return if (devices.current()?.id == device) result else identityChanged()
    }
    suspend fun list(scope: AuthScopeSnapshot): ApiResult<DownloadsListResponse> {
        val device = devices.current()?.id?.takeIf { it.isNotBlank() } ?: return identityChanged()
        val rows = linkedMapOf<String, DownloadRecord>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            when (val result = exchange<DownloadPageV2>(scope, device, HttpMethod.Get, "/api/v2/downloads") {
                parameter("limit", 100); cursor?.let { parameter("cursor", it) }
            }) {
                is ApiResult.Success -> {
                    val page = result.data
                    if (page.items.size > 100) return invalid()
                    for (wire in page.items) {
                        val row = try { wire.project(device) } catch (_: IllegalArgumentException) { return invalid() }
                        if (rows.put(row.id, row) != null) return invalid()
                    }
                    if (!page.page.hasMore) {
                        if (!page.page.nextCursor.isNullOrBlank()) return invalid()
                        return ApiResult.Success(DownloadsListResponse(rows.values.toList()))
                    }
                    val next = page.page.nextCursor
                    if (next.isNullOrBlank() || page.items.isEmpty() || !cursors.add(next)) return invalid()
                    cursor = next
                }
                is ApiResult.Error -> return result
                is ApiResult.NetworkError -> return result
            }
        }
        return invalid()
    }
    suspend fun capability(scope: AuthScopeSnapshot): ApiResult<DownloadCapability> {
        val device = devices.current()?.id ?: return identityChanged()
        val result = exchange<DownloadCapability>(scope, device, HttpMethod.Get, "/api/v2/capabilities/downloads")
        return if (result is ApiResult.Success && (result.data.revision.isNullOrBlank() || result.data.state.isNullOrBlank())) invalid() else result
    }
    suspend fun delete(id: String, scope: AuthScopeSnapshot): ApiResult<Unit> {
        val device = devices.current()?.id ?: return identityChanged()
        return exchange(scope, device, HttpMethod.Delete, "/api/v2/downloads/${id.encodeURLPathPart()}")
    }
}
