package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.profile.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.map
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.apiv2.MaxPlaybackQuality
import org.siloserver.silo.network.apiv2.Patch
import org.siloserver.silo.network.apiv2.ProfileUpdate
import org.siloserver.silo.network.apiv2.ProfileV2
import org.siloserver.silo.network.apiv2.QualityPreference
import org.siloserver.silo.network.apiv2.SubtitleMode
import org.siloserver.silo.network.apiv2.safeApiV2Call

class ProfileApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate,
    private val tokens: TokenManager? = null,
) {

    // Profile headers remain optional: the picker and first-profile bootstrap
    // run before selection. When present, retain the captured manager PIN proof.
    private suspend inline fun <reified T> exchange(
        path: String, method: HttpMethod, status: HttpStatusCode,
        nonRetryable: Boolean = false,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> {
        val scope = tokens?.snapshotCurrentScope()
        if (tokens != null && scope == null) return identityChanged()
        return ownedV2Call<T, T>(apiV2Gate, tokens, scope, OwnerPolicy.IDENTITY, status, { owner ->
            client.request(path) {
                this.method = method
                owner?.let { authScope(it) }
                requireSiloAuth()
                if (nonRetryable) singleAttempt()
                configure()
            }
        }) { it }
    }

    suspend fun listProfiles(): ApiResult<ProfilesResponse> =
        exchange<ProfileCollectionV2>("/api/v2/profiles", HttpMethod.Get, HttpStatusCode.OK)
            .map { ProfilesResponse(it.items.map { profile -> profile.toProfile() }) }

    suspend fun createProfile(request: CreateProfileRequest): ApiResult<Profile> =
        exchange<ProfileV2>("/api/v2/profiles", HttpMethod.Post, HttpStatusCode.Created, nonRetryable = true) {
            contentType(ContentType.Application.Json)
            // Create does not accept null; library identifiers are v2 strings.
            val fields = SiloJson.encodeToJsonElement(CreateProfileRequest.serializer(), request).jsonObject
                .filterValues { it != JsonNull }.toMutableMap()
            request.allowedLibraryIds?.let { ids ->
                fields["allowed_library_ids"] = JsonArray(ids.map { JsonPrimitive(it.toString()) })
            }
            setBody(JsonObject(fields))
        }.map { it.toProfile() }

    // PATCH v2 only; a failed mutation is never replayed.
    suspend fun updateProfile(
        id: String,
        request: UpdateProfileRequest
    ): ApiResult<Profile> = updateProfile(id, request.toProfileUpdate())

    suspend fun updateProfile(
        id: String,
        update: ProfileUpdate,
    ): ApiResult<Profile> =
        exchange<ProfileV2>("/api/v2/profiles/${id.encodeURLPathPart()}", HttpMethod.Patch, HttpStatusCode.OK, nonRetryable = true) {
            contentType(ContentType.Application.Json)
            setBody(update.toJsonObject())
        }.map { profile -> profile.toProfile() }

    suspend fun deleteProfile(id: String): ApiResult<Unit> =
        exchange("/api/v2/profiles/${id.encodeURLPathPart()}", HttpMethod.Delete, HttpStatusCode.NoContent, nonRetryable = true)

    suspend fun verifyPin(id: String, pin: String): ApiResult<VerifyPinResponse> =
        exchange("/api/v2/profiles/${id.encodeURLPathPart()}/verify-pin", HttpMethod.Post, HttpStatusCode.OK) {
            contentType(ContentType.Application.Json)
            setBody(VerifyPinRequest(pin))
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

@Serializable
private data class ProfileCollectionV2(val items: List<ProfileV2>)
