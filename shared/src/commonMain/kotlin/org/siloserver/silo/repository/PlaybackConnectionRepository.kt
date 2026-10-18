package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.BUFFERING_QUALITY_V1_FEATURE
import org.siloserver.silo.model.playback.CONNECTION_SPEED_V1_FEATURE
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.PlaybackConnectionV2
import org.siloserver.silo.network.apiv2.PlaybackV2Api

/** What Settings shows about this device's connection ([PlaybackConnectionRepository.settingsState]). */
data class PlaybackConnectionSettings(
    /** Null when the server does not advertise `buffering_quality_v1` or the read failed. */
    val connection: PlaybackConnectionV2?,
    /**
     * The server advertises `connection_speed_v1` with a `bandwidth_probe_url`,
     * so Settings shows the Connection group.
     */
    val connectionSpeed: Boolean,
)

/**
 * This device's connection state and learned bandwidth limit, for Settings.
 * The server repo's `docs/design/2026-10-17-buffering-quality.md`,
 * *Reporting and display*.
 *
 * `GET /api/v2/playback/connection` and its reset exist only on a server whose
 * `/api/v2/playback/capabilities` `features` carry
 * [BUFFERING_QUALITY_V1_FEATURE], so [connection] checks that first and treats
 * every other server as having no state to show. Each call reads the active
 * profile's scope once and uses it for every request it makes.
 */
class PlaybackConnectionRepository(
    private val api: PlaybackV2Api,
    private val tokens: TokenManager,
) {
    /**
     * Whether the server advertises [BUFFERING_QUALITY_V1_FEATURE] to the
     * active profile. False when signed out or the capability read fails.
     */
    suspend fun isBufferingQualityAdvertised(): Boolean {
        val scope = tokens.snapshotCurrentScope() ?: return false
        return advertises(scope)
    }

    /**
     * The connection state, or null when the server does not advertise the
     * feature, nobody is signed in, or a read fails.
     */
    suspend fun connection(): PlaybackConnectionV2? {
        val scope = tokens.snapshotCurrentScope() ?: return null
        if (!advertises(scope)) return null
        return (api.connection(scope) as? ApiResult.Success)?.data
    }

    /**
     * One read for Settings: the connection state (when the server advertises
     * [BUFFERING_QUALITY_V1_FEATURE]) and whether the server measures
     * connection speed. Null when nobody is signed in or the capability read
     * fails.
     */
    suspend fun settingsState(): PlaybackConnectionSettings? {
        val scope = tokens.snapshotCurrentScope() ?: return null
        val capabilities = (api.capabilities(scope) as? ApiResult.Success)?.data ?: return null
        val connection = if (BUFFERING_QUALITY_V1_FEATURE in capabilities.features) {
            (api.connection(scope) as? ApiResult.Success)?.data
        } else {
            null
        }
        return PlaybackConnectionSettings(
            connection = connection,
            connectionSpeed = CONNECTION_SPEED_V1_FEATURE in capabilities.features &&
                !capabilities.bandwidthProbeUrl.isNullOrBlank(),
        )
    }

    /** Deletes this device's learned limit on its current connection (HTTP 204). */
    suspend fun resetLearnedLimit(): ApiResult<Unit> {
        val scope = tokens.snapshotCurrentScope()
            ?: return ApiResult.Error(0, "identity_unavailable", "Resetting the limit needs an authenticated profile.")
        return api.resetLearnedLimit(scope)
    }

    private suspend fun advertises(scope: AuthScopeSnapshot): Boolean =
        (api.capabilities(scope) as? ApiResult.Success)?.data?.features
            ?.contains(BUFFERING_QUALITY_V1_FEATURE) == true
}
