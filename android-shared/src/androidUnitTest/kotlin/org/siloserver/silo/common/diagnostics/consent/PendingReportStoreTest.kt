package org.siloserver.silo.common.diagnostics.consent

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.common.diagnostics.TestDrafts
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingReportStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var baseDir: File
    private var now: Long = 1_000_000L

    private val bindingA = DiagnosticsBinding("srv_home_01", "user_a")
    private val bindingB = DiagnosticsBinding("srv_home_01", "user_b")

    @Before
    fun setUp() {
        baseDir = tmp.newFolder("diagnostics")
        now = 1_000_000L
    }

    private fun newStore(): PendingReportStore = PendingReportStore(baseDir) { now }

    private fun capture(
        binding: DiagnosticsBinding = bindingA,
        capturedAtEpochMs: Long = now,
        fingerprint: String = "fp-$capturedAtEpochMs",
        artifacts: List<PendingReportStore.Artifact> = listOf(
            PendingReportStore.Artifact("device.json", """{"probe":"device"}""".encodeToByteArray()),
            PendingReportStore.Artifact("logs.jsonl", "{\"msg\":\"a\"}\n".encodeToByteArray()),
            PendingReportStore.Artifact("crash/stack.txt", "stack line 1\nstack line 2\n".encodeToByteArray()),
        ),
    ): PendingReportStore.PendingReportCapture = PendingReportStore.PendingReportCapture(
        binding = binding,
        profileId = "prof_living_room",
        capturedAtEpochMs = capturedAtEpochMs,
        type = DiagnosticsReportType.CRASH,
        fingerprint = fingerprint,
        manifestDraft = TestDrafts.crashDraft(serverInstanceId = binding.serverInstanceId),
        artifacts = artifacts,
    )

    // ---- save / list -----------------------------------------------------

    @Test
    fun `save then listReports round-trips binding manifest and artifacts`() {
        val store = newStore()
        val saved = assertNotNull(store.save(capture()))

        val reports = store.listReports()
        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals(saved.id, report.id)
        assertEquals(bindingA, report.binding.binding)
        assertEquals("prof_living_room", report.binding.profileId)
        assertEquals(1_000_000L, report.binding.capturedAtEpochMs)
        assertEquals(DiagnosticsReportType.CRASH, report.binding.type)
        assertEquals("fp-1000000", report.binding.fingerprint)
        assertEquals(TestDrafts.crashDraft(), report.manifestDraft)
        assertEquals(PendingReportStore.PendingReportState(), report.state)

        assertContentEquals(
            """{"probe":"device"}""".encodeToByteArray(),
            store.readArtifact(report, "device.json"),
        )
        assertContentEquals(
            "stack line 1\nstack line 2\n".encodeToByteArray(),
            store.readArtifact(report, "crash/stack.txt"),
        )
        assertNull(store.readArtifact(report, "crash/tombstone.pb"), "absent artifact reads as null")
    }

    @Test
    fun `per-binding cap of three evicts the oldest`() {
        val store = newStore()
        for (capturedAt in listOf(1000L, 2000L, 3000L, 4000L)) {
            now = 100_000L
            assertNotNull(store.save(capture(capturedAtEpochMs = capturedAt)))
        }
        assertEquals(
            listOf(2000L, 3000L, 4000L),
            store.listReports(bindingA).map { it.binding.capturedAtEpochMs },
        )
    }

    @Test
    fun `save that is immediately evicted returns null and leaves existing reports intact`() {
        val store = newStore()
        now = 100_000L
        for (capturedAt in listOf(1000L, 2000L, 3000L)) {
            assertNotNull(store.save(capture(capturedAtEpochMs = capturedAt)))
        }

        // Older than all three retained reports: evicted by its own save.
        assertNull(store.save(capture(capturedAtEpochMs = 500L)))
        assertEquals(
            listOf(1000L, 2000L, 3000L),
            store.listReports(bindingA).map { it.binding.capturedAtEpochMs },
        )
    }

    @Test
    fun `expired reports are removed on next listReports`() {
        val store = newStore()
        assertNotNull(store.save(capture(capturedAtEpochMs = now)))
        assertEquals(1, store.listReports().size)

        now += PendingReportStore.EXPIRY_MS // exactly at the boundary: kept
        assertEquals(1, store.listReports().size)

        now += 1 // past the boundary: expired
        assertTrue(store.listReports().isEmpty())
    }

    @Test
    fun `purge of one binding leaves other binding reports and the global fingerprint set`() {
        val store = newStore()
        assertNotNull(store.save(capture(binding = bindingA, fingerprint = "fp-a")))
        assertNotNull(store.save(capture(binding = bindingB, fingerprint = "fp-b")))
        store.markFingerprintSeen("fp-a")
        store.recordAutoUploadAttempt(bindingA, "fp-a")

        store.purge(bindingA)

        assertTrue(store.listReports(bindingA).isEmpty())
        assertEquals(1, store.listReports(bindingB).size)
        // Seen fingerprints are global: a crash physically happened.
        assertTrue(store.hasSeenFingerprint("fp-a"))
        // But the per-binding throttle went with the binding.
        assertTrue(store.canAutoUpload(bindingA, "fp-a"))
    }

    // ---- state persistence ----------------------------------------------

    @Test
    fun `state marks persist across a new store instance`() {
        val store = newStore()
        var report = assertNotNull(store.save(capture()))
        report = store.markPromptDeclined(report)
        report = store.markNeedsServerUpdate(report)
        report = store.markTooLarge(report)
        assertTrue(report.state.promptDeclined)

        val reloaded = newStore().listReports().single()
        assertTrue(reloaded.state.promptDeclined)
        assertTrue(reloaded.state.needsServerUpdate)
        assertTrue(reloaded.state.tooLarge)
        assertTrue(reloaded.state.isPermanentFailure)
    }

    @Test
    fun `updatingConsent rewrites the manifest consent and persists`() {
        val store = newStore()
        val report = assertNotNull(store.save(capture()))
        assertEquals(DiagnosticsConsentMode.PROMPT, report.manifestDraft.consent.mode)

        val updated = store.updatingConsent(report, DiagnosticsConsentMode.ALWAYS, noticeVersion = 3)
        assertEquals(
            DiagnosticsManifest.Consent(DiagnosticsConsentMode.ALWAYS, 3),
            updated.manifestDraft.consent,
        )

        val reloaded = newStore().listReports().single()
        assertEquals(
            DiagnosticsManifest.Consent(DiagnosticsConsentMode.ALWAYS, 3),
            reloaded.manifestDraft.consent,
        )
        // Everything else in the draft is untouched.
        assertEquals(report.manifestDraft.report, reloaded.manifestDraft.report)
    }

    // ---- fingerprints / throttle / retry-after ---------------------------

    @Test
    fun `fingerprint seen round-trips and prunes after 30 days`() {
        val store = newStore()
        assertFalse(store.hasSeenFingerprint("fp-x"))
        store.markFingerprintSeen("fp-x")
        assertTrue(store.hasSeenFingerprint("fp-x"))
        assertTrue(newStore().hasSeenFingerprint("fp-x"), "seen set persists on disk")

        now += PendingReportStore.FINGERPRINT_RETENTION_MS + 1
        assertFalse(store.hasSeenFingerprint("fp-x"), "pruned after retention window")
    }

    @Test
    fun `auto-upload throttle blocks within 24h and reopens after`() {
        val store = newStore()
        assertTrue(store.canAutoUpload(bindingA, "fp-x"))

        store.recordAutoUploadAttempt(bindingA, "fp-x")
        assertFalse(store.canAutoUpload(bindingA, "fp-x"))
        // Per-(binding, fingerprint): other keys are unaffected.
        assertTrue(store.canAutoUpload(bindingA, "fp-other"))
        assertTrue(store.canAutoUpload(bindingB, "fp-x"))

        now += PendingReportStore.AUTO_UPLOAD_THROTTLE_MS - 1
        assertFalse(store.canAutoUpload(bindingA, "fp-x"))
        now += 1
        assertTrue(store.canAutoUpload(bindingA, "fp-x"))
    }

    @Test
    fun `retry-after deadline persists expires by clock and clears`() {
        val store = newStore()
        assertNull(store.retryAfterDeadlineEpochMs(bindingA))

        val deadline = now + 60_000
        store.setRetryAfterDeadline(bindingA, deadline)
        assertEquals(deadline, store.retryAfterDeadlineEpochMs(bindingA))
        assertEquals(deadline, newStore().retryAfterDeadlineEpochMs(bindingA), "persists across instances")

        now = deadline // deadline must be strictly in the future
        assertNull(store.retryAfterDeadlineEpochMs(bindingA))

        now = deadline - 30_000
        assertEquals(deadline, store.retryAfterDeadlineEpochMs(bindingA))
        store.clearRetryAfterDeadline(bindingA)
        assertNull(store.retryAfterDeadlineEpochMs(bindingA))
    }

    // ---- artifact allowlist ----------------------------------------------

    @Test
    fun `artifact name outside allowlist fails the save with no partial directory`() {
        val store = newStore()
        assertNull(
            store.save(
                capture(
                    artifacts = listOf(
                        PendingReportStore.Artifact("../evil.txt", byteArrayOf(1)),
                    ),
                ),
            ),
        )
        // manifest.json is a reserved store file, not an attachable artifact.
        assertNull(
            store.save(
                capture(
                    artifacts = listOf(
                        PendingReportStore.Artifact("manifest.json", byteArrayOf(1)),
                    ),
                ),
            ),
        )

        assertTrue(store.listReports().isEmpty())
        val leftovers = File(baseDir, "pending").listFiles().orEmpty()
        assertTrue(leftovers.isEmpty(), "staging must be discarded, found: ${leftovers.map { it.name }}")
    }
}
