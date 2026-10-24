package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.network.*
import org.siloserver.silo.playback.TrackPreferenceKind

/**
 * `/api/v2/{audio,subtitle}-prefs/{key}`: the acting profile's remembered
 * track for a series (or a movie, keyed by its own id). A write replaces the
 * row and answers 204. Every call is pinned to [owner], the profile the pick
 * was made under, and refused once that profile is no longer the active one.
 */
class TrackPreferencesV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    /** The saved row for [key] (null when the profile has none, a 404) and the server's clock. */
    suspend fun read(owner: AuthScopeSnapshot, kind: TrackPreferenceKind, key: String): ApiResult<TrackPreferenceRead> {
        var serverDate: String? = null
        val result = ownedV2Call<JsonObject, JsonObject>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { scope ->
            client.get(path(kind, key)) {
                authScope(scope!!); requireSiloAuth()
            }.also { serverDate = it.headers[HttpHeaders.Date] }
        }) { it }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(TrackPreferenceRead(result.data, serverDate))
            is ApiResult.Error ->
                if (result.code == HttpStatusCode.NotFound.value) ApiResult.Success(TrackPreferenceRead(null, serverDate)) else result
            is ApiResult.NetworkError -> result
        }
    }

    /** Replaces the row for [key] with [body], an update document of [kind]. */
    suspend fun put(owner: AuthScopeSnapshot, kind: TrackPreferenceKind, key: String, body: String): ApiResult<Unit> =
        ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.NoContent, { scope ->
            client.put(path(kind, key)) {
                authScope(scope!!); requireSiloAuth(); singleAttempt()
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }) { it }

    private fun path(kind: TrackPreferenceKind, key: String) = "/api/v2/${kind.path}/${key.encodeURLPathPart()}"
}

/** A read of one remembered track: its row (null without one) and the response's `Date`. */
data class TrackPreferenceRead(val row: JsonObject?, val serverDate: String?) {
    val updatedAt: String? get() = (row?.get("updated_at") as? JsonPrimitive)?.content
}

/**
 * The series' saved audio pick as the server resolved it against [version]
 * (`effective_audio_track_index`), or null when there is no saved pick or it
 * could not be read. Without a saved pick that index is only the server's
 * default, which must not displace the client's own automatic choice.
 */
suspend fun TrackPreferencesV2Api.savedAudioTrackIndex(owner: AuthScopeSnapshot, key: String?, version: FileVersion): Int? {
    val index = version.effectiveAudioTrackIndex?.takeIf { it in version.audioTracks.orEmpty().indices } ?: return null
    key ?: return null
    return index.takeIf { (read(owner, TrackPreferenceKind.AUDIO, key) as? ApiResult.Success)?.data?.row != null }
}
