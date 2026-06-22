package com.continuum.app.common.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.continuum.app.common.BuildConfig
import com.continuum.app.common.player.backend.MpvDeviceFloor
import com.continuum.app.common.player.backend.PlaybackBackendFallback
import com.continuum.app.common.player.backend.PlaybackEngineCommand
import com.continuum.app.common.player.backend.PlaybackEngineDecision
import com.continuum.app.common.player.backend.VideoPlaybackBackendKind
import com.continuum.app.common.player.backend.VideoPlaybackBackendRequest
import com.continuum.app.common.player.backend.VideoPlaybackBackendSelector
import com.continuum.app.common.player.audio.DelayAudioProcessor
import com.continuum.app.common.player.subtitle.SubtitleOffsetHolder
import com.continuum.app.common.settings.PlayerSettingsStore
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified playback service for phone + TV. Owns the single [Player] for the
 * process and exposes it through a [MediaSession] so both UIs can drive the
 * player via a `MediaController` — which is what gives us lock-screen controls,
 * Assistant "pause"/"play", headset buttons, and (on TV) a session entry in
 * `dumpsys media_session`.
 *
 * Keep this class free of UI-layer state; the surface towards the UI is the
 * `Player` / `MediaSession` contract plus the [positionMs] flow below.
 */
@UnstableApi
class ContinuumPlaybackService : MediaSessionService() {

    companion object {
        /**
         * Debug-only counter so we can assert "exactly one playback player per
         * process" in tests / logcat. Read via adb logcat on tag [TAG].
         */
        private val playerInstanceCount = AtomicInteger(0)
        private const val TAG = "ContinuumPlayback"
        private const val POSITION_TICK_MS = 500L
    }

    private val playerFactory: ContinuumPlayerFactory by inject()
    private val activePlayerHolder: ActivePlayerHolder by inject()
    private val analyticsListener: PlaybackAnalyticsListener by inject()
    private val playerSettingsStore: PlayerSettingsStore by inject()
    private val delayProcessor: DelayAudioProcessor by inject()
    private val subtitleOffsetHolder: SubtitleOffsetHolder by inject()

    private var mediaSession: MediaSession? = null
    private lateinit var scope: CoroutineScope
    private var positionJob: Job? = null
    private var audioSyncJob: Job? = null
    private var subtitleSyncJob: Job? = null

    // The engine actually bound to the session. The background sync jobs read this
    // (not a closure-captured local) so they survive an engine swap. @Volatile
    // because it's set under the swap mutex but read by the jobs.
    @Volatile private var activePlayer: Player? = null
    private var currentEngineKind: VideoPlaybackBackendKind = VideoPlaybackBackendKind.Media3
    // Serializes engine swaps: two quick SET_ENGINE commands must not both capture
    // the same old player and double-release / leak (Codex review req #1).
    private val engineSwitchMutex = Mutex()
    // A just-built engine player that hasn't yet been bound to the session. The
    // off-main NonCancellable MPV build stashes it under [pendingLock] (or releases
    // it immediately if the service was already destroyed), and onDestroy sweeps it
    // under the same lock — so a teardown that races the native build can't orphan
    // the player (Codex reviews #4 + final). Guarded by [pendingLock], not just
    // @Volatile, because the stash-or-release decision must be atomic vs onDestroy.
    private val pendingLock = Any()
    private var pendingBuiltPlayer: Player? = null
    @Volatile private var serviceDestroyed = false
    // The just-swapped-out player. NOT released immediately on swap: the UI binds
    // its PlayerView surface directly to the session player and re-binds to the new
    // player asynchronously (recomposition), so releasing the old one synchronously
    // would let PlayerView call into a released player and crash. Held and released
    // on the next swap (UI long re-bound by then) or on destroy.
    private var previousPlayer: Player? = null

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Current player position in ms, ticked every [POSITION_TICK_MS]. UI
     * layers subscribe via `collectAsStateWithLifecycle()` rather than
     * polling `currentPosition` on every recomposition — that keeps
     * render-driven coroutines out of the hot path and matches how the
     * service's own lifecycle bounds the flow.
     */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val player = createPlaybackPlayer()
        if (player is ExoPlayer) {
            player.addAnalyticsListener(analyticsListener)
        }
        activePlayer = player
        currentEngineKind = VideoPlaybackBackendKind.Media3
        activePlayerHolder.set(player)
        val count = playerInstanceCount.incrementAndGet()
        android.util.Log.i(
            TAG,
            "Playback player created (${player::class.java.simpleName}); live instance count = $count",
        )
        // Triage aid: when diagnosing TRANSCODE vs DIRECT decisions we want
        // the logcat to show the player's FFmpeg-audio mode alongside the
        // analytics listener's `onAudioDecoderInitialized` callback, which
        // reports the specific decoder the renderer selected.
        android.util.Log.i(
            TAG,
            "FFmpeg audio preferred = ${BuildConfig.FFMPEG_AUDIO_ENABLED}, " +
                "extension on classpath = ${FfmpegAudioSupport.isAvailable()}",
        )

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(EngineSwitchCallback())
            .build()

