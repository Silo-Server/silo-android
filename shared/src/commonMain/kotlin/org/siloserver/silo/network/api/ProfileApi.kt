package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.profile.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.map
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.MaxPlaybackQuality
import org.siloserver.silo.network.apiv2.Patch
import org.siloserver.silo.network.apiv2.ProfileUpdate
import org.siloserver.silo.network.apiv2.ProfileV2
import org.siloserver.silo.network.apiv2.QualityPreference
import org.siloserver.silo.network.apiv2.SubtitleMode
import org.siloserver.silo.network.apiv2.safeApiV2Call

class ProfileApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {

    suspend fun listProfiles(): ApiResult<ProfilesResponse> = safeApiCall {
        client.get("/api/v1/profiles")
    }

    suspend fun createProfile(request: CreateProfileRequest): ApiResult<Profile> = safeApiCall {
        client.post("/api/v1/profiles") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    // Pilot v2 operation (updateProfile): PATCH v2 only, no v1 fallback and
    // no replay of a failed mutation against another API major.
    suspend fun updateProfile(
        id: String,
        request: UpdateProfileRequest
    ): ApiResult<Profile> = updateProfile(id, request.toProfileUpdate())

    suspend fun updateProfile(
        id: String,
        update: ProfileUpdate,
    ): ApiResult<Profile> = safeApiV2Call<ProfileV2>(apiV2Gate) {
        client.patch("/api/v2/profiles/$id") {
            contentType(ContentType.Application.Json)
            setBody(update.toJsonObject())
        }
    }.map { profile -> profile.toProfile() }

    suspend fun deleteProfile(id: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/profiles/$id")
    }

    suspend fun verifyPin(
        id: String,
        pin: String
    ): ApiResult<VerifyPinResponse> = safeApiCall {
        client.post("/api/v1/profiles/$id/verify-pin") {
            contentType(ContentType.Application.Json)
            setBody(VerifyPinRequest(pin))
        }
    }
}

/**
 * v1 request semantics on the v2 PATCH: a null member is omitted (unchanged);
 * an empty string on a clearable member is the v1 clearing form and becomes a
 * literal `null`; everything else is sent as-is.
 */
internal fun UpdateProfileRequest.toProfileUpdate(): ProfileUpdate = ProfileUpdate(
    name = Patch.ofOptional(name),
    avatar = clearable(avatar),
    pin = clearable(pin),
    isChild = Patch.ofOptional(isChild),
    maxContentRating = clearable(maxContentRating),
    qualityPreference = Patch.ofOptional(qualityPreference?.let(::QualityPreference)),
    language = clearable(language),
    subtitleLanguage = clearable(subtitleLanguage),
    preferredMetadataLanguage = clearable(preferredMetadataLanguage),
    subtitleMode = Patch.ofOptional(subtitleMode?.let(::SubtitleMode)),
    showForcedSubtitles = Patch.ofOptional(showForcedSubtitles),
    autoSkipIntro = Patch.ofOptional(autoSkipIntro),
    autoSkipCredits = Patch.ofOptional(autoSkipCredits),
    libraryRestrictionsEnabled = Patch.ofOptional(libraryRestrictionsEnabled),
    allowedLibraryIds = Patch.ofOptional(allowedLibraryIds?.map { it.toString() }),
    maxPlaybackQuality = when (maxPlaybackQuality) {
        null -> Patch.Omit
        "" -> Patch.Clear
        else -> Patch.Set(MaxPlaybackQuality(maxPlaybackQuality))
    },
)

private fun clearable(value: String?): Patch<String> = when (value) {
    null -> Patch.Omit
    "" -> Patch.Clear
    else -> Patch.Set(value)
}

/** Adapts the v2 profile to the v1-shaped [Profile] the repositories and screens consume. */
internal fun ProfileV2.toProfile(): Profile = Profile(
    id = id,
    name = name,
    avatar = avatar.ifEmpty { null },
    avatarUrl = avatarUrl,
    avatarSource = avatarSource.wire,
    isPrimary = isPrimary,
    hasPin = hasPin,
    isChild = isChild,
    maxContentRating = maxContentRating.ifEmpty { null },
    qualityPreference = qualityPreference.wire,
    language = language.ifEmpty { null },
    subtitleLanguage = subtitleLanguage.ifEmpty { null },
    preferredMetadataLanguage = preferredMetadataLanguage.ifEmpty { null },
    subtitleMode = subtitleMode.wire,
    showForcedSubtitles = showForcedSubtitles,
    autoSkipIntro = autoSkipIntro,
    autoSkipCredits = autoSkipCredits,
    libraryRestrictionsEnabled = libraryRestrictionsEnabled,
    allowedLibraryIds = allowedLibraryIds.mapNotNull { it.toIntOrNull() },
    maxPlaybackQuality = maxPlaybackQuality.wire,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
