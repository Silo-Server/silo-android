package org.siloserver.silo.model.diagnostics

/**
 * Structured error vocabulary of `POST /api/v1/diagnostics/reports`, matching
 * `silo-server/internal/api/handlers/diagnostics.go` exactly. Unknown codes
 * (a newer server) map to [UNKNOWN] and are treated as retryable.
 */
enum class DiagnosticsErrorCode(val wire: String) {
    DISABLED("disabled"),
    STORAGE_UNAVAILABLE("storage_unavailable"),
    QUOTA_EXCEEDED("quota_exceeded"),
    TOO_LARGE("too_large"),
    BUSY("busy"),
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    DESTINATION_MISMATCH("destination_mismatch"),
    STALE_CONSENT("stale_consent"),
    ARCHIVE_MISMATCH("archive_mismatch"),
    INVALID_BUNDLE("invalid_bundle"),
    PROFILE_MISMATCH("profile_mismatch"),
    CHILD_PROFILE_FORBIDDEN("child_profile_forbidden"),
    UNAUTHORIZED("unauthorized"),
    INTERNAL_ERROR("internal_error"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): DiagnosticsErrorCode =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * Upload outcome. Deliberately not [org.siloserver.silo.network.ApiResult]:
 * the uploader needs the structured error code and the `Retry-After` header
 * (sent on 429 quota_exceeded / 503 busy), which `ApiResult` doesn't carry —
 * widening a type every other API uses for one caller would be the wrong trade.
 */
sealed class DiagnosticsUploadResult {
    data class Success(val result: DiagnosticsIngestResult) : DiagnosticsUploadResult()

    data class Failure(
        val code: DiagnosticsErrorCode,
        val httpStatus: Int,
        val retryAfterSeconds: Long? = null,
        val message: String = "",
    ) : DiagnosticsUploadResult()

    data class NetworkError(val exception: Throwable) : DiagnosticsUploadResult()
}
