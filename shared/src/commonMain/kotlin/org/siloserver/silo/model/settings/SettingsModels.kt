package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `PUT /api/v2/settings/device/subtitle-appearance`: the appearance as a JSON document. */
@Serializable
data class SubtitleAppearanceDeviceOverride(
    val value: String,
)

/** `GET /api/v2/settings/subtitle-appearance/effective`. */
@Serializable
data class EffectiveSubtitleAppearance(
    val key: String,
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("global_value") val globalValue: String,
    @SerialName("device_value") val deviceValue: String? = null,
    @SerialName("effective_value") val effectiveValue: String,
    @SerialName("has_device_override") val hasDeviceOverride: Boolean = false,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
