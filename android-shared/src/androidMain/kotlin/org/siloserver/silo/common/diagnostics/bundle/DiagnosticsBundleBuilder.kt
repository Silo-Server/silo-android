package org.siloserver.silo.common.diagnostics.bundle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.common.diagnostics.toHexLower
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsManifestDraft
import org.siloserver.silo.model.diagnostics.validate
import org.siloserver.silo.network.SiloJson
import java.io.ByteArrayOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Builds the transmitted `bundle` part (tar.gz) and the part-1 manifest for
 * one report.
 *
 * Construction is two-phase per the wire contract's hashing note: the
 * embedded `manifest.json` (first tar entry) is the manifest WITHOUT the
 * `archive` object; `archive.{bytes, uncompressed_bytes, sha256, entries}` are
 * measured from the actual gzip stream afterwards. Part 1 is produced by
 * splicing the `archive` object into the *same* embedded bytes, so
 * "part-1 minus archive == embedded" holds byte-for-byte by construction and
 * the archive object never passes through redaction.
 *
 * The bundle-time redaction pass (exact current-token scrub, defense in depth
 * behind the collection-time facade) runs on every textual entry BEFORE its
 * tar header is written — redaction changes byte lengths, so sizing anything
 * first would corrupt the archive. `crash/tombstone.pb` is opaque binary and
 * is never string-scrubbed.
 */
class DiagnosticsBundleBuilder {

    class Input(
        val draft: DiagnosticsManifestDraft,
        val deviceJson: ByteArray,
        val logsJsonl: ByteArray,
        val stackTxt: ByteArray?,
        val tombstonePb: ByteArray?,
        val breadcrumbsJsonl: ByteArray?,
        val debugLogging: Boolean,
    )

    class Result(
        val manifestJson: ByteArray,
        val bundleBytes: ByteArray,
        val manifest: DiagnosticsManifest,
    )

    class BundleValidationException(val problems: List<String>) :
        IllegalStateException("diagnostics manifest invalid: ${problems.joinToString("; ")}")

    fun build(input: Input, redactionTokens: List<String>): Result {
        val tokens = redactionTokens.filter { it.length >= MIN_TOKEN_LENGTH }

        // Redact payload entries first — sizes below must describe shipped bytes.
        val deviceJson = scrub(input.deviceJson, tokens)
        val logsJsonl = scrub(input.logsJsonl, tokens)
        val stackTxt = input.stackTxt?.let { scrub(it, tokens) }
        val breadcrumbs = input.breadcrumbsJsonl?.let { scrub(it, tokens) }

        // log_summary is part of "manifest minus archive" and must be final
        // before the embedded copy is rendered.
        val logSummary = computeLogSummary(logsJsonl, input.draft, input.debugLogging)
        val draft = input.draft.copy(logSummary = logSummary)
        val embeddedManifest = scrub(
            SiloJson.encodeToString(DiagnosticsManifestDraft.serializer(), draft).encodeToByteArray(),
            tokens,
        )

        val crashSummary = draft.crash?.let {
            scrub(
                SiloJson.encodeToString(DiagnosticsManifest.Crash.serializer(), it).encodeToByteArray(),
                tokens,
            )
        }

        // Canonical allowlist order; archive.entries must equal actual tar order.
        val entries = buildList {
            add("manifest.json" to embeddedManifest)
            add("device.json" to deviceJson)
            add("logs.jsonl" to logsJsonl)
            crashSummary?.let { add("crash/summary.json" to it) }
            stackTxt?.let { add("crash/stack.txt" to it) }
            input.tombstonePb?.let { add("crash/tombstone.pb" to it) }
            breadcrumbs?.let { add("breadcrumbs.jsonl" to it) }
        }

        val baos = ByteArrayOutputStream(256 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        val compressedCounting = CountingOutputStream(DigestOutputStream(baos, digest))
        var uncompressedBytes = 0L
        GZIPOutputStream(compressedCounting).use { gzip ->
            val tarCounting = CountingOutputStream(gzip)
            for ((name, bytes) in entries) UstarTarWriter.writeEntry(tarCounting, name, bytes)
            UstarTarWriter.writeEndOfArchive(tarCounting)
            uncompressedBytes = tarCounting.count
        }
        // Read counts/digest only after close() flushed the gzip trailer.
        val archive = DiagnosticsManifest.Archive(
            entries = entries.map { it.first },
            bytes = compressedCounting.count,
            uncompressedBytes = uncompressedBytes,
            sha256 = digest.digest().toHexLower(),
        )

        // Part 1 = embedded bytes + spliced archive object. Splicing (rather
        // than re-rendering) guarantees semantic equality with the embedded
        // copy and keeps the archive's sha256 out of the redaction pass.
        val archiveJson = SiloJson.encodeToString(DiagnosticsManifest.Archive.serializer(), archive)
        val manifestJson = spliceArchive(embeddedManifest, archiveJson)

        val manifest = draft.finalized(archive)
        val problems = manifest.validate()
        if (problems.isNotEmpty()) throw BundleValidationException(problems)

        return Result(manifestJson = manifestJson, bundleBytes = baos.toByteArray(), manifest = manifest)
    }

    private fun computeLogSummary(
        logsJsonl: ByteArray,
        draft: DiagnosticsManifestDraft,
        debugLogging: Boolean,
    ): DiagnosticsManifest.LogSummary {
        val lines = logsJsonl.decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
        val categories = lines.mapNotNullTo(LinkedHashSet()) { line ->
            runCatching {
                val cat = lenientJson.parseToJsonElement(line).jsonObject["cat"]?.jsonPrimitive?.content
                DiagnosticsLogCategory.entries.firstOrNull { it.wire == cat }
            }.getOrNull()
        }
        val bytesGz = if (logsJsonl.isEmpty()) {
            0L
        } else {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(logsJsonl) }
            out.size().toLong()
        }
        return DiagnosticsManifest.LogSummary(
            lines = lines.size.toLong(),
            bytesGz = bytesGz,
            droppedLines = draft.logSummary.droppedLines,
            categories = categories.toList(),
            debugLogging = debugLogging,
        )
    }

    /** Exact-token scrub over textual bytes; fail-closed on non-UTF8 content. */
    private fun scrub(bytes: ByteArray, tokens: List<String>): ByteArray {
        if (tokens.isEmpty() || bytes.isEmpty()) return bytes
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            ?: return REDACTION_FAILED_PLACEHOLDER.encodeToByteArray()
        var out = text
        for (token in tokens) {
            out = out.replace(token, "[redacted_token]")
        }
        return if (out === text) bytes else out.encodeToByteArray()
    }

    private fun spliceArchive(embeddedManifest: ByteArray, archiveJson: String): ByteArray {
        val text = embeddedManifest.toString(Charsets.UTF_8).trimEnd()
        check(text.endsWith("}")) { "embedded manifest must be a JSON object" }
        return (text.dropLast(1) + ",\"archive\":" + archiveJson + "}").encodeToByteArray()
    }

    private companion object {
        const val MIN_TOKEN_LENGTH = 8
        const val REDACTION_FAILED_PLACEHOLDER = "[redaction_failed: non-utf8 content dropped]"
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
