package org.siloserver.silo.common.player

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.domain.player.BufferingWaitCause

class BufferingQualityPlayerAdapterTest {
    private class RecordingSink : BufferingPlaybackEventSink {
        val events = mutableListOf<String>()
        override fun onPlaying() { events += "playing" }
        override fun onWaiting(cause: BufferingWaitCause) { events += "waiting:${cause.name.lowercase()}" }
        override fun onPause() { events += "pause" }
        override fun onSeek() { events += "seek" }
        override fun onTransportErrorStart() { events += "transport_start" }
        override fun onTransportErrorEnd() { events += "transport_end" }
    }

    private var now = 0L
    private val sink = RecordingSink()

    /** Drain checks the adapter scheduled, run by the test. */
    private val scheduled = mutableListOf<() -> Unit>()
    private val adapter = BufferingQualityPlayerAdapter(
        sink,
        clock = { now },
        schedule = { _, action -> scheduled += action; { scheduled -= action } },
    )

    private fun runScheduled() {
        val due = scheduled.toList()
        scheduled.clear()
        due.forEach { it() }
    }

    /** The callbacks Media3 sends as a stall begins on a playing player. */
    private fun starve() {
        adapter.onPlaybackStateChanged(Player.STATE_BUFFERING)
        adapter.onIsPlayingChanged(false)
    }

    /** The callbacks Media3 sends as a starved player resumes. */
    private fun resume() {
        adapter.onPlaybackStateChanged(Player.STATE_READY)
        adapter.onIsPlayingChanged(true)
    }

    private fun playing() {
        adapter.seed(playWhenReady = true, playbackState = Player.STATE_READY, isPlaying = true)
        sink.events.clear()
    }

    private fun stalled(classification: String = "transport_stall") =
        Playability.StartupStalled(bufferedAheadMs = 0, stalledForMs = 20_000, classification = classification)

    @Test
    fun aNewPlansFirstWaitAndALaterStarvationAreNetworkWaits() {
        adapter.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        adapter.onPlaybackStateChanged(Player.STATE_BUFFERING)
        resume()
        starve()
        resume()
        // READY arrives before isPlaying: a resume is never read as a pause.
        assertEquals(listOf("waiting:network", "playing", "waiting:network", "playing"), sink.events)
    }

    @Test
    fun aWaitWithMediaBufferedAheadIsARendererWaitUntilItDrains() {
        var aheadMs = 3_600L
        adapter.seed(playWhenReady = true, playbackState = Player.STATE_READY, isPlaying = true) { aheadMs }
        sink.events.clear()

        // Media3 dropping to a keyframe: buffering with seconds still buffered.
        starve()
        resume()
        // The same kind of wait, but the buffer drains before playback resumes.
        starve()
        aheadMs = 400L
        adapter.sampleBuffer(nowMs = 1_000L, bufferedPositionMs = 10_000L)
        adapter.sampleBuffer(nowMs = 2_000L, bufferedPositionMs = 10_000L)
        resume()
        // Out of data from the start.
        starve()

        assertEquals(
            listOf(
                "waiting:renderer", "playing",
                "waiting:renderer", "waiting:network", "playing",
                "waiting:network",
            ),
            sink.events,
        )
    }

