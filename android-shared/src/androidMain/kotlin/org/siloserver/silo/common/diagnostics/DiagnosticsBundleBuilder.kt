package org.siloserver.silo.common.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.validate

data class DiagnosticsBundle(
    val manifest: DiagnosticsManifest,
    val manifestBytes: ByteArray,
    val bytes: ByteArray,
)

interface DiagnosticsBundleBuilder {
    fun build(report: PendingReport, redactionTokens: List<String>): DiagnosticsBundle
}

val CANONICAL_ARCHIVE_ORDER = listOf(
    "manifest.json",
    "device.json",
    "logs.jsonl",
    "crash/summary.json",
    "crash/stack.txt",
    "crash/tombstone.pb",
    "crash/metrickit.json",
    "breadcrumbs.jsonl",
)

class FileDiagnosticsBundleBuilder : DiagnosticsBundleBuilder {
    override fun build(report: PendingReport, redactionTokens: List<String>): DiagnosticsBundle {
        val tokens = redactionTokens.filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
        val artifactEntries = CANONICAL_ARCHIVE_ORDER.drop(1).mapNotNull { path ->
            val file = report.directory.resolve(path)
            if (!file.isFile) return@mapNotNull null
            require(file.isWithin(report.directory)) { "diagnostics artifact escapes report directory: $path" }
            val bytes = if (path in TEXT_ENTRIES) {
                sanitizeText(
                    path = path,
                    bytes = file.readBytes(),
                    tokens = tokens,
                    hosted = report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED,
                )
            } else {
                file.readBytes()
            }
            ArchiveEntry(path, bytes)
        }
        val sanitizedManifest = sanitizeManifest(report.manifest, tokens).let { manifest ->
            val logs = artifactEntries.firstOrNull { it.name == LOGS_FILE }?.bytes
            manifest.copy(
                logSummary = DiagnosticsLogSummaryBuilder.build(
                    logBytes = logs,
                    droppedLines = manifest.logSummary.droppedLines,
                    debugLogging = manifest.logSummary.debugLogging,
                ),
            )
        }
        val embeddedManifest = JSON.encodeToJsonElement(DiagnosticsManifest.serializer(), sanitizedManifest)
            .jsonObject
            .jsonObjectWithoutArchive()
            .let(JSON::encodeToString)
            .encodeToByteArray()

        val entries = buildList {
            add(ArchiveEntry(MANIFEST_FILE, embeddedManifest))
            addAll(artifactEntries)
        }
        require(entries.any { it.name == DEVICE_FILE }) { "device.json is required" }

        val tarBytes = UstarWriter.write(entries)
        val gzipBytes = gzip(tarBytes)
        val externalManifest = sanitizedManifest.copy(
            archive = DiagnosticsArchive(
                entries = entries.map(ArchiveEntry::name),
                bytes = gzipBytes.size.toLong(),
                uncompressedBytes = tarBytes.size.toLong(),
                sha256 = sha256Hex(gzipBytes),
            ),
        ).also(DiagnosticsManifest::validate)
        val externalManifestBytes = JSON.encodeToString(externalManifest).encodeToByteArray()
        return DiagnosticsBundle(externalManifest, externalManifestBytes, gzipBytes)
    }

    private fun sanitizeManifest(
        manifest: DiagnosticsManifest,
        tokens: List<String>,
    ): DiagnosticsManifest {
        val sanitized = JSON.encodeToJsonElement(DiagnosticsManifest.serializer(), manifest).redact(tokens)
        val encoded = JSON.encodeToString(sanitized)
        check(tokens.none(encoded::contains)) { "manifest redaction could not be verified" }
        return JSON.decodeFromString(encoded)
    }

