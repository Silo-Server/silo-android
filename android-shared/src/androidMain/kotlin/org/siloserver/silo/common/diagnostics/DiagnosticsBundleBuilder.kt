package org.siloserver.silo.common.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
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
    /** Already-sanitized members used only to safely reframe stale hosted consent. */
    val sanitizedEntries: Map<String, ByteArray> = emptyMap(),
)

interface DiagnosticsBundleBuilder {
    fun build(report: PendingReport, redactionTokens: List<String>): DiagnosticsBundle

    fun reframeHosted(
        cached: DiagnosticsBundle,
        consent: org.siloserver.silo.model.diagnostics.DiagnosticsConsent,
    ): DiagnosticsBundle = error("hosted consent reframing is unsupported")
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
        val hosted = report.binding.destinationKind == DiagnosticsDestinationKind.HOSTED
        val artifactEntries = CANONICAL_ARCHIVE_ORDER.drop(1).mapNotNull { path ->
            // ApplicationExitInfo tombstones are opaque protobuf bytes. They
            // cannot pass the hosted collector's textual privacy admission
            // boundary, so retain them only for self-hosted diagnostics.
            if (hosted && path == CRASH_TOMBSTONE_FILE) return@mapNotNull null
            val file = report.directory.resolve(path)
            if (!file.isFile) return@mapNotNull null
            require(file.isWithin(report.directory)) { "diagnostics artifact escapes report directory: $path" }
            val bytes = if (path in TEXT_ENTRIES) {
                sanitizeText(
                    path = path,
                    bytes = file.readBytes(),
                    tokens = tokens,
                    hosted = hosted,
                )
            } else {
                file.readBytes()
            }
            ArchiveEntry(path, bytes)
        }
        val sanitizedManifest = sanitizeManifest(report.manifest, tokens, hosted).let { manifest ->
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

        return finalize(sanitizedManifest, entries)
    }

    override fun reframeHosted(
        cached: DiagnosticsBundle,
        consent: org.siloserver.silo.model.diagnostics.DiagnosticsConsent,
    ): DiagnosticsBundle {
        require(cached.sanitizedEntries.isNotEmpty()) { "hosted sanitized evidence is unavailable" }
        require(cached.manifest.destination.serverInstanceId == HOSTED_DIAGNOSTICS_COLLECTOR_ID)
        require(cached.manifest.report.profileId == null)
        require(cached.manifest.playbackSessionIds.isEmpty())
        require(CRASH_TOMBSTONE_FILE !in cached.manifest.archive.entries)
        val reframedManifest = cached.manifest.copy(consent = consent)
        val embeddedManifest = JSON.encodeToJsonElement(DiagnosticsManifest.serializer(), reframedManifest)
            .jsonObject
            .jsonObjectWithoutArchive()
            .let(JSON::encodeToString)
            .encodeToByteArray()
        val entries = cached.manifest.archive.entries.map { name ->
            val bytes = if (name == MANIFEST_FILE) {
                embeddedManifest
            } else {
                checkNotNull(cached.sanitizedEntries[name]) { "missing sanitized hosted member: $name" }
            }
            ArchiveEntry(name, bytes)
        }
        return finalize(reframedManifest, entries)
    }

    private fun finalize(
        manifest: DiagnosticsManifest,
        entries: List<ArchiveEntry>,
    ): DiagnosticsBundle {
        val tarBytes = UstarWriter.write(entries)
        val gzipBytes = gzip(tarBytes)
        val externalManifest = manifest.copy(
            archive = DiagnosticsArchive(
                entries = entries.map(ArchiveEntry::name),
                bytes = gzipBytes.size.toLong(),
                uncompressedBytes = tarBytes.size.toLong(),
                sha256 = sha256Hex(gzipBytes),
            ),
        ).also(DiagnosticsManifest::validate)
        val externalManifestBytes = JSON.encodeToString(externalManifest).encodeToByteArray()
        return DiagnosticsBundle(
            externalManifest,
            externalManifestBytes,
            gzipBytes,
            entries.associate { entry -> entry.name to entry.bytes },
        )
    }

