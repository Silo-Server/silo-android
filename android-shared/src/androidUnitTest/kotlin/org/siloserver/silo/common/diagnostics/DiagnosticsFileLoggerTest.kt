package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsFileLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writesJsonLinesUnderNoBackupAndFreezeRetainsAStableSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noBackup = temporaryFolder.newFolder("no-backup")
        val logger = DiagnosticsFileLogger(noBackup, writerDispatcher = dispatcher, directorySync = {})

        logger.start(generation = 7)
        logger.offer("one")
        logger.offer("two")
        advanceUntilIdle()
        val frozen = logger.freeze(expectedGeneration = 7)
        logger.offer("ignored-after-freeze")

        assertEquals(7, frozen.generation)
        assertEquals(0, frozen.droppedCount)
        assertEquals(listOf("one", "two"), frozen.files.flatMap { it.readLines() })
        assertTrue(frozen.files.all { it.toPath().startsWith(noBackup.toPath()) })
        assertTrue(frozen.files.all { it.name.endsWith(".jsonl") })
        assertFalse(frozen.files.any { it.readText().contains("ignored-after-freeze") })
    }

    @Test
    fun rotatesAppendOnlySegmentsAndKeepsOnlyTheNewestBoundedSet() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = temporaryFolder.newFolder("segments"),
            writerDispatcher = dispatcher,
            channelCapacity = 16,
            maxSegments = 2,
            maxSegmentBytes = 14,
            directorySync = {},
        )

        logger.start(generation = 9)
        repeat(5) { logger.offer("line-$it") }
        advanceUntilIdle()
        val frozen = logger.freeze(expectedGeneration = 9)

        assertEquals(2, frozen.files.size)
        assertEquals(listOf("line-2", "line-3", "line-4"), frozen.files.flatMap { it.readLines() })
        assertTrue(frozen.files.all { it.length() <= 14 })
        assertEquals(frozen.files.sumOf { it.length() }, frozen.bytes)
    }

    @Test
    fun channelOverflowDropsOldestWithoutBlockingCaller() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = temporaryFolder.newFolder("overflow"),
            writerDispatcher = dispatcher,
            channelCapacity = 2,
            maxSegments = 5,
            maxSegmentBytes = 1_024,
            directorySync = {},
        )

        logger.start(generation = 11)
        repeat(5) { logger.offer("line-$it") }
        advanceUntilIdle()
        val frozen = logger.freeze(expectedGeneration = 11)

        assertEquals(listOf("line-3", "line-4"), frozen.files.flatMap { it.readLines() })
        assertEquals(3, frozen.droppedCount)
    }

    @Test
    fun oversizedLinesAreDroppedAndCancelPurgesOnlyActiveGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val noBackup = temporaryFolder.newFolder("cancel")
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = noBackup,
            writerDispatcher = dispatcher,
            maxSegmentBytes = 8,
            directorySync = {},
        )

        logger.start(generation = 13)
        logger.offer("this-line-is-too-large")
        logger.offer("small")
        advanceUntilIdle()
        logger.cancel(expectedGeneration = 13)

        assertFalse(noBackup.resolve("client-diagnostics/logs/generation-13").exists())
        assertFalse(logger.isActive)
    }

    @Test
    fun purgeFailureAtDirectorySyncPropagatesAfterVerifiedRawDeletion() = runTest {
        val noBackup = temporaryFolder.newFolder("purge-fsync")
        val root = noBackup.resolve("client-diagnostics/logs")
        assertTrue(root.mkdirs())
        root.resolve("raw.jsonl").writeText("private")
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = noBackup,
            writerDispatcher = StandardTestDispatcher(testScheduler),
            directorySync = { error("injected fsync failure") },
        )

        assertFailsWith<IllegalStateException> { logger.purgeStoredEvidence() }
        assertFalse(root.exists())
    }

    @Test
    fun startupReconcilesCrashInterruptedFrozenGeneration() = runTest {
        val noBackup = temporaryFolder.newFolder("restart-frozen")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = DiagnosticsFileLogger(noBackup, writerDispatcher = dispatcher, directorySync = {})
        first.start(generation = 17)
        first.offer("raw captured line")
        advanceUntilIdle()
        val frozen = first.freeze(expectedGeneration = 17)
        val generationDirectory = noBackup.resolve("client-diagnostics/logs/generation-17")
        assertTrue(generationDirectory.isDirectory)
        assertTrue(frozen.files.isNotEmpty())

        // Simulates process death after the pending report publish and before raw cleanup.
        DiagnosticsFileLogger(noBackup, writerDispatcher = dispatcher, directorySync = {})

        assertFalse(generationDirectory.exists())
    }

    @Test
    fun partialFrozenCleanupFailsClosedAndRestartRetriesIt() = runTest {
        val noBackup = temporaryFolder.newFolder("partial-frozen")
        val dispatcher = StandardTestDispatcher(testScheduler)
        var failGenerationDelete = false
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = noBackup,
            writerDispatcher = dispatcher,
            deleteRecursively = { target ->
                if (failGenerationDelete && target.name == "generation-19") {
                    target.resolve("segment-00000.jsonl").delete()
                    false
                } else {
                    target.deleteRecursively()
                }
            },
            directorySync = {},
        )
        logger.start(generation = 19)
        logger.offer("raw captured line")
        advanceUntilIdle()
        val frozen = logger.freeze(expectedGeneration = 19)
        failGenerationDelete = true

        assertFailsWith<IllegalStateException> { logger.deleteFrozen(frozen) }
        val generationDirectory = noBackup.resolve("client-diagnostics/logs/generation-19")
        assertTrue(generationDirectory.isDirectory)

        DiagnosticsFileLogger(noBackup, writerDispatcher = dispatcher, directorySync = {})

        assertFalse(generationDirectory.exists())
    }

    @Test
    fun failedStartupReconciliationBlocksNewCaptureUntilCleanupRecovers() = runTest {
        val noBackup = temporaryFolder.newFolder("startup-cleanup-failure")
        val stale = noBackup.resolve("client-diagnostics/logs/generation-21")
        assertTrue(stale.mkdirs())
        stale.resolve("segment-00000.jsonl").writeText("private crash-leftover bytes")
        var allowDelete = false
        val logger = DiagnosticsFileLogger(
            noBackupFilesDir = noBackup,
            writerDispatcher = StandardTestDispatcher(testScheduler),
            deleteRecursively = { target -> allowDelete && target.deleteRecursively() },
            directorySync = {},
        )

        assertFailsWith<IllegalStateException> { logger.start(generation = 22) }
        assertFalse(logger.isActive)
        assertTrue(stale.isDirectory)

        allowDelete = true
        logger.start(generation = 22)
        assertTrue(logger.isActive)
        assertFalse(stale.exists())
        logger.cancel(expectedGeneration = 22)
    }
}
