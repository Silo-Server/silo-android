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
    /** False when the household manager blocked this profile from requests and request discovery. */
    @SerialName("requests_allowed") val requestsAllowed: Boolean = true,
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
    @SerialName("max_playback_quality") val maxPlaybackQuality: String? = null,
    /**
     * Null omits the member (the server then allows requests). Set it only when
     * [ProfilesResponse.requestsAllowedSupported]: older servers reject unknown members.
     */
    @SerialName("requests_allowed") val requestsAllowed: Boolean? = null,
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
    @SerialName("max_playback_quality") val maxPlaybackQuality: String? = null,
    /** Null leaves it unchanged. Set it only when [ProfilesResponse.requestsAllowedSupported]. */
    @SerialName("requests_allowed") val requestsAllowed: Boolean? = null,
)

@Serializable
data class ProfilesResponse(
    val profiles: List<Profile>,
    /**
     * The server stores a per-profile requests switch and accepts
     * `requests_allowed` on create and update. Older servers reject the member.
     */
    val requestsAllowedSupported: Boolean = false,
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

/**
 * The household's primary profile, the only one the server lets manage the
 * other profiles. A one-profile household is its own primary, as on Apple.
 */
fun List<Profile>.householdPrimary(): Profile? =
    firstOrNull { it.isPrimary } ?: singleOrNull()

/** Label and help text for the per-profile requests switch, matching the web profile editor. */
const val PROFILE_REQUESTS_ALLOWED_LABEL = "Allow requests"
const val PROFILE_REQUESTS_ALLOWED_HELP =
    "Lets this profile browse titles that are not in the library and request them. " +
        "The content rating limits those titles by their US rating only, and the " +
        "advisory-age limit does not apply to them."

/**
 * The requests switch after the Child Profile toggle changes to [isChild],
 * matching the web Kids preset. Request discovery browses titles outside the
 * library, where parental controls reach only partly, so turning Child Profile
 * on turns requests off unless the manager already set the switch by hand
 * ([touched]). Turning it off turns requests back on; callers reset [touched].
 */
fun requestsAllowedAfterChildToggle(isChild: Boolean, requestsAllowed: Boolean, touched: Boolean): Boolean = when {
    !isChild -> true
    touched -> requestsAllowed
    else -> false
}