        positionJob = scope.launch {
            while (isActive) {
                _positionMs.value = activePlayer?.currentPosition ?: 0L
                delay(POSITION_TICK_MS)
            }
        }

        // Mirror the per-profile AudioSyncMs preference into the active
        // DelayAudioProcessor. The processor only acts on its pending
        // value at the next flush, so when playback is active we force one
        // via a no-op seekTo(currentPosition) — that's the cheapest way to
        // re-engage the audio pipeline without resetting the renderer.
        audioSyncJob = scope.launch {
            playerSettingsStore.audioSyncMsFlow
                .distinctUntilChanged()
                .collect { delayMs ->
                    val previous = delayProcessor.getActiveDelayMs()
                    delayProcessor.setDelayMs(delayMs)
                    val p = activePlayer
                    if (previous != delayMs && p != null && p.isPlaying) {
                        p.seekTo(p.currentPosition)
                    }
                }
        }

        // Mirror the per-profile SubtitleSyncMs preference into the active
        // SubtitleOffsetHolder. OffsetSubtitleParserFactory reads the holder
        // on every parse. Sidecar subtitles are commonly parsed up front, so
        // changing the holder alone cannot retime already-built cue timestamps.
        // Reprepare the current item at the same position so the parser rebuilds
        // cues with the new offset while preserving play/pause intent.
        subtitleSyncJob = scope.launch {
            playerSettingsStore.subtitleSyncMsFlow
                .distinctUntilChanged()
                .collect { offsetMs ->
                    val previous = subtitleOffsetHolder.getOffsetMs()
                    subtitleOffsetHolder.setOffsetMs(offsetMs)
                    val p = activePlayer
                    if (previous != offsetMs && p != null) {
                        reparseCurrentMediaItemAtCurrentPosition(p, offsetMs)
                    }
                }
        }
    }

    private fun reparseCurrentMediaItemAtCurrentPosition(player: Player, offsetMs: Int) {
        if (player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return

        val currentItem = player.getMediaItemAt(currentIndex)
        val hasConfiguredSubtitles =
            currentItem.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true
        val hasTextTracks = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (!hasConfiguredSubtitles && !hasTextTracks) return

        val mediaItems = (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index) }
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady

        player.setMediaItems(mediaItems, currentIndex, positionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
        android.util.Log.i(TAG, "Reprepared media item to apply subtitle sync: ${offsetMs}ms")
    }

    private fun createPlaybackPlayer(): Player =
        playerFactory.createPlayer()

    /**
     * Accepts [PlaybackEngineCommand.SET_ENGINE]. The UI mount path sends it once
     * the media's playMethod/container/subtitle info is known so the right engine
     * owns the session. Decode failures return RESULT_ERROR_BAD_VALUE; the result
     * future completes only after the swap finishes (so a caller can await it and
     * failures aren't masked) — Codex review reqs #2, #7.
     */
    private inner class EngineSwitchCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackEngineCommand.SET_ENGINE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != PlaybackEngineCommand.SET_ENGINE) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            val payload = args.getString(PlaybackEngineCommand.ARG_REQUEST_JSON)
            val request = payload?.let { runCatching { PlaybackEngineCommand.decode(it) }.getOrNull() }
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            val future = SettableFuture.create<SessionResult>()
            scope.launch {
                try {
                    val outcome = switchEngine(request)
                    // Report error when the requested switch could not be honored
                    // (kept the current engine), so the result isn't a false success.
                    val code = if (outcome == SwitchOutcome.FAILED) {
                        SessionResult.RESULT_ERROR_UNKNOWN
                    } else {
                        SessionResult.RESULT_SUCCESS
                    }
                    // Tell the UI whether the session player was actually swapped, so
                    // it can re-attach the PlayerView (re-pushing the video Surface to
                    // the new engine). Only SWITCHED requires the re-attach.
                    val extras = Bundle().apply {
                        putBoolean(PlaybackEngineCommand.RESULT_SWAPPED, outcome == SwitchOutcome.SWITCHED)
                    }
                    future.set(SessionResult(code, extras))
                } catch (c: CancellationException) {
                    future.setException(c)
                    throw c
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "switchEngine failed", t)
                    future.set(SessionResult(SessionResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }
    }

    /**
     * Resolve the request (device floor + selector); if the chosen engine differs
     * from the bound one, build it (ExoPlayer on the app thread; MPV's heavy native
     * init off-main since [com.continuum.app.common.player.mpv.MpvPlayer] pins its
     * own threading to the main looper), transfer full playback state, rebind the
     * session player, and release the old one. Serialized via [engineSwitchMutex]
     * so concurrent commands can't double-release/leak (Codex reqs #1, #3, #5, #6).
     */
    private enum class SwitchOutcome { NO_CHANGE, SWITCHED, FAILED }

    private suspend fun switchEngine(request: VideoPlaybackBackendRequest): SwitchOutcome = engineSwitchMutex.withLock {
        val session = mediaSession ?: return@withLock SwitchOutcome.FAILED
        val resolved = request.copy(
            mpvSupportedOnDevice = MpvDeviceFloor.isMpvSupported(
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            ),
        )
        val selected = VideoPlaybackBackendSelector.select(resolved)
        if (selected == currentEngineKind) {
            logDecision(resolved, selected, currentEngineKind)
            return@withLock SwitchOutcome.NO_CHANGE
        }

        try {
            // Build the new engine. ExoPlayer on the application (main) thread; MPV's
            // heavy native init off-main under NonCancellable. The handle is recorded
            // in pendingBuiltPlayer (off-main, before any cancellable resume) so a
            // teardown can't orphan it — onDestroy sweeps pendingBuiltPlayer.
            val built: Player? = try {
                when (selected) {
                    VideoPlaybackBackendKind.Media3 -> stashPending(createPlaybackPlayer())
                    VideoPlaybackBackendKind.Mpv ->
                        withContext(NonCancellable + Dispatchers.Default) {
                            // Stash-or-release atomically: if the service was destroyed
                            // during this off-main build, release here instead of orphaning.
                            stashPending(playerFactory.createMpvPlayer())
                        }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Engine build failed for $selected: ${t.message}")
                null
            }

            // The session may have been torn down while MPV built off-main; if so,
            // release the freshly-built player rather than binding to a dead session.
            if (mediaSession !== session) {
                built?.let { runCatching { it.release() } }
                return@withLock SwitchOutcome.FAILED
            }

            if (built != null) {
                val bound = bindNewPlayer(session, built, selected = selected, actual = selected, request = resolved)
                return@withLock if (bound) SwitchOutcome.SWITCHED else SwitchOutcome.FAILED
            }

            // Build failed. Fall back only when a step exists and the fallback engine
            // isn't already current (don't rebuild Media3 when it's already bound).
            val step = PlaybackBackendFallback.onStartFailure(selected, "build-failed")
            if (step == null || step.fallbackTo == currentEngineKind) {
                android.util.Log.w(TAG, "Keeping current engine ($currentEngineKind) after failed build of $selected")
                logDecision(resolved, selected, currentEngineKind)
                return@withLock SwitchOutcome.FAILED
            }
            val fallback = try {
                stashPending(createPlaybackPlayer()) ?: return@withLock SwitchOutcome.FAILED
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Terminal fallback (Media3) build failed; keeping current engine", t)
                return@withLock SwitchOutcome.FAILED
            }
            val bound = bindNewPlayer(session, fallback, selected = selected, actual = step.fallbackTo, request = resolved)
            return@withLock if (bound) SwitchOutcome.SWITCHED else SwitchOutcome.FAILED
        } finally {
            // The player is now either the session's player (bound) or released;
            // clear the pending handle so onDestroy doesn't double-release it.
            clearPending()
        }
    }

    /**
     * Atomically stash a freshly-built player as [pendingBuiltPlayer], or release it
     * immediately and return null if the service was destroyed during the build.
     * Shared by the off-main MPV build and the on-main ExoPlayer build so a teardown
     * racing the build never orphans a player (Codex final review #1).
     */
    private fun stashPending(player: Player): Player? = synchronized(pendingLock) {
        if (serviceDestroyed) {
            runCatching { player.release() }
            null
        } else {
            pendingBuiltPlayer = player
            player
        }
    }

    private fun clearPending() = synchronized(pendingLock) { pendingBuiltPlayer = null }

    /**
     * Transfer state to [newPlayer], rebind the session, release the old player.
     * If the transfer/rebind throws, release the new player and keep the old bound
     * (Codex req #6 — no half-swapped state, no leak). Returns true if bound.
     */
    private fun bindNewPlayer(
        session: MediaSession,
        newPlayer: Player,
        selected: VideoPlaybackBackendKind,
        actual: VideoPlaybackBackendKind,
        request: VideoPlaybackBackendRequest,
    ): Boolean {
        val old = session.player
        try {
            transferPlaybackState(from = old, to = newPlayer)
            if (newPlayer is ExoPlayer) {
                newPlayer.addAnalyticsListener(analyticsListener)
            }
            session.player = newPlayer
            activePlayer = newPlayer
            currentEngineKind = actual
            // Publish so the UI re-binds its PlayerView surface to the new engine.
            activePlayerHolder.set(newPlayer)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Engine bind failed; releasing new player, keeping old", t)
            runCatching { newPlayer.release() }
            return false
        }
        // The old player may still hold a surface until Compose re-binds the
        // PlayerView. Stop it now so it cannot keep fetching or rendering after
        // the session has moved to the new engine; release remains deferred below
        // so stale UI references do not call into a released player.
        runCatching {
            old.playWhenReady = false
            old.stop()
            old.clearMediaItems()
        }
        // Defer releasing the old player: the UI re-binds its PlayerView off it
        // asynchronously. Release the previous-previous now (long detached); hold
        // this one until the next swap or destroy.
        previousPlayer?.let { runCatching { it.release() } }
        previousPlayer = old
        logDecision(request, selected, actual)
        return true
    }

    /** Carry full playback state across a swap (Codex req #4). */
    private fun transferPlaybackState(from: Player, to: Player) {
        runCatching { to.trackSelectionParameters = from.trackSelectionParameters }
        runCatching { to.playbackParameters = from.playbackParameters }
        runCatching { to.volume = from.volume }
        runCatching { to.repeatMode = from.repeatMode }
        runCatching { to.shuffleModeEnabled = from.shuffleModeEnabled }
        val count = from.mediaItemCount
        if (count == 0) return
        val items = (0 until count).map { from.getMediaItemAt(it) }
        val index = from.currentMediaItemIndex.coerceIn(0, count - 1)
        val positionMs = from.currentPosition.coerceAtLeast(0L)
        val playWhenReady = from.playWhenReady
        to.setMediaItems(items, index, positionMs)
        to.prepare()
        to.playWhenReady = playWhenReady
    }

    private fun logDecision(
        request: VideoPlaybackBackendRequest,
        selected: VideoPlaybackBackendKind,
        actual: VideoPlaybackBackendKind,
    ) {
        android.util.Log.i(TAG, PlaybackEngineDecision.from(request, selected, actual).toLogLine())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Continuum is a video player — when the user swipes the app away
        // we want playback (and audio) to end, not continue in the
        // background like a music app. Always tear down regardless of
        // playWhenReady. The previous "only stop when paused" check was
        // the Media3 music-app default and left audio orphaned.
        mediaSession?.player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        // Mark destroyed + sweep any in-flight built player under the same lock the
        // off-main build uses, so a build completing during/after teardown either
        // sees serviceDestroyed and self-releases, or is released here — never orphaned.
        synchronized(pendingLock) {
            serviceDestroyed = true
            pendingBuiltPlayer?.let { runCatching { it.release() } }
            pendingBuiltPlayer = null
        }
        positionJob?.cancel()
        audioSyncJob?.cancel()
        subtitleSyncJob?.cancel()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        activePlayer = null
        activePlayerHolder.set(null)
        previousPlayer?.let { runCatching { it.release() } }
        previousPlayer = null
        val count = playerInstanceCount.decrementAndGet()
        android.util.Log.i(TAG, "Playback player released; live instance count = $count")
        super.onDestroy()
    }
}
