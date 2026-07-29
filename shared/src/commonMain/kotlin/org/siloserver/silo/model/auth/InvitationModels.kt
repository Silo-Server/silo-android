package org.siloserver.silo.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emailed-invitation claim flow. The invitee's email address is their
 * username; the claim screen asks for a password and nothing else.
 */
@Serializable
data class InvitationLookupResponse(
    val email: String,
    @SerialName("inviter_name") val inviterName: String? = null,
    @SerialName("server_name") val serverName: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class AcceptInvitationRequest(
    val password: String,
)
