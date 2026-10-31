package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.atomic.AtomicLong

/**
 * The player-timeline position the sidecar's period last saw, in microseconds.
 *
 * Written by [SidecarSubtitleMediaSource] from `reevaluateBuffer` (every
 * playback tick while its period is the loading period) and from every seek;
 * read by an extractor on the loader thread as the floor below which a cue is
 * already history and must not be published. Shared per sidecar.
 */
class SidecarPlaybackFloor {
    private val positionUs = AtomicLong(0L)

    fun get(): Long = positionUs.get()

    fun set(value: Long) = positionUs.set(value)
}

/**
 * Takes a text sidecar out of the [androidx.media3.exoplayer.source.MergingMediaSource]
 * loading gate.
 *
 * Media3 drives a merged period through one `CompositeSequenceableLoader`,
 * which only ever continues the child with the smallest next-load position
 * (or one behind the playhead). A sidecar is read from byte zero, so on a
 * resume its next-load position sits at the seek point until the download
 * reaches it — and for that whole time it is the child that gets continued.
 * The video child fetches its first chunk and then starves. Seen on an onn
 * box: 20 s in BUFFERING with 406 ms of video buffered while a 4K film's
 * SDH `.sup` streamed, until the startup-stall detector gave up and fell back
 * to a transcode (which then dropped the subtitle).
 *
 * This wrapper reports the sidecar's period as having nothing to load and
 * nothing buffered, so the composite ignores it and the video decides when
 * playback starts and what loads next. The sidecar's own load keeps running:
 * `ProgressiveMediaPeriod` starts loading in `prepare()`, parks itself every
 * `continueLoadingCheckIntervalBytes` (and after a seek cancels a load in
 * flight) by asking its callback to continue, and that callback is this
 * wrapper — which continues it directly instead of waiting for a composite
 * that will never ask.
 *
 * Captions that arrive after the playhead has passed them are the extractor's
 * problem, not this class's: it publishes the live position through
 * [SidecarPlaybackFloor] so a REPLACE-behaviour extractor can drop them.
 *
 * [maxLookaheadUs] bounds how far past the playhead the sidecar may read.
 * Without it the whole file is read up front, and everything read stays in the
 * sample queue until playback reaches it. That is nothing for a text sidecar,
 * but a feature-length `.sup` holds tens of MB of caption images, enough to
 * run a phone with a 192 MB heap out of memory. [C.TIME_UNSET] keeps the
 * unbounded read.
 */
@UnstableApi
class SidecarSubtitleMediaSource(
    child: MediaSource,
    private val floor: SidecarPlaybackFloor,
    private val maxLookaheadUs: Long = C.TIME_UNSET,
    /**
     * The track [child] will publish, known from the `SubtitleConfiguration`
     * before any byte arrives. With it the period reports itself prepared at
     * once, so a sidecar can never hold back the video's preparation; see
     * [NonGatingSidecarPeriod].
     */
    private val trackFormat: Format? = null,
    /** Called when the sidecar's load starts over on purpose; see [NonGatingSidecarPeriod]. */
    private val onFreshLoad: () -> Unit = {},
) : WrappingMediaSource(child) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = NonGatingSidecarPeriod(
        mediaSource.createPeriod(id, allocator, startPositionUs),
        floor,
        maxLookaheadUs,
        trackFormat,
        onFreshLoad,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as NonGatingSidecarPeriod).delegate)
    }
}

/**
 * With an [announcedFormat] the period is prepared as soon as it starts: it
 * publishes that one track, hands the player its own streams, and passes the
 * selection on to the delegate once the delegate has prepared (its HTTP
 * response arrived and the extractor declared the track). Until then the
 * streams read nothing, seeks only move the floor, and the calls a
 * `ProgressiveMediaPeriod` refuses before preparing are not made. A sidecar
 * that never answers, or fails before preparing, then costs only its
 * subtitle: the error reaches the text renderer through the stream, which
 * Media3 answers by disabling the text track, never the video.
 *
 * Without one, the period prepares when the delegate does, as before.
 *
 * [onFreshLoad] runs when the sidecar's load starts over on purpose: on
 * prepare, on a seek, and when the text track is enabled again. The retry
 * budget starts afresh then, and not when Media3 restarts the stream on its
 * own after an error.
 */
