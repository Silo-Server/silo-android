package org.siloserver.silo.common.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val decoded = FileJvmCrashMarkerSource(temporaryFolder.root).records().single()
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
}
