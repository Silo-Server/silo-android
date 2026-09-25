package org.siloserver.silo.network

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * JSON configuration used for all API serialization/deserialization.
 */
val SiloJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Creates a fully configured Ktor [HttpClient] for Silo API communication.
 *
 * The client is built on top of the platform-specific engine (OkHttp on Android,
 * Darwin on iOS) provided by [createPlatformHttpClient], and layers on:
 *
 * - **Content Negotiation**: JSON serialization with lenient, unknown-key-tolerant parsing
 * - **Authentication**: Custom [SiloAuthPlugin] that manages Bearer tokens,
 *   profile headers, and automatic 401 token refresh
 * - **Logging**: Configurable request/response logging (disabled by default)
 * - **Timeouts**: 30s connect, 60s overall request timeout
 * - **Default Request**: JSON content type
 */
fun createSiloClient(
    tokenManager: TokenManager,
    deviceMetadataProvider: DeviceMetadataProvider? = null,
    diagnosticsObserver: NetworkDiagnosticsObserver? = null,
    cleartextOriginConsent: CleartextOriginConsent? = null,
    retryOnConnectionFailure: Boolean = true,
): HttpClient {
    val platformClient = createPlatformHttpClient(retryOnConnectionFailure)

    return platformClient.config {
        install(ContentNegotiation) {
            json(SiloJson)
        }

        // Foreground notifications accelerator (events websocket). REST stays
        // the source of truth; the socket only makes the unread badge instant.
        install(WebSockets)

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE
        }

        diagnosticsObserver?.let { observer ->
            install(ResponseObserver) {
                onResponse { response ->
                    runCatching {
                        observer.completed(
                            method = response.call.request.method.value,
                            rawPath = response.call.request.url.encodedPath,
                            status = response.status.value,
                            durationMs = (response.responseTime.timestamp - response.requestTime.timestamp)
                                .coerceAtLeast(0),
                        )
                    }
                }
            }
        }

        install(SiloAuthPlugin) {
            this.tokenManager = tokenManager
            this.deviceMetadataProvider = deviceMetadataProvider
            this.diagnosticsObserver = diagnosticsObserver
            this.cleartextOriginConsent = cleartextOriginConsent
        }

        install(HttpTimeout) {
            // Connect timeout is the "is the server reachable" signal — a dead
            // server should fail fast here rather than hang. Kept short (10s) so
            // server-down is detected quickly; request/socket stay generous so
            // legitimately slow but connected calls (transcode start, large
            // catalog pages) aren't cut off. Individual slow endpoints may still
            // raise their own per-request timeout.
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }

        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }
}

fun interface NetworkDiagnosticsObserver {
    fun completed(method: String, rawPath: String, status: Int, durationMs: Long)

    fun authRefresh(state: String) = Unit
}
