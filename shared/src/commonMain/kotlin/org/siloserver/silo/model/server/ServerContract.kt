package org.siloserver.silo.model.server

import kotlinx.serialization.Serializable

/**
 * What the app has learned about a saved server's native API contract.
 *
 * Set from [org.siloserver.silo.network.apiv2.ApiV2Probe] when a connection is
 * established or the server identity is refreshed — never per request.
 * [UPDATE_REQUIRED] is the explicit update-server state for a v1-only alpha
 * server; it blocks the pilot's v2 operations (see
 * [org.siloserver.silo.network.apiv2.ApiV2Gate]). Transport, TLS, auth, rate
 * limit, and server errors leave the previous value in place.
 */
@Serializable
enum class ServerContract {
    /** Not probed yet, or the last probe failed for a reason unrelated to the contract. */
    UNKNOWN,

    /** The server answered GET /api/v2/system/info with a valid v2 info body. */
    V2,

    /** The server has no /api/v2 listener: it predates the v2 contract and must be updated. */
    UPDATE_REQUIRED;

    companion object {
        /** The one user-facing message for [UPDATE_REQUIRED]. */
        const val UPDATE_REQUIRED_MESSAGE: String =
            "This server runs an older Silo release that this app no longer supports. Update the server, then try again."
    }
}
