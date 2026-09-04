package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Handwritten lenient models for the native API v2 pilot. They are decoded
 * with the production [org.siloserver.silo.network.SiloJson] instance and are
 * deliberately separate from the v1 data classes: v2 ids are opaque strings,
 * instants are RFC 3339 text (see [parseApiV2Instant]), and enums are
 * string-backed value classes with an observable unknown case.
 */

/** GET /api/v2/system/info — the contract probe body. */
@Serializable
data class SystemInfo(
    @SerialName("server_version") val serverVersion: String,
    @SerialName("api_major") val apiMajor: Int,
    @SerialName("contract_digest") val contractDigest: String,
    val links: SystemInfoLinks,
)

@Serializable
data class SystemInfoLinks(
    val openapi: String,
    val capabilities: String,
)

/** RFC 9457 problem details; every v2 error body. */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String = "",
    val instance: String? = null,
    val errors: List<ProblemError> = emptyList(),
) {
    /** The stable code: the last path segment of [type], e.g. `validation_failed`. */
    val code: String get() = type.trimEnd('/').substringAfterLast('/')
}

@Serializable
data class ProblemError(
    val location: String,
    val code: String,
    val detail: String = "",
)

/** GET /api/v2/system/setup */
@Serializable
data class SetupStatus(
    @SerialName("needs_setup") val needsSetup: Boolean,
)

/** GET /api/v2/account/me */
@Serializable
data class Account(
    val id: String,
    val username: String,
    val email: String,
    val role: AccountRole,
    val permissions: List<String> = emptyList(),
    @SerialName("download_allowed") val downloadAllowed: Boolean = false,
    /** Present only inside an admin impersonation session. */
    val impersonation: Impersonation? = null,
)

@Serializable
data class Impersonation(
    val active: Boolean,
    @SerialName("impersonator_user_id") val impersonatorUserId: String,
    @SerialName("impersonator_username") val impersonatorUsername: String,
)

/** GET /api/v2/progress */
@Serializable
data class ProgressCollection(
    val items: List<ProgressEntryV2> = emptyList(),
    val page: PageInfo = PageInfo(hasMore = false),
)

@Serializable
data class ProgressEntryV2(
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val completed: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
) {
    val updatedAtEpochMillis: Long? get() = parseApiV2Instant(updatedAt)
}

