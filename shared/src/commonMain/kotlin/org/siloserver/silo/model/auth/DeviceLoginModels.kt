package org.siloserver.silo.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceLoginStartRequest(
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("client_purpose") val clientPurpose: String? = null,
    val temporary: Boolean? = null,
)

/**
 * `deviceCode` is the TV-only secret used for polling; never display it.
 * `verificationUriComplete` is the URL encoded into the QR — scanning it
 * deep-links into the web app's activation page.
 */
@Serializable
data class DeviceLoginStartResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("match_code") val matchCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_at") val expiresAt: String,  // ISO-8601 string; UI parses lazily
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_platform") val devicePlatform: String,
    @SerialName("client_purpose") val clientPurpose: String? = null,
    val temporary: Boolean? = null,
)

@Serializable
data class DeviceLoginPollRequest(
    @SerialName("device_code") val deviceCode: String,
)

/**
 * Token fields are only populated on the first `approved` response — the
 * server marks the record consumed atomically, so the client must capture
 * them immediately on that single reply.
 *
 * `user` reuses the existing [User] type from [AuthModels.kt] (same wire
 * shape as `LoginResponse.user`).
 */
@Serializable
data class DeviceLoginPollResponse(
    val status: String,
    @SerialName("poll_after") val pollAfter: Int? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: User? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("profile_token") val profileToken: String? = null,
    val temporary: Boolean? = null,
    @SerialName("session_expires_at") val sessionExpiresAt: String? = null,
)

@Serializable
data class DeviceLoginLookupResponse(
    val status: String,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("match_code") val matchCode: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("device_platform") val devicePlatform: String? = null,
    @SerialName("ip_address_hint") val ipAddressHint: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("client_purpose") val clientPurpose: String? = null,
    val temporary: Boolean? = null,
)

@Serializable
data class DeviceLoginCapabilityResponse(
    @SerialName("remote_playback_handoff") val remotePlaybackHandoff: Boolean = false,
    @SerialName("protocol_versions") val protocolVersions: List<Int> = emptyList(),
)

@Serializable
data class DeviceLoginDecisionRequest(
    val token: String? = null,
    val code: String? = null,
)

@Serializable
data class DeviceLoginDecisionResponse(
    val status: String,
)

enum class DeviceLoginStatus {
    Pending, Approved, Denied, Expired, Consumed, Unknown;

    companion object {
        fun fromWire(raw: String): DeviceLoginStatus = when (raw) {
            "pending" -> Pending
            "approved" -> Approved
            "denied" -> Denied
            "expired" -> Expired
            "consumed" -> Consumed
            else -> Unknown
        }
    }
}
