package org.siloserver.silo.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val provider: String? = null
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: User
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long
)

@Serializable
data class User(
    val id: Int,
    val username: String,
    val email: String,
    val role: String,
    @SerialName("download_allowed") val downloadAllowed: Boolean = false,
    val impersonation: ImpersonationInfo? = null
)

@Serializable
data class ImpersonationInfo(
    val active: Boolean,
    @SerialName("impersonator_user_id") val impersonatorUserId: Int,
    @SerialName("impersonator_username") val impersonatorUsername: String
)

@Serializable
data class SetupRequest(
    val username: String,
    val email: String,
    val password: String
)

@Serializable
data class SetupStatusResponse(
    @SerialName("needs_setup") val needsSetup: Boolean
)

@Serializable
data class SignupRequest(
    val username: String,
    val email: String,
    val password: String,
    @SerialName("invite_code") val inviteCode: String
)

@Serializable
data class SignupStatusResponse(
    val enabled: Boolean
)
