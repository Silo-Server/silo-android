package org.siloserver.silo.common.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import org.siloserver.silo.domain.player.BufferingQualityController
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.RENDERER_WAIT_BUFFER_MS
import org.siloserver.silo.domain.player.BufferingWaitCause

/** The playback events [BufferingQualityController] consumes, as [BufferingQualityPlayerAdapter] emits them. */
interface BufferingPlaybackEventSink {
    fun onPlaying()
    fun onWaiting(cause: BufferingWaitCause)
    fun onPause()
    fun onSeek()
    fun onTransportErrorStart()
    fun onTransportErrorEnd()
}

/** Feeds the controller's same-named inputs. */
fun BufferingQualityController.asPlaybackEventSink(): BufferingPlaybackEventSink {
    val controller = this
    return object : BufferingPlaybackEventSink {
        override fun onPlaying() = controller.onPlaying()
        override fun onWaiting(cause: BufferingWaitCause) = controller.onWaiting(cause)
        override fun onPause() = controller.onPause()
        override fun onSeek() = controller.onSeek()
        override fun onTransportErrorStart() = controller.onTransportErrorStart()
        override fun onTransportErrorEnd() = controller.onTransportErrorEnd()
    }
}

/**
 * The one Media3 adapter for the buffering-quality controller, shared by the
 * phone and TV players. Register it as both a [Player.Listener] and an
 * [AnalyticsListener] on the player that renders the plan. The contract is
 * the server repo's `docs/design/2026-10-17-buffering-quality.md` (*Terms*).
 *
 * It keeps its own view of the player from callbacks alone, so [seed] it when
 * it attaches to a player that is already running, and translates that view
 * into the controller's events:
 *
 * - Playback running → [BufferingPlaybackEventSink.onPlaying].
 * - Starved (`STATE_BUFFERING` while `playWhenReady`) →
 *   [BufferingPlaybackEventSink.onWaiting], caused by [BufferingWaitCause.SEEK]
 *   after a seek, [BufferingWaitCause.RELOAD] after [markReload],
 *   [BufferingWaitCause.RENDERER] when more than [RENDERER_WAIT_BUFFER_MS] of
 *   media is buffered past the playhead on a player that does not tunnel,
 *   and otherwise
 *   [BufferingWaitCause.NETWORK]. Both flags clear when playback next runs. A
 *   new plan's first wait is a network wait; the controller applies the
 *   startup grace.
 * - Media3 also buffers when its decoder falls behind and it drops to the
 *   next keyframe, with seconds of media still buffered. The spec's stall is
 *   running out of data, so that wait is [BufferingWaitCause.RENDERER], which
 *   the controller does not count. If the buffer then drains before playback
 *   resumes, [sampleBuffer] re-announces the wait as a network wait, from
 *   which the stall is timed.
 * - `playWhenReady` false, `STATE_ENDED` or `STATE_IDLE` →
 *   [BufferingPlaybackEventSink.onPause].
 * - A seek discontinuity → [BufferingPlaybackEventSink.onSeek]. During a
 *   stall it also re-announces the wait as a seek wait, as [markReload] does
 *   for a reload.
 *
 * Media3 reports one change as several callbacks (state, then
 * `playWhenReady`, then `isPlaying`), so `STATE_READY` with `playWhenReady`
 * but not yet playing keeps the previous phase. Resuming from a stall then
 * reads as waiting → playing, never waiting → paused → playing. A playback
 * suppression (transient audio-focus loss, say) sends nothing.
 *
 * Data arrives when the buffered position advances between [sampleBuffer]
 * calls or a media load completes with bytes; [isReceivingData] and
 * [ownsStartupStall] read it.
 *
 * Transport trouble has two independent sources: failing loads and the
 * device being offline. The sink hears
 * [BufferingPlaybackEventSink.onTransportErrorStart] when either becomes
 * active and [BufferingPlaybackEventSink.onTransportErrorEnd] once both have
 * cleared. Load trouble clears on a completed load, on buffered-position
 * progress, or when playback runs; offline trouble clears only through
 * [onNetworkAvailable]. If playback runs and that clears the last trouble, the
 * end is sent first, so the controller re-times the stall before it ends.
 *
 * Every event it sends is logged, because the controller keeps no log of its
 * own: these lines, with the players' `buffering_quality` plan and replan
 * lines, are how a device log replays its decisions. Waits and resumes also
 * log the media buffered past the playhead and whether the player tunnels, so
 * one line shows why a wait got its cause.
 *
 * Main thread only, as Media3 delivers both listeners' callbacks.
 */
