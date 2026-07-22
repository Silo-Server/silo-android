package org.siloserver.silo.network

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.encodedPath
import kotlin.time.TimeSource

class NetworkDiagnosticsPluginConfig {
    var hook: NetworkDiagnosticsHook? = null
}

/**
 * Reports one line per request to the configured [NetworkDiagnosticsHook]:
 * method, templated path, final status, duration. Never touches headers,
 * bodies, query strings, or the host — the safe-logging network contract.
 */
val NetworkDiagnosticsPlugin = createClientPlugin(
    "NetworkDiagnosticsPlugin",
    ::NetworkDiagnosticsPluginConfig,
) {
    val hook = pluginConfig.hook
    if (hook != null) {
        on(Send) { request ->
            val method = request.method.value
            val templatedPath = templateApiPath(request.url.encodedPath)
            val start = TimeSource.Monotonic.markNow()
            try {
                val call = proceed(request)
                hook.onRequestCompleted(
                    method,
                    templatedPath,
                    call.response.status.value,
                    start.elapsedNow().inWholeMilliseconds,
                )
                call
            } catch (t: Throwable) {
                hook.onRequestCompleted(method, templatedPath, 0, start.elapsedNow().inWholeMilliseconds)
                throw t
            }
        }
    }
}