    private fun sanitizeManifest(
        manifest: DiagnosticsManifest,
        tokens: List<String>,
        hosted: Boolean,
    ): DiagnosticsManifest {
        val sanitized = JSON.encodeToJsonElement(DiagnosticsManifest.serializer(), manifest)
            .redact(tokens)
            .sanitizeHostedStringsIf(hosted)
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
                path.endsWith(".json") -> JSON.encodeToString(
                    JSON.parseToJsonElement(decoded)
                        .redact(tokens)
                        .stripHostedDeviceIdentifiersIf(hosted && path == DEVICE_FILE)
                        .normalizeHostedDeviceDecodersIf(hosted && path == DEVICE_FILE)
                        .sanitizeHostedStringsIf(hosted),
                )
                path.endsWith(".jsonl") -> redactJsonLines(decoded, tokens, hosted)
                else -> decoded.redact(tokens).sanitizeHostedTextIf(hosted)
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
                    if (hosted) element.toHostedDiagnosticsLogLine().sanitizeHostedStrings() else element
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
            ?.mapValues { (key, value) ->
                when {
                    category == "network" && key == "path" && value is JsonPrimitive && value.isString ->
                        JsonPrimitive(checkNotNull(value.contentOrNull).templateHostedPrivatePathSegments())
                    category == "playback" && key == "decoder" && value is JsonPrimitive && value.isString ->
                        JsonPrimitive(checkNotNull(value.contentOrNull).hostedDecoderFamily())
                    else -> value
                }
            }
            .orEmpty()
        if (filteredAttributes.isEmpty()) {
            output.remove("attrs")
        } else {
            output["attrs"] = JsonObject(filteredAttributes)
        }
        return JsonObject(output)
    }

