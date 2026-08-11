package org.siloserver.silo.common.diagnostics

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsBundleBuilderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val builder = FileDiagnosticsBundleBuilder()

    @Test
    fun bundleUsesCanonicalOrderAndExternalHash() {
        val report = report(
            artifacts = linkedMapOf(
                "breadcrumbs.jsonl" to "{\"event\":\"focus\"}\n".encodeToByteArray(),
                "crash/stack.txt" to "safe stack".encodeToByteArray(),
                "logs.jsonl" to "{\"msg\":\"safe\"}\n".encodeToByteArray(),
                "device.json" to "{\"captured_at\":\"2026-07-22T00:00:00Z\"}".encodeToByteArray(),
                "rogue.txt" to "must not ship".encodeToByteArray(),
            ),
        )

        val bundle = builder.build(report, redactionTokens = emptyList())
        val tarBytes = gunzip(bundle.bytes)
        val entries = untar(tarBytes)

        assertEquals(
            listOf("manifest.json", "device.json", "logs.jsonl", "crash/stack.txt", "breadcrumbs.jsonl"),
            entries.map(TarEntry::name),
        )
        assertEquals(entries.map(TarEntry::name), bundle.manifest.archive.entries)
        assertEquals(bundle.bytes.size.toLong(), bundle.manifest.archive.bytes)
        assertEquals(tarBytes.size.toLong(), bundle.manifest.archive.uncompressedBytes)
        assertEquals(sha256Hex(bundle.bytes), bundle.manifest.archive.sha256)
        assertFalse(Json.parseToJsonElement(entries.first().bytes.decodeToString()).jsonObject.containsKey("archive"))
        assertTrue(Json.parseToJsonElement(bundle.manifestBytes.decodeToString()).jsonObject.containsKey("archive"))
    }

    @Test
    fun bundleIsDeterministicAndRedactsTextWithoutTouchingBinary() {
        val secret = "secret-token"
        val binary = byteArrayOf(0, 1, 2, 3, 0x7f, 0xff.toByte()) + secret.encodeToByteArray()
        val report = report(
            artifacts = mapOf(
                "device.json" to "{\"token\":\"$secret\"}".encodeToByteArray(),
                "logs.jsonl" to "{\"msg\":\"Authorization: Bearer $secret\"}\n".encodeToByteArray(),
                "crash/tombstone.pb" to binary,
            ),
        )

        val first = builder.build(report, redactionTokens = listOf(secret))
        val second = builder.build(report, redactionTokens = listOf(secret))
        val entries = untar(gunzip(first.bytes)).associateBy(TarEntry::name)

        assertContentEquals(first.bytes, second.bytes)
        assertContentEquals(first.manifestBytes, second.manifestBytes)
        assertFalse(entries.getValue("device.json").bytes.decodeToString().contains(secret))
        Json.parseToJsonElement(entries.getValue("device.json").bytes.decodeToString())
        assertFalse(entries.getValue("logs.jsonl").bytes.decodeToString().contains(secret))
        assertTrue(entries.getValue("logs.jsonl").bytes.decodeToString().contains("[REDACTED]"))
        entries.getValue("logs.jsonl").bytes.decodeToString().lineSequence().filter(String::isNotBlank).forEach {
            Json.parseToJsonElement(it)
        }
        assertContentEquals(binary, entries.getValue("crash/tombstone.pb").bytes)
    }

    @Test
    fun manifestLogSummaryDescribesTheFinalSanitizedJsonl() {
        val secret = "secret-token"
        val report = report(
            artifacts = mapOf(
                "device.json" to "{}".encodeToByteArray(),
                "logs.jsonl" to (
                    "{\"cat\":\"playback\",\"msg\":\"$secret\"}\n" +
                        "{\"cat\":\"network\",\"msg\":\"safe\"}\n"
                    ).encodeToByteArray(),
            ),
        )

        val bundle = builder.build(report, redactionTokens = listOf(secret))
        val entries = untar(gunzip(bundle.bytes)).associateBy(TarEntry::name)
        val shippedLogs = entries.getValue("logs.jsonl").bytes

        assertFalse(shippedLogs.decodeToString().contains(secret))
        assertEquals(2, bundle.manifest.logSummary.lines)
        assertEquals(
            listOf(DiagnosticsLogCategory.PLAYBACK, DiagnosticsLogCategory.NETWORK),
            bundle.manifest.logSummary.categories,
        )
        assertEquals(
            DiagnosticsLogSummaryBuilder.build(shippedLogs, droppedLines = 0, debugLogging = false).bytesGzip,
            bundle.manifest.logSummary.bytesGzip,
        )
    }

    @Test
    fun hostedBundleFiltersLogsAndBreadcrumbsToCollectorV1WithoutChangingSelfHosted() {
        val playbackLine = """{"ts":"2026-08-11T00:00:00Z","run":"run-1","lvl":"I","cat":"playback","tag":"Player","msg":"stats playback_session_id=private-playback-correlation","attrs":{"decoder":"c2.android.avc","buffered_ms":1200,"failure_code":"source-private"}}"""
        val lifecycleLine = """{"ts":"2026-08-11T00:00:01Z","run":"run-1","lvl":"I","cat":"lifecycle","tag":"Lifecycle","msg":"performance","attrs":{"state":"foreground","p95_frame_ms":22,"startup_first_frame_ms":400}}"""
        val focusLine = """{"ts":"2026-08-11T00:00:02Z","run":"run-1","lvl":"I","cat":"focus","tag":"Focus","msg":"moved","attrs":{"target":"send","action":"enter","route":"private-route"}}"""
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "logs.jsonl" to "$playbackLine\n$lifecycleLine\n".encodeToByteArray(),
            "breadcrumbs.jsonl" to "$focusLine\n".encodeToByteArray(),
        )

        val hosted = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        )
        val hostedEntries = untar(gunzip(hosted.bytes)).associateBy(TarEntry::name)
        val hostedLogs = hostedEntries.getValue("logs.jsonl").bytes.decodeToString()
            .lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()
        val hostedBreadcrumb = Json.parseToJsonElement(
            hostedEntries.getValue("breadcrumbs.jsonl").bytes.decodeToString().trim(),
        ).jsonObject

        assertEquals("c2.android.avc", hostedLogs[0].getValue("attrs").jsonObject.getValue("decoder").jsonPrimitive.content)
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("buffered_ms"))
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("failure_code"))
        assertFalse(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("private-playback-correlation"))
        assertTrue(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("[REDACTED]"))
        assertEquals(setOf("state"), hostedLogs[1].getValue("attrs").jsonObject.keys)
        assertEquals(setOf("target", "action"), hostedBreadcrumb.getValue("attrs").jsonObject.keys)

        val selfHosted = builder.build(
            report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        )
        val selfHostedEntries = untar(gunzip(selfHosted.bytes)).associateBy(TarEntry::name)
        val selfHostedLogs = selfHostedEntries.getValue("logs.jsonl").bytes.decodeToString()
        val selfHostedBreadcrumbs = selfHostedEntries.getValue("breadcrumbs.jsonl").bytes.decodeToString()
        assertTrue(selfHostedLogs.contains("buffered_ms"))
        assertTrue(selfHostedLogs.contains("failure_code"))
        assertTrue(selfHostedLogs.contains("private-playback-correlation"))
        assertTrue(selfHostedLogs.contains("p95_frame_ms"))
        assertTrue(selfHostedBreadcrumbs.contains("private-route"))
    }

    @Test
    fun invalidUtf8TextIsReplacedByRedactionFailureSentinel() {
        val report = report(
            artifacts = mapOf(
                "device.json" to "{}".encodeToByteArray(),
                "logs.jsonl" to byteArrayOf(0xc3.toByte(), 0x28),
            ),
        )

        val entries = untar(gunzip(builder.build(report, emptyList()).bytes)).associateBy(TarEntry::name)

        assertContentEquals(
            "{\"redaction_failure\":true}\n".encodeToByteArray(),
            entries.getValue("logs.jsonl").bytes,
        )
    }

    private fun report(
        artifacts: Map<String, ByteArray>,
        destinationKind: DiagnosticsDestinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
    ): PendingReport {
        val directory = temporaryFolder.newFolder()
        artifacts.forEach { (path, bytes) ->
            directory.resolve(path).also { file ->
                check(file.parentFile?.mkdirs() != false || file.parentFile?.isDirectory == true)
                file.writeBytes(bytes)
            }
        }
        return PendingReport(
            id = "a".repeat(32),
            directory = directory,
            binding = PendingReportBinding(
                "server-1",
                "user-1",
                "profile-1",
                7,
                destinationKind,
            ),
            manifest = manifest(),
            state = PendingReportState(
                capturedAtEpochMs = 1,
                fingerprint = "fingerprint",
                updatedAtEpochMs = 1,
            ),
        )
    }

    private fun manifest() = DiagnosticsManifest(
        schemaVersion = 1,
        report = DiagnosticsReport(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-07-22T00:00:00Z",
            captureSessionId = "capture-1",
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "36",
            profileId = "profile-1",
        ),
        destination = DiagnosticsDestination("server-1"),
        consent = DiagnosticsConsent(DiagnosticsConsentMode.MANUAL, 1),
        deviceSummary = DiagnosticsDeviceSummary("Google", "Shield", "Android 36", "tv"),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsLogSummary(1, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
        archive = DiagnosticsArchive(listOf("manifest.json"), 0, 0, "0".repeat(64)),
    )

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun untar(bytes: ByteArray): List<TarEntry> {
        val entries = mutableListOf<TarEntry>()
        var offset = 0
        while (offset + TAR_BLOCK_SIZE <= bytes.size) {
            val header = bytes.copyOfRange(offset, offset + TAR_BLOCK_SIZE)
            if (header.all { it == 0.toByte() }) break
            val name = header.copyOfRange(0, 100).cstring()
            val size = header.copyOfRange(124, 136).cstring().trim().toLong(8)
            val checksum = header.copyOfRange(148, 156).cstring().trim().toLong(8)
            val checksumHeader = header.copyOf().also { copy ->
                repeat(8) { copy[148 + it] = ' '.code.toByte() }
            }
            assertEquals(checksum, checksumHeader.sumOf { it.toUByte().toLong() }, "USTAR checksum for $name")
            assertEquals("ustar", header.copyOfRange(257, 263).cstring())
            val contentStart = offset + TAR_BLOCK_SIZE
            val contentEnd = contentStart + size.toInt()
            entries += TarEntry(name, bytes.copyOfRange(contentStart, contentEnd))
            offset = contentStart + ((size + TAR_BLOCK_SIZE - 1) / TAR_BLOCK_SIZE * TAR_BLOCK_SIZE).toInt()
        }
        assertTrue(bytes.takeLast(TAR_BLOCK_SIZE * 2).all { it == 0.toByte() }, "tar must end with two zero blocks")
        return entries
    }

    private fun ByteArray.cstring(): String =
        copyOfRange(0, indexOf(0).takeIf { it >= 0 } ?: size).decodeToString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class TarEntry(val name: String, val bytes: ByteArray)

    private companion object {
        const val TAR_BLOCK_SIZE = 512
    }
}
