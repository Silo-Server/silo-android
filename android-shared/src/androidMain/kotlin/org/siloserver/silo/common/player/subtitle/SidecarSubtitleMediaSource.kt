package org.siloserver.silo.common.player.subtitle

import androidx.media3.common.C
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
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
 */
@UnstableApi
class SidecarSubtitleMediaSource(
    child: MediaSource,
    private val floor: SidecarPlaybackFloor,
) : WrappingMediaSource(child) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = NonGatingSidecarPeriod(
        mediaSource.createPeriod(id, allocator, startPositionUs),
        floor,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as NonGatingSidecarPeriod).delegate)
    }
}

@UnstableApi
internal class NonGatingSidecarPeriod(
    val delegate: MediaPeriod,
    private val floor: SidecarPlaybackFloor,
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        floor.set(positionUs)
        delegate.prepare(this, positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        // The delegate parked its loader (interval reached, or a cancelled load
        // finished unwinding). Nobody upstream will continue a child that
        // reports nothing to load, so do it here.
        kickDelegate("requested")
        callback?.onContinueLoadingRequested(this)
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
        val continued = delegate.continueLoading(
            LoadingInfo.Builder()
                .setPlaybackPositionUs(floor.get())
                .setPlaybackSpeed(1f)
                .setLastRebufferRealtimeMs(C.TIME_UNSET)
                .build(),
        )
        org.siloserver.silo.common.player.SubDiag.log(
            "sidecar kick($reason) continued=$continued floor=${floor.get() / 1000}ms",
        )
    }

    override fun maybeThrowPrepareError() = delegate.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = delegate.trackGroups

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        delegate.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val result = delegate.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)
        // Enabling the text track (a subtitle pick after start, or the first
        // selection once prepared) is what makes the delegate willing to load.
        if (selections.any { it != null }) kickDelegate("select")
        return result
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        delegate.discardBuffer(positionUs, toKeyframe)

    override fun readDiscontinuity(): Long = delegate.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long {
        floor.set(positionUs)
        val result = delegate.seekToUs(positionUs)
        // An idle delegate is left reset-but-parked by a seek; a loading one is
        // cancelled and comes back through onContinueLoadingRequested.
        kickDelegate("seek")
        return result
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        delegate.getAdjustedSeekPositionUs(positionUs, seekParameters)

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
    }
}
