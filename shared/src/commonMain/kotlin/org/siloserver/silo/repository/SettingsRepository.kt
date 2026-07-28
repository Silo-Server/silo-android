package org.siloserver.silo.repository

import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OverlayConfigResponse
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.map

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
