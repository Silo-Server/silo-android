package org.siloserver.silo.network.localroute

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Sends requests addressed to the saved server address over the active
 * [LocalRoute] instead. Only the transport moves: the request keeps its path,
 * query and headers, and the response reports the saved address again, so
 * everything above OkHttp (auth origin checks, caches, URL resolution) never
 * sees the LAN address.
 *
 * A request that fails on the route takes the route out of use at once and goes
 * again to the saved address when it cannot have reached the server (no
 * connection was made: name lookup, connect, the TLS handshake or a refused
 * key) or when it is a GET or HEAD. API and artwork responses are read ahead up
 * to [PREFETCH_BYTES] before they are handed on, so a load that fails after its
 * headers but before its caller saw anything goes again too. A failure later in
 * a body drops the route; the caller sees that failure as any other. A
 * cancelled call, an interrupted thread (Media3 abandons a load that way on a
 * seek) or a slow answer on a working connection says nothing about the route
 * and leaves it alone.
 *
 * Never routed: sign-in and token refresh (`/api/v2/auth/`), WebSocket upgrades,
 * and requests marked with [LocalRouteHeaders.SAVED_ADDRESS]. With
 * [media], only requests carrying [LocalRouteHeaders.MEDIA] for the route still
 * in use are routed, so a stream never moves onto a route that appeared after it
 * started, and bodies stream without read-ahead.
 */
class LocalRouteInterceptor(
    private val state: LocalRouteState,
    private val media: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val marked = chain.request()
        val request = marked.withoutMarkers()
        val route = routeFor(marked, request) ?: return chain.proceed(request)
        val pin = state.pinFor(route.localOrigin.host, route.localOrigin.port) ?: return chain.proceed(request)
        val attempt = LocalRouteAttempt(route, pin)
        val routed = request.newBuilder()
            .url(route.toLocal(request.url))
            .tag(LocalRouteAttempt::class.java, attempt)
            .build()
        val repeatable = request.method in REPEATABLE_METHODS
        val response = try {
            chain.withConnectTimeout(ROUTE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS).proceed(routed)
        } catch (failure: IOException) {
            return fallBack(chain, request, route, failure, connected = attempt.connected, mayRepeat = repeatable || !attempt.connected)
        }
        if (!media) {
            try {
                response.body?.source()?.request(PREFETCH_BYTES)
            } catch (failure: IOException) {
                response.close()
                return fallBack(chain, request, route, failure, connected = true, mayRepeat = repeatable)
            }
        }
        return response.relabeled(route) { failure ->
            if (isRouteFailure(chain, failure, connected = true)) state.reportFailure(route)
        }
    }

    /** Drops [route] after a failure it caused, and sends [request] to the saved address when [mayRepeat]. */
    private fun fallBack(
        chain: Interceptor.Chain,
        request: Request,
        route: LocalRoute,
        failure: IOException,
        connected: Boolean,
        mayRepeat: Boolean,
    ): Response {
        if (!isRouteFailure(chain, failure, connected)) throw failure
        state.reportFailure(route)
        if (!mayRepeat) throw failure
        return chain.proceed(request)
    }

    /**
     * Whether [failure] says the route stopped working. A cancelled call does
     * not, and neither does an interrupted thread: Okio reports that as an
     * [InterruptedIOException] that is not a timeout. A timeout counts only
     * before a connection was made; once [connected], it is a slow server,
     * which the saved address would not answer faster.
     */
    private fun isRouteFailure(chain: Interceptor.Chain, failure: IOException, connected: Boolean): Boolean {
        if (chain.call().isCanceled()) return false
        if (failure !is InterruptedIOException) return true
        return failure is SocketTimeoutException && !connected
    }

    private fun routeFor(marked: Request, request: Request): LocalRoute? {
        if (marked.header(LocalRouteHeaders.SAVED_ADDRESS) != null) return null
        val route = state.active.value ?: return null
        if (media && marked.header(LocalRouteHeaders.MEDIA)?.toLongOrNull() != route.generation) return null
        val url = request.url
        if (!route.coversSaved(url.scheme, url.host, url.port)) return null
        if (url.encodedPath.contains(AUTH_PATH)) return null
        if (request.header("Upgrade").equals("websocket", ignoreCase = true)) return null
        return route
    }

    companion object {
        /** A LAN connect takes milliseconds; a route that does not answer in this long is gone. */
        const val ROUTE_CONNECT_TIMEOUT_MS = 3_000

        /** How much of an API or artwork body is read before it is handed on. */
        const val PREFETCH_BYTES = 2L * 1024 * 1024

        private const val AUTH_PATH = "/api/v2/auth/"
        private val REPEATABLE_METHODS = setOf("GET", "HEAD")
    }
}

