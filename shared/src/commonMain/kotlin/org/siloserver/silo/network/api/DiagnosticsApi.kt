package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsIngestResult
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.ApiResult

interface DiagnosticsApi {
    suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse>

    /**
     * Uploads one diagnostics report: exactly two multipart parts, in order —
     * `manifest` (application/json, ≤64 KiB) then `bundle` (application/gzip).
     * The server's streaming reader rejects any other name/type/order as
     * `invalid_bundle`.
     */
    suspend fun uploadReport(manifestJson: ByteArray, bundleBytes: ByteArray): DiagnosticsUploadResult
}

class DefaultDiagnosticsApi(private val client: HttpClient) : DiagnosticsApi {

    override suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse> = safeApiCall {
        client.get("/api/v1/diagnostics/status")
    }

    override suspend fun uploadReport(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
    ): DiagnosticsUploadResult = try {
        val response = client.post("/api/v1/diagnostics/reports") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "manifest",
                            manifestJson,
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                append(HttpHeaders.ContentDisposition, "filename=\"manifest.json\"")
                            },
                        )
                        append(
                            "bundle",
                            bundleBytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/gzip")
                                append(HttpHeaders.ContentDisposition, "filename=\"bundle.tar.gz\"")
                            },
                        )
                    },
                ),
            )
            // Bundles are up to 10 MiB and TV boxes can sit on slow uplinks;
            // the server grants a 10-minute read window for this route.
            timeout {
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 300_000
            }
        }
        if (response.status.isSuccess()) {
            DiagnosticsUploadResult.Success(response.body<DiagnosticsIngestResult>())
        } else {
            val errorBody = try {
                response.body<ApiErrorBody>()
            } catch (_: Exception) {
                ApiErrorBody()
            }
            DiagnosticsUploadResult.Failure(
                code = DiagnosticsErrorCode.fromWire(errorBody.error),
                httpStatus = response.status.value,
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
                message = errorBody.message,
            )
        }
    } catch (e: Exception) {
        DiagnosticsUploadResult.NetworkError(e)
    }
}
