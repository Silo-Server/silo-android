package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostResumeVideoStallDetectorTest {
    @Test
    fun performsBoundedSeekThenReprepareWhenClockAdvancesWithoutFrames() {
        val detector = PostResumeVideoStallDetector(1_000, 1_000, 500, 2_000)
        detector.onMounted("session")
        detector.onFirstFrameRendered()
        detector.onIsPlayingChanged("session", false, 0, 10_000, 20)
        detector.onIsPlayingChanged("session", true, 100, 10_000, 20)

        assertNull(detector.sample("session", 500, true, true, true, 10_600, 60_000, 20))
        assertEquals(
            PostResumeVideoStallDetector.Signal.SeekBack,
            detector.sample("session", 1_100, true, true, true, 11_100, 60_000, 20),
        )
        assertEquals(
            PostResumeVideoStallDetector.Signal.Reprepare,
            detector.sample("session", 2_100, true, true, true, 12_000, 60_000, 20),
        )
        assertEquals(
            PostResumeVideoStallDetector.Signal.Failed("client_surface_recovery_v1"),
            detector.sample("session", 3_100, true, true, true, 13_000, 60_000, 20),
        )
        assertNull(detector.sample("session", 4_100, true, true, true, 14_000, 60_000, 20))
    }

    @Test
    fun frameProgressCancelsRecovery() {
        val detector = PostResumeVideoStallDetector(1_000, 1_000, 500, 2_000)
        detector.onMounted("session")
        detector.onFirstFrameRendered()
        detector.onIsPlayingChanged("session", false, 0, 10_000, 20)
        detector.onIsPlayingChanged("session", true, 100, 10_000, 20)

        assertNull(detector.sample("session", 1_100, true, true, true, 11_000, 60_000, 21))
        assertNull(detector.sample("session", 3_000, true, true, true, 13_000, 60_000, 21))
    }

    @Test
    fun ignoresStartupAndEndOfMedia() {
        val detector = PostResumeVideoStallDetector(1_000, 1_000, 500, 2_000)
        detector.onMounted("session")
        detector.onIsPlayingChanged("session", false, 0, 0, 0)
        detector.onIsPlayingChanged("session", true, 100, 0, 0)
        assertNull(detector.sample("session", 2_000, true, true, true, 1_500, 60_000, 0))

        detector.onFirstFrameRendered()
        detector.onIsPlayingChanged("session", false, 2_100, 58_000, 20)
        detector.onIsPlayingChanged("session", true, 2_200, 58_000, 20)
        assertNull(detector.sample("session", 4_000, true, true, true, 59_000, 60_000, 20))
    }

    @Test
    fun pauseDuringRecoveryDoesNotCountTowardTheStageDeadline() {
        val detector = PostResumeVideoStallDetector(1_000, 1_000, 500, 2_000)
        detector.onMounted("session")
        detector.onFirstFrameRendered()
        detector.onIsPlayingChanged("session", false, 0, 10_000, 20)
        detector.onIsPlayingChanged("session", true, 100, 10_000, 20)
        detector.onIsPlayingChanged("session", false, 500, 10_400, 20)
        detector.onIsPlayingChanged("session", true, 10_000, 10_400, 20)

        assertNull(detector.sample("session", 10_500, true, true, true, 11_000, 60_000, 20))
        assertEquals(
            PostResumeVideoStallDetector.Signal.SeekBack,
            detector.sample("session", 11_000, true, true, true, 11_500, 60_000, 20),
        )
    }
}
