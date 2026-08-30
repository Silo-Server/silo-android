package org.siloserver.silo.model.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Wire models for the canonical settings API (`/api/v1/settings/contract`
 * and the `/api/v1/settings/values` routes).
 *
 * These mirror the server's `settings_values.go` handler shapes exactly. The
 * older models in [SettingsModels.kt] speak the legacy string-only endpoints;
 * here values are typed JSON, scope is explicit, and unknown keys are refused
 * by the server rather than stored.
 */

/**
 * The six scopes an explicit value can live at, in the server's wire
 * spelling. Kept as an enum for request construction only — response fields
 * stay raw strings so a server that adds a scope cannot break deserialization.
 */
enum class SettingScope(val wire: String) {
    ACCOUNT("account"),
    PROFILE("profile"),
    PROFILE_CLIENT("profile_client"),
    PROFILE_DEVICE("profile_device"),
    PROFILE_LIBRARY("profile_library"),
    PROFILE_SERIES("profile_series"),
}

/**
 * The scope identity a write or delete addresses.
 *
 * Only the content ids travel with the request: `scope` plus `library_id` /
 * `series_id` go in the query string. The profile and device parts of the
 * identity come from the session headers (`X-Profile-Id`, `X-Silo-Device-Id`)
 * that the auth interceptor already attaches — the server reads them from
 * there deliberately, so one profile cannot write another's settings by
 * naming it in the query.
 *
 * The init block enforces the fields each scope requires — the same check the
 * server's identity validation makes — so an invalid identity fails at
 * construction instead of as a 400. The companion factories are the readable
 * way to build one.
 */
data class SettingScopeIdentity(
    val scope: SettingScope,
    /** Set only for [SettingScope.PROFILE_LIBRARY]. */
    val libraryId: Int? = null,
    /** Set only for [SettingScope.PROFILE_SERIES]. */
    val seriesId: String? = null,
) {
    init {
        require((scope == SettingScope.PROFILE_LIBRARY) == (libraryId != null)) {
            "library_id is required for profile_library and forbidden elsewhere"
        }
        libraryId?.let { require(it > 0) { "library_id must be positive" } }
        require((scope == SettingScope.PROFILE_SERIES) == (seriesId != null)) {
            "series_id is required for profile_series and forbidden elsewhere"
        }
        seriesId?.let { require(it.isNotBlank()) { "series_id must not be blank" } }
    }

    companion object {
        fun account(): SettingScopeIdentity = SettingScopeIdentity(SettingScope.ACCOUNT)

        fun profile(): SettingScopeIdentity = SettingScopeIdentity(SettingScope.PROFILE)

        /**
         * Like-client scope: the value roams among devices of the same client
         * family. Only `scope` travels in the query — the family half of the
         * identity comes from the `X-Silo-Client-Family` header the auth
         * interceptor attaches, never from a query parameter.
         */
        fun profileClient(): SettingScopeIdentity =
            SettingScopeIdentity(SettingScope.PROFILE_CLIENT)

        fun profileDevice(): SettingScopeIdentity =
            SettingScopeIdentity(SettingScope.PROFILE_DEVICE)

        fun profileLibrary(libraryId: Int): SettingScopeIdentity =
            SettingScopeIdentity(SettingScope.PROFILE_LIBRARY, libraryId = libraryId)

        fun profileSeries(seriesId: String): SettingScopeIdentity =
            SettingScopeIdentity(SettingScope.PROFILE_SERIES, seriesId = seriesId)
    }
}

/**
 * `GET /api/v1/settings/contract/capabilities` — what the connected server
 * supports, for feature detection rather than version sniffing. Compare
 * [revision] against the generated [SettingKeys.REVISION] to hide definitions
 * the server does not know yet.
 */
@Serializable
data class SettingsContractCapabilities(
    @SerialName("api_version") val apiVersion: Int = 0,
    val revision: Int = 0,
    @SerialName("contract_etag") val contractEtag: String = "",
    @SerialName("definition_count") val definitionCount: Int = 0,
    val scopes: List<String> = emptyList(),
    @SerialName("client_families") val clientFamilies: List<String> = emptyList(),
    @SerialName("supports_batched_effective") val supportsBatchedEffective: Boolean = false,
    @SerialName("supports_idempotent_writes") val supportsIdempotentWrites: Boolean = false,
)

/** Body for `PUT /api/v1/settings/values/{key}`: `{"value": …}`. */
@Serializable
data class SettingValueWriteRequest(
    val value: JsonElement,
)

/**
 * One explicit stored value: the receipt returned by a PUT, and the shape a
 * GET at one scope returns. An idempotent replay of a PUT returns the
 * recorded receipt, which omits [revision] and [updatedAt] — treat them as
 * informational, not as fields every response carries.
 */
@Serializable
data class StoredSettingValue(
    val key: String,
    val scope: String,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("library_id") val libraryId: Int? = null,
    @SerialName("series_id") val seriesId: String? = null,
    val value: JsonElement = JsonNull,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * One resolved value plus where it came from.
 *
 * [storedValue] and [constrained] are present only when policy narrowed the
 * answer: [value] is what applies, [storedValue] is what the user chose, so a
 * client can say "your choice is capped" instead of silently showing the cap.
 * The scope fields locate the row the value came from, so a reset can target
 * exactly that scope; they are absent for a contract default.
 */
@Serializable
data class EffectiveSettingValue(
    val key: String,
    val value: JsonElement = JsonNull,
    val source: String = SOURCE_DEFAULT,
    @SerialName("stored_value") val storedValue: JsonElement? = null,
    val constrained: Boolean = false,
    /** One of "ceiling", "floor", "allowlist", "locked" when [constrained]. */
    @SerialName("constraint_kind") val constraintKind: String? = null,
    /** Advisory values for an open picker; never a write allowlist. */
    @SerialName("suggested_values") val suggestedValues: List<String> = emptyList(),
    val scope: String? = null,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("library_id") val libraryId: Int? = null,
    @SerialName("series_id") val seriesId: String? = null,
) {
    companion object {
        /** [source] when no stored value applied and the contract default won. */
        const val SOURCE_DEFAULT = "default"
    }
}

/**
 * `GET /api/v1/settings/values/effective`. [revision] names the contract
 * revision the resolution was computed at, so definitions, scopes and enum
 * members can be filtered against it.
 */
@Serializable
data class EffectiveSettingValuesResponse(
    val settings: List<EffectiveSettingValue> = emptyList(),
    val revision: Int = 0,
)
