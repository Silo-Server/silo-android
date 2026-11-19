package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.*
import org.siloserver.silo.playback.trickplay.TrickplayManifest
import org.siloserver.silo.playback.trickplay.TrickplayManifestSource
import org.siloserver.silo.playback.trickplay.TrickplayTarget

/**
 * `GET /api/v2/watch/{id}/trickplay?file_id=`: one file's seek-bar previews.
 * Thumbnail i shows what plays from `i*interval_ms` to `(i+1)*interval_ms`;
 * sheet s holds thumbnails `s*tile_columns*tile_rows` onward, left to right
 * and top to bottom. Each sheet URL carries its own authorization until
 * [expiresAt]; read the manifest again for fresh ones.
 */
@Serializable
data class WatchTrickplayV2(
    @SerialName("file_id") val fileId: String,
    @SerialName("interval_ms") val intervalMs: Long,
    @SerialName("thumbnail_width") val thumbnailWidth: Long,
    @SerialName("thumbnail_height") val thumbnailHeight: Long,
    @SerialName("tile_columns") val tileColumns: Long,
    @SerialName("tile_rows") val tileRows: Long,
    @SerialName("thumbnail_count") val thumbnailCount: Long,
    val sheets: List<WatchTrickplaySheetV2>,
    @SerialName("expires_at") val expiresAt: String,
)

/** [url] is a credential: [toString] leaves it out. */
@Serializable
data class WatchTrickplaySheetV2(
    val index: Long,
    val url: String,
) {
    override fun toString(): String = "WatchTrickplaySheetV2(index=$index)"
}

/**
 * Seek-bar previews (`trickplay_v1`, `docs/playback-api.md` "Seek previews").
 * Read only for a version whose `trickplay_available` is true; the manifest
 * follows the same access rules as the watch detail.
 */
class TrickplayV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) : TrickplayManifestSource {
    override suspend fun manifest(target: TrickplayTarget): ApiResult<TrickplayManifest> {
        val owner = tokens.captureProfileScope() ?: return identityChanged()
        var requestUrl: String? = null
        return ownedV2Call<WatchTrickplayV2, TrickplayManifest>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/watch/${target.contentId.encodeURLPathPart()}/trickplay") {
                authScope(scope!!)
                requireSiloAuth()
                parameter("file_id", target.fileId)
            }.also { requestUrl = it.call.request.url.toString() }
        }) { v2 ->
            val manifest = requestUrl?.let { TrickplayManifest.from(v2, it) }
            requireNotNull(manifest) { "The server returned seek-bar previews that cannot be cut." }
            require(manifest.fileId == target.fileId) { "The server returned another file's seek-bar previews." }
            manifest
        }
    }
}
