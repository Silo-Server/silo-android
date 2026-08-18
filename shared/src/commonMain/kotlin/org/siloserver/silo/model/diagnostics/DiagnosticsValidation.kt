package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.siloserver.silo.util.IsoDate
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

class DiagnosticsValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun decodeDiagnosticsManifest(raw: String): DiagnosticsManifest =
    decodeContract<DiagnosticsManifest>("manifest", raw).also(DiagnosticsManifest::validate)

fun decodeDiagnosticsDeviceSnapshot(raw: String): DiagnosticsDeviceSnapshot =
    decodeContract<DiagnosticsDeviceSnapshot>("device", raw).also(DiagnosticsDeviceSnapshot::validate)

fun decodeDiagnosticsLogLine(raw: String): DiagnosticsLogLine =
    decodeContract<DiagnosticsLogLine>("log line", raw).also(DiagnosticsLogLine::validate)

private inline fun <reified T> decodeContract(label: String, raw: String): T =
    try {
        DiagnosticsContractJson.decodeFromString(raw)
    } catch (error: SerializationException) {
        throw DiagnosticsValidationException("Invalid diagnostics $label: ${error.message}", error)
    } catch (error: IllegalArgumentException) {
        throw DiagnosticsValidationException("Invalid diagnostics $label: ${error.message}", error)
    }

fun DiagnosticsManifest.validate() {
    requireDiagnostics(schemaVersion == SCHEMA_VERSION, "manifest.schema_version", "unsupported value $schemaVersion")
    report.validate()
    destination.serverInstanceId.requireUtf8("manifest.destination.server_instance_id", 1, 128)
    requireDiagnostics(consent.noticeVersion >= 1, "manifest.consent.notice_version", "must be >= 1")

    when (report.type) {
        DiagnosticsReportType.MANUAL ->
            requireDiagnostics(crash == null, "manifest.crash", "must be absent for manual reports")
        else -> {
            val crashInfo = crash
                ?: throw DiagnosticsValidationException("manifest.crash: required")
            crashInfo.validate()
        }
    }

    deviceSummary.manufacturer.requireUtf8("manifest.device_summary.manufacturer", 1, 128)
    deviceSummary.model.requireUtf8("manifest.device_summary.model", 1, 128)
    deviceSummary.os.requireUtf8("manifest.device_summary.os", 1, 128)
    deviceSummary.formFactor.requireUtf8("manifest.device_summary.form_factor", 1, 64)

    requireDiagnostics(playbackSessionIds.size <= MAX_PLAYBACK_SESSION_IDS, "manifest.playback_session_ids", "must contain <= $MAX_PLAYBACK_SESSION_IDS items")
    playbackSessionIds.forEachIndexed { index, value ->
        value.requireUtf8("manifest.playback_session_ids[$index]", 1, 128)
    }

    logSummary.validate()
    archive.validate()
}

private fun DiagnosticsReport.validate() {
    capturedAt.requireTimestamp("manifest.report.captured_at")
    captureSessionId.requireUtf8("manifest.report.capture_session_id", 1, 128)
    appVersion.requireUtf8("manifest.report.app_version", 1, 64)
    appBuild.requireUtf8("manifest.report.app_build", 1, 64)
    osVersion.requireUtf8("manifest.report.os_version", 1, 128)
    profileId?.requireUtf8("manifest.report.profile_id", 0, 128)
}

private fun DiagnosticsCrashInfo.validate() {
    summary.requireUtf8("manifest.crash.summary", 1, MAX_CRASH_TEXT_BYTES)
    stackExcerpt?.requireUtf8("manifest.crash.stack_excerpt", 0, MAX_CRASH_TEXT_BYTES)
    thread?.requireUtf8("manifest.crash.thread", 0, 128)
    occurredAt.requireTimestamp("manifest.crash.occurred_at")
}

private fun DiagnosticsLogSummary.validate() {
    requireDiagnostics(lines >= 0, "manifest.log_summary.lines", "must be >= 0")
    requireDiagnostics(bytesGzip >= 0, "manifest.log_summary.bytes_gz", "must be >= 0")
    requireDiagnostics(droppedLines >= 0, "manifest.log_summary.dropped_lines", "must be >= 0")
    requireDiagnostics(categories.size <= DiagnosticsLogCategory.entries.size, "manifest.log_summary.categories", "contains too many items")
    requireDiagnostics(categories.distinct().size == categories.size, "manifest.log_summary.categories", "must be unique")
}