    private fun sanitizeText(
        path: String,
        bytes: ByteArray,
        tokens: List<String>,
        hosted: Boolean,
    ): ByteArray =
        runCatching {
            val decoded = checkNotNull(UTF8_DECODER.get()).decode(ByteBuffer.wrap(bytes)).toString()
            val sanitized = when {
                path.endsWith(".json") -> JSON.encodeToString(JSON.parseToJsonElement(decoded).redact(tokens))
                path.endsWith(".jsonl") -> redactJsonLines(decoded, tokens, hosted)
                else -> decoded.redact(tokens)
            }
            check(tokens.none(sanitized::contains)) { "artifact redaction could not be verified" }
            sanitized.encodeToByteArray()
        }.getOrElse { REDACTION_FAILURE_SENTINEL }

    private fun redactJsonLines(
        value: String,
        tokens: List<String>,
        hosted: Boolean,
    ): String {
        val hadTrailingNewline = value.endsWith('\n')
        val lines = value.split('\n').let { if (hadTrailingNewline) it.dropLast(1) else it }
        val redacted = lines.joinToString("\n") { line ->
            if (line.isBlank()) {
                line
            } else {
                val sanitized = JSON.parseToJsonElement(line).redact(tokens).let { element ->
                    if (hosted) element.toHostedDiagnosticsLogLine() else element
                }
                JSON.encodeToString(sanitized)
            }
        }
        return if (hadTrailingNewline) "$redacted\n" else redacted
    }

