package org.siloserver.silo.common.player

import android.content.Intent
import android.os.Bundle
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

        /**
         * True when [connectionHints] belong to the synthetic caller Media3
         * fabricates for a media-button event that started the service
         * (`MediaSessionService.createFallbackMediaButtonCaller`). It is not a
         * real controller — it exists only so [onGetSession] can accept or
         * refuse being cold-started by a transport key.
         */
        internal fun isMediaButtonFallbackCaller(connectionHints: Bundle): Boolean =
            connectionHints.getString(
                MediaSessionService.CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE,
            ) == Intent.ACTION_MEDIA_BUTTON

        /**
         * Pure decision shared by both start-path guards below: the service has
         * nothing it could possibly act on when no media is queued, it is not
         * already running as a foreground playback service, and no controller is
         * connected to it. That combination only occurs when something outside
         * the app cold-started us — a remote key or a stale PendingIntent — and
         * it is the state in which the service must terminate rather than sit
         * idle waiting for a `startForeground()` that will never come.
         */
        internal fun hasNothingToServe(
            queuedMediaItemCount: Int,
            isPlaybackOngoing: Boolean,
            connectedControllerCount: Int,
        ): Boolean =
            queuedMediaItemCount == 0 && !isPlaybackOngoing && connectedControllerCount == 0
    }

    private val playerFactory: SiloPlayerFactory by inject()
    private val activePlayerHolder: ActivePlayerHolder by inject()
    private val analyticsListener: PlaybackAnalyticsListener by inject()
    private val playerSettingsStore: PlayerSettingsStore by inject()
    private val delayProcessor: DelayAudioProcessor by inject()
    private val subtitleOffsetHolder: SubtitleOffsetHolder by inject()

    private var mediaSession: MediaSession? = null
    private var mediaSessionBitmapLoader: SiloMediaSessionBitmapLoader? = null
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
        val ffmpegAvailable = FfmpegAudioSupport.isAvailable()
        val ffmpegCodecs = if (ffmpegAvailable) {
            FfmpegAudioSupport.supportedCodecShortCodes().joinToString(",")
        } else {
            "none"
        }
        android.util.Log.i(
            TAG,
            "FFmpeg audio enabled = ${BuildConfig.FFMPEG_AUDIO_ENABLED}, " +
                "native extension available = $ffmpegAvailable, " +
                "verified codecs = $ffmpegCodecs",
        )

        val bitmapLoader = SiloMediaSessionBitmapLoader(this)
        mediaSessionBitmapLoader = bitmapLoader
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(bitmapLoader)
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

    /**
     * Live read of [hasNothingToServe] against the service's current state.
     * `isPlaybackOngoing()` is Media3's own "am I a running foreground playback
     * service" flag, so this is false for every state reached through normal
     * playback.
     */
    private fun hasNothingToServeNow(): Boolean = hasNothingToServe(
        queuedMediaItemCount = (activePlayer ?: mediaSession?.player)?.mediaItemCount ?: 0,
        isPlaybackOngoing = isPlaybackOngoing(),
        connectedControllerCount = mediaSession?.connectedControllers?.size ?: 0,
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Because our manifest advertises `MediaSessionService.SERVICE_INTERFACE`
        // and declares no MediaButtonReceiver, Media3 registers this service as
        // the session's media-button target using
        // `PendingIntent.getForegroundService()` (MediaSessionLegacyStub). A
        // transport key on a TV remote is therefore delivered by the system as
        // `startForegroundService(SiloPlaybackService)` even when our process is
        // cold — which arms the platform's 10-second "call startForeground() or
        // be killed" watchdog (ActiveServices.SERVICE_START_FOREGROUND_TIMEOUT).
        //
        // Media3 already guards that case, but only through this method: when
        // onGetSession() refuses the synthetic media-button caller it runs its
        // own `stopSelfSafely()`, which posts a throwaway foreground
        // notification, immediately drops it again and stops the service. That
        // is the only shutdown sequence that is legal for a
        // startForegroundService() launch — a bare stopSelf() would be killed by
        // the same watchdog.
        //
        // We used to return the session unconditionally, so that guard never
        // ran. The freshly built player was idle with an empty timeline,
        // MediaNotificationManager.shouldShowNotification() bails out on an empty
        // timeline, nothing ever called startForeground(), and ten seconds later
        // the watchdog killed the whole app with RemoteServiceException —
        // observed twice on a Shield (API 30, Media3 1.10.1) while the user was
        // pressing remote keys on the sign-in screen.
        //
        // Refusing here is safe for real playback: once a session has been added
        // to the service Media3 resolves media buttons via getSessionByUri() and
        // never calls onGetSession() at all, so this branch is unreachable while
        // anything is playing. Silo also implements no
        // `MediaSession.Callback.onPlaybackResumption`, so a cold transport key
        // has genuinely nothing to resume — declining it costs no product
        // behaviour. Every other caller (the phone/TV player screens, system and
        // Assistant controllers) still gets the session unconditionally.
        if (isMediaButtonFallbackCaller(controllerInfo.connectionHints) && hasNothingToServeNow()) {
            android.util.Log.i(
                TAG,
                "Declining media-button cold start: nothing queued to play",
            )
            return null
        }
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (dispatchPictureInPictureAction(intent, activePlayer ?: mediaSession?.player)) {
            // PiP transport actions reach us through `PendingIntent.getService()`
            // (SiloPictureInPictureCoordinator), i.e. a plain startService(), so
            // unlike the media-button PendingIntent above this branch does not arm
            // the start-foreground watchdog and returning without calling
            // startForeground() cannot crash us. Keep it that way: switching that
            // PendingIntent to getForegroundService() would make this path fatal in
            // exactly the way onGetSession() documents.
            //
            // It can still cold-start the process from a PiP window that outlived
            // us. PipActionCapability regenerates its token per process, so a stale
            // intent fails authorisation, dispatch is refused, and we would be left
            // running an idle service and player forever. Terminate instead.
            // pauseAllPlayersAndStopSelf() is Media3's own termination path and is
            // documented as safe only while playback is not ongoing, which is
            // precisely what hasNothingToServeNow() establishes.
            if (hasNothingToServeNow()) {
                android.util.Log.i(
                    TAG,
                    "Stopping service after unusable PiP action: nothing queued to play",
                )
                pauseAllPlayersAndStopSelf()
            }
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
        mediaSessionBitmapLoader?.close()
        mediaSessionBitmapLoader = null
        activePlayer = null
        activePlayerHolder.set(null)
        val count = playerInstanceCount.decrementAndGet()
        android.util.Log.i(TAG, "Playback player released; live instance count = $count")
        super.onDestroy()
    }
}
