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
    @SerialName("show_tour") val showTour: Boolean,
    @SerialName("acceptance_available") val acceptanceAvailable: Boolean,
)

@Serializable
data class AcceptInvitationRequest(
    val password: String,
)

@Serializable
data class InvitationCapabilities(
    val revision: String,
    val state: String,
    @SerialName("default_profile") val defaultProfile: Boolean,
    val profileless: Boolean,
)

data class InvitationAcceptance(val username: String, val tokens: LoginResponse?)
data class InvitationClaimResult(val username: String, val signedIn: Boolean)
