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
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashInfo
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
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
        assertEquals(0xff.toByte(), bundle.bytes[9], "self-hosted gzip origin stays runtime-native")
        assertFalse(Json.parseToJsonElement(entries.first().bytes.decodeToString()).jsonObject.containsKey("archive"))
        assertTrue(Json.parseToJsonElement(bundle.manifestBytes.decodeToString()).jsonObject.containsKey("archive"))
    }

    @Test
    fun hostedBundleUsesCollectorCanonicalGzipOriginBeforeHashing() {
        val bundle = builder.build(
            report(
                artifacts = mapOf("device.json" to "{}".encodeToByteArray()),
                destinationKind = DiagnosticsDestinationKind.HOSTED,
            ),
            redactionTokens = emptyList(),
        )

        assertContentEquals(byteArrayOf(0x1f, 0x8b.toByte()), bundle.bytes.copyOfRange(0, 2))
        assertEquals(0, bundle.bytes[9].toInt())
        assertEquals(sha256Hex(bundle.bytes), bundle.manifest.archive.sha256)
    }

    @Test
    fun hostedBundlePreservesCanonicalApplicationVersionMetadata() {
        val source = report(
            artifacts = mapOf("device.json" to "{}".encodeToByteArray()),
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        ).let { report ->
            report.copy(
                manifest = report.manifest.copy(
                    report = report.manifest.report.copy(
                        appVersion = "0.3.11",
                        appBuild = "14",
                        osVersion = "16",
                    ),
                ),
            )
        }

        val bundle = builder.build(source, redactionTokens = emptyList())
        val embedded = Json.parseToJsonElement(
            bundle.sanitizedEntries.getValue("manifest.json").decodeToString(),
        ).jsonObject.getValue("report").jsonObject

        assertEquals("0.3.11", bundle.manifest.report.appVersion)
        assertEquals("14", bundle.manifest.report.appBuild)
        assertEquals("16", bundle.manifest.report.osVersion)
        assertEquals("0.3.11", embedded.getValue("app_version").jsonPrimitive.content)
        assertEquals("14", embedded.getValue("app_build").jsonPrimitive.content)
        assertEquals("16", embedded.getValue("os_version").jsonPrimitive.content)
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
        val homeContentLine = """{"ts":"2026-08-11T00:00:03Z","run":"run-1","lvl":"I","cat":"lifecycle","tag":"HomeScreen","msg":"home content state changed","attrs":{"phase":"home_content","outcome":"ready"}}"""
        val homeScrollLine = """{"ts":"2026-08-11T00:00:04Z","run":"run-1","lvl":"I","cat":"lifecycle","tag":"HomeScreen","msg":"home scroll state changed","attrs":{"phase":"home_scroll","outcome":"scrolling","reason":"content"}}"""
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "logs.jsonl" to "$playbackLine\n$lifecycleLine\n".encodeToByteArray(),
            "breadcrumbs.jsonl" to "$focusLine\n$homeContentLine\n$homeScrollLine\n".encodeToByteArray(),
            "crash/tombstone.pb" to "opaque-private-native-trace".encodeToByteArray(),
        )

        val hosted = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        )
        val hostedEntries = untar(gunzip(hosted.bytes)).associateBy(TarEntry::name)
        val hostedLogs = hostedEntries.getValue("logs.jsonl").bytes.decodeToString()
            .lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()
        val hostedBreadcrumbs = hostedEntries.getValue("breadcrumbs.jsonl").bytes.decodeToString()
            .lineSequence().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.toList()

        assertEquals(
            "android-c2-platform-decoder",
            hostedLogs[0].getValue("attrs").jsonObject.getValue("decoder").jsonPrimitive.content,
        )
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("buffered_ms"))
        assertFalse(hostedLogs[0].getValue("attrs").jsonObject.containsKey("failure_code"))
        assertFalse(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("private-playback-correlation"))
        assertTrue(hostedLogs[0].getValue("msg").jsonPrimitive.content.contains("[redacted_private_id]"))
        assertEquals(setOf("state"), hostedLogs[1].getValue("attrs").jsonObject.keys)
        assertEquals(setOf("target", "action"), hostedBreadcrumbs[0].getValue("attrs").jsonObject.keys)
        assertEquals(
            mapOf("phase" to "home_content", "outcome" to "ready"),
            hostedBreadcrumbs[1].getValue("attrs").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("phase" to "home_scroll", "outcome" to "scrolling", "reason" to "content"),
            hostedBreadcrumbs[2].getValue("attrs").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
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
    fun hostedBundleWithholdsPrivatePlaybackAndAttemptAttributesFromCollector() {
        // Pins the hosted allowlist in both directions so it cannot silently drift
        // from the Apple client's hostedAttributeRegistry. The playback keys that
        // describe one user's viewing session (session_id, play_method, reason,
        // position_ms) and the network retry counter (attempt) are withheld; the
        // rest of the newly registered keys are safe and must survive. lifecycle
        // "reason" is a client-side classification, not the playback operator free
        // text, so it stays.
        val playbackLine = """{"ts":"2026-08-14T00:00:00Z","run":"run-1","lvl":"I","cat":"playback","tag":"Player","msg":"stats","attrs":{"sink":"hdmi","fmt":"hevc","width":3840,"height":2160,"hdr_mode":"hdr10","bitrate_kbps":18000,"dropped_frames":3,"audio_underruns":1,"session_id":"private-server-playback-session","play_method":"transcode","reason":"operator-free-text-stop-reason","position_ms":42500}}"""
        val networkLine = """{"ts":"2026-08-14T00:00:01Z","run":"run-1","lvl":"I","cat":"network","tag":"Http","msg":"request","attrs":{"method":"GET","path":"/health","status":503,"duration_ms":120,"outcome":"retried","error_code":"timeout","attempt":4}}"""
        val lifecycleLine = """{"ts":"2026-08-14T00:00:02Z","run":"run-1","lvl":"I","cat":"lifecycle","tag":"Lifecycle","msg":"startup","attrs":{"state":"foreground","phase":"first_frame","duration_ms":400,"outcome":"succeeded","reason":"cold_start_classification","launch_type":"cold"}}"""
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "logs.jsonl" to "$playbackLine\n$networkLine\n$lifecycleLine\n".encodeToByteArray(),
        )

        val hostedLogs = untar(gunzip(builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        ).bytes)).associateBy(TarEntry::name)
            .getValue("logs.jsonl").bytes.decodeToString()
        val hostedLines = hostedLogs.lineSequence().filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }.toList()

        assertEquals(
            setOf(
                "sink",
                "fmt",
                "width",
                "height",
                "hdr_mode",
                "bitrate_kbps",
                "dropped_frames",
                "audio_underruns",
            ),
            hostedLines[0].getValue("attrs").jsonObject.keys,
        )
        assertEquals(
            setOf("method", "path", "status", "duration_ms", "outcome", "error_code"),
            hostedLines[1].getValue("attrs").jsonObject.keys,
        )
        assertEquals(
            setOf("state", "phase", "duration_ms", "outcome", "reason", "launch_type"),
            hostedLines[2].getValue("attrs").jsonObject.keys,
        )
        assertEquals(
            "cold_start_classification",
            hostedLines[2].getValue("attrs").jsonObject.getValue("reason").jsonPrimitive.content,
        )
        for (withheld in listOf(
            "session_id",
            "play_method",
            "position_ms",
            "attempt",
            "private-server-playback-session",
            "operator-free-text-stop-reason",
        )) {
            assertFalse(hostedLogs.contains(withheld), "hosted logs must not carry $withheld: $hostedLogs")
        }

        val selfHostedLogs = untar(gunzip(builder.build(
            report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        ).bytes)).associateBy(TarEntry::name)
            .getValue("logs.jsonl").bytes.decodeToString()
        for (retained in listOf(
            "session_id",
            "play_method",
            "position_ms",
            "attempt",
            "private-server-playback-session",
            "operator-free-text-stop-reason",
            "cold_start_classification",
        )) {
            assertTrue(
                selfHostedLogs.contains(retained),
                "self-hosted logs must still carry $retained: $selfHostedLogs",
            )
        }
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
        assertTrue(shipped.contains("[redacted_private_id]"), shipped)
        Json.parseToJsonElement(shippedDevice)
        shippedLog.lineSequence().filter(String::isNotBlank).forEach { line -> Json.parseToJsonElement(line) }
    }

    @Test
    fun hostedBundleCanonicalizesLoopbackIdentityAcrossEveryTextSurfaceOnly() {
        val device = """{"host":"127.0.0.1","host.name":"LOCALHOST","server_url":"http://127.0.0.2:49152/device/42","server.url":"ws://[::1]:9000/device/42","origin":{"note":"removed"},"safe":{"hostname":"localhost","originUrl":"https://127.0.0.3/origin","base.url":"http://localhost/base","endpoint":"[::1]","address":"127.0.0.4","url":"http://[::1]:9001/device","server_instance_id":"keep","note":"device LOCALHOST 127.0.0.0 127.255.255.255 [::1] ::1 connect http://localhost:8080/items/42 url=http://127.0.0.5/private \"host\":\"127.0.0.12\" 'hostname'='localhost' \"playbackSessionId\":\"device-private-session\""}}""".encodeToByteArray()
        val logs = """{"ts":"2026-08-11T00:00:00Z","run":"::1","lvl":"E","cat":"network","tag":"http://127.0.0.2:49152/items/42","msg":"host=127.0.0.1 throwable LOCALHOST peer [::1] ws://[::1]:9000/users/99 server_instance_id=keep \"host\":\"127.0.0.13\" 'hostname'='localhost' \"playbackSessionId\":\"log-private-session\"","attrs":{"method":"GET","path":"/items/42","status":500,"duration_ms":2}}"""
            .plus('\n').encodeToByteArray()
        val breadcrumbs = """{"ts":"2026-08-11T00:00:01Z","run":"run-1","lvl":"I","cat":"focus","tag":"ws://127.0.0.3:9002/library/42","msg":"server_url='ws://[::1]:9001/items/42' origin=https://example.test/private bare 127.255.254.253 and ::1 \"host\":\"127.0.0.14\" 'hostname'='localhost' 'playbackSessionId'='breadcrumb-private-session'","attrs":{"target":"127.0.0.9","action":"baseUrl=http://localhost:1234/x"}}"""
            .plus('\n').encodeToByteArray()
        val crashSummary = """{"summary":"endpoint=http://127.0.0.5:8080/x bare localhost \"host\":\"127.0.0.15\" \"playbackSessionId\":\"summary-private-session\"","stack_excerpt":"peer ::1 and [::1] http://127.0.0.6:8080/items/42 'hostname'='localhost' 'playbackSessionId'='excerpt-private-session'","thread":"url='http://localhost:9000/private'"}"""
            .encodeToByteArray()
        val crashStack = (
            "IllegalStateException: hostname=\"LOCALHOST\" address=[::1] peer 127.0.0.7 ::1 [::1]\n" +
                "at ws://[::1]:9000/items/42 endpoint : http://127.0.0.8:8080/private\n" +
                "\"host\":\"127.0.0.16\" 'hostname'='localhost' \"playbackSessionId\":\"stack-private-session\"\n" +
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
                    captureSessionId =
                        "\"host\":\"127.0.0.1\" \"playbackSessionId\":\"manifest-private-session\"",
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
            assertFalse(text.contains("localhost", ignoreCase = true), "$name: $text")
            assertFalse(text.contains("127."), "$name: $text")
            assertFalse(text.contains("::1"), "$name: $text")
            assertFalse(text.contains("example.test"), "$name: $text")
            assertFalse(text.contains("already-safe"), "$name: $text")
            assertFalse(text.contains("private-session"), "$name: $text")
        }
        val hostedText = hostedSurfaces.values.joinToString("\n")
        assertTrue(hostedText.contains("http://redacted.invalid:49152/build/{id}"), hostedText)
        assertTrue(hostedText.contains("http://redacted.invalid:49152/items/{id}"), hostedText)
        assertTrue(hostedText.contains("ws://redacted.invalid:9000/redacted"), hostedText)
        assertTrue(hostedText.contains("ws://redacted.invalid:9002/library/{id}"), hostedText)
        assertTrue(hostedText.contains("http://redacted.invalid:8080/items/{id}"), hostedText)
        assertFalse(hostedText.contains("server_instance_id=keep"), hostedText)
        assertFalse(
            Regex(
                """(?i)(?<![a-z0-9_.\-'\"])[\"']?(?:host(?:[._-]?name)?|server(?:[._-]?url)?|base[._-]?url|origin(?:[._-]?url)?|endpoint(?:[._-]?url)?|address|url)[\"']?(?![a-z0-9_.-])\s*[:=]""",
            ).containsMatchIn(hostedText),
            hostedText,
        )
        assertFalse(
            Regex(
                """(?i)(?<![a-z0-9_.\-'\"])[\"']?(?:playback[_-]?session[_-]?id|session[_-]?id|(?:plan|selected|effective|requested|media)?[_-]?file[_-]?id|item[_-]?id|media[_-]?id|plan[_-]?id|playback[_-]?attempt[_-]?id|plan[_-]?attempt[_-]?key|subtitle[_-]?id|track[_-]?id)[\"']?(?![a-z0-9_.-])\s*[:=]""",
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
            setOf("note"),
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
            val captureSessionId = Json.parseToJsonElement(manifest).jsonObject
                .getValue("report").jsonObject
                .getValue("capture_session_id").jsonPrimitive.content
            assertTrue(captureSessionId.contains("\"host\":\"127.0.0.1\""), manifest)
            assertTrue(manifest.contains("http://localhost:49152/build/42"), manifest)
            assertTrue(manifest.contains("manifest-private-session"), manifest)
            assertFalse(manifest.contains("[redacted_network_identity]"), manifest)
        }
    }

    @Test
    fun hostedBundleNormalizesR8ObfuscatedCrashSymbolsWithoutChangingSelfHostedEvidence() {
        val rawStack = (
            "a.b: failure\n" +
                "    at a.b.c(SourceFile:42)\n" +
                "caused by c.d: nested failure\n" +
                "    at a.b.invokeSuspend(SourceFile:7)\n" +
                "java.lang.IllegalStateException: named failure\n" +
                "    at org.siloserver.silo.Player.play(Player.kt:9)\n"
            )
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "logs.jsonl" to (
                """{"ts":"2026-08-11T00:00:00Z","run":"run-1","lvl":"E","cat":"crash","tag":"Crash","msg":"playback failed\na.b: failure\ncaused by c.d: nested failure","attrs":{"fingerprint":"safe","source":"ueh"}}""" +
                    "\n"
                ).encodeToByteArray(),
            "crash/summary.json" to
                """{"throwable_type":"a.b","stack_excerpt":"a.b: failure\n    at a.b.c(SourceFile:42)"}"""
                    .encodeToByteArray(),
            "crash/stack.txt" to rawStack.encodeToByteArray(),
        )
        fun withCrashManifest(report: PendingReport): PendingReport = report.copy(
            manifest = report.manifest.copy(
                report = report.manifest.report.copy(type = DiagnosticsReportType.CRASH),
                crash = DiagnosticsCrashInfo(
                    summary = "a.b",
                    stackExcerpt = rawStack,
                    thread = "main",
                    foreground = true,
                    source = DiagnosticsCrashSource.UEH,
                    provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
                    occurredAt = "2026-08-11T00:00:00Z",
                ),
            ),
        )

        val hosted = builder.build(
            withCrashManifest(report(artifacts, DiagnosticsDestinationKind.HOSTED)),
            redactionTokens = emptyList(),
        )
        val hostedSurfaces = linkedMapOf(
            "outer manifest" to hosted.manifestBytes.decodeToString(),
            "embedded manifest" to hosted.sanitizedEntries.getValue("manifest.json").decodeToString(),
            "logs" to hosted.sanitizedEntries.getValue("logs.jsonl").decodeToString(),
            "crash summary" to hosted.sanitizedEntries.getValue("crash/summary.json").decodeToString(),
            "crash stack" to hosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString(),
        )
        hostedSurfaces.forEach { (name, text) ->
            assertTrue(text.contains("android-obfuscated-error"), "$name: $text")
            listOf("a.b:", "c.d:", "at a.b.").forEach { raw ->
                assertFalse(text.contains(raw), "$name leaked $raw: $text")
            }
        }
        listOf("outer manifest", "embedded manifest", "crash summary", "crash stack").forEach { name ->
            assertTrue(
                hostedSurfaces.getValue(name).contains("android-obfuscated-frame"),
                "$name: ${hostedSurfaces.getValue(name)}",
            )
        }
        val hostedStack = hostedSurfaces.getValue("crash stack")
        assertTrue(hostedStack.contains("at android-obfuscated-frame(SourceFile:42)"), hostedStack)
        assertTrue(hostedStack.contains("at android-obfuscated-frame(SourceFile:7)"), hostedStack)
        assertTrue(hostedStack.contains("java.lang.IllegalStateException: named failure"), hostedStack)
        assertTrue(hostedStack.contains("at org.siloserver.silo.Player.play(Player.kt:9)"), hostedStack)

        val selfHosted = builder.build(
            withCrashManifest(report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED)),
            redactionTokens = emptyList(),
        )
        artifacts.forEach { (path, bytes) ->
            assertContentEquals(bytes, selfHosted.sanitizedEntries.getValue(path), path)
        }
        val selfHostedManifests = listOf(
            selfHosted.manifestBytes.decodeToString(),
            selfHosted.sanitizedEntries.getValue("manifest.json").decodeToString(),
        )
        selfHostedManifests.forEach { manifest ->
            assertTrue(manifest.contains("a.b"), manifest)
            assertFalse(manifest.contains("android-obfuscated-error"), manifest)
            assertFalse(manifest.contains("android-obfuscated-frame"), manifest)
        }
    }

    @Test
    fun hostedBundleRedactsUnsafeCrashStackLinesWithoutDiscardingSafeFrames() {
        val rawStack = (
            "java.lang.IllegalStateException: content://private.authority/item/42\n" +
                "    at a.b.c(SourceFile:42)\n" +
                "    at java.base/java.lang.Thread.run(Thread.java:840)\n" +
                "    at app//com.example.Foo.bar(Foo.java:12)\n" +
                "    at app/my.module@1.0/com.example.Foo.baz(Foo.java:13)\n" +
                "    at org.siloserver.silo.Player.<init>(Player.kt:3)\n" +
                "    at org.siloserver.silo.Player.play(Player.kt:9)\n" +
                "caused by java.lang.IllegalArgumentException: nested failure\n" +
                "diagnostic source content://private.authority/item/42\n"
            )
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "crash/summary.json" to """{"kind":"jvm_crash"}""".encodeToByteArray(),
            "crash/stack.txt" to rawStack.encodeToByteArray(),
        )
        fun crashReport(destinationKind: DiagnosticsDestinationKind) = report(artifacts, destinationKind).let { value ->
            value.copy(
                manifest = value.manifest.copy(
                    report = value.manifest.report.copy(type = DiagnosticsReportType.CRASH),
                    crash = DiagnosticsCrashInfo(
                        summary = "java.lang.IllegalStateException",
                        stackExcerpt = rawStack,
                        thread = "main",
                        foreground = true,
                        source = DiagnosticsCrashSource.UEH,
                        provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
                        occurredAt = "2026-08-11T00:00:00Z",
                    ),
                ),
            )
        }

        val hosted = builder.build(
            crashReport(DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        )
        val hostedStack = hosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString()
        val hostedExcerpt = Json.parseToJsonElement(hosted.manifestBytes.decodeToString()).jsonObject
            .getValue("crash").jsonObject
            .getValue("stack_excerpt").jsonPrimitive.content

        listOf(hostedStack, hostedExcerpt).forEach { text ->
            assertTrue(text.lineSequence().any { it == "java.lang.IllegalStateException" }, text)
            assertTrue(text.contains("at android-obfuscated-frame(SourceFile:42)"), text)
            assertTrue(text.contains("at java.lang.Thread.run(Thread.java:840)"), text)
            assertTrue(text.contains("at com.example.Foo.bar(Foo.java:12)"), text)
            assertTrue(text.contains("at com.example.Foo.baz(Foo.java:13)"), text)
            assertTrue(text.contains("at org.siloserver.silo.Player.<init>(Player.kt:3)"), text)
            assertTrue(text.contains("at org.siloserver.silo.Player.play(Player.kt:9)"), text)
            assertTrue(text.lineSequence().any { it == "caused by java.lang.IllegalArgumentException" }, text)
            assertTrue(text.contains("[redacted_private_id]"), text)
            assertFalse(text.contains("named failure"), text)
            assertFalse(text.contains("nested failure"), text)
            assertFalse(text.contains("content://"), text)
            assertFalse(text.contains("private.authority"), text)
        }

        val selfHosted = builder.build(
            crashReport(DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        )
        assertEquals(rawStack, selfHosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString())
    }

    @Test
    fun hostedBundleStillFailsClosedWhenEveryCrashStackLineIsUnsafe() {
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "crash/stack.txt" to (
                "content://private.authority/item/42\n" +
                    "custom://another.private/source\n"
                ).encodeToByteArray(),
        )

        val hosted = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        )

        assertEquals(
            "[redacted_private_id]",
            hosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString(),
        )
    }

    @Test
    fun hostedBundleDoesNotLeakPrivateContextSplitAcrossCrashStackLines() {
        val privateHost = "private-deployment-host"
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "crash/stack.txt" to (
                "server content://private.authority/item/42\n" +
                    "java.lang.IllegalStateException: $privateHost\n" +
                    "    at org.siloserver.silo.Player.play(Player.kt:9)\n"
                ).encodeToByteArray(),
        )

        val hosted = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries.getValue("crash/stack.txt").decodeToString()

        assertTrue(hosted.contains("[redacted_private_id]"), hosted)
        assertTrue(hosted.lineSequence().any { it == "java.lang.IllegalStateException" }, hosted)
        assertTrue(hosted.contains("at org.siloserver.silo.Player.play(Player.kt:9)"), hosted)
        assertFalse(hosted.contains("content://"), hosted)
        assertFalse(hosted.contains(privateHost), hosted)
    }

    @Test
    fun hostedBundleBoundsCrashExcerptAfterUnsafeLineReplacementExpandsIt() {
        val rawStack = buildString {
            repeat(850) {
                append("x://\n")
                append("    at org.siloserver.silo.Player.play(Player.kt:9)\n")
            }
        }.take(8 * 1_024)
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "crash/stack.txt" to rawStack.encodeToByteArray(),
        )
        val report = report(artifacts, DiagnosticsDestinationKind.HOSTED).let { value ->
            value.copy(
                manifest = value.manifest.copy(
                    report = value.manifest.report.copy(type = DiagnosticsReportType.CRASH),
                    crash = DiagnosticsCrashInfo(
                        summary = "java.lang.IllegalStateException",
                        stackExcerpt = rawStack,
                        thread = "main",
                        foreground = true,
                        source = DiagnosticsCrashSource.UEH,
                        provenance = DiagnosticsCrashProvenance.PRE_FAILURE,
                        occurredAt = "2026-08-11T00:00:00Z",
                    ),
                ),
            )
        }

        val hosted = builder.build(report, redactionTokens = emptyList())
        val hostedExcerpt = Json.parseToJsonElement(hosted.manifestBytes.decodeToString()).jsonObject
            .getValue("crash").jsonObject
            .getValue("stack_excerpt").jsonPrimitive.content

        assertTrue(hostedExcerpt.encodeToByteArray().size <= 8 * 1_024, hostedExcerpt.length.toString())
        assertTrue(hostedExcerpt.contains("at org.siloserver.silo.Player.play(Player.kt:9)"), hostedExcerpt)
        assertFalse(hostedExcerpt.contains("x://"), hostedExcerpt)
        assertTrue(
            hostedExcerpt.removeSuffix("\n").lineSequence().all { line ->
                line == "[redacted_private_id]" ||
                    line == "    at org.siloserver.silo.Player.play(Player.kt:9)"
            },
            hostedExcerpt.takeLast(80),
        )
    }

    @Test
    fun hostedBundleRedactsBareAndPrefixedPrivateIdsButPreservesCanonicalCaptureAndRunFields() {
        val captureId = "run_0123456789abcdef0123456789abcdef"
        val structuredRunId = "run_99999999999999999999999999999999"
        val structuredBreadcrumbRunId = "0198a8f8-5678-4abc-8def-0123456789ab"
        val freeUuid = "0198a8f8-9999-4abc-8def-0123456789ab"
        val privateTokens = listOf(
            "ps-1",
            "playback_2",
            "session-3",
            "file_4",
            "item-5",
            "media_6",
            "plan-7",
            "attempt_8",
            "profile-9",
            "account_10",
            "user-11",
            "device_12",
            "content-13",
            "library_14",
            "request-15",
            "req_16",
            "correlation-abcdefgh",
            "server-17",
            "subtitle-18",
            "track_19",
            "run-20",
        )
        val semanticTokens = listOf(
            "request_cancelled",
            "request_completed",
            "session_unavailable",
            "playback_unavailable",
            "file_not_found",
            "plan_invalidated",
            "item_count",
        )
        val freeText = (listOf("Request", freeUuid) + privateTokens + semanticTokens).joinToString(" ")
        val logs =
            """{"ts":"2026-08-11T00:00:00Z","run":"$structuredRunId","lvl":"E","cat":"crash","tag":"request-15","msg":"$freeText","attrs":{"fingerprint":"correlation-abcdefgh","source":"file_4"}}""" +
                "\n"
        val breadcrumbs =
            """{"ts":"2026-08-11T00:00:01Z","run":"$structuredBreadcrumbRunId","lvl":"I","cat":"focus","tag":"req_16","msg":"$freeText","attrs":{"target":"user-11","action":"request_cancelled"}}""" +
                "\n"
        val device =
            """{"note":"$freeText","nested":{"request":"request_abcdefgh","url_note":"http://redacted.invalid/items/request-15"}}"""
        val crashSummary = """{"summary":"$freeText","stack_excerpt":"Request $freeUuid failed"}"""
        val crashStack = "IllegalStateException: $freeText\n    at Safe.Frame.method(Source.kt:1)\n"
        val artifacts = mapOf(
            "device.json" to device.encodeToByteArray(),
            "logs.jsonl" to logs.encodeToByteArray(),
            "breadcrumbs.jsonl" to breadcrumbs.encodeToByteArray(),
            "crash/summary.json" to crashSummary.encodeToByteArray(),
            "crash/stack.txt" to crashStack.encodeToByteArray(),
        )
        fun withPrivateManifest(report: PendingReport): PendingReport = report.copy(
            manifest = report.manifest.copy(
                report = report.manifest.report.copy(
                    captureSessionId = captureId,
                    appVersion = "request_abcdefgh",
                    appBuild = "request_cancelled",
                    osVersion = "Request $freeUuid failed",
                ),
                deviceSummary = report.manifest.deviceSummary.copy(
                    manufacturer = "device_12",
                    model = "request_completed",
                ),
            ),
        )

        val hosted = builder.build(
            withPrivateManifest(report(artifacts, DiagnosticsDestinationKind.HOSTED)),
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
            assertFalse(text.contains(freeUuid), "$name leaked $freeUuid: $text")
            privateTokens.forEach { token ->
                assertFalse(text.contains(token), "$name leaked $token: $text")
            }
            assertTrue(text.contains("[redacted_private_id]"), "$name: $text")
        }
        val outerManifest = Json.parseToJsonElement(hostedSurfaces.getValue("outer manifest")).jsonObject
        val embeddedManifest = Json.parseToJsonElement(hostedSurfaces.getValue("embedded manifest")).jsonObject
        listOf(outerManifest, embeddedManifest).forEach { manifest ->
            val hostedCaptureId =
                manifest.getValue("report").jsonObject.getValue("capture_session_id").jsonPrimitive.content
            assertTrue(CANONICAL_UUID.matches(hostedCaptureId), hostedCaptureId)
            assertFalse(hostedCaptureId.contains(captureId), hostedCaptureId)
        }
        val hostedLog = Json.parseToJsonElement(hostedSurfaces.getValue("logs").trim()).jsonObject
        val hostedBreadcrumb = Json.parseToJsonElement(hostedSurfaces.getValue("breadcrumbs").trim()).jsonObject
        assertTrue(CANONICAL_UUID.matches(hostedLog.getValue("run").jsonPrimitive.content))
        assertEquals(structuredBreadcrumbRunId, hostedBreadcrumb.getValue("run").jsonPrimitive.content)
        assertTrue(hostedSurfaces.getValue("device").contains("[redacted_private_id]"))
        semanticTokens.forEach { token ->
            assertTrue(hostedSurfaces.values.any { it.contains(token) }, "missing semantic token $token")
        }

        val selfHosted = builder.build(
            withPrivateManifest(report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED)),
            redactionTokens = emptyList(),
        )
        artifacts.forEach { (path, bytes) ->
            assertContentEquals(bytes, selfHosted.sanitizedEntries.getValue(path), path)
        }
        assertTrue(selfHosted.manifestBytes.decodeToString().contains(freeUuid))
        assertTrue(selfHosted.manifestBytes.decodeToString().contains("request_abcdefgh"))
    }

    @Test
    fun hostedLoopbackNormalizationRequiresLiteralTokenBoundariesAndValidIpv4Octets() {
        val nearMisses = listOf(
            "mylocalhost",
            "127.0.0.256",
            "1127.0.0.1",
        )
        nearMisses.forEach { value ->
            val bundle = builder.build(
                report(
                    artifacts = mapOf(
                        "device.json" to "{}".encodeToByteArray(),
                        "crash/stack.txt" to value.encodeToByteArray(),
                    ),
                    destinationKind = DiagnosticsDestinationKind.HOSTED,
                ),
                redactionTokens = emptyList(),
            )
            assertEquals(
                value,
                bundle.sanitizedEntries.getValue("crash/stack.txt").decodeToString(),
                value,
            )

            val selfHosted = builder.build(
                report(
                    artifacts = mapOf(
                        "device.json" to "{}".encodeToByteArray(),
                        "crash/stack.txt" to value.encodeToByteArray(),
                    ),
                    destinationKind = DiagnosticsDestinationKind.SELF_HOSTED,
                ),
                redactionTokens = emptyList(),
            )
            assertEquals(value, selfHosted.sanitizedEntries.getValue("crash/stack.txt").decodeToString())
        }
    }

    @Test
    fun hostedBundleNormalizesBareNetworkAndAndroidPathProseAcrossEveryTextSurface() {
        val privateIp = "192.168.1.44"
        val privateDns = "silo.home.ArpaServer"
        val privatePath = "/data/user/0/org.siloserver.silo/files/diagnostics.log"
        val compactId = "0123456789abcdef0123456789abcdef"
        val transportError =
            "java.net.ConnectException: Failed to connect to /$privateIp:8096; " +
                "java.net.UnknownHostException: Unable to resolve host \"$privateDns\"; file=$privatePath; " +
                "user_id=alice password=hunter2 request_id=req_abcdefgh peer=0x7f000001; " +
                "targets 2130706433 017700000001 127.1 127.0x000001; " +
                "unicode https://silo。home/users/42; routes /api/v1/items/42 and " +
                "/api?token=secretvalue; request $compactId"
        val jsonTransportError = transportError.replace("\\", "\\\\").replace("\"", "\\\"")
        val logs =
            """{"ts":"2026-08-11T00:00:00.123Z","run":"run_0123456789abcdef0123456789abcdef","lvl":"E","cat":"network","tag":"$privateDns","msg":"$jsonTransportError","attrs":{"method":"GET","path":"/items/42","status":503,"duration_ms":5}}"""
                .plus('\n').encodeToByteArray()
        val crashSummary =
            """{"kind":"jvm_crash","summary":"$jsonTransportError","stack_excerpt":"$jsonTransportError"}"""
                .encodeToByteArray()
        val crashStack = (transportError + "\n    at java.net.Socket.connect(Socket.java:42)\n").encodeToByteArray()
        val device =
            """{"captured_at":"2026-08-11T00:00:00.987654321Z","note":"$jsonTransportError","user_id":"alice","nested":{"password":"hunter2"}}"""
                .encodeToByteArray()
        val artifacts = mapOf(
            "device.json" to device,
            "logs.jsonl" to logs,
            "crash/summary.json" to crashSummary,
            "crash/stack.txt" to crashStack,
        )
        fun withNetworkManifest(report: PendingReport) = report.copy(
            manifest = report.manifest.copy(
                report = report.manifest.report.copy(
                    capturedAt = "2026-08-11T00:00:00.456Z",
                    captureSessionId = "run_0123456789abcdef0123456789abcdef",
                    appVersion = privateDns,
                ),
                deviceSummary = report.manifest.deviceSummary.copy(model = "peer=$privateIp"),
            ),
        )

        val hosted = builder.build(
            withNetworkManifest(report(artifacts, DiagnosticsDestinationKind.HOSTED)),
            redactionTokens = emptyList(),
        )
        val hostedSurfaces = hosted.sanitizedEntries.values.map(ByteArray::decodeToString) +
            hosted.manifestBytes.decodeToString()
        hostedSurfaces.forEach { text ->
            assertFalse(text.contains(privateIp), text)
            assertFalse(text.contains(privateDns), text)
            assertFalse(text.contains(privatePath), text)
            assertFalse(text.contains("user_id"), text)
            assertFalse(text.contains("password"), text)
            assertFalse(text.contains("request_id"), text)
            assertFalse(text.contains("peer="), text)
            listOf("2130706433", "017700000001", "127.1", "127.0x000001", "silo。home")
                .forEach { value -> assertFalse(text.contains(value), text) }
            assertFalse(text.contains("/api"), text)
            assertFalse(text.contains(compactId), text)
        }
        val hostedText = hostedSurfaces.joinToString("\n")
        assertTrue(hostedText.contains("[redacted_private_id]"), hostedText)
        listOf(
            "2026-08-11T00:00:00.123Z",
            "2026-08-11T00:00:00.456Z",
            "2026-08-11T00:00:00.987654321Z",
        ).forEach { timestamp -> assertTrue(hostedText.contains(timestamp), hostedText) }

        val selfHosted = builder.build(
            withNetworkManifest(report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED)),
            redactionTokens = emptyList(),
        )
        artifacts.forEach { (path, bytes) ->
            assertContentEquals(bytes, selfHosted.sanitizedEntries.getValue(path), path)
        }
        assertTrue(selfHosted.manifestBytes.decodeToString().contains(privateDns))
        assertTrue(selfHosted.manifestBytes.decodeToString().contains("peer=$privateIp"))
    }

    @Test
    fun hostedDeviceSnapshotOmitsDeterministicRouteDeviceAndBuildIdentifiersOnlyForHosted() {
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

        listOf(
            "build_fingerprint_hash",
            "route_hashes",
            "\"id\"",
            "\"address\"",
            "a".repeat(32),
            "b".repeat(32),
            "c".repeat(32),
            "d".repeat(32),
        )
            .forEach { value -> assertFalse(hostedDevice.contains(value), hostedDevice) }
        assertTrue(selfHostedDevice.contains("build_fingerprint_hash"), selfHostedDevice)
        assertTrue(selfHostedDevice.contains("route_hashes"), selfHostedDevice)
        assertTrue(selfHostedDevice.contains("\"id\""), selfHostedDevice)
        assertTrue(selfHostedDevice.contains("\"address\""), selfHostedDevice)
    }

    @Test
    fun hostedCrashSummaryOmitsProcessIdentityOnlyForHosted() {
        val processHash = "e".repeat(32)
        val summary =
            """{"kind":"native_crash","process_hash":"$processHash","pid":42,"status":6}"""
        val artifacts = mapOf(
            "device.json" to "{}".encodeToByteArray(),
            "crash/summary.json" to summary.encodeToByteArray(),
        )

        val hostedSummary = builder.build(
            report(artifacts, DiagnosticsDestinationKind.HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries.getValue("crash/summary.json").decodeToString()
        val selfHostedSummary = builder.build(
            report(artifacts, DiagnosticsDestinationKind.SELF_HOSTED),
            redactionTokens = emptyList(),
        ).sanitizedEntries.getValue("crash/summary.json").decodeToString()

        assertFalse(hostedSummary.contains("process_hash"), hostedSummary)
        assertFalse(hostedSummary.contains(processHash), hostedSummary)
        assertTrue(hostedSummary.contains("\"kind\":\"native_crash\""), hostedSummary)
        assertEquals(summary, selfHostedSummary)
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
        val CANONICAL_UUID = Regex(
            """(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""",
        )
    }
}
