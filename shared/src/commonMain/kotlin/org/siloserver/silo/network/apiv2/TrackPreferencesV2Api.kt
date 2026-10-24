package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.AudioPreferenceUpdate
import org.siloserver.silo.model.playback.SubtitlePreferenceUpdate
import org.siloserver.silo.network.*

/**
 * `/api/v2/{audio,subtitle}-prefs/{key}`: the acting profile's remembered
 * track for a series (or a movie, keyed by its own id). A write replaces the
 * row and answers 204; it is pinned to [owner], the profile the pick was made
 * under, and refused once that profile is no longer the active one.
 */
class TrackPreferencesV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    /** The saved audio row for [key], or null when the profile has none (404). */
    suspend fun getAudio(owner: AuthScopeSnapshot, key: String): ApiResult<SavedTrackPreference?> {
        val result = ownedV2Call<SavedTrackPreference, SavedTrackPreference>(
            gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.OK, { scope ->
                client.get("/api/v2/audio-prefs/${key.encodeURLPathPart()}") {
                    authScope(scope!!); requireSiloAuth()
                }
            },
        ) { it }
        return if (result is ApiResult.Error && result.code == HttpStatusCode.NotFound.value) ApiResult.Success(null) else result
    }

    suspend fun putAudio(owner: AuthScopeSnapshot, key: String, body: AudioPreferenceUpdate): ApiResult<Unit> =
        put(owner, "/api/v2/audio-prefs/${key.encodeURLPathPart()}", body)

    suspend fun putSubtitle(owner: AuthScopeSnapshot, key: String, body: SubtitlePreferenceUpdate): ApiResult<Unit> =
        put(owner, "/api/v2/subtitle-prefs/${key.encodeURLPathPart()}", body)

    private suspend inline fun <reified B : Any> put(owner: AuthScopeSnapshot, path: String, body: B): ApiResult<Unit> =
        ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.PROFILE, HttpStatusCode.NoContent, { scope ->
            client.put(path) {
                authScope(scope!!); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }) { it }
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
    return index.takeIf { (getAudio(owner, key) as? ApiResult.Success)?.data != null }
}

/** The members of a saved preference row this client reads. */
@Serializable
data class SavedTrackPreference(
    @SerialName("updated_at") val updatedAt: String? = null,
)
