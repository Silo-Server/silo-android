package org.siloserver.silo.common.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsPlaybackSessionTrackerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun trackerDeduplicatesBoundsAndKeepsNewestSessions() {
        val tracker = DiagnosticsPlaybackSessionTracker(maxSessions = 3, maxIdChars = 8)
        tracker.open(identityKey("profile-1", 1))
        val recording = tracker.recording()

        recording.record("one-long-session")
        recording.record("two-long-session")
        recording.record("three-long-session")
        recording.record("two-long-session")
        recording.record("four-long-session")
        recording.record(" ")

        assertEquals(listOf("three-lo", "two-long", "four-lon"), tracker.snapshot())
        assertTrue(tracker.snapshot().all { it.length <= 8 })
    }

    @Test
    fun clearDropsAllCorrelationState() {
        val tracker = DiagnosticsPlaybackSessionTracker()
        tracker.open(identityKey("profile-1", 1))
        tracker.recording().record("session-1")

        tracker.close()

        assertEquals(emptyList(), tracker.snapshot())
    }

    @Test
    fun staleRecordingCannotCrossAClosedAndReopenedIdentityGate() {
        val tracker = DiagnosticsPlaybackSessionTracker()
        tracker.open(identityKey("profile-1", 1))
        val stale = tracker.recording()

        tracker.close()
        tracker.open(identityKey("profile-2", 2))
        stale.record("old-profile-session")
        tracker.recording().record("new-profile-session")

        assertEquals(listOf("new-profile-session"), tracker.snapshot())
    }

    @Test
    fun runtimePublisherIncludesSessionsAndPrivacyGateClearsThem() = runTest {
        val tracker = DiagnosticsPlaybackSessionTracker()
        val publisher = DefaultDiagnosticsRuntimePublisher(
            ledger = DiagnosticsRunLedger(
                temporaryFolder.newFolder(),
                directorySync = {},
                atomicRename = ::testAtomicRename,
            ),
            logBuffer = LogRing(),
            deviceSnapshots = DeviceSnapshotCollector(EmptyProbe),
            deviceSnapshotCache = DeviceSnapshotCache(),
            redactionTokens = DiagnosticsRedactionTokenProvider { _ -> emptyList() },
            playbackSessions = tracker,
            captureSessionIdFactory = { "capture-1" },
        )
        val context = DiagnosticsCaptureContext(
            binding = DiagnosticsBinding("server-1", "user-1"),
            profileId = "profile-1",
            profileEligible = true,
            noticeVersion = 1,
            status = DiagnosticsAvailabilityStatus.AVAILABLE,
            ownershipGeneration = 1,
        )

        publisher.publish(context)
        tracker.recording().record("session-1")

        assertEquals(listOf("session-1"), CrashCapture.currentSnapshotForTests().playbackSessionIds)

        publisher.closeGate()

        assertEquals(emptyList(), tracker.snapshot())
        assertEquals(emptyList(), CrashCapture.currentSnapshotForTests().playbackSessionIds)
    }

    private fun identityKey(profileId: String, generation: Long) = DiagnosticsIdentityKey(
        binding = DiagnosticsBinding("server-1", "user-1"),
        profileId = profileId,
        ownershipGeneration = generation,
    )

    private object EmptyProbe : DiagnosticsDeviceProbe {
        override fun identity() = DiagnosticsIdentitySnapshot(
            manufacturer = "Google",
            model = "Streamer",
            device = "streamer",
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
}