    @Test
    fun aRendererWaitBecomesANetworkWaitWhenItsBufferDrainsBetweenSamples() {
        var aheadMs = 3_600L
        adapter.seed(playWhenReady = true, playbackState = Player.STATE_READY, isPlaying = true) { aheadMs }
        sink.events.clear()

        starve()
        runScheduled()
        aheadMs = 400L
        runScheduled()
        // Nothing is left to check once it is a network wait.
        runScheduled()

        assertEquals(listOf("waiting:renderer", "waiting:network"), sink.events)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun aTunneledPlayerWaitIsANetworkWaitWhateverIsBuffered() {
        // A tunneled codec's samples still count as buffered.
        adapter.seed(
            playWhenReady = true,
            playbackState = Player.STATE_READY,
            isPlaying = true,
            tunneling = { true },
        ) { 3_600L }
        sink.events.clear()

        starve()

        assertEquals(listOf("waiting:network"), sink.events)
    }

    @Test
    fun aSeekMakesTheNextWaitASeekWaitUntilPlaybackRuns() {
        playing()
        adapter.onDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
        starve()
        resume()
        starve()
        assertEquals(listOf("seek", "waiting:seek", "playing", "waiting:network"), sink.events)

        // A seek during a stall re-announces the wait with its new cause.
        sink.events.clear()
        adapter.onDiscontinuity(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        assertEquals(listOf("seek", "waiting:seek"), sink.events)

        sink.events.clear()
        adapter.onDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun aMarkedReloadMakesTheRemountsWaitAReloadWait() {
        playing()
        adapter.markReload()
        adapter.onPlaybackStateChanged(Player.STATE_IDLE)
        adapter.onIsPlayingChanged(false)
        adapter.onPlaybackStateChanged(Player.STATE_BUFFERING)
        resume()
        starve()
        assertEquals(listOf("pause", "waiting:reload", "playing", "waiting:network"), sink.events)

        sink.events.clear()
        adapter.markReload()
        assertEquals(listOf("waiting:reload"), sink.events)
    }

    @Test
    fun pausingEndingAndIdlingAreInterruptions() {
        playing()
        adapter.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        adapter.onIsPlayingChanged(false)
        adapter.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        adapter.onIsPlayingChanged(true)
        adapter.onPlaybackStateChanged(Player.STATE_ENDED)
        adapter.onIsPlayingChanged(false)
        assertEquals(listOf("pause", "playing", "pause"), sink.events)

        // A pause during a stall is an interruption too.
        sink.events.clear()
        adapter.onPlaybackStateChanged(Player.STATE_BUFFERING)
        adapter.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        assertEquals(listOf("waiting:network", "pause"), sink.events)
    }

    @Test
    fun aLoadErrorStartsTroubleAndBufferProgressEndsIt() {
        playing()
        adapter.onLoadFailed(wasCanceled = true)
        adapter.onLoadFailed(wasCanceled = false, subtitle = true)
        assertFalse(adapter.transportTroubleActive)

        adapter.sampleBuffer(nowMs = 0, bufferedPositionMs = 10_000)
        adapter.onLoadFailed(wasCanceled = false)
        adapter.onLoadFailed(wasCanceled = false)
        assertTrue(adapter.transportTroubleActive)
        adapter.sampleBuffer(nowMs = 1_000, bufferedPositionMs = 10_000)
        assertTrue(adapter.transportTroubleActive)
        adapter.sampleBuffer(nowMs = 2_000, bufferedPositionMs = 12_000)
        assertFalse(adapter.transportTroubleActive)
        assertEquals(listOf("transport_start", "transport_end"), sink.events)
    }

    @Test
    fun playbackRunningEndsLoadTroubleBeforeItPlays() {
        playing()
        starve()
        adapter.onLoadFailed(wasCanceled = false)
        resume()
        assertEquals(listOf("waiting:network", "transport_start", "transport_end", "playing"), sink.events)
    }

    @Test
    fun offlineAndLoadErrorsEndOnlyWhenBothClear() {
        playing()
        adapter.onLoadFailed(wasCanceled = false)
        adapter.onNetworkAvailable(connected = false)
        adapter.onLoadFinished(bytesLoaded = 4_096)
        assertTrue(adapter.transportTroubleActive)
        // Playing from the buffer is no evidence the device is back online.
        starve()
        resume()
        assertTrue(adapter.transportTroubleActive)
        // Back online is not data arriving either; the media's next bytes are.
        adapter.onNetworkAvailable(connected = true)
        assertTrue(adapter.transportTroubleActive)
        adapter.onLoadFinished(bytesLoaded = 4_096)
        assertFalse(adapter.transportTroubleActive)
        assertEquals(listOf("transport_start", "waiting:network", "playing", "transport_end"), sink.events)

        sink.events.clear()
        adapter.onNetworkAvailable(connected = false)
        adapter.onLoadFailed(wasCanceled = false)
        adapter.onNetworkAvailable(connected = true)
        // An empty load or a playlist refresh that succeeds ends nothing.
        adapter.onLoadFinished(bytesLoaded = 0)
        adapter.onLoadFinished(bytesLoaded = 2_048, carriesMedia = false)
        assertTrue(adapter.transportTroubleActive)
        adapter.sampleBuffer(nowMs = 0, bufferedPositionMs = 10_000)
        adapter.sampleBuffer(nowMs = 1_000, bufferedPositionMs = 12_000)
        assertFalse(adapter.transportTroubleActive)
        assertEquals(listOf("transport_start", "transport_end"), sink.events)
    }

    @Test
    fun aNewPlanStartsWithNoTransportTrouble() {
        playing()
        adapter.onLoadFailed(wasCanceled = false)
        adapter.onNetworkAvailable(connected = false)
        assertTrue(adapter.transportTroubleActive)

        adapter.reset()

        assertFalse(adapter.transportTroubleActive)
        assertEquals(listOf("transport_start", "transport_end", "playing"), sink.events)
    }

    @Test
    fun aSlowLinkStillReceivingDataOwnsItsTransportStall() {
        adapter.sampleBuffer(nowMs = 0, bufferedPositionMs = 1_000)
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 1_000, controllerEnabled = true))
        adapter.sampleBuffer(nowMs = 2_000, bufferedPositionMs = 3_000)
        assertTrue(adapter.ownsStartupStall(stalled(), nowMs = 7_000, controllerEnabled = true))

        // No data in the last five seconds.
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 7_001, controllerEnabled = true))

        // A completed media load with bytes is data too, timed by the adapter's clock;
        // a playlist refresh is not.
        now = 10_000
        adapter.onLoadFinished(bytesLoaded = 2_048, carriesMedia = false)
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 10_000, controllerEnabled = true))
        adapter.onLoadFinished(bytesLoaded = 65_536)
        assertTrue(adapter.ownsStartupStall(stalled(), nowMs = 12_000, controllerEnabled = true))

        // Not for another classification, another reason, or a controller that is off.
        assertFalse(adapter.ownsStartupStall(stalled("decoder_no_output"), nowMs = 12_000, controllerEnabled = true))
        assertFalse(adapter.ownsStartupStall(Playability.Supported, nowMs = 12_000, controllerEnabled = true))
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 12_000, controllerEnabled = false))

        // Not while loads fail or the device is offline.
        adapter.onLoadFailed(wasCanceled = false)
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 12_000, controllerEnabled = true))
        adapter.onLoadFinished(bytesLoaded = 65_536)
        adapter.onNetworkAvailable(connected = false)
        assertFalse(adapter.ownsStartupStall(stalled(), nowMs = 12_000, controllerEnabled = true))
    }

    @Test
    fun aSeekJumpIsNotDataArrivingAndResetForgetsHistory() {
        adapter.sampleBuffer(nowMs = 0, bufferedPositionMs = 10_000)
        adapter.onDiscontinuity(Player.DISCONTINUITY_REASON_SEEK)
        adapter.sampleBuffer(nowMs = 1_000, bufferedPositionMs = 500_000)
        assertFalse(adapter.isReceivingData(1_000))

        adapter.sampleBuffer(nowMs = 2_000, bufferedPositionMs = 502_000)
        assertTrue(adapter.isReceivingData(2_000))
        adapter.reset()
        assertFalse(adapter.isReceivingData(2_000))
    }
}
