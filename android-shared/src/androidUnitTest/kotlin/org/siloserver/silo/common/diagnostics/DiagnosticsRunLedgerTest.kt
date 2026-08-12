package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsRunLedgerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun publishesOnlyOpaqueTokenAndPersistsIdentityMappingLocally() = runTest {
        val published = mutableListOf<ByteArray>()
        val root = temporaryFolder.newFolder("ledger")
        val ledger = DiagnosticsRunLedger(
            root,
            ProcessStateSummaryPublisher { published += it },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val context = context("server-secret", "user-secret", "profile-secret", generation = 4)

        val token = ledger.beginRun(context, processStartedAtEpochMs = 100, captureSessionId = "capture-secret")

        assertEquals(listOf(token), published.map { it.decodeToString() })
        val summary = published.single().decodeToString()
        listOf("server-secret", "user-secret", "profile-secret", "capture-secret").forEach {
            assertFalse(summary.contains(it), summary)
        }
        assertTrue(token.matches(Regex("[0-9a-f]{32}")), token)

        val restored = DiagnosticsRunLedger(
            root,
            directorySync = {},
            atomicRename = ::testAtomicRename,
        ).find(token)
        assertEquals(context.binding, restored?.binding)
        assertEquals("profile-secret", restored?.profileId)
        assertEquals("capture-secret", restored?.captureSessionId)
        assertEquals(4, restored?.ownershipGeneration)
    }

    @Test
    fun ledgerIsBoundedNewestFirstAndUnknownTokensDoNotResolve() = runTest {
        var tokenCounter = 0
        val ledger = DiagnosticsRunLedger(
            noBackupFilesDir = temporaryFolder.newFolder("bounded"),
            maxRecords = 2,
            tokenFactory = { "a".repeat(31) + (tokenCounter++).toString(16) },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )

        val first = ledger.beginRun(context("s", "u", null, 1), 1, "c1")
        val second = ledger.beginRun(context("s", "u", null, 1), 2, "c2")
        val third = ledger.beginRun(context("s", "u", null, 1), 3, "c3")

        assertNull(ledger.find(first))
        assertEquals("c2", ledger.find(second)?.captureSessionId)
        assertEquals("c3", ledger.find(third)?.captureSessionId)
        assertNull(ledger.find("not-a-token"))
    }

    @Test
    fun purgeBindingRemovesOnlyOwnedRuns() = runTest {
        val ledger = DiagnosticsRunLedger(
            temporaryFolder.newFolder("purge"),
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val a = ledger.beginRun(context("server-a", "user", null, 1), 1, "a")
        val b = ledger.beginRun(context("server-b", "user", null, 1), 2, "b")

        ledger.purge(DiagnosticsBinding("server-a", "user"))

        assertNull(ledger.find(a))
        assertEquals("b", ledger.find(b)?.captureSessionId)
    }

    @Test
    fun clearRemovesCommittedAndTemporaryLedgersAndPropagatesDirectorySyncFailure() = runTest {
        val root = temporaryFolder.newFolder("strict-clear")
        var failSync = false
        val ledger = DiagnosticsRunLedger(
            root,
            directorySync = { if (failSync) error("injected ledger fsync failure") },
            atomicRename = ::testAtomicRename,
        )
        ledger.beginRun(context("server", "user", null, 1), 1, "capture")
        val temporary = root.resolve("client-diagnostics/run-ledger.json.tmp")
        temporary.writeText("private stale bytes")
        failSync = true

        assertFailsWith<IllegalStateException> { ledger.clear() }
        assertFalse(root.resolve("client-diagnostics/run-ledger.json").exists())

        failSync = false
        ledger.clear()
        assertFalse(temporary.exists())
    }

    @Test
    fun failedAtomicReplacementPreservesPriorLedgerAndSyncedTemporary() = runTest {
        val root = temporaryFolder.newFolder("rename-failure")
        var failReplacement = false
        var tokenCounter = 0
        val ledger = DiagnosticsRunLedger(
            root,
            tokenFactory = { "b".repeat(31) + (tokenCounter++).toString(16) },
            directorySync = {},
            atomicRename = { source, target ->
                if (failReplacement && target.exists()) error("simulated atomic rename failure")
                testAtomicRename(source, target)
            },
        )
        val first = ledger.beginRun(context("server", "user", null, 1), 1, "first")
        failReplacement = true

        assertFailsWith<IllegalStateException> {
            ledger.beginRun(context("server", "user", null, 1), 2, "second")
        }

        val restored = DiagnosticsRunLedger(
            root,
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        assertEquals("first", restored.find(first)?.captureSessionId)
        assertTrue(root.resolve("client-diagnostics/run-ledger.json.tmp").isFile)
    }

    private fun context(
        server: String,
        user: String,
        profile: String?,
        generation: Long,
    ) = DiagnosticsCaptureContext(
        binding = DiagnosticsBinding(server, user),
        profileId = profile,
        profileEligible = true,
        noticeVersion = 1,
        status = DiagnosticsAvailabilityStatus.AVAILABLE,
        ownershipGeneration = generation,
    )
}
