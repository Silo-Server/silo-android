package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingEntry(
    val key: String,
    val value: String,
)

@Serializable
data class UpdateSettingRequest(
    val value: String,
)

@Serializable
data class EffectiveSetting(
    val key: String,
    @SerialName("user_value") val userValue: String? = null,
    @SerialName("device_value") val deviceValue: String? = null,
    @SerialName("effective_value") val effectiveValue: String,
    val source: String,
    @SerialName("has_device_override") val hasDeviceOverride: Boolean = false,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class EffectiveSettingsResponse(
    val settings: List<EffectiveSetting>,
)

@Serializable
data class EffectiveSubtitleAppearance(
    val key: String,
    @SerialName("global_value") val globalValue: String,
    @SerialName("device_value") val deviceValue: String? = null,
    @SerialName("effective_value") val effectiveValue: String,
    @SerialName("has_device_override") val hasDeviceOverride: Boolean = false,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
