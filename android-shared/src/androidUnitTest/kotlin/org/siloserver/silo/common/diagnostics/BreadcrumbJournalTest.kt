package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLevel
import org.siloserver.silo.model.diagnostics.DiagnosticsLogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BreadcrumbJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retainsOnlyEnabledCurrentGenerationLinesForTheRequestedRun() = runTest {
        val journal = BreadcrumbJournal(
            noBackupFilesDir = temporaryFolder.root,
            writerDispatcher = UnconfinedTestDispatcher(testScheduler),
            directorySync = {},
        )
        val runA = renderedLine("run-a", "foreground")
        val runB = renderedLine("run-b", "background")

        journal.offer(runA)
        journal.setEnabled(IDENTITY_A)
        journal.offer(runA)
        journal.offer(runB)
        advanceUntilIdle()

        assertEquals(listOf(runA), journal.linesForRun("run-a", IDENTITY_A))
        assertEquals(listOf(runB), journal.linesForRun("run-b", IDENTITY_A))

        journal.closeGate()
        advanceUntilIdle()
        assertTrue(journal.linesForRun("run-a", IDENTITY_A).isEmpty())

        journal.setEnabled(IDENTITY_A)
        journal.offer(runB)
        advanceUntilIdle()
        journal.purge()
        assertTrue(journal.linesForRun("run-b", IDENTITY_A).isEmpty())
    }

    @Test
    fun initialClosedGatePreservesPreviousProcessEvidenceUntilCollection() = runTest {
        val previous = renderedLine("previous-run", "before native crash")
        val previousJournal = BreadcrumbJournal(
            noBackupFilesDir = temporaryFolder.root,
            writerDispatcher = UnconfinedTestDispatcher(testScheduler),
            directorySync = {},
        )
        previousJournal.setEnabled(IDENTITY_A)
        previousJournal.offer(previous)
        advanceUntilIdle()
        val journal = BreadcrumbJournal(
            noBackupFilesDir = temporaryFolder.root,
            writerDispatcher = UnconfinedTestDispatcher(testScheduler),
            directorySync = {},
        )

        journal.closeGate()
        advanceUntilIdle()
        assertEquals(listOf(previous), journal.linesForRun("previous-run", IDENTITY_A))
        assertTrue(journal.linesForRun("previous-run", IDENTITY_B).isEmpty())

        journal.setEnabled(IDENTITY_B)
        advanceUntilIdle()
        assertTrue(journal.linesForRun("previous-run", IDENTITY_A).isEmpty())
    }

    @Test
    fun purgeFailsClosedWhenExistingBreadcrumbDirectoryCannotBeEnumerated() = runTest {
        val root = temporaryFolder.newFolder("unreadable")
        val directory = root.resolve("client-diagnostics/breadcrumbs")
        assertTrue(directory.mkdirs())
        directory.resolve("segment-private-0.jsonl").writeText("private")
        val journal = BreadcrumbJournal(
            noBackupFilesDir = root,
            writerDispatcher = UnconfinedTestDispatcher(testScheduler),
            listFiles = { null },
            directorySync = {},
        )

        assertFailsWith<IllegalStateException> { journal.purge() }
        assertTrue(directory.resolve("segment-private-0.jsonl").isFile)
    }

    private fun renderedLine(run: String, message: String): String = JSON.encodeToString(
        DiagnosticsLogLine(
            timestamp = "2026-07-22T00:00:00Z",
            run = run,
            level = DiagnosticsLogLevel.INFO,
            category = DiagnosticsLogCategory.LIFECYCLE,
            tag = "AppLifecycle",
            message = message,
        ),
    )

    private companion object {
        val JSON = Json { explicitNulls = false }
        val IDENTITY_A = DiagnosticsIdentityKey(DiagnosticsBinding("server", "account"), "adult-a", 1)
        val IDENTITY_B = DiagnosticsIdentityKey(DiagnosticsBinding("server", "account"), "adult-b", 2)
    }
}
