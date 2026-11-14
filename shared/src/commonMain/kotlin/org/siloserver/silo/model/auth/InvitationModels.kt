package org.siloserver.silo.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /**
     * Not on the wire: the repository copies the server's `token_in_body`
     * capability here, so acceptance uses the same request form as the lookup.
     */
    @Transient val tokenInBody: Boolean = false,
)

@Serializable
data class AcceptInvitationRequest(
    val password: String,
    /** Set only for the body form; the path form carries the token in the URL. */
    val token: String? = null,
)

@Serializable
data class InvitationCapabilities(
    val revision: String,
    val state: String,
    @SerialName("default_profile") val defaultProfile: Boolean,
    val profileless: Boolean,
    /**
     * The server takes the claim token in the request body. Older servers omit
     * it and only have the path forms, which put the token in the request line.
     */
    @SerialName("token_in_body") val tokenInBody: Boolean = false,
)

data class InvitationAcceptance(val username: String, val tokens: LoginResponse?)
data class InvitationClaimResult(val username: String, val signedIn: Boolean)
