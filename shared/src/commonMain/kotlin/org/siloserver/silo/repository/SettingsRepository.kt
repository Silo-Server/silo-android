package org.siloserver.silo.repository

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OverlayConfigResponse
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.map
import kotlinx.serialization.json.JsonElement

class SettingsRepository(
    private val settingsApi: SettingsApi,
) {
    suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> =
        settingsApi.overlayConfig()

    /**
     * Batched canonical resolution (`GET /api/v2/settings/values/effective`):
     * typed JSON values, each with the scope it resolved from. A key the
     * server's contract does not know is simply absent from the map.
     */
    suspend fun getEffectiveValues(
        keys: List<String> = emptyList(),
        libraryIds: List<Int> = emptyList(),
        seriesIds: List<String> = emptyList(),
        authority: org.siloserver.silo.network.AuthScopeSnapshot? = null,
    ): ApiResult<Map<String, EffectiveSettingValue>> =
        settingsApi.getEffectiveValues(keys, libraryIds, seriesIds, authority).map { response ->
            response.settings.associateBy { it.key }
        }

    /** What the connected server's settings contract supports. */
    suspend fun contractCapabilities(): ApiResult<SettingsContractCapabilities> =
        settingsApi.getContractCapabilities()

    /**
     * Write one profile-scoped value (`scope=profile`) — the household
     * preference that applies on every device until a device overrides it.
     *
     * Picker callers surface failure and let the user make a new decision.
     */
    suspend fun setProfileValue(key: String, value: JsonElement): ApiResult<StoredSettingValue> =
        settingsApi.putValue(key, SettingScopeIdentity.profile(), value)

    /** Clear the profile-scoped value so the setting inherits again. */
    suspend fun clearProfileValue(key: String): ApiResult<Unit> =
        treatMissingAsCleared(settingsApi.deleteValue(key, SettingScopeIdentity.profile()))

    /**
     * Write one like-client value (`scope=profile_client`) — the preference
     * that roams among this profile's devices of the same client family. The
     * family half of the identity rides the `X-Silo-Client-Family` header the
     * auth interceptor attaches.
     */
    suspend fun setProfileClientValue(key: String, value: JsonElement): ApiResult<StoredSettingValue> =
        settingsApi.putValue(key, SettingScopeIdentity.profileClient(), value)

    suspend fun clearProfileClientValue(key: String): ApiResult<Unit> =
        treatMissingAsCleared(settingsApi.deleteValue(key, SettingScopeIdentity.profileClient()))

    /**
     * Write one device-override value (`scope=profile_device`) — this profile
     * on this device only. The device half of the identity rides the
     * `X-Silo-Device-Id` header.
     */
    suspend fun setProfileDeviceValue(key: String, value: JsonElement): ApiResult<StoredSettingValue> =
        settingsApi.putValue(key, SettingScopeIdentity.profileDevice(), value)

    suspend fun setMigrationDeviceValue(key: String, value: JsonElement, authority: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<StoredSettingValue> =
        settingsApi.putValue(key, SettingScopeIdentity.profileDevice(), value, authority.profileId, authority)

    suspend fun clearProfileDeviceValue(key: String): ApiResult<Unit> =
        treatMissingAsCleared(settingsApi.deleteValue(key, SettingScopeIdentity.profileDevice()))

    /**
     * 404 on a DELETE means nothing was stored there, which is the state the
     * caller asked for, so it reports success rather than an error the UI
     * would have to special-case.
     */
    private fun treatMissingAsCleared(result: ApiResult<Unit>): ApiResult<Unit> =
        when (result) {
            is ApiResult.Error ->
                if (result.code == 404) ApiResult.Success(Unit) else result
            else -> result
        }
}