    private fun JsonElement.redact(tokens: List<String>): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.redact(tokens) })
        is JsonArray -> JsonArray(map { value -> value.redact(tokens) })
        is JsonPrimitive -> if (isString) JsonPrimitive(checkNotNull(contentOrNull).redact(tokens)) else this
    }

    private fun JsonElement.sanitizeHostedStringsIf(hosted: Boolean): JsonElement =
        if (hosted) sanitizeHostedStrings() else this

    private fun JsonElement.stripHostedDeviceIdentifiersIf(strip: Boolean): JsonElement =
        if (strip) stripHostedDeviceIdentifiers() else this

    private fun JsonElement.normalizeHostedDeviceDecodersIf(normalize: Boolean): JsonElement =
        if (normalize) normalizeHostedDeviceDecoders() else this

    private fun JsonElement.normalizeHostedDeviceDecoders(): JsonElement {
        if (this !is JsonObject) return this
        val videoCodecs = this["video_codecs"] as? JsonArray ?: return this
        val normalizedCodecs = videoCodecs.map { codec ->
            if (codec !is JsonObject) return@map codec
            val decoderName = codec["decoder_name"] as? JsonPrimitive
            if (decoderName?.isString != true) return@map codec
            JsonObject(
                codec.toMutableMap().also { fields ->
                    fields["decoder_name"] = JsonPrimitive(
                        checkNotNull(decoderName.contentOrNull).hostedDecoderFamily(),
                    )
                },
            )
        }
        return JsonObject(toMutableMap().also { it["video_codecs"] = JsonArray(normalizedCodecs) })
    }

    private fun JsonElement.stripHostedDeviceIdentifiers(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries
                .filterNot { (key, _) -> key.normalizedPrivacyKey() in HOSTED_DEVICE_IDENTIFIER_KEYS }
                .associate { (key, value) -> key to value.stripHostedDeviceIdentifiers() },
        )
        is JsonArray -> JsonArray(map { value -> value.stripHostedDeviceIdentifiers() })
        is JsonPrimitive -> this
    }

    private fun String.normalizedPrivacyKey(): String =
        lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun String.hostedDecoderFamily(): String {
        val normalized = lowercase(Locale.ROOT)
        return when {
            normalized in HOSTED_DECODER_FAMILIES -> normalized
            normalized.startsWith("c2.android.") -> HOSTED_C2_PLATFORM_DECODER
            normalized.startsWith("c2.") -> HOSTED_C2_VENDOR_DECODER
            normalized.startsWith("omx.google.") || normalized.startsWith("omx.android.") ->
                HOSTED_OMX_PLATFORM_DECODER
            normalized.startsWith("omx.") -> HOSTED_OMX_VENDOR_DECODER
            else -> HOSTED_GENERIC_DECODER
        }
    }

    private fun JsonElement.sanitizeHostedStrings(): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.sanitizeHostedStrings() })
        is JsonArray -> JsonArray(map { value -> value.sanitizeHostedStrings() })
        is JsonPrimitive -> if (isString) JsonPrimitive(checkNotNull(contentOrNull).sanitizeHostedText()) else this
    }

    private fun String.redact(tokens: List<String>): String {
        var output = this
        tokens.forEach { token -> output = output.replace(token, REDACTED_VALUE) }
        return output
    }

    private fun String.sanitizeHostedTextIf(hosted: Boolean): String =
        if (hosted) sanitizeHostedText() else this

    private fun String.sanitizeHostedText(): String {
        var output = HOST_TOKEN.replace(this, REDACTED_HOST_VALUE)
        output = REDACTED_AUTHORITY.replace(output) { match ->
            "${match.groupValues[1]}$REDACTED_HOST_VALUE"
        }
        output = PRIVATE_IDENTIFIER_ASSIGNMENT.replace(output, "[REDACTED_PRIVATE_ID]")
        return HOSTED_AUTHORITY_URL.replace(output) { match -> sanitizeHostedUrl(match.value) }
    }

    private fun sanitizeHostedUrl(candidate: String): String {
        val trailing = candidate.takeLastWhile { it in TRAILING_URL_PUNCTUATION }
        val core = candidate.dropLast(trailing.length)
        val uri = runCatching { URI(core) }.getOrNull() ?: return candidate
        val host = uri.host ?: return candidate
        return runCatching {
            URI(
                uri.scheme,
                null,
                host,
                uri.port,
                ENCODED_ID_PLACEHOLDER.replace(uri.rawPath.orEmpty(), "{id}")
                    .templateHostedPrivatePathSegments(),
                null,
                null,
            ).toASCIIString()
                .replace("%7Bid%7D", "{id}", ignoreCase = true) + trailing
        }.getOrDefault(candidate)
    }

    private fun String.templateHostedPrivatePathSegments(): String = split('/')
        .joinToString("/") { segment ->
            if (
                UUID_PATH_SEGMENT.matches(segment) ||
                NUMERIC_ID_PATH_SEGMENT.matches(segment) ||
                HEX_ID_PATH_SEGMENT.matches(segment) ||
                OPAQUE_ID_PATH_SEGMENT.matches(segment)
            ) {
                "{id}"
            } else {
                segment
            }
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
        const val CRASH_TOMBSTONE_FILE = "crash/tombstone.pb"
        const val REDACTED_VALUE = "[REDACTED]"
        const val REDACTED_HOST_VALUE = "redacted.invalid"
        const val HOSTED_C2_PLATFORM_DECODER = "android-c2-platform-decoder"
        const val HOSTED_C2_VENDOR_DECODER = "android-c2-vendor-decoder"
        const val HOSTED_OMX_PLATFORM_DECODER = "android-omx-platform-decoder"
        const val HOSTED_OMX_VENDOR_DECODER = "android-omx-vendor-decoder"
        const val HOSTED_GENERIC_DECODER = "android-decoder"
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
        val HOSTED_DECODER_FAMILIES = setOf(
            HOSTED_C2_PLATFORM_DECODER,
            HOSTED_C2_VENDOR_DECODER,
            HOSTED_OMX_PLATFORM_DECODER,
            HOSTED_OMX_VENDOR_DECODER,
            HOSTED_GENERIC_DECODER,
        )
        val HOSTED_DEVICE_IDENTIFIER_KEYS = setOf(
            "id",
            "address",
            "routehash",
            "routehashes",
            "deviceid",
            "deviceaddress",
            "deviceidhash",
            "deviceaddresshash",
            "serial",
            "serialnumber",
            "imei",
            "meid",
            "mac",
            "macaddress",
            "ssid",
            "bssid",
            "ip",
            "ipaddress",
        )
        val PRIVATE_IDENTIFIER_ASSIGNMENT = Regex(
            """(?i)\b(playback[_-]?session[_-]?id|session[_-]?id|(?:plan|selected|effective|requested|media)?[_-]?file[_-]?id|item[_-]?id|media[_-]?id|plan[_-]?id|playback[_-]?attempt[_-]?id|plan[_-]?attempt[_-]?key|subtitle[_-]?id|track[_-]?id)\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'[^'\r\n]*'|[^\s,;)\]}]+)""",
        )
        val HOST_TOKEN = Regex("(?i)\\bhost_[0-9a-f]{16}\\b")
        val REDACTED_AUTHORITY = Regex("(?i)\\b((?:https?|wss?)://)\\[REDACTED]")
        val HOSTED_AUTHORITY_URL = Regex("(?i)\\b(?:https?|wss?)://[^\\s<>\\\"']+")
        val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val UUID_PATH_SEGMENT = Regex(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        val NUMERIC_ID_PATH_SEGMENT = Regex("^[0-9]+$")
        val HEX_ID_PATH_SEGMENT = Regex("(?i)^[0-9a-f]{16,}$")
        val OPAQUE_ID_PATH_SEGMENT = Regex("^[A-Za-z0-9_-]{20,}$")
        val ENCODED_ID_PLACEHOLDER = Regex("(?i)%(?:25)?7bid%(?:25)?7d")
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
