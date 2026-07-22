package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/diagnostics/status` response.
 *
 * `status` stays a raw string (not an enum) so an unknown future value from a
 * newer server degrades to [DiagnosticsAvailability.UNAVAILABLE] instead of a
 * deserialization crash; map through [availability].
 */
@Serializable
data class DiagnosticsStatusResponse(
    val status: String,
    @SerialName("server_instance_id") val serverInstanceId: String,
    @SerialName("accepted_schema_versions") val acceptedSchemaVersions: List<Int> = emptyList(),
    @SerialName("max_bundle_bytes") val maxBundleBytes: Long = 0,
    @SerialName("max_manifest_bytes") val maxManifestBytes: Long = 0,
    @SerialName("retention_days") val retentionDays: Int = 0,
    @SerialName("consent_notice_version") val consentNoticeVersion: Int = 1,
) {
    val availability: DiagnosticsAvailability
        get() = when (status) {
            "available" -> DiagnosticsAvailability.AVAILABLE
            "disabled" -> DiagnosticsAvailability.DISABLED
            "storage_unavailable" -> DiagnosticsAvailability.STORAGE_UNAVAILABLE
            else -> DiagnosticsAvailability.UNAVAILABLE
        }
}

enum class DiagnosticsAvailability {
    /** No status fetched yet this session. */
    UNKNOWN,
    AVAILABLE,
    DISABLED,
    STORAGE_UNAVAILABLE,
    /** Status fetch failed (network); last-known limits may still be cached. */
    OFFLINE,
    /** Server answered with a status value this client doesn't understand. */
    UNAVAILABLE,
}

@Serializable
data class DiagnosticsIngestResult(
    @SerialName("report_id") val reportId: String,
    @SerialName("short_id") val shortId: String,
)
