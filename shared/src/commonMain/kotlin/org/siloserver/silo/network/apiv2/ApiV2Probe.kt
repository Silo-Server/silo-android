package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.skipSiloAuth

/** Outcome of one [ApiV2Probe] call. */
sealed class ApiV2ProbeResult {
    /** The server speaks API v2. */
    data class V2(val info: SystemInfo) : ApiV2ProbeResult()

    /** A v1-only alpha server: the v2 prefix fell through to the legacy listener's plain 404. */
    data object UpdateServer : ApiV2ProbeResult()

    /** Anything else; never treated as a contract verdict. */
    data class Failure(val kind: Kind, val status: Int? = null, val cause: Throwable? = null) : ApiV2ProbeResult()

    enum class Kind {
        /** DNS, connect, TLS, or a closed socket. */
        CONNECTION,
        TIMEOUT,
        /** 401 or 403. */
        AUTHENTICATION,
        /** 429. */
        RATE_LIMITED,
        /** 5xx. */
        SERVER_ERROR,
        /** A 200 that is not a syntactically valid v2 info body (HTML from a proxy, malformed JSON, api_major != 2). */
        MALFORMED_RESPONSE,
        /** Any other status, including a 404 whose body is not the legacy listener's exact `404 page not found`. */
        UNEXPECTED_STATUS,
    }
}

/**
 * The v2 contract probe: `GET /api/v2/system/info`, once per established
 * connection (or identity refresh), never per request.
 *
 * Compatibility rule:
 * - A syntactically valid info body with `api_major == 2` is [ApiV2ProbeResult.V2].
 * - A 404 whose body is exactly what Go's `http.NotFound` on the legacy v1
 *   listener writes — [LEGACY_NOT_FOUND_BODY], tolerating only the single
 *   trailing newline the helper appends — is [ApiV2ProbeResult.UpdateServer].
 *   It is the only input that produces the update-server state. The body
 *   decides; the Content-Type is not consulted.
 * - A 404 with any other body (an HTML page from a proxy, a v2
 *   `application/problem+json` body, a proxy's own plain-text `Not Found`,
 *   leading whitespace or extra newlines around the Go text) is
 *   [ApiV2ProbeResult.Failure] with [ApiV2ProbeResult.Kind.UNEXPECTED_STATUS]:
 *   it does not prove the server is a v1-only Silo, so it is a reachability
 *   problem, not a contract verdict.
 * - Timeouts, TLS/connect failures, 401/403, 429, 5xx, and unparseable 200
 *   bodies are each their own [ApiV2ProbeResult.Failure] kind.
 *
 * No result enables a v1 transport for a pilot operation.
 */
class ApiV2Probe(private val client: HttpClient) {

    /**
     * Probes [serverUrl] (or the active server when null, resolved by the auth
     * plugin exactly like every other relative call).
     */
    suspend fun probe(serverUrl: String? = null): ApiV2ProbeResult {
        val url = if (serverUrl == null) PATH else "${serverUrl.trimEnd('/')}$PATH"
        val response = try {
            client.get(url) { skipSiloAuth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.TIMEOUT, cause = e)
        } catch (e: ConnectTimeoutException) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.TIMEOUT, cause = e)
        } catch (e: SocketTimeoutException) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.TIMEOUT, cause = e)
        } catch (e: Throwable) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.CONNECTION, cause = e)
        }
        val status = response.status.value
        return when {
            response.status == HttpStatusCode.NotFound -> {
                if (isLegacyNotFound(response.bodyAsText())) {
                    ApiV2ProbeResult.UpdateServer
                } else {
                    ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.UNEXPECTED_STATUS, status)
                }
            }
            status == 401 || status == 403 -> ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.AUTHENTICATION, status)
            status == 429 -> ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.RATE_LIMITED, status)
            status >= 500 -> ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.SERVER_ERROR, status)
            status != 200 -> ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.UNEXPECTED_STATUS, status)
            else -> decodeInfo(response.bodyAsText(), status)
        }
    }

    private fun decodeInfo(body: String, status: Int): ApiV2ProbeResult {
        val info = try {
            SiloJson.decodeFromString(SystemInfo.serializer(), body)
        } catch (e: Throwable) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, status, e)
        }
        if (info.apiMajor != 2) {
            return ApiV2ProbeResult.Failure(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, status)
        }
        return ApiV2ProbeResult.V2(info)
    }

    companion object {
        const val PATH = "/api/v2/system/info"

        /** What Go's `http.NotFound` writes, before its trailing newline. */
        const val LEGACY_NOT_FOUND_BODY = "404 page not found"

        /**
         * Exact match, tolerating only the single trailing newline the Go helper
         * writes. Leading whitespace or any other decoration is some other
         * service's 404, not the legacy listener's.
         */
        fun isLegacyNotFound(body: String): Boolean =
            body == LEGACY_NOT_FOUND_BODY || body == LEGACY_NOT_FOUND_BODY + "\n"
    }
}
