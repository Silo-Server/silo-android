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
    // [actingAs] replaces only this request's profile headers; the device's
    // selected profile is untouched.
    private suspend inline fun <reified T> exchange(
        path: String, method: HttpMethod, status: HttpStatusCode,
        nonRetryable: Boolean = false,
        actingAs: HouseholdManager? = null,
        withoutProfile: Boolean = false,
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> {
        val scope = tokens?.snapshotCurrentScope()
        if (tokens != null && scope == null) return identityChanged()
        // A manager proof belongs to the account it was verified under; never
        // present it on another account's request.
        if (actingAs?.scope != null && !actingAs.scope.isSameAccountAs(scope)) return identityChanged()
        // The pinned path rewrites profile headers from the scope, so the acting
        // profile goes into the pinned scope rather than onto the request.
        val pinned = when {
            scope == null -> null
            actingAs != null -> scope.copy(profileId = actingAs.profileId, profileToken = actingAs.profileToken)
            withoutProfile -> scope.copy(profileId = null, profileToken = null)
            else -> scope
        }
        return ownedV2Call<T, T>(apiV2Gate, tokens, pinned, OwnerPolicy.IDENTITY, status, { owner ->
            client.request(path) {
                this.method = method
                owner?.let { authScope(it) }
                if (owner == null && actingAs != null) {
                    headers.remove(PROFILE_ID_HEADER)
                    headers.remove(PROFILE_TOKEN_HEADER)
                    header(PROFILE_ID_HEADER, actingAs.profileId)
                    actingAs.profileToken?.let { header(PROFILE_TOKEN_HEADER, it) }
                }
                requireSiloAuth()
                if (nonRetryable) singleAttempt()
                configure()
            }
        }) { it }
    }

    suspend fun listProfiles(): ApiResult<ProfilesResponse> =
        exchange<ProfileCollectionV2>("/api/v2/profiles", HttpMethod.Get, HttpStatusCode.OK)
            .map { ProfilesResponse(it.items.map { profile -> profile.toProfile() }) }

    suspend fun createProfile(
        request: CreateProfileRequest,
        actingAs: HouseholdManager? = null,
    ): ApiResult<Profile> =
        exchange<ProfileV2>("/api/v2/profiles", HttpMethod.Post, HttpStatusCode.Created, nonRetryable = true, actingAs = actingAs) {
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
        request: UpdateProfileRequest,
        actingAs: HouseholdManager? = null,
    ): ApiResult<Profile> = updateProfile(id, request.toProfileUpdate(), actingAs)

    suspend fun updateProfile(
        id: String,
        update: ProfileUpdate,
        actingAs: HouseholdManager? = null,
    ): ApiResult<Profile> =
        exchange<ProfileV2>(
            "/api/v2/profiles/${id.encodeURLPathPart()}", HttpMethod.Patch, HttpStatusCode.OK,
            nonRetryable = true, actingAs = actingAs,
        ) {
            contentType(ContentType.Application.Json)
            setBody(update.toJsonObject())
        }.map { profile -> profile.toProfile() }

    suspend fun deleteProfile(id: String, actingAs: HouseholdManager? = null): ApiResult<Unit> =
        exchange(
            "/api/v2/profiles/${id.encodeURLPathPart()}", HttpMethod.Delete, HttpStatusCode.NoContent,
            nonRetryable = true, actingAs = actingAs,
        )

    /**
     * Sent with no profile headers, on the account only. Declaring the device's
     * selected profile would make the server check that profile's stored token
     * first, and a stale one (its PIN or the account's access policy changed
     * since) turns every correct PIN into 403 `profile_verification_required`.
     */
    suspend fun verifyPin(id: String, pin: String): ApiResult<VerifyPinResponse> =
        exchange(
            "/api/v2/profiles/${id.encodeURLPathPart()}/verify-pin", HttpMethod.Post, HttpStatusCode.OK,
            withoutProfile = true,
        ) {
            contentType(ContentType.Application.Json)
            setBody(VerifyPinRequest(pin))
        }

}

/**
 * The profile household management acts as: the account's primary profile,
 * plus the token `verify-pin` issued for it when it has a PIN. The server
 * lets only the primary profile create, edit, or delete household profiles.
 *
 * [scope] is the identity the proof was obtained under. A call made after the
 * account or server changed fails with `identity_changed` instead of carrying
 * this account's profile proof onto another account's request.
 */
class HouseholdManager(
    val profileId: String,
    val profileToken: String?,
    val scope: AuthScopeSnapshot?,
) {
    override fun toString(): String = "HouseholdManager(profileId=<redacted>, profileToken=<redacted>, scope=<redacted>)"
}

/**
 * Same signed-in account on the same server as [current], whichever profile
 * the device has selected. The picker clears the selection when the selected
 * profile is deleted, and that profile switch must not void the manager's
 * proof; a server switch, sign-in, sign-out, or remote-playback overlay does.
 */
internal fun AuthScopeSnapshot.isSameAccountAs(current: AuthScopeSnapshot?): Boolean {
    if (current == null || current.serverId != serverId) return false
    if (current.credentialGenerationId != credentialGenerationId) return false
    // Only a saved account's stamped epoch tells two sign-ins apart. Without
    // one (an overlay, or an unstamped scope), keep the full identity check.
    if (credentialGenerationId != null || credentialEpoch == 0L) return isSameIdentityAs(current)
    return current.credentialEpoch == credentialEpoch
}

private const val PROFILE_ID_HEADER = "X-Profile-Id"
private const val PROFILE_TOKEN_HEADER = "X-Profile-Token"

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