@UnstableApi
internal class NonGatingSidecarPeriod(
    val delegate: MediaPeriod,
    private val floor: SidecarPlaybackFloor,
    private val maxLookaheadUs: Long = C.TIME_UNSET,
    announcedFormat: Format? = null,
    private val onFreshLoad: () -> Unit = {},
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    /** The delegate parked far enough ahead that it was left parked; see [continueUnlessAhead]. */
    private var heldAhead = false

    /** The delegate's track groups, buffered position and seeks are defined only once it has prepared. */
    private var prepared = false

    /** The one track published up front, under the id `ProgressiveMediaPeriod` gives its first group. */
    private val announcedTrackGroups: TrackGroupArray? =
        announcedFormat?.let { TrackGroupArray(TrackGroup("0", it)) }

    /** The player's streams and selections for the announced track, by renderer slot. */
    private var announcedStreams: Array<AnnouncedSampleStream?> = emptyArray()
    private var announcedSelections: Array<ExoTrackSelection?> = emptyArray()

    /** The delegate's own streams by slot, and which announced stream each serves. */
    private var delegateStreams: Array<SampleStream?> = emptyArray()
    private var delegateStreamOwners: Array<AnnouncedSampleStream?> = emptyArray()

    /** Whether the player has the text track enabled, for [onFreshLoad]. */
    private var selected = false

    private val awaitingDelegate: Boolean
        get() = announcedTrackGroups != null && !prepared

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        floor.set(positionUs)
        onFreshLoad()
        delegate.prepare(this, positionUs)
        if (announcedTrackGroups != null) callback.onPrepared(this)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        prepared = true
        if (announcedTrackGroups == null) {
            callback?.onPrepared(this)
            return
        }
        // Already reported prepared: hand over the selection made meanwhile.
        if (announcedSelections.any { it != null }) {
            forwardAnnouncedSelection(handoverPositionUs())
            continueUnlessAhead("prepared")
        }
    }

    /**
     * Where the delegate's first selection goes when it arrives late: the
     * playhead, as far as the delegate can seek. `ProgressiveMediaPeriod`
     * treats a first selection anywhere but 0 as a seek, and a response of
     * unknown length (a cold extraction) cannot seek: it would cancel the
     * load under way and request the sidecar again from byte zero. Its
     * adjusted position is 0, so that load carries on and the cues it reads
     * from before the playhead are dropped as history.
     */
    private fun handoverPositionUs(): Long =
        delegate.getAdjustedSeekPositionUs(floor.get(), SeekParameters.DEFAULT)

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        // The delegate parked its loader (interval reached, or a cancelled load
        // finished unwinding). Nobody upstream will continue a child that
        // reports nothing to load, so do it here.
        continueUnlessAhead("requested")
        callback?.onContinueLoadingRequested(this)
    }

    /**
     * Continues the delegate unless its queued cues already reach more than
     * [maxLookaheadUs] past the playhead. A held delegate stays parked, and
     * [resumeIfCaughtUp] continues it from the playback ticks once the
     * playhead closes the gap.
     */
    private fun continueUnlessAhead(reason: String) {
        if (isAheadOfPlayhead()) {
            if (!heldAhead) {
                org.siloserver.silo.common.player.SubDiag.log(
                    "sidecar held($reason) buffered=${delegate.bufferedPositionUs / 1000}ms " +
                        "floor=${floor.get() / 1000}ms",
                )
            }
            heldAhead = true
            return
        }
        heldAhead = false
        kickDelegate(reason)
    }

    private fun resumeIfCaughtUp() {
        if (heldAhead && !isAheadOfPlayhead()) continueUnlessAhead("caught-up")
    }

    private fun isAheadOfPlayhead(): Boolean {
        if (maxLookaheadUs == C.TIME_UNSET || !prepared) return false
        val bufferedUs = delegate.bufferedPositionUs
        if (bufferedUs == C.TIME_END_OF_SOURCE || bufferedUs == C.TIME_UNSET) return false
        return bufferedUs - floor.get() > maxLookaheadUs
    }

    /**
     * `ProgressiveMediaPeriod` never restarts itself: after `prepare()` it
     * reads until its check interval and parks; `selectTracks` that enables a
     * track, and `seekToUs`, both leave it parked or cancelled and wait for
     * `continueLoading`. Every one of those funnels here. The delegate declines
     * on its own when it has finished, has a fatal error, is mid-cancel, or has
     * no enabled track — so calling it eagerly is safe.
     */
    private fun kickDelegate(reason: String) {
        if (delegate.isLoading) return
        val loadingInfo = LoadingInfo.Builder()
            .setPlaybackPositionUs(floor.get())
            .setPlaybackSpeed(1f)
            .setLastRebufferRealtimeMs(C.TIME_UNSET)
            .build()
        val continued = try {
            delegate.continueLoading(loadingInfo)
        } catch (error: IllegalStateException) {
            // An unprepared delegate refuses the seek below. (A real one
            // only throws here once prepared.)
            if (awaitingDelegate) throw error
            // A prepared ProgressiveMediaPeriod only starts a new load from a
            // pending reset. A load cancelled because its track was disabled
            // has none, and when the track is enabled again before that
            // cancel lands (a subtitle remount reselecting the same track),
            // the cancel's continue request reaches here without one. Restart
            // the load from the playhead, as a seek would.
            org.siloserver.silo.common.player.SubDiag.log("sidecar restart($reason) floor=${floor.get() / 1000}ms")
            delegate.seekToUs(floor.get())
            delegate.continueLoading(loadingInfo)
        }
        org.siloserver.silo.common.player.SubDiag.log(
            "sidecar kick($reason) continued=$continued floor=${floor.get() / 1000}ms",
        )
    }

    override fun maybeThrowPrepareError() {
        // An announced period is prepared whatever the delegate does; its
        // error reaches the text renderer through the streams instead.
        if (announcedTrackGroups == null) delegate.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = announcedTrackGroups ?: delegate.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        if (announcedTrackGroups == null) delegate.getStreamKeys(trackSelections) else emptyList()

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val selecting = selections.any { it != null }
        // Enabling the text track again starts its load over on purpose.
        if (selecting && !selected) onFreshLoad()
        selected = selecting
        if (announcedTrackGroups == null) {
            val result = delegate.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
            // Enabling the text track (a subtitle pick after start, or the first
            // selection once prepared) is what makes the delegate willing to load.
            if (selecting) continueUnlessAhead("select")
            return result
        }
        for (i in selections.indices) {
            val current = streams[i] as? AnnouncedSampleStream
            if (current != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                current.inner = null
                streams[i] = null
            }
            if (streams[i] == null && selections[i] != null) {
                streams[i] = AnnouncedSampleStream()
                streamResetFlags[i] = true
            }
        }
        announcedStreams = Array(selections.size) { streams[it] as? AnnouncedSampleStream }
        announcedSelections = Array(selections.size) { selections[it] }
        if (prepared) forwardAnnouncedSelection(positionUs)
        if (selecting) continueUnlessAhead("select")
        return positionUs
    }

    /**
     * Mirrors the announced selection onto the delegate's own track, keeping a
     * delegate stream only while the player keeps the stream it serves.
     */
    private fun forwardAnnouncedSelection(positionUs: Long) {
        val slots = announcedSelections.size
        if (delegateStreams.size != slots) {
            delegateStreams = delegateStreams.copyOf(slots)
            delegateStreamOwners = delegateStreamOwners.copyOf(slots)
        }
        val group = delegate.trackGroups.takeIf { it.length > 0 }?.get(0)
        val selections = arrayOfNulls<ExoTrackSelection>(slots)
        val retain = BooleanArray(slots)
        for (i in 0 until slots) {
            val stream = announcedStreams[i]
            if (group != null && stream != null && announcedSelections[i] != null) {
                selections[i] = FixedTrackSelection(group, 0)
                retain[i] = delegateStreams[i] != null && delegateStreamOwners[i] === stream
            }
        }
        delegate.selectTracks(selections, retain, delegateStreams, BooleanArray(slots), positionUs)
        for (i in 0 until slots) {
            val owner = announcedStreams[i].takeIf { delegateStreams[i] != null }
            delegateStreamOwners[i] = owner
            owner?.inner = delegateStreams[i]
        }
    }

    /**
     * Calls a `ProgressiveMediaPeriod` refuses before preparing are skipped.
     */
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        if (!awaitingDelegate) delegate.discardBuffer(positionUs, toKeyframe)
        floor.set(maxOf(floor.get(), positionUs))
        resumeIfCaughtUp()
    }

    /**
     * A sidecar never moves the playhead. `MergingMediaPeriod` seeks every
     * child to a discontinuity any one child reports, and
     * `ProgressiveMediaPeriod` reports one at 0 once it has restarted a
     * response of unknown length from the start after an error (a deferred
     * retry): passed on, it would take the video back to the beginning. The
     * delegate's is read, so it resumes reading, and never reported.
     */
    override fun readDiscontinuity(): Long {
        if (!awaitingDelegate) delegate.readDiscontinuity()
        return C.TIME_UNSET
    }

    override fun seekToUs(positionUs: Long): Long {
        floor.set(positionUs)
        heldAhead = false
        onFreshLoad()
        // Not prepared yet: the delegate's selection is made at the floor
        // once it prepares.
        if (awaitingDelegate) return positionUs
        val result = delegate.seekToUs(positionUs)
        // An idle delegate is left reset-but-parked by a seek; a loading one is
        // cancelled and comes back through onContinueLoadingRequested.
        kickDelegate("seek")
        return result
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        if (awaitingDelegate) positionUs else delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)

    /** Not a participant: the audio/video children decide when playback may start. */
    override fun getBufferedPositionUs(): Long = C.TIME_END_OF_SOURCE

    /** Not a participant: the audio/video children decide what loads next. */
    override fun getNextLoadPositionUs(): Long = C.TIME_END_OF_SOURCE

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        floor.set(maxOf(floor.get(), loadingInfo.playbackPositionUs))
        return delegate.continueLoading(loadingInfo)
    }

    override fun isLoading(): Boolean = delegate.isLoading

    override fun reevaluateBuffer(positionUs: Long) {
        // Called on the loading period every playback tick with the current
        // period position — the live floor for "this cue is already history".
        floor.set(positionUs)
        delegate.reevaluateBuffer(positionUs)
        resumeIfCaughtUp()
    }

    /**
     * The player's stream for the announced track. It reads nothing until
     * the delegate has prepared and been given the selection, then reads the
     * delegate's stream. Before that, a delegate that failed to prepare
     * reports its error here, for the text renderer to disable the track.
     */
    private inner class AnnouncedSampleStream : SampleStream {
        var inner: SampleStream? = null

        override fun isReady(): Boolean = inner?.isReady == true

        override fun maybeThrowError() {
            val stream = inner
            if (stream != null) {
                stream.maybeThrowError()
            } else if (!prepared) {
                delegate.maybeThrowPrepareError()
            }
        }

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int,
        ): Int = inner?.readData(formatHolder, buffer, readFlags) ?: C.RESULT_NOTHING_READ

        override fun skipData(positionUs: Long): Int = inner?.skipData(positionUs) ?: 0
    }
}
