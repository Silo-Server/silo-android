package org.siloserver.silo.common.diagnostics

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
            "crash/tombstone.pb" to "opaque-private-native-trace".encodeToByteArray(),
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

        assertEquals(
            "android-c2-platform-decoder",
            hostedLogs[0].getValue("attrs").jsonObject.getValue("decoder").jsonPrimitive.content,
        )
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("buffered_ms"))
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("failure_code"))
        assertFalse(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("private-playback-correlation"))
        assertTrue(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("[REDACTED_PRIVATE_ID]"))
        assertEquals(setOf("state"), hostedLogs[1].getValue("attrs").jsonObject.keys)
        assertEquals(setOf("target", "action"), hostedBreadcrumb.getValue("attrs").jsonObject.keys)
        assertFalse(hostedEntries.containsKey("crash/tombstone.pb"))
        assertFalse(hosted.manifest.archive.entries.contains("crash/tombstone.pb"))

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
        assertContentEquals(
            "opaque-private-native-trace".encodeToByteArray(),
            selfHostedEntries.getValue("crash/tombstone.pb").bytes,
        )
    }

    @Test
    fun hostedBundleNormalizesDecoderNamesOnLogsBreadcrumbsAndDeviceOnly() {
        val decoderFamilies = listOf(
            "c2.android.avc.decoder" to "android-c2-platform-decoder",
            "c2.vendor.avc.decoder" to "android-c2-vendor-decoder",
            "c2.qti.hevc.decoder" to "android-c2-vendor-decoder",
            "OMX.google.h264.decoder" to "android-omx-platform-decoder",
            "OMX.android.hevc.decoder" to "android-omx-platform-decoder",
            "OMX.Nvidia.h264.decode" to "android-omx-vendor-decoder",
            "OMX.qcom.video.decoder.avc" to "android-omx-vendor-decoder",
            "OMX.vendor.video.decoder.hevc" to "android-omx-vendor-decoder",
            "com.example.super.decoder" to "android-decoder",
            "android-c2-platform-decoder" to "android-c2-platform-decoder",
            "android-c2-vendor-decoder" to "android-c2-vendor-decoder",
            "android-omx-platform-decoder" to "android-omx-platform-decoder",
            "android-omx-vendor-decoder" to "android-omx-vendor-decoder",
            "android-decoder" to "android-decoder",
        )
        val logs = decoderFamilies.mapIndexed { index, (raw, _) ->
            """{"ts":"2026-08-11T00:00:${index.toString().padStart(2, '0')}Z","run":"run-1","lvl":"I","cat":"playback","tag":"Player","msg":"decoder","attrs":{"decoder":"$raw"}}"""
        }.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()
        val breadcrumbs = decoderFamilies.mapIndexed { index, (raw, _) ->
            """{"ts":"2026-08-11T00:01:${index.toString().padStart(2, '0')}Z","run":"run-1","lvl":"I","cat":"playback","tag":"Breadcrumb","msg":"decoder","attrs":{"decoder":"$raw"}}"""
        }.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()
        val device = decoderFamilies.mapIndexed { index, (raw, _) ->
            """{"codec":"codec-$index","decoder_name":"$raw","hardware":true}"""
        }.joinToString(prefix = "{\"video_codecs\":[", separator = ",", postfix = "]}")
            .encodeToByteArray()
        val artifacts = mapOf(
            "device.json" to device,
            "logs.jsonl" to logs,
            "breadcrumbs.jsonl" to breadcrumbs,
        )

        val hostedEntries = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries
        val expected = decoderFamilies.map { (_, family) -> family }
        assertEquals(
            setOf(
                "android-c2-platform-decoder",
                "android-c2-vendor-decoder",
                "android-omx-platform-decoder",
                "android-omx-vendor-decoder",
                "android-decoder",
            ),
            expected.toSet(),
        )

        listOf("logs.jsonl", "breadcrumbs.jsonl").forEach { path ->
            val actual = hostedEntries.getValue(path).decodeToString()
                .lineSequence()
                .filter(String::isNotBlank)
                .map { line ->
                    Json.parseToJsonElement(line).jsonObject
                        .getValue("attrs").jsonObject
                        .getValue("decoder").jsonPrimitive.content
                }
                .toList()
            assertEquals(expected, actual, path)
            assertTrue(actual.none { '.' in it }, path)
        }
        val hostedDeviceDecoders = Json.parseToJsonElement(
            hostedEntries.getValue("device.json").decodeToString(),
        ).jsonObject.getValue("video_codecs").jsonArray.map { codec ->
            codec.jsonObject.getValue("decoder_name").jsonPrimitive.content
        }
        assertEquals(expected, hostedDeviceDecoders)
        assertTrue(hostedDeviceDecoders.none { '.' in it })

        val selfHostedEntries = builder.build(
            report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries
        artifacts.forEach { (path, bytes) ->
            assertContentEquals(bytes, selfHostedEntries.getValue(path), path)
        }
    }

    @Test
    fun hostedBundleCanonicalizesPrivateHostsPathsAndIdentifierAssignmentsInEveryTextField() {
        val privateHost = "saved-private-silo.example"
        val networkLine = """{"ts":"2026-08-11T00:00:00Z","run":"run-1","lvl":"I","cat":"network","tag":"wss://$privateHost/items/42 planAttemptKey=attempt-private","msg":"host_0123456789abcdef selectedFileId=991 playbackSessionId=session-private","attrs":{"method":"GET","path":"/users/42/items/0123456789abcdef","status":200,"duration_ms":5}}"""
        val device = """{"server":"$privateHost","socket":"ws://$privateHost/items/42?token=private","note":"sessionId=session-private trackId=track-private","host_token":"host_fedcba9876543210"}"""
        val bundle = builder.build(
            report(
                artifacts = mapOf(
                    "device.json" to device.encodeToByteArray(),
                    "logs.jsonl" to "$networkLine\n".encodeToByteArray(),
                ),
                destinationKind = DiagnosticsDestinationKind.HOSTED,
            ),
            redactionTokens = listOf(privateHost),
        )
        val entries = untar(gunzip(bundle.bytes)).associateBy(TarEntry::name)
        val shippedDevice = entries.getValue("device.json").bytes.decodeToString()
        val shippedLog = entries.getValue("logs.jsonl").bytes.decodeToString()
        val shipped = entries.values.joinToString("\n") { it.bytes.decodeToString() }

        listOf(
            privateHost,
            "host_0123456789abcdef",
            "host_fedcba9876543210",
            "attempt-private",
            "session-private",
            "track-private",
            "/items/42",
            "/users/42",
        ).forEach { leaked -> assertFalse(shipped.contains(leaked), "leaked $leaked in $shipped") }
        assertTrue(shipped.contains("wss://redacted.invalid/items/{id}"), shipped)
        assertTrue(shipped.contains("ws://redacted.invalid/items/{id}"), shipped)
        assertTrue(shipped.contains("[REDACTED_PRIVATE_ID]"), shipped)
        Json.parseToJsonElement(shippedDevice)
        shippedLog.lineSequence().filter(String::isNotBlank).forEach { line -> Json.parseToJsonElement(line) }
    }

    @Test
    fun hostedBundleCanonicalizesLoopbackIdentityAcrossEveryTextSurfaceOnly() {
        val device = """{"host":"127.0.0.1","host.name":"LOCALHOST","server_url":"http://127.0.0.2:49152/device/42","server.url":"ws://[::1]:9000/device/42","origin":{"note":"removed"},"safe":{"hostname":"localhost","originUrl":"https://127.0.0.3/origin","base.url":"http://localhost/base","endpoint":"[::1]","address":"127.0.0.4","url":"http://[::1]:9001/device","server_instance_id":"keep","note":"device LOCALHOST 127.0.0.0 127.255.255.255 [::1] ::1 connect http://localhost:8080/items/42 url=http://127.0.0.5/private"}}""".encodeToByteArray()
        val logs = """{"ts":"2026-08-11T00:00:00Z","run":"::1","lvl":"E","cat":"network","tag":"http://127.0.0.2:49152/items/42","msg":"host=127.0.0.1 throwable LOCALHOST peer [::1] ws://[::1]:9000/users/99 server_instance_id=keep","attrs":{"method":"GET","path":"/items/42","status":500,"duration_ms":2}}"""
            .plus('\n').encodeToByteArray()
        val breadcrumbs = """{"ts":"2026-08-11T00:00:01Z","run":"run-1","lvl":"I","cat":"focus","tag":"ws://127.0.0.3:9002/library/42","msg":"server_url='ws://[::1]:9001/items/42' origin=https://example.test/private bare 127.255.254.253 and ::1","attrs":{"target":"127.0.0.9","action":"baseUrl=http://localhost:1234/x"}}"""
            .plus('\n').encodeToByteArray()
        val crashSummary = """{"summary":"endpoint=http://127.0.0.5:8080/x bare localhost","stack_excerpt":"peer ::1 and [::1] http://127.0.0.6:8080/items/42","thread":"url='http://localhost:9000/private'"}"""
            .encodeToByteArray()
        val crashStack = (
            "IllegalStateException: hostname=\"LOCALHOST\" address=[::1] peer 127.0.0.7 ::1 [::1]\n" +
                "at ws://[::1]:9000/items/42 endpoint : http://127.0.0.8:8080/private\n" +
                "server=redacted.invalid server_instance_id=keep"
            ).encodeToByteArray()
        val artifacts = mapOf(
            "device.json" to device,
            "logs.jsonl" to logs,
            "crash/summary.json" to crashSummary,
            "crash/stack.txt" to crashStack,
            "breadcrumbs.jsonl" to breadcrumbs,
        )
        fun withLoopbackManifest(report: PendingReport): PendingReport = report.copy(
            manifest = report.manifest.copy(
                report = report.manifest.report.copy(
                    captureSessionId = "host=127.0.0.1",
                    appVersion = "http://localhost:49152/build/42",
                    appBuild = "127.0.0.10",
                    osVersion = "peer ::1",
                ),
                deviceSummary = report.manifest.deviceSummary.copy(
                    manufacturer = "LOCALHOST",
                    model = "[::1]",
                    os = "http://127.0.0.11:8080/os/42",
                    formFactor = "server=already-safe",
                ),
            ),
        )

        val hosted = builder.build(
            withLoopbackManifest(report(artifacts, DiagnosticsDestinationKind.HOSTED)),
            redactionTokens = emptyList(),
        )
        val hostedSurfaces = linkedMapOf(
            "outer manifest" to hosted.manifestBytes.decodeToString(),
            "embedded manifest" to hosted.sanitizedEntries.getValue("manifest.json").decodeToString(),
            "device" to hosted.sanitizedEntries.getValue("device.json").decodeToString(),
            "logs" to hosted.sanitizedEntries.getValue("logs.jsonl").decodeToString(),
            "breadcrumbs" to hosted.sanitizedEntries.getValue("breadcrumbs.jsonl").decodeToString(),
            "crash summary" to hosted.sanitizedEntries.getValue("crash/summary.json").decodeToString(),
            "crash stack" to hosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString(),
        )
        hostedSurfaces.forEach { (name, text) ->
            assertTrue(text.contains("redacted.invalid"), "$name: $text")
            assertTrue(text.contains("[redacted_network_identity]"), "$name: $text")
            assertFalse(text.contains("localhost", ignoreCase = true), "$name: $text")
            assertFalse(text.contains("127."), "$name: $text")
            assertFalse(text.contains("::1"), "$name: $text")
            assertFalse(text.contains("example.test"), "$name: $text")
            assertFalse(text.contains("already-safe"), "$name: $text")
        }
        val hostedText = hostedSurfaces.values.joinToString("\n")
        assertTrue(hostedText.contains("http://redacted.invalid:49152/build/{id}"), hostedText)
        assertTrue(hostedText.contains("http://redacted.invalid:49152/items/{id}"), hostedText)
        assertTrue(hostedText.contains("ws://redacted.invalid:9000/users/{id}"), hostedText)
        assertTrue(hostedText.contains("ws://redacted.invalid:9002/library/{id}"), hostedText)
        assertTrue(hostedText.contains("http://redacted.invalid:8080/items/{id}"), hostedText)
        assertTrue(hostedText.contains("server_instance_id=keep"), hostedText)
        assertFalse(
            Regex(
                """(?i)\b(?:host(?:[._-]?name)?|server(?:[._-]?url)?|base[._-]?url|origin(?:[._-]?url)?|endpoint(?:[._-]?url)?|address|url)\s*[:=]""",
            ).containsMatchIn(hostedText),
            hostedText,
        )

        listOf("outer manifest", "embedded manifest").forEach { name ->
            val manifest = Json.parseToJsonElement(hostedSurfaces.getValue(name)).jsonObject
            assertEquals(
                HOSTED_DIAGNOSTICS_COLLECTOR_ID,
                manifest.getValue("destination").jsonObject.getValue("server_instance_id").jsonPrimitive.content,
            )
        }
        val hostedDevice = Json.parseToJsonElement(hostedSurfaces.getValue("device")).jsonObject
        assertEquals(setOf("safe"), hostedDevice.keys)
        assertEquals(
            setOf("server_instance_id", "note"),
            hostedDevice.getValue("safe").jsonObject.keys,
        )

        val selfHosted = builder.build(
            withLoopbackManifest(report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED)),
            redactionTokens = emptyList(),
        )
        artifacts.forEach { (path, bytes) ->
            assertContentEquals(bytes, selfHosted.sanitizedEntries.getValue(path), path)
        }
        listOf(
            selfHosted.manifestBytes.decodeToString(),
            selfHosted.sanitizedEntries.getValue("manifest.json").decodeToString(),
        ).forEach { manifest ->
            assertTrue(manifest.contains("host=127.0.0.1"), manifest)
            assertTrue(manifest.contains("http://localhost:49152/build/42"), manifest)
            assertFalse(manifest.contains("[redacted_network_identity]"), manifest)
        }
    }

    @Test
    fun hostedLoopbackNormalizationRequiresLiteralTokenBoundariesAndValidIpv4Octets() {
        val boundaryValues =
            "mylocalhost localhost.example 127.0.0.1.example 127.0.0.256 1127.0.0.1 " +
                "127.0.0.1x 2001:db8::1 ::10 foo[::1]bar"
        val bundle = builder.build(
            report(
                artifacts = mapOf(
                    "device.json" to "{}".encodeToByteArray(),
                    "crash/stack.txt" to boundaryValues.encodeToByteArray(),
                ),
                destinationKind = DiagnosticsDestinationKind.HOSTED,
            ),
            redactionTokens = emptyList(),
        )

        assertContentEquals(
            boundaryValues.encodeToByteArray(),
            bundle.sanitizedEntries.getValue("crash/stack.txt"),
        )
    }

    @Test
    fun hostedDeviceSnapshotOmitsDeterministicRouteAndDeviceIdentifiersOnlyForHosted() {
        val device = """{"identity":{"manufacturer":"NVIDIA","build_fingerprint_hash":"${"a".repeat(32)}"},"audio":{"route_hashes":["${"b".repeat(32)}"],"outputs":[{"type":"hdmi","id":"${"c".repeat(32)}","address":"${"d".repeat(32)}"}]}}"""
        val artifacts = mapOf("device.json" to device.encodeToByteArray())

        val hostedDevice = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries.getValue("device.json").decodeToString()
        val selfHostedDevice = builder.build(
            report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries.getValue("device.json").decodeToString()

        listOf("route_hashes", "\"id\"", "\"address\"", "b".repeat(32), "c".repeat(32), "d".repeat(32))
            .forEach { value -> assertFalse(hostedDevice.contains(value), hostedDevice) }
        assertTrue(hostedDevice.contains("build_fingerprint_hash"), hostedDevice)
        assertTrue(selfHostedDevice.contains("route_hashes"), selfHostedDevice)
        assertTrue(selfHostedDevice.contains("\"id\""), selfHostedDevice)
        assertTrue(selfHostedDevice.contains("\"address\""), selfHostedDevice)
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
        val reportManifest = manifest().let { value ->
            if (destinationKind == DiagnosticsDestinationKind.HOSTED) {
                value.copy(
                    report = value.report.copy(profileId = null),
                    destination = DiagnosticsDestination(HOSTED_DIAGNOSTICS_COLLECTOR_ID),
                    playbackSessionIds = emptyList(),
                )
            } else {
                value
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
            manifest = reportManifest,
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
