package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*

/** Naturally idempotent desired-state writes; no mutation-ID receipt replay. */
class SettingsWritesV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    private suspend fun current(scope: AuthScopeSnapshot): Boolean {
        val now = tokens.snapshotCurrentScope()
        return scope.isSameIdentityAs(now) && scope.profileId == now?.profileId && scope.profileToken == now?.profileToken
    }

    private fun changed() = ApiResult.Error(0, "identity_changed", "The settings account or profile changed.")

    suspend fun capabilities(): ApiResult<SettingsContractCapabilities> {
        val owner = tokens.snapshotCurrentScope() ?: return changed()
        val result = safeApiV2Call<SettingsContractCapabilities>(gate) {
            client.get("/api/v2/settings/contract/capabilities") {
                authScope(owner); requireSiloAuth()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            // The shared server view can advertise legacy receipt support, but
            // v2 does not declare that header. Do not expose it as a guarantee.
            is ApiResult.Success -> ApiResult.Success(result.data.copy(supportsIdempotentWrites = false))
            else -> result
        }
    }

    suspend fun put(
        key: String, scope: SettingScopeIdentity, value: JsonElement,
        profileId: String?, expected: AuthScopeSnapshot?,
    ): ApiResult<StoredSettingValue> {
        val owner = expected ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!current(owner)) return changed()
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
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val fields = result.data.toMutableMap()
                for (name in listOf("profile_id", "device_id", "client_family", "series_id")) {
                    fields[name]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content.isNotBlank()) }
                }
                fields["library_id"]?.let {
                    val id = it.jsonPrimitive
                    require(id.isString)
                    val number = requireNotNull(id.content.toIntOrNull())
                    require(number > 0 && number.toString() == id.content)
                    fields["library_id"] = JsonPrimitive(number)
                }
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
    ): ApiResult<Unit> {
        val owner = expected ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!current(owner)) return changed()
        val result = safeApiV2Call<Unit>(gate) {
            client.delete("/api/v2/settings/values/${key.encodeURLPathPart()}") {
                identity(scope, profileId, owner)
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        return if (current(owner)) result else changed()
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
}
