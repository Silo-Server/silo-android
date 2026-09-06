package org.siloserver.silo.model.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushDeviceRegisterRequest(
    val platform: String,
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("push_mode") val pushMode: String = "private_push",
) { override fun toString() = "PushDeviceRegisterRequest(<redacted>)" }

@Serializable
data class PushDeviceRegisterResponse(
    val id: String,
    @SerialName("push_mode") val pushMode: String,
    val generation: String,
    @SerialName("server_device_id") val serverDeviceId: String,
)
