package org.siloserver.silo.common.diagnostics

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import org.siloserver.silo.network.api.HostedDiagnosticsAvailability
import org.siloserver.silo.network.api.HostedDiagnosticsCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val bindingA = DiagnosticsBinding("server-a", "user-1")
    private val bindingB = DiagnosticsBinding("server-b", "user-1")

    @Test
    fun defaultsToAskAndNoticeBumpDemotesAlwaysButNotNever() = runTest {
        val store = newStore()

        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, currentNoticeVersion = 1).mode)
        assertFalse(store.debugLogging())

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 1)
        assertEquals(DiagnosticsConsentMode.ALWAYS, store.consent(bindingA, 1).mode)
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, 2).mode)

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 2)
        assertEquals(DiagnosticsConsentMode.NEVER, store.consent(bindingA, 3).mode)
    }

    @Test
    fun consentIsBindingScopedAndDebugLoggingIsDeviceScoped() = runTest {
        val store = newStore()
        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 4)
        store.setDebugLogging(enabled = true)

        assertEquals(DiagnosticsConsentMode.ALWAYS, store.consent(bindingA, 4).mode)
        assertTrue(store.debugLogging())
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingB, 4).mode)
        assertTrue(store.debugLogging())
    }

    @Test
    fun staleConsentDemotesAlwaysButPreservesNever() = runTest {
        val store = newStore()
        val handler = SettingsDiagnosticsStaleConsentHandler(store)

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 2)
        handler.demote(bindingA, noticeVersion = 2)
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, 2).mode)

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 2)
        handler.demote(bindingA, noticeVersion = 2)
        assertEquals(DiagnosticsConsentMode.NEVER, store.consent(bindingA, 2).mode)

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 3)
        handler.demote(bindingA, noticeVersion = 2)
        assertEquals(DiagnosticsConsentMode.ALWAYS, store.consent(bindingA, 3).mode)
    }

    @Test
    fun selectingNeverAndExplicitPurgeDeleteBindingEvidenceAndSettings() = runTest {
        val purger = RecordingBindingPurger()
        val store = newStore(purger)
        store.setDebugLogging(enabled = true)

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 1)
        assertEquals(listOf(bindingA), purger.bindings)
        assertEquals(listOf(bindingA to true), purger.calls)
        assertFalse(store.debugLogging())

        store.setConsent(bindingA, DiagnosticsConsentMode.ALWAYS, noticeVersion = 1)
        store.purgeBinding(bindingA)
        assertEquals(listOf(bindingA, bindingA), purger.bindings)
        assertEquals(listOf(bindingA to true, bindingA to true), purger.calls)
        assertEquals(DiagnosticsConsentMode.ASK, store.consent(bindingA, 1).mode)
    }

    @Test
    fun sentHistoryIsNewestFirstAndBoundedPerBinding() = runTest {
        val store = newStore(historyLimit = 3)
        repeat(5) { index -> store.recordSent(bindingA, "R-$index", sentAtEpochMs = index.toLong()) }
        store.recordSent(bindingB, "other", sentAtEpochMs = 10)

        assertEquals(listOf("R-4", "R-3", "R-2"), store.sentHistory(bindingA).map { it.shortId })
        assertEquals(listOf("other"), store.sentHistory(bindingB).map { it.shortId })
    }

    @Test
    fun lateWorkerSuccessCannotReviveHistoryAfterTurnOffWins() = runTest {
        val store = newStore()

        store.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 1)
        store.recordSent(bindingA, "late-ready", sentAtEpochMs = 10, state = "ready")

        assertTrue(store.sentHistory(bindingA).isEmpty())
    }

    @Test
    fun hostedDestinationDefaultsOnAndCapabilityCacheContainsNoCredential() = runTest {
        val store = newStore()
        val capabilities = HostedDiagnosticsCapabilities(
            status = HostedDiagnosticsAvailability.AVAILABLE,
            collectorId = HOSTED_DIAGNOSTICS_COLLECTOR_ID,
            acceptedSchemaVersions = listOf(1),
            maxBundleBytes = 10L * 1_024 * 1_024,
            maxManifestBytes = 64L * 1_024,
            retentionDays = HOSTED_DIAGNOSTICS_RETENTION_DAYS,
            consentNoticeVersion = 1,
        )

        assertEquals(DiagnosticsDestinationKind.HOSTED, store.destinationKind())
        store.setDestinationKind(DiagnosticsDestinationKind.SELF_HOSTED)
        assertEquals(DiagnosticsDestinationKind.SELF_HOSTED, store.destinationKind())
        store.cacheHostedCapabilities(capabilities)
        assertEquals(capabilities, store.hostedCapabilities())
    }

    @Test
    fun serverBindingIndexSurvivesStoreReconstructionAndPurgesOnlyTheTargetWithoutLiveCapture() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-index.preferences_pb")
        }
        val first = DiagnosticsSettingsStore(dataStore, RecordingBindingPurger())
        first.cacheContext(context(bindingB, "local-b"))
        first.cacheContext(context(bindingA, "local-a"))
        first.cacheHostedBindingOwner("local-b", "hosted-owner-b")

        val purger = RecordingBindingPurger()
        val reconstructed = DiagnosticsSettingsStore(dataStore, purger)
        assertEquals(listOf(bindingA), reconstructed.bindingsForLocalServer("local-a"))
        assertEquals(listOf(bindingB), reconstructed.bindingsForLocalServer("local-b"))

        reconstructed.purgeLocalServer("local-b")

        assertEquals(listOf(bindingB to false), purger.calls)
        assertEquals(listOf(bindingA), reconstructed.bindingsForLocalServer("local-a"))
        assertTrue(reconstructed.bindingsForLocalServer("local-b").isEmpty())
        assertNull(reconstructed.hostedBindingOwner("local-b"))
    }

    @Test
    fun failedServerPurgeRetainsTheDurableIndexForRetry() = runTest {
        val binding = bindingB
        val purger = DiagnosticsBindingPurger { _, _ -> error("injected") }
        val store = newStore(purger)
        store.cacheContext(context(binding, "local-b"))

        assertFailsWith<IllegalStateException> { store.purgeLocalServer("local-b") }

        assertEquals(listOf(binding), store.bindingsForLocalServer("local-b"))
    }

    @Test
    fun removingTheFirstUnindexedLegacyServerPurgesAllOnceAndMarksTheIndexComplete() = runTest {
        var allEvidenceCalls = 0
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-legacy-unindexed.preferences_pb")
        }
        val store = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = RecordingBindingPurger(),
            allEvidencePurger = DiagnosticsAllEvidencePurger { includeLiveCapture ->
                assertFalse(includeLiveCapture)
                allEvidenceCalls += 1
            },
        )

        store.purgeLocalServer("legacy-inactive-server")

        assertEquals(1, allEvidenceCalls)
        assertTrue(store.bindingsForLocalServer("legacy-inactive-server").isEmpty())

        store.cacheContext(context(bindingA, "local-a"))
        store.purgeLocalServer("new-server-without-diagnostics")

        assertEquals(1, allEvidenceCalls, "the legacy fallback must never erase unrelated evidence twice")
        assertEquals(listOf(bindingA), store.bindingsForLocalServer("local-a"))
    }

    @Test
    fun accountScopedPurgeNeverUsesLegacyFallbackAgainstOtherServers() = runTest {
        var allEvidenceCalls = 0
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-scoped-purge.preferences_pb")
        }
        val purger = RecordingBindingPurger()
        val store = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = purger,
            allEvidencePurger = DiagnosticsAllEvidencePurger { allEvidenceCalls += 1 },
        )
        store.cacheContext(context(bindingB, "local-b"))

        store.purgeLocalServer(
            localServerId = "local-a",
            allowLegacyAllEvidenceFallback = false,
        )

        assertEquals(0, allEvidenceCalls)
        assertTrue(purger.calls.isEmpty())
        assertEquals(listOf(bindingB), store.bindingsForLocalServer("local-b"))
    }

    @Test
    fun crashAfterNeverCommitLeavesDurableErasureForReconstructedStore() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-never-crash.preferences_pb")
        }
        val firstPurger = RecordingBindingPurger()
        val first = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = firstPurger,
            afterErasureIntentPersisted = { error("simulated process death") },
        )

        assertFailsWith<IllegalStateException> {
            first.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 1)
        }
        assertEquals(DiagnosticsConsentMode.NEVER, first.consent(bindingA, 1).mode)
        assertEquals(listOf(bindingA), first.pendingErasureBindings())
        assertTrue(firstPurger.calls.isEmpty())

        val recoveredPurger = RecordingBindingPurger()
        val recovered = DiagnosticsSettingsStore(dataStore, recoveredPurger)
        recovered.retryPendingErasures(currentBinding = bindingA)

        assertEquals(listOf(bindingA to true), recoveredPurger.calls)
        assertTrue(recovered.pendingErasureBindings().isEmpty())
        assertEquals(DiagnosticsConsentMode.NEVER, recovered.consent(bindingA, 1).mode)
    }

    @Test
    fun purgeFailureKeepsNeverErasurePendingUntilRestartRetrySucceeds() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-never-failure.preferences_pb")
        }
        val failing = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = DiagnosticsBindingPurger { _, _ -> error("disk unavailable") },
        )

        assertFailsWith<IllegalStateException> {
            failing.setConsent(bindingA, DiagnosticsConsentMode.NEVER, noticeVersion = 1)
        }
        assertEquals(DiagnosticsConsentMode.NEVER, failing.consent(bindingA, 1).mode)
        assertEquals(listOf(bindingA), failing.pendingErasureBindings())

        val recoveredPurger = RecordingBindingPurger()
        val recovered = DiagnosticsSettingsStore(dataStore, recoveredPurger)
        recovered.retryPendingErasures(currentBinding = bindingA)

        assertEquals(listOf(bindingA to true), recoveredPurger.calls)
        assertTrue(recovered.pendingErasureBindings().isEmpty())
    }

    @Test
    fun corruptIndexesFailClosedThenRepairWithoutPermanentlyBlockingConsent() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-corrupt-index.preferences_pb")
        }
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("diagnostics.erasure_pending")] = "not-json"
            preferences[stringPreferencesKey("diagnostics.binding_index")] = "not-json"
        }
        var allEvidencePurges = 0
        val store = DiagnosticsSettingsStore(
            dataStore = dataStore,
            bindingPurger = RecordingBindingPurger(),
            allEvidencePurger = DiagnosticsAllEvidencePurger { includeLiveCapture ->
                assertTrue(includeLiveCapture)
                allEvidencePurges += 1
            },
        )
        val pending = PendingReportBinding(
            serverInstanceId = bindingA.serverInstanceId,
            accountUserId = bindingA.accountUserId,
            ownershipGeneration = 1,
            destinationKind = DiagnosticsDestinationKind.HOSTED,
        )

        assertFalse(store.permitsUpload(pending, noticeVersion = 1, requireAlwaysConsent = false))
        assertTrue(store.bindingsForLocalServer("local-a").isEmpty())

        store.setConsent(bindingA, DiagnosticsConsentMode.ASK, noticeVersion = 1)

        assertEquals(1, allEvidencePurges)
        assertTrue(store.pendingErasureBindings().isEmpty())
        assertTrue(store.permitsUpload(pending, noticeVersion = 1, requireAlwaysConsent = false))
    }

    private fun context(binding: DiagnosticsBinding, localServerId: String) = DiagnosticsCaptureContext(
        binding = binding,
        profileId = "profile",
        profileEligible = true,
        noticeVersion = 1,
        status = DiagnosticsAvailabilityStatus.AVAILABLE,
        ownershipGeneration = 0,
        acceptedSchemaVersions = setOf(1),
        maxBundleBytes = 1_024,
        maxManifestBytes = 1_024,
        retentionDays = 7,
        localServerId = localServerId,
    )

    private fun newStore(
        purger: DiagnosticsBindingPurger = RecordingBindingPurger(),
        historyLimit: Int = 20,
    ): DiagnosticsSettingsStore {
        val scope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.newFile("diagnostics-${System.nanoTime()}.preferences_pb")
        }
        return DiagnosticsSettingsStore(dataStore, purger, historyLimit)
    }

    private class RecordingBindingPurger : DiagnosticsBindingPurger {
        val calls = mutableListOf<Pair<DiagnosticsBinding, Boolean>>()
        val bindings: List<DiagnosticsBinding> get() = calls.map { it.first }
        override suspend fun purge(binding: DiagnosticsBinding, includeLiveCapture: Boolean) {
            calls += binding to includeLiveCapture
        }
    }
}
