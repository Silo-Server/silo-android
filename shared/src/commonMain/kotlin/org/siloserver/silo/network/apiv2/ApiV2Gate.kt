package org.siloserver.silo.network.apiv2

import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry

/**
 * Blocks the pilot's v2 operations while the active server is in the
 * [ServerContract.UPDATE_REQUIRED] state. The state comes from the registry
 * entry (set by [ApiV2Probe] on connect); nothing here ever performs a
 * request, and a blocked call is never redirected to a v1 path.
 */
class ApiV2Gate(private val registry: ServerRegistry? = null) {

    val contract: ServerContract
        get() = registry?.activeEntry?.value?.contract ?: ServerContract.UNKNOWN

    /** The error to return instead of calling the server, or null when the call may proceed. */
    fun blocked(): ApiResult.Error? =
        if (contract == ServerContract.UPDATE_REQUIRED) {
            ApiResult.Error(
                code = UPDATE_REQUIRED_CODE,
                error = UPDATE_REQUIRED_ERROR,
                message = ServerContract.UPDATE_REQUIRED_MESSAGE,
            )
        } else {
            null
        }

    companion object {
        /** No HTTP exchange happened; distinguishes the gate from any server status. */
        const val UPDATE_REQUIRED_CODE = 0
        const val UPDATE_REQUIRED_ERROR = "update_server"

        /** For construction sites without a registry (commonMain tests, single-server hosts). */
        val Unrestricted = ApiV2Gate(null)
    }
}
