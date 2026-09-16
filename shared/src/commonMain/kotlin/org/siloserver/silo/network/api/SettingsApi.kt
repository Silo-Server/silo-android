package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.SettingsV2Api
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Admin-configured card-overlay baseline. `enabled` is the global
 * kill-switch (admins can disable overlays for everyone); `defaults` is
 * the serialized [CardOverlayPrefs] JSON used when a user has no override.
 * Mirrors iOS `OverlayConfigResponse` and the server's
 * `GET /api/v2/settings/overlay-config` shape.
 */
@Serializable
data class OverlayConfigResponse(
    val enabled: Boolean = true,
    val defaults: String? = null,
)

open class SettingsApi(
    private val v2: SettingsV2Api,
) {

    open suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> = v2.overlayConfig()

    /** What the connected server's settings contract supports. */
    open suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = v2.capabilities()

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
        authority: AuthScopeSnapshot? = null,
    ): ApiResult<EffectiveSettingValuesResponse> = v2.effectiveValues(keys, libraryIds, seriesIds, authority)

    /**
     * Write one typed value at one scope.
     *
     * Repeated desired-state writes may advance the revision again. There is
     * no stored mutation receipt or concurrency guard.
     * [authority] pins queued work to its original acting account/profile/PIN;
     * [profileId] is the separately authorized target profile query parameter.
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
        profileId: String? = null,
        authority: AuthScopeSnapshot? = null,
    ): ApiResult<StoredSettingValue> = v2.put(key, scope, value, profileId, authority)

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
        authority: AuthScopeSnapshot? = null,
    ): ApiResult<Unit> = v2.delete(key, scope, profileId, authority)
}
