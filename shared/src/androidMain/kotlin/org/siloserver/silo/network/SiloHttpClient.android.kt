package org.siloserver.silo.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import org.siloserver.silo.network.localroute.localRoute

/**
 * Android platform HttpClient using the OkHttp engine.
 *
 * Concurrency tuning and the shared connection pool live in [SiloOkHttp]; the
 * dispatcher is built fresh per client because Ktor's OkHttp engine derives a
 * new `OkHttpClient` for each Ktor client (force-resetting the dispatcher in
 * the process), and per-client dispatchers keep API, diagnostics, and image
 * traffic from queueing behind each other.
 *
 * Every client can take the active server's local route
 * ([org.siloserver.silo.network.localroute.LocalRouteInterceptor]).
 */
actual fun createPlatformHttpClient(retryOnConnectionFailure: Boolean): HttpClient {
    return HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(SiloOkHttp.newDispatcher())
                connectionPool(SiloOkHttp.connectionPool)
                retryOnConnectionFailure(retryOnConnectionFailure)
                localRoute()
            }
        }
    }
}
