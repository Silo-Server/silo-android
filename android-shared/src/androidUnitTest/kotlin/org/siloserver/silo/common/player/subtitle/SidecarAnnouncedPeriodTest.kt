package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sidecar that knows its track up front is prepared at once and never holds
 * back the video: its selection waits for the delegate, and a delegate that
 * never prepares costs only the subtitle.
 */
@RunWith(RobolectricTestRunner::class)
class SidecarAnnouncedPeriodTest {
    private val announced = Format.Builder()
        .setId("silo-subtitle:3")
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCodecs(MimeTypes.TEXT_VTT)
        .build()

    @Test
    fun isPreparedAtOnceWithTheAnnouncedTrackWhateverTheDelegateDoes() {
        val delegate = DelegatePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor(), announcedFormat = announced)
        val upstream = Recording()

        period.prepare(upstream, 5_000_000L)

        assertSame(period, upstream.prepared.single())
        assertEquals(1, period.trackGroups.length)
        assertSame(announced, period.trackGroups[0].getFormat(0))
        // The delegate's preparation failed: the merged period must not.
        delegate.prepareError = IOException("sidecar never answered")
        period.maybeThrowPrepareError()
        // Preparing late does not report the period a second time.
        delegate.finishPreparing()
        assertEquals(1, upstream.prepared.size)
    }

    @Test
    fun aSelectionBeforeTheDelegatePreparesWaitsForItAtThePlayhead() {
        val delegate = DelegatePeriod()
        val floor = SidecarPlaybackFloor()
        val period = NonGatingSidecarPeriod(delegate, floor, announcedFormat = announced)
        period.prepare(Recording(), 0L)

        val streams = arrayOfNulls<SampleStream>(2)
        val resets = BooleanArray(2)
        val position = period.selectTracks(
            arrayOf(null, FixedTrackSelection(period.trackGroups[0], 0)),
            BooleanArray(2),
            streams,
            resets,
            0L,
        )

        assertEquals(0L, position)
        assertNull(streams[0])
        val stream = streams[1]!!
        assertTrue(resets[1])
        assertEquals(C.RESULT_NOTHING_READ, stream.readData(FormatHolder(), DecoderInputBuffer(0), 0))
        assertTrue(delegate.selections.isEmpty())
        // Seeks before then only move the floor: an unprepared delegate refuses them.
        assertEquals(42_000_000L, period.seekToUs(42_000_000L))
        assertEquals(C.TIME_UNSET, delegate.lastSeekUs)
        assertEquals(7L, period.getAdjustedSeekPositionUs(7L, SeekParameters.DEFAULT))
        period.discardBuffer(42_000_000L, false)
        assertEquals(C.TIME_UNSET, period.readDiscontinuity())

        delegate.finishPreparing()

        val forwarded = delegate.selections.single()
        assertEquals(42_000_000L, forwarded.positionUs)
        assertSame(delegate.group, forwarded.selections[1]!!.trackGroup)
        assertNull(forwarded.selections[0])
        assertEquals(C.RESULT_BUFFER_READ, stream.readData(FormatHolder(), DecoderInputBuffer(0), 0))
    }

    @Test
    fun aKeptStreamKeepsItsDelegateStreamAndADisabledOneReleasesIt() {
        val delegate = DelegatePeriod().apply { finishPreparingOnPrepare = true }
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor(), announcedFormat = announced)
        period.prepare(Recording(), 0L)
        val selection = FixedTrackSelection(period.trackGroups[0], 0)
        val streams = arrayOfNulls<SampleStream>(1)
        period.selectTracks(arrayOf(selection), BooleanArray(1), streams, BooleanArray(1), 0L)
        val first = streams[0]!!
        val delegateStream = delegate.selections.single().streams[0]

        val resets = BooleanArray(1)
        period.selectTracks(arrayOf(selection), booleanArrayOf(true), streams, resets, 0L)
        assertSame(first, streams[0])
        assertSame(delegateStream, delegate.selections.last().streams[0])
        assertTrue(delegate.selections.last().retain[0])

        period.selectTracks(arrayOf(null), BooleanArray(1), streams, BooleanArray(1), 0L)
        assertNull(streams[0])
        assertNull(delegate.selections.last().streams[0])
        assertEquals(C.RESULT_NOTHING_READ, first.readData(FormatHolder(), DecoderInputBuffer(0), 0))

        period.selectTracks(arrayOf(selection), BooleanArray(1), streams, BooleanArray(1), 0L)
        assertNotSame(first, streams[0])
    }

    @Test
    fun aDelegateThatFailsBeforePreparingReportsThroughTheStream() {
        val delegate = DelegatePeriod()
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor(), announcedFormat = announced)
        period.prepare(Recording(), 0L)
        val streams = arrayOfNulls<SampleStream>(1)
        period.selectTracks(
            arrayOf(FixedTrackSelection(period.trackGroups[0], 0)), BooleanArray(1), streams, BooleanArray(1), 0L,
        )

        delegate.prepareError = IOException("sidecar never answered")

        // The text renderer gets the error and Media3 disables only the text track.
        assertThrows(IOException::class.java) { streams[0]!!.maybeThrowError() }
        period.maybeThrowPrepareError()
    }

    @Test
    fun anUnseekableDelegateKeepsTheLoadItIsRunningWhenTheSelectionArrivesLate() {
        // A cold extraction has no known length: any seek restarts it from byte zero.
        val delegate = DelegatePeriod().apply { seekable = false }
        val floor = SidecarPlaybackFloor()
        val period = NonGatingSidecarPeriod(delegate, floor, announcedFormat = announced)
        period.prepare(Recording(), 0L)
        period.selectTracks(
            arrayOf(FixedTrackSelection(period.trackGroups[0], 0)), BooleanArray(1), arrayOfNulls(1), BooleanArray(1), 0L,
        )
        floor.set(15_000_000L)

        delegate.finishPreparing()

        // ProgressiveMediaPeriod seeks on a first selection anywhere but 0.
        assertEquals(0L, delegate.selections.single().positionUs)
    }

    @Test
    fun theDelegatesDiscontinuityIsReadButNeverMovesThePlayhead() {
        val delegate = DelegatePeriod().apply { finishPreparingOnPrepare = true }
        val period = NonGatingSidecarPeriod(delegate, SidecarPlaybackFloor(), announcedFormat = announced)
        period.prepare(Recording(), 0L)
        // A deferred retry restarted the response from the start.
        delegate.discontinuityUs = 0L

        assertEquals(C.TIME_UNSET, period.readDiscontinuity())
        assertEquals(C.TIME_UNSET, delegate.discontinuityUs)
    }

    @Test
    fun theLoadStartsFreshOnPrepareSeekAndReenablingButNotOnAKeptSelection() {
        val delegate = DelegatePeriod().apply { finishPreparingOnPrepare = true }
        var fresh = 0
        val period = NonGatingSidecarPeriod(
            delegate, SidecarPlaybackFloor(), announcedFormat = announced, onFreshLoad = { fresh++ },
        )
        period.prepare(Recording(), 0L)
        assertEquals(1, fresh)
        val selection = FixedTrackSelection(period.trackGroups[0], 0)
        val streams = arrayOfNulls<SampleStream>(1)
        period.selectTracks(arrayOf(selection), BooleanArray(1), streams, BooleanArray(1), 0L)
        assertEquals(2, fresh)
        period.selectTracks(arrayOf(selection), booleanArrayOf(true), streams, BooleanArray(1), 0L)
        assertEquals(2, fresh)

        period.seekToUs(30_000_000L)
        assertEquals(3, fresh)
        period.selectTracks(arrayOf(null), BooleanArray(1), streams, BooleanArray(1), 0L)
        period.selectTracks(arrayOf(selection), BooleanArray(1), streams, BooleanArray(1), 0L)
        assertEquals(4, fresh)
    }

    private class Recording : MediaPeriod.Callback {
        val prepared = mutableListOf<MediaPeriod>()
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared += mediaPeriod
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
    }

    /** Models a ProgressiveMediaPeriod: track groups, selection and seeks need preparation. */
    private class DelegatePeriod : MediaPeriod {
        data class Selection(
            val selections: Array<ExoTrackSelection?>,
            val retain: BooleanArray,
            val streams: Array<SampleStream?>,
            val positionUs: Long,
        )

        val group = TrackGroup("0", Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).build())
        val selections = mutableListOf<Selection>()
        var prepareError: IOException? = null
        var lastSeekUs = C.TIME_UNSET
        var finishPreparingOnPrepare = false
        var seekable = true
        var discontinuityUs = C.TIME_UNSET
        private var callback: MediaPeriod.Callback? = null
        private var prepared = false

        fun finishPreparing() {
            prepared = true
            callback!!.onPrepared(this)
        }

        private fun assertPrepared() = check(prepared) { "not prepared" }

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
            if (finishPreparingOnPrepare) finishPreparing()
        }

        override fun maybeThrowPrepareError() {
            prepareError?.let { throw it }
        }

        override fun getTrackGroups(): TrackGroupArray {
            assertPrepared()
            return TrackGroupArray(group)
        }

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            assertPrepared()
            for (i in selections.indices) {
                if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) streams[i] = null
                if (streams[i] == null && selections[i] != null) streams[i] = DataStream()
            }
            this.selections += Selection(
                Array(selections.size) { selections[it] },
                mayRetainStreamFlags.copyOf(),
                streams.copyOf(),
                positionUs,
            )
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = assertPrepared()
        override fun readDiscontinuity(): Long = discontinuityUs.also { discontinuityUs = C.TIME_UNSET }
        override fun seekToUs(positionUs: Long): Long {
            assertPrepared()
            lastSeekUs = positionUs
            return positionUs
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
            assertPrepared()
            // ProgressiveMediaPeriod treats every seek into unseekable media as one to 0.
            return if (seekable) positionUs else 0L
        }

        override fun getBufferedPositionUs(): Long {
            assertPrepared()
            return 0L
        }

        override fun getNextLoadPositionUs(): Long = 0L
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = true
        override fun isLoading(): Boolean = true
        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    private class DataStream : SampleStream {
        override fun isReady(): Boolean = true
        override fun maybeThrowError() = Unit
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            C.RESULT_BUFFER_READ

        override fun skipData(positionUs: Long): Int = 0
    }
}
