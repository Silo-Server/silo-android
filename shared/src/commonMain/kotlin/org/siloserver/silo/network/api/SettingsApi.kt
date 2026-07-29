package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.EffectiveSettingsResponse
import org.siloserver.silo.model.settings.EffectiveSubtitleAppearance
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SettingEntry
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingValueWriteRequest
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.SettingsListResponse
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.UpdateSettingRequest
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Admin-configured card-overlay baseline. `enabled` is the global
 * kill-switch (admins can disable overlays for everyone); `defaults` is
 * the serialized [CardOverlayPrefs] JSON used when a user has no override.
 * Mirrors iOS `OverlayConfigResponse` and the server's
 * `GET /api/v1/settings/overlay-config` shape.
 */
@Serializable
data class OverlayConfigResponse(
    val enabled: Boolean = true,
    val defaults: String? = null,
)

/**
 * Result of probing the canonical settings contract.
 *
 * A server that predates the canonical settings API has no
 * `/api/v1/settings/contract` routes at all, so the probe 404s. That is a
 * distinct, actionable state — the UI must say "this server needs an
 * upgrade" rather than render an empty settings screen — so it is a typed
 * case here instead of dissolving into the generic error path.
 */
sealed class SettingsCapabilitiesResult {
    /** The server speaks the canonical settings API. */
    data class Available(
        val capabilities: SettingsContractCapabilities,
    ) : SettingsCapabilitiesResult()

    /**
     * The connected server does not serve `/api/v1/settings/contract`
     * (HTTP 404): it is too old for the canonical settings API.
     */
    data object ServerUpgradeRequired : SettingsCapabilitiesResult()

    /** Any other HTTP failure, with the server's error body when present. */
    data class Error(
        val code: Int,
        val error: String,
        val message: String,
    ) : SettingsCapabilitiesResult()

    /** The request never reached the server. */
    data class NetworkError(val exception: Throwable) : SettingsCapabilitiesResult()
}

/**
 * A fresh idempotency key for one settings write.
 *
 * Generate one per logical write and hold it across retries: the server
 * replays the recorded receipt for a repeated id with identical content, and
 * rejects the id with 409 `mutation_id_conflict` when it was used for
 * different content. Generating a new id per retry would defeat both.
 */
@OptIn(ExperimentalUuidApi::class)
fun newSettingMutationId(): String = Uuid.random().toString()

open class SettingsApi(private val client: HttpClient) {

    open suspend fun getSettings(): ApiResult<SettingsListResponse> = safeApiCall {
        client.get("/api/v1/settings")
    }

    open suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> = safeApiCall {
        client.get("/api/v1/settings/overlay-config")
    }

    open suspend fun getSetting(key: String): ApiResult<SettingEntry> = safeApiCall {
        client.get("/api/v1/settings/$key")
    }