    private fun JsonElement.toHostedDiagnosticsLogLine(): JsonElement {
        if (this !is JsonObject) return this
        val output = toMutableMap()
        val category = output["cat"]?.jsonPrimitive?.contentOrNull
        val allowedAttributes = HOSTED_V1_LOG_ATTRIBUTES[category].orEmpty()
        val filteredAttributes = (output["attrs"] as? JsonObject)
            ?.filterKeys(allowedAttributes::contains)
            .orEmpty()
        if (filteredAttributes.isEmpty()) {
            output.remove("attrs")
        } else {
            output["attrs"] = JsonObject(filteredAttributes)
        }
        val message = output["msg"] as? JsonPrimitive
        if (message?.isString == true) {
            output["msg"] = JsonPrimitive(
                PRIVATE_PLAYBACK_ASSIGNMENT.replace(checkNotNull(message.contentOrNull)) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
                },
            )
        }
        return JsonObject(output)
    }

    private fun JsonElement.redact(tokens: List<String>): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.redact(tokens) })
        is JsonArray -> JsonArray(map { value -> value.redact(tokens) })
        is JsonPrimitive -> if (isString) JsonPrimitive(checkNotNull(contentOrNull).redact(tokens)) else this
    }

    private fun String.redact(tokens: List<String>): String {
        var output = this
        tokens.forEach { token -> output = output.replace(token, REDACTED_VALUE) }
        return output
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private data class ArchiveEntry(val name: String, val bytes: ByteArray)

    private object UstarWriter {
        fun write(entries: List<ArchiveEntry>): ByteArray = ByteArrayOutputStream().use { output ->
            entries.forEach { entry ->
                val header = header(entry)
                output.write(header)
                output.write(entry.bytes)
                val padding = (BLOCK_SIZE - entry.bytes.size % BLOCK_SIZE) % BLOCK_SIZE
                if (padding > 0) output.write(ByteArray(padding))
            }
            output.write(ByteArray(BLOCK_SIZE * 2))
            output.toByteArray()
        }

        private fun header(entry: ArchiveEntry): ByteArray {
            val nameBytes = entry.name.encodeToByteArray()
            require(nameBytes.size <= NAME_BYTES) { "USTAR entry name is too long: ${entry.name}" }
            require(entry.bytes.size.toLong() <= MAX_ENTRY_BYTES) { "USTAR entry is too large: ${entry.name}" }
            val header = ByteArray(BLOCK_SIZE)
            nameBytes.copyInto(header, destinationOffset = 0)
            writeOctal(header, MODE_OFFSET, MODE_LENGTH, FILE_MODE)
            writeOctal(header, UID_OFFSET, UID_LENGTH, 0)
            writeOctal(header, GID_OFFSET, GID_LENGTH, 0)
            writeOctal(header, SIZE_OFFSET, SIZE_LENGTH, entry.bytes.size.toLong())
            writeOctal(header, MTIME_OFFSET, MTIME_LENGTH, 0)
            repeat(CHECKSUM_LENGTH) { header[CHECKSUM_OFFSET + it] = ' '.code.toByte() }
            header[TYPE_OFFSET] = REGULAR_FILE_TYPE
            USTAR_MAGIC.copyInto(header, destinationOffset = MAGIC_OFFSET)
            USTAR_VERSION.copyInto(header, destinationOffset = VERSION_OFFSET)
            val checksum = header.sumOf { it.toUByte().toLong() }
            writeChecksum(header, checksum)
            return header
        }

        private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
            val encoded = value.toString(8).padStart(length - 1, '0').encodeToByteArray()
            require(encoded.size == length - 1) { "USTAR numeric field overflow" }
            encoded.copyInto(target, destinationOffset = offset)
            target[offset + length - 1] = 0
        }

        private fun writeChecksum(target: ByteArray, checksum: Long) {
            val encoded = checksum.toString(8).padStart(CHECKSUM_LENGTH - 2, '0').encodeToByteArray()
            require(encoded.size == CHECKSUM_LENGTH - 2) { "USTAR checksum overflow" }
            encoded.copyInto(target, destinationOffset = CHECKSUM_OFFSET)
            target[CHECKSUM_OFFSET + CHECKSUM_LENGTH - 2] = 0
            target[CHECKSUM_OFFSET + CHECKSUM_LENGTH - 1] = ' '.code.toByte()
        }

        private const val BLOCK_SIZE = 512
        private const val NAME_BYTES = 100
        private const val MODE_OFFSET = 100
        private const val MODE_LENGTH = 8
        private const val UID_OFFSET = 108
        private const val UID_LENGTH = 8
        private const val GID_OFFSET = 116
        private const val GID_LENGTH = 8
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val MTIME_OFFSET = 136
        private const val MTIME_LENGTH = 12
        private const val CHECKSUM_OFFSET = 148
        private const val CHECKSUM_LENGTH = 8
        private const val TYPE_OFFSET = 156
        private const val MAGIC_OFFSET = 257
        private const val VERSION_OFFSET = 263
        private const val FILE_MODE = 420L
        private const val REGULAR_FILE_TYPE = '0'.code.toByte()
        private const val MAX_ENTRY_BYTES = 8_589_934_591L
        private val USTAR_MAGIC = byteArrayOf('u'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), 0)
        private val USTAR_VERSION = byteArrayOf('0'.code.toByte(), '0'.code.toByte())
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val DEVICE_FILE = "device.json"
        const val LOGS_FILE = "logs.jsonl"
        const val REDACTED_VALUE = "[REDACTED]"
        val REDACTION_FAILURE_SENTINEL = "{\"redaction_failure\":true}\n".encodeToByteArray()
        val TEXT_ENTRIES = CANONICAL_ARCHIVE_ORDER.toSet() - MANIFEST_FILE - "crash/tombstone.pb"
        val HOSTED_V1_LOG_ATTRIBUTES = mapOf(
            "playback" to setOf(
                "sink",
                "fmt",
                "decoder",
                "width",
                "height",
                "hdr_mode",
                "bitrate_kbps",
                "dropped_frames",
                "audio_underruns",
            ),
            "focus" to setOf("target", "action"),
            "network" to setOf("method", "path", "status", "duration_ms"),
            "lifecycle" to setOf("state"),
            "crash" to setOf("fingerprint", "source"),
        )
        val PRIVATE_PLAYBACK_ASSIGNMENT = Regex(
            """(?i)\b(playback(?:[_\s-]?session)?[_\s-]?ids?)\s*([:=])\s*(?:"[^"]*"|'[^']*'|[^\s,;]+)""",
        )
        val JSON = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
        val UTF8_DECODER: ThreadLocal<java.nio.charset.CharsetDecoder> = ThreadLocal.withInitial {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        }
    }
}

private fun JsonObject.jsonObjectWithoutArchive(): JsonObject = JsonObject(this - "archive")

private fun File.isWithin(directory: File): Boolean {
    val rootPath = directory.canonicalFile.path
    val candidatePath = canonicalFile.path
    return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
}
