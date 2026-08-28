package org.siloserver.silo.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*

/**
 * Android platform HttpClient using the OkHttp engine.
 *
 * Concurrency tuning and the shared connection pool live in [SiloOkHttp]; the
 * dispatcher is built fresh per client because Ktor's OkHttp engine derives a
 * new `OkHttpClient` for each Ktor client (force-resetting the dispatcher in
 * the process), and per-client dispatchers keep API, diagnostics, and image
 * traffic from queueing behind each other.
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(SiloOkHttp.newDispatcher())
                connectionPool(SiloOkHttp.connectionPool)
            }
        }
    }
}
