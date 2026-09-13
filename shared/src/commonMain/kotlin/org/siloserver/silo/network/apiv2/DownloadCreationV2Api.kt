package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.download.*
import org.siloserver.silo.network.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable private data class CreatedDownloadsV2(
    val items: List<DownloadEntryV2>, val skipped: List<SkippedDownload>, val page: PageInfo,
    @SerialName("batch_id") val batchId: String? = null,
)

/** Explicit user creation only: no uncertain POST replay or automatic guard replacement. */
class DownloadCreationV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val devices: DeviceMetadataProvider,
    private val registry: DownloadRegistryV2Api,
    private val gate: ApiV2Gate,
) {
    private fun invalid() = ApiResult.Error(0,"invalid_download_creation","The server returned an incomplete download creation receipt. Refresh downloads before trying again.")
    /** Identity plus the intentional extra device check: a download belongs to one installation. */
    private suspend fun current(scope: AuthScopeSnapshot, device: String): Boolean =
        !scope.profileId.isNullOrBlank() && scope.stillOwns(tokens, OwnerPolicy.IDENTITY) && devices.current()?.id == device

    private fun body(request: DownloadRequest) = buildJsonObject {
        put("content_id", request.contentId)
        request.episodeId?.let { put("episode_id",it) }
        request.fileId?.let { put("media_file_id",it.toString()) }
        put("quality", request.quality ?: "original")
    }.toMutableMap()

    private suspend fun send(scope: AuthScopeSnapshot, device: String, body: JsonObject, cursor: String? = null): ApiResult<CreatedDownloadsV2> {
        if (!current(scope,device)) return identityChanged()
        val result = ownedV2Call<CreatedDownloadsV2, CreatedDownloadsV2>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.Accepted, { owner ->
            client.post("/api/v2/downloads") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                // The auth plugin attaches X-Silo-Device-Id for the current device;
                // adding it here too sends two values, which the server stores as
                // "id,id" and the receipt guard then rejects. `device` is only the
                // identity the reply is checked against.
                parameter("limit",100); cursor?.let { parameter("cursor",it) }
                contentType(ContentType.Application.Json); setBody(body)
            }
        }) { it }
        return if (current(scope,device)) result else identityChanged()
    }

    suspend fun create(request: DownloadRequest, scope: AuthScopeSnapshot): ApiResult<DownloadRecord> {
        val device = devices.current()?.id?.takeIf { it.isNotBlank() } ?: return identityChanged()
        if (!current(scope,device)) return identityChanged()
        if (request.series || request.fileId == null || request.fileId <= 0) return invalid()
        // Reconcile first. A confirmed usable entry with the requested quality
        // can be downloaded without resetting its bytes/status/batch on the server.
        val listed = registry.list(scope)
        if (listed !is ApiResult.Success) return when(listed) {
            is ApiResult.Error -> listed
            is ApiResult.NetworkError -> listed
            else -> invalid()
        }
        if (!current(scope,device)) return identityChanged()
        val matches = listed.data.downloads.filter {
            it.mediaFileId == request.fileId ||
                (request.episodeId != null && it.episodeId == request.episodeId) ||
                it.episodeId == request.contentId ||
                (it.contentId == request.contentId && it.episodeId.isNullOrBlank())
        }
        if (matches.size > 1) return invalid()
        val existing = matches.singleOrNull()
        if (existing != null && existing.mediaFileId == request.fileId && existing.quality == (request.quality ?: "original") &&
            existing.status in setOf("ready","preparing","queued","downloading","completed")) return ApiResult.Success(existing)
        val fields = body(request)
        fields["expected_revision"] = JsonPrimitive(existing?.revision ?: 0)
        existing?.let { fields["expected_download_id"] = JsonPrimitive(it.id) }
        return when(val result = send(scope,device,JsonObject(fields))) {
            is ApiResult.Success -> {
                val receipt = result.data
                if (receipt.page.hasMore || !receipt.page.nextCursor.isNullOrBlank() || receipt.items.size != 1 || receipt.skipped.isNotEmpty()) return invalid()
                val row = try { receipt.items.single().project(device) } catch (_: IllegalArgumentException) { return invalid() }
                if (row.mediaFileId != request.fileId || row.quality != (request.quality ?: "original")) return invalid()
                ApiResult.Success(row)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun createBatch(request: DownloadRequest, scope: AuthScopeSnapshot): ApiResult<DownloadsListResponse> {
        val device = devices.current()?.id?.takeIf { it.isNotBlank() } ?: return identityChanged()
        if (!current(scope,device)) return identityChanged()
        if (!request.series || request.fileId != null || request.episodeId != null) return invalid()
        val batch = Uuid.random().toString()
        val fields = body(request)
        fields["series"] = JsonPrimitive(true)
        fields["batch_id"] = JsonPrimitive(batch)
        // Explicit empty batch guards preserve ALL existing episode entries.
        fields["expected_entries"] = JsonObject(emptyMap())
        val payload = JsonObject(fields)
        val rows = linkedMapOf<String,DownloadRecord>()
        val skipped = mutableListOf<SkippedDownload>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            when (val result = send(scope,device,payload,cursor)) {
                is ApiResult.Success -> {
                    val page = result.data
                    if (page.batchId != batch || page.items.size + page.skipped.size > 100) return invalid()
                    for (wire in page.items) {
                        val row = try { wire.project(device) } catch (_: IllegalArgumentException) { return invalid() }
                        if (row.contentId != request.contentId || rows.put(row.id,row) != null) return invalid()
                    }
                    skipped.addAll(page.skipped)
                    if (!page.page.hasMore) {
                        if (!page.page.nextCursor.isNullOrBlank()) return invalid()
                        return ApiResult.Success(DownloadsListResponse(rows.values.toList(),skipped))
                    }
                    val next = page.page.nextCursor
                    if (next.isNullOrBlank() || !cursors.add(next)) return invalid()
                    cursor = next // empty items with has_more is valid progress
                }
                is ApiResult.Error -> return result
                is ApiResult.NetworkError -> return result
            }
        }
        return invalid()
    }
}
