package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.OverlayConfigResponse

/**
 * Settings transport on API v2: configuration reads, the canonical
 * `/settings/values` scope writes, the device subtitle-appearance override,
 * and per-library playback preferences. Every call is pinned to the acting
 * identity captured before the request and rejected when that identity
 * changed while the request was in flight. Writes are naturally idempotent
 * desired-state writes; there is no mutation-ID receipt replay.
 */
class SettingsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    private suspend fun current(scope: AuthScopeSnapshot): Boolean {
        val now = tokens.snapshotCurrentScope()
        return scope.isSameIdentityAs(now) && scope.profileId == now?.profileId && scope.profileToken == now?.profileToken
    }

    private fun changed() = ApiResult.Error(0, "identity_changed", "The settings account or profile changed.")

    /** One pinned, identity-checked exchange. */
    private suspend inline fun <T> exchange(
        expected: AuthScopeSnapshot? = null,
        block: (AuthScopeSnapshot) -> ApiResult<T>,
    ): ApiResult<T> {
        val owner = expected ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!current(owner)) return changed()
        val result = block(owner)
        return if (current(owner)) result else changed()
    }

    private suspend fun <T> read(
        path: String,
        expected: AuthScopeSnapshot? = null,
        configure: HttpRequestBuilder.() -> Unit = {},
        project: (JsonObject, AuthScopeSnapshot) -> T,
    ): ApiResult<T> = exchange(expected) { scope ->
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get(path) { authScope(scope); requireSiloAuth(); configure() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        when (result) {
            is ApiResult.Success -> try { ApiResult.Success(project(result.data, scope)) }
                catch (_: Exception) {
                    ApiResult.Error(0, "invalid_settings", "The server returned unsupported settings identities.")
                }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

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

    suspend fun capabilities(): ApiResult<SettingsContractCapabilities> = exchange { owner ->
        val result = safeApiV2Call<SettingsContractCapabilities>(gate) {
            client.get("/api/v2/settings/contract/capabilities") {
                authScope(owner); requireSiloAuth()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        when (result) {
            // The shared server view can advertise legacy receipt support, but
            // v2 does not declare that header. Do not expose it as a guarantee.
            is ApiResult.Success -> ApiResult.Success(result.data.copy(supportsIdempotentWrites = false))
            else -> result
        }
    }

    /** GET /api/v2/settings/subtitle-appearance/effective for the acting profile on this device. */
    suspend fun effectiveSubtitleAppearance(): ApiResult<EffectiveSubtitleAppearance> =
        read("/api/v2/settings/subtitle-appearance/effective") { body, scope ->
            require(stringID(body.getValue("profile_id")) == scope.profileId)
            SiloJson.decodeFromJsonElement(EffectiveSubtitleAppearance.serializer(), body)
        }

    // ---- canonical scope writes ----

    suspend fun put(
        key: String, scope: SettingScopeIdentity, value: JsonElement,
        profileId: String?, expected: AuthScopeSnapshot?,
    ): ApiResult<StoredSettingValue> = exchange(expected) { owner ->
        var sentDevice: String? = null
        var sentFamily: String? = null
        val result = safeApiV2Call<JsonObject>(gate) {
            client.put("/api/v2/settings/values/${key.encodeURLPathPart()}") {
                identity(scope, profileId, owner)
                contentType(ContentType.Application.Json)
                setBody(SettingValueWriteRequest(value))
            }.also {
                check(!it.status.isSuccess() || it.status == HttpStatusCode.OK)
                sentDevice = it.call.request.headers["X-Silo-Device-Id"]
                sentFamily = it.call.request.headers["X-Silo-Client-Family"]
            }
        }
        when (result) {
            is ApiResult.Success -> try {
                val fields = result.data.toMutableMap()
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
                ApiResult.Success(row)
            } catch (_: Exception) {
                ApiResult.Error(0, "invalid_settings", "The server returned an unsupported settings receipt.")
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun delete(
        key: String, scope: SettingScopeIdentity, profileId: String?, expected: AuthScopeSnapshot?,
    ): ApiResult<Unit> = exchange(expected) { owner ->
        safeApiV2Call<Unit>(gate) {
            client.delete("/api/v2/settings/values/${key.encodeURLPathPart()}") {
                identity(scope, profileId, owner)
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
    }

    private fun HttpRequestBuilder.identity(scope: SettingScopeIdentity, profileId: String?, owner: AuthScopeSnapshot) {
        authScope(owner); requireSiloAuth()
        url {
            parameters.append("scope", scope.scope.wire)
            if (scope.scope != SettingScope.ACCOUNT) profileId?.let { parameters.append("profile_id", it) }
            scope.libraryId?.let { parameters.append("library_id", it.toString()) }
            scope.seriesId?.let { parameters.append("series_id", it) }
        }
    }

    // ---- device subtitle-appearance override ----

    /**
     * PUT /api/v2/settings/device/subtitle-appearance (200): the override for
     * the acting profile on this device. An explicit [profileId] replaces the
     * session's `X-Profile-Id` so a parent can act for a child profile.
     */
    suspend fun putDeviceSubtitleAppearance(appearance: SubtitleAppearance, profileId: String? = null): ApiResult<Unit> =
        exchange { owner ->
            safeApiV2Call<Unit>(gate) {
                client.put("/api/v2/settings/device/subtitle-appearance") {
                    authScope(owner); requireSiloAuth()
                    if (!profileId.isNullOrBlank()) header("X-Profile-Id", profileId)
                    contentType(ContentType.Application.Json)
                    setBody(SubtitleAppearanceDeviceOverride(appearance.toJsonString()))
                }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
            }
        }

    /** DELETE /api/v2/settings/device/subtitle-appearance (204). */
    suspend fun deleteDeviceSubtitleAppearance(): ApiResult<Unit> = exchange { owner ->
        safeApiV2Call<Unit>(gate) {
            client.delete("/api/v2/settings/device/subtitle-appearance") {
                authScope(owner); requireSiloAuth()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
    }

    // ---- per-library playback preferences ----

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

    /**
     * PATCH /api/v2/library-playback-prefs/{library_id} (204). The server
     * leaves omitted members unchanged and clears null ones, so every member
     * is sent explicitly: the store's `setPref` replaces the whole override.
     */
    suspend fun patchLibraryPreference(libraryId: Int, request: LibraryPlaybackPrefRequest): ApiResult<Unit> =
        exchange { owner ->
            safeApiV2Call<Unit>(gate) {
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
                }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
            }
        }

    /** DELETE /api/v2/library-playback-prefs/{library_id} (204). */
    suspend fun deleteLibraryPreference(libraryId: Int): ApiResult<Unit> = exchange { owner ->
        safeApiV2Call<Unit>(gate) {
            client.delete("/api/v2/library-playback-prefs/$libraryId") {
                authScope(owner); requireSiloAuth()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
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
