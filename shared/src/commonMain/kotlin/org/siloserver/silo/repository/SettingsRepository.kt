package org.siloserver.silo.repository

import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSubtitleAppearance
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OverlayConfigResponse
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.network.api.newSettingMutationId
import org.siloserver.silo.network.map
import kotlinx.serialization.json.JsonElement

class SettingsRepository(
    private val settingsApi: SettingsApi,
) {
    suspend fun listSettings(): ApiResult<Map<String, String>> =
        settingsApi.getSettings().map { response ->
            response.settings.associate { it.key to it.value }
        }

    suspend fun getSetting(key: String): ApiResult<String> =
        settingsApi.getSetting(key).map { it.value }

    suspend fun setSetting(key: String, value: String): ApiResult<Unit> =
        settingsApi.setSetting(key, value)

    suspend fun deleteSetting(key: String): ApiResult<Unit> =
        settingsApi.deleteSetting(key)

    suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> =
        settingsApi.overlayConfig()

    suspend fun getDeviceSetting(key: String): ApiResult<String> =
        settingsApi.getDeviceSetting(key).map { it.value }

    suspend fun setDeviceSetting(key: String, value: String): ApiResult<Unit> =
        settingsApi.setDeviceSetting(key, value)

    suspend fun deleteDeviceSetting(key: String): ApiResult<Unit> =
        settingsApi.deleteDeviceSetting(key)

    suspend fun getEffectiveSettings(keys: List<String>): ApiResult<Map<String, EffectiveSetting>> =
        settingsApi.getEffectiveSettings(keys).map { response ->
            response.settings.associateBy { it.key }
        }

    /**
     * Batched canonical resolution (`GET /api/v1/settings/values/effective`):
     * typed JSON values, each with the scope it resolved from. A key the
     * server's contract does not know is simply absent from the map.
     */
    suspend fun getEffectiveValues(
        keys: List<String> = emptyList(),
        libraryIds: List<Int> = emptyList(),
        seriesIds: List<String> = emptyList(),
    ): ApiResult<Map<String, EffectiveSettingValue>> =
        settingsApi.getEffectiveValues(keys, libraryIds, seriesIds).map { response ->
            response.settings.associateBy { it.key }
        }

    /**
     * What the connected server's settings contract supports, or
     * [SettingsCapabilitiesResult.ServerUpgradeRequired] when it predates the
     * canonical settings API. Screens surface that case as an explanation
     * rather than as an empty list of settings.
     */
    suspend fun contractCapabilities(): SettingsCapabilitiesResult =
        settingsApi.getContractCapabilities()

    /**
     * Write one profile-scoped value (`scope=profile`) — the household
     * preference that applies on every device until a device overrides it.
     *
     * A fresh mutation id per call is correct here because one call is one
     * logical write: these callers are settings pickers that roll their UI
     * back on failure, so a user re-picking is genuinely new content and must
     * not replay an id (that is exactly the 409 `mutation_id_conflict` case).
     * A caller that retries the *same* write must pass the id it already used.
     */
    suspend fun setProfileValue(
        key: String,
        value: JsonElement,
        mutationId: String = newSettingMutationId(),
    ): ApiResult<StoredSettingValue> =
        settingsApi.putValue(
            key = key,
            scope = SettingScopeIdentity.profile(),
            value = value,
            mutationId = mutationId,
        )

    /**
     * Clear the profile-scoped value so the setting inherits again. 404 means
     * nothing was stored there, which is the state the caller asked for, so it
     * reports success rather than an error the UI would have to special-case.
     */
    suspend fun clearProfileValue(key: String): ApiResult<Unit> =
        when (val result = settingsApi.deleteValue(key, SettingScopeIdentity.profile())) {
            is ApiResult.Error ->
                if (result.code == 404) ApiResult.Success(Unit) else result
            else -> result
        }

    /**
     * Write one like-client value (`scope=profile_client`) — the preference
     * that roams among this profile's devices of the same client family. The
     * family half of the identity rides the `X-Silo-Client-Family` header the
     * auth interceptor attaches; see [setProfileValue] for the mutation-id
     * contract.
     */
    suspend fun setProfileClientValue(
        key: String,
        value: JsonElement,
        mutationId: String = newSettingMutationId(),
    ): ApiResult<StoredSettingValue> =
        settingsApi.putValue(
            key = key,
            scope = SettingScopeIdentity.profileClient(),
            value = value,
            mutationId = mutationId,
        )

    suspend fun clearProfileClientValue(key: String): ApiResult<Unit> =
        treatMissingAsCleared(
            settingsApi.deleteValue(key, SettingScopeIdentity.profileClient()),
        )

    /**
     * Write one device-override value (`scope=profile_device`) — this profile
     * on this device only. The device half of the identity rides the
     * `X-Silo-Device-Id` header.
     */
    suspend fun setProfileDeviceValue(
        key: String,
        value: JsonElement,
        mutationId: String = newSettingMutationId(),
    ): ApiResult<StoredSettingValue> =
        settingsApi.putValue(
            key = key,
            scope = SettingScopeIdentity.profileDevice(),
            value = value,
            mutationId = mutationId,
        )

    suspend fun clearProfileDeviceValue(key: String): ApiResult<Unit> =
        treatMissingAsCleared(
            settingsApi.deleteValue(key, SettingScopeIdentity.profileDevice()),
        )

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

    suspend fun getEffectiveSubtitleAppearance(): ApiResult<EffectiveSubtitleAppearance> =
        settingsApi.getEffectiveSubtitleAppearance()

    suspend fun setDeviceSubtitleAppearanceOverride(
        appearance: SubtitleAppearance,
        profileId: String? = null,
    ): ApiResult<Unit> =
        settingsApi.setDeviceSubtitleAppearanceOverride(appearance, profileId)

    suspend fun deleteDeviceSubtitleAppearanceOverride(): ApiResult<Unit> =
        settingsApi.deleteDeviceSubtitleAppearanceOverride()
}
