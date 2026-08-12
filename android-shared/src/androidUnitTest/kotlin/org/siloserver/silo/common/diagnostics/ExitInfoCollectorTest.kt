package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsCrashSource
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExitInfoCollectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nativeCrashStoresOpaqueTombstoneWithoutPuttingItInManifest() = runTest {
        val fixture = fixture(
            records = listOf(exit(reason = AndroidExitReason.NATIVE_CRASH, trace = byteArrayOf(1, 2, 3))),
        )

        val report = fixture.collector.collect().single()

        assertContentEquals(byteArrayOf(1, 2, 3), report.directory.resolve("crash/tombstone.pb").readBytes())
        assertNull(report.manifest.crash?.stackExcerpt)
        assertEquals(DiagnosticsReportType.NATIVE_CRASH, report.manifest.report.type)
        assertEquals(DiagnosticsCrashSource.EXIT_INFO, report.manifest.crash?.source)
    }

    @Test
    fun unmatchedRunTokenIsNotUploadable() = runTest {
        var traceReads = 0
        val fixture = fixture(
            records = listOf(
                exit(
                    processStateSummary = "f".repeat(32).encodeToByteArray(),
                    trace = "must not read".encodeToByteArray(),
                    onTrace = { traceReads += 1 },
                ),
            ),
        )

        assertTrue(fixture.collector.collect().isEmpty())
        assertTrue(fixture.store.list(BINDING).isEmpty())
        assertEquals(0, traceReads)
    }

    @Test
    fun exitFingerprintIsRecordedOnlyOnce() = runTest {
        val fixture = fixture(records = listOf(exit(reason = AndroidExitReason.ANR, trace = "main blocked".encodeToByteArray())))

        assertEquals(1, fixture.collector.collect().size)
        assertTrue(fixture.collector.collect().isEmpty())
        assertEquals(1, fixture.store.list(BINDING).size)
    }

    @Test
    fun anrTraceIsRedactedBeforePendingStorage() = runTest {
        val fixture = fixture(
            records = listOf(
                exit(
                    reason = AndroidExitReason.ANR,
                    trace = "Authorization: Bearer secret-token".encodeToByteArray(),
                ),
            ),
        )

        val report = fixture.collector.collect().single()
        val stack = report.directory.resolve("crash/stack.txt").readText()

        assertFalse(stack.contains("secret-token"))
        assertTrue(stack.contains("[REDACTED]"))
    }

    @Test
    fun anrIncludesPersistedBreadcrumbsFromTheExitedRun() = runTest {
        val breadcrumb = "{\"run\":\"capture-1\",\"msg\":\"foreground\"}"
        val fixture = fixture(
            records = listOf(exit(reason = AndroidExitReason.ANR, trace = "main blocked".encodeToByteArray())),
            breadcrumbs = DiagnosticsBreadcrumbSource { captureSessionId, _ ->
                if (captureSessionId == "capture-1") listOf(breadcrumb) else emptyList()
            },
        )

        val report = fixture.collector.collect().single()

        assertEquals("$breadcrumb\n", report.directory.resolve("breadcrumbs.jsonl").readText())
        assertTrue("breadcrumbs.jsonl" in report.manifest.archive.entries)
    }

    @Test
    fun matchingJvmMarkerWinsOverDuplicateExitRecord() = runTest {
        val marker = JvmCrashMarkerRecord(
            occurredAtEpochMs = EXIT_AT,
            threadName = "main-secret-token",
            threadId = 1,
            throwableType = "java.lang.IllegalStateException-secret-token",
            stack = "java.lang.IllegalStateException: secret-token",
            binding = PendingReportBinding("server-1", "user-1", "profile-1", 7),
            captureSessionId = "capture-1",
            runToken = RUN_TOKEN,
            foreground = true,
            playbackSessionIds = listOf("playback-1"),
            deviceSnapshotJson = DEVICE_JSON,
            logLines = listOf(
                "{\"ts\":\"2026-07-22T00:00:00Z\",\"run\":\"capture-1\",\"lvl\":\"I\",\"cat\":\"crash\",\"tag\":\"Test\",\"msg\":\"Authorization: Bearer secret-token\"}",
            ),
            logDroppedCount = 0,
            logTornCount = 0,
            logGeneration = 7,
            truncated = false,
        )
        val markers = FakeMarkerSource(listOf(marker))
        val fixture = fixture(
            records = listOf(exit(reason = AndroidExitReason.JVM_CRASH, timestampMs = EXIT_AT + 100)),
            markers = markers,
        )

        val reports = fixture.collector.collect()

        assertEquals(1, reports.size)
        assertEquals(DiagnosticsCrashSource.UEH, reports.single().manifest.crash?.source)
        assertFalse(reports.single().manifest.crash?.summary.orEmpty().contains("secret-token"))
        assertFalse(reports.single().manifest.crash?.thread.orEmpty().contains("secret-token"))
        assertFalse(reports.single().directory.resolve("crash/stack.txt").readText().contains("secret-token"))
        assertTrue(reports.single().directory.resolve("crash/stack.txt").readText().contains("[REDACTED]"))
        assertFalse(reports.single().directory.resolve("logs.jsonl").readText().contains("secret-token"))
        assertTrue(reports.single().directory.resolve("logs.jsonl").readText().contains("[REDACTED]"))
        assertEquals(listOf(marker), markers.deleted)
        assertTrue(fixture.collector.collect().isEmpty())
        assertEquals(1, fixture.store.list(BINDING).size)
    }

    @Test
    fun renderedHostedJvmMarkerMatchesHostedRunAndIsCollectedThenDeleted() = runTest {
        val root = temporaryFolder.newFolder()
        val hostedBinding = DiagnosticsBinding(
            HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            "anonymous-hosted-device",
        )
        val context = DiagnosticsCaptureContext(
            binding = hostedBinding,
            profileId = null,
            profileEligible = true,
            noticeVersion = 2,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { RUN_TOKEN },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        ledger.beginRun(context, EXIT_AT - 10_000, "hosted-capture")
        val marker = Json.decodeFromString<JvmCrashMarkerRecord>(
            CrashMarkerRenderer().render(
                thread = Thread.currentThread(),
                throwable = IllegalStateException("hosted crash"),
                runtime = CrashRuntimeSnapshot(
                    binding = PendingReportBinding(
                        serverInstanceId = hostedBinding.serverInstanceId,
                        accountUserId = hostedBinding.accountUserId,
                        profileId = null,
                        ownershipGeneration = context.ownershipGeneration,
                        destinationKind = DiagnosticsDestinationKind.HOSTED,
                    ),
                    captureSessionId = "hosted-capture",
                    runToken = RUN_TOKEN,
                    foreground = true,
                    playbackSessionIds = listOf("private-playback-session"),
                    deviceSnapshotJson = DEVICE_JSON,
                ),
                occurredAtEpochMs = EXIT_AT,
            ).decodeToString(),
        )
        assertEquals(DiagnosticsDestinationKind.HOSTED, marker.binding?.destinationKind)
        val markers = FakeMarkerSource(listOf(marker))
        val store = FilePendingReportStore(
            root,
            nowMs = { EXIT_AT + 1_000 },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val collector = ExitInfoCollector(
            source = AndroidExitInfoSource {
                listOf(exit(reason = AndroidExitReason.JVM_CRASH, timestampMs = EXIT_AT + 100))
            },
            ledger = ledger,
            reports = store,
            markers = markers,
            environment = ExitReportEnvironment(
                appVersion = "1.0",
                appBuild = "1",
                platform = DiagnosticsPlatform.ANDROID,
                osVersion = "Android 36",
                deviceSummary = DiagnosticsDeviceSummary("Google", "Pixel", "Android 36", "phone"),
            ),
            deviceSnapshotBytes = { DEVICE_JSON.encodeToByteArray() },
            noticeVersion = { 2 },
        )

        val report = collector.collect().single()

        assertEquals(DiagnosticsDestinationKind.HOSTED, report.binding.destinationKind)
        assertEquals(hostedBinding, report.binding.binding)
        assertNull(report.binding.profileId)
        assertTrue(report.manifest.playbackSessionIds.isEmpty())
        assertFalse(report.directory.resolve("manifest.json").readText().contains("private-playback-session"))
        assertEquals(listOf(marker), markers.deleted)
        assertEquals(listOf(report.id), store.list(hostedBinding).map(PendingReport::id))
        assertTrue(collector.collect().isEmpty())
    }

    private suspend fun fixture(
        records: List<AndroidExitInfoRecord>,
        markers: FakeMarkerSource = FakeMarkerSource(emptyList()),
        breadcrumbs: DiagnosticsBreadcrumbSource = DiagnosticsBreadcrumbSource.None,
    ): Fixture {
        val root = temporaryFolder.newFolder()
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { RUN_TOKEN },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        ledger.beginRun(
            context = DiagnosticsCaptureContext(
                binding = BINDING,
                profileId = "profile-1",
                profileEligible = true,
                noticeVersion = 2,
                status = DiagnosticsAvailabilityStatus.AVAILABLE,
                ownershipGeneration = 7,
            ),
            processStartedAtEpochMs = EXIT_AT - 10_000,
            captureSessionId = "capture-1",
        )
        val store = FilePendingReportStore(
            root,
            nowMs = { EXIT_AT + 1_000 },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val collector = ExitInfoCollector(
            source = AndroidExitInfoSource { records },
            ledger = ledger,
            reports = store,
            markers = markers,
            environment = ExitReportEnvironment(
                appVersion = "1.0",
                appBuild = "1",
                platform = DiagnosticsPlatform.ANDROID_TV,
                osVersion = "Android 36",
                deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
            ),
            deviceSnapshotBytes = { DEVICE_JSON.encodeToByteArray() },
            noticeVersion = { 2 },
            redactionTokens = { listOf("secret-token") },
            breadcrumbs = breadcrumbs,
        )
        return Fixture(collector, store)
    }

    private fun exit(
        reason: Int = AndroidExitReason.JVM_CRASH,
        timestampMs: Long = EXIT_AT,
        processStateSummary: ByteArray? = RUN_TOKEN.encodeToByteArray(),
        trace: ByteArray? = null,
        onTrace: () -> Unit = {},
    ) = object : AndroidExitInfoRecord {
        override val reason = reason
        override val timestampMs = timestampMs
        override val pid = 123
        override val processName = "org.siloserver.silo"
        override val status = 0
        override val processStateSummary = processStateSummary
        override fun trace(maxBytes: Int): ByteArray? {
            onTrace()
            return trace?.copyOf(maxBytes.coerceAtMost(trace.size))
        }
    }

    private class FakeMarkerSource(private val markers: List<JvmCrashMarkerRecord>) : JvmCrashMarkerSource {
        val deleted = mutableListOf<JvmCrashMarkerRecord>()
        override fun records(): List<JvmCrashMarkerRecord> = markers.filterNot(deleted::contains)
        override fun delete(marker: JvmCrashMarkerRecord) {
            deleted += marker
        }
    }

    private data class Fixture(
        val collector: ExitInfoCollector,
        val store: FilePendingReportStore,
    )

    private companion object {
        val BINDING = DiagnosticsBinding("server-1", "user-1")
        const val RUN_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXIT_AT = 1_700_000_000_000L
        const val DEVICE_JSON = "{\"captured_at\":\"2026-07-22T00:00:00Z\"}"
    }
}
