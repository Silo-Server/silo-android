package org.siloserver.silo.model.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: String? = null,
    /**
     * Fetchable URL for [avatar], supplied by the server.
     *
     * For an `upload:` ref this is a **presigned** object-store URL that the
     * client could never construct itself (it carries an AWS SigV4 signature)
     * and that expires — the server currently signs for 900 seconds. It is
     * therefore authoritative but perishable: fetch it, cache the *bytes* under
     * a key derived from the stable [avatar] ref, and never persist the signed
     * URL as if it were durable. See `ProfileAvatarSupport` on the Android side.
     */
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** How [avatar] was produced — e.g. `upload`, `preset`, `emoji`, `initials`. */
    @SerialName("avatar_source") val avatarSource: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("has_pin") val hasPin: Boolean = false,
    @SerialName("is_child") val isChild: Boolean = false,
    @SerialName("max_content_rating") val maxContentRating: String? = null,
    @SerialName("quality_preference") val qualityPreference: String? = null,
    val language: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("preferred_metadata_language") val preferredMetadataLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
    @SerialName("auto_skip_intro") val autoSkipIntro: Boolean = false,
    @SerialName("auto_skip_credits") val autoSkipCredits: Boolean = false,
    @SerialName("library_restrictions_enabled") val libraryRestrictionsEnabled: Boolean = false,
    @SerialName("allowed_library_ids") val allowedLibraryIds: List<Int>? = null,
    @SerialName("max_playback_quality") val maxPlaybackQuality: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * True when a profile other than [excludeId] already uses [name], comparing
 * trimmed forms case-insensitively ("Laura" ≡ " laura "). Mirrors the server's
 * per-account `name_conflict` rule so forms can fail fast before the network
 * round-trip (and against servers that predate the rule).
 */
fun List<Profile>.hasProfileNamed(name: String, excludeId: String? = null): Boolean {
    val trimmed = name.trim()
    return any { it.id != excludeId && it.name.trim().equals(trimmed, ignoreCase = true) }
}

@Serializable
data class CreateProfileRequest(
    val name: String,
    val avatar: String? = null,
    val pin: String? = null,
    @SerialName("is_child") val isChild: Boolean? = null,
    @SerialName("max_content_rating") val maxContentRating: String? = null,
    val language: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("preferred_metadata_language") val preferredMetadataLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
    @SerialName("auto_skip_intro") val autoSkipIntro: Boolean? = null,
    @SerialName("auto_skip_credits") val autoSkipCredits: Boolean? = null,
    @SerialName("library_restrictions_enabled") val libraryRestrictionsEnabled: Boolean? = null,
    @SerialName("allowed_library_ids") val allowedLibraryIds: List<Int>? = null,
    @SerialName("max_playback_quality") val maxPlaybackQuality: String? = null
)

@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val avatar: String? = null,
    val pin: String? = null,
    @SerialName("is_child") val isChild: Boolean? = null,
    @SerialName("max_content_rating") val maxContentRating: String? = null,
    @SerialName("quality_preference") val qualityPreference: String? = null,
    val language: String? = null,
    @SerialName("subtitle_language") val subtitleLanguage: String? = null,
    @SerialName("preferred_metadata_language") val preferredMetadataLanguage: String? = null,
    @SerialName("subtitle_mode") val subtitleMode: String? = null,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean? = null,
    @SerialName("auto_skip_intro") val autoSkipIntro: Boolean? = null,
    @SerialName("auto_skip_credits") val autoSkipCredits: Boolean? = null,
    @SerialName("library_restrictions_enabled") val libraryRestrictionsEnabled: Boolean? = null,
    @SerialName("allowed_library_ids") val allowedLibraryIds: List<Int>? = null,
    @SerialName("max_playback_quality") val maxPlaybackQuality: String? = null
)

@Serializable
data class ProfilesResponse(
    val profiles: List<Profile>
)

@Serializable
data class VerifyPinRequest(
    val pin: String
)

@Serializable
data class VerifyPinResponse(
    val valid: Boolean,
    @SerialName("profile_token") val profileToken: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

/**
 * The profile token to commit for a successful PIN verification, or null if
 * this response does not authorize entry.
 *
 * Fail closed on shape, not just on [VerifyPinResponse.valid]: the token is
 * the artifact that proves the PIN was entered, and the server rejects
 * management calls that cannot present one. Treating a bare `valid=true` with
 * no token as success let the client enter a protected profile holding nothing
 * to prove it — the failure then surfaced much later, as a confusing 403 on an
 * unrelated action.
 *
 * Expiry is left to the server, which validates the token on every use; the
 * client does not parse [VerifyPinResponse.expiresAt].
 */
fun VerifyPinResponse.authorizedProfileToken(): String? =
    profileToken?.takeIf { valid && it.isNotBlank() }
