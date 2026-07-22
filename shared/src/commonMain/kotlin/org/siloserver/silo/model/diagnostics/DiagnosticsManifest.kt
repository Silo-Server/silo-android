package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Part-1 multipart manifest for `POST /api/v1/diagnostics/reports`, schema v1.
 *
 * The archive's embedded `manifest.json` entry must be this object **without**
 * the `archive` field ([DiagnosticsManifestDraft]) — the archive stats can only
 * be computed after the tar.gz is built, so construction is two-phase:
 * build a [DiagnosticsManifestDraft], embed it, then [DiagnosticsManifestDraft.finalized]
 * with the measured [Archive].
 */
@Serializable
data class DiagnosticsManifest(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    val report: Report,
    val destination: Destination,
    val consent: Consent,
    val crash: Crash? = null,
    @SerialName("device_summary") val deviceSummary: DeviceSummary,
    @SerialName("playback_session_ids") val playbackSessionIds: List<String>,
    @SerialName("log_summary") val logSummary: LogSummary,
    val archive: Archive,
) {
    @Serializable
    data class Report(
        val type: DiagnosticsReportType,
        @SerialName("captured_at") val capturedAt: String,
        @SerialName("capture_session_id") val captureSessionId: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("app_build") val appBuild: String,
        val platform: DiagnosticsPlatform,
        @SerialName("os_version") val osVersion: String,
        @SerialName("profile_id") val profileId: String? = null,
    )

    @Serializable
    data class Destination(
        @SerialName("server_instance_id") val serverInstanceId: String,
    )

    @Serializable
    data class Consent(
        val mode: DiagnosticsConsentMode,
        @SerialName("notice_version") val noticeVersion: Int,
    )

    @Serializable
    data class Crash(
        val summary: String,
        @SerialName("stack_excerpt") val stackExcerpt: String? = null,
        val thread: String? = null,
        val foreground: Boolean? = null,
        val source: DiagnosticsCrashSource,
        val provenance: DiagnosticsCrashProvenance,
        @SerialName("occurred_at") val occurredAt: String,
    )

    @Serializable
    data class DeviceSummary(
        val manufacturer: String,
        val model: String,
        val os: String,
        @SerialName("form_factor") val formFactor: String,
    )

    @Serializable
    data class LogSummary(
        val lines: Long,
        @SerialName("bytes_gz") val bytesGz: Long,
        @SerialName("dropped_lines") val droppedLines: Long,
        val categories: List<DiagnosticsLogCategory>,
        @SerialName("debug_logging") val debugLogging: Boolean,
    )

    @Serializable
    data class Archive(
        val entries: List<String>,
        val bytes: Long,
        @SerialName("uncompressed_bytes") val uncompressedBytes: Long,
        val sha256: String,
    )

    fun withoutArchive(): DiagnosticsManifestDraft = DiagnosticsManifestDraft(
        schemaVersion = schemaVersion,
        report = report,
        destination = destination,
        consent = consent,
        crash = crash,
        deviceSummary = deviceSummary,
        playbackSessionIds = playbackSessionIds,
        logSummary = logSummary,
    )

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * The fixed archive-entry allowlist, in canonical order. The server
         * rejects any entry outside this set and requires `manifest.json`
         * first; every entry name implies its media type.
         */
        val ARCHIVE_ENTRY_ALLOWLIST: List<String> = listOf(
            "manifest.json",
            "device.json",
            "logs.jsonl",
            "crash/summary.json",
            "crash/stack.txt",
            "crash/tombstone.pb",
            "crash/metrickit.json",
            "breadcrumbs.jsonl",
        )
    }
}

/** [DiagnosticsManifest] minus `archive` — the embedded archive copy and the pre-build draft. */
@Serializable
data class DiagnosticsManifestDraft(
    @SerialName("schema_version") val schemaVersion: Int = DiagnosticsManifest.SCHEMA_VERSION,
    val report: DiagnosticsManifest.Report,
    val destination: DiagnosticsManifest.Destination,
    val consent: DiagnosticsManifest.Consent,
    val crash: DiagnosticsManifest.Crash? = null,
    @SerialName("device_summary") val deviceSummary: DiagnosticsManifest.DeviceSummary,
    @SerialName("playback_session_ids") val playbackSessionIds: List<String>,
    @SerialName("log_summary") val logSummary: DiagnosticsManifest.LogSummary,
) {
    fun finalized(archive: DiagnosticsManifest.Archive): DiagnosticsManifest = DiagnosticsManifest(
        schemaVersion = schemaVersion,
        report = report,
        destination = destination,
        consent = consent,
        crash = crash,
        deviceSummary = deviceSummary,
        playbackSessionIds = playbackSessionIds,
        logSummary = logSummary,
        archive = archive,
    )
}
