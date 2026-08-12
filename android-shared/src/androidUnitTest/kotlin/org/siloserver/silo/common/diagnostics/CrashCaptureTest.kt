package org.siloserver.silo.common.diagnostics

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashCaptureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun delegatesExactlyOnceWhenMarkerWriteFails() {
        var delegated = 0
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated += 1 }
        val handler = CrashExceptionHandler(
            markerSink = CrashMarkerSink { _, _, _ -> error("disk full") },
            runtimeSnapshot = { runtime() },
            previous = previous,
        )

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(1, delegated)
    }

    @Test
    fun renderedMarkerIsValidJsonAndNeverExceedsHardLimit() {
        val throwable = IllegalStateException("boom-secret-token-" + "x".repeat(100_000)).apply {
            stackTrace = Array(10_000) { index ->
                StackTraceElement("Class$index", "method$index", "File$index.kt", index)
            }
        }
        val hugeLogs = List(10_000) { index -> "{\"index\":$index,\"message\":\"${"z".repeat(2_000)}\"}" }
        val renderer = CrashMarkerRenderer()

        val bytes = renderer.render(
            thread = Thread.currentThread(),
            throwable = throwable,
            runtime = runtime(logs = hugeLogs, deviceSnapshotJson = "{\"device\":\"${"d".repeat(100_000)}\"}"),
            occurredAtEpochMs = 1_700_000_000_000,
        )

        assertTrue(bytes.size <= CrashMarkerRenderer.MAX_MARKER_BYTES)
        Json.parseToJsonElement(bytes.decodeToString())
        // Exact credential values are scrubbed cheaply; structural redaction still runs next launch.
        assertFalse(bytes.decodeToString().contains("secret-token"))
        assertTrue(bytes.decodeToString().contains("[REDACTED]"))
    }

    @Test
    fun rendererSnapshotsTheLiveRingAtCrashTime() {
        val ring = LogRing()
        ring.offer("{\"msg\":\"before publish\"}")
        val runtime = runtime(logs = listOf("{\"msg\":\"stale cached line\"}"))
            .copy(logBuffer = ring, logGeneration = ring.currentGeneration)
        ring.offer("{\"msg\":\"secret-token immediately before crash\"}")

        val bytes = CrashMarkerRenderer().render(
            thread = Thread.currentThread(),
            throwable = IllegalStateException("boom"),
            runtime = runtime,
            occurredAtEpochMs = 1_700_000_000_000,
        )
        val marker = Json.decodeFromString<JvmCrashMarkerRecord>(bytes.decodeToString())

        assertEquals(
            listOf("{\"msg\":\"before publish\"}", "{\"msg\":\"[REDACTED] immediately before crash\"}"),
            marker.logLines,
        )
        assertFalse(marker.logLines.any { it.contains("stale cached line") })
        assertFalse(marker.logLines.any { it.contains("secret-token") })
    }

    @Test
    fun renderedHostedMarkerRoundTripsItsDestinationKindWhileSelfHostedRemainsCompatible() {
        val hosted = Json.decodeFromString<JvmCrashMarkerRecord>(
            CrashMarkerRenderer().render(
                thread = Thread.currentThread(),
                throwable = IllegalStateException("hosted crash"),
                runtime = runtime().copy(
                    binding = PendingReportBinding(
                        serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
                        accountUserId = "anonymous-hosted-device",
                        profileId = null,
                        ownershipGeneration = 7,
                        destinationKind = DiagnosticsDestinationKind.HOSTED,
                    ),
                ),
                occurredAtEpochMs = 1_700_000_000_000,
            ).decodeToString(),
        )
        val selfHosted = Json.decodeFromString<JvmCrashMarkerRecord>(
            CrashMarkerRenderer().render(
                thread = Thread.currentThread(),
                throwable = IllegalStateException("self-hosted crash"),
                runtime = runtime(),
                occurredAtEpochMs = 1_700_000_000_000,
            ).decodeToString(),
        )

        assertEquals(DiagnosticsDestinationKind.HOSTED, hosted.binding?.destinationKind)
        assertEquals(DiagnosticsDestinationKind.SELF_HOSTED, selfHosted.binding?.destinationKind)
    }

    @Test
    fun liveRingFromANewerIdentityGenerationIsNotAttachedToTheOldRuntime() {
        val ring = LogRing()
        val runtime = runtime().copy(logBuffer = ring, logGeneration = ring.currentGeneration)
        ring.rotateGeneration()
        ring.offer("{\"msg\":\"new identity\"}")

        val bytes = CrashMarkerRenderer().render(
            thread = Thread.currentThread(),
            throwable = IllegalStateException("boom"),
            runtime = runtime,
            occurredAtEpochMs = 1_700_000_000_000,
        )
        val marker = Json.decodeFromString<JvmCrashMarkerRecord>(bytes.decodeToString())

        assertTrue(marker.logLines.isEmpty())
    }

    @Test
    fun exactRedactionProcessesLongerOverlappingValuesFirst() {
        val bytes = CrashMarkerRenderer().render(
            thread = Thread.currentThread(),
            throwable = IllegalStateException("alice-secret"),
            runtime = runtime().copy(redactionTokens = List(16) { "alice" } + "alice-secret"),
            occurredAtEpochMs = 1_700_000_000_000,
        )
        val marker = Json.decodeFromString<JvmCrashMarkerRecord>(bytes.decodeToString())

        assertTrue(marker.stack.contains("[REDACTED]"))
        assertFalse(marker.stack.contains("secret"))
    }

    @Test
    fun tooManyExactCredentialsFailClosedWithoutCrashMessagesOrLogs() {
        val credentials = List(17) { index -> "credential-$index" }
        val bytes = CrashMarkerRenderer().render(
            thread = Thread.currentThread(),
            throwable = IllegalStateException("credential-16"),
            runtime = runtime(logs = listOf("{\"msg\":\"credential-16\"}"))
                .copy(redactionTokens = credentials),
            occurredAtEpochMs = 1_700_000_000_000,
        )
        val marker = Json.decodeFromString<JvmCrashMarkerRecord>(bytes.decodeToString())

        assertEquals("", marker.threadName)
        assertEquals("java.lang.IllegalStateException", marker.stack)
        assertTrue(marker.logLines.isEmpty())
        assertFalse(bytes.decodeToString().contains("credential-16"))
    }

    @Test
    fun fileWriterPublishesSyncedMarkerWithoutLeavingTemporaryFiles() {
        val writer = FileCrashMarkerWriter(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            nanoTime = { 1 },
        )

        writer.write(Thread.currentThread(), IllegalArgumentException("boom"), runtime())

        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        val files = directory.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().name.endsWith(".json"))
        assertFalse(files.single().name.endsWith(".tmp"))
        assertTrue(files.single().length() in 1..CrashMarkerRenderer.MAX_MARKER_BYTES.toLong())
        val decoded = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = File::listFiles,
        ).records().single()
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded.runToken)
        assertEquals("capture-1", decoded.captureSessionId)
    }

    @Test
    fun elapsedBudgetPreventsLateMarkerPublication() {
        var tick = 0L
        val writer = FileCrashMarkerWriter(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            nanoTime = { tick.also { tick += 100_000_000 } },
            elapsedBudgetNanos = 50_000_000,
        )

        writer.write(Thread.currentThread(), IllegalStateException("slow"), runtime())

        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun destructiveTransitionAbortsWhenAMatchingCrashMarkerCannotBeDeleted() = runTest {
        FileCrashMarkerWriter(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            nanoTime = { 1 },
        ).write(Thread.currentThread(), IllegalStateException("private crash"), runtime())
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = { false },
            syncDirectory = {},
            listFiles = File::listFiles,
        )
        val transitions = DefaultIdentityTransitionBarrier()
        transitions.installGate { source.purge(DiagnosticsBinding("server-1", "user-1")) }
        var mutationRan = false

        assertFailsWith<IllegalStateException> {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
            }
        }

        assertFalse(mutationRan)
        assertEquals(0, transitions.generation.value)
        assertEquals(1, temporaryFolder.root.resolve("client-diagnostics/crash-markers").listFiles().orEmpty().size)
    }

    @Test
    fun purgeStrictlyRemovesMatchingMalformedAndTemporaryMarkerEvidence() {
        FileCrashMarkerWriter(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            nanoTime = { 1 },
        ).write(Thread.currentThread(), IllegalStateException("private crash"), runtime())
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        directory.resolve(".jvm-2-2.tmp").writeText("raw temporary private crash")
        directory.resolve("jvm-3-3.json").writeText("raw malformed private crash")
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = File::listFiles,
        )

        source.purge(DiagnosticsBinding("server-1", "user-1"))

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun destructiveTransitionAbortsWhenCrashMarkerDirectoryCannotBeEnumerated() = runTest {
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.mkdirs())
        directory.resolve("jvm-3-3.json").writeText("raw private crash")
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = { null },
        )
        val transitions = DefaultIdentityTransitionBarrier()
        transitions.installGate { source.purge(DiagnosticsBinding("server-1", "user-1")) }
        var mutationRan = false

        assertFailsWith<IllegalStateException> {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
            }
        }

        assertFalse(mutationRan)
        assertTrue(directory.resolve("jvm-3-3.json").exists())
    }

    @Test
    fun reconciliationStrictlyPrunesExpiredFutureMalformedTemporaryAndOverCapEvidence() {
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.mkdirs())
        markerFile(directory, NOW - EIGHT_DAYS_MS)
        markerFile(directory, NOW + ONE_HOUR_MS)
        markerFile(directory, NOW - 10, fileTimestamp = NOW - 9)
        directory.resolve("jvm-${NOW - 8}-8.json").writeText("raw malformed private crash")
        directory.resolve("jvm-${NOW - 7}-7.json")
            .writeBytes(ByteArray(CrashMarkerRenderer.MAX_MARKER_BYTES + 1))
        directory.resolve(".jvm-${NOW - 6}-6.tmp").writeText("raw temporary private crash")
        directory.resolve("unexpected-private-evidence").writeText("raw unexpected private crash")
        assertTrue(directory.resolve("unexpected-directory").mkdir())
        val retainedTimes = listOf(NOW - 4, NOW - 3, NOW - 2, NOW - 1)
        retainedTimes.forEach { markerFile(directory, it) }
        var syncs = 0
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { NOW },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = { syncs += 1 },
            listFiles = File::listFiles,
        )

        val records = source.records()

        assertEquals(retainedTimes.takeLast(3), records.map(JvmCrashMarkerRecord::occurredAtEpochMs))
        assertEquals(
            retainedTimes.takeLast(3).map { "jvm-$it-1.json" },
            directory.listFiles().orEmpty().map(File::getName).sorted(),
        )
        assertEquals(1, syncs)
    }

    @Test
    fun expiredMarkerDeletionAndDirectorySyncFailuresFailClosed() {
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.mkdirs())
        val expired = markerFile(directory, NOW - EIGHT_DAYS_MS)
        val deletionFailure = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { NOW },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = { false },
            syncDirectory = {},
            listFiles = File::listFiles,
        )

        assertFailsWith<IllegalStateException> { deletionFailure.reconcile() }
        assertTrue(expired.exists())

        val syncFailure = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { NOW },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = { error("fsync failed") },
            listFiles = File::listFiles,
        )
        assertFailsWith<IllegalStateException> { syncFailure.reconcile() }
        assertFalse(expired.exists())
    }

    @Test
    fun markerReconciliationFailsClosedWhenTheOwnedDirectoryCannotBeEnumerated() {
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.mkdirs())
        markerFile(directory, NOW)
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { NOW },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = { null },
        )

        assertFailsWith<IllegalStateException> { source.reconcile() }
        assertTrue(directory.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun reconciliationRemovesLiveAndDanglingSymbolicLinksWithoutFollowingThem() {
        val directory = temporaryFolder.root.resolve("client-diagnostics/crash-markers")
        assertTrue(directory.mkdirs())
        val outside = temporaryFolder.root.resolve("outside-private-evidence").apply {
            writeText("private evidence outside marker root")
        }
        val liveLink = directory.resolve("jvm-${NOW}-1.json")
        val danglingLink = directory.resolve("dangling-private-evidence")
        java.nio.file.Files.createSymbolicLink(liveLink.toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(
            danglingLink.toPath(),
            temporaryFolder.root.resolve("missing-private-evidence").toPath(),
        )
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { NOW },
            fileGate = JvmCrashMarkerFileGate(),
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = File::listFiles,
        )

        source.reconcile()

        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(outside.isFile)
        assertEquals("private evidence outside marker root", outside.readText())
    }

    @Test
    fun closeAndPurgeWaitForAnInFlightMarkerPublication() {
        val gate = JvmCrashMarkerFileGate()
        val writer = FileCrashMarkerWriter(
            noBackupFilesDir = temporaryFolder.root,
            nowMs = { 1_700_000_000_000 },
            nanoTime = { 1 },
        )
        val runtime = AtomicReference(runtime().copy(identityKey = DiagnosticsIdentityKey(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "profile-1",
            ownershipGeneration = 7,
        )))
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val transitionStarted = CountDownLatch(1)
        val transitionFinished = CountDownLatch(1)
        val handler = CrashExceptionHandler(
            markerSink = CrashMarkerSink { thread, throwable, snapshot ->
                writeEntered.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                writer.write(thread, throwable, snapshot)
            },
            runtimeSnapshot = runtime::get,
            previous = null,
            writeGate = gate,
        )
        val source = FileJvmCrashMarkerSource(
            noBackupFilesDir = temporaryFolder.root,
            fileGate = gate,
            deleteFile = File::delete,
            syncDirectory = {},
            listFiles = File::listFiles,
        )
        val crashThread = thread(name = "diagnostics-crash-test") {
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("private crash"))
        }
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
        val transitionThread = thread(name = "diagnostics-transition-test") {
            transitionStarted.countDown()
            gate.withLock { runtime.set(CrashRuntimeSnapshot.empty()) }
            source.purge(DiagnosticsBinding("server-1", "user-1"))
            transitionFinished.countDown()
        }
        assertTrue(transitionStarted.await(5, TimeUnit.SECONDS))
        assertFalse(transitionFinished.await(100, TimeUnit.MILLISECONDS))

        releaseWrite.countDown()
        crashThread.join(5_000)
        transitionThread.join(5_000)

        assertFalse(crashThread.isAlive)
        assertFalse(transitionThread.isAlive)
        assertEquals(CrashRuntimeSnapshot.empty(), runtime.get())
        assertTrue(source.records().isEmpty())
    }

    private fun runtime(
        logs: List<String> = listOf("{\"msg\":\"safe\"}"),
        deviceSnapshotJson: String? = "{\"captured_at\":\"2026-07-22T00:00:00Z\"}",
    ) = CrashRuntimeSnapshot(
        binding = PendingReportBinding("server-1", "user-1", "profile-1", 7),
        captureSessionId = "capture-1",
        runToken = "a".repeat(32),
        foreground = true,
        playbackSessionIds = listOf("playback-1"),
        deviceSnapshotJson = deviceSnapshotJson,
        logLines = logs,
        logDroppedCount = 2,
        logTornCount = 1,
        logGeneration = 7,
        redactionTokens = listOf("secret-token"),
    )

    private fun markerFile(
        directory: File,
        occurredAtEpochMs: Long,
        fileTimestamp: Long = occurredAtEpochMs,
    ): File = directory.resolve("jvm-$fileTimestamp-1.json").also { file ->
        file.writeBytes(
            CrashMarkerRenderer().render(
                thread = Thread.currentThread(),
                throwable = IllegalStateException("private crash"),
                runtime = runtime(),
                occurredAtEpochMs = occurredAtEpochMs,
            ),
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val ONE_HOUR_MS = 60L * 60 * 1_000
        const val EIGHT_DAYS_MS = 8L * 24 * 60 * 60 * 1_000
    }
}