private fun DiagnosticsArchive.validate() {
    requireDiagnostics(entries.isNotEmpty(), "manifest.archive.entries", "must not be empty")
    requireDiagnostics(entries.size <= DiagnosticsArchive.ALLOWED_ENTRIES.size, "manifest.archive.entries", "contains too many items")
    requireDiagnostics(entries.first() == "manifest.json", "manifest.archive.entries[0]", "must be manifest.json")
    requireDiagnostics(entries.distinct().size == entries.size, "manifest.archive.entries", "must be unique")
    entries.forEachIndexed { index, entry ->
        entry.requireUtf8("manifest.archive.entries[$index]", 1, 64)
        requireDiagnostics(entry in DiagnosticsArchive.ALLOWED_ENTRIES, "manifest.archive.entries[$index]", "unsupported value $entry")
    }
    requireDiagnostics(bytes >= 0, "manifest.archive.bytes", "must be >= 0")
    requireDiagnostics(uncompressedBytes >= 0, "manifest.archive.uncompressed_bytes", "must be >= 0")
    requireDiagnostics(SHA256_PATTERN.matches(sha256), "manifest.archive.sha256", "must be 64 hexadecimal characters")
}

fun DiagnosticsDeviceSnapshot.validate() {
    capturedAt.requireTimestamp("device.captured_at")
    validateDeviceSection("device.identity", identity, expectsArray = false)
    validateDeviceSection("device.display", display, expectsArray = false)
    validateDeviceSection("device.audio", audio, expectsArray = false)
    validateDeviceSection("device.video_codecs", videoCodecs, expectsArray = true)
    validateDeviceSection("device.network", network, expectsArray = false)
}

private fun validateDeviceSection(path: String, value: JsonElement, expectsArray: Boolean) {
    if (value.isDeviceSentinel()) return
    if (expectsArray) {
        val array = value as? JsonArray
            ?: throw DiagnosticsValidationException("$path: must be an array or unknown/not_collected")
        requireDiagnostics(array.size <= MAX_DEVICE_ITEMS, path, "must contain <= $MAX_DEVICE_ITEMS items")
        array.forEachIndexed { index, item ->
            val itemObject = item as? JsonObject
                ?: throw DiagnosticsValidationException("$path[$index]: must be an object")
            validateDeviceObject("$path[$index]", itemObject, depth = 0)
        }
    } else {
        val objectValue = value as? JsonObject
            ?: throw DiagnosticsValidationException("$path: must be an object or unknown/not_collected")
        validateDeviceObject(path, objectValue, depth = 0)
    }
}

private fun validateDeviceObject(path: String, value: JsonObject, depth: Int) {
    requireDiagnostics(depth <= MAX_DEVICE_DEPTH, path, "is nested too deeply")
    requireDiagnostics(value.size <= MAX_DEVICE_PROPERTIES, path, "must contain <= $MAX_DEVICE_PROPERTIES properties")
    value.forEach { (key, child) ->
        key.requireUtf8("$path key", 1, 128)
        validateDeviceValue("$path.$key", child, depth + 1)
    }
}

private fun validateDeviceValue(path: String, value: JsonElement, depth: Int) {
    requireDiagnostics(depth <= MAX_DEVICE_DEPTH, path, "is nested too deeply")
    when (value) {
        JsonNull -> Unit
        is JsonObject -> validateDeviceObject(path, value, depth + 1)
        is JsonArray -> {
            requireDiagnostics(value.size <= MAX_DEVICE_ITEMS, path, "must contain <= $MAX_DEVICE_ITEMS items")
            value.forEachIndexed { index, child -> validateDeviceValue("$path[$index]", child, depth + 1) }
        }
        is JsonPrimitive -> {
            if (value.isString) {
                value.content.requireUtf8(path, 0, 512)
            } else {
                requireDiagnostics(
                    value.booleanOrNull != null ||
                        value.longOrNull != null ||
                        value.content.toDoubleOrNull()?.isFinite() == true,
                    path,
                    "must be a string, number, boolean, null, array, or object",
                )
            }
        }
    }
}

private fun JsonElement.isDeviceSentinel(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    if (!primitive.isString) return false
    return primitive.contentOrNull == DiagnosticsDeviceSentinel.UNKNOWN.wireValue ||
        primitive.contentOrNull == DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue
}

fun DiagnosticsLogLine.validate() {
    timestamp.requireTimestamp("log.ts")
    run.requireUtf8("log.run", 1, 128)
    tag.requireUtf8("log.tag", 1, 128)
    message.requireUtf8("log.msg", 1, 2_048)

    val registered = REGISTERED_ATTRIBUTES[category].orEmpty()
    attributes.orEmpty().forEach { (key, value) ->
        when (registered[key]) {
            DiagnosticsAttributeKind.STRING -> {
                val primitive = value as? JsonPrimitive
                requireDiagnostics(primitive?.isString == true, "log.attrs.$key", "must be a string")
                primitive!!.content.requireUtf8("log.attrs.$key", 0, 512)
            }
            DiagnosticsAttributeKind.INTEGER -> {
                val primitive = value as? JsonPrimitive
                requireDiagnostics(
                    primitive != null && !primitive.isString && primitive.longOrNull != null,
                    "log.attrs.$key",
                    "must be an integer",
                )
            }
            null -> Unit
        }
    }
}

