package org.siloserver.silo.network.localroute

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

/**
 * Request headers the local-route transport reads and removes before a request
 * leaves the process.
 */
object LocalRouteHeaders {
    /** Keep this request on the saved address: Watch Party and downloads. */
    const val SAVED_ADDRESS = "Silo-Local-Route-Saved-Address"

    /** Generation of the route a video stream started on; media is routed only with it. */
    const val MEDIA = "Silo-Local-Route-Media"

    /** Hand the body on as it arrives, never read ahead: the caller times it (the bandwidth probe). */
    const val UNBUFFERED = "Silo-Local-Route-Unbuffered"
}

/** Sends this request to the saved address even while a local route is in use. */
fun HttpRequestBuilder.stayOnSavedAddress() {
    header(LocalRouteHeaders.SAVED_ADDRESS, "1")
}

/** Hands this request's body on as it arrives on the local route, with no read-ahead. */
fun HttpRequestBuilder.readBodyUnbuffered() {
    header(LocalRouteHeaders.UNBUFFERED, "1")
}