/**
 * Checks, before a routed request is written, that its connection presents the
 * key the request was routed under: a pooled or resumed connection must not
 * carry it to a key the pin no longer names. Then notes that the request may
 * reach the server from here on. Install as the first network interceptor
 * wherever [LocalRouteInterceptor] is installed.
 */
class LocalRouteConnectionCheck(private val state: LocalRouteState) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val attempt = chain.request().tag(LocalRouteAttempt::class.java)
        if (attempt != null && chain.request().url.let { attempt.route.coversLocal(it.scheme, it.host, it.port) }) {
            val session = (chain.connection()?.socket() as? SSLSocket)?.session
            val leaf = runCatching { session?.peerCertificates?.firstOrNull() }.getOrNull()
            if (LocalRouteTls.spkiPin(leaf) != attempt.pin) {
                state.reportKeyMismatch(attempt.route.localOrigin.host, attempt.route.localOrigin.port)
                throw IOException("The local address presented a key other than its pin")
            }
            attempt.connected = true
        }
        return chain.proceed(chain.request())
    }
}

/** One request's try on the route, and the key it may be sent to. */
class LocalRouteAttempt internal constructor(val route: LocalRoute, internal val pin: String) {
    @Volatile
    internal var connected = false
}

/**
 * [this] as a routed network request sees it: on the local origin when
 * [networkRequest] is a routed attempt for it, else unchanged. Lets origin
 * checks in network interceptors treat the routed leg as the saved address it
 * stands in for.
 */
fun HttpUrl.asRoutedFor(networkRequest: Request): HttpUrl {
    val route = networkRequest.tag(LocalRouteAttempt::class.java)?.route ?: return this
    if (!route.coversSaved(scheme, host, port)) return this
    return route.toLocal(this)
}

/** Installs the route and its TLS on [this]. [media] routes only marked video requests. */
fun OkHttpClient.Builder.localRoute(
    state: LocalRouteState,
    tls: LocalRouteTls,
    media: Boolean = false,
): OkHttpClient.Builder = apply {
    addInterceptor(LocalRouteInterceptor(state, media))
    // First, so the key is checked before any other network interceptor runs.
    networkInterceptors().add(0, LocalRouteConnectionCheck(state))
    tls.applyTo(this)
}

internal fun LocalRoute.toLocal(url: HttpUrl): HttpUrl =
    url.newBuilder().scheme(localOrigin.scheme).host(localOrigin.host).port(localOrigin.port).build()

private fun LocalRoute.toSaved(url: HttpUrl): HttpUrl =
    if (coversLocal(url.scheme, url.host, url.port)) {
        url.newBuilder().scheme(savedOrigin.scheme).host(savedOrigin.host).port(savedOrigin.port).build()
    } else {
        url
    }

private fun Request.withoutMarkers(): Request =
    if (header(LocalRouteHeaders.SAVED_ADDRESS) == null && header(LocalRouteHeaders.MEDIA) == null) this
    else newBuilder().removeHeader(LocalRouteHeaders.SAVED_ADDRESS).removeHeader(LocalRouteHeaders.MEDIA).build()

private fun Response.relabeled(route: LocalRoute, onBodyFailure: (IOException) -> Unit): Response {
    val builder = newBuilder().request(request.newBuilder().url(route.toSaved(request.url)).build())
    body?.let { builder.body(RouteFailureBody(it, onBodyFailure)) }
    return builder.build()
}

/** Reports a failed body read: a stream on the route that died. */
private class RouteFailureBody(
    private val delegate: ResponseBody,
    private val onFailure: (IOException) -> Unit,
) : ResponseBody() {
    private val source: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                try {
                    super.read(sink, byteCount)
                } catch (failure: IOException) {
                    onFailure(failure)
                    throw failure
                }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = source
    override fun close() = delegate.close()
}
