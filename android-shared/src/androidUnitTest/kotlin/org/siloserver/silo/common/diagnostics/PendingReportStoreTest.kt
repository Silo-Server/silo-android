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
    fun futureDatedCaptureIsRejectedAndFutureIndexValuesArePruned() {
        val now = day(10)
        val store = newStore(nowMs = { now }, retentionMs = day(7))
        store.markThrottled("future", atEpochMs = day(11))

        assertFailsWith<PendingReportRejectedException> {
            store.save(capture(day = 11, fingerprint = "future"))
        }

        assertFalse(store.hasSeenFingerprint("future"))
        assertFalse(store.isThrottled("future", windowMs = day(7)))
        assertTrue(store.list(binding.binding).isEmpty())
    }

    @Test
    fun negativeClockFailsClosedBeforeReportOrIndexMutation() {
        val store = newStore(nowMs = { -1L }, retentionMs = day(7))

        assertFailsWith<IllegalStateException> {
            store.save(capture(day = 0, fingerprint = "negative-clock"))
        }
        assertFailsWith<IllegalStateException> {
            store.markThrottled("negative-clock", atEpochMs = 0)
        }
    }

    @Test
    fun negativeClockCannotPublishHostedErasureAuthorityOrDeleteRawEvidence() {
        var now = day(10)
        val hostedBinding = hostedBinding()
        val store = newStore(nowMs = { now }, retentionMs = day(7))
        val report = store.save(capture(day = 10, fingerprint = "negative-authority", binding = hostedBinding))
        now = -1

        assertFailsWith<IllegalStateException> {
            store.stageHostedDeletionAndDelete(report.id)
        }

        assertTrue(report.directory.isDirectory)
        assertTrue(store.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun correctedClockPrunesFutureHostedEvidenceButRetainsItsErasureAuthority() {
        var now = day(20)
        val hostedBinding = hostedBinding()
        val store = newStore(nowMs = { now }, retentionMs = day(7))
        val report = store.save(capture(day = 20, fingerprint = "future-hosted", binding = hostedBinding))
        store.markHostedProcessing(report.id, "ABC123")

        now = day(10)

        assertTrue(store.list(hostedBinding.binding).isEmpty())
        assertFalse(report.directory.exists())
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))

        store.purge(hostedBinding.binding)
        assertEquals(listOf(report.id), store.hostedDeletionIntents())
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
    fun hostedEnvelopeSyncsNestedEntryDirectoriesBottomUpBeforePublishing() {
        val synced = mutableListOf<String>()
        val store = newStore(
            nowMs = { day(10) },
            directorySync = { directory -> synced += directory.name },
        )
        val report = store.save(
            capture(
                day = 10,
                fingerprint = "hosted-nested-sync",
                binding = hostedBinding(),
                artifacts = mapOf(
                    "device.json" to "{}".encodeToByteArray(),
                    "logs.jsonl" to "{\"msg\":\"safe\"}\n".encodeToByteArray(),
                    "crash/stack.txt" to "safe stack".encodeToByteArray(),
                ),
            ),
        )
        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        synced.clear()

        store.saveHostedEnvelope(report.id, bundle)

        val crashSync = synced.lastIndexOf("crash")
        val entriesSync = synced.indexOfFirstAfter(crashSync) { it == "entries" }
        val stagingSync = synced.indexOfFirstAfter(entriesSync) { it.startsWith(".hosted-envelope-staging-") }
        assertTrue(crashSync >= 0, synced.toString())
        assertTrue(entriesSync > crashSync, synced.toString())
        assertTrue(stagingSync > entriesSync, synced.toString())
        assertTrue(store.loadHostedEnvelope(report.id) is HostedEnvelopeLoadResult.Available)
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
            listOf(envelopeReport.id, remoteReport.id, localOnly.id).sorted(),
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
            listOf(envelopeReport.id, remoteReport.id, localOnly.id).sorted(),
            restarted.hostedDeletionIntents(),
        )
        restarted.completeHostedDeletion(envelopeReport.id)
        assertEquals(listOf(localOnly.id, remoteReport.id).sorted(), restarted.hostedDeletionIntents())
        restarted.completeHostedDeletion(remoteReport.id)
        restarted.completeHostedDeletion(localOnly.id)
        assertTrue(restarted.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun failedIntentReplacementPreservesPriorAuthorityAndNewRawEvidence() {
        val files = temporaryFolder.root.resolve("store")
        val initial = newStore(nowMs = { day(10) })
        val prior = initial.save(
            capture(day = 9, fingerprint = "prior-intent", binding = hostedBinding()),
        )
        initial.stageHostedDeletionAndDelete(prior.id)
        assertFalse(prior.directory.exists())
        assertEquals(listOf(prior.id), initial.hostedDeletionIntents())

        val restarted = FilePendingReportStore(
            noBackupFilesDir = files,
            nowMs = { day(11) },
            directorySync = {},
            atomicRename = { source, target ->
                if (target.name == "hosted-deletion-intents.json" && target.exists()) {
                    error("simulated atomic rename failure")
                }
                testAtomicRename(source, target)
            },
        )
        val pending = restarted.save(
            capture(day = 10, fingerprint = "new-intent", binding = hostedBinding()),
        )

        assertFailsWith<IllegalStateException> {
            restarted.stageHostedDeletionAndDelete(pending.id)
        }

        assertFalse(prior.directory.exists(), "prior raw evidence was already removed")
        assertTrue(pending.directory.isDirectory, "new raw evidence must remain when intent publication fails")
        assertEquals(listOf(prior.id), restarted.hostedDeletionIntents())
        val stateDirectory = files.resolve("client-diagnostics")
        assertTrue(stateDirectory.resolve("hosted-deletion-intents.json.tmp").isFile)
        assertFalse(stateDirectory.resolve("hosted-deletion-intents.json").readText().contains(pending.id))
    }

    @Test
    fun startupStrictlyRemovesStagingAndMalformedUuidEvidence() {
        val root = temporaryFolder.root.resolve("store/client-diagnostics/pending")
        assertTrue(root.mkdirs())
        val staging = root.resolve(".staging-${"a".repeat(32)}")
        assertTrue(staging.mkdirs())
        staging.resolve("logs.jsonl").writeText("raw staging evidence")
        val malformed = root.resolve("b".repeat(32))
        assertTrue(malformed.mkdirs())
        malformed.resolve("logs.jsonl").writeText("raw malformed evidence")

        newStore(nowMs = { day(10) })

        assertFalse(staging.exists())
        assertFalse(malformed.exists())
    }

    @Test
    fun corruptBindingAfterHostedCreatePreservesUuidAsDeletionIntent() {
        val store = newStore(nowMs = { day(10) })
        val report = store.save(
            capture(day = 10, fingerprint = "hosted-corrupt-binding", binding = hostedBinding()),
        )
        store.markHostedProcessing(report.id, "ABC123")
        report.directory.resolve("binding.json").writeText("{not-json")

        val restarted = newStore(nowMs = { day(11) })

        assertFalse(report.directory.exists())
        assertEquals(listOf(report.id), restarted.hostedDeletionIntents())
        restarted.completeHostedDeletion(report.id)
        assertTrue(restarted.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun corruptDeletionIntentLedgerNeverReexposesInterruptedHostedEvidence() {
        val files = temporaryFolder.newFolder("corrupt-intent-store")
        val store = FilePendingReportStore(
            files,
            nowMs = { day(10) },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val report = store.save(
            capture(day = 10, fingerprint = "corrupt-intent", binding = hostedBinding()),
        )
        val evidence = temporaryFolder.newFolder("corrupt-intent-evidence")
        report.directory.copyRecursively(evidence, overwrite = true)
        store.stageHostedDeletionAndDelete(report.id)
        evidence.copyRecursively(report.directory, overwrite = true)
        files.resolve("client-diagnostics/hosted-deletion-intents.json").writeText("{not-json")

        val restarted = FilePendingReportStore(
            files,
            nowMs = { day(11) },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )

        assertFailsWith<IllegalArgumentException> { restarted.load(report.id) }
        assertFailsWith<IllegalArgumentException> { restarted.list(hostedBinding().binding) }
        assertFailsWith<IllegalArgumentException> { restarted.hostedDeletionIntents() }
        assertTrue(report.directory.resolve("logs.jsonl").isFile)
    }

    @Test
    fun corruptReadyReceiptLedgerNeverReexposesInterruptedHostedEvidence() {
        val files = temporaryFolder.newFolder("corrupt-receipt-store")
        val store = FilePendingReportStore(
            files,
            nowMs = { day(10) },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val hostedBinding = hostedBinding()
        val report = store.save(
            capture(day = 10, fingerprint = "corrupt-receipt", binding = hostedBinding),
        )
        val evidence = temporaryFolder.newFolder("corrupt-receipt-evidence")
        report.directory.copyRecursively(evidence, overwrite = true)
        store.recordHostedReadyAndDelete(report.id, hostedBinding, "ABC123")
        evidence.copyRecursively(report.directory, overwrite = true)
        files.resolve("client-diagnostics/hosted-ready-receipts.json").writeText("{not-json")

        val restarted = FilePendingReportStore(
            files,
            nowMs = { day(11) },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )

        assertFailsWith<IllegalArgumentException> { restarted.load(report.id) }
        assertFailsWith<IllegalArgumentException> { restarted.list(hostedBinding.binding) }
        assertTrue(report.directory.resolve("logs.jsonl").isFile)
    }

    @Test
    fun destructivePurgeFailsClosedWhenStagingOrMalformedEvidenceCannotBeDeleted() {
        var blockedName: String? = null
        val store = newStore(
            nowMs = { day(10) },
            deleteRecursively = { file ->
                if (file.name == blockedName) false else file.deleteRecursively()
            },
        )
        val root = temporaryFolder.root.resolve("store/client-diagnostics/pending")

        val staging = root.resolve(".staging-${"c".repeat(32)}")
        assertTrue(staging.mkdirs())
        staging.resolve("logs.jsonl").writeText("raw staging evidence")
        blockedName = staging.name
        assertFailsWith<IllegalStateException> { store.purge(binding.binding) }
        assertTrue(staging.exists())

        blockedName = null
        store.purge(binding.binding)
        val malformed = root.resolve("d".repeat(32))
        assertTrue(malformed.mkdirs())
        malformed.resolve("logs.jsonl").writeText("raw malformed evidence")
        blockedName = malformed.name
        assertFailsWith<IllegalStateException> { store.purge(binding.binding) }
        assertTrue(malformed.exists())
    }

    @Test
    fun destructivePurgeFailsClosedWhenPendingRootCannotBeEnumerated() {
        var failEnumeration = false
        val store = newStore(
            nowMs = { day(10) },
            listFiles = { directory -> if (failEnumeration) null else directory.listFiles() },
        )
        store.save(capture(day = 10, fingerprint = "enumeration"))
        failEnumeration = true

        assertFailsWith<IllegalStateException> { store.purge(binding.binding) }
    }

    @Test
    fun hostedDeletionAbortsBeforeRawRemovalWhenIntentDirectorySyncFailsAndRetriesSafely() {
        var failClientStateSync = false
        val store = newStore(
            nowMs = { day(10) },
            directorySync = { directory ->
                if (failClientStateSync && directory.name == "client-diagnostics") {
                    error("injected intent directory fsync failure")
                }
            },
        )
        val report = store.save(
            capture(day = 10, fingerprint = "intent-fsync", binding = hostedBinding()),
        )
        failClientStateSync = true

        assertFailsWith<IllegalStateException> { store.stageHostedDeletionAndDelete(report.id) }
        assertTrue(report.directory.resolve("logs.jsonl").isFile)
        assertFalse(
            temporaryFolder.root.resolve("store/client-diagnostics/hosted-deletion-intents.json").isFile,
            "an unsynced intent must not be treated as committed",
        )

        failClientStateSync = false
        store.stageHostedDeletionAndDelete(report.id)
        assertFalse(report.directory.exists())
        assertEquals(listOf(report.id), store.hostedDeletionIntents())
    }

    @Test
    fun hostedDeletionPropagatesPostRemovalDirectorySyncFailureWithoutLosingIntent() {
        var failPendingRootSync = false
        val store = newStore(
            nowMs = { day(10) },
            directorySync = { directory ->
                if (failPendingRootSync && directory.name == "pending") {
                    error("injected raw deletion directory fsync failure")
                }
            },
        )
        val report = store.save(
            capture(day = 10, fingerprint = "raw-fsync", binding = hostedBinding()),
        )
        failPendingRootSync = true

        assertFailsWith<IllegalStateException> { store.stageHostedDeletionAndDelete(report.id) }
        assertFalse(report.directory.exists())
        assertTrue(
            temporaryFolder.root.resolve("store/client-diagnostics/hosted-deletion-intents.json")
                .readText()
                .contains(report.id),
        )

        failPendingRootSync = false
        assertEquals(listOf(report.id), store.hostedDeletionIntents())
        store.completeHostedDeletion(report.id)
        assertTrue(store.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun startupCleanupFailureIsContainedButEveryLaterBoundaryRetriesStrictly() {
        val root = temporaryFolder.root.resolve("store/client-diagnostics/pending")
        assertTrue(root.mkdirs())
        val staging = root.resolve(".staging-${"e".repeat(32)}")
        assertTrue(staging.mkdirs())
        staging.resolve("logs.jsonl").writeText("raw startup evidence")
        var failDeletion = true

        val store = newStore(
            nowMs = { day(10) },
            deleteRecursively = { file ->
                if (failDeletion && file == staging) false else file.deleteRecursively()
            },
        )

        assertTrue(staging.exists(), "construction must remain available so the identity gate can install")
        assertFailsWith<IllegalStateException> { store.list(binding.binding) }
        assertFailsWith<IllegalStateException> { store.purge(binding.binding) }

        failDeletion = false
        store.purge(binding.binding)
        assertFalse(staging.exists())
    }

    @Test
    fun explicitDeleteStagesUnsentHostedUuidBeforeAPartialLocalDeletion() {
        val hostedBinding = hostedBinding()
        var blockedId: String? = null
        var failDeletion = false
        val store = newStore(
            nowMs = { day(10) },
            deleteRecursively = { file ->
                if (failDeletion && file.name == blockedId) {
                    file.resolve("manifest.json").delete()
                    false
                } else {
                    file.deleteRecursively()
                }
            },
        )
        val report = store.save(capture(day = 10, fingerprint = "unsent-partial", binding = hostedBinding))
        blockedId = report.id
        failDeletion = true

        assertFailsWith<IllegalStateException> { store.stageHostedDeletionAndDelete(report.id) }
        assertTrue(report.directory.resolve("logs.jsonl").isFile)
        assertTrue(
            temporaryFolder.root.resolve("store/client-diagnostics/hosted-deletion-intents.json")
                .readText()
                .contains(report.id),
        )

        val restarted = newStore(nowMs = { day(11) })
        assertFalse(report.directory.exists())
        assertEquals(listOf(report.id), restarted.hostedDeletionIntents())
    }

    @Test
    fun hostedExpiryRetainsHandoffAuthorityUntilTurnOffStagesErasure() {
        var now = day(10)
        val hostedBinding = hostedBinding()
        val store = newStore(nowMs = { now }, retentionMs = day(7))
        val report = store.save(capture(day = 10, fingerprint = "hosted-expiry", binding = hostedBinding))
        store.markHostedProcessing(report.id, "ABC123")

        now = day(18)
        assertTrue(store.list(hostedBinding.binding).isEmpty())
        assertFalse(report.directory.exists())
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))
        assertTrue(store.hostedReadyReports().isEmpty())
        assertTrue(store.hostedDeletionIntents().isEmpty())

        store.purge(hostedBinding.binding)
        assertEquals(listOf(report.id), store.hostedDeletionIntents())
    }

    @Test
    fun hostedQuotaEvictionRetainsHandoffAuthorityUntilTurnOffStagesErasure() {
        val hostedBinding = hostedBinding()
        val store = newStore(nowMs = { day(10) }, maxReportsPerBinding = 1, retentionMs = day(30))
        val evicted = store.save(capture(day = 9, fingerprint = "hosted-evicted", binding = hostedBinding))
        store.markHostedProcessing(evicted.id, "ABC123")

        val retained = store.save(capture(day = 10, fingerprint = "hosted-retained", binding = hostedBinding))

        assertNull(store.load(evicted.id))
        assertNotNull(store.load(retained.id))
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(evicted.id))
        assertTrue(store.hostedReadyReports().isEmpty())
        assertTrue(store.hostedDeletionIntents().isEmpty())

        store.purge(hostedBinding.binding)
        assertEquals(listOf(evicted.id, retained.id).sorted(), store.hostedDeletionIntents())
    }

    @Test
    fun legacyUnindexedPurgeAllDeletesEveryReportAndPreservesHostedErasureAuthority() {
        val store = newStore(nowMs = { day(10) })
        val selfHosted = store.save(capture(day = 9, fingerprint = "legacy-self-hosted"))
        val hostedBinding = hostedBinding()
        val hosted = store.save(capture(day = 10, fingerprint = "legacy-hosted", binding = hostedBinding))
        store.markHostedProcessing(hosted.id, "ABC123")

        store.purgeAll()

        assertNull(store.load(selfHosted.id))
        assertNull(store.load(hosted.id))
        assertEquals(listOf(hosted.id), store.hostedDeletionIntents())
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

    @Test
    fun hostedReadyReceiptSurvivesEvidenceDeletionAndRestartUntilErasureCompletes() {
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val store = newStore(nowMs = { day(10) })
        val report = store.save(capture(day = 10, fingerprint = "hosted-ready", binding = hostedBinding))
        store.markHostedProcessing(report.id, "ABC123")
        val interruptedCopy = temporaryFolder.newFolder("hosted-ready-interrupted")
        report.directory.copyRecursively(interruptedCopy, overwrite = true)

        store.recordHostedReadyAndDelete(report.id, hostedBinding, "ABC123")

        assertNull(store.load(report.id))
        assertFalse(report.directory.exists())
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))
        assertTrue(store.hostedDeletionIntents().isEmpty())

        // Simulate stopping after the atomic UUID receipt write but before raw
        // evidence removal. Startup must hide and finish removing the evidence.
        interruptedCopy.copyRecursively(report.directory, overwrite = true)
        assertTrue(report.directory.isDirectory)
        val restarted = newStore(nowMs = { day(11) })
        assertFalse(report.directory.exists())
        assertNull(restarted.load(report.id))
        assertEquals(hostedBinding.binding, restarted.hostedReadyBinding(report.id))
        assertEquals("ABC123", restarted.hostedReadyReports().single().shortId)

        restarted.stageHostedDeletionAndDelete(report.id)
        assertEquals(listOf(report.id), restarted.hostedDeletionIntents())
        assertEquals(hostedBinding.binding, restarted.hostedReadyBinding(report.id))
        assertTrue(restarted.hostedReadyReports().isEmpty())
        restarted.completeHostedDeletion(report.id)
        assertTrue(restarted.hostedDeletionIntents().isEmpty())
        assertNull(restarted.hostedReadyBinding(report.id))
    }

    @Test
    fun purgeStagesErasureForReceiptAfterReadyEvidenceWasRemoved() {
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        val store = newStore(nowMs = { day(10) })
        val report = store.save(capture(day = 10, fingerprint = "hosted-ready-purge", binding = hostedBinding))
        store.markHostedProcessing(report.id, "ABC123")
        store.recordHostedReadyAndDelete(report.id, hostedBinding)

        store.purge(hostedBinding.binding)

        assertNull(store.load(report.id))
        assertEquals(listOf(report.id), store.hostedDeletionIntents())
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))
    }

    @Test
    fun hostedReadyReceiptExpiryOrClockJumpTransitionsToDurableErasureInsteadOfDiscardingAuthority() {
        val hostedBinding = PendingReportBinding(
            serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            accountUserId = "anonymous-hosted-device",
            profileId = null,
            ownershipGeneration = 7,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )
        var now = day(10)
        val store = newStore(nowMs = { now })
        val report = store.save(capture(day = 10, fingerprint = "hosted-ready-retention", binding = hostedBinding))
        store.recordHostedReadyAndDelete(report.id, hostedBinding)

        now = day(47)
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))
        now += 1
        assertEquals(hostedBinding.binding, store.hostedReadyBinding(report.id))
        assertEquals(listOf(report.id), store.hostedDeletionIntents())

        store.completeHostedDeletion(report.id)
        assertNull(store.hostedReadyBinding(report.id))
        assertTrue(store.hostedDeletionIntents().isEmpty())
    }

    private fun newStore(
        nowMs: () -> Long,
        maxReportsPerBinding: Int = 3,
        retentionMs: Long = day(7),
        deleteRecursively: (File) -> Boolean = File::deleteRecursively,
        listFiles: (File) -> Array<File>? = File::listFiles,
        directorySync: (File) -> Unit = {},
    ): FilePendingReportStore = FilePendingReportStore(
        noBackupFilesDir = temporaryFolder.root.resolve("store"),
        nowMs = nowMs,
        maxReportsPerBinding = maxReportsPerBinding,
        retentionMs = retentionMs,
        deleteRecursively = deleteRecursively,
        listFiles = listFiles,
        directorySync = directorySync,
        atomicRename = ::testAtomicRename,
    )

    private fun hostedBinding() = PendingReportBinding(
        serverInstanceId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
        accountUserId = "anonymous-hosted-device",
        profileId = null,
        ownershipGeneration = 7,
        destinationKind = DiagnosticsDestinationKind.HOSTED,
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

        private fun List<String>.indexOfFirstAfter(startIndex: Int, predicate: (String) -> Boolean): Int {
            val relative = drop(startIndex + 1).indexOfFirst(predicate)
            return if (relative < 0) -1 else startIndex + 1 + relative
        }
    }
}
