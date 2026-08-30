package org.siloserver.silo.common.diagnostics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsPrivacyIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        SiloLog.installSink(null)
    }

    @Test
    fun childThenAdultManualBundleHasNoChildGeneration() = runTest {
        val root = temporaryFolder.newFolder()
        val ring = LogRing()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val capture = captureController(root, ring, reports, UnconfinedTestDispatcher(testScheduler))
        val identity = MutableIdentity(CHILD)
        val transitions = DefaultIdentityTransitionBarrier()
        val coordinator = coordinator(
            root = root,
            identity = identity,
            transitions = transitions,
            capture = capture,
            reports = reports,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
        )
        coordinator.start()
        coordinator.refresh()

        SiloLog.i(DiagnosticsLogCategory.OTHER, "PrivacyTest", "child evidence")
        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            identity.current = ADULT
        }
        coordinator.refresh()
        SiloLog.i(DiagnosticsLogCategory.OTHER, "PrivacyTest", "adult evidence")

        val reportId = requireNotNull(coordinator.captureNow())
        val report = requireNotNull(reports.load(reportId))
        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        val archiveText = GZIPInputStream(ByteArrayInputStream(bundle.bytes)).use { it.readBytes() }.decodeToString()

        assertFalse(archiveText.contains("child evidence"))
        assertTrue(archiveText.contains("adult evidence"))
        assertEquals(ADULT.ownershipGeneration, report.binding.ownershipGeneration)
        assertEquals(ADULT.profileId, report.binding.profileId)
    }

    @Test
    fun publishedManualReportDoesNotHideFrozenRawCleanupFailureAndRestartRetriesIt() = runTest {
        val root = temporaryFolder.newFolder()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        var failFrozenCleanup = false
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = root,
            writerDispatcher = dispatcher,
            deleteRecursively = { target ->
                if (failFrozenCleanup && target.name == "generation-1") false else target.deleteRecursively()
            },
            directorySync = {},
        )
        val capture = FileDiagnosticsCaptureController(
            logBuffer = LogRing(),
            fileLogger = logger,
            reports = reports,
            deviceSnapshots = DeviceSnapshotCollector(StableProbe, nowRfc3339 = { "2026-07-22T00:00:00Z" }),
            deviceSnapshotCache = DeviceSnapshotCache(),
            environment = ENVIRONMENT,
            nowMs = { CAPTURED_AT },
            sessionIdFactory = { "manual-cleanup-boundary" },
        )
        val active = capture.start(ADULT)
        SiloLog.i(DiagnosticsLogCategory.OTHER, "CleanupBoundary", "raw captured line")
        failFrozenCleanup = true

        assertFailsWith<IllegalStateException> { capture.stop(active, ADULT) }

        assertEquals(1, reports.list(ADULT.binding).size, "bounded report was published first")
        val rawGeneration = root.resolve("client-diagnostics/logs/generation-1")
        assertTrue(rawGeneration.isDirectory, "failed cleanup must remain observable")

        DiagnosticsFileLogger(root, writerDispatcher = dispatcher, directorySync = {})

        assertFalse(rawGeneration.exists(), "restart reconciliation must remove detached raw bytes")
    }

    @Test
    fun debugAndTimedCaptureImmediatelyReenablePersistentBreadcrumbs() = runTest {
        val root = temporaryFolder.newFolder()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val journal = BreadcrumbJournal(root, writerDispatcher = dispatcher, directorySync = {})
        val capture = FileDiagnosticsCaptureController(
            logBuffer = LogRing(),
            fileLogger = DiagnosticsFileLogger(root, dispatcher, directorySync = {}),
            breadcrumbJournal = journal,
            reports = FilePendingReportStore(
                root,
                nowMs = { CAPTURED_AT },
                directorySync = {},
                atomicRename = ::testAtomicRename,
            ),
            deviceSnapshots = DeviceSnapshotCollector(StableProbe, nowRfc3339 = { "2026-07-22T00:00:00Z" }),
            deviceSnapshotCache = DeviceSnapshotCache(),
            environment = ENVIRONMENT,
            nowMs = { CAPTURED_AT },
        )

        capture.setPersistentBreadcrumbs(ADULT, true)
        capture.setDebugLogging(ADULT, true)
        val debugLine = diagnosticsLine("debug-run", "debug capture breadcrumb")
        journal.offer(debugLine)

        assertEquals(listOf(debugLine), journal.linesForRun("debug-run", ADULT.identityKey))

        capture.start(ADULT)
        val timedLine = diagnosticsLine("timed-run", "timed capture breadcrumb")
        journal.offer(timedLine)

        assertEquals(listOf(timedLine), journal.linesForRun("timed-run", ADULT.identityKey))
    }

    @Test
    fun hostedManualBundleOmitsSourceServerAccountAndProfileIdentity() = runTest {
        val root = temporaryFolder.newFolder()
        val ring = LogRing()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val context = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, LOCAL_HOSTED_OWNER),
            profileId = null,
            profileEligible = true,
            noticeVersion = 1,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 3,
            localServerId = SOURCE_SERVER_ID,
            credentialFingerprint = "f".repeat(64),
            sourceProfileId = SOURCE_PROFILE_ID,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val playbackSessions = DiagnosticsPlaybackSessionTracker().apply {
            open(context.identityKey)
            record(PRIVATE_PLAYBACK_SESSION_ID)
        }
        val capture = captureController(
            root,
            ring,
            reports,
            UnconfinedTestDispatcher(testScheduler),
            playbackSessions,
        )
        SiloLog.installSink(ring)
        SiloLog.i(
            DiagnosticsLogCategory.NETWORK,
            "PrivacyTest",
            "request to $SOURCE_SERVER_URL profile=$SOURCE_PROFILE_ID account=$SOURCE_ACCOUNT_ID",
        )
        SiloLog.i(
            DiagnosticsLogCategory.PLAYBACK,
            "PrivacyTest",
            "playback_session_id=$PRIVATE_PLAYBACK_SESSION_ID",
            mapOf(
                "decoder" to SiloLogAttribute.Text("safe-decoder"),
                "buffered_ms" to SiloLogAttribute.Integer(1_200),
            ),
        )

        val report = requireNotNull(capture.captureNow(context))
        assertTrue(report.manifest.playbackSessionIds.isEmpty(), "hosted capture must strip playback ids")
        val capturedLogs = report.directory.resolve("logs.jsonl").readText()
        assertTrue(capturedLogs.contains(SOURCE_PROFILE_ID))
        assertTrue(capturedLogs.contains(SOURCE_ACCOUNT_ID))
        val framed = report.withCurrentConsent(DiagnosticsConsentMode.ALWAYS, context.noticeVersion)
        val bundle = FileDiagnosticsBundleBuilder().build(
            framed,
            redactionTokens = listOf(SOURCE_SERVER_URL, SOURCE_PROFILE_ID, SOURCE_ACCOUNT_ID),
        )
        val outerManifest = bundle.manifestBytes.decodeToString()
        val archive = GZIPInputStream(ByteArrayInputStream(bundle.bytes)).use { it.readBytes() }.decodeToString()

        assertEquals(DiagnosticsDestinationKind.HOSTED, report.binding.destinationKind)
        assertEquals(null, bundle.manifest.report.profileId)
        assertEquals(HOSTED_DIAGNOSTICS_COLLECTOR_ID, bundle.manifest.destination.serverInstanceId)
        assertTrue(archive.contains("android-decoder"), "collector-v1 playback decoder family must remain")
        assertFalse(archive.contains("buffered_ms"), "extended Android attributes must not ship hosted")
        listOf(
            SOURCE_SERVER_URL,
            SOURCE_SERVER_ID,
            SOURCE_PROFILE_ID,
            SOURCE_ACCOUNT_ID,
            LOCAL_HOSTED_OWNER,
            PRIVATE_PLAYBACK_SESSION_ID,
        ).forEach { identity ->
            assertFalse(outerManifest.contains(identity), "outer manifest: $identity")
            assertFalse(archive.contains(identity), "embedded manifest/logs: $identity")
        }
    }

    @Test
    fun hostedCrashBundleStripsSourceAndPlaybackIdentityAndForcesPromptConsent() = runTest {
        val root = temporaryFolder.newFolder()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT + 1_000 },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val context = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, LOCAL_HOSTED_OWNER),
            profileId = null,
            profileEligible = true,
            noticeVersion = 1,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 9,
            localServerId = SOURCE_SERVER_ID,
            credentialFingerprint = "f".repeat(64),
            sourceProfileId = SOURCE_PROFILE_ID,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { RUN_TOKEN },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        ledger.beginRun(context, CAPTURED_AT - 10_000, "hosted-crash-session")
        val marker = JvmCrashMarkerRecord(
            occurredAtEpochMs = CAPTURED_AT,
            threadName = "main",
            threadId = 1,
            throwableType = "java.lang.IllegalStateException",
            stack = NETWORK_CRASH_STACK,
            binding = PendingReportBinding(
                serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
                accountUserId = LOCAL_HOSTED_OWNER,
                profileId = null,
                ownershipGeneration = context.ownershipGeneration,
                destinationKind = DiagnosticsDestinationKind.HOSTED,
            ),
            captureSessionId = "hosted-crash-session",
            runToken = RUN_TOKEN,
            foreground = true,
            playbackSessionIds = listOf(PRIVATE_PLAYBACK_SESSION_ID),
            deviceSnapshotJson = DEVICE_JSON,
            logLines = emptyList(),
            logDroppedCount = 0,
            logTornCount = 0,
            logGeneration = context.ownershipGeneration,
            truncated = false,
        )
        val collector = ExitInfoCollector(
            source = AndroidExitInfoSource { emptyList() },
            ledger = ledger,
            reports = reports,
            markers = InMemoryMarkers(marker),
            environment = ENVIRONMENT,
            deviceSnapshotBytes = { DEVICE_JSON.encodeToByteArray() },
            noticeVersion = { context.noticeVersion },
            consentMode = { org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS },
        )

        val report = collector.collect().single()
        assertTrue(report.manifest.playbackSessionIds.isEmpty())
        assertEquals(null, report.manifest.report.profileId)
        val framed = report.withCurrentConsent(DiagnosticsConsentMode.ALWAYS, context.noticeVersion)
        val bundle = FileDiagnosticsBundleBuilder().build(framed, redactionTokens = emptyList())
        assertEquals(
            org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.PROMPT,
            bundle.manifest.consent.mode,
        )
        val outerManifest = bundle.manifestBytes.decodeToString()
        val archive = GZIPInputStream(ByteArrayInputStream(bundle.bytes)).use { it.readBytes() }.decodeToString()
        assertTrue(archive.contains("illegal_state_other"), archive)
        listOf(
            SOURCE_SERVER_ID,
            SOURCE_PROFILE_ID,
            LOCAL_HOSTED_OWNER,
            PRIVATE_PLAYBACK_SESSION_ID,
            "192.168.1.44",
            "silo.home.arpa",
            "/data/user/0/org.siloserver.silo",
        ).forEach { identity ->
            assertFalse(outerManifest.contains(identity), "outer manifest: $identity")
            assertFalse(archive.contains(identity), "embedded manifest/logs: $identity")
        }
    }

    @Test
    fun hostedNativeExitBundleOmitsProcessAndBuildFingerprintIdentityFromProductionArtifacts() = runTest {
        val root = temporaryFolder.newFolder()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT + 1_000 },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val context = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, LOCAL_HOSTED_OWNER),
            profileId = null,
            profileEligible = true,
            noticeVersion = 1,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 11,
            localServerId = SOURCE_SERVER_ID,
            credentialFingerprint = "f".repeat(64),
            sourceProfileId = SOURCE_PROFILE_ID,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { RUN_TOKEN },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        ledger.beginRun(context, CAPTURED_AT - 10_000, "hosted-native-session")
        val collector = ExitInfoCollector(
            source = AndroidExitInfoSource {
                listOf(
                    object : AndroidExitInfoRecord {
                        override val reason = AndroidExitReason.NATIVE_CRASH
                        override val timestampMs = CAPTURED_AT
                        override val pid = 123
                        override val processName = "org.siloserver.silo"
                        override val status = 6
                        override val processStateSummary = RUN_TOKEN.encodeToByteArray()
                        override fun trace(maxBytes: Int) = "opaque native trace".encodeToByteArray()
                    },
                )
            },
            ledger = ledger,
            reports = reports,
            markers = object : JvmCrashMarkerSource {
                override fun records() = emptyList<JvmCrashMarkerRecord>()
                override fun delete(marker: JvmCrashMarkerRecord) = Unit
            },
            environment = ENVIRONMENT,
            deviceSnapshotBytes = { DEVICE_WITH_BUILD_FINGERPRINT.encodeToByteArray() },
            noticeVersion = { context.noticeVersion },
            consentMode = { org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode.ALWAYS },
        )

        val report = collector.collect().single()
        val rawSummary = report.directory.resolve("crash/summary.json").readText()
        val rawDevice = report.directory.resolve("device.json").readText()
        assertTrue(rawSummary.contains("process_hash"), rawSummary)
        assertTrue(rawDevice.contains("build_fingerprint_hash"), rawDevice)

        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        val hostedSummary = bundle.sanitizedEntries.getValue("crash/summary.json").decodeToString()
        val hostedDevice = bundle.sanitizedEntries.getValue("device.json").decodeToString()
        assertFalse(hostedSummary.contains("process_hash"), hostedSummary)
        assertFalse(hostedSummary.contains("org.siloserver.silo"), hostedSummary)
        assertFalse(hostedDevice.contains("build_fingerprint_hash"), hostedDevice)
        assertFalse(hostedDevice.contains("a".repeat(32)), hostedDevice)
        assertFalse("crash/tombstone.pb" in bundle.manifest.archive.entries)
    }

    @Test
    fun jvmMarkerAndExitInfoProduceOneCoordinatorReport() = runTest {
        val root = temporaryFolder.newFolder()
        val reports = FilePendingReportStore(
            root,
            nowMs = { CAPTURED_AT + 1_000 },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { RUN_TOKEN },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val adult = ADULT.copy(ownershipGeneration = 0)
        ledger.beginRun(adult, CAPTURED_AT - 10_000, "capture-1")
        val marker = JvmCrashMarkerRecord(
            occurredAtEpochMs = CAPTURED_AT,
            threadName = "main",
            threadId = 1,
            throwableType = "java.lang.IllegalStateException",
            stack = "java.lang.IllegalStateException: boom",
            binding = PendingReportBinding(
                serverInstanceId = adult.binding.serverInstanceId,
                accountUserId = adult.binding.accountUserId,
                profileId = adult.profileId,
                ownershipGeneration = adult.ownershipGeneration,
            ),
            captureSessionId = "capture-1",
            runToken = RUN_TOKEN,
            foreground = true,
            playbackSessionIds = emptyList(),
            deviceSnapshotJson = DEVICE_JSON,
            logLines = emptyList(),
            logDroppedCount = 0,
            logTornCount = 0,
            logGeneration = adult.ownershipGeneration,
            truncated = false,
        )
        val markers = InMemoryMarkers(marker)
        val collector = ExitInfoCollector(
            source = AndroidExitInfoSource { listOf(jvmExit()) },
            ledger = ledger,
            reports = reports,
            markers = markers,
            environment = ENVIRONMENT,
            deviceSnapshotBytes = { DEVICE_JSON.encodeToByteArray() },
            noticeVersion = { adult.noticeVersion },
        )
        val transitions = DefaultIdentityTransitionBarrier()
        val coordinator = coordinator(
            root = root,
            identity = MutableIdentity(adult),
            transitions = transitions,
            capture = NoOpCapture,
            reports = reports,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
            incidentCollector = DiagnosticsIncidentCollector { _, _ -> collector.collect() },
        )

        coordinator.start()
        coordinator.refresh()

        assertEquals(1, reports.list(adult.binding).size)
        assertEquals(1, coordinator.state.value.pending.size)
        assertTrue(markers.deleted)
    }

    private fun coordinator(
        root: File,
        identity: MutableIdentity,
        transitions: DefaultIdentityTransitionBarrier,
        capture: DiagnosticsCaptureController,
        reports: PendingReportStore,
        actorDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
        incidentCollector: DiagnosticsIncidentCollector = DiagnosticsIncidentCollector { _, _ -> emptyList() },
    ): DiagnosticsCoordinator {
        val settings = DiagnosticsSettingsStore(
            PreferenceDataStoreFactory.create {
                root.resolve("settings-${System.nanoTime()}.preferences_pb")
            },
            DiagnosticsBindingPurger { _, _ -> },
        )
        return DefaultDiagnosticsCoordinator(
            scope = scope,
            actorDispatcher = actorDispatcher,
            identity = identity,
            identityTransitions = transitions,
            settings = settings,
            reports = reports,
            capture = capture,
            uploader = DiagnosticsUploader { DiagnosticsUploadDecision.KeptUnavailable },
            uploadScheduler = DiagnosticsUploadScheduler { },
            incidentCollector = incidentCollector,
        )
    }

    private fun captureController(
        root: File,
        ring: LogRing,
        reports: PendingReportStore,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        playbackSessions: DiagnosticsPlaybackSessionTracker = DiagnosticsPlaybackSessionTracker(),
    ) = FileDiagnosticsCaptureController(
        logBuffer = ring,
        fileLogger = DiagnosticsFileLogger(root, dispatcher, directorySync = {}),
        reports = reports,
        deviceSnapshots = DeviceSnapshotCollector(StableProbe, nowRfc3339 = { "2026-07-22T00:00:00Z" }),
        deviceSnapshotCache = DeviceSnapshotCache(),
        environment = ENVIRONMENT,
        nowMs = { CAPTURED_AT },
        sessionIdFactory = { "manual-session" },
        playbackSessions = playbackSessions,
    )

    private fun jvmExit() = object : AndroidExitInfoRecord {
        override val reason = AndroidExitReason.JVM_CRASH
        override val timestampMs = CAPTURED_AT + 100
        override val pid = 123
        override val processName = "org.siloserver.silo"
        override val status = 0
        override val processStateSummary = RUN_TOKEN.encodeToByteArray()
        override fun trace(maxBytes: Int): ByteArray? = null
    }

    private fun diagnosticsLine(run: String, message: String): String =
        """{"ts":"2026-07-22T00:00:00Z","run":"$run","lvl":"I","cat":"lifecycle","tag":"Test","msg":"$message"}"""

    private class MutableIdentity(var current: DiagnosticsCaptureContext?) : DiagnosticsIdentityResolver {
        override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? = current
    }

    private class InMemoryMarkers(private val marker: JvmCrashMarkerRecord) : JvmCrashMarkerSource {
        var deleted = false
        override fun records(): List<JvmCrashMarkerRecord> = if (deleted) emptyList() else listOf(marker)
        override fun delete(marker: JvmCrashMarkerRecord) {
            deleted = true
        }
    }

    private object NoOpCapture : DiagnosticsCaptureController {
        override fun closeGate() = Unit
        override suspend fun start(context: DiagnosticsCaptureContext) =
            ActiveDiagnosticsCapture(1, context.identityKey, CAPTURED_AT)
        override suspend fun stop(active: ActiveDiagnosticsCapture, context: DiagnosticsCaptureContext): PendingReport? = null
        override suspend fun cancel(active: ActiveDiagnosticsCapture) = Unit
        override suspend fun captureNow(context: DiagnosticsCaptureContext): PendingReport? = null
        override suspend fun purgeCurrentEvidence() = Unit
    }

    private object StableProbe : DiagnosticsDeviceProbe {
        override fun identity() = DiagnosticsIdentitySnapshot(
            manufacturer = "Google",
            model = "Pixel",
            device = "pixel",
            osRelease = "16",
            sdkInt = 36,
            formFactor = "phone",
            buildFingerprintHash = "0".repeat(32),
        )
        override fun display(): DiagnosticsDisplaySnapshot? = null
        override fun audio(): DiagnosticsAudioSnapshot? = null
        override fun codecs(): List<DiagnosticsCodecSnapshot>? = null
        override fun network(): DiagnosticsNetworkSnapshot? = null
    }

    private companion object {
        const val CAPTURED_AT = 1_700_000_000_000L
        const val RUN_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DEVICE_JSON = "{\"captured_at\":\"2026-07-22T00:00:00Z\"}"
        val DEVICE_WITH_BUILD_FINGERPRINT =
            """{"captured_at":"2026-07-22T00:00:00Z","identity":{"manufacturer":"Google","build_fingerprint_hash":"${"a".repeat(32)}"}}"""
        const val SOURCE_SERVER_ID = "private-server-id"
        const val SOURCE_SERVER_URL = "https://private-silo.example"
        const val SOURCE_PROFILE_ID = "private-profile-id"
        const val SOURCE_ACCOUNT_ID = "private-account-id"
        const val LOCAL_HOSTED_OWNER = "local-hosted-owner-hash"
        const val PRIVATE_PLAYBACK_SESSION_ID = "private-playback-session-id"
        const val NETWORK_CRASH_STACK =
            "java.net.ConnectException: Failed to connect to /192.168.1.44:8096; " +
                "Unable to resolve host \"silo.home.arpa\"; " +
                "file=/data/user/0/org.siloserver.silo/files/diagnostics.log\n" +
                "    at java.net.Socket.connect(Socket.java:42)"
        val ENVIRONMENT = ExitReportEnvironment(
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "Android 36",
            deviceSummary = DiagnosticsDeviceSummary("Google", "Pixel", "Android 36", "phone"),
        )
        val CHILD = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "child",
            profileEligible = false,
            noticeVersion = 2,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 0,
        )
        val ADULT = CHILD.copy(profileId = "adult", profileEligible = true, ownershipGeneration = 1)
    }
}
