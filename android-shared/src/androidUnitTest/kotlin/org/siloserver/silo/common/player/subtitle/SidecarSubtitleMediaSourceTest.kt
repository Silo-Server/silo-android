package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract that keeps a sidecar from gating the merged period: it reports
 * nothing to load and nothing buffered, it keeps its own delegate loading
 * without the composite's help, and it publishes the live position as the
 * history floor.
 */
@RunWith(RobolectricTestRunner::class)
class SidecarSubtitleMediaSourceTest {

    @Test
    fun reportsItselfAsNotAParticipantInLoadingDecisions() {
        val delegate = FakePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor())

        assertEquals(C.TIME_END_OF_SOURCE, period.bufferedPositionUs)
        assertEquals(C.TIME_END_OF_SOURCE, period.nextLoadPositionUs)
    }

    /**
     * ProgressiveMediaPeriod parks its loader every N bytes and after a seek
     * cancels a load in flight, and only resumes when someone calls
     * continueLoading. The composite never will for a child that reports
     * END_OF_SOURCE, so the wrapper has to.
     */
    @Test
    fun continuesItsOwnDelegateWhenTheDelegateParks() {
        val delegate = FakePeriod()
        val floor = SidecarPlaybackFloor()
        val period = NonGatingSidecarPeriod(delegate, floor)
        val upstream = RecordingCallback()
        period.prepare(upstream, 0L)
        floor.set(7_000_000L)

        delegate.loading = false
        delegate.callback!!.onContinueLoadingRequested(delegate)

        assertEquals(1, delegate.continueLoadingCalls.size)
        assertEquals(7_000_000L, delegate.continueLoadingCalls.single().playbackPositionUs)
        // Still forwarded, identified as this wrapper, so the merge stays informed.
        assertSame(period, upstream.continueLoadingRequestedFrom.single())
    }

    @Test
    fun doesNotDoubleStartADelegateThatIsStillLoading() {
        val delegate = FakePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor())
        period.prepare(RecordingCallback(), 0L)

        delegate.loading = true
        delegate.callback!!.onContinueLoadingRequested(delegate)

        assertTrue(delegate.continueLoadingCalls.isEmpty())
    }

    /**
     * ProgressiveMediaPeriod refuses continueLoading until a track is enabled,
     * and a seek leaves an idle delegate reset-but-parked. Both wait for a
     * continueLoading nobody upstream will send — seen on an onn box as a
     * SUP that stopped at its first check interval and never drew a caption.
     */
    @Test
    fun kicksTheDelegateAfterATrackIsEnabledAndAfterASeek() {
        val delegate = FakePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor())
        period.prepare(RecordingCallback(), 0L)

        period.selectTracks(arrayOfNulls(1), BooleanArray(1), arrayOfNulls(1), BooleanArray(1), 0L)
        assertTrue(delegate.continueLoadingCalls.isEmpty()) // nothing enabled: nothing to kick

        period.selectTracks(
            arrayOf(FixedTrackSelection(TrackGroup(Format.Builder().build()), 0)), BooleanArray(1), arrayOfNulls(1), BooleanArray(1), 0L,
        )
        assertEquals(1, delegate.continueLoadingCalls.size)

        period.seekToUs(3_000_000L)
        assertEquals(2, delegate.continueLoadingCalls.size)
        assertEquals(3_000_000L, delegate.continueLoadingCalls.last().playbackPositionUs)
    }

    @Test
    fun publishesTheLivePositionAndSeeksAsTheFloor() {
        val delegate = FakePeriod()
        val floor = SidecarPlaybackFloor()
        val period = NonGatingSidecarPeriod(delegate, floor)

        period.prepare(RecordingCallback(), 2_000_000L)
        assertEquals(2_000_000L, floor.get())

        period.reevaluateBuffer(9_500_000L)
        assertEquals(9_500_000L, floor.get())

        period.seekToUs(1_000_000L)
        assertEquals(1_000_000L, floor.get())
        assertEquals(1_000_000L, delegate.lastSeekUs)
    }

    @Test
    fun forwardsPreparedAsItself() {
        val delegate = FakePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor())
        val upstream = RecordingCallback()

        period.prepare(upstream, 0L)
        delegate.callback!!.onPrepared(delegate)

        assertSame(period, upstream.preparedFrom.single())
        assertFalse(upstream.preparedFrom.contains(delegate))
    }

    private class RecordingCallback : MediaPeriod.Callback {
        val preparedFrom = mutableListOf<MediaPeriod>()
        val continueLoadingRequestedFrom = mutableListOf<MediaPeriod>()

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            preparedFrom += mediaPeriod
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            continueLoadingRequestedFrom += source
        }
    }

    private class FakePeriod : MediaPeriod {
        var callback: MediaPeriod.Callback? = null
        var loading = false
        var lastSeekUs = C.TIME_UNSET
        val continueLoadingCalls = mutableListOf<LoadingInfo>()

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
        }

        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = positionUs

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = C.TIME_UNSET
        override fun seekToUs(positionUs: Long): Long {
            lastSeekUs = positionUs
            return positionUs
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            positionUs

        override fun getBufferedPositionUs(): Long = 0L
        override fun getNextLoadPositionUs(): Long = 0L
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
            continueLoadingCalls += loadingInfo
            return true
        }

        override fun isLoading(): Boolean = loading
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }
}