    open suspend fun setSetting(key: String, value: String): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/settings/$key") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSettingRequest(value))
        }
    }

    open suspend fun deleteSetting(key: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/settings/$key")
    }

    open suspend fun getDeviceSetting(key: String): ApiResult<SettingEntry> = safeApiCall {
        client.get("/api/v1/settings/device/$key")
    }

    open suspend fun setDeviceSetting(
        key: String,
        value: String,
        profileId: String? = null,
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/settings/device/$key") {
            if (!profileId.isNullOrBlank()) {
                header("X-Profile-Id", profileId)
            }
            contentType(ContentType.Application.Json)
            setBody(UpdateSettingRequest(value))
        }
    }

    open suspend fun deleteDeviceSetting(key: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/settings/device/$key")
    }

    open suspend fun getEffectiveSettings(keys: List<String>): ApiResult<EffectiveSettingsResponse> = safeApiCall {
        client.get("/api/v1/settings/effective") {
            url {
                parameters.append("keys", keys.joinToString(","))
            }
        }
    }

    open suspend fun getEffectiveSubtitleAppearance(): ApiResult<EffectiveSubtitleAppearance> = safeApiCall {
        client.get("/api/v1/settings/subtitle_appearance/effective")
    }

    open suspend fun setDeviceSubtitleAppearanceOverride(
        appearance: SubtitleAppearance,
        profileId: String? = null,
    ): ApiResult<Unit> = setDeviceSetting(
        key = PlaybackSettingsKeys.SubtitleAppearance,
        value = appearance.toJsonString(),
        profileId = profileId,
    )

    open suspend fun deleteDeviceSubtitleAppearanceOverride(): ApiResult<Unit> =
        deleteDeviceSetting(PlaybackSettingsKeys.SubtitleAppearance)

    // ------------------------------------------------------------------
    // Canonical settings API (/settings/contract, /settings/values/*).
    // Typed JSON values with explicit scopes; the endpoints above speak the
    // legacy string-only registry and remain for not-yet-migrated call sites.
    // ------------------------------------------------------------------

    /**
     * What the connected server's settings contract supports, or
     * [SettingsCapabilitiesResult.ServerUpgradeRequired] when the server
     * predates the canonical settings API entirely.
     *
     * Not every 404 on this path means an old server. The route sits behind
     * the viewer-access middleware, which answers a JSON
     * `{"error":"not_found"}` when the `X-Profile-Id` this client sends names
     * a profile the household deleted elsewhere. Telling the user their
     * server is too old — and to go ask its admin — when the real fix is
     * re-selecting a profile is worse than saying nothing, so the two are
     * separated on the wire: a server with no `/settings/contract` routes
     * falls through to the router's plain-text `404 page not found`, which
     * leaves the parsed error code empty.
     */
    open suspend fun getContractCapabilities(): SettingsCapabilitiesResult =
        when (val result = safeApiCall<SettingsContractCapabilities> {
            client.get("/api/v1/settings/contract/capabilities")
        }) {
            is ApiResult.Success -> SettingsCapabilitiesResult.Available(result.data)
            is ApiResult.Error ->
                if (result.code == HttpStatusCode.NotFound.value && result.error.isEmpty()) {
                    SettingsCapabilitiesResult.ServerUpgradeRequired
                } else {
                    SettingsCapabilitiesResult.Error(result.code, result.error, result.message)
                }
            is ApiResult.NetworkError -> SettingsCapabilitiesResult.NetworkError(result.exception)
        }

    /**
     * Resolve settings the way the server does, including the scope each
     * answer came from.
     *
     * Batched on purpose: one request serves a whole settings screen or a
     * season view spanning several series. Passing no [keys] resolves every
     * remote definition in the server's contract. [libraryIds] and
     * [seriesIds] widen the resolution to those content scopes; the profile
     * and device parts of the context come from the session headers the auth
     * interceptor already attaches.
     */
    open suspend fun getEffectiveValues(
        keys: List<String> = emptyList(),
        libraryIds: List<Int> = emptyList(),
        seriesIds: List<String> = emptyList(),
    ): ApiResult<EffectiveSettingValuesResponse> = safeApiCall {
        client.get("/api/v1/settings/values/effective") {
            url {
                if (keys.isNotEmpty()) parameters.append("keys", keys.joinToString(","))
                if (libraryIds.isNotEmpty()) {
                    parameters.append("library_ids", libraryIds.joinToString(","))
                }
                if (seriesIds.isNotEmpty()) {
                    parameters.append("series_ids", seriesIds.joinToString(","))
                }
            }
        }
    }

    /**
     * Write one typed value at one scope.
     *
     * [mutationId] (sent as `X-Silo-Mutation-Id`) makes retries safe: create
     * it once per logical write with [newSettingMutationId] and reuse it for
     * every retry of that write. A retry the server already applied replays
     * the recorded receipt instead of re-applying; reusing an id for
     * *different* content fails with 409 `mutation_id_conflict`.
     *
     * A value that exceeds a policy restriction is stored, not rejected — the
     * restriction caps it at resolution time — so a 200 receipt does not mean
     * the value is what playback will use. Resolve via [getEffectiveValues]
     * for that.
     */
    open suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        mutationId: String,
        profileId: String? = null,
    ): ApiResult<StoredSettingValue> = safeApiCall {
        client.put("/api/v1/settings/values/$key") {
            applyScopeIdentity(scope, profileId)
            if (mutationId.isNotBlank()) {
                header("X-Silo-Mutation-Id", mutationId)
            }
            contentType(ContentType.Application.Json)
            setBody(SettingValueWriteRequest(value))
        }
    }

    /**
     * Clear the explicit value at one scope, so the setting inherits again.
     *
     * 204 on success; 404 `not_found` when nothing was set there, which a
     * retrying caller should treat as already done.
     */
    open suspend fun deleteValue(
        key: String,
        scope: SettingScopeIdentity,
        profileId: String? = null,
    ): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/settings/values/$key") {
            applyScopeIdentity(scope, profileId)
        }
    }

    /**
     * Applies the parts of a scope identity that travel with the request.
     *
     * Scope and the content ids go in the query. The profile normally rides
     * the session's `X-Profile-Id` header; an explicit [profileId] overrides
     * it (the interceptor only fills the header when absent), matching how
     * [setDeviceSetting] lets a parent act for a child profile. The device id
     * is never set here — the interceptor always attaches
     * `X-Silo-Device-Id`, and appending it again would send two values.
     */
    private fun HttpRequestBuilder.applyScopeIdentity(
        scope: SettingScopeIdentity,
        profileId: String?,
    ) {
        url {
            parameters.append("scope", scope.scope.wire)
            scope.libraryId?.let { parameters.append("library_id", it.toString()) }
            scope.seriesId?.let { parameters.append("series_id", it) }
        }
        if (!profileId.isNullOrBlank() && scope.scope != SettingScope.ACCOUNT) {
            header("X-Profile-Id", profileId)
        }
    }
}
