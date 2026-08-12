package org.siloserver.silo.common.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.model.diagnostics.DiagnosticsArchive
import org.siloserver.silo.model.diagnostics.DiagnosticsConsent
import org.siloserver.silo.model.diagnostics.DiagnosticsConsentMode as ManifestConsentMode
import org.siloserver.silo.model.diagnostics.DiagnosticsDestination
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.diagnostics.DiagnosticsLogSummary
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.model.diagnostics.DiagnosticsPlatform
import org.siloserver.silo.model.diagnostics.DiagnosticsReport
import org.siloserver.silo.model.diagnostics.DiagnosticsReportType
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun profileChangeClosesGateBeforeMutationAndInvalidatesTimedCapture() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.startTimedCapture()

        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            assertTrue(capture.gateClosed)
            identity.current = ADULT_B
        }
        fixture.coordinator.refresh()

        assertEquals(TimedCaptureStatus.INVALIDATED, fixture.coordinator.state.value.timedCapture.status)
        assertTrue(capture.cancelled.isNotEmpty())
        assertTrue(capture.persistentBreadcrumbsEnabled, "the new identity may enable fresh evidence")
    }

    @Test
    fun neverClosesCaptureAndPurgesAllBindingEvidence() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.startTimedCapture()
        fixture.evidence.add(ADULT_A.binding)

        fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)

        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
        assertFalse(capture.hasPersistentEvidence)
        assertFalse(ADULT_A.binding in fixture.evidence)
        assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)

        fixture.coordinator.captureNow()
        assertEquals(1, capture.captureNowCalls)
        assertFalse(capture.hasPersistentEvidence)
    }

    @Test
    fun manualCaptureFailsClosedWhenLiveDestinationAttestationFails() = runTest {
        val identity = MutableIdentityResolver(ADULT_A).apply { captureAttestationAllowed = false }
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertNull(fixture.coordinator.captureNow())
        fixture.coordinator.startTimedCapture()

        assertEquals(2, identity.captureAttestationCalls)
        assertEquals(0, capture.captureNowCalls)
        assertEquals(TimedCaptureStatus.IDLE, fixture.coordinator.state.value.timedCapture.status)
    }

    @Test
    fun promptAggregatesAccountReportsAcrossProfilesAndTheyRemainVisibleOffline() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            Dispatchers.Unconfined,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.reports.save(reportCapture(ADULT_A, "first"))
        fixture.reports.save(reportCapture(ADULT_B, "second"))

        fixture.coordinator.refresh()

        assertEquals(2, fixture.coordinator.state.value.pending.size)
        assertEquals(2, fixture.coordinator.state.value.prompt?.reportCount)

        identity.current = null
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.OFFLINE, fixture.coordinator.state.value.availability)
        assertEquals(2, fixture.coordinator.state.value.pending.size)
        assertEquals(null, fixture.coordinator.state.value.prompt)
    }

    @Test
    fun unresolvedProfileSwitchCannotRestoreAnAdultOfflineCache() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val fixture = fixture(
            identity,
            transitions,
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.reports.save(reportCapture(ADULT_A, "adult"))

        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            identity.current = null
        }
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.OFFLINE, fixture.coordinator.state.value.availability)
        assertFalse(fixture.coordinator.state.value.profileEligible)
        assertTrue(fixture.coordinator.state.value.pending.isEmpty())
    }

    @Test
    fun confirmedIneligibleProfileClearsThePreviousAdultOfflineCache() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.reports.save(reportCapture(ADULT_A, "adult"))

        identity.current = ADULT_A.copy(profileEligible = false)
        fixture.coordinator.refresh()
        identity.current = null
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.OFFLINE, fixture.coordinator.state.value.availability)
        assertFalse(fixture.coordinator.state.value.profileEligible)
        assertTrue(fixture.coordinator.state.value.pending.isEmpty())
    }

    @Test
    fun offlineCacheIsHiddenWhenLocalIdentityCannotBeAttested() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.reports.save(reportCapture(ADULT_A, "adult"))

        identity.current = null
        identity.trustCachedIdentity = false
        fixture.coordinator.refresh()

        assertFalse(fixture.coordinator.state.value.profileEligible)
        assertTrue(fixture.coordinator.state.value.pending.isEmpty())
    }

    @Test
    fun promptUploadRejectsANoticeVersionDifferentFromTheApprovedBatch() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(ADULT_A, "adult"))

        val decision = fixture.coordinator.upload(report.id, expectedNoticeVersion = ADULT_A.noticeVersion + 1)

        assertEquals(DiagnosticsUploadDecision.KeptConsentReviewRequired, decision)
        assertNotNull(fixture.reports.load(report.id))
    }

    @Test
    fun stalePromptCannotGrantAlwaysForADifferentNoticeVersion() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        fixture.coordinator.setConsent(
            DiagnosticsConsentMode.ALWAYS,
            expectedNoticeVersion = ADULT_A.noticeVersion + 1,
        )

        assertEquals(DiagnosticsConsentMode.ASK, fixture.coordinator.state.value.consent)
    }

    @Test
    fun hostedDestinationNeverEnablesAutomaticCrashUploads() = runTest {
        val hosted = ADULT_A.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device"),
            profileId = null,
            sourceProfileId = ADULT_A.profileId,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
        )
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        fixture.coordinator.setConsent(DiagnosticsConsentMode.ALWAYS)
        fixture.reports.save(reportCapture(hosted, "hosted"))
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsConsentMode.ASK, fixture.coordinator.state.value.consent)
        assertFalse(fixture.coordinator.state.value.allowsAutomaticUpload)
        assertEquals(HOSTED_DIAGNOSTICS_RETENTION_DAYS, fixture.coordinator.state.value.retentionDays)
        assertEquals(
            CAPTURED_AT + PENDING_DIAGNOSTICS_RETENTION_DAYS * 24L * 60 * 60 * 1_000,
            fixture.coordinator.state.value.pending.single().expiresAtEpochMs,
            "local pending evidence expires after seven days even though uploaded reports disclose 30-day retention",
        )
    }

    @Test
    fun cachedHostedCapabilitiesCannotOpenPersistentCaptureGates() = runTest {
        val identity = MutableIdentityResolver(hostedContext()).apply {
            captureAttestationAllowed = false
        }
        val capture = RecordingCaptureController()
        val runtime = RecordingRuntimePublisher()
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            runtimePublisher = runtime,
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertTrue(identity.captureAttestationCalls > 0)
        assertFalse(runtime.live)
        assertFalse(capture.debugLoggingEnabled)
        assertFalse(capture.persistentBreadcrumbsEnabled)
        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
    }

    @Test
    fun hostedProcessingSchedulesStatusPollingWithoutReportingFailure() = runTest {
        val hosted = hostedContext()
        val scheduled = mutableListOf<String>()
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { DiagnosticsUploader { DiagnosticsUploadDecision.HostedProcessing("ABC123") } },
            uploadScheduler = DiagnosticsUploadScheduler(scheduled::add),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-processing"))

        val decision = fixture.coordinator.upload(report.id)

        assertEquals(DiagnosticsUploadDecision.HostedProcessing("ABC123"), decision)
        assertEquals(listOf(report.id), scheduled)
        assertNotNull(fixture.reports.load(report.id))
    }

    @Test
    fun hostedDeletePersistsErasureIntentAndRetriesAfterAnAmbiguousFailure() = runTest {
        val hosted = ADULT_A.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device"),
            profileId = null,
            sourceProfileId = ADULT_A.profileId,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
        )
        val deleter = RecordingHostedReportDeleter(result = false)
        var scheduledDeletionRetries = 0
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            hostedReportDeleter = deleter,
            hostedDeletionScheduler = HostedDiagnosticsDeletionScheduler { scheduledDeletionRetries += 1 },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-delete"))
        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        fixture.reports.saveHostedEnvelope(report.id, bundle)

        assertTrue(fixture.coordinator.delete(report.id))

        assertEquals(null, fixture.reports.load(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        assertEquals(listOf(report.id), deleter.reportIds)
        assertTrue(scheduledDeletionRetries > 0)
        assertTrue(fixture.coordinator.state.value.pending.none { it.id == report.id })

        deleter.result = true
        fixture.coordinator.refresh()

        assertTrue(fixture.reports.hostedDeletionIntents().isEmpty())
        assertEquals(listOf(report.id, report.id), deleter.reportIds)
    }

    @Test
    fun selfHostedDeleteRemainsLocalOnly() = runTest {
        val deleter = RecordingHostedReportDeleter(result = false)
        val selfHosted = ADULT_A.copy(destinationKind = DiagnosticsDestinationKind.SELF_HOSTED)
        val fixture = fixture(
            MutableIdentityResolver(selfHosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            hostedReportDeleter = deleter,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(ADULT_A, "self-hosted-delete"))

        assertTrue(fixture.coordinator.delete(report.id))

        assertEquals(null, fixture.reports.load(report.id))
        assertTrue(deleter.reportIds.isEmpty())
    }

    @Test
    fun turnOffStagesHostedErasureBeforePurgingLocalEvidence() = runTest {
        val hosted = ADULT_A.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device"),
            profileId = null,
            sourceProfileId = ADULT_A.profileId,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
        )
        val deleter = RecordingHostedReportDeleter(result = false)
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            hostedReportDeleter = deleter,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-turn-off"))
        val bundle = FileDiagnosticsBundleBuilder().build(report, redactionTokens = emptyList())
        fixture.reports.saveHostedEnvelope(report.id, bundle)

        fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)

        assertEquals(null, fixture.reports.load(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        assertEquals(listOf(report.id), deleter.reportIds)
        assertEquals(listOf(hosted.binding), fixture.purgedBindings)
        assertTrue(fixture.coordinator.state.value.pending.isEmpty())
    }

    @Test
    fun failedTurnOffStaysClosedAndRefreshRetriesDurableLocalAndRemoteErasure() = runTest {
        val hosted = hostedContext()
        var failPurge = true
        val capture = RecordingCaptureController()
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            purgeFailure = {
                if (failPurge) IllegalStateException("injected Turn Off purge failure") else null
            },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-never-retry"))
        fixture.reports.markHostedProcessing(report.id, "ABC123")

        assertFailsWith<IllegalStateException> {
            fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)
        }

        assertEquals(DiagnosticsConsentMode.NEVER, fixture.settings.consent(hosted.binding, hosted.noticeVersion).mode)
        assertEquals(listOf(hosted.binding), fixture.settings.pendingErasureBindings())
        assertNotNull(fixture.reports.load(report.id))
        assertTrue(capture.gateClosed)

        failPurge = false
        fixture.coordinator.refresh()

        assertTrue(fixture.settings.pendingErasureBindings().isEmpty())
        assertNull(fixture.reports.load(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
    }

    @Test
    fun pendingTurnOffErasureRetriesBeforeAResolvedChildProfileReturnsIneligible() = runTest {
        val hosted = hostedContext()
        val identity = MutableIdentityResolver(hosted)
        val transitions = DefaultIdentityTransitionBarrier()
        var failPurge = true
        val fixture = fixture(
            identity,
            transitions,
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            purgeFailure = {
                if (failPurge) IllegalStateException("injected Turn Off purge failure") else null
            },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-never-child-retry"))
        fixture.reports.markHostedProcessing(report.id, "ABC123")

        assertFailsWith<IllegalStateException> {
            fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)
        }
        assertEquals(listOf(hosted.binding), fixture.settings.pendingErasureBindings())

        failPurge = false
        transitions.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            identity.current = hosted.copy(profileEligible = false, ownershipGeneration = 1)
        }
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.INELIGIBLE, fixture.coordinator.state.value.availability)
        assertTrue(fixture.settings.pendingErasureBindings().isEmpty())
        assertNull(fixture.reports.load(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
    }

    @Test
    fun explicitDeleteReturnsFailureUntilHostedEvidenceIsPhysicallyAbsent() = runTest {
        val hosted = hostedContext()
        var blockedId: String? = null
        var failDeletion = false
        val deletionCalls = mutableListOf<String>()
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            reportsFactory = { files ->
                FilePendingReportStore(
                    noBackupFilesDir = files,
                    nowMs = { CAPTURED_AT },
                    directorySync = {},
                    atomicRename = ::testAtomicRename,
                    deleteRecursively = { file ->
                        deletionCalls += file.name
                        if (failDeletion && file.name == blockedId) {
                            false
                        } else {
                            file.deleteRecursively()
                        }
                    },
                )
            },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-delete-partial"))
        fixture.coordinator.refresh()
        blockedId = report.id
        failDeletion = true

        assertFalse(fixture.coordinator.delete(report.id))

        assertTrue(report.directory.resolve("device.json").isFile, deletionCalls.toString())
        assertTrue(
            report.directory.parentFile.parentFile
                .resolve("hosted-deletion-intents.json")
                .readText()
                .contains(report.id),
        )
        assertTrue(fixture.coordinator.state.value.pending.any { it.id == report.id })
    }

    @Test
    fun startupRefreshRetriesPersistedHostedErasureIntents() = runTest {
        val hosted = ADULT_A.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device"),
            profileId = null,
            sourceProfileId = ADULT_A.profileId,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
        )
        val deleter = RecordingHostedReportDeleter(result = true)
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            hostedReportDeleter = deleter,
        )
        val report = fixture.reports.save(reportCapture(hosted, "hosted-startup-delete"))
        fixture.reports.markHostedProcessing(report.id, "ABC123")
        val interruptedCopy = temporaryFolder.newFolder("hosted-startup-delete-copy")
        report.directory.copyRecursively(interruptedCopy, overwrite = true)
        fixture.reports.stageHostedDeletionAndDelete(report.id)
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        interruptedCopy.copyRecursively(report.directory, overwrite = true)
        assertTrue(report.directory.isDirectory)

        fixture.coordinator.start()
        runCurrent()

        assertFalse(report.directory.exists())
        assertTrue(fixture.reports.hostedDeletionIntents().isEmpty())
        assertEquals(listOf(report.id), deleter.reportIds)
    }

    @Test
    fun hostedErasureNetworkWaitDoesNotBlockCoordinatorRefresh() = runTest {
        val hosted = hostedContext()
        val deletionStarted = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        val deleter = HostedDiagnosticsReportDeleter {
            deletionStarted.complete(Unit)
            releaseDeletion.await()
            true
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val identity = MutableIdentityResolver(hosted)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            dispatcher,
            hostedReportDeleter = deleter,
        )
        val report = fixture.reports.save(reportCapture(hosted, "hosted-non-blocking-delete"))
        fixture.reports.markHostedProcessing(report.id, "ABC123")
        fixture.reports.stageHostedDeletionAndDelete(report.id)

        fixture.coordinator.start()
        runCurrent()
        assertTrue(deletionStarted.isCompleted)

        identity.current = null
        val refresh = async(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.refresh() }
        runCurrent()
        assertEquals(
            DiagnosticsAvailabilityUi.OFFLINE,
            fixture.coordinator.state.value.availability,
            "remote DELETE polling must run outside the coordinator actor",
        )

        refresh.cancel()
        releaseDeletion.complete(Unit)
        runCurrent()
        assertTrue(fixture.reports.hostedDeletionIntents().isEmpty())
    }

    @Test
    fun queuedDeleteAfterHostedReadyStagesReceiptAndEventuallyErasesRemoteReport() = runTest {
        val hosted = hostedContext()
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val deleter = RecordingHostedReportDeleter(result = false)
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    val report = checkNotNull(reports.load(reportId))
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    reports.recordHostedReadyAndDelete(reportId, report.binding)
                    DiagnosticsUploadDecision.Uploaded("ABC123")
                }
            },
            hostedReportDeleter = deleter,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-ready-delete-race"))

        val upload = async { fixture.coordinator.upload(report.id) }
        uploadStarted.await()
        val deletion = async { fixture.coordinator.delete(report.id) }
        assertTrue(deleter.reportIds.isEmpty(), "Delete must remain queued while the manual upload owns the actor")
        releaseUpload.complete(Unit)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), upload.await())
        assertTrue(deletion.await())
        assertNull(fixture.reports.load(report.id))
        assertEquals(hosted.binding, fixture.reports.hostedReadyBinding(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        assertEquals(listOf(report.id), deleter.reportIds)

        deleter.result = true
        fixture.coordinator.refresh()

        assertTrue(fixture.reports.hostedDeletionIntents().isEmpty())
        assertNull(fixture.reports.hostedReadyBinding(report.id))
        assertEquals(listOf(report.id, report.id), deleter.reportIds)
        assertEquals(DiagnosticsUploadDecision.KeptInvalid, fixture.coordinator.upload(report.id))
    }

    @Test
    fun queuedTurnOffAfterHostedReadyStagesReceiptAndEventuallyErasesRemoteReport() = runTest {
        val hosted = hostedContext()
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val deleter = RecordingHostedReportDeleter(result = false)
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    val report = checkNotNull(reports.load(reportId))
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    reports.recordHostedReadyAndDelete(reportId, report.binding)
                    DiagnosticsUploadDecision.Uploaded("ABC123")
                }
            },
            hostedReportDeleter = deleter,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val report = fixture.reports.save(reportCapture(hosted, "hosted-ready-never-race"))

        val upload = async { fixture.coordinator.upload(report.id) }
        uploadStarted.await()
        val turnOff = async { fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER) }
        assertTrue(deleter.reportIds.isEmpty(), "Turn Off must remain queued while the manual upload owns the actor")
        releaseUpload.complete(Unit)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), upload.await())
        turnOff.await()
        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
        assertNull(fixture.reports.load(report.id))
        assertEquals(hosted.binding, fixture.reports.hostedReadyBinding(report.id))
        assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
        assertEquals(listOf(report.id), deleter.reportIds)
        assertEquals(listOf(hosted.binding), fixture.purgedBindings)

        deleter.result = true
        fixture.coordinator.refresh()

        assertTrue(fixture.reports.hostedDeletionIntents().isEmpty())
        assertNull(fixture.reports.hostedReadyBinding(report.id))
        assertEquals(listOf(report.id, report.id), deleter.reportIds)
    }

    @Test
    fun automaticUploadAndTurnOffShareTheCoordinatorPrivacyBoundary() = runTest {
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var uploadCalls = 0
        val selfHosted = ADULT_A.copy(destinationKind = DiagnosticsDestinationKind.SELF_HOSTED)
        val fixture = fixture(
            MutableIdentityResolver(selfHosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    uploadCalls += 1
                    reports.delete(reportId)
                    DiagnosticsUploadDecision.Uploaded("ABC123")
                }
            },
        )
        fixture.settings.setDestinationKind(DiagnosticsDestinationKind.SELF_HOSTED)
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.setConsent(DiagnosticsConsentMode.ALWAYS)
        val report = fixture.reports.save(reportCapture(selfHosted, "worker-never-race"))

        val upload = async { fixture.coordinator.uploadAutomatically(report.id) }
        uploadStarted.await()
        val turnOff = async { fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER) }
        runCurrent()
        assertFalse(turnOff.isCompleted, "Turn Off must wait for transport that already won the actor boundary")
        releaseUpload.complete(Unit)
        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), upload.await())
        turnOff.await()
        assertEquals(1, uploadCalls)
        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
        assertNull(fixture.reports.load(report.id))

        val later = fixture.coordinator.uploadAutomatically(report.id)
        assertEquals(DiagnosticsUploadDecision.KeptInvalid, later)
        assertEquals(1, uploadCalls, "no transport may start after Turn Off returned")
    }

    @Test
    fun destinationChangeThatWinsBeforeAutomaticUploadPreventsTransport() = runTest {
        var uploadCalls = 0
        val selfHosted = ADULT_A.copy(destinationKind = DiagnosticsDestinationKind.SELF_HOSTED)
        val fixture = fixture(
            MutableIdentityResolver(selfHosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = {
                DiagnosticsUploader {
                    uploadCalls += 1
                    DiagnosticsUploadDecision.Uploaded("ABC123")
                }
            },
        )
        fixture.settings.setDestinationKind(DiagnosticsDestinationKind.SELF_HOSTED)
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.setConsent(DiagnosticsConsentMode.ALWAYS)
        val report = fixture.reports.save(
            reportCapture(selfHosted, "destination-race"),
        )

        fixture.coordinator.setDestination(DiagnosticsDestinationKind.HOSTED)
        val decision = fixture.coordinator.uploadAutomatically(report.id)

        assertEquals(DiagnosticsUploadDecision.KeptIdentityChanged, decision)
        assertEquals(0, uploadCalls)
    }

    @Test
    fun concurrentManualAndWorkerUploadCannotPostTheSameReportTwice() = runTest {
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var uploadCalls = 0
        val selfHosted = ADULT_A.copy(destinationKind = DiagnosticsDestinationKind.SELF_HOSTED)
        val fixture = fixture(
            MutableIdentityResolver(selfHosted),
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    uploadCalls += 1
                    reports.delete(reportId)
                    DiagnosticsUploadDecision.Uploaded("ABC123")
                }
            },
        )
        fixture.settings.setDestinationKind(DiagnosticsDestinationKind.SELF_HOSTED)
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.coordinator.setConsent(DiagnosticsConsentMode.ALWAYS)
        val report = fixture.reports.save(
            reportCapture(selfHosted, "manual-worker-dedup"),
        )

        val manual = async { fixture.coordinator.upload(report.id) }
        uploadStarted.await()
        val worker = async { fixture.coordinator.uploadAutomatically(report.id) }
        runCurrent()
        releaseUpload.complete(Unit)

        assertEquals(DiagnosticsUploadDecision.Uploaded("ABC123"), manual.await())
        assertEquals(DiagnosticsUploadDecision.KeptInvalid, worker.await())
        assertEquals(1, uploadCalls)
    }

    @Test
    fun offlineStatePreservesTheCachedConsentChoice() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val fixture = fixture(
            identity,
            DefaultIdentityTransitionBarrier(),
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        fixture.coordinator.setConsent(DiagnosticsConsentMode.ALWAYS)
        identity.current = null
        fixture.coordinator.refresh()
        assertEquals(DiagnosticsConsentMode.ALWAYS, fixture.coordinator.state.value.consent)

        identity.current = ADULT_A
        fixture.coordinator.refresh()
        fixture.coordinator.setConsent(DiagnosticsConsentMode.NEVER)
        identity.current = null
        fixture.coordinator.refresh()
        assertEquals(DiagnosticsConsentMode.NEVER, fixture.coordinator.state.value.consent)
    }

    @Test
    fun signOutPurgesTheOldBindingAfterTheSynchronousGateCloses() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.evidence.add(ADULT_A.binding)

        transitions.changing(IdentityTransitionKind.SIGN_OUT) {
            assertTrue(capture.gateClosed)
            assertFalse(ADULT_A.binding in fixture.evidence)
            assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)
            identity.current = null
        }
        fixture.coordinator.refresh()

        assertFalse(ADULT_A.binding in fixture.evidence)
        assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)
    }

    @Test
    fun failedSynchronousSignOutPurgeAbortsMutationAndRetriesAfterCoordinatorReconstruction() = runTest {
        val hosted = hostedContext().copy(localServerId = "server-a", ownershipGeneration = 0)
        val identity = MutableIdentityResolver(hosted)
        val transitions = DefaultIdentityTransitionBarrier()
        var failNextPurge = true
        val fixture = fixture(
            identity,
            transitions,
            RecordingCaptureController(),
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            purgeFailure = {
                if (failNextPurge) {
                    failNextPurge = false
                    IllegalStateException("injected purge failure")
                } else {
                    null
                }
            },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.evidence += hosted.binding
        val report = fixture.reports.save(reportCapture(hosted, "failed-sign-out"))
        fixture.reports.markHostedProcessing(report.id, "REMOTE1")
        var mutationRan = false

        assertFailsWith<IllegalStateException> {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
                identity.current = null
            }
        }

        assertFalse(mutationRan)
        assertEquals(0, transitions.generation.value)
        assertNotNull(fixture.reports.load(report.id))
        assertTrue(hosted.binding in fixture.settings.bindingsForLocalServer("server-a"))
        assertNull(fixture.settings.cachedContext(), "settings deletion may precede the failed evidence purge")

        val reconstructedTransitions = DefaultIdentityTransitionBarrier()
        val reconstructed = DefaultDiagnosticsCoordinator(
            scope = backgroundScope,
            actorDispatcher = StandardTestDispatcher(testScheduler),
            identity = identity,
            identityTransitions = reconstructedTransitions,
            settings = fixture.settings,
            reports = fixture.reports,
            capture = RecordingCaptureController(),
            uploader = DiagnosticsUploader { DiagnosticsUploadDecision.KeptUnavailable },
            uploadScheduler = DiagnosticsUploadScheduler { },
        )
        reconstructed.start()
        reconstructed.refresh()
        reconstructedTransitions.changing(IdentityTransitionKind.SIGN_OUT) {
            assertNull(fixture.reports.load(report.id))
            assertEquals(listOf(report.id), fixture.reports.hostedDeletionIntents())
            identity.current = null
        }

        assertEquals(1, reconstructedTransitions.generation.value)
        assertFalse(hosted.binding in fixture.evidence)
        assertTrue(fixture.settings.bindingsForLocalServer("server-a").isEmpty())
    }

    @Test
    fun removingInactiveServerUsesTheDurableBindingIndexWithoutPurgingActiveEvidence() = runTest {
        val activeA = hostedContext().copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-a"),
            localServerId = "server-a",
            ownershipGeneration = 0,
        )
        val inactiveB = activeA.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-b"),
            localServerId = "server-b",
        )
        val identity = MutableIdentityResolver(activeA)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        // Persist both scopes before constructing the coordinator mirror, as after
        // a process restart only this index can identify an inactive server's
        // one-way hosted binding.
        fixture.settings.cacheContext(inactiveB)
        fixture.settings.cacheContext(activeA)
        fixture.evidence += setOf(activeA.binding, inactiveB.binding)
        val activeReport = fixture.reports.save(reportCapture(activeA, "active-a"))
        fixture.reports.markHostedProcessing(activeReport.id, "ACTIVE1")
        val pendingB = fixture.reports.save(reportCapture(inactiveB, "pending-b"))
        fixture.reports.markHostedProcessing(pendingB.id, "REMOTE2")
        val readyB = fixture.reports.save(reportCapture(inactiveB, "ready-b"))
        fixture.reports.recordHostedReadyAndDelete(readyB.id, readyB.binding)
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        transitions.changing(
            kind = IdentityTransitionKind.SERVER_REMOVE,
            target = {
                IdentityTransitionTarget(
                    serverId = "server-b",
                    affectsCurrentIdentity = false,
                )
            },
        ) {
            assertNotNull(fixture.reports.load(activeReport.id))
            assertNull(fixture.reports.load(pendingB.id))
            assertEquals(setOf(pendingB.id, readyB.id), fixture.reports.hostedDeletionIntents().toSet())
            assertTrue(activeA.binding in fixture.evidence)
            assertFalse(inactiveB.binding in fixture.evidence)
            assertEquals(0, capture.currentEvidencePurgeCount, "inactive removal must not clear active live evidence")
        }

        assertEquals(listOf(inactiveB.binding), fixture.purgedBindings)
        assertTrue(fixture.settings.bindingsForLocalServer("server-b").isEmpty())
        assertEquals(listOf(activeA.binding), fixture.settings.bindingsForLocalServer("server-a"))
    }

    @Test
    fun accountReplacementPurgesPendingProcessingAndReadyAuthorityBeforeMutation() = runTest {
        val hosted = hostedContext().copy(localServerId = "server-a")
        val transitions = DefaultIdentityTransitionBarrier()
        val fixture = fixture(
            MutableIdentityResolver(hosted),
            transitions,
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val pending = fixture.reports.save(reportCapture(hosted, "replace-pending"))
        val processing = fixture.reports.save(reportCapture(hosted, "replace-processing"))
        fixture.reports.markHostedProcessing(processing.id, "ABC123")
        val ready = fixture.reports.save(reportCapture(hosted, "replace-ready"))
        fixture.reports.recordHostedReadyAndDelete(ready.id, ready.binding)
        var mutationRan = false

        transitions.changing(
            kind = IdentityTransitionKind.ACCOUNT_REPLACE,
            target = { IdentityTransitionTarget(serverId = "server-a") },
        ) {
            assertNull(fixture.reports.load(pending.id))
            assertNull(fixture.reports.load(processing.id))
            assertNull(fixture.reports.load(ready.id))
            assertEquals(
                listOf(pending.id, processing.id, ready.id).sorted(),
                fixture.reports.hostedDeletionIntents(),
            )
            mutationRan = true
        }

        assertTrue(mutationRan)
        assertEquals(listOf(hosted.binding), fixture.purgedBindings)
        assertTrue(fixture.settings.bindingsForLocalServer("server-a").isEmpty())
    }

    @Test
    fun immediateSwitchThenSignOutPurgesTheNewServerWhileTheActorStillOwnsOldWork() = runTest {
        val serverA = hostedContext().copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-a"),
            localServerId = "server-a",
            ownershipGeneration = 0,
        )
        val serverB = serverA.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-b"),
            localServerId = "server-b",
        )
        val identity = MutableIdentityResolver(serverA)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    checkNotNull(reports.load(reportId))
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    DiagnosticsUploadDecision.KeptUnavailable
                }
            },
        )
        fixture.settings.cacheContext(serverB)
        fixture.settings.cacheContext(serverA)
        fixture.evidence += setOf(serverA.binding, serverB.binding)
        val oldWork = fixture.reports.save(reportCapture(serverA, "actor-held-a"))
        val pendingB = fixture.reports.save(reportCapture(serverB, "pending-b"))
        fixture.reports.markHostedProcessing(pendingB.id, "REMOTE2")
        val readyB = fixture.reports.save(reportCapture(serverB, "ready-b"))
        fixture.reports.recordHostedReadyAndDelete(readyB.id, readyB.binding)
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        val upload = async { fixture.coordinator.upload(oldWork.id) }
        uploadStarted.await()
        transitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            identity.current = serverB
        }
        capture.hasPersistentEvidence = true
        capture.gateClosed = false

        transitions.changing(
            kind = IdentityTransitionKind.SIGN_OUT,
            target = { IdentityTransitionTarget(serverId = "server-b") },
        ) {
            assertNotNull(fixture.reports.load(oldWork.id))
            assertNull(fixture.reports.load(pendingB.id))
            assertEquals(setOf(pendingB.id, readyB.id), fixture.reports.hostedDeletionIntents().toSet())
            assertTrue(serverA.binding in fixture.evidence)
            assertFalse(serverB.binding in fixture.evidence)
            assertFalse(capture.hasPersistentEvidence)
            identity.current = null
        }

        assertEquals(2, capture.currentEvidencePurgeCount)
        assertEquals(listOf(serverB.binding), fixture.purgedBindings)
        assertEquals(listOf(serverA.binding), fixture.settings.bindingsForLocalServer("server-a"))
        assertTrue(fixture.settings.bindingsForLocalServer("server-b").isEmpty())
        releaseUpload.complete(Unit)
        assertEquals(DiagnosticsUploadDecision.KeptUnavailable, upload.await())
    }

    @Test
    fun immediateSwitchThenRemovingTheOldServerLeavesNewServerLiveEvidenceUntouched() = runTest {
        val serverA = hostedContext().copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-a"),
            localServerId = "server-a",
            ownershipGeneration = 0,
        )
        val serverB = serverA.copy(
            binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "hosted-account-b"),
            localServerId = "server-b",
        )
        val identity = MutableIdentityResolver(serverA)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val fixture = fixture(
            identity,
            transitions,
            capture,
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
            uploaderFactory = { reports ->
                DiagnosticsUploader { reportId ->
                    checkNotNull(reports.load(reportId))
                    uploadStarted.complete(Unit)
                    releaseUpload.await()
                    DiagnosticsUploadDecision.KeptUnavailable
                }
            },
        )
        fixture.settings.cacheContext(serverB)
        fixture.settings.cacheContext(serverA)
        fixture.evidence += setOf(serverA.binding, serverB.binding)
        val heldA = fixture.reports.save(reportCapture(serverA, "held-a"))
        fixture.reports.markHostedProcessing(heldA.id, "REMOTE1")
        val pendingB = fixture.reports.save(reportCapture(serverB, "pending-b"))
        fixture.coordinator.start()
        fixture.coordinator.refresh()

        val upload = async { fixture.coordinator.upload(heldA.id) }
        uploadStarted.await()
        transitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            identity.current = serverB
        }
        capture.hasPersistentEvidence = true
        capture.gateClosed = false
        val livePurgeCountAfterSwitch = capture.currentEvidencePurgeCount

        transitions.changing(
            kind = IdentityTransitionKind.SERVER_REMOVE,
            target = {
                IdentityTransitionTarget(
                    serverId = "server-a",
                    affectsCurrentIdentity = false,
                )
            },
        ) {
            assertNull(fixture.reports.load(heldA.id))
            assertNotNull(fixture.reports.load(pendingB.id))
            assertEquals(listOf(heldA.id), fixture.reports.hostedDeletionIntents())
            assertTrue(capture.hasPersistentEvidence)
            assertEquals(livePurgeCountAfterSwitch, capture.currentEvidencePurgeCount)
            assertFalse(serverA.binding in fixture.evidence)
            assertTrue(serverB.binding in fixture.evidence)
        }

        assertEquals(listOf(serverA.binding), fixture.purgedBindings)
        assertTrue(fixture.settings.bindingsForLocalServer("server-a").isEmpty())
        assertEquals(listOf(serverB.binding), fixture.settings.bindingsForLocalServer("server-b"))
        releaseUpload.complete(Unit)
        assertEquals(DiagnosticsUploadDecision.KeptUnavailable, upload.await())
    }

    @Test
    fun failedDurableBindingRegistrationKeepsEvidenceClosedAndRecoversWithoutKillingTheActor() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController().apply {
            hasPersistentEvidence = true
            purgeFailuresRemaining = 3
        }
        val runtime = RecordingRuntimePublisher()
        lateinit var failingStore: FailingUpdateDataStore
        var incidentCalls = 0
        val fixture = fixture(
            identity = identity,
            transitions = transitions,
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            dataStoreDecorator = { delegate ->
                FailingUpdateDataStore(delegate).also { failingStore = it }
            },
            runtimePublisher = runtime,
            incidentCollectorFactory = {
                DiagnosticsIncidentCollector { _, _ ->
                    incidentCalls += 1
                    emptyList()
                }
            },
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE, fixture.coordinator.state.value.availability)
        assertEquals(0, runtime.publishCalls)
        assertEquals(0, incidentCalls)
        assertFalse(capture.debugLoggingEnabled)
        assertFalse(capture.persistentBreadcrumbsEnabled)
        assertTrue(capture.gateClosed)
        assertTrue(capture.hasPersistentEvidence, "the injected first purge failed")
        assertTrue(fixture.settings.bindingsForLocalServer(ADULT_A.localServerId!!).isEmpty())
        assertNull(fixture.coordinator.captureNow())
        assertEquals(0, capture.captureNowCalls)

        capture.purgeFailuresRemaining = 0
        failingStore.failUpdates = false
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
        assertEquals(1, runtime.publishCalls)
        assertEquals(1, incidentCalls)
        assertTrue(capture.hasPersistentEvidence)
        assertEquals(4, capture.currentEvidencePurgeCount)
        assertEquals(listOf(ADULT_A.binding), fixture.settings.bindingsForLocalServer(ADULT_A.localServerId!!))
    }

    @Test
    fun rawMarkerReconciliationRunsBeforeIdentityResolutionAndRecoversWithoutKillingTheActor() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val capture = RecordingCaptureController()
        var reconciliationFailuresRemaining = 2
        var incidentCalls = 0
        val fixture = fixture(
            identity = identity,
            transitions = DefaultIdentityTransitionBarrier(),
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            incidentCollectorFactory = {
                DiagnosticsIncidentCollector { _, _ ->
                    incidentCalls += 1
                    emptyList()
                }
            },
            storedEvidenceReconciler = DiagnosticsStoredEvidenceReconciler {
                if (reconciliationFailuresRemaining > 0) {
                    reconciliationFailuresRemaining -= 1
                    error("marker directory unavailable")
                }
            },
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE, fixture.coordinator.state.value.availability)
        assertEquals(0, identity.resolveCalls)
        assertEquals(0, incidentCalls)
        assertTrue(capture.gateClosed)
        assertFalse(capture.persistentBreadcrumbsEnabled)

        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
        assertEquals(1, identity.resolveCalls)
        assertEquals(1, incidentCalls)
    }

    @Test
    fun incidentMarkerCleanupFailureKeepsEvidenceClosedAndActorCanRetry() = runTest {
        val capture = RecordingCaptureController()
        var failuresRemaining = 2
        val fixture = fixture(
            identity = MutableIdentityResolver(ADULT_A),
            transitions = DefaultIdentityTransitionBarrier(),
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            incidentCollectorFactory = {
                DiagnosticsIncidentCollector { _, _ ->
                    if (failuresRemaining > 0) {
                        failuresRemaining -= 1
                        error("marker delete failed")
                    }
                    emptyList()
                }
            },
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE, fixture.coordinator.state.value.availability)
        assertTrue(capture.gateClosed)
        assertFalse(capture.persistentBreadcrumbsEnabled)

        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
        assertTrue(capture.persistentBreadcrumbsEnabled)
    }

    @Test
    fun detachedRawGenerationCleanupFailureKeepsActorClosedUntilRetrySucceeds() = runTest {
        val capture = RecordingCaptureController().apply {
            hasPersistentEvidence = true
            // start() enqueues one refresh before the explicit request that
            // drives the background actor in this deterministic fixture.
            reconciliationFailuresRemaining = 2
            purgeFailuresRemaining = 2
        }
        val fixture = fixture(
            identity = MutableIdentityResolver(ADULT_A),
            transitions = DefaultIdentityTransitionBarrier(),
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE, fixture.coordinator.state.value.availability)
        assertTrue(capture.gateClosed)
        assertTrue(capture.hasPersistentEvidence)
        assertEquals(2, capture.currentEvidencePurgeCount)

        fixture.coordinator.refresh()

        assertEquals(DiagnosticsAvailabilityUi.AVAILABLE, fixture.coordinator.state.value.availability)
        assertTrue(capture.persistentBreadcrumbsEnabled)
        assertEquals(3, capture.currentEvidencePurgeCount)
    }

    @Test
    fun startupCleanupFailureStillInstallsGateAndBlocksAccountReplacementUntilRecovery() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        var failDeletion = true
        lateinit var staging: File
        val fixture = fixture(
            identity = identity,
            transitions = transitions,
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = UnconfinedTestDispatcher(testScheduler),
            reportsFactory = { files ->
                val root = files.resolve("client-diagnostics/pending")
                check(root.mkdirs())
                staging = root.resolve(".staging-${"f".repeat(32)}")
                check(staging.mkdirs())
                staging.resolve("logs.jsonl").writeText("raw startup evidence")
                FilePendingReportStore(
                    noBackupFilesDir = files,
                    nowMs = { CAPTURED_AT },
                    directorySync = {},
                    atomicRename = ::testAtomicRename,
                    deleteRecursively = { file ->
                        if (failDeletion && file == staging) false else file.deleteRecursively()
                    },
                )
            },
        )

        fixture.coordinator.start()
        fixture.coordinator.refresh()
        assertEquals(DiagnosticsAvailabilityUi.STORAGE_UNAVAILABLE, fixture.coordinator.state.value.availability)
        assertTrue(staging.exists())
        var mutationRan = false

        assertFailsWith<IllegalStateException> {
            transitions.changing(
                kind = IdentityTransitionKind.ACCOUNT_REPLACE,
                target = { IdentityTransitionTarget(serverId = ADULT_A.localServerId) },
            ) {
                mutationRan = true
            }
        }
        assertFalse(mutationRan)

        failDeletion = false
        transitions.changing(
            kind = IdentityTransitionKind.ACCOUNT_REPLACE,
            target = { IdentityTransitionTarget(serverId = ADULT_A.localServerId) },
        ) {
            mutationRan = true
        }

        assertTrue(mutationRan)
        assertFalse(staging.exists())
    }

    @Test
    fun identityMutationWaitsForRefreshEvidenceCommitThenClosesAndPurgesIt() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val runtime = RecordingRuntimePublisher()
        val fixture = fixture(
            identity = identity,
            transitions = transitions,
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = StandardTestDispatcher(testScheduler),
            runtimePublisher = runtime,
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        runtime.pauseNextPublish()

        val refresh = async { fixture.coordinator.refresh() }
        checkNotNull(runtime.publishStarted).await()
        var mutationRan = false
        val transition = async {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
                identity.current = null
            }
        }
        runCurrent()

        assertFalse(mutationRan, "the generation guard must hold until publish finishes")
        checkNotNull(runtime.releasePublish).complete(Unit)
        refresh.await()
        transition.await()
        runCurrent()

        assertTrue(mutationRan)
        assertFalse(runtime.live)
        assertFalse(capture.hasPersistentEvidence)
        assertFalse(capture.debugLoggingEnabled)
        assertFalse(capture.persistentBreadcrumbsEnabled)
        assertTrue(fixture.settings.bindingsForLocalServer(ADULT_A.localServerId!!).isEmpty())
    }

    @Test
    fun identityMutationWaitsForIncidentPersistenceThenPurgesTheNewReport() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val incidentStarted = CompletableDeferred<Unit>()
        val releaseIncident = CompletableDeferred<Unit>()
        var pauseIncident = false
        var savedIncident: PendingReport? = null
        val fixture = fixture(
            identity = identity,
            transitions = transitions,
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = StandardTestDispatcher(testScheduler),
            incidentCollectorFactory = { reports ->
                DiagnosticsIncidentCollector { context, _ ->
                    if (!pauseIncident) return@DiagnosticsIncidentCollector emptyList()
                    incidentStarted.complete(Unit)
                    releaseIncident.await()
                    listOf(reports.save(reportCapture(context, "incident-race")).also { savedIncident = it })
                }
            },
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        pauseIncident = true

        val refresh = async { fixture.coordinator.refresh() }
        incidentStarted.await()
        var mutationRan = false
        val transition = async {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
                identity.current = null
            }
        }
        runCurrent()
        assertFalse(mutationRan)

        releaseIncident.complete(Unit)
        refresh.await()
        transition.await()
        runCurrent()

        assertTrue(mutationRan)
        assertNotNull(savedIncident)
        assertNull(fixture.reports.load(checkNotNull(savedIncident).id))
        assertTrue(fixture.reports.list(ADULT_A.binding).isEmpty())
    }

    @Test
    fun identityMutationWaitsForOneShotCaptureThenPurgesTheNewReport() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val capture = RecordingCaptureController()
        val fixture = fixture(
            identity = identity,
            transitions = transitions,
            capture = capture,
            scope = backgroundScope,
            actorDispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        val captureStarted = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        var savedCapture: PendingReport? = null
        capture.captureNowAction = { context ->
            captureStarted.complete(Unit)
            releaseCapture.await()
            fixture.reports.save(reportCapture(context, "capture-race")).also { savedCapture = it }
        }

        val captureResult = async { fixture.coordinator.captureNow() }
        captureStarted.await()
        var mutationRan = false
        val transition = async {
            transitions.changing(IdentityTransitionKind.SIGN_OUT) {
                mutationRan = true
                identity.current = null
            }
        }
        runCurrent()
        assertFalse(mutationRan)

        releaseCapture.complete(Unit)
        transition.await()
        captureResult.await()
        runCurrent()

        assertTrue(mutationRan)
        assertNotNull(savedCapture)
        assertNull(fixture.reports.load(checkNotNull(savedCapture).id))
        assertTrue(fixture.reports.list(ADULT_A.binding).isEmpty())
    }

    @Test
    fun offlineSignOutPurgesTheCachedBinding() = runTest {
        val identity = MutableIdentityResolver(ADULT_A)
        val transitions = DefaultIdentityTransitionBarrier()
        val fixture = fixture(
            identity,
            transitions,
            RecordingCaptureController(),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        fixture.coordinator.start()
        fixture.coordinator.refresh()
        fixture.evidence.add(ADULT_A.binding)

        identity.current = null
        fixture.coordinator.refresh()
        transitions.changing(IdentityTransitionKind.SIGN_OUT) { }
        fixture.coordinator.refresh()

        assertFalse(ADULT_A.binding in fixture.evidence)
        assertEquals(listOf(ADULT_A.binding), fixture.purgedBindings)
    }

    @Test
    fun oneShotCaptureBuildsAProfileBoundManualReportFromTheCurrentRing() = runTest {
        val files = temporaryFolder.newFolder()
        val ring = LogRing()
        val playbackSessions = DiagnosticsPlaybackSessionTracker().apply {
            open(ADULT_A.identityKey)
            record("playback-session-1")
        }
        ring.offer("{\"cat\":\"playback\",\"msg\":\"safe\"}")
        ring.offer("{\"cat\":\"network\",\"msg\":\"safe\"}")
        val store = FilePendingReportStore(
            files,
            nowMs = { 20L },
            directorySync = {},
            atomicRename = ::testAtomicRename,
        )
        val controller = FileDiagnosticsCaptureController(
            logBuffer = ring,
            fileLogger = DiagnosticsFileLogger(files, UnconfinedTestDispatcher(testScheduler), directorySync = {}),
            reports = store,
            deviceSnapshots = DeviceSnapshotCollector(StableDeviceProbe(), nowRfc3339 = { "2026-07-22T00:00:00Z" }),
            deviceSnapshotCache = DeviceSnapshotCache(),
            environment = ExitReportEnvironment(
                appVersion = "1.0",
                appBuild = "1",
                platform = DiagnosticsPlatform.ANDROID_TV,
                osVersion = "36",
                deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
            ),
            playbackSessions = playbackSessions,
            nowMs = { 20L },
            sessionIdFactory = { "manual-session" },
        )

        val report = controller.captureNow(ADULT_A)

        requireNotNull(report)
        assertEquals(DiagnosticsReportType.MANUAL, report.manifest.report.type)
        assertEquals("adult-a", report.binding.profileId)
        assertEquals("server-1", report.manifest.destination.serverInstanceId)
        assertTrue(report.directory.resolve("device.json").isFile)
        assertTrue(report.directory.resolve("logs.jsonl").readText().contains("safe"))
        assertEquals(2, report.manifest.logSummary.lines)
        assertTrue(report.manifest.logSummary.bytesGzip > 0)
        assertEquals(
            listOf(DiagnosticsLogCategory.PLAYBACK, DiagnosticsLogCategory.NETWORK),
            report.manifest.logSummary.categories,
        )
        assertEquals(listOf("playback-session-1"), report.manifest.playbackSessionIds)

        controller.closeGate()
        assertEquals(listOf("playback-session-1"), playbackSessions.snapshot())
    }

    private fun fixture(
        identity: MutableIdentityResolver,
        transitions: DefaultIdentityTransitionBarrier,
        capture: RecordingCaptureController,
        scope: CoroutineScope,
        actorDispatcher: CoroutineDispatcher,
        uploaderFactory: (PendingReportStore) -> DiagnosticsUploader = {
            DiagnosticsUploader { DiagnosticsUploadDecision.KeptUnavailable }
        },
        uploadScheduler: DiagnosticsUploadScheduler = DiagnosticsUploadScheduler { },
        hostedDeletionScheduler: HostedDiagnosticsDeletionScheduler = HostedDiagnosticsDeletionScheduler.None,
        hostedReportDeleter: HostedDiagnosticsReportDeleter = HostedDiagnosticsReportDeleter.None,
        purgeFailure: (() -> Throwable?)? = null,
        dataStoreDecorator: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
        runtimePublisher: DiagnosticsRuntimePublisher = DiagnosticsRuntimePublisher.None,
        incidentCollectorFactory: (PendingReportStore) -> DiagnosticsIncidentCollector = {
            DiagnosticsIncidentCollector { _, _ -> emptyList() }
        },
        storedEvidenceReconciler: DiagnosticsStoredEvidenceReconciler = DiagnosticsStoredEvidenceReconciler.None,
        reportsFactory: (File) -> FilePendingReportStore = { files ->
            FilePendingReportStore(
                files,
                nowMs = { CAPTURED_AT },
                directorySync = {},
                atomicRename = ::testAtomicRename,
            )
        },
    ): Fixture {
        val files = temporaryFolder.newFolder()
        val purgedBindings = mutableListOf<DiagnosticsBinding>()
        val evidence = mutableSetOf<DiagnosticsBinding>()
        val reports = reportsFactory(files)
        val dataStore = dataStoreDecorator(
            PreferenceDataStoreFactory.create {
                File(files, "diagnostics-${System.nanoTime()}.preferences_pb")
            },
        )
        val settings = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = DiagnosticsBindingPurger { binding, includeLiveCapture ->
                purgeFailure?.invoke()?.let { throw it }
                purgedBindings += binding
                evidence -= binding
                if (includeLiveCapture) capture.purgeCurrentEvidence()
                reports.purge(binding)
            },
        )
        val coordinator = DefaultDiagnosticsCoordinator(
            scope = scope,
            actorDispatcher = actorDispatcher,
            identity = identity,
            identityTransitions = transitions,
            settings = settings,
            reports = reports,
            capture = capture,
            uploader = uploaderFactory(reports),
            uploadScheduler = uploadScheduler,
            hostedDeletionScheduler = hostedDeletionScheduler,
            hostedReportDeleter = hostedReportDeleter,
            runtimePublisher = runtimePublisher,
            incidentCollector = incidentCollectorFactory(reports),
            storedEvidenceReconciler = storedEvidenceReconciler,
        )
        return Fixture(coordinator, settings, reports, evidence, purgedBindings, dataStore)
    }

    private fun hostedContext() = ADULT_A.copy(
        binding = DiagnosticsBinding(HOSTED_DIAGNOSTICS_COLLECTOR_ID, "anonymous-hosted-device"),
        profileId = null,
        sourceProfileId = ADULT_A.profileId,
        destinationKind = DiagnosticsDestinationKind.HOSTED,
        retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
    )

    private fun reportCapture(context: DiagnosticsCaptureContext, fingerprint: String) = PendingReportCapture(
        binding = PendingReportBinding(
            serverInstanceId = context.binding.serverInstanceId,
            accountUserId = context.binding.accountUserId,
            profileId = context.profileId,
            ownershipGeneration = context.ownershipGeneration,
            destinationKind = context.destinationKind,
        ),
        manifest = DiagnosticsManifest(
            schemaVersion = 1,
            report = DiagnosticsReport(
                type = DiagnosticsReportType.MANUAL,
                capturedAt = "2026-07-22T00:00:00Z",
                captureSessionId = "capture-$fingerprint",
                appVersion = "1.0",
                appBuild = "1",
                platform = DiagnosticsPlatform.ANDROID_TV,
                osVersion = "36",
                profileId = context.profileId,
            ),
            destination = DiagnosticsDestination(context.binding.serverInstanceId),
            consent = DiagnosticsConsent(ManifestConsentMode.MANUAL, context.noticeVersion),
            deviceSummary = DiagnosticsDeviceSummary("NVIDIA", "Shield", "Android 36", "tv"),
            playbackSessionIds = emptyList(),
            logSummary = DiagnosticsLogSummary(0, 0, 0, listOf(DiagnosticsLogCategory.OTHER), false),
            archive = DiagnosticsArchive(listOf("manifest.json", "device.json"), 0, 0, "0".repeat(64)),
        ),
        artifacts = mapOf("device.json" to "{}".encodeToByteArray()),
        fingerprint = fingerprint,
        capturedAtEpochMs = CAPTURED_AT,
    )

    private class MutableIdentityResolver(
        var current: DiagnosticsCaptureContext?,
    ) : DiagnosticsIdentityResolver {
        var trustCachedIdentity = true
        var resolveCalls = 0
        var captureAttestationAllowed = true
        var captureAttestationCalls = 0
        override suspend fun resolve(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? {
            resolveCalls += 1
            return current
        }
        override suspend fun resolveForCapture(requirePersistentCapture: Boolean): DiagnosticsCaptureContext? {
            captureAttestationCalls += 1
            return if (captureAttestationAllowed) current else null
        }
        override suspend fun matchesCachedIdentity(cached: CachedDiagnosticsContext): Boolean = trustCachedIdentity
    }

    private class RecordingCaptureController : DiagnosticsCaptureController {
        var gateClosed = false
        var hasPersistentEvidence = false
        var debugLoggingEnabled = false
        var persistentBreadcrumbsEnabled = false
        val cancelled = mutableListOf<Long>()
        var currentEvidencePurgeCount = 0
        var purgeFailuresRemaining = 0
        var reconciliationFailuresRemaining = 0
        var captureNowCalls = 0
        var captureNowAction: suspend (DiagnosticsCaptureContext) -> PendingReport? = { null }
        private var nextGeneration = 0L

        override fun closeGate() {
            gateClosed = true
        }

        override suspend fun start(context: DiagnosticsCaptureContext): ActiveDiagnosticsCapture {
            gateClosed = false
            hasPersistentEvidence = true
            return ActiveDiagnosticsCapture(++nextGeneration, context.identityKey, 10L)
        }

        override suspend fun stop(
            active: ActiveDiagnosticsCapture,
            context: DiagnosticsCaptureContext,
        ): PendingReport? {
            hasPersistentEvidence = false
            return null
        }

        override suspend fun cancel(active: ActiveDiagnosticsCapture) {
            cancelled += active.generation
            hasPersistentEvidence = false
        }

        override suspend fun captureNow(context: DiagnosticsCaptureContext): PendingReport? {
            captureNowCalls += 1
            return captureNowAction(context)
        }

        override suspend fun setDebugLogging(context: DiagnosticsCaptureContext?, enabled: Boolean) {
            debugLoggingEnabled = context != null && enabled
            if (debugLoggingEnabled) hasPersistentEvidence = true
        }

        override suspend fun setPersistentBreadcrumbs(context: DiagnosticsCaptureContext?, enabled: Boolean) {
            persistentBreadcrumbsEnabled = context != null && enabled
            if (persistentBreadcrumbsEnabled) hasPersistentEvidence = true
        }

        override suspend fun reconcileStoredEvidence() {
            if (reconciliationFailuresRemaining > 0) {
                reconciliationFailuresRemaining -= 1
                throw IllegalStateException("injected detached evidence cleanup failure")
            }
        }

        override suspend fun purgeCurrentEvidence() {
            currentEvidencePurgeCount += 1
            if (purgeFailuresRemaining > 0) {
                purgeFailuresRemaining -= 1
                throw IllegalStateException("injected live evidence purge failure")
            }
            hasPersistentEvidence = false
            debugLoggingEnabled = false
            persistentBreadcrumbsEnabled = false
        }
    }

    private class RecordingRuntimePublisher : DiagnosticsRuntimePublisher {
        var live = false
        var publishCalls = 0
        var closeCalls = 0
        var publishStarted: CompletableDeferred<Unit>? = null
        var releasePublish: CompletableDeferred<Unit>? = null

        override fun closeGate() {
            closeCalls += 1
            live = false
        }

        override suspend fun publish(context: DiagnosticsCaptureContext) {
            publishCalls += 1
            publishStarted?.complete(Unit)
            releasePublish?.await()
            live = true
        }

        fun pauseNextPublish() {
            publishStarted = CompletableDeferred()
            releasePublish = CompletableDeferred()
        }
    }

    private class FailingUpdateDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var failUpdates = true

        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failUpdates) throw IllegalStateException("injected DataStore update failure")
            return delegate.updateData(transform)
        }
    }

    private class RecordingHostedReportDeleter(
        var result: Boolean,
    ) : HostedDiagnosticsReportDeleter {
        val reportIds = mutableListOf<String>()

        override suspend fun delete(reportId: String): Boolean {
            reportIds += reportId
            return result
        }
    }

    private class StableDeviceProbe : DiagnosticsDeviceProbe {
        override fun identity() = DiagnosticsIdentitySnapshot(
            manufacturer = "NVIDIA",
            model = "Shield",
            device = "foster",
            osRelease = "16",
            sdkInt = 36,
            formFactor = "tv",
            buildFingerprintHash = "0".repeat(32),
        )

        override fun display(): DiagnosticsDisplaySnapshot? = null
        override fun audio(): DiagnosticsAudioSnapshot? = null
        override fun codecs(): List<DiagnosticsCodecSnapshot>? = null
        override fun network(): DiagnosticsNetworkSnapshot? = null
    }

    private data class Fixture(
        val coordinator: DiagnosticsCoordinator,
        val settings: DiagnosticsSettingsStore,
        val reports: PendingReportStore,
        val evidence: MutableSet<DiagnosticsBinding>,
        val purgedBindings: MutableList<DiagnosticsBinding>,
        val dataStore: DataStore<Preferences>,
    )

    private companion object {
        val ADULT_A = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "adult-a",
            profileEligible = true,
            noticeVersion = 2,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 0,
            localServerId = "local-server-1",
        )
        val ADULT_B = ADULT_A.copy(profileId = "adult-b", ownershipGeneration = 1)
        const val CAPTURED_AT = 1_700_000_000_000L
    }
}
