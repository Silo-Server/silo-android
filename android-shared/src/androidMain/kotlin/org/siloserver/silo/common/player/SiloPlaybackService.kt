package org.siloserver.silo.common.player

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.siloserver.silo.common.BuildConfig
import org.siloserver.silo.common.player.audio.DelayAudioProcessor
import org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder
import org.siloserver.silo.common.settings.PlayerSettingsStore
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
class SiloPlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_PIP_PLAY = "org.siloserver.silo.common.player.action.PIP_PLAY"
        const val ACTION_PIP_PAUSE = "org.siloserver.silo.common.player.action.PIP_PAUSE"
        const val ACTION_PIP_SKIP_BACK = "org.siloserver.silo.common.player.action.PIP_SKIP_BACK"
        const val ACTION_PIP_SKIP_FORWARD = "org.siloserver.silo.common.player.action.PIP_SKIP_FORWARD"

        /**
         * Debug-only counter so we can assert "exactly one playback player per
         * process" in tests / logcat. Read via adb logcat on tag [TAG].
         */
        private val playerInstanceCount = AtomicInteger(0)
        private const val TAG = "SiloPlayback"
        private const val POSITION_TICK_MS = 500L
        private const val PIP_SKIP_BACK_MS = 10_000L
        private const val PIP_SKIP_FORWARD_MS = 30_000L
        private val PIP_ACTIONS = setOf(
            ACTION_PIP_PLAY,
            ACTION_PIP_PAUSE,
            ACTION_PIP_SKIP_BACK,
            ACTION_PIP_SKIP_FORWARD,
        )

        internal fun dispatchPictureInPictureAction(intent: Intent?, player: Player?): Boolean {
            val action = intent?.action
            if (intent == null || action !in PIP_ACTIONS) return false
            if (!PipActionCapability.isAuthorized(intent)) return true
            if (player == null) return true

            when (action) {
                ACTION_PIP_PLAY -> {
                    player.playWhenReady = true
                    player.play()
                }
                ACTION_PIP_PAUSE -> player.pause()
                ACTION_PIP_SKIP_BACK -> player.seekTo(
                    (player.currentPosition - PIP_SKIP_BACK_MS).coerceAtLeast(0L),
                )
                ACTION_PIP_SKIP_FORWARD -> {
                    val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    player.seekTo(
                        (player.currentPosition + PIP_SKIP_FORWARD_MS).coerceAtMost(duration),
                    )
                }
            }
            return true
        }
    }

    private val playerFactory: SiloPlayerFactory by inject()
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

    // The sole Media3 player owned by this service.
    @Volatile private var activePlayer: Player? = null

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

        mediaSession = MediaSession.Builder(this, player).build()

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

        // Mirror the per-device SubtitleSyncMs preference into the active
        // SubtitleOffsetHolder. The libass renderer reads this value live;
        // Media3 text sidecars are commonly parsed up front, so changing the
        // holder alone cannot retime their already-built cue timestamps.
        // Reprepare at the same position to rebuild those cues while preserving
        // play/pause intent (the libass clock remains continuous across it).
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (dispatchPictureInPictureAction(intent, activePlayer ?: mediaSession?.player)) {
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Silo is a video player — when the user swipes the app away
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
        positionJob?.cancel()
        audioSyncJob?.cancel()
        subtitleSyncJob?.cancel()
        scope.cancel()
        mediaSession?.run {
            playerFactory.releasePlayer(player)
            release()
        }
        mediaSession = null
        activePlayer = null
        activePlayerHolder.set(null)
        val count = playerInstanceCount.decrementAndGet()
        android.util.Log.i(TAG, "Playback player released; live instance count = $count")
        super.onDestroy()
    }
}
