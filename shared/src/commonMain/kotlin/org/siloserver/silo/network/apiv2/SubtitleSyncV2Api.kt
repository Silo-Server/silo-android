package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable private data class SyncJobEnvelopeV2(val job: SubtitleSyncJob)
@Serializable private data class StoredSubtitleEnvelopeV2(val subtitle: StoredSubtitleV2)

/**
 * Stored-subtitle sync (silo-server "Stored subtitle sync"): the capability
 * probe, starting a sync, reading its progress, and resetting the timing.
 * Starting a sync or changing the timing needs the account that added the
 * subtitle or an administrator; anyone else gets 403.
 */
class SubtitleSyncV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {

    /** GET /api/v2/subtitles/sync/status */
    suspend fun status(): ApiResult<SubtitleSyncCapability> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<SubtitleSyncCapability, SubtitleSyncCapability>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/subtitles/sync/status") { authScope(owner!!); requireSiloAuth() }
        }) { it }
    }

    /** POST /api/v2/subtitles/stored/{id}/sync: 202 with a new job or the subtitle's active one. */
    suspend fun requestSync(id: Int): ApiResult<SubtitleSyncJob> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        if (id <= 0) return ApiResult.Error(0, "invalid_subtitle", "A stored subtitle is required.")
        return ownedV2Call<SyncJobEnvelopeV2, SubtitleSyncJob>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.Accepted, { owner ->
            client.post("/api/v2/subtitles/stored/$id/sync") { authScope(owner!!); requireSiloAuth(); singleAttempt() }
        }) { it.job }
    }

    /** GET /api/v2/subtitles/stored/{id}/sync: the subtitle with its timing and latest job. */
    suspend fun read(id: Int, mediaFileId: Int): ApiResult<DownloadedSubtitle> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        if (id <= 0) return ApiResult.Error(0, "invalid_subtitle", "A stored subtitle is required.")
        return ownedV2Call<StoredSubtitleEnvelopeV2, DownloadedSubtitle>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/subtitles/stored/$id/sync") { authScope(owner!!); requireSiloAuth() }
        }) { it.subtitle.project(mediaFileId) }
    }

    /**
     * Resets the timing to `{offset_ms: 0, scale: 1}`. The PUT needs the
     * validator from GET /api/v2/subtitles/stored/{id}/metadata; a stale one
     * returns 412.
     */
    suspend fun resetTiming(id: Int, mediaFileId: Int): ApiResult<DownloadedSubtitle> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        if (id <= 0) return ApiResult.Error(0, "invalid_subtitle", "A stored subtitle is required.")
        var etag: String? = null
        val metadata = ownedV2Call<Unit, Unit>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/subtitles/stored/$id/metadata") { authScope(owner!!); requireSiloAuth() }
                .also { etag = it.headers[HttpHeaders.ETag] }
        }) { }
        when (metadata) {
            is ApiResult.Error -> return metadata
            is ApiResult.NetworkError -> return metadata
            is ApiResult.Success -> Unit
        }
        val validator = etag?.takeIf { it.isNotBlank() }
            ?: return ApiResult.Error(0, "missing_etag", "Couldn't read the subtitle's current version.")
        return ownedV2Call<StoredSubtitleEnvelopeV2, DownloadedSubtitle>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.put("/api/v2/subtitles/stored/$id/timing") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                header(HttpHeaders.IfMatch, validator)
                contentType(ContentType.Application.Json)
                setBody(SubtitleTiming())
            }
        }) { it.subtitle.project(mediaFileId) }
    }
}
