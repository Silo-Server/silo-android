package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.OverlayConfigResponse

/**
 * Settings transport on API v2: configuration reads, the canonical
 * `/settings/values` scope writes, and per-library playback preferences. Every call is pinned to the acting
 * identity captured before the request and rejected when that identity
 * changed while the request was in flight. Writes are naturally idempotent
 * desired-state writes; there is no mutation-ID receipt replay.
 */
class SettingsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    /** One pinned, identity-checked exchange bound to [expected] or the current scope. */
    private suspend inline fun <reified T, R> exchange(
        expected: AuthScopeSnapshot? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        crossinline block: suspend (AuthScopeSnapshot) -> HttpResponse,
        crossinline project: (T, AuthScopeSnapshot) -> R,
    ): ApiResult<R> {
        val owner = expected ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, owner, OwnerPolicy.PROFILE, status, { block(owner) }) { project(it, owner) }
    }

    private suspend fun <T> read(
        path: String,
        expected: AuthScopeSnapshot? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
        project: (JsonObject, AuthScopeSnapshot) -> T,
    ): ApiResult<T> = exchange<JsonObject, T>(expected, HttpStatusCode.OK, { scope ->
        client.get(path) { authScope(scope); requireSiloAuth(); configure() }
    }, project)

    // ---- reads ----

    suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> = read("/api/v2/settings/overlay-config") { body, _ ->
        SiloJson.decodeFromJsonElement(OverlayConfigResponse.serializer(), body)
    }

    suspend fun effectiveValues(keys: List<String>, libraries: List<Int>, series: List<String>, expected: AuthScopeSnapshot? = null): ApiResult<EffectiveSettingValuesResponse> =
        read("/api/v2/settings/values/effective", expected, {
            url {
                keys.forEach { parameters.append("keys", it) }
                libraries.forEach { parameters.append("library_ids", it.toString()) }
                series.forEach { parameters.append("series_ids", it) }
            }
        }) { body, scope ->
            val rows = requireNotNull(body["items"]).jsonArray.map { item ->
                val row = item.jsonObject
                row["profile_id"]?.let { require(stringID(it) == scope.profileId) }
                val fields = row.toMutableMap()
                row["library_id"]?.let { fields["library_id"] = JsonPrimitive(libraryID(it)) }
                SiloJson.decodeFromJsonElement(EffectiveSettingValue.serializer(), JsonObject(fields))
            }
            require(rows.map { it.key }.distinct().size == rows.size)
            EffectiveSettingValuesResponse(rows, requireNotNull(body["revision"]).jsonPrimitive.int)
        }

    suspend fun capabilities(): ApiResult<SettingsContractCapabilities> =
        exchange<SettingsContractCapabilities, SettingsContractCapabilities>(block = { owner ->
            client.get("/api/v2/settings/contract/capabilities") { authScope(owner); requireSiloAuth() }
        }) { capabilities, _ -> capabilities }

    // ---- canonical scope writes ----

    suspend fun put(
        key: String, scope: SettingScopeIdentity, value: JsonElement,
        profileId: String?, expected: AuthScopeSnapshot?,
    ): ApiResult<StoredSettingValue> {
        var sentDevice: String? = null
        var sentFamily: String? = null
        return exchange<JsonObject, StoredSettingValue>(expected, HttpStatusCode.OK, { owner ->
            client.put("/api/v2/settings/values/${key.encodeURLPathPart()}") {
                identity(scope, profileId, owner)
                contentType(ContentType.Application.Json)
                setBody(SettingValueWriteRequest(value))
            }.also {
                sentDevice = it.call.request.headers["X-Silo-Device-Id"]
                sentFamily = it.call.request.headers["X-Silo-Client-Family"]
            }
        }) { body, owner ->
            val fields = body.toMutableMap()
            for (name in listOf("profile_id", "device_id", "client_family", "series_id")) {
                fields[name]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.isNotBlank()) }
            }
            fields["library_id"]?.let { fields["library_id"] = JsonPrimitive(libraryID(it)) }
            val row = SiloJson.decodeFromJsonElement(StoredSettingValue.serializer(), JsonObject(fields))
            require(row.key == key && row.scope == scope.scope.wire && row.revision > 0)
            require(row.profileId == if (scope.scope == SettingScope.ACCOUNT) null else profileId ?: owner.profileId)
            require(row.libraryId == scope.libraryId && row.seriesId == scope.seriesId)
            if (scope.scope == SettingScope.PROFILE_DEVICE) {
                require(!sentDevice.isNullOrBlank() && row.deviceId == sentDevice)
            } else require(row.deviceId == null)
            if (scope.scope == SettingScope.PROFILE_CLIENT) {
                require(!sentFamily.isNullOrBlank() && row.clientFamily == sentFamily)
            } else require(row.clientFamily == null)
            row
        }
    }

    suspend fun delete(
        key: String, scope: SettingScopeIdentity, profileId: String?, expected: AuthScopeSnapshot?,
    ): ApiResult<Unit> = exchange<Unit, Unit>(expected, HttpStatusCode.NoContent, { owner ->
        client.delete("/api/v2/settings/values/${key.encodeURLPathPart()}") { identity(scope, profileId, owner) }
    }) { _, _ -> }

    private fun HttpRequestBuilder.identity(scope: SettingScopeIdentity, profileId: String?, owner: AuthScopeSnapshot) {
        authScope(owner); requireSiloAuth()
        url {
            parameters.append("scope", scope.scope.wire)
            if (scope.scope != SettingScope.ACCOUNT) profileId?.let { parameters.append("profile_id", it) }
            scope.libraryId?.let { parameters.append("library_id", it.toString()) }
            scope.seriesId?.let { parameters.append("series_id", it) }
        }
    }

    // ---- per-library playback preferences ----

    suspend fun libraryPreferences(): ApiResult<LibraryPlaybackPrefsResponse> = read("/api/v2/library-playback-prefs") { body, scope ->
        val rows = requireNotNull(body["items"]).jsonArray.map { item ->
            val row = item.jsonObject
            require(stringID(requireNotNull(row["profile_id"])) == scope.profileId)
            val fields = row.toMutableMap()
            fields["library_id"] = JsonPrimitive(libraryID(requireNotNull(row["library_id"])))
            SiloJson.decodeFromJsonElement(LibraryPlaybackPref.serializer(), JsonObject(fields))
        }
        require(rows.map { it.libraryId }.distinct().size == rows.size)
        LibraryPlaybackPrefsResponse(rows)
    }

    /**
     * PATCH /api/v2/library-playback-prefs/{library_id} (204). The server
     * leaves omitted members unchanged and clears null ones, so every member
     * is sent explicitly: the store's `setPref` replaces the whole override.
     */
    suspend fun patchLibraryPreference(libraryId: Int, request: LibraryPlaybackPrefRequest): ApiResult<Unit> =
        exchange<Unit, Unit>(status = HttpStatusCode.NoContent, block = { owner ->
            client.patch("/api/v2/library-playback-prefs/$libraryId") {
                authScope(owner); requireSiloAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("audio_language", request.audioLanguage)
                        put("subtitle_language", request.subtitleLanguage)
                        put("subtitle_mode", request.subtitleMode)
                        put("show_forced_subtitles", request.showForcedSubtitles)
                    },
                )
            }
        }) { _, _ -> }

    /** DELETE /api/v2/library-playback-prefs/{library_id} (204). */
    suspend fun deleteLibraryPreference(libraryId: Int): ApiResult<Unit> = exchange<Unit, Unit>(status = HttpStatusCode.NoContent, block = { owner ->
        client.delete("/api/v2/library-playback-prefs/$libraryId") { authScope(owner); requireSiloAuth() }
    }) { _, _ -> }

    private fun stringID(value: JsonElement): String {
        val primitive = value.jsonPrimitive
        require(primitive.isString && primitive.content.isNotBlank())
        return primitive.content
    }

    private fun libraryID(value: JsonElement): Int = checkedPositiveId(stringID(value))
}
