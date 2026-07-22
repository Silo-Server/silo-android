package org.siloserver.silo.model.diagnostics

enum class DiagnosticsErrorCode(val wire: String) {
    DISABLED("disabled"),
    STORAGE_UNAVAILABLE("storage_unavailable"),
    QUOTA_EXCEEDED("quota_exceeded"),
    RATE_LIMITED("rate_limited"),
    TOO_LARGE("too_large"),
    BUSY("busy"),
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    DESTINATION_MISMATCH("destination_mismatch"),
    STALE_CONSENT("stale_consent"),
    ARCHIVE_MISMATCH("archive_mismatch"),
    INVALID_BUNDLE("invalid_bundle"),
    INVALID_ARCHIVE("invalid_archive"),
    INVALID_MANIFEST("invalid_manifest"),
    STALE_REPORT("stale_report"),
    PROFILE_MISMATCH("profile_mismatch"),
    CHILD_PROFILE_FORBIDDEN("child_profile_forbidden"),
    UNAUTHORIZED("unauthorized"),
    API_KEY_NOT_ALLOWED("api_key_not_allowed"),
    FORBIDDEN("forbidden"),
    INTERNAL_ERROR("internal_error"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): DiagnosticsErrorCode = when (value) {
            "quota" -> QUOTA_EXCEEDED
            "rate_limit", "rate_limit_exceeded" -> RATE_LIMITED
            "diagnostics_disabled" -> DISABLED
            else -> entries.firstOrNull { it.wire == value } ?: UNKNOWN
        }
    }
}

sealed class DiagnosticsUploadResult {
    data class Success(val response: DiagnosticsUploadResponse) : DiagnosticsUploadResult()

    data class Failure(
        val code: DiagnosticsErrorCode,
        val httpStatus: Int,
        val retryAfterSeconds: Long? = null,
        val message: String = "",
    ) : DiagnosticsUploadResult()

    data class NetworkError(val exception: Throwable) : DiagnosticsUploadResult()
}
