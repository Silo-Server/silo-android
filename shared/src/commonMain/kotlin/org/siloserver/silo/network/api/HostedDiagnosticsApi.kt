package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.createPlatformHttpClient
import org.siloserver.silo.network.httpOrigin

const val DEFAULT_HOSTED_DIAGNOSTICS_BASE_URL = "https://diagnostics.siloserver.org"

/**
 * Builds the public collector transport without installing SiloAuthPlugin, cookies,
 * profile headers, or any source-server default request state.
 */
fun createHostedDiagnosticsClient(
    baseUrl: String = DEFAULT_HOSTED_DIAGNOSTICS_BASE_URL,
    platformClient: HttpClient = createPlatformHttpClient(),
): HttpClient {
    val normalizedBaseUrl = validateHostedDiagnosticsBaseUrl(baseUrl)
    return platformClient.config {
        followRedirects = false
        install(ContentNegotiation) { json(SiloJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        defaultRequest {
            url(normalizedBaseUrl)
            accept(ContentType.Application.Json)
        }
    }
}

internal fun validateHostedDiagnosticsBaseUrl(baseUrl: String): String {
    require(baseUrl == baseUrl.trim() && baseUrl.isNotBlank()) { "invalid hosted diagnostics origin" }
    require('?' !in baseUrl && '#' !in baseUrl) {
        "hosted diagnostics origin must not contain a query or fragment"
    }
    val parsed = runCatching { Url(baseUrl) }.getOrElse { throw IllegalArgumentException("invalid hosted diagnostics origin", it) }
    require(httpOrigin(baseUrl) != null) { "invalid hosted diagnostics origin" }
    require(parsed.user == null && parsed.password == null) { "hosted diagnostics origin must not contain userinfo" }
    require(parsed.encodedPath.isEmpty() || parsed.encodedPath == "/") {
        "hosted diagnostics origin must not contain a path"
    }
    require(parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) { "invalid hosted diagnostics origin" }
    val isLoopback = parsed.host.lowercase() in setOf("localhost", "127.0.0.1", "::1")
    require(parsed.protocol == URLProtocol.HTTPS || (parsed.protocol == URLProtocol.HTTP && isLoopback)) {
        "hosted diagnostics requires HTTPS (except loopback tests)"
    }
    return baseUrl.trimEnd('/')
}

@Serializable
data class HostedDiagnosticsCapabilities(
    val status: HostedDiagnosticsAvailability,
    @SerialName("collector_id") val collectorId: String,
    @SerialName("accepted_schema_versions") val acceptedSchemaVersions: List<Int>,
    @SerialName("max_bundle_bytes") val maxBundleBytes: Long,
    @SerialName("max_manifest_bytes") val maxManifestBytes: Long,
    @SerialName("retention_days") val retentionDays: Int,
    @SerialName("consent_notice_version") val consentNoticeVersion: Int,
)

@Serializable
enum class HostedDiagnosticsAvailability {
    @SerialName("available") AVAILABLE,
    @SerialName("disabled") DISABLED,
    @SerialName("storage_unavailable") STORAGE_UNAVAILABLE,
}

@Serializable
data class HostedDiagnosticsInstallationRequest(
    val platform: String,
    @SerialName("app_id") val appId: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_build") val appBuild: String,
)

@Serializable
data class HostedDiagnosticsInstallationResponse(
    @SerialName("installation_id") val installationId: String,
    @SerialName("installation_token") val installationToken: String,
)

@Serializable
data class HostedDiagnosticsCreateReportRequest(
    @SerialName("report_id") val reportId: String,
    val manifest: DiagnosticsManifest,
    @SerialName("bundle_bytes") val bundleBytes: Long,
    @SerialName("bundle_sha256") val bundleSha256: String,
)

@Serializable
data class HostedDiagnosticsCreateReportResponse(
    @SerialName("report_id") val reportId: String,
    @SerialName("short_id") val shortId: String,
    @SerialName("upload_token") val uploadToken: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class HostedDiagnosticsReportStatusResponse(
    @SerialName("report_id") val reportId: String,
    @SerialName("short_id") val shortId: String? = null,
    val state: HostedDiagnosticsReportState,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
enum class HostedDiagnosticsReportState {
    @SerialName("receiving") RECEIVING,
    @SerialName("uploaded") UPLOADED,
    @SerialName("processing") PROCESSING,
    @SerialName("ready") READY,
    @SerialName("rejected") REJECTED,
    @SerialName("deleting") DELETING,
    @SerialName("deleted") DELETED,
    ;

    val wireValue: String get() = name.lowercase()
}

sealed interface HostedDiagnosticsApiResult<out T> {
    data class Success<T>(val value: T) : HostedDiagnosticsApiResult<T>

    data class Failure(
        val httpStatus: Int,
        val errorCode: String,
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : HostedDiagnosticsApiResult<Nothing>

    data class NetworkError(val exception: Throwable) : HostedDiagnosticsApiResult<Nothing>
}

interface HostedDiagnosticsApi {
    suspend fun capabilities(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities>

    suspend fun createInstallation(
        request: HostedDiagnosticsInstallationRequest,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsInstallationResponse>

    suspend fun createReport(
        installationToken: String,
        request: HostedDiagnosticsCreateReportRequest,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsCreateReportResponse>

    suspend fun uploadBundle(
        installationToken: String,
        reportId: String,
        uploadToken: String,
        bundle: ByteArray,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>

    suspend fun reportStatus(
        installationToken: String,
        reportId: String,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse>

    suspend fun deleteReport(
        installationToken: String,
        reportId: String,
    ): HostedDiagnosticsApiResult<Unit>
}

class DefaultHostedDiagnosticsApi(
    private val client: HttpClient,
) : HostedDiagnosticsApi {
    override suspend fun capabilities(): HostedDiagnosticsApiResult<HostedDiagnosticsCapabilities> = request(
        expected = HttpStatusCode.OK,
        call = { client.get("/v1/capabilities") },
    )

    override suspend fun createInstallation(
        request: HostedDiagnosticsInstallationRequest,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsInstallationResponse> = request(
        expected = HttpStatusCode.Created,
        call = {
            client.post("/v1/installations") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        },
    )

    override suspend fun createReport(
        installationToken: String,
        request: HostedDiagnosticsCreateReportRequest,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsCreateReportResponse> = request(
        expected = HttpStatusCode.Created,
        call = {
            client.post("/v1/reports") {
                bearerAuth(installationToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        },
    )

    override suspend fun uploadBundle(
        installationToken: String,
        reportId: String,
        uploadToken: String,
        bundle: ByteArray,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse> = try {
        val response = client.put("/v1/reports/$reportId/bundle") {
            bearerAuth(installationToken)
            header(UPLOAD_TOKEN_HEADER, uploadToken)
            header(HttpHeaders.ContentLength, bundle.size.toString())
            header(HttpHeaders.ContentType, "application/gzip")
            setBody(bundle)
            timeout {
                requestTimeoutMillis = UPLOAD_TIMEOUT_MS
                socketTimeoutMillis = UPLOAD_TIMEOUT_MS
            }
        }
        if (response.status == HttpStatusCode.Accepted) {
            try {
                HostedDiagnosticsApiResult.Success(response.body())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                HostedDiagnosticsApiResult.Failure(
                    httpStatus = response.status.value,
                    errorCode = "invalid_response",
                    message = "Collector returned an invalid upload receipt",
                )
            }
        } else {
            response.failure()
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        HostedDiagnosticsApiResult.NetworkError(error)
    }

    override suspend fun reportStatus(
        installationToken: String,
        reportId: String,
    ): HostedDiagnosticsApiResult<HostedDiagnosticsReportStatusResponse> = request(
        expected = HttpStatusCode.OK,
        call = {
            client.get("/v1/reports/$reportId") {
                bearerAuth(installationToken)
            }
        },
    )

    override suspend fun deleteReport(
        installationToken: String,
        reportId: String,
    ): HostedDiagnosticsApiResult<Unit> = try {
        val response = client.delete("/v1/reports/$reportId") {
            bearerAuth(installationToken)
        }
        if (response.status == HttpStatusCode.NoContent) {
            HostedDiagnosticsApiResult.Success(Unit)
        } else {
            response.failure()
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        HostedDiagnosticsApiResult.NetworkError(error)
    }

    private suspend inline fun <reified T> request(
        expected: HttpStatusCode,
        crossinline call: suspend () -> io.ktor.client.statement.HttpResponse,
    ): HostedDiagnosticsApiResult<T> = try {
        val response = call()
        if (response.status == expected) {
            HostedDiagnosticsApiResult.Success(response.body())
        } else {
            response.failure()
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        HostedDiagnosticsApiResult.NetworkError(error)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.failure(): HostedDiagnosticsApiResult.Failure {
        val error = try {
            body<ApiErrorBody>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ApiErrorBody()
        }
        return HostedDiagnosticsApiResult.Failure(
            httpStatus = status.value,
            errorCode = error.error.ifBlank { "unknown" },
            message = error.message,
            retryAfterSeconds = headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceAtLeast(0),
        )
    }

    private companion object {
        const val UPLOAD_TOKEN_HEADER = "X-Upload-Token"
        const val UPLOAD_TIMEOUT_MS = 300_000L
    }
}
