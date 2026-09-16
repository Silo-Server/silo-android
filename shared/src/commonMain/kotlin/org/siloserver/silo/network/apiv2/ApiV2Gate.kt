package org.siloserver.silo.network.apiv2

import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry

/**
 * Blocks every v2 operation while the active server is in the
 * [ServerContract.UPDATE_REQUIRED] state. The state comes from the registry
 * entry (set by [ApiV2Probe] on connect); nothing here ever performs a
 * request.
 */
class ApiV2Gate(private val registry: ServerRegistry? = null) {

    /** The error to return instead of calling the server, or null when the call may proceed. */
    fun blocked(): ApiResult.Error? =
        if (registry?.activeEntry?.value?.contract == ServerContract.UPDATE_REQUIRED) {
            ApiResult.Error(
                code = 0, // no HTTP exchange happened; distinguishes the gate from any server status
                error = UPDATE_REQUIRED_ERROR,
                message = ServerContract.UPDATE_REQUIRED_MESSAGE,
            )
        } else {
            null
        }

    companion object {
        const val UPDATE_REQUIRED_ERROR = "update_server"

        /** For construction sites without a registry (commonMain tests, single-server hosts). */
        val Unrestricted = ApiV2Gate(null)
    }
}
