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
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.diagnosticsProfileScope
import org.siloserver.silo.network.apiv2.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DiagnosticsUploadAuthorization
import org.siloserver.silo.network.diagnosticsUploadAuthorization

interface DiagnosticsApi {
    suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse>

    suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): DiagnosticsUploadResult

    /**
     * Sends against one exact server credential without auth refresh or request
     * rebasing. Implementations that do not own a Silo transport may delegate to
     * [upload]; the production implementation overrides this boundary.
     */
    suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
        authorization: DiagnosticsUploadAuthorization,
    ): DiagnosticsUploadResult = upload(manifestJson, bundleBytes, capturedProfileId)
}

class DefaultDiagnosticsApi(
    client: HttpClient,
    private val nowMs: () -> Long = { GMTDate().timestamp },
    private val gate: ApiV2Gate,
) : DiagnosticsApi {
    private val client = client.config { followRedirects = false }
    override suspend fun getStatus(): ApiResult<DiagnosticsStatusResponse> = safeApiV2Call(gate) {
        client.get("/api/v2/diagnostics/capabilities") { diagnosticsProfileScope(null) }
    }

    override suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
    ): DiagnosticsUploadResult = performUpload(
        manifestJson = manifestJson,
        bundleBytes = bundleBytes,
        capturedProfileId = capturedProfileId,
        authorization = null,
    )

    override suspend fun upload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
        authorization: DiagnosticsUploadAuthorization,
    ): DiagnosticsUploadResult = performUpload(
        manifestJson = manifestJson,
        bundleBytes = bundleBytes,
        capturedProfileId = capturedProfileId,
        authorization = authorization,
    )

    private suspend fun performUpload(
        manifestJson: ByteArray,
        bundleBytes: ByteArray,
        capturedProfileId: String?,
        authorization: DiagnosticsUploadAuthorization?,
    ): DiagnosticsUploadResult = try {
        gate.blocked()?.let {
            return DiagnosticsUploadResult.Failure(DiagnosticsErrorCode.UNKNOWN, it.code, message = it.message)
        }
        val endpoint = authorization?.let { "${it.serverUrl.trimEnd('/')}/api/v2/diagnostics/reports" }
            ?: "/api/v2/diagnostics/reports"
        val response = client.post(endpoint) {
            singleAttempt()
            diagnosticsProfileScope(capturedProfileId)
            authorization?.let { diagnosticsUploadAuthorization(it) }
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
            val error = response.toApiV2Error()
            DiagnosticsUploadResult.Failure(
                code = when (response.status.value) {
                    401 -> DiagnosticsErrorCode.UNAUTHORIZED
                    413 -> DiagnosticsErrorCode.TOO_LARGE
                    429 -> DiagnosticsErrorCode.RATE_LIMITED
                    403 -> DiagnosticsErrorCode.FORBIDDEN
                    else -> DiagnosticsErrorCode.UNKNOWN
                },
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