@Serializable
data class PageInfo(
    @SerialName("has_more") val hasMore: Boolean,
    /** Absent (null) exactly when [hasMore] is false. */
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** PATCH /api/v2/profiles/{id} response; every string member is always emitted. */
@Serializable
data class ProfileV2(
    val id: String,
    val name: String,
    val avatar: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_source") val avatarSource: AvatarSource = AvatarSource("none"),
    @SerialName("has_pin") val hasPin: Boolean = false,
    @SerialName("is_child") val isChild: Boolean = false,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("max_content_rating") val maxContentRating: String = "",
    @SerialName("quality_preference") val qualityPreference: QualityPreference = QualityPreference("auto"),
    val language: String = "",
    @SerialName("preferred_metadata_language") val preferredMetadataLanguage: String = "",
    @SerialName("subtitle_language") val subtitleLanguage: String = "",
    @SerialName("subtitle_mode") val subtitleMode: SubtitleMode = SubtitleMode("auto"),
    @SerialName("auto_skip_intro") val autoSkipIntro: Boolean = false,
    @SerialName("auto_skip_credits") val autoSkipCredits: Boolean = false,
    @SerialName("auto_skip_recap") val autoSkipRecap: Boolean = false,
    @SerialName("auto_play_next_preview") val autoPlayNextPreview: Boolean = false,
    @SerialName("show_forced_subtitles") val showForcedSubtitles: Boolean = false,
    @SerialName("library_restrictions_enabled") val libraryRestrictionsEnabled: Boolean = false,
    @SerialName("allowed_library_ids") val allowedLibraryIds: List<String> = emptyList(),
    @SerialName("max_playback_quality") val maxPlaybackQuality: MaxPlaybackQuality = MaxPlaybackQuality("1080p"),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * One member of a PATCH body. [Omit] leaves the member unchanged on the
 * server (absent from the JSON), [Clear] sends a literal `null` (only valid
 * for the clearable members), [Set] sends the value.
 *
 * `SiloJson` has `explicitNulls = false`, so a nullable property can never
 * distinguish omitted from cleared; the PATCH body is therefore built as a
 * [JsonObject] by [ProfileUpdate.toJsonObject] rather than serialized from a
 * class.
 */
sealed class Patch<out T> {
    data object Omit : Patch<Nothing>()
    data object Clear : Patch<Nothing>()
    data class Set<T>(val value: T) : Patch<T>()

    companion object {
        /** v1-style convenience: null means "leave unchanged", never "clear". */
        fun <T : Any> ofOptional(value: T?): Patch<T> = if (value == null) Omit else Set(value)
    }
}

/** PATCH /api/v2/profiles/{id} request. Nullable-on-PATCH members accept [Patch.Clear]. */
data class ProfileUpdate(
    val name: Patch<String> = Patch.Omit,
    val avatar: Patch<String> = Patch.Omit,
    val pin: Patch<String> = Patch.Omit,
    val isChild: Patch<Boolean> = Patch.Omit,
    val maxContentRating: Patch<String> = Patch.Omit,
    val qualityPreference: Patch<QualityPreference> = Patch.Omit,
    val language: Patch<String> = Patch.Omit,
    val preferredMetadataLanguage: Patch<String> = Patch.Omit,
    val subtitleLanguage: Patch<String> = Patch.Omit,
    val subtitleMode: Patch<SubtitleMode> = Patch.Omit,
    val showForcedSubtitles: Patch<Boolean> = Patch.Omit,
    val autoSkipIntro: Patch<Boolean> = Patch.Omit,
    val autoSkipCredits: Patch<Boolean> = Patch.Omit,
    val autoSkipRecap: Patch<Boolean> = Patch.Omit,
    val autoPlayNextPreview: Patch<Boolean> = Patch.Omit,
    val libraryRestrictionsEnabled: Patch<Boolean> = Patch.Omit,
    val allowedLibraryIds: Patch<List<String>> = Patch.Omit,
    val maxPlaybackQuality: Patch<MaxPlaybackQuality> = Patch.Omit,
) {
    fun toJsonObject(): JsonObject {
        val members = LinkedHashMap<String, JsonElement>()
        fun <T> put(key: String, patch: Patch<T>, encode: (T) -> JsonElement) {
            when (patch) {
                Patch.Omit -> Unit
                Patch.Clear -> members[key] = JsonNull
                is Patch.Set -> members[key] = encode(patch.value)
            }
        }
        val str = { s: String -> JsonPrimitive(s) }
        val bool = { b: Boolean -> JsonPrimitive(b) }
        put("name", name, str)
        put("avatar", avatar, str)
        put("pin", pin, str)
        put("is_child", isChild, bool)
        put("max_content_rating", maxContentRating, str)
        put("quality_preference", qualityPreference) { JsonPrimitive(it.wire) }
        put("language", language, str)
        put("preferred_metadata_language", preferredMetadataLanguage, str)
        put("subtitle_language", subtitleLanguage, str)
        put("subtitle_mode", subtitleMode) { JsonPrimitive(it.wire) }
        put("show_forced_subtitles", showForcedSubtitles, bool)
        put("auto_skip_intro", autoSkipIntro, bool)
        put("auto_skip_credits", autoSkipCredits, bool)
        put("auto_skip_recap", autoSkipRecap, bool)
        put("auto_play_next_preview", autoPlayNextPreview, bool)
        put("library_restrictions_enabled", libraryRestrictionsEnabled, bool)
        put("allowed_library_ids", allowedLibraryIds) { ids -> JsonArray(ids.map(::JsonPrimitive)) }
        put("max_playback_quality", maxPlaybackQuality) { JsonPrimitive(it.wire) }
        return JsonObject(members)
    }
}
