package com.continuum.app.network

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * Android platform HttpClient using the OkHttp engine.
 *
 * The app fans out many small REST calls per screen — e.g. a library's
 * Recommended view resolves one `/sections/{id}/items` request per section
 * (10–12 of them) and the Home grid does the same. OkHttp's default
 * [Dispatcher] caps concurrency at 64 total / **5 per host**, so a 10–12 way
 * fan-out to a single server runs in 2–3 sequential waves instead of in
 * parallel, multiplying the wall-clock time the loading spinner is visible.
 *
 * We raise the per-host ceiling so the fan-out runs in one wave, and keep a
 * larger keep-alive [ConnectionPool] so those parallel requests reuse warm TLS
 * connections rather than renegotiating.
 */
actual fun createPlatformHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(
                    Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 16
                    },
                )
                connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            }
        }
    }
}
