package org.siloserver.silo.common.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import org.siloserver.silo.domain.player.BufferingQualityController
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.OUTAGE_NO_DATA_MS
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.OUTAGE_NO_DATA_SEGMENTED_MS
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.RENDERER_WAIT_AMPLE_MS
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.RENDERER_WAIT_BUFFER_MS
import org.siloserver.silo.domain.player.BufferingQualityController.Companion.RENDERER_WAIT_MAX_MS
import org.siloserver.silo.domain.player.BufferingWaitCause
import org.siloserver.silo.model.playback.PlaybackDelivery

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
 *   startup grace, or treats it as a source reload when the plan replaced
 *   another.
 * - Media3 also buffers when its decoder falls behind and it drops to the
 *   next keyframe, with seconds of media still buffered. The spec's stall is
 *   running out of data, so that wait is [BufferingWaitCause.RENDERER], which
 *   the controller does not count. If the buffer then drains before playback
 *   resumes, or the wait is still going [RENDERER_WAIT_MAX_MS] after it began
 *   with less buffered than the player needs to resume
 *   ([resumeAfterRebufferMs]), the wait is re-announced as a network wait,
 *   from which the stall is timed: a decoder hiccup on a link too slow to
 *   rebuild that amount soon is the network's stall. Any other wait, a
 *   start's included, becomes a renderer wait once more than
 *   [RENDERER_WAIT_AMPLE_MS] is buffered: the data is there and the player
 *   still does not run, so its own start and decoder failure handling owns
 *   it. The adapter checks every [WAIT_CHECK_MS] while a wait lasts, and at
 *   each [sampleBuffer].
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
 * suppression (a transient audio-focus loss, say) is a stop, as a pause is.
 *
 * Data arrives when the buffered position advances between [sampleBuffer]
 * calls or a media load completes with bytes; [isReceivingData] and
 * [ownsStartupStall] read it.
 *
 * Transport trouble has two independent sources: failing loads and the
 * device being offline. A load fails when it errors, and also when the player
 * is waiting and loading but no media request has received a byte for
 * [OUTAGE_NO_DATA_MS] ([lastMediaByteAtMs]), counted from the last byte or
 * the latest media request or plan start, whichever is later. On segmented
 * delivery the limit is [OUTAGE_NO_DATA_SEGMENTED_MS], because the server
 * holds a segment request silent while it produces the segment. That catches
 * a link that went silent long before the HTTP client's read timeout; a
 * request that keeps receiving, however slowly, never fails. The sink hears
 * [BufferingPlaybackEventSink.onTransportErrorStart] when either becomes
 * active and [BufferingPlaybackEventSink.onTransportErrorEnd] once both have
 * cleared. Load trouble clears only when the plan's media data arrives again
 * (a media load that completes with bytes, or buffered-position progress) or
 * when playback runs; a playlist or subtitle load that succeeds clears
 * nothing. Offline trouble lasts while the device is offline, and coming back
 * online turns it into load trouble. If playback runs and that clears the last
 * trouble, the end is sent first, so the controller re-times the stall before
 * it ends.
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
    /** Runs an action on the main thread after a delay; returns its cancel. */
    private val schedule: (delayMs: Long, action: () -> Unit) -> () -> Unit = ::scheduleOnMainThread,
    /**
     * When any of the player's media requests last received a byte, on
     * [clock]'s timeline: [PlaybackThroughputMeter.lastByteAtMs]. Any thread.
     */
    private val lastMediaByteAtMs: () -> Long? = { null },
    /** Media3's `bufferForPlaybackAfterRebufferMs`: what a wait must rebuild before playback resumes. */
    private val resumeAfterRebufferMs: Long = PlaybackBufferPolicy.REBUFFER_MS.toLong(),
    /** [BufferingQualityController.loadingReplacement]: the controller does not time this plan's waits. */
    private val loadingReplacement: () -> Boolean = { false },
) : Player.Listener, AnalyticsListener {

    private val sink: BufferingPlaybackEventSink = sink.logged {
        "buffered_ahead_ms=${bufferedAheadMs()} tunneling=${tunneling()}"
    }

    private enum class Phase { STOPPED, STARVED, RUNNING }

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var isPlaying = false

    /** Playback is suppressed, such as by a transient audio-focus loss: stopped, as if paused. */
    private var suppressed = false
    private var phase = Phase.STOPPED

    private var seekSinceRun = false
    private var reloadMarked = false

    /** The cause of the wait in progress, while [phase] is [Phase.STARVED]. */
    private var waitCause: BufferingWaitCause? = null

    /** When the renderer wait in progress began. */
    private var rendererWaitStartedAt = 0L

    /** Cancels the pending check of the wait in progress. */
    private var cancelWaitCheck: (() -> Unit)? = null

    /** The player is loading media, so its requests can go silent. */
    private var loading = false

    /**
     * The earliest moment a silence can be timed from: the plan's start or
     * its latest media request, which cannot have received anything before
     * it was sent.
     */
    private var silenceFloorMs: Long = clock()

    /** How long the plan's media requests may stay silent during a wait before it is an outage. */
    private var outageNoDataMs: Long = OUTAGE_NO_DATA_MS

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
        suppressed: Boolean = false,
        loading: Boolean = false,
        bufferedAheadMs: () -> Long = { 0L },
    ) {
        this.bufferedAheadMs = bufferedAheadMs
        this.tunneling = tunneling
        noteLoading(loading)
        this.playWhenReady = playWhenReady
        this.playbackState = playbackState
        this.isPlaying = isPlaying
        this.suppressed = suppressed
        updatePhase()
    }

    /**
     * Listens to [player], both its state and its loads, seeded with where it
     * already is. Returns the detach.
     */
    fun attach(player: ExoPlayer): () -> Unit {
        player.addListener(this)
        player.addAnalyticsListener(this)
        seed(
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState,
            isPlaying = player.isPlaying,
            bufferedAheadMs = { player.totalBufferedDuration },
            tunneling = { player.isTunnelingEnabled },
            suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            loading = player.isLoading,
        )
        return {
            player.removeListener(this)
            player.removeAnalyticsListener(this)
        }
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

    /**
     * The default network came or went; feed it `PlaybackNetworkSnapshot.connected`.
     * Coming back is not the plan's data arriving, so the outage carries on as
     * load trouble until it does or playback runs.
     */
    fun onNetworkAvailable(connected: Boolean) {
        setTrouble(load = loadTrouble || (connected && offline), offline = !connected)
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
        checkRendererDrain()
    }

    /** Media data arrived within the last [RECEIVING_DATA_WINDOW_MS]. */
    fun isReceivingData(nowMs: Long): Boolean {
        val last = lastDataAtMs ?: return false
        return nowMs - last <= RECEIVING_DATA_WINDOW_MS
    }

    /**
     * Whether this startup-stall report is not the stall fallback's to act
     * on. A slow plan that is still receiving data belongs to the
     * buffering-quality controller, which lowers quality if the stalls
     * warrant it, unless it is a replacement still loading, whose waits the
     * controller does not time: the fallback's start deadline lowers that
     * one. During an outage (failing or silent loads, or offline) the
     * reconnect path owns it: Media3's own retries reopen the requests, and
     * a player error waits for the network before reopening the route. When
     * true, the caller must neither reopen the same plan nor send a failure
     * replan for it. When false (no data recently and no outage, a
     * non-transport classification, or the controller is off), the existing
     * recovery keeps it.
     */
    fun ownsStartupStall(reason: Playability, nowMs: Long, controllerEnabled: Boolean): Boolean =
        controllerEnabled &&
            reason is Playability.StartupStalled &&
            reason.classification == TRANSPORT_STALL_CLASSIFICATION &&
            (transportTroubleActive || (isReceivingData(nowMs) && !loadingReplacement()))

    /**
     * A new plan or mount, delivered as [delivery]. Forgets the seek and
     * reload flags and the data-arrival history; for a same-plan remount call
     * [markReload] after this. Transport trouble ends too: a new plan's
     * transport starts with no outage. Player state is kept, so the
     * controller's view stays in step; [seed] again when the player instance
     * changes.
     *
     * Call it after `BufferingQualityController.onPlanAdopted`: it re-announces
     * the player's current phase to the new plan. A player that was already
     * buffering when the new media item went in makes no state transition, so
     * without this the new plan would never see its first wait, and a slow
     * start would go unnoticed.
     */
    fun reset(delivery: PlaybackDelivery?) {
        val segmented =
            delivery == PlaybackDelivery.SERVER_REMUX_HLS || delivery == PlaybackDelivery.SERVER_TRANSCODE_HLS
        outageNoDataMs = if (segmented) OUTAGE_NO_DATA_SEGMENTED_MS else OUTAGE_NO_DATA_MS
        seekSinceRun = false
        reloadMarked = false
        lastBufferedPositionMs = null
        lastDataAtMs = null
        silenceFloorMs = clock()
        setTrouble(load = false, offline = false)
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

    override fun onIsLoadingChanged(isLoading: Boolean) {
        noteLoading(isLoading)
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        suppressed = playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
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

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        if (mediaLoadData.trackType != C.TRACK_TYPE_TEXT) onMediaRequestStarted()
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        onLoadFailed(wasCanceled, subtitle = mediaLoadData.trackType == C.TRACK_TYPE_TEXT)
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // A subtitle segment says nothing about the video's transport, either way.
        if (mediaLoadData.trackType == C.TRACK_TYPE_TEXT) return
        onLoadFinished(
            bytesLoaded = loadEventInfo.bytesLoaded,
            // A playlist refresh says nothing about the video arriving.
            carriesMedia = mediaLoadData.dataType == C.DATA_TYPE_MEDIA,
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

    /** A load that begins after none was running starts its silence now. */
    internal fun noteLoading(isLoading: Boolean) {
        if (isLoading && !loading) silenceFloorMs = clock()
        loading = isLoading
    }

    /** A new media request has received nothing yet, so its silence starts now. */
    internal fun onMediaRequestStarted() {
        silenceFloorMs = clock()
    }

    /** A failing subtitle load says nothing about the video's transport. */
    internal fun onLoadFailed(wasCanceled: Boolean, subtitle: Boolean = false) {
        if (wasCanceled || subtitle) return
        setTrouble(load = true, offline = offline)
    }

    /**
     * A completed media load with bytes is the plan's data arriving, which
     * ends load trouble. A playlist refresh or an empty load ends nothing.
     */
    internal fun onLoadFinished(bytesLoaded: Long, carriesMedia: Boolean = true) {
        if (!carriesMedia || bytesLoaded <= 0) return
        lastDataAtMs = clock()
        setTrouble(load = false, offline = offline)
    }

    private fun updatePhase() {
        val next = when {
            isPlaying -> Phase.RUNNING
            suppressed -> Phase.STOPPED
            playWhenReady && playbackState == Player.STATE_BUFFERING -> Phase.STARVED
            !playWhenReady || playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE -> Phase.STOPPED
            // READY with playWhenReady but not playing: mid-transition.
            else -> phase
        }
        if (next == phase) return
        phase = next
        if (next != Phase.STARVED) {
            waitCause = null
            cancelWaitCheck?.invoke()
            cancelWaitCheck = null
        }
        when (next) {
            Phase.RUNNING -> {
                seekSinceRun = false
                reloadMarked = false
                setTrouble(load = false, offline = offline)
                sink.onPlaying()
            }
            Phase.STARVED -> {
                announceWait(
                    when {
                        seekSinceRun -> BufferingWaitCause.SEEK
                        reloadMarked -> BufferingWaitCause.RELOAD
                        !tunneling() && bufferedAheadMs() > RENDERER_WAIT_BUFFER_MS -> BufferingWaitCause.RENDERER
                        else -> BufferingWaitCause.NETWORK
                    },
                )
                // A wait that begins on a link already silent is an outage at once.
                checkSilence()
                scheduleWaitCheck()
            }
            Phase.STOPPED -> sink.onPause()
        }
    }

    private fun announceWait(cause: BufferingWaitCause) {
        waitCause = cause
        if (cause == BufferingWaitCause.RENDERER) rendererWaitStartedAt = clock()
        sink.onWaiting(cause)
    }

    /**
     * A renderer wait whose buffer has drained, or that has lasted
     * [RENDERER_WAIT_MAX_MS] still short of what playback needs to resume,
     * has become a network wait; any other wait with more than
     * [RENDERER_WAIT_AMPLE_MS] buffered has become a renderer wait.
     */
    private fun checkRendererDrain() {
        if (phase != Phase.STARVED) return
        if (waitCause != BufferingWaitCause.RENDERER) {
            // Ample media buffered and still waiting: not the network, whatever began the wait.
            if (!tunneling() && bufferedAheadMs() > RENDERER_WAIT_AMPLE_MS) announceWait(BufferingWaitCause.RENDERER)
            return
        }
        val buffered = bufferedAheadMs()
        val drained = buffered <= RENDERER_WAIT_BUFFER_MS
        val stuck = clock() - rendererWaitStartedAt >= RENDERER_WAIT_MAX_MS && buffered < resumeAfterRebufferMs
        if (drained || stuck) announceWait(BufferingWaitCause.NETWORK)
    }

    /** The player is waiting and loading, and no media request has received a byte for [outageNoDataMs]. */
    private fun checkSilence() {
        if (phase != Phase.STARVED || !loading || loadTrouble) return
        val silentSince = maxOf(silenceFloorMs, lastDataAtMs ?: Long.MIN_VALUE, lastMediaByteAtMs() ?: Long.MIN_VALUE)
        if (clock() - silentSince >= outageNoDataMs) setTrouble(load = true, offline = offline)
    }

    /** Watches the wait in progress until it ends. */
    private fun scheduleWaitCheck() {
        cancelWaitCheck?.invoke()
        cancelWaitCheck = schedule(WAIT_CHECK_MS) {
            cancelWaitCheck = null
            checkRendererDrain()
            checkSilence()
            if (phase == Phase.STARVED) scheduleWaitCheck()
        }
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

        /** How often a wait checks whether its buffer has drained or its link gone silent. */
        const val WAIT_CHECK_MS: Long = 200L

        private const val TRANSPORT_STALL_CLASSIFICATION = "transport_stall"
    }
}

private const val TAG = "BufferingQuality"

private val mainThread by lazy { Handler(Looper.getMainLooper()) }

private fun scheduleOnMainThread(delayMs: Long, action: () -> Unit): () -> Unit {
    val runnable = Runnable(action)
    mainThread.postDelayed(runnable, delayMs)
    return { mainThread.removeCallbacks(runnable) }
}

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
