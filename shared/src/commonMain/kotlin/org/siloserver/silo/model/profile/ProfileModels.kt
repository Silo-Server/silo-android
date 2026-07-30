package org.siloserver.silo.model.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: String? = null,
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
