package org.siloserver.silo.common.diagnostics

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingReportStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val binding = PendingReportBinding("server-1", "user-1", "adult-1", 7)

    @Test
    fun savePublishesCompleteReportAndLoadRoundTrips() {
        val store = newStore(nowMs = { day(10) })

        val saved = store.save(capture(day = 10, fingerprint = "fp-1"))

        assertTrue(saved.directory.isDirectory)
        assertEquals(
            setOf("binding.json", "manifest.json", "state.json", "device.json", "logs.jsonl"),
            saved.directory.listFiles().orEmpty().mapTo(mutableSetOf(), File::getName),
        )
        val bindingText = saved.directory.resolve("binding.json").readText()
        assertFalse(bindingText.contains("token", ignoreCase = true), bindingText)
        assertFalse(bindingText.contains("https://"), bindingText)
        assertEquals(saved, store.load(saved.id))
        assertEquals(listOf(saved.id), store.list(binding.binding).map(PendingReport::id))
        assertTrue(store.hasSeenFingerprint("fp-1"))
    }

    @Test
    fun invalidArtifactsNeverPublishAndStagingIsCleaned() {
        val store = newStore(nowMs = { day(10) })

        listOf("../escape", "body.txt", "/absolute", "crash/../../escape").forEachIndexed { index, path ->
            assertFailsWith<IllegalArgumentException> {
                store.save(capture(day = 10, fingerprint = "bad-$index", artifacts = mapOf(path to "secret".encodeToByteArray())))
            }
            assertFalse(store.hasSeenFingerprint("bad-$index"))
        }

        val root = temporaryFolder.root.resolve("store/client-diagnostics/pending")
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("staging") })
        assertTrue(store.list(binding.binding).isEmpty())
    }

    @Test
    fun droppedLateReportIsNotMarkedSeenButNewerReportEvictsOldest() {
        val store = newStore(nowMs = { day(10) }, maxReportsPerBinding = 3, retentionMs = day(30))
        repeat(3) { store.save(capture(day = it + 2, fingerprint = "kept-$it")) }

        assertFailsWith<PendingReportRejectedException> {
            store.save(capture(day = 1, fingerprint = "late"))
        }
        assertFalse(store.hasSeenFingerprint("late"))

        val newest = store.save(capture(day = 6, fingerprint = "newest"))
        val reports = store.list(binding.binding)
        assertEquals(3, reports.size)
        assertTrue(reports.any { it.id == newest.id })
        assertTrue(reports.none { it.manifest.report.captureSessionId == "capture-2" })
    }

    @Test
    fun expiryPrunesReportsFingerprintsAndThrottleEntries() {
        var now = day(1)
        val store = newStore(nowMs = { now }, retentionMs = day(7))
        val old = store.save(capture(day = 1, fingerprint = "old"))
        store.markThrottled("crash:old", atEpochMs = day(1))
        assertTrue(store.isThrottled("crash:old", windowMs = day(7)))

        now = day(9) + 1

        assertTrue(store.list(binding.binding).isEmpty())
        assertNull(store.load(old.id))
        assertFalse(store.hasSeenFingerprint("old"))
        assertFalse(store.isThrottled("crash:old", windowMs = day(7)))
    }

    @Test
    fun alreadyExpiredCaptureIsRejectedWithoutMarkingFingerprintSeen() {
        val store = newStore(nowMs = { day(10) }, retentionMs = day(7))

        assertFailsWith<PendingReportRejectedException> {
            store.save(capture(day = 2, fingerprint = "expired"))
        }

        assertFalse(store.hasSeenFingerprint("expired"))
        assertTrue(store.list(binding.binding).isEmpty())
    }

    @Test
    fun stateDeleteAndBindingPurgeArePersistentAndScoped() {
        val store = newStore(nowMs = { day(10) })
        val first = store.save(capture(day = 9, fingerprint = "first"))
        val otherBinding = PendingReportBinding("server-2", "user-1", null, 8)
        val other = store.save(capture(day = 10, fingerprint = "other", binding = otherBinding))

        store.markState(first.id, PendingReportStatus.RETRYABLE, errorCode = "busy")
        val updated = assertNotNull(store.load(first.id))
        assertEquals(PendingReportStatus.RETRYABLE, updated.state.status)
        assertEquals("busy", updated.state.errorCode)
        assertEquals(1, updated.state.attemptCount)

        store.purge(binding.binding)
        assertNull(store.load(first.id))
        assertNotNull(store.load(other.id))
        store.delete(other.id)
        assertNull(store.load(other.id))
    }

    @Test
    fun retryAfterIsAccountScopedPersistentAndClearedByPurge() {
        val store = newStore(nowMs = { day(10) })
        val binding = this.binding.binding

        store.setRetryAfterDeadline(binding, day(11))

        assertEquals(day(11), store.retryAfterDeadline(binding))
        store.purge(binding)
        assertNull(store.retryAfterDeadline(binding))
    }

    @Test
    fun interruptedHostedEnvelopeStagingIsDiscardedAndTreatedAsMissing() {
        val store = newStore(nowMs = { day(10) })
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val report = store.save(capture(day = 10, fingerprint = "hosted", binding = hostedBinding))
        val staging = report.directory.resolve(".hosted-envelope-staging-${"a".repeat(32)}")
        assertTrue(staging.mkdirs())
        staging.resolve("manifest.json").writeText("partial")

        assertEquals(HostedEnvelopeLoadResult.Missing, store.loadHostedEnvelope(report.id))
        assertFalse(staging.exists(), "an uncommitted generation must never become the retry envelope")
        assertNull(store.load(report.id)?.state?.hostedEnvelopeGeneration)
    }

    @Test
    fun tamperedPublishedHostedMemberMakesTheCommittedEnvelopeCorrupt() {
        val store = newStore(nowMs = { day(10) })
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val report = store.save(capture(day = 10, fingerprint = "hosted-tamper", binding = hostedBinding))
        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        store.saveHostedEnvelope(report.id, bundle)
        val generation = assertNotNull(store.load(report.id)?.state?.hostedEnvelopeGeneration)
        report.directory.resolve(".hosted-envelope-$generation/entries/device.json").writeText("{\"tampered\":true}")

        assertEquals(HostedEnvelopeLoadResult.Corrupt, store.loadHostedEnvelope(report.id))
    }

    @Test
    fun hostedDeletionIntentIsDurableForEnvelopeOrRemoteIdentity() {
        val store = newStore(nowMs = { day(10) })
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val envelopeReport = store.save(capture(day = 8, fingerprint = "hosted-envelope", binding = hostedBinding))
        val bundle = FileDiagnosticsBundleBuilder().build(envelopeReport, redactionTokens = emptyList())
        store.saveHostedEnvelope(envelopeReport.id, bundle)
        val interruptedCopy = temporaryFolder.newFolder("hosted-delete-interrupted")
        envelopeReport.directory.copyRecursively(interruptedCopy, overwrite = true)
        val remoteReport = store.save(capture(day = 9, fingerprint = "hosted-remote", binding = hostedBinding))
        store.markHostedProcessing(remoteReport.id, "ABC123")
        val localOnly = store.save(capture(day = 10, fingerprint = "hosted-local", binding = hostedBinding))

        store.stageHostedDeletionAndDelete(envelopeReport.id)
        store.stageHostedDeletionAndDelete(remoteReport.id)
        store.stageHostedDeletionAndDelete(localOnly.id)

        assertNull(store.load(envelopeReport.id))
        assertNull(store.load(remoteReport.id))
        assertNull(store.load(localOnly.id))
        assertEquals(
            listOf(envelopeReport.id, remoteReport.id).sorted(),
            store.hostedDeletionIntents(),
        )

        // Simulate a process stopping after the atomic intent write but before
        // local evidence removal by restoring the report bytes while leaving
        // the durable UUID intent in place.
        interruptedCopy.copyRecursively(envelopeReport.directory, overwrite = true)
        assertTrue(envelopeReport.directory.isDirectory)

        val restarted = newStore(nowMs = { day(11) })
        assertFalse(envelopeReport.directory.exists())
        assertEquals(
            listOf(envelopeReport.id, remoteReport.id).sorted(),
            restarted.hostedDeletionIntents(),
        )
        restarted.completeHostedDeletion(envelopeReport.id)
        assertEquals(listOf(remoteReport.id), restarted.hostedDeletionIntents())
        restarted.completeHostedDeletion(remoteReport.id)
        assertTrue(restarted.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun hostedDeletionIntentCannotCompleteWhileLocalRemovalKeepsFailing() {
        val store = newStore(nowMs = { day(10) })
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val report = store.save(capture(day = 10, fingerprint = "hosted-delete-failure", binding = hostedBinding))
        store.markHostedProcessing(report.id, "ABC123")
        val interruptedCopy = temporaryFolder.newFolder("hosted-delete-failure-copy")
        report.directory.copyRecursively(interruptedCopy, overwrite = true)
        store.stageHostedDeletionAndDelete(report.id)
        interruptedCopy.copyRecursively(report.directory, overwrite = true)

        val originalPermissions = Files.getPosixFilePermissions(report.directory.toPath())
        Files.setPosixFilePermissions(
            report.directory.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        try {
            val restarted = newStore(nowMs = { day(11) })

            assertTrue(report.directory.exists(), "persistent removal failure must be observable")
            assertNull(restarted.load(report.id), "queued evidence must never become uploadable")
            assertTrue(restarted.hostedDeletionIntents().isEmpty(), "remote DELETE must wait for local removal")
            assertFailsWith<IllegalStateException> { restarted.completeHostedDeletion(report.id) }
            assertTrue(
                temporaryFolder.root.resolve("store/client-diagnostics/hosted-deletion-intents.json")
                    .readText()
                    .contains(report.id),
                "failed local removal must leave the durable intent queued",
            )
        } finally {
            if (report.directory.exists()) {
                Files.setPosixFilePermissions(report.directory.toPath(), originalPermissions)
            }
        }

        val recovered = newStore(nowMs = { day(12) })
        assertFalse(report.directory.exists())
        assertEquals(listOf(report.id), recovered.hostedDeletionIntents())
        recovered.completeHostedDeletion(report.id)
        assertTrue(recovered.hostedDeletionIntents().isEmpty())
    }

    private fun newStore(
        nowMs: () -> Long,
        maxReportsPerBinding: Int = 3,
        retentionMs: Long = day(7),
    ): FilePendingReportStore = FilePendingReportStore(
        noBackupFilesDir = temporaryFolder.root.resolve("store"),
        nowMs = nowMs,
        maxReportsPerBinding = maxReportsPerBinding,
        retentionMs = retentionMs,
    )

    private fun capture(
        day: Int,
        fingerprint: String,
        binding: PendingReportBinding = this.binding,
        artifacts: Map<String, ByteArray> = mapOf(
            "device.json" to "{\"captured_at\":\"2026-07-22T00:00:00Z\"}".encodeToByteArray(),
            "logs.jsonl" to "{\"msg\":\"safe\"}\n".encodeToByteArray(),
        ),
    ) = PendingReportCapture(
        binding = binding,
        manifest = manifest(day, binding),
        artifacts = artifacts,
        fingerprint = fingerprint,
        capturedAtEpochMs = day(day),
    )

    private fun manifest(day: Int, binding: PendingReportBinding) = DiagnosticsManifest(
        schemaVersion = 1,
        report = DiagnosticsReport(
            type = DiagnosticsReportType.MANUAL,
            capturedAt = "2026-07-${day.toString().padStart(2, '0')}T00:00:00Z",
            captureSessionId = "capture-$day",
            appVersion = "1.0",
            appBuild = "1",
            platform = DiagnosticsPlatform.ANDROID,
            osVersion = "36",
            profileId = binding.profileId,
        ),
        destination = DiagnosticsDestination(binding.serverInstanceId),
        consent = DiagnosticsConsent(DiagnosticsConsentMode.MANUAL, 1),
        deviceSummary = DiagnosticsDeviceSummary("Google", "Shield", "Android 36", "tv"),
        playbackSessionIds = emptyList(),
        logSummary = DiagnosticsLogSummary(1, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
        archive = DiagnosticsArchive(listOf("manifest.json"), 0, 0, "0".repeat(64)),
    )

    private companion object {
        fun day(value: Int): Long = value * 24L * 60 * 60 * 1_000
    }
}
