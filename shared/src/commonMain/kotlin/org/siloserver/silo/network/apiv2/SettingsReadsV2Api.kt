package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.OverlayConfigResponse

/** Existing foreground/configuration reads. Mutation receipt policy is separate. */
class SettingsReadsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    private suspend fun <T> read(
        path: String,
        configure: HttpRequestBuilder.() -> Unit = {},
        project: (JsonObject, AuthScopeSnapshot) -> T,
    ): ApiResult<T> {
        val scope = tokens.snapshotCurrentScope() ?: return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get(path) { authScope(scope); requireSiloAuth(); configure() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!scope.isSameIdentityAs(tokens.snapshotCurrentScope())) return changed()
        return when (result) {
            is ApiResult.Success -> try { ApiResult.Success(project(result.data, scope)) }
                catch (_: Exception) {
                    ApiResult.Error(0, "invalid_settings", "The server returned unsupported settings identities.")
                }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private fun changed() = ApiResult.Error(0, "identity_changed", "The settings account or profile changed.")

    suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> = read("/api/v2/settings/overlay-config") { body, _ ->
        SiloJson.decodeFromJsonElement(OverlayConfigResponse.serializer(), body)
    }

    suspend fun effectiveValues(keys: List<String>, libraries: List<Int>, series: List<String>): ApiResult<EffectiveSettingValuesResponse> =
        read("/api/v2/settings/values/effective", {
            url {
                keys.forEach { parameters.append("keys", it) }
                libraries.forEach { parameters.append("library_ids", it.toString()) }
                series.forEach { parameters.append("series_ids", it) }
            }
        }) { body, scope ->
            val rows = body.getValue("items").jsonArray.map { item ->
                val row = item.jsonObject
                row["profile_id"]?.let { require(stringID(it) == scope.profileId) }
                val fields = row.toMutableMap()
                row["library_id"]?.let { fields["library_id"] = JsonPrimitive(libraryID(it)) }
                SiloJson.decodeFromJsonElement(EffectiveSettingValue.serializer(), JsonObject(fields))
            }
            require(rows.map { it.key }.distinct().size == rows.size)
            EffectiveSettingValuesResponse(rows, body.getValue("revision").jsonPrimitive.int)
        }

    suspend fun libraryPreferences(): ApiResult<LibraryPlaybackPrefsResponse> = read("/api/v2/library-playback-prefs") { body, scope ->
        val rows = body.getValue("items").jsonArray.map { item ->
            val row = item.jsonObject
            require(stringID(row.getValue("profile_id")) == scope.profileId)
            val fields = row.toMutableMap()
            fields["library_id"] = JsonPrimitive(libraryID(row.getValue("library_id")))
            SiloJson.decodeFromJsonElement(LibraryPlaybackPref.serializer(), JsonObject(fields))
        }
        require(rows.map { it.libraryId }.distinct().size == rows.size)
        LibraryPlaybackPrefsResponse(rows)
    }

    private fun stringID(value: JsonElement): String {
        val primitive = value.jsonPrimitive
        require(primitive.isString && primitive.content.isNotBlank())
        return primitive.content
    }

    private fun libraryID(value: JsonElement): Int {
        val text = stringID(value)
        val id = requireNotNull(text.toIntOrNull())
        require(id > 0 && id.toString() == text)
        return id
    }
}
