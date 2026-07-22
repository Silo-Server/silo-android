package org.siloserver.silo.model.diagnostics

import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/**
 * Client-side mirror of the server's manifest/device/logline validation
 * (`silo-server/internal/diagnostics/contract/contract.go`). Run before every
 * upload so a malformed bundle fails locally with a named problem instead of
 * an opaque `invalid_bundle` rejection. String caps are byte lengths (UTF-8),
 * matching the server, not character counts.
 *
 * Returns a list of problems; empty means valid.
 */

private fun MutableList<String>.requireBytes(field: String, value: String, min: Int, max: Int) {
    val size = value.encodeToByteArray().size
    if (size < min) add("$field must be at least $min bytes")
    if (size > max) add("$field must be at most $max bytes")
}

private fun MutableList<String>.requireTimestamp(field: String, value: String) {
    if (value.length > 64) add("$field must be at most 64 characters")
    if (parseRfc3339ToEpochMillis(value) == null) add("$field is not an RFC3339 timestamp")
}

private val SHA256_HEX = Regex("^[A-Fa-f0-9]{64}$")

fun DiagnosticsManifest.validate(): List<String> {
    val problems = mutableListOf<String>()
    if (schemaVersion != DiagnosticsManifest.SCHEMA_VERSION) {
        problems.add("schema_version must be ${DiagnosticsManifest.SCHEMA_VERSION}")
    }

    with(report) {
        problems.requireTimestamp("report.captured_at", capturedAt)
        problems.requireBytes("report.capture_session_id", captureSessionId, 1, 128)
        problems.requireBytes("report.app_version", appVersion, 1, 64)
        problems.requireBytes("report.app_build", appBuild, 1, 64)
        problems.requireBytes("report.os_version", osVersion, 1, 128)
        profileId?.let { problems.requireBytes("report.profile_id", it, 1, 128) }
    }

    problems.requireBytes("destination.server_instance_id", destination.serverInstanceId, 1, 128)

    if (consent.noticeVersion < 1) problems.add("consent.notice_version must be >= 1")

    if (report.type == DiagnosticsReportType.MANUAL) {
        if (crash != null) problems.add("crash must be absent for manual reports")
    } else {
        val crash = crash
        if (crash == null) {
            problems.add("crash is required for ${report.type} reports")
        } else {
            problems.requireBytes("crash.summary", crash.summary, 1, 8192)
            crash.stackExcerpt?.let { problems.requireBytes("crash.stack_excerpt", it, 0, 8192) }
            crash.thread?.let { problems.requireBytes("crash.thread", it, 1, 128) }
            problems.requireTimestamp("crash.occurred_at", crash.occurredAt)
        }
    }

    with(deviceSummary) {
        problems.requireBytes("device_summary.manufacturer", manufacturer, 1, 128)
        problems.requireBytes("device_summary.model", model, 1, 128)
        problems.requireBytes("device_summary.os", os, 1, 128)
        problems.requireBytes("device_summary.form_factor", formFactor, 1, 64)
    }

    if (playbackSessionIds.size > 20) problems.add("playback_session_ids must have at most 20 items")
    playbackSessionIds.forEachIndexed { i, id ->
        problems.requireBytes("playback_session_ids[$i]", id, 1, 128)
    }

    with(logSummary) {
        if (lines < 0) problems.add("log_summary.lines must be >= 0")
        if (bytesGz < 0) problems.add("log_summary.bytes_gz must be >= 0")
        if (droppedLines < 0) problems.add("log_summary.dropped_lines must be >= 0")
        if (categories.size != categories.distinct().size) problems.add("log_summary.categories must be unique")
    }

    with(archive) {
        if (entries.isEmpty()) problems.add("archive.entries must not be empty")
        if (entries.size > DiagnosticsManifest.ARCHIVE_ENTRY_ALLOWLIST.size) {
            problems.add("archive.entries must have at most ${DiagnosticsManifest.ARCHIVE_ENTRY_ALLOWLIST.size} items")
        }
        if (entries.size != entries.distinct().size) problems.add("archive.entries must be unique")
        if (entries.firstOrNull() != "manifest.json") problems.add("archive.entries[0] must be manifest.json")
        entries.filterNot { it in DiagnosticsManifest.ARCHIVE_ENTRY_ALLOWLIST }.forEach {
            problems.add("archive entry not in allowlist: $it")
        }
        if (bytes < 0) problems.add("archive.bytes must be >= 0")
        if (uncompressedBytes < 0) problems.add("archive.uncompressed_bytes must be >= 0")
        if (!SHA256_HEX.matches(sha256)) problems.add("archive.sha256 must be 64 hex characters")
    }

    return problems
}

fun DiagnosticsDeviceSnapshot.validateShape(): List<String> {
    val problems = mutableListOf<String>()
    problems.requireTimestamp("captured_at", capturedAt)
    if (!identity.isTriStateObject()) problems.add("identity must be an object or unknown/not_collected")
    if (!display.isTriStateObject()) problems.add("display must be an object or unknown/not_collected")
    if (!audio.isTriStateObject()) problems.add("audio must be an object or unknown/not_collected")
    if (!videoCodecs.isTriStateArray()) problems.add("video_codecs must be an array or unknown/not_collected")
    if (!network.isTriStateObject()) problems.add("network must be an object or unknown/not_collected")
    return problems
}

fun DiagnosticsLogLine.validate(): List<String> {
    val problems = mutableListOf<String>()
    problems.requireTimestamp("ts", ts)
    problems.requireBytes("run", run, 1, 128)
    problems.requireBytes("tag", tag, 1, 128)
    problems.requireBytes("msg", msg, 1, 2048)
    return problems
}