fun DiagnosticsStatusResponse.validate() {
    serverInstanceId.requireUtf8("status.server_instance_id", 1, 128)
    requireDiagnostics(acceptedSchemaVersions.isNotEmpty(), "status.accepted_schema_versions", "must not be empty")
    requireDiagnostics(acceptedSchemaVersions.all { it > 0 }, "status.accepted_schema_versions", "must contain positive versions")
    requireDiagnostics(maxBundleBytes > 0, "status.max_bundle_bytes", "must be > 0")
    requireDiagnostics(maxManifestBytes > 0, "status.max_manifest_bytes", "must be > 0")
    requireDiagnostics(retentionDays > 0, "status.retention_days", "must be > 0")
    requireDiagnostics(consentNoticeVersion > 0, "status.consent_notice_version", "must be > 0")
}

private fun String.requireTimestamp(path: String) {
    requireUtf8(path, 1, 64)
    val parsed = parseRfc3339ToEpochMillis(this)
    requireDiagnostics(parsed != null, path, "must be RFC3339 date-time")
    val date = take(10)
    val canonicalDate = runCatching { IsoDate.fromEpochDay(IsoDate.toEpochDay(date)) }.getOrNull()
    requireDiagnostics(canonicalDate == date, path, "must contain a valid calendar date")
}

private fun String.requireUtf8(path: String, minBytes: Int, maxBytes: Int) {
    val byteCount = encodeToByteArray().size
    requireDiagnostics(byteCount >= minBytes, path, "must be at least $minBytes bytes")
    requireDiagnostics(byteCount <= maxBytes, path, "exceeds $maxBytes bytes")
}

private fun requireDiagnostics(condition: Boolean, path: String, message: String) {
    if (!condition) throw DiagnosticsValidationException("$path: $message")
}

internal enum class DiagnosticsAttributeKind { STRING, INTEGER }

/**
 * Mirror of the canonical client-diagnostics v1 attribute registry.
 *
 * This map must stay byte-for-byte equivalent to the vendored
 * `diagnostics/v1/attr-registry.json` contract fixture; the parity test in
 * `DiagnosticsAttributeRegistryParityTest` is the gate that enforces it.
 */
internal val REGISTERED_ATTRIBUTES = mapOf(
    DiagnosticsLogCategory.PLAYBACK to mapOf(
        "sink" to DiagnosticsAttributeKind.STRING,
        "fmt" to DiagnosticsAttributeKind.STRING,
        "decoder" to DiagnosticsAttributeKind.STRING,
        "width" to DiagnosticsAttributeKind.INTEGER,
        "height" to DiagnosticsAttributeKind.INTEGER,
        "hdr_mode" to DiagnosticsAttributeKind.STRING,
        "bitrate_kbps" to DiagnosticsAttributeKind.INTEGER,
        "dropped_frames" to DiagnosticsAttributeKind.INTEGER,
        "audio_underruns" to DiagnosticsAttributeKind.INTEGER,
        "session_id" to DiagnosticsAttributeKind.STRING,
        "play_method" to DiagnosticsAttributeKind.STRING,
        "reason" to DiagnosticsAttributeKind.STRING,
        "position_ms" to DiagnosticsAttributeKind.INTEGER,
    ),
    DiagnosticsLogCategory.FOCUS to mapOf(
        "target" to DiagnosticsAttributeKind.STRING,
        "action" to DiagnosticsAttributeKind.STRING,
    ),
    DiagnosticsLogCategory.NETWORK to mapOf(
        "method" to DiagnosticsAttributeKind.STRING,
        "path" to DiagnosticsAttributeKind.STRING,
        "status" to DiagnosticsAttributeKind.INTEGER,
        "duration_ms" to DiagnosticsAttributeKind.INTEGER,
        "outcome" to DiagnosticsAttributeKind.STRING,
        "error_code" to DiagnosticsAttributeKind.STRING,
        "attempt" to DiagnosticsAttributeKind.INTEGER,
    ),
    DiagnosticsLogCategory.LIFECYCLE to mapOf(
        "state" to DiagnosticsAttributeKind.STRING,
        "phase" to DiagnosticsAttributeKind.STRING,
        "duration_ms" to DiagnosticsAttributeKind.INTEGER,
        "outcome" to DiagnosticsAttributeKind.STRING,
        "reason" to DiagnosticsAttributeKind.STRING,
        "launch_type" to DiagnosticsAttributeKind.STRING,
    ),
    DiagnosticsLogCategory.CRASH to mapOf(
        "fingerprint" to DiagnosticsAttributeKind.STRING,
        "source" to DiagnosticsAttributeKind.STRING,
    ),
)

private const val SCHEMA_VERSION = 1
private const val MAX_CRASH_TEXT_BYTES = 8 * 1_024
private const val MAX_PLAYBACK_SESSION_IDS = 20
private const val MAX_DEVICE_PROPERTIES = 64
private const val MAX_DEVICE_ITEMS = 128
private const val MAX_DEVICE_DEPTH = 8
private val SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
private val DiagnosticsContractJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = false
}