@UnstableApi
class BufferingQualityPlayerAdapter(
    sink: BufferingPlaybackEventSink,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : Player.Listener, AnalyticsListener {

    private val sink: BufferingPlaybackEventSink = sink.logged {
        "buffered_ahead_ms=${bufferedAheadMs()} tunneling=${tunneling()}"
    }

    private enum class Phase { STOPPED, STARVED, RUNNING }

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var isPlaying = false
    private var phase = Phase.STOPPED

    private var seekSinceRun = false
    private var reloadMarked = false

    /** The cause of the wait in progress, while [phase] is [Phase.STARVED]. */
    private var waitCause: BufferingWaitCause? = null

    /** Media buffered past the playhead, read from the attached player. */
    private var bufferedAheadMs: () -> Long = { 0L }

    /**
     * Whether the attached player renders tunneled. A tunneled codec holds
     * samples that `totalBufferedDuration` still counts, and it never falls
     * behind the way a decoder that drops to a keyframe does, so its waits
     * are network waits whatever is buffered.
     */
    private var tunneling: () -> Boolean = { false }

    private var loadTrouble = false
    private var offline = false

    private var lastBufferedPositionMs: Long? = null
    private var lastDataAtMs: Long? = null

    /** Failing media loads or no network: stalls now belong to the reconnect path. */
    val transportTroubleActive: Boolean get() = loadTrouble || offline

    /**
     * The player's state when the adapter attaches to it. Sends what a
     * callback would have. [bufferedAheadMs] reads the player's
     * `totalBufferedDuration`, and [tunneling] its `isTunnelingEnabled`.
     */
    fun seed(
        playWhenReady: Boolean,
        playbackState: Int,
        isPlaying: Boolean,
        tunneling: () -> Boolean = { false },
        bufferedAheadMs: () -> Long = { 0L },
    ) {
        this.bufferedAheadMs = bufferedAheadMs
        this.tunneling = tunneling
        this.playWhenReady = playWhenReady
        this.playbackState = playbackState
        this.isPlaying = isPlaying
        updatePhase()
    }

    /**
     * The caller is reopening the same plan or remounting the player, so the
     * coming wait is a [BufferingWaitCause.RELOAD]. A wait already in progress
     * is re-announced with that cause. Cleared when playback next runs.
     */
    fun markReload() {
        reloadMarked = true
        if (phase == Phase.STARVED) announceWait(BufferingWaitCause.RELOAD)
    }

    /** The default network came or went; feed it `PlaybackNetworkSnapshot.connected`. */
    fun onNetworkAvailable(connected: Boolean) {
        setTrouble(load = loadTrouble, offline = !connected)
    }

    /**
     * One sample from the screen's stall-sampling loop, on the same clock as
     * [clock]. A buffered position that advanced since the last sample is
     * data arriving, and it clears load trouble. A renderer wait whose buffer
     * has drained since it began has become a network wait.
     */
    fun sampleBuffer(nowMs: Long, bufferedPositionMs: Long) {
        val previous = lastBufferedPositionMs
        lastBufferedPositionMs = bufferedPositionMs
        if (previous != null && bufferedPositionMs > previous) {
            lastDataAtMs = nowMs
            setTrouble(load = false, offline = offline)
        }
        if (phase == Phase.STARVED &&
            waitCause == BufferingWaitCause.RENDERER &&
            bufferedAheadMs() <= RENDERER_WAIT_BUFFER_MS
        ) {
            announceWait(BufferingWaitCause.NETWORK)
        }
    }

    /** Media data arrived within the last [RECEIVING_DATA_WINDOW_MS]. */
    fun isReceivingData(nowMs: Long): Boolean {
        val last = lastDataAtMs ?: return false
        return nowMs - last <= RECEIVING_DATA_WINDOW_MS
    }

    /**
     * Whether the buffering-quality controller owns this startup-stall
     * report: the spec's rule that "a slow plan that is still receiving data
     * belongs to this controller". When true, the caller must neither reopen
     * the same plan nor send a failure replan for it; the controller lowers
     * quality if the stalls warrant it. When false (no data recently, failing
     * loads or offline, a non-transport classification, or the controller is
     * off), the existing recovery keeps it.
     */
    fun ownsStartupStall(reason: Playability, nowMs: Long, controllerEnabled: Boolean): Boolean =
        controllerEnabled &&
            reason is Playability.StartupStalled &&
            reason.classification == TRANSPORT_STALL_CLASSIFICATION &&
            isReceivingData(nowMs) &&
            !transportTroubleActive

    /**
     * A new plan or mount. Forgets the seek and reload flags and the
     * data-arrival history; for a same-plan remount call [markReload] after
     * this. Player state and transport trouble are kept, so the controller's
     * view stays in step; [seed] again when the player instance changes.
     *
     * Call it after `BufferingQualityController.onPlanAdopted`: it re-announces
     * the player's current phase to the new plan. A player that was already
     * buffering when the new media item went in makes no state transition, so
     * without this the new plan would never see its first wait, and a slow
     * start would go unnoticed.
     */
    fun reset() {
        seekSinceRun = false
        reloadMarked = false
        lastBufferedPositionMs = null
        lastDataAtMs = null
        when (phase) {
            Phase.STARVED -> announceWait(BufferingWaitCause.NETWORK)
            Phase.RUNNING -> sink.onPlaying()
            Phase.STOPPED -> Unit
        }
    }

    // Player.Listener

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        updatePhase()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        this.playbackState = playbackState
        updatePhase()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        this.playWhenReady = playWhenReady
        updatePhase()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        onDiscontinuity(reason)
    }

    // AnalyticsListener

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        onLoadFailed(wasCanceled)
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        onLoadFinished(
            bytesLoaded = loadEventInfo.bytesLoaded,
            // A playlist refresh or a subtitle segment says nothing about the video arriving.
            carriesMedia = mediaLoadData.dataType == C.DATA_TYPE_MEDIA && mediaLoadData.trackType != C.TRACK_TYPE_TEXT,
        )
    }

    // The callbacks' bodies, reachable from JVM tests without Media3 event objects.

    internal fun onDiscontinuity(reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
        seekSinceRun = true
        // The buffer jumps with the playhead; that is not data arriving.
        lastBufferedPositionMs = null
        sink.onSeek()
        if (phase == Phase.STARVED) announceWait(BufferingWaitCause.SEEK)
    }

    internal fun onLoadFailed(wasCanceled: Boolean) {
        if (wasCanceled) return
        setTrouble(load = true, offline = offline)
    }

    /** Any completed load ends load trouble; only media bytes count as data arriving. */
    internal fun onLoadFinished(bytesLoaded: Long, carriesMedia: Boolean = true) {
        if (carriesMedia && bytesLoaded > 0) lastDataAtMs = clock()
        setTrouble(load = false, offline = offline)
    }

    private fun updatePhase() {
        val next = when {
            isPlaying -> Phase.RUNNING
            playWhenReady && playbackState == Player.STATE_BUFFERING -> Phase.STARVED
            !playWhenReady || playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE -> Phase.STOPPED
            // READY with playWhenReady but not playing: mid-transition, or suppressed.
            else -> phase
        }
        if (next == phase) return
        phase = next
        if (next != Phase.STARVED) waitCause = null
        when (next) {
            Phase.RUNNING -> {
                seekSinceRun = false
                reloadMarked = false
                setTrouble(load = false, offline = offline)
                sink.onPlaying()
            }
            Phase.STARVED -> announceWait(
                when {
                    seekSinceRun -> BufferingWaitCause.SEEK
                    reloadMarked -> BufferingWaitCause.RELOAD
                    !tunneling() && bufferedAheadMs() > RENDERER_WAIT_BUFFER_MS -> BufferingWaitCause.RENDERER
                    else -> BufferingWaitCause.NETWORK
                },
            )
            Phase.STOPPED -> sink.onPause()
        }
    }

    private fun announceWait(cause: BufferingWaitCause) {
        waitCause = cause
        sink.onWaiting(cause)
    }

    private fun setTrouble(load: Boolean, offline: Boolean) {
        val wasActive = transportTroubleActive
        loadTrouble = load
        this.offline = offline
        val active = transportTroubleActive
        if (active && !wasActive) sink.onTransportErrorStart()
        if (!active && wasActive) sink.onTransportErrorEnd()
    }

    companion object {
        /** How recent data must be for a slow plan to count as still receiving it. */
        const val RECEIVING_DATA_WINDOW_MS: Long = 5_000L

        private const val TRANSPORT_STALL_CLASSIFICATION = "transport_stall"
    }
}

private const val TAG = "BufferingQuality"

/** Logs each event; waits and resumes add [detail], the player's buffer and rendering mode. */
private fun BufferingPlaybackEventSink.logged(detail: () -> String): BufferingPlaybackEventSink {
    val sink = this
    fun log(event: String) {
        Log.i(TAG, "buffering_quality event=$event")
    }
    return object : BufferingPlaybackEventSink {
        override fun onPlaying() {
            log("playing ${detail()}")
            sink.onPlaying()
        }

        override fun onWaiting(cause: BufferingWaitCause) {
            log("waiting cause=${cause.name.lowercase()} ${detail()}")
            sink.onWaiting(cause)
        }

        override fun onPause() {
            log("pause")
            sink.onPause()
        }

        override fun onSeek() {
            log("seek")
            sink.onSeek()
        }

        override fun onTransportErrorStart() {
            log("transport_error_start")
            sink.onTransportErrorStart()
        }

        override fun onTransportErrorEnd() {
            log("transport_error_end")
            sink.onTransportErrorEnd()
        }
    }
}
