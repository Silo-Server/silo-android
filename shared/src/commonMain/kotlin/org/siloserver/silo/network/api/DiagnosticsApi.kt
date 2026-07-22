package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import org.siloserver.silo.model.diagnostics.DiagnosticsErrorCode
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResponse
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.diagnosticsProfileScope

interface DiagnosticsApi {
    suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse>

    suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): DiagnosticsUploadResult
}

class DefaultDiagnosticsApi(
    private val client: HttpClient,
    private val nowMs: () -> Long = { GMTDate().timestamp },
) : DiagnosticsApi {
    override suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse> = safeApiCall {
        client.get("/api/v1/diagnostics/status")
    }

    override suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): DiagnosticsUploadResult = try {
        val response = client.post("/api/v1/diagnostics/reports") {
            diagnosticsProfileScope(capturedProfileId)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            key = "manifest",
                            value = manifestJson,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                                append(HttpHeaders.ContentDisposition, "filename=manifest.json")
                            },
                        )
                        append(
                            key = "bundle",
                            value = bundleBytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, "application/gzip")
                                append(HttpHeaders.ContentDisposition, "filename=bundle.tar.gz")
                            },
                        )
                    },
                ),
            )
            timeout {
                requestTimeoutMillis = UPLOAD_TIMEOUT_MS
                socketTimeoutMillis = UPLOAD_TIMEOUT_MS
            }
        }
        if (response.status == HttpStatusCode.Created) {
            DiagnosticsUploadResult.Success(response.body<DiagnosticsUploadResponse>())
        } else {
            val error = try {
                response.body<ApiErrorBody>()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                ApiErrorBody()
            }
            DiagnosticsUploadResult.Failure(
                code = DiagnosticsErrorCode.fromWire(error.error),
                httpStatus = response.status.value,
                retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.let(::retryAfterSeconds),
                message = error.message,
            )
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        DiagnosticsUploadResult.NetworkError(error)
    }

    private fun retryAfterSeconds(value: String): Long? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        val deadlineMs = runCatching { trimmed.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
        val remainingMs = (deadlineMs - nowMs()).coerceAtLeast(0)
        return (remainingMs + 999) / 1_000
    }

    private companion object {
        const val UPLOAD_TIMEOUT_MS = 300_000L
    }
}
