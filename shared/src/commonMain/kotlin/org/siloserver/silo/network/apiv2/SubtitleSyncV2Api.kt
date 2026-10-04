package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*

@Serializable private data class SyncListEnvelopeV2(val subtitles: List<SubtitleSyncState>)
@Serializable private data class SyncStateEnvelopeV2(val subtitle: SubtitleSyncState)

/** The server's sync key grammar; anything else is refused before it reaches a URL. */
private val syncKeyPattern = Regex("^(stored-[1-9][0-9]*|external-[0-9a-f]{64})$")

fun isSubtitleSyncKey(key: String): Boolean = syncKeyPattern.matches(key)

/**
 * Subtitle sync (silo-server "Subtitle sync"): the capability probe, and the
 * operations on one media file's syncable subtitles, each named by the sync
 * key the playback inventory publishes. Anyone who can play the file may
 * start a sync or set the timing; demo mode answers 403.
 */
class SubtitleSyncV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {

    /** GET /api/v2/subtitles/sync/status */
    suspend fun status(): ApiResult<SubtitleSyncCapability> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<SubtitleSyncCapability, SubtitleSyncCapability>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/subtitles/sync/status") { authScope(owner!!); requireSiloAuth() }
        }) { it }
    }

    /** GET /api/v2/subtitles/{media_file_id}/sync: every syncable subtitle of the file. */
    suspend fun list(mediaFileId: Int): ApiResult<List<SubtitleSyncState>> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        if (mediaFileId <= 0) return invalidFile()
        return ownedV2Call<SyncListEnvelopeV2, List<SubtitleSyncState>>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/subtitles/$mediaFileId/sync") { authScope(owner!!); requireSiloAuth() }
        }) { body -> body.subtitles.onEach { requireFile(it, mediaFileId) } }
    }

    /** GET /api/v2/subtitles/{media_file_id}/sync/{key}: one subtitle with its timing and latest job. */
    suspend fun read(mediaFileId: Int, key: String): ApiResult<SubtitleSyncState> =
        readWithValidator(mediaFileId, key).map { it.first }

    /** POST /api/v2/subtitles/{media_file_id}/sync/{key}: 202 with the subtitle and its new or active job. */
    suspend fun start(mediaFileId: Int, key: String): ApiResult<SubtitleSyncState> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        invalidTarget(mediaFileId, key)?.let { return it }
        return ownedV2Call<SyncStateEnvelopeV2, SubtitleSyncState>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.Accepted, { owner ->
            client.post(syncPath(mediaFileId, key)) { authScope(owner!!); requireSiloAuth(); singleAttempt() }
        }) { requireFile(it.subtitle, mediaFileId, key) }
    }

    /**
     * PUT /api/v2/subtitles/{media_file_id}/sync/{key}/timing. The PUT needs
     * the validator a read returns; a stale one answers 412. `{0, 1}` restores
     * the original timing.
     */
    suspend fun setTiming(mediaFileId: Int, key: String, timing: SubtitleTiming): ApiResult<SubtitleSyncState> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        val validator = when (val read = readWithValidator(mediaFileId, key)) {
            is ApiResult.Success -> read.data.second
                ?: return ApiResult.Error(0, "missing_etag", "Couldn't read the subtitle's current version.")
            is ApiResult.Error -> return read
            is ApiResult.NetworkError -> return read
        }
        return ownedV2Call<SyncStateEnvelopeV2, SubtitleSyncState>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.put("${syncPath(mediaFileId, key)}/timing") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                header(HttpHeaders.IfMatch, validator)
                contentType(ContentType.Application.Json)
                setBody(timing)
            }
        }) { requireFile(it.subtitle, mediaFileId, key) }
    }

    private suspend fun readWithValidator(mediaFileId: Int, key: String): ApiResult<Pair<SubtitleSyncState, String?>> {
        val scope = tokens.snapshotCurrentScope() ?: return identityChanged()
        invalidTarget(mediaFileId, key)?.let { return it }
        var etag: String? = null
        return ownedV2Call<SyncStateEnvelopeV2, Pair<SubtitleSyncState, String?>>(gate, tokens, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get(syncPath(mediaFileId, key)) { authScope(owner!!); requireSiloAuth() }
                .also { etag = it.headers[HttpHeaders.ETag]?.takeIf(String::isNotBlank) }
        }) { requireFile(it.subtitle, mediaFileId, key) to etag }
    }

    private fun syncPath(mediaFileId: Int, key: String) = "/api/v2/subtitles/$mediaFileId/sync/$key"

    private fun invalidTarget(mediaFileId: Int, key: String): ApiResult.Error? = when {
        mediaFileId <= 0 -> invalidFile()
        !isSubtitleSyncKey(key) -> ApiResult.Error(0, "invalid_sync_key", "This subtitle can't be synced.")
        else -> null
    }

    private fun invalidFile() = ApiResult.Error(0, "invalid_media_file", "A media file is required.")

    private fun requireFile(state: SubtitleSyncState, mediaFileId: Int, key: String? = null): SubtitleSyncState {
        require(state.mediaFileId == mediaFileId.toString()) { "The subtitle belongs to another file." }
        require(key == null || state.key == key) { "The server answered for another subtitle." }
        return state
    }
}
