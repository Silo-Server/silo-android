package org.siloserver.silo.network.apiv2

import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry

/**
 * Blocks every v2 operation while the active server is in the
 * [ServerContract.UPDATE_REQUIRED] state. The state comes from the registry
 * entry (set by [ApiV2Probe] on connect); nothing here ever performs a
 * request.
 *
 * A call scoped to one saved server, which need not be the active one, is
 * gated on that server's own entry instead: [forServer].
 */
class ApiV2Gate(
    private val registry: ServerRegistry? = null,
    /** The saved server this gate checks; null for the active one. */
    private val serverId: String? = null,
) {

    /** The error to return instead of calling the server, or null when the call may proceed. */
    fun blocked(): ApiResult.Error? {
        val entry = if (serverId == null) {
            registry?.activeEntry?.value
        } else {
            registry?.entries?.value?.firstOrNull { it.id == serverId }
        }
        return if (entry?.contract == ServerContract.UPDATE_REQUIRED) {
            ApiResult.Error(
                code = 0, // no HTTP exchange happened; distinguishes the gate from any server status
                error = UPDATE_REQUIRED_ERROR,
                message = ServerContract.UPDATE_REQUIRED_MESSAGE,
            )
        } else {
            null
        }
    }

    /**
     * The gate for a call scoped to [serverId]'s saved server: that entry's
     * verdict applies, not the active server's.
     */
    fun forServer(serverId: String): ApiV2Gate = ApiV2Gate(registry, serverId)

    companion object {
        const val UPDATE_REQUIRED_ERROR = "update_server"

        /** For construction sites without a registry (commonMain tests, single-server hosts). */
        val Unrestricted = ApiV2Gate(null)
    }
}
