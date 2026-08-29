package org.siloserver.silo.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp foundation shared by the Ktor API clients and Coil.
 *
 * One [ConnectionPool] means API calls and artwork fetches reuse the same warm
 * TLS connections to the Silo server instead of maintaining two independent
 * connection sets. Dispatchers are deliberately NOT shared: each client gets
 * its own tuned [Dispatcher] so a burst of poster downloads can never queue
 * API calls (or vice versa), and the app's long-lived websockets — which pin a
 * dispatcher slot for their lifetime — can never crowd out image traffic.
 *
 * Deliberately NOT part of this pool: the Media3 playback/reader transport
 * (`PlayerOkHttpClient` in android-shared). Its long-lived streaming
 * connections would churn this pool's idle slots, and its interceptor chain
 * (cleartext consent, credential stripping) must never apply to API or image
 * traffic — do not "unify" them.
 *
 * Everything here is process-lifetime and must never be closed. In particular,
 * never close a Ktor `HttpClient` built on this pool: Ktor's engine cleanup
 * calls `connectionPool.evictAll()`, which would drop the shared pool's warm
 * connections for every other client in the process.
 */
object SiloOkHttp {
    // Idle capacity covers a simultaneous API fan-out (16) + poster burst (16)
    // so post-burst sockets are retained warm instead of evicted and re-opened
    // with fresh TLS handshakes on the next screen.
    val connectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    /**
     * The app fans out many small REST calls per screen — e.g. a library's
     * Recommended view resolves one `/sections/{id}/items` request per section
     * (10–12 of them) and the Home grid does the same. OkHttp's default
     * [Dispatcher] caps concurrency at 64 total / **5 per host**, so a 10–12
     * way fan-out to a single server runs in 2–3 sequential waves instead of
     * in parallel, multiplying the wall-clock time the loading spinner is
     * visible. 16 per host lets the fan-out run in one wave.
     */
    fun newDispatcher(): Dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    /**
     * Coil's network client. Shares [connectionPool] with the API clients but
     * owns its dispatcher (see class KDoc). No OkHttp `Cache` is attached —
     * Coil owns disk caching for images.
     */
    val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(newDispatcher())
            .connectionPool(connectionPool)
            .build()
    }
}
