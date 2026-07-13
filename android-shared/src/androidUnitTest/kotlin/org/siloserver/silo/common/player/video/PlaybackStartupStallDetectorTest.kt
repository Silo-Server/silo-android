package org.siloserver.silo.common.player.video

import org.siloserver.silo.common.player.Playability
import org.siloserver.silo.model.playback.PlayMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaybackStartupStallDetectorTest {

    @Test
    fun directStartupTriggersFallbackAfterGraceWhenPlaybackNeverAdvances() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-1",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 1_767_000,
            nowMs = 0,
        )

        assertNull(
            detector.sample(
                sessionKey = "session-1",
                nowMs = 9_999,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 1_767_000,
                bufferedPositionMs = 1_767_115,
            ),
        )

        assertEquals(
            Playability.StartupStalled(bufferedAheadMs = 115, stalledForMs = 10_001),
            detector.sample(
                sessionKey = "session-1",
                nowMs = 10_001,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 1_767_000,
                bufferedPositionMs = 1_767_115,
            ),
        )
    }

    @Test
    fun playingOrPositionProgressDisarmsStartupFallback() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-1",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 90_000,
            nowMs = 0,
        )

        assertNull(
            detector.sample(
                sessionKey = "session-1",
                nowMs = 2_000,
                playWhenReady = true,
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 91_000,
                bufferedPositionMs = 100_000,
            ),
        )

        assertNull(
            detector.sample(
                sessionKey = "session-1",
                nowMs = 20_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 91_000,
                bufferedPositionMs = 91_500,
            ),
        )
    }

    @Test
    fun nonDirectRouteStartupTriggersFallback() {
        // Non-DIRECT routes (remux/transcode/HLS) that wedge at startup now
        // trigger the fallback too (TV QA 2026-07-10: remuxes buffered forever).
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-1",
            playMethod = PlayMethod.TRANSCODE,
            startPositionMs = 0,
            nowMs = 0,
        )

        assertNotNull(
            detector.sample(
                sessionKey = "session-1",
                nowMs = 20_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 0,
            ),
        )
    }

    @Test
    fun queuedInputWithZeroOutputIsClassifiedAsDecoderHang() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 1_000)
        detector.onMounted("decoder", PlayMethod.DIRECT, 0, 0)
        val stalled = detector.sample(
            sessionKey = "decoder",
            nowMs = 1_001,
            playWhenReady = true,
            isPlaying = false,
            isBuffering = true,
            currentPositionMs = 0,
            bufferedPositionMs = 5_000,
            decoderInputBufferCount = 4,
            decoderRenderedOutputBufferCount = 0,
            decoderSkippedOutputBufferCount = 0,
            decoderDroppedBufferCount = 0,
        )
        assertEquals("decoder_no_output", stalled?.classification)
    }

    @Test
    fun noDecoderInputIsClassifiedAsTransportStall() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 1_000)
        detector.onMounted("transport", PlayMethod.DIRECT, 0, 0)
        val stalled = detector.sample(
            sessionKey = "transport",
            nowMs = 1_001,
            playWhenReady = true,
            isPlaying = false,
            isBuffering = true,
            currentPositionMs = 0,
            bufferedPositionMs = 0,
        )
        assertEquals("transport_stall", stalled?.classification)
    }

    @Test
    fun audioProgressDoesNotDisarmVideoDecoderStartupDeadline() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 1_000)
        detector.onMounted("black-video", PlayMethod.DIRECT, 0, 0)

        assertNull(
            detector.sample(
                sessionKey = "black-video",
                nowMs = 100,
                playWhenReady = true,
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 100,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 1,
            ),
        )
        val stalled = detector.sample(
            sessionKey = "black-video",
            nowMs = 1_101,
            playWhenReady = true,
            isPlaying = true,
            isBuffering = false,
            currentPositionMs = 1_101,
            bufferedPositionMs = 5_000,
            decoderInputBufferCount = 8,
        )
        assertEquals("decoder_no_output", stalled?.classification)
    }

    @Test
    fun firstFrameDisarmsIndependentDecoderDeadline() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 1_000)
        detector.onMounted("rendered", PlayMethod.DIRECT, 0, 0)
        detector.sample(
            sessionKey = "rendered",
            nowMs = 100,
            playWhenReady = true,
            isPlaying = true,
            isBuffering = false,
            currentPositionMs = 100,
            bufferedPositionMs = 5_000,
            decoderInputBufferCount = 1,
        )
        detector.onFirstFrameRendered()
        assertNull(
            detector.sample(
                sessionKey = "rendered",
                nowMs = 2_000,
                playWhenReady = true,
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 2_000,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 10,
            ),
        )
    }

    @Test
    fun pausedStartupDoesNotTriggerFallback() {
        // playWhenReady=false (user paused) never triggers, even past the grace.
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-2",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 0,
            nowMs = 0,
        )

        assertNull(
            detector.sample(
                sessionKey = "session-2",
                nowMs = 20_000,
                playWhenReady = false,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 0,
            ),
        )
    }

    @Test
    fun resumeStartsAFreshDecoderStartupGracePeriod() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 1_000)
        detector.onMounted("paused-decoder", PlayMethod.DIRECT, 0, 0)

        assertNull(
            detector.sample(
                sessionKey = "paused-decoder",
                nowMs = 100,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 1,
            ),
        )
        assertNull(
            detector.sample(
                sessionKey = "paused-decoder",
                nowMs = 900,
                playWhenReady = false,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 4,
            ),
        )
        assertNull(
            detector.sample(
                sessionKey = "paused-decoder",
                nowMs = 5_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 4,
            ),
        )
        assertNull(
            detector.sample(
                sessionKey = "paused-decoder",
                nowMs = 5_999,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 4,
            ),
        )
        assertEquals(
            "decoder_no_output",
            detector.sample(
                sessionKey = "paused-decoder",
                nowMs = 6_001,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
                decoderInputBufferCount = 4,
            )?.classification,
        )
    }

    @Test
    fun midStreamFreezeTriggersFallbackAfterStarted() {
        // Playback starts and advances, then the position freezes while
        // buffering → fires after the mid-stream grace, not before.
        val detector = PlaybackStartupStallDetector(
            startupGraceMs = 10_000,
            midStreamGraceMs = 10_000,
        )
        detector.onMounted(
            sessionKey = "session-3",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 0,
            nowMs = 0,
        )
        // Progress: playing, position advanced — latches started + re-anchors.
        assertNull(
            detector.sample(
                sessionKey = "session-3",
                nowMs = 1_000,
                playWhenReady = true,
                isPlaying = true,
                isBuffering = false,
                currentPositionMs = 2_000,
                bufferedPositionMs = 5_000,
            ),
        )
        // Frozen (buffering, no progress) but still within the grace.
        assertNull(
            detector.sample(
                sessionKey = "session-3",
                nowMs = 5_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 2_000,
                bufferedPositionMs = 2_000,
            ),
        )
        // Still frozen past the mid-stream grace → fires.
        assertNotNull(
            detector.sample(
                sessionKey = "session-3",
                nowMs = 15_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 2_000,
                bufferedPositionMs = 2_000,
            ),
        )
    }

    @Test
    fun bufferedGrowthDoesNotMaskPostFirstFrameFreeze() {
        val detector = PlaybackStartupStallDetector(
            startupGraceMs = 10_000,
            midStreamGraceMs = 1_000,
            bufferedProgressMs = 100,
        )
        detector.onMounted("session", PlayMethod.DIRECT, 0, 0)
        detector.sample("session", 100, true, true, false, 1_000, 5_000)
        detector.onFirstFrameRendered()

        assertNull(detector.sample("session", 900, true, false, true, 1_000, 6_000))
        assertNotNull(detector.sample("session", 1_101, true, false, true, 1_000, 7_000))
    }

    @Test
    fun newMountResetsSignalState() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-1",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 0,
            nowMs = 0,
        )
        detector.sample(
            sessionKey = "session-1",
            nowMs = 20_000,
            playWhenReady = true,
            isPlaying = false,
            isBuffering = true,
            currentPositionMs = 0,
            bufferedPositionMs = 0,
        )

        detector.onMounted(
            sessionKey = "session-2",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 0,
            nowMs = 30_000,
        )

        assertNull(
            detector.sample(
                sessionKey = "session-1",
                nowMs = 39_999,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 0,
            ),
        )

        assertEquals(
            Playability.StartupStalled(bufferedAheadMs = 0, stalledForMs = 10_001),
            detector.sample(
                sessionKey = "session-2",
                nowMs = 40_001,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 0,
            ),
        )
    }

    @Test
    fun bufferedGrowthReanchorsTransportStallWithoutPretendingPlaybackStarted() {
        val detector = PlaybackStartupStallDetector(startupGraceMs = 10_000)
        detector.onMounted(
            sessionKey = "session-buffering",
            playMethod = PlayMethod.DIRECT,
            startPositionMs = 0,
            nowMs = 0,
        )

        assertNull(
            detector.sample(
                sessionKey = "session-buffering",
                nowMs = 9_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
            ),
        )
        assertNull(
            detector.sample(
                sessionKey = "session-buffering",
                nowMs = 15_000,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
            ),
        )
        assertEquals(
            Playability.StartupStalled(bufferedAheadMs = 5_000, stalledForMs = 10_001),
            detector.sample(
                sessionKey = "session-buffering",
                nowMs = 19_001,
                playWhenReady = true,
                isPlaying = false,
                isBuffering = true,
                currentPositionMs = 0,
                bufferedPositionMs = 5_000,
            ),
        )
    }
}
