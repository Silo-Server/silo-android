package org.siloserver.silo.network.api

import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.auth.*
import org.siloserver.silo.network.apiv2.AccountRole
import org.siloserver.silo.network.apiv2.DetailStringIdSerializer

@Serializable
internal data class AuthUserV2(
    @Serializable(with = DetailStringIdSerializer::class) val id: String,
    val username: String,
    val email: String,
    val role: AccountRole,
    @SerialName("download_allowed") val downloadAllowed: Boolean = false,
    val impersonation: AuthImpersonationV2? = null,
) {
    fun domain() = User(id, username, email, role.wire, downloadAllowed, impersonation?.domain())
}

@Serializable
internal data class AuthImpersonationV2(
    val active: Boolean,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("impersonator_user_id") val userId: String,
    @SerialName("impersonator_username") val username: String,
) {
    fun domain() = ImpersonationInfo(active, userId, username)
}

@Serializable
internal data class TokenPairV2(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: AuthUserV2,
) {
    fun domain() = LoginResponse(accessToken, refreshToken, expiresIn, user.domain())
}

@Serializable
internal data class DevicePollV2(
    val status: String,
    @SerialName("poll_after") val pollAfter: Int,
    @Serializable(with = DetailStringIdSerializer::class)
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_token") val profileToken: String,
    val temporary: Boolean,
    val tokens: TokenPairV2? = null,
    @SerialName("session_expires_at") val sessionExpiresAt: String? = null,
) {
    fun domain() = DeviceLoginPollResponse(status, pollAfter, tokens?.accessToken, tokens?.refreshToken,
        tokens?.expiresIn, tokens?.user?.domain(), profileId, profileToken, temporary, sessionExpiresAt)
}

@Serializable
internal data class DeviceCapabilityV2(
    val revision: String,
    val state: String,
    @SerialName("remote_playback_handoff") val handoff: Boolean,
    @SerialName("protocol_versions") val protocols: List<Int>,
) {
    fun domain() = DeviceLoginCapabilityResponse(state == "available" && handoff, protocols)
}

internal fun HttpResponse.requireAuthStatus(expected: Int): HttpResponse = also {
    check(!status.isSuccess() || status.value == expected) { "Unexpected authentication response status" }
}

@Serializable
internal data class InvitationAcceptanceV2(
    val status: String,
    @SerialName("login_status") val loginStatus: String,
    val username: String,
    val tokens: TokenPairV2? = null,
) {
    init {
        require(status == "accepted" && username.isNotBlank())
        require((loginStatus == "signed_in" && tokens != null) ||
            (loginStatus == "sign_in_required" && tokens == null))
    }
    fun domain() = InvitationAcceptance(username, tokens?.domain())
}
