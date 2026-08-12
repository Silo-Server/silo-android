package org.siloserver.silo.common.diagnostics

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
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
            DiagnosticsArchiveEntry(path, bytes)
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
            add(DiagnosticsArchiveEntry(MANIFEST_FILE, embeddedManifest))
            addAll(artifactEntries)
        }
        require(entries.any { it.name == DEVICE_FILE }) { "device.json is required" }

        return finalize(sanitizedManifest, entries, canonicalHostedGzip = hosted)
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
            DiagnosticsArchiveEntry(name, bytes)
        }
        return finalize(reframedManifest, entries, canonicalHostedGzip = true)
    }

    private fun finalize(
        manifest: DiagnosticsManifest,
        entries: List<DiagnosticsArchiveEntry>,
        canonicalHostedGzip: Boolean,
    ): DiagnosticsBundle {
        val archive = DiagnosticsArchiveEncoder.encode(entries, canonicalHostedGzip)
        val externalManifest = manifest.copy(
            archive = DiagnosticsArchive(
                entries = entries.map(DiagnosticsArchiveEntry::name),
                bytes = archive.bytes.size.toLong(),
                uncompressedBytes = archive.uncompressedBytes,
                sha256 = archive.sha256,
            ),
        ).also(DiagnosticsManifest::validate)
        val externalManifestBytes = JSON.encodeToString(externalManifest).encodeToByteArray()
        return DiagnosticsBundle(
            externalManifest,
            externalManifestBytes,
            archive.bytes,
            entries.associate { entry -> entry.name to entry.bytes },
        )
    }

    private fun sanitizeManifest(
        manifest: DiagnosticsManifest,
        tokens: List<String>,
        hosted: Boolean,
    ): DiagnosticsManifest {
        val redacted = JSON.encodeToJsonElement(DiagnosticsManifest.serializer(), manifest)
            .redact(tokens)
        val sanitized = if (hosted) redacted.sanitizeHostedManifestStrings() else redacted
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
                        .stripHostedForbiddenIdentifiersIf(hosted)
                        .stripHostedDeviceIdentifiersIf(hosted && path == DEVICE_FILE)
                        .stripHostedCrashIdentifiersIf(hosted && path == CRASH_SUMMARY_FILE)
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
                    if (hosted) element.toHostedDiagnosticsLogLine().sanitizeHostedLogLineStrings() else element
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

    private fun JsonElement.stripHostedForbiddenIdentifiersIf(strip: Boolean): JsonElement =
        if (strip) stripHostedForbiddenIdentifiers() else this

    private fun JsonElement.normalizeHostedDeviceDecodersIf(normalize: Boolean): JsonElement =
        if (normalize) normalizeHostedDeviceDecoders() else this

    private fun JsonElement.stripHostedCrashIdentifiersIf(strip: Boolean): JsonElement =
        if (strip && this is JsonObject) {
            JsonObject(filterKeys { key -> key.normalizedPrivacyKey() !in HOSTED_CRASH_IDENTIFIER_KEYS })
        } else {
            this
        }

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

    private fun JsonElement.stripHostedForbiddenIdentifiers(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries
                .filterNot { (key, _) ->
                    key.normalizedAssignmentKey().let { normalized ->
                        normalized in NETWORK_ASSIGNMENT_KEYS || normalized.hasSensitiveAssignmentKey()
                    }
                }
                .associate { (key, value) -> key to value.stripHostedForbiddenIdentifiers() },
        )
        is JsonArray -> JsonArray(map { value -> value.stripHostedForbiddenIdentifiers() })
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
        is JsonObject -> JsonObject(mapValues { (key, value) ->
            if (key.isStructuredTimestampKey() && value is JsonPrimitive && value.isString) {
                value
            } else {
                value.sanitizeHostedStrings()
            }
        })
        is JsonArray -> JsonArray(map { value -> value.sanitizeHostedStrings() })
        is JsonPrimitive -> if (isString) JsonPrimitive(checkNotNull(contentOrNull).sanitizeHostedText()) else this
    }

    private fun JsonElement.sanitizeHostedManifestStrings(): JsonElement {
        if (this !is JsonObject) return sanitizeHostedStrings()
        return JsonObject(
            mapValues { (key, value) ->
                if (key != "report" || value !is JsonObject) {
                    value.sanitizeHostedStrings()
                } else {
                    JsonObject(
                        value.mapValues { (reportKey, reportValue) ->
                            if (reportValue.isSafeHostedReleaseMetadata(reportKey)) {
                                reportValue
                            } else if (
                                reportKey == "capture_session_id" &&
                                reportValue is JsonPrimitive &&
                                reportValue.isString
                            ) {
                                JsonPrimitive(
                                    checkNotNull(reportValue.contentOrNull)
                                        .hostedAnonymousUuid(),
                                )
                            } else if (
                                reportKey.isStructuredTimestampKey() &&
                                reportValue is JsonPrimitive &&
                                reportValue.isString
                            ) {
                                reportValue
                            } else {
                                reportValue.sanitizeHostedStrings()
                            }
                        },
                    )
                }
            },
        )
    }

    private fun JsonElement.sanitizeHostedLogLineStrings(): JsonElement {
        if (this !is JsonObject) return sanitizeHostedStrings()
        return JsonObject(
            mapValues { (key, value) ->
                if (key == "run" && value is JsonPrimitive && value.isString) {
                    JsonPrimitive(
                        checkNotNull(value.contentOrNull)
                            .hostedAnonymousUuid(),
                    )
                } else if (key.isStructuredTimestampKey() && value is JsonPrimitive && value.isString) {
                    value
                } else {
                    value.sanitizeHostedStrings()
                }
            },
        )
    }

    private fun JsonElement.isSafeHostedReleaseMetadata(key: String): Boolean =
        this is JsonPrimitive && isString && when (key) {
            "app_version" -> checkNotNull(contentOrNull).matches(APP_VERSION_VALUE)
            "app_build" -> checkNotNull(contentOrNull).matches(APP_BUILD_VALUE)
            else -> false
        }

    private fun String.redact(tokens: List<String>): String {
        var output = this
        tokens.forEach { token -> output = output.replace(token, REDACTED_VALUE) }
        return output
    }

    private fun String.hostedAnonymousUuid(): String {
        if (CANONICAL_UUID.matches(this)) return lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
        val hexadecimal = digest.take(16).joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
        return "${hexadecimal.take(8)}-${hexadecimal.substring(8, 12)}-" +
            "${hexadecimal.substring(12, 16)}-${hexadecimal.substring(16, 20)}-" +
            hexadecimal.substring(20)
    }

    private fun String.sanitizeHostedTextIf(hosted: Boolean): String =
        if (hosted) sanitizeHostedText() else this

    private fun String.sanitizeHostedText(): String {
        val comparable = Normalizer.normalize(this, Normalizer.Form.NFKC)
            .replace('\u3002', '.')
            .replace('\uff0e', '.')
            .replace('\uff61', '.')
        var output = comparable
        output = SENSITIVE_ASSIGNMENT.replace(output) { match ->
            val key = match.groupValues[2].normalizedAssignmentKey()
            when {
                match.isCollectorSafeSourceLocationAssignment() -> match.value
                match.groupValues[2].matches(QUALIFIED_ERROR_TYPE) ||
                    match.groupValues[2].matches(QUALIFIED_ERROR_TERMINAL) ->
                    match.sanitizeQualifiedErrorMessage()
                key in NETWORK_ASSIGNMENT_KEYS -> REDACTED_NETWORK_IDENTITY
                key.hasSensitiveAssignmentKey() -> REDACTED_PRIVATE_ID
                else -> match.value
            }
        }
        output = LOOPBACK_IDENTITY.replace(output, REDACTED_HOST_VALUE)
        output = HOST_TOKEN.replace(output, REDACTED_HOST_VALUE)
        output = REDACTED_AUTHORITY.replace(output) { match ->
            "${match.groupValues[1]}$REDACTED_HOST_VALUE"
        }
        output = OBFUSCATED_STACK_FRAME.replace(output) { match ->
            val symbol = match.groupValues[2]
            if (symbol.isObfuscatedStackSymbol()) {
                "${match.groupValues[1]}$HOSTED_OBFUSCATED_FRAME"
            } else {
                match.value
            }
        }
        output = OBFUSCATED_ERROR_TYPE.replace(output) { match ->
            val symbol = match.groupValues[2]
            if (symbol.isObfuscatedErrorType()) {
                "${match.groupValues[1]}$HOSTED_OBFUSCATED_ERROR"
            } else {
                match.value
            }
        }
        output = HOSTED_AUTHORITY_URL.replace(output) { match -> sanitizeHostedUrl(match.value) }
        output = ANDROID_PRIVATE_PATH.replace(output, REDACTED_PRIVATE_ID)
        output = BEARER_TOKEN.replace(output, REDACTED_PRIVATE_ID)
        output = COLLECTOR_TOKEN.replace(output, REDACTED_PRIVATE_ID)
        output = JWT_TOKEN.replace(output, REDACTED_PRIVATE_ID)
        output = EMAIL_ADDRESS.replace(output, REDACTED_PRIVATE_ID)
        output = MAC_NETWORK_IDENTITY.replace(output, REDACTED_HOST_VALUE)
        output = LEGACY_LOOPBACK_NETWORK_IDENTITY.replace(output) { match ->
            if (match.value.isLegacyLoopbackAddress()) REDACTED_HOST_VALUE else match.value
        }
        output = IPV4_NETWORK_IDENTITY.replace(output) { match ->
            REDACTED_HOST_VALUE + match.validPortSuffix(groupIndex = 2)
        }
        output = BRACKETED_IPV6_NETWORK_IDENTITY.replace(output) { match ->
            REDACTED_HOST_VALUE + match.validPortSuffix(groupIndex = 2)
        }
        output = BARE_IPV6_NETWORK_IDENTITY.replace(output) { match ->
            if (match.value.count { it == ':' } >= 2) REDACTED_HOST_VALUE else match.value
        }
        output = BARE_PATH_IN_TEXT.replace(output) { match ->
            val path = match.groupValues[2]
            if (path.startsWith("//")) {
                match.value
            } else {
                match.groupValues[1] + sanitizeHostedPath(path)
            }
        }
        output = SENSITIVE_QUERY.replace(output, REDACTED_PRIVATE_ID)
        output = BARE_UUID_ANYWHERE.replace(output, REDACTED_PRIVATE_ID)
        output = COMPACT_UUID_ANYWHERE.replace(output, REDACTED_PRIVATE_ID)
        output = COMPACT_HEX_ID.replace(output, REDACTED_PRIVATE_ID)
        output = HIGH_CONFIDENCE_PRIVATE_ID.replace(output) { match ->
            if (match.value.lowercase(Locale.ROOT) in SAFE_SEMANTIC_TOKENS) match.value else REDACTED_PRIVATE_ID
        }
        if (output.hasUnsafeHostedResidue()) return HOSTED_UNSAFE_TEXT
        // NFKC is used for admission so full-width and alternate-dot bypasses
        // cannot hide identities. Preserve byte-for-byte diagnostic prose when
        // normalization did not reveal anything that needed rewriting.
        return if (output == comparable) this else output
    }

    private fun String.isObfuscatedStackSymbol(): Boolean {
        val labels = split('.')
        if (labels.size < 3) return false
        val owner = labels.getOrNull(labels.lastIndex - 1).orEmpty()
        return owner.firstOrNull()?.isLowerCase() == true
    }

    private fun String.isObfuscatedErrorType(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (
            lower == REDACTED_HOST_VALUE ||
            lower in CANONICAL_ARTIFACT_NAMES ||
            lower in CANONICAL_ARTIFACT_BASENAMES
        ) {
            return false
        }
        val terminal = substringAfterLast('.')
        if (terminal.matches(QUALIFIED_ERROR_TERMINAL)) return false
        if (startsWith("org.siloserver.silo.") && terminal.firstOrNull()?.isUpperCase() == true) return false
        return terminal.firstOrNull()?.isLowerCase() == true
    }

    private fun String.normalizedAssignmentKey(): String =
        replace(LOWER_CAMEL_KEY_BOUNDARY, "$1_$2")
            .replace(UPPER_CAMEL_KEY_BOUNDARY, "$1_$2")
            .replace(NON_KEY_CHARACTER, "_")
            .trim('_')
            .lowercase(Locale.ROOT)

    private fun String.isStructuredTimestampKey(): Boolean =
        normalizedAssignmentKey().let { normalized ->
            normalized == "ts" ||
                normalized == "timestamp" ||
                normalized == "captured_at" ||
                normalized == "occurred_at" ||
                normalized == "started_at" ||
                normalized == "ended_at"
        }

    private fun String.hasSensitiveAssignmentKey(): Boolean =
        SENSITIVE_ASSIGNMENT_KEYS.any { candidate ->
            this == candidate ||
                startsWith("${candidate}_") ||
                endsWith("_${candidate}") ||
                contains("_${candidate}_")
        } || replace("_", "") in SENSITIVE_ASSIGNMENT_COMPACT_KEYS ||
            split('_').any(CREDENTIAL_KEY_SEGMENTS::contains) ||
            substringAfterLast('_') in IDENTIFIER_KEY_SUFFIXES

    private fun String.hasUnsafeHostedResidue(): Boolean {
        if (RFC3339_UTC.matches(this)) return false
        val residue = APPROVED_HOSTED_AUTHORITY_URL.replace(this, "")
        if (residue.contains("//") || residue.contains('@') || residue.contains('\u0000')) return true
        if (UNAPPROVED_URI_SCHEME.containsMatchIn(residue)) return true
        if (BARE_PATH_IN_TEXT.findAll(residue).any { match ->
                val path = match.groupValues[2]
                !path.startsWith("//") && !path.isCollectorSafeHostedPath()
            }
        ) {
            return true
        }
        if (SENSITIVE_QUERY.containsMatchIn(residue)) return true
        if (SENSITIVE_ASSIGNMENT.findAll(residue).any { match ->
                !match.isCollectorSafeSourceLocationAssignment() &&
                    !match.groupValues[2].matches(QUALIFIED_ERROR_TYPE) &&
                    !match.groupValues[2].matches(QUALIFIED_ERROR_TERMINAL) &&
                    match.groupValues[2].normalizedAssignmentKey().hasSensitiveAssignmentKey()
            }
        ) {
            return true
        }
        if (
            LEGACY_HOST_TOKEN.containsMatchIn(residue) ||
            PRIVATE_SERVER_TOKEN.containsMatchIn(residue) ||
            MAC_NETWORK_IDENTITY.containsMatchIn(residue) ||
            BRACKETED_IPV6_NETWORK_IDENTITY.containsMatchIn(residue) ||
            BARE_IPV6_NETWORK_IDENTITY.findAll(residue).any { match -> match.value.count { it == ':' } >= 2 } ||
            residue.hasUnsafeHostWithPort() ||
            BARE_UUID_ANYWHERE.containsMatchIn(residue) ||
            COMPACT_UUID_ANYWHERE.containsMatchIn(residue) ||
            residue.hasUnsafeLegacyNetworkAddress() ||
            residue.hasUnsafeNetworkContext()
        ) {
            return true
        }
        return DOTTED_NETWORK_TOKEN.findAll(residue).any { match ->
            !match.value.isCollectorSafeDottedToken(residue, match.range)
        }
    }

    private fun String.hasUnsafeHostWithPort(): Boolean = HOST_WITH_PORT.findAll(this).any { match ->
        val label = match.groupValues[1]
        val port = match.groupValues[2].toIntOrNull() ?: return@any false
        if (port !in 1..65_535) return@any false
        label.normalizedAssignmentKey() !in SAFE_NUMERIC_ASSIGNMENT_KEYS &&
            label !in SAFE_SOURCE_LOCATION_LABELS
    }

    private fun MatchResult.isCollectorSafeSourceLocationAssignment(): Boolean {
        val label = groupValues.getOrNull(2).orEmpty()
        if (label !in SAFE_SOURCE_LOCATION_LABELS) return false
        val suffix = value.substringAfter(':', missingDelimiterValue = "")
            .ifEmpty { value.substringAfter('=', missingDelimiterValue = "") }
            .trim()
        return suffix.matches(SOURCE_LOCATION_LINE)
    }

    private fun MatchResult.sanitizeQualifiedErrorMessage(): String {
        val separator = value.indexOf(':')
        if (separator < 0) return value
        return value.take(separator + 1) + value.drop(separator + 1).sanitizeHostedText()
    }

    private fun String.hasUnsafeLegacyNetworkAddress(): Boolean =
        LEGACY_NETWORK_TOKEN.findAll(this).any { match ->
            val token = match.value
            val encoded = token.parseLegacyIpv4() ?: return@any false
            val components = token.split('.')
            val explicitLegacy = components.any { component ->
                component.startsWith("0x", ignoreCase = true) ||
                    (component.length > 1 && component.startsWith('0'))
            }
            val longInteger = components.size == 1 && token.matches(LONG_IPV4_INTEGER)
            explicitLegacy || longInteger || (components.size > 1 && encoded.isNonPublicIpv4())
        }

    private fun String.hasUnsafeNetworkContext(): Boolean {
        var networkContextRemaining = 0
        var networkContextWord = ""
        var numericNetworkContextRemaining = 0
        NETWORK_SCAN_TOKEN.findAll(this).forEach { match ->
            val token = match.value.trimEnd('.', '-').lowercase(Locale.ROOT)
            if (token == REDACTED_HOST_VALUE) {
                networkContextRemaining = 0
                networkContextWord = ""
                numericNetworkContextRemaining = 0
                return@forEach
            }
            if (token in NUMERIC_NETWORK_CONTEXT_WORDS) {
                numericNetworkContextRemaining = NETWORK_CONTEXT_WINDOW
                return@forEach
            }
            if (numericNetworkContextRemaining > 0) {
                if (token in NETWORK_CONNECTOR_WORDS) {
                    numericNetworkContextRemaining -= 1
                    return@forEach
                }
                if (token.parseLegacyIpv4() != null) return true
                numericNetworkContextRemaining = 0
            }
            if (token in NETWORK_CONTEXT_WORDS) {
                networkContextRemaining = NETWORK_CONTEXT_WINDOW
                networkContextWord = token
                return@forEach
            }
            if (networkContextRemaining > 0) {
                if (token in NETWORK_CONNECTOR_WORDS) {
                    networkContextRemaining -= 1
                    return@forEach
                }
                if (token in NETWORK_NON_HOST_WORDS) {
                    networkContextRemaining = 0
                    networkContextWord = ""
                    return@forEach
                }
                if (networkContextWord == "server" && token in SERVER_STATE_WORDS) {
                    networkContextRemaining = 0
                    networkContextWord = ""
                    return@forEach
                }
                if (networkContextWord == "backend" && token in SAFE_BACKEND_VALUES) {
                    networkContextRemaining = 0
                    networkContextWord = ""
                    return@forEach
                }
                if (
                    token.parseLegacyIpv4() != null ||
                    token.matches(NETWORK_LABEL_TOKEN)
                ) {
                    return true
                }
                networkContextRemaining = 0
                networkContextWord = ""
            }
        }
        return false
    }

    private fun String.isCollectorSafeDottedToken(context: String, range: IntRange): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower == REDACTED_HOST_VALUE) return true
        if (lower in CANONICAL_ARTIFACT_NAMES || lower in CANONICAL_ARTIFACT_BASENAMES) return true
        if (lower in SAFE_DOTTED_TELEMETRY_KEYS || lower in SAFE_DOTTED_SETTING_KEYS) return true
        if (lower in KNOWN_NATIVE_LIBRARIES) return true
        if (lower in SAFE_MEDIA_DOTTED_SUBTYPES) return true
        if (lower in SAFE_MEDIA_RESOURCE_NAMES && context.getOrNull(range.first - 1) == '/') return true
        if (all { character -> character.isDigit() || character == '.' }) return true
        if (matches(RFC3339_UTC)) return true
        if (matches(QUALIFIED_ERROR_TYPE)) return true
        if (startsWith("org.siloserver.silo.") && substringAfterLast('.').firstOrNull()?.isUpperCase() == true) {
            return true
        }
        val after = context.getOrNull(range.last + 1)
        if (after == '(' && matches(QUALIFIED_STACK_SYMBOL)) return true
        if (
            after == ':' &&
            context.drop(range.last + 2).takeWhile(Char::isDigit).isNotEmpty() &&
            matches(SOURCE_FILE_TOKEN)
        ) {
            return true
        }
        return false
    }

    private fun String.parseLegacyIpv4(): Long? {
        val components = split('.')
        if (components.size !in 1..4) return null
        val values = components.map { component ->
            when {
                component.isEmpty() || component.length > 16 -> null
                component.startsWith("0x", ignoreCase = true) ->
                    component.drop(2).takeIf(String::isNotEmpty)?.toLongOrNull(16)
                component.length > 1 && component.startsWith('0') -> component.drop(1).toLongOrNull(8)
                else -> component.toLongOrNull()
            }
        }
        if (values.any { it == null }) return null
        val concrete = values.map(::checkNotNull)
        if (concrete.dropLast(1).any { it > 0xff }) return null
        val lastLimit = when (concrete.size) {
            1 -> 0xffff_ffffL
            2 -> 0xff_ffffL
            3 -> 0xffffL
            else -> 0xffL
        }
        if (concrete.last() > lastLimit) return null
        return concrete.dropLast(1).foldIndexed(concrete.last()) { index, result, component ->
            result + (component shl (24 - index * 8))
        }
    }

    private fun Long.isNonPublicIpv4(): Boolean {
        val first = (this ushr 24).toInt()
        val second = ((this ushr 16) and 0xff).toInt()
        return first == 0 || first == 10 ||
            (first == 100 && second in 64..127) ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && (second == 0 || second == 168)) ||
            (first == 198 && second in 18..19) ||
            first >= 224
    }

    private fun String.isLegacyLoopbackAddress(): Boolean {
        val components = split('.')
        val parsed = components.map { component ->
            when {
                component.startsWith("0x", ignoreCase = true) -> component.drop(2).toLongOrNull(16)
                component.length > 1 && component.startsWith('0') -> component.drop(1).toLongOrNull(8)
                else -> component.toLongOrNull()
            }
        }
        if (parsed.any { it == null }) return false
        val values = parsed.map(::checkNotNull)
        val encoded = when (values.size) {
            1 -> values[0].takeIf { it <= 0xffff_ffffL }
            2 -> if (values[0] <= 0xff && values[1] <= 0xff_ffff) {
                (values[0] shl 24) or values[1]
            } else {
                null
            }
            3 -> if (values[0] <= 0xff && values[1] <= 0xff && values[2] <= 0xffff) {
                (values[0] shl 24) or (values[1] shl 16) or values[2]
            } else {
                null
            }
            4 -> if (values.all { it <= 0xff }) {
                (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
            } else {
                null
            }
            else -> null
        } ?: return false
        return (encoded ushr 24) == 127L
    }

    private fun MatchResult.validPortSuffix(groupIndex: Int): String =
        groupValues.getOrNull(groupIndex)
            ?.takeIf(String::isNotEmpty)
            ?.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?.let { ":$it" }
            .orEmpty()

    private fun sanitizeHostedUrl(candidate: String): String {
        val trailing = candidate.takeLastWhile { it in TRAILING_URL_PUNCTUATION }
        val core = candidate.dropLast(trailing.length)
        if (core.isCollectorSafeHostedAuthority()) return candidate
        val scheme = core.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (scheme !in HOSTED_URL_SCHEMES) return candidate
        val uri = runCatching { URI(core) }.getOrNull()
            ?: return "$scheme://$REDACTED_HOST_VALUE/redacted$trailing"
        uri.host ?: return "$scheme://$REDACTED_HOST_VALUE/redacted$trailing"
        val port = uri.port.takeIf { it in 1..65_535 } ?: -1
        val rawPath = uri.rawPath.orEmpty()
        val sanitizedPath = sanitizeHostedPath(rawPath).let { path ->
            if (path == REDACTED_PRIVATE_ID) "/redacted" else path
        }
        return runCatching {
            URI(
                uri.scheme,
                null,
                REDACTED_HOST_VALUE,
                port,
                sanitizedPath,
                null,
                null,
            ).toASCIIString()
                .replace("%7Bid%7D", "{id}", ignoreCase = true) + trailing
        }.getOrDefault(candidate)
    }

    private fun String.isCollectorSafeHostedAuthority(): Boolean {
        val scheme = substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (scheme !in HOSTED_URL_SCHEMES) return false
        val remainder = substringAfter("://", missingDelimiterValue = "")
        if (remainder.isEmpty() || '?' in remainder || '#' in remainder || '@' in remainder) return false
        val authority = remainder.substringBefore('/')
        val host = authority.substringBefore(':')
        if (!host.equals(REDACTED_HOST_VALUE, ignoreCase = false)) return false
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        if (port.isNotEmpty() && (port.toIntOrNull() !in 1..65_535)) return false
        val path = remainder.substringAfter('/', missingDelimiterValue = "")
        return "/$path".isCollectorSafeHostedPath()
    }

    private fun sanitizeHostedPath(candidate: String): String {
        if (candidate.isCollectorSafeHostedPath()) return candidate
        if (
            candidate.contains('?') ||
            candidate.contains('#') ||
            candidate.contains('%') ||
            candidate.startsWith("/users/", ignoreCase = true) ||
            candidate.startsWith("/private/", ignoreCase = true) ||
            candidate.startsWith("/var/mobile/", ignoreCase = true) ||
            candidate.startsWith("/data/user/", ignoreCase = true) ||
            candidate.startsWith("/data/", ignoreCase = true) ||
            candidate.startsWith("/storage/", ignoreCase = true) ||
            candidate.startsWith("/sdcard/", ignoreCase = true) ||
            candidate.startsWith("/mnt/", ignoreCase = true) ||
            candidate.startsWith("/system/", ignoreCase = true) ||
            candidate.startsWith("/vendor/", ignoreCase = true) ||
            candidate.startsWith("/apex/", ignoreCase = true) ||
            candidate.startsWith("/proc/", ignoreCase = true) ||
            candidate.startsWith("/dev/", ignoreCase = true) ||
            candidate.startsWith("/Users/", ignoreCase = true)
        ) {
            return REDACTED_PRIVATE_ID
        }
        val templated = candidate.templateHostedPrivatePathSegments()
        return if (templated.isCollectorSafeHostedPath()) templated else REDACTED_PRIVATE_ID
    }

    private fun String.isCollectorSafeHostedPath(): Boolean {
        if ('?' in this || '#' in this || '%' in this) return false
        val lower = lowercase(Locale.ROOT)
        if (
            lower.startsWith("/users/") ||
            lower.startsWith("/private/") ||
            lower.startsWith("/var/mobile/") ||
            lower.startsWith("/data/user/")
        ) {
            return false
        }
        return split('/').all { rawSegment ->
            if (rawSegment.isEmpty()) return@all true
            val segment = rawSegment.trim(*PATH_SEGMENT_PUNCTUATION)
            if (segment.isEmpty() || TEMPLATE_SEGMENT.matches(segment) || SAFE_VERSION_SEGMENT.matches(segment)) {
                return@all true
            }
            val candidates = listOf(segment) + segment.split(PATH_CANDIDATE_DELIMITER)
            candidates.none { value ->
                CANONICAL_UUID.containsMatchIn(value) ||
                    value.matches(NUMERIC_ID_PATH_SEGMENT) ||
                    value.matches(HEX_ID_PATH_SEGMENT) ||
                    value.matches(OPAQUE_ID_PATH_SEGMENT) ||
                    value.matches(PRIVATE_ID_PATH_SEGMENT)
            }
        }
    }

    private fun String.templateHostedPrivatePathSegments(): String = split('/')
        .joinToString("/") { segment ->
            if (
                UUID_PATH_SEGMENT.matches(segment) ||
                NUMERIC_ID_PATH_SEGMENT.matches(segment) ||
                HEX_ID_PATH_SEGMENT.matches(segment) ||
                OPAQUE_ID_PATH_SEGMENT.matches(segment) ||
                BARE_UUID.containsMatchIn(segment) ||
                segment.containsHighConfidencePrivateId()
            ) {
                "{id}"
            } else {
                segment
            }
        }

    private fun String.containsHighConfidencePrivateId(): Boolean =
        HIGH_CONFIDENCE_PRIVATE_ID.findAll(this).any { match ->
            match.value.lowercase(Locale.ROOT) !in SAFE_SEMANTIC_TOKENS
        }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val DEVICE_FILE = "device.json"
        const val CRASH_SUMMARY_FILE = "crash/summary.json"
        const val LOGS_FILE = "logs.jsonl"
        const val CRASH_TOMBSTONE_FILE = "crash/tombstone.pb"
        const val REDACTED_VALUE = "[REDACTED]"
        const val REDACTED_HOST_VALUE = "redacted.invalid"
        const val REDACTED_NETWORK_IDENTITY = "[redacted_network_identity]"
        const val REDACTED_PRIVATE_ID = "[redacted_private_id]"
        const val HOSTED_UNSAFE_TEXT = "[redacted_private_id]"
        const val HOSTED_C2_PLATFORM_DECODER = "android-c2-platform-decoder"
        const val HOSTED_C2_VENDOR_DECODER = "android-c2-vendor-decoder"
        const val HOSTED_OMX_PLATFORM_DECODER = "android-omx-platform-decoder"
        const val HOSTED_OMX_VENDOR_DECODER = "android-omx-vendor-decoder"
        const val HOSTED_GENERIC_DECODER = "android-decoder"
        const val HOSTED_OBFUSCATED_FRAME = "android-obfuscated-frame"
        const val HOSTED_OBFUSCATED_ERROR = "android-obfuscated-error"
        val REDACTION_FAILURE_SENTINEL = "{\"redaction_failure\":true}\n".encodeToByteArray()
        val HOSTED_URL_SCHEMES = setOf("http", "https", "ws", "wss")
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
        val APP_VERSION_VALUE = Regex("^[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9._-]+)?$")
        val APP_BUILD_VALUE = Regex("^[0-9]+$")
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
            "buildfingerprinthash",
            "host",
            "hostname",
            "server",
            "serverurl",
            "baseurl",
            "origin",
            "originurl",
            "endpoint",
            "endpointurl",
            "url",
        )
        val HOSTED_FORBIDDEN_IDENTIFIER_KEYS = setOf(
            "account", "accountid", "user", "userid", "email", "profile", "profileid",
            "profiletoken", "cookie", "authorization", "password", "passwd", "secret",
            "apikey", "credential", "privatekey", "authtoken", "accesstoken", "refreshtoken",
            "clientsecret", "uploadtoken", "serverurl", "baseurl", "originurl", "hostname",
            "deviceid", "devicename", "devicetoken", "deviceidentifier", "serial", "serialnumber",
            "imei", "meid", "androidid", "advertisingid", "advertisingidentifier", "adid", "aaid",
            "gaid", "vendorid", "vendoridentifier", "identifierforvendor", "idfa", "idfv", "mac",
            "macaddress", "ip", "ipaddress", "ssid", "bssid", "uidhash", "routehash", "routehashes",
        )
        val HOSTED_CRASH_IDENTIFIER_KEYS = setOf("process", "processname", "processhash")
        val NETWORK_ASSIGNMENT_KEYS = setOf(
            "host", "hostname", "server", "server_url", "base_url", "origin", "origin_url",
            "endpoint", "endpoint_url", "address", "url", "peer", "ip", "ip_address", "mac",
            "mac_address", "ssid", "bssid",
        )
        val SENSITIVE_ASSIGNMENT_KEYS = setOf(
            "account", "account_id", "user", "user_id", "email", "profile", "profile_id",
            "profile_token", "cookie", "authorization", "auth", "bearer", "password", "passwords",
            "pass", "passwd", "passphrase", "pwd", "secret", "secrets", "api_key", "apikey",
            "credential", "credentials", "passcode", "pin", "otp", "private_key", "auth_token", "access_token", "refresh_token",
            "client_secret", "upload_token", "server_url", "base_url", "origin_url", "hostname",
            "device_id", "device_name", "device_token", "device_identifier", "serial", "serial_number",
            "imei", "meid", "android_id", "advertising_id", "advertising_identifier", "ad_id", "aaid",
            "gaid", "vendor_id", "vendor_identifier", "identifier_for_vendor", "idfa", "idfv", "mac",
            "mac_address", "ip", "ip_address", "ssid", "bssid", "uid_hash", "route_hash", "route_hashes",
            "capture_session_id", "session", "session_id", "playback", "playback_id", "playback_session",
            "playback_session_id", "playback_session_ids", "file", "file_id", "selected_file_id",
            "effective_file_id", "requested_file_id", "selected_media_file_id", "effective_media_file_id",
            "requested_media_file_id", "media", "media_id", "media_file", "media_file_id", "item", "item_id",
            "content_id", "content_identifier", "library_id", "library_identifier", "plan", "plan_id",
            "plan_attempt", "plan_attempt_id", "plan_attempt_key", "attempt", "attempt_id", "playback_attempt",
            "playback_attempt_id", "subtitle", "subtitle_id", "track", "track_id", "request_id", "req_id",
            "request_identifier", "req_identifier", "request_uuid", "req_uuid", "correlation_id",
            "correlation_identifier", "correlation_uuid", "trace", "trace_id", "traceparent", "trace_parent",
            "span", "span_id", "transaction_id", "transaction_identifier", "server", "server_id",
            "server_instance", "server_instance_id", "peer", "host", "jwt", "key", "login", "origin",
            "endpoint", "address", "sig", "signature", "token", "tokens", "url", "username",
        )
        val SENSITIVE_ASSIGNMENT_COMPACT_KEYS =
            SENSITIVE_ASSIGNMENT_KEYS.mapTo(mutableSetOf()) { key -> key.replace("_", "") }
        val CREDENTIAL_KEY_SEGMENTS = setOf(
            "auth", "authorization", "bearer", "credential", "credentials", "jwt", "key", "login",
            "otp", "pass", "passcode", "passphrase", "passwd", "password", "passwords", "pin",
            "secret", "secrets", "sig", "signature", "token", "tokens", "username",
        )
        val IDENTIFIER_KEY_SUFFIXES = setOf("id", "ids", "identifier", "identifiers", "uuid", "uuids")
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)(?<![a-z0-9_.\-'\"])([\"']?)([a-z0-9_.-]{1,96})\1(?![a-z0-9_.-])\s*[:=]\s*(?:\"(?:\\.|[^\"\\\r\n])*\"|'(?:\\.|[^'\\\r\n])*'|(?:https?|wss?)://\[[^\]\r\n]+\](?::\d+)?[^\s,;)\]}]*|\[[^\]\r\n]+\](?::\d+)?|[^\s,;)\]}]+)""",
        )
        val LOWER_CAMEL_KEY_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        val UPPER_CAMEL_KEY_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
        val NON_KEY_CHARACTER = Regex("[^A-Za-z0-9]+")
        const val NETWORK_IDENTITY_KEYS =
            """host(?:[._-]?name)?|server(?:[._-]?url)?|base[._-]?url|origin(?:[._-]?url)?|endpoint(?:[._-]?url)?|address|url"""
        val NETWORK_IDENTITY_ASSIGNMENT = Regex(
            """(?i)(?<![a-z0-9_.\-'\"])[\"']?(?:$NETWORK_IDENTITY_KEYS)[\"']?(?![a-z0-9_.-])\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|(?:https?|wss?)://\[[^\]\r\n]+\](?::\d+)?[^\s,;)\]}]*|\[[^\]\r\n]+\](?::\d+)?|[^\s,;)\]}]+)""",
        )
        val LOOPBACK_IDENTITY = Regex(
            """(?i)(?:(?<![a-z0-9_.-])localhost(?![a-z0-9_-]|\.[a-z0-9])|(?<![a-z0-9_.-])127\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|0?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|0?[0-9]{1,2})\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|0?[0-9]{1,2})(?![a-z0-9_-]|\.[a-z0-9])|(?<![a-z0-9_.:-])\[::1\](?![a-z0-9_.-])|(?<![a-z0-9_:\[])::1(?![a-z0-9_:\]]|\.[a-z0-9]))""",
        )
        const val PRIVATE_IDENTIFIER_KEYS =
            """playback[_-]?session[_-]?id|session[_-]?id|(?:plan|selected|effective|requested|media)?[_-]?file[_-]?id|item[_-]?id|media[_-]?id|plan[_-]?id|playback[_-]?attempt[_-]?id|plan[_-]?attempt[_-]?key|subtitle[_-]?id|track[_-]?id"""
        val PRIVATE_IDENTIFIER_ASSIGNMENT = Regex(
            """(?i)(?<![a-z0-9_.\-'\"])[\"']?(?:$PRIVATE_IDENTIFIER_KEYS)[\"']?(?![a-z0-9_.-])\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^\s,;)\]}]+)""",
        )
        val BARE_UUID = Regex(
            """(?i)(?<![a-z0-9])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![a-z0-9])""",
        )
        val BARE_UUID_ANYWHERE = Regex(
            """(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""",
        )
        val CANONICAL_UUID = Regex(
            """(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
        )
        val COMPACT_UUID_ANYWHERE = Regex("(?i)[0-9a-f]{32}")
        val COMPACT_HEX_ID = Regex("""(?i)(?<![a-z0-9])[a-f0-9]{16,}(?![a-z0-9])""")
        const val HIGH_CONFIDENCE_PRIVATE_ID_PREFIXES =
            """ps|playback|session|file|item|media|plan|attempt|profile|account|user|device|content|library|request|req|correlation|server|subtitle|track|run"""
        val HIGH_CONFIDENCE_PRIVATE_ID = Regex(
            """(?i)(?<![a-z0-9_])(?:$HIGH_CONFIDENCE_PRIVATE_ID_PREFIXES)[_-](?:[0-9]+|[a-z0-9_-]{8,})(?![a-z0-9_-])""",
        )
        val SAFE_SEMANTIC_TOKENS = setOf(
            "request_cancelled",
            "request_completed",
            "session_unavailable",
            "playback_unavailable",
            "file_not_found",
            "plan_invalidated",
            "item_count",
        )
        val OBFUSCATED_STACK_FRAME = Regex(
            """(?m)^([ \t]*at[ \t]+)([A-Za-z_$][A-Za-z0-9_$]*(?:\.(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>))+)(?=\()""",
        )
        val OBFUSCATED_ERROR_TYPE = Regex(
            """(?im)^([ \t]*(?:(?:caused[ \t]+by|suppressed):?[ \t]+)?)([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+)(?=[ \t]*(?::|$))""",
        )
        val QUALIFIED_ERROR_TERMINAL = Regex("^[A-Z][A-Za-z0-9_$]*(?:Exception|Error)$")
        val QUALIFIED_ERROR_TYPE = Regex(
            "^[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*\\.[A-Z][A-Za-z0-9_$]*(?:Exception|Error)$",
        )
        val QUALIFIED_STACK_SYMBOL = Regex(
            "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+\\.[a-z_$][A-Za-z0-9_$]*$",
        )
        val SOURCE_FILE_TOKEN = Regex("^[A-Z][A-Za-z0-9_$-]*\\.(?:c|cc|cpp|h|java|kt|m|mm|swift)$")
        val HOST_TOKEN = Regex("(?i)\\bhost_[0-9a-f]{16}\\b")
        val REDACTED_AUTHORITY = Regex("(?i)\\b((?:https?|wss?)://)\\[REDACTED]")
        val HOSTED_AUTHORITY_URL = Regex("(?i)\\b(?:https?|wss?)://[^\\s<>\\\"']+")
        val APPROVED_HOSTED_AUTHORITY_URL = Regex(
            """(?i)\b(?:https?|wss?)://redacted\.invalid(?::(?:[1-9][0-9]{0,4}))?(?:/[^\s<>\"'?#]*)?""",
        )
        val UNAPPROVED_URI_SCHEME = Regex("(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]{0,31}://")
        val BEARER_TOKEN = Regex("(?i)(?<![a-z0-9_])bearer[ \\t]+[a-z0-9._~+/-]{6,}=*")
        val COLLECTOR_TOKEN = Regex("(?i)(?<![a-z0-9_])sid[tu]_[a-z0-9_-]{43,64}(?![a-z0-9_-])")
        val JWT_TOKEN = Regex(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}(?![A-Za-z0-9_-])",
        )
        val EMAIL_ADDRESS = Regex(
            """(?i)(?<![a-z0-9.!#$%&'*+/=?^_`{|}~-])[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9.-]{0,253}[a-z0-9])?(?![a-z0-9.-])""",
        )
        val ANDROID_PRIVATE_PATH = Regex(
            """(?i)(?<![a-z0-9])/(?:data|storage|sdcard|mnt|system|vendor|apex|proc|dev)(?:/[^\s,;)\]}\"']*)?""",
        )
        val BARE_PATH_IN_TEXT = Regex("""(?m)(^|[\s(\[\"'=:])(/[^\s\"'<>\\]+)""")
        val SENSITIVE_QUERY = Regex(
            """(?i)(?:[?&](?:token|signature|x-amz-credential|x-amz-signature)=[^\s,;)\]}]+)+""",
        )
        val NON_ASCII_TEXT = Regex("[^\\x00-\\x7f]+")
        val MAC_NETWORK_IDENTITY = Regex(
            """(?i)(?:(?<![0-9a-f])(?:(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?:[0-9a-f]{2}-){5}[0-9a-f]{2})(?![0-9a-f])|(?<![0-9a-f-])[0-9a-f]{12}(?![0-9a-f-]))""",
        )
        val LEGACY_LOOPBACK_NETWORK_IDENTITY = Regex(
            """(?i)(?<![a-z0-9_.-])(?:0x[0-9a-f]{1,8}|0[0-7]{7,11}|[0-9]{8,10}|(?:0x[0-9a-f]+|0[0-7]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|0[0-7]+|[0-9]+)){1,3})(?![a-z0-9_.-])""",
        )
        val LEGACY_NETWORK_TOKEN = Regex(
            """(?i)(?<![a-z0-9_.-])(?:0x[0-9a-f]{1,16}|0[0-7]{1,16}|[0-9]{1,10})(?:\.(?:0x[0-9a-f]{1,16}|0[0-7]{1,16}|[0-9]{1,10})){0,3}(?![a-z0-9_.-])""",
        )
        val LONG_IPV4_INTEGER = Regex("^[0-9]{7,10}$")
        val NETWORK_CONTEXT_IDENTITY = Regex(
            """(?i)\b(?:address|backend|connect(?:ed|ing)?|dns|endpoint|gateway|host(?:name)?|ip|ipv4|network|origin|peer|proxy|remote|resolved?|resolving|server|socket|target|upstream|url)\b(?:\s+(?:at|from|is|on|the|to|via|was))?\s+[\"']?(?:0x[0-9a-f]+|0[0-7]+|[0-9]+(?:\.[0-9a-fx]+){0,3}|[a-z][a-z0-9-]{0,62})(?::[0-9]{1,5})?[\"']?""",
        )
        val NETWORK_SCAN_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
        val NETWORK_LABEL_TOKEN = Regex("^[A-Za-z][A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*$")
        val NETWORK_CONTEXT_WORDS = setOf(
            "address", "backend", "connect", "connected", "connecting", "dns", "endpoint", "host",
            "hostname", "origin", "peer", "resolved", "resolving", "server", "socket", "url",
        )
        val NUMERIC_NETWORK_CONTEXT_WORDS = setOf(
            "destination", "gateway", "ip", "ipv4", "network", "proxy", "remote", "target", "upstream",
        )
        val NETWORK_CONNECTOR_WORDS = setOf(
            "at", "from", "http", "https", "is", "on", "the", "to", "via", "was", "ws", "wss",
        )
        val NETWORK_NON_HOST_WORDS = setOf(
            "aborted", "because", "closed", "complete", "completed", "error", "failed", "failure",
            "refused", "reset", "success", "successfully", "timeout", "unavailable", "unknown", "without",
            "with",
        )
        val SERVER_STATE_WORDS = setOf(
            "active", "available", "changed", "changing", "current", "now", "reachable", "ready",
            "returned", "running", "started", "stopped", "switched", "unreachable",
        )
        val SAFE_BACKEND_VALUES = setOf(
            "avplayerhls", "avplayernativedirect", "playercoredirect", "siloplayerloopback",
        )
        const val NETWORK_CONTEXT_WINDOW = 3
        const val IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|0?[0-9]{1,2})"
        val IPV4_NETWORK_IDENTITY = Regex(
            """(?i)(?<![a-z0-9_.-])/?($IPV4_OCTET(?:\.$IPV4_OCTET){3})(?::([0-9]{1,5}))?(?![a-z0-9_.-])""",
        )
        val BRACKETED_IPV6_NETWORK_IDENTITY = Regex(
            """(?i)(?<![a-z0-9_.-])/?\[([a-z0-9:.%_-]*:[a-z0-9:.%_-]+)](?::([0-9]{1,5}))?(?![a-z0-9_.-])""",
        )
        val BARE_IPV6_NETWORK_IDENTITY = Regex(
            """(?i)(?<![a-z0-9_.:\[])([0-9a-f:]{2,39})(?![a-z0-9_.:\]])""",
        )
        const val DNS_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        val DNS_NETWORK_IDENTITY = Regex(
            """(?i)(?<![a-z0-9_./-])($DNS_LABEL(?:\.$DNS_LABEL)+)(?::([0-9]{1,5}))?(?![a-z0-9_.-])""",
        )
        val DOTTED_NETWORK_TOKEN = Regex(
            """(?i)(?<![A-Za-z0-9_.-])([A-Za-z0-9][A-Za-z0-9_-]{0,62}(?:\.[A-Za-z0-9][A-Za-z0-9_-]{0,62})+)(?![A-Za-z0-9_.-])""",
        )
        val LEGACY_HOST_TOKEN = Regex("(?i)(?:\\[host:[a-f0-9]{12}]|\\bhost_[a-f0-9]{16}\\b)")
        val PRIVATE_SERVER_TOKEN = Regex("(?i)\\b(?:host|server|srv)_[a-z0-9_-]{8,}\\b")
        val HOST_WITH_PORT = Regex(
            """(?i)(?:^|[^A-Za-z0-9_.-])([A-Za-z][A-Za-z0-9_-]{0,62}):([0-9]{1,5})(?=$|[^0-9])""",
        )
        val SAFE_NUMERIC_ASSIGNMENT_KEYS = setOf(
            "audio_underruns", "bitrate_kbps", "dropped_frames", "duration_ms", "height", "line",
            "status", "width",
        )
        val SAFE_SOURCE_LOCATION_LABELS = setOf("Source", "SourceFile")
        val SOURCE_LOCATION_LINE = Regex("^[0-9]{1,9}$")
        val CANONICAL_ARTIFACT_NAMES = setOf(
            "breadcrumbs.jsonl", "device.json", "logs.jsonl", "manifest.json", "crash/metrickit.json",
        )
        val CANONICAL_ARTIFACT_BASENAMES = setOf("metrickit.json")
        val SAFE_DOTTED_TELEMETRY_KEYS = setOf(
            "ac.cur", "ac.pts", "audiooutput.status", "normalization.audiomode",
            "normalization.containermode", "normalization.subtitlemode", "normalization.videomode",
        )
        val SAFE_DOTTED_SETTING_KEYS = setOf(
            "catalog.metadata_language", "playback.audio_language", "playback.auto_play_next",
            "playback.auto_play_next_preview", "playback.auto_skip_credits", "playback.auto_skip_intro",
            "playback.auto_skip_recap", "playback.max_bitrate_kbps", "playback.next_up_prompt_seconds",
            "playback.preferred_quality", "playback.show_forced_subtitles", "playback.subtitle_appearance",
            "playback.subtitle_language", "playback.subtitle_mode", "player.audio_sync_ms",
            "player.dolby_vision_enabled", "player.dv_profile7_hdr10_fallback", "player.hdr_enabled",
            "player.match_frame_rate", "player.orientation_mode", "player.playback_speed",
            "player.seek_cache_enabled", "player.sleep_timer_default_minutes", "player.subtitle_sync_ms",
            "player.video_gravity", "search.media_scope", "ui.card_overlays", "ui.custom_css",
            "ui.custom_theme_vars", "ui.date_format", "ui.disabled_library_ids", "ui.high_contrast",
            "ui.library_order", "ui.library_page_state", "ui.next_up_mode", "ui.remember_library_page_state",
            "ui.sidebar_pins", "ui.text_scale", "ui.text_weight", "ui.theme", "ui.time_format",
        )
        val KNOWN_NATIVE_LIBRARIES = setOf(
            "libart.so", "libc++_shared.so", "libc.so", "libdyld.dylib", "libsystem_kernel.dylib",
        )
        val SAFE_MEDIA_DOTTED_SUBTYPES = setOf("vnd.dts", "vnd.dts.hd", "x-vnd.on2.vp9")
        val SAFE_MEDIA_RESOURCE_NAMES = setOf("init.mp4", "master.m3u8", "playlist.m3u8")
        val RFC3339_UTC = Regex(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z$",
        )
        val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', ')', ']')
        val UUID_PATH_SEGMENT = Regex(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        val NUMERIC_ID_PATH_SEGMENT = Regex("^[0-9]+$")
        val HEX_ID_PATH_SEGMENT = Regex("(?i)^[0-9a-f]{16,}$")
        val OPAQUE_ID_PATH_SEGMENT = Regex("^[A-Za-z0-9_-]{20,}$")
        val PRIVATE_ID_PATH_SEGMENT = Regex(
            "(?i)^(?:$HIGH_CONFIDENCE_PRIVATE_ID_PREFIXES)[_-][A-Za-z0-9_-]{4,}$",
        )
        val TEMPLATE_SEGMENT = Regex("^\\{[A-Za-z][A-Za-z0-9_]*\\}$")
        val SAFE_VERSION_SEGMENT = Regex("(?i)^v[0-9]+$")
        val PATH_CANDIDATE_DELIMITER = Regex("[.,;:()\\[\\]]+")
        val PATH_SEGMENT_PUNCTUATION = charArrayOf('(', '[', ']', '"', '\'', '.', ',', ';', '!', ':', ')')
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
