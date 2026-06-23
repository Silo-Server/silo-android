package com.continuum.app.common.player.mpv

import android.content.Context
import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Size
import androidx.media3.common.util.Util
import dev.jdtech.mpv.MPVLib
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@androidx.media3.common.util.UnstableApi
class MpvPlayer(
    context: Context,
    private val audioAttributes: AudioAttributes = AudioAttributes.DEFAULT,
    private val handleAudioFocus: Boolean = true,
    private var trackSelectionParameters: TrackSelectionParameters =
        TrackSelectionParameters.DEFAULT,
    private val seekBackIncrement: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS,
    private val seekForwardIncrement: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
    private val pauseAtEndOfMediaItems: Boolean = false,
    private val videoOutput: String = "gpu-next",
    private val audioOutput: String = "aaudio",
    private val hwDec: String = "mediacodec-copy",
    private val bufferSizeMb: Int = 64,
    private val httpHeaderFieldsProvider: () -> List<Pair<String, String>> = { emptyList() },
) : BasePlayer(), MPVLib.EventObserver, AudioManager.OnAudioFocusChangeListener {

    val mpv: MPVLib
    private val audioManager: AudioManager by lazy { context.getSystemService()!! }
    private var audioFocusCallback: () -> Unit = {}
    private lateinit var audioFocusRequest: AudioFocusRequestCompat
    private val handler = Handler(context.mainLooper)
    private var currentSurface: Surface? = null
    private var currentSurfaceHolder: SurfaceHolder? = null
    private var currentTextureView: TextureView? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var currentTextureSurface: Surface? = null

    private constructor(
        builder: Builder
    ) : this(
        context = builder.context,
        audioAttributes = builder.audioAttributes,
        handleAudioFocus = builder.handleAudioFocus,
        trackSelectionParameters = builder.trackSelectionParameters,
        seekBackIncrement = builder.seekBackIncrementMs,
        seekForwardIncrement = builder.seekForwardIncrementMs,
        pauseAtEndOfMediaItems = builder.pauseAtEndOfMediaItems,
        videoOutput = builder.videoOutput,
        audioOutput = builder.audioOutput,
        hwDec = builder.hwDec,
        bufferSizeMb = builder.bufferSizeMb,
        httpHeaderFieldsProvider = builder.httpHeaderFieldsProvider,
    )

    class Builder(val context: Context) {
        var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
            private set

        var handleAudioFocus: Boolean = true
            private set

        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
            private set

        var seekBackIncrementMs: Long = C.DEFAULT_SEEK_BACK_INCREMENT_MS
            private set

        var seekForwardIncrementMs: Long = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS
            private set

        var pauseAtEndOfMediaItems: Boolean = false
            private set

        var videoOutput: String = "gpu-next"
            private set

        var audioOutput: String = "aaudio"
            private set

        var hwDec: String = "mediacodec-copy"
            private set

        var bufferSizeMb: Int = 64
            private set

        var httpHeaderFieldsProvider: () -> List<Pair<String, String>> = { emptyList() }
            private set

        fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) =
            apply {
                this.audioAttributes = audioAttributes
                this.handleAudioFocus = handleAudioFocus
            }

        fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters) =
            apply {
                this.trackSelectionParameters = trackSelectionParameters
            }

        fun setSeekBackIncrementMs(seekBackIncrementMs: Long) = apply {
            this.seekBackIncrementMs = seekBackIncrementMs
        }

        fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long) = apply {
            this.seekForwardIncrementMs = seekForwardIncrementMs
        }

        fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean) = apply {
            this.pauseAtEndOfMediaItems = pauseAtEndOfMediaItems
        }

        fun setVideoOutput(videoOutput: String) = apply { this.videoOutput = videoOutput }

        fun setAudioOutput(audioOutput: String) = apply { this.audioOutput = audioOutput }

        fun setHwDec(hwDec: String) = apply { this.hwDec = hwDec }

        fun setBufferSizeMb(sizeMb: Int) = apply { this.bufferSizeMb = sizeMb }

        fun setHttpHeaderFieldsProvider(
            httpHeaderFieldsProvider: () -> List<Pair<String, String>>,
        ) = apply {
            this.httpHeaderFieldsProvider = httpHeaderFieldsProvider
        }

        // startObserving() is called only after the MpvPlayer is fully constructed,
        // so mpv property events (which dispatch through `listeners`) can never fire
        // before every field is initialized — safe for off-main construction.
        fun build() = MpvPlayer(this).apply { startObserving() }
    }

    init {
        val configDir = File(context.filesDir, "mpv")
        val cacheDir = File(context.cacheDir, "mpv")

        setupDirectories(context, configDir, cacheDir)

        mpv = MPVLib.create(context) ?: throw IllegalStateException("MPVLib.create() returned null")

        Log.d(TAG, "MpvPlayer init: vo=$videoOutput, ao=$audioOutput, hwdec=$hwDec")

        mpv.setOptionString("config", "yes")
        mpv.setOptionString("config-dir", configDir.path)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) mpv.setOptionString(
            opt,
            cacheDir.path,
        )
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("msg-level", "all=info")
        mpv.setOptionString("vo", videoOutput)
        mpv.setOptionString("ao", audioOutput)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        mpv.setOptionString("vid", "no")
        mpv.setOptionString("osc", "no")
        mpv.setOptionString("osd-level", "0")
        mpv.setOptionString("osd-bar", "no")

        mpv.setOptionString("target-colorspace-hint", "yes")

        mpv.setOptionString("hwdec", hwDec)
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

        mpv.setOptionString("tls-verify", "yes")
        writeAndroidCaBundle(configDir)?.let { caBundle ->
            mpv.setOptionString("tls-ca-file", caBundle.path)
            Log.i(TAG, "MPV TLS CA bundle: ${caBundle.path}")
        }

        // Modest in-memory buffering, matching the working findroid/Wholphin MPV
        // configs. The previous config used cache-on-disk + cache-secs/
        // demuxer-readahead-secs=36000 (a 10-hour readahead with continuous disk
        // writes), which starves the render thread and causes the aimagereader
        // frame-timeout stutter. Let mpv use default readahead with a bounded buffer.
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("cache-pause-initial", "yes")
        mpv.setOptionString("demuxer-max-bytes", "${bufferSizeMb}MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "32MiB")

        mpv.setOptionString("sub-scale-with-window", "yes")
        mpv.setOptionString("sub-use-margins", "no")

        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("keep-open", "always")
        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("ytdl", "no")
        mpv.setOptionString("audio-set-media-role", "yes")

        mpv.init()

        mpv.setPropertyString("demuxer-max-bytes", "${bufferSizeMb}MiB")
        mpv.setOptionString("sub-auto", "exact")
        mpv.setOptionString("sub-visibility", "yes")

        val audioSessionId = audioManager.generateAudioSessionId()
        if (audioSessionId != AudioManager.ERROR) {
            mpv.setPropertyInt("audiotrack-session-id", audioSessionId)
            mpv.setPropertyInt("aaudio-session-id", audioSessionId)
        }

        // NOTE: mpv.addObserver(this) + observeProperty() are intentionally NOT
        // called here. observeProperty emits each property's current value
        // immediately, which dispatches eventProperty -> handler.post(main) ->
        // listeners.sendEvent. When this player is constructed OFF the main thread
        // (the service builds MPV on Dispatchers.Default to avoid an ANR), that
        // posted runnable can race the still-running constructor and touch
        // `listeners` (declared after this init block) before it is assigned -> NPE.
        // Registering observers only after construction (via startObserving(),
        // called by Builder.build()) guarantees every field is initialized first.

        if (handleAudioFocus) {
            audioFocusRequest =
                AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
            if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                mpv.setPropertyBoolean("pause", true)
            }
        }
    }

    /**
     * Register the mpv property observers. Called by [Builder.build] AFTER the
     * MpvPlayer is fully constructed, never from the `init` block — observeProperty
     * emits each property's current value immediately, and those events dispatch
     * through `listeners`, which is only assigned after the init block. Registering
     * here avoids an init-order NPE when the player is built off the main thread.
     */
    private fun startObserving() {
        mpv.addObserver(this)
        arrayOf(
            Property("track-list", MPVLib.MpvFormat.MPV_FORMAT_STRING),
            Property("paused", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("seekable", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
            Property("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
            Property("playlist-count", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            Property("playlist-current-pos", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            Property("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            Property("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64),
            Property("video-params/gamma", MPVLib.MpvFormat.MPV_FORMAT_STRING),
            Property("video-params/primaries", MPVLib.MpvFormat.MPV_FORMAT_STRING),
        ).forEach { (name, format) -> mpv.observeProperty(name, format) }
    }

    private fun setupDirectories(context: Context, configDir: File, cacheDir: File) {
        Log.i(TAG, "mpv config dir: $configDir, cache dir: $cacheDir")
        if (!configDir.exists()) configDir.mkdirs()
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val mpvConf = File(configDir, "mpv.conf")
        if (!mpvConf.exists()) {
            try {
                context.assets
                    .open("mpv.conf", AssetManager.ACCESS_STREAMING)
                    .copyTo(FileOutputStream(mpvConf))
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy mpv.conf: ${e.message}")
            }
        }

        writeFontsConf(context, configDir)
    }

    private fun writeFontsConf(context: Context, configDir: File) {
        val configFile = File(configDir, "fonts.conf")
        if (configFile.exists()) return

        val fontcacheDir = File(context.cacheDir, "fontconfig")
        if (!fontcacheDir.exists()) fontcacheDir.mkdirs()

        val config =
            """
            <fontconfig>
                <dir>/system/fonts/</dir>
                <dir>/product/fonts/</dir>

                <cachedir>${fontcacheDir.path}</cachedir>

                <alias>
                    <family>serif</family>
                    <prefer><family>Noto Serif</family></prefer>
                </alias>

                <alias>
                    <family>sans-serif</family>
                    <prefer>
                        <family>Roboto</family>
                        <family>Noto Sans</family>
                    </prefer>
                </alias>

                <alias>
                    <family>monospace</family>
                    <prefer><family>Droid Sans Mono</family></prefer>
                </alias>

            </fontconfig>
        """
                .trimIndent()

        try {
            configFile.writeText(config)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write fonts.conf: $e")
        }
    }

    private fun writeAndroidCaBundle(configDir: File): File? {
        val caBundleFile = File(configDir, "android-ca-bundle.pem")
        return runCatching {
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm(),
            )
            trustManagerFactory.init(null as KeyStore?)
            val certificates = trustManagerFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .flatMap { it.acceptedIssuers.asIterable() }
                .distinctBy { certificate ->
                    certificate.subjectX500Principal.name to certificate.serialNumber
                }

            if (certificates.isEmpty()) {
                Log.w(TAG, "Android trust manager returned no CA certificates for MPV")
                null
            } else {
                caBundleFile.outputStream().bufferedWriter(Charsets.US_ASCII).use { writer ->
                    certificates.forEach { certificate ->
                        writer.appendLine("-----BEGIN CERTIFICATE-----")
                        Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
                            .chunked(64)
                            .forEach { line -> writer.appendLine(line) }
                        writer.appendLine("-----END CERTIFICATE-----")
                    }
                }
                caBundleFile
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to write Android CA bundle for MPV", e)
        }.getOrNull()
    }

    private val listeners: ListenerSet<Player.Listener> =
        ListenerSet(context.mainLooper, Clock.DEFAULT) { listener: Player.Listener, flags: FlagSet
            ->
            listener.onEvents(this, Player.Events(flags))
        }
    private val videoListeners = CopyOnWriteArraySet<Player.Listener>()

    @Volatile private var isReleased = false

    private var internalMediaItems = mutableListOf<MediaItem>()

    @Player.State private var playbackState: Int = STATE_IDLE
    private var currentPlayWhenReady: Boolean = false
    private var currentPlayerError: PlaybackException? = null

    @Player.RepeatMode private val repeatMode: Int = REPEAT_MODE_OFF
    private var currentTracks: Tracks = Tracks.EMPTY
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var passthroughEnabled: Boolean = false

    private var isPlayerReady: Boolean = false
    private var isSeekable: Boolean = false
    private var currentMediaItemIndex: Int = 0
    private var currentPositionMs: Long? = null
    private var currentDurationMs: Long? = null
    private var currentCacheDurationMs: Long? = null
    private var initialCommands = mutableListOf<Array<String>>()
    private var initialIndex: Int = 0
    private var initialSeekTo: Long = 0L
    private var awaitingInitialLoad: Boolean = false
    /**
     * True between issuing a seamless playlist auto-advance and the next item
     * becoming ready. The advance re-arms [awaitingInitialLoad]; without this
     * flag the END_FILE for the just-finished item (which mpv delivers AFTER we
     * prepared the next one) would trip the initial-open failure guard and raise
     * a spurious fatal error mid-queue. Consumed by that one expected END_FILE.
     */
    private var seamlessAdvanceInFlight: Boolean = false
    private var oldMediaItem: MediaItem? = null
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0
    // Content video frame rate (from mpv's demux-fps). Used to hint the Android
    // compositor via Surface.setFrameRate so MPV playback is paced to the source
    // rate — ExoPlayer does this automatically; mpv's generic Android vo does not,
    // which is why MPV playback judders even on a 120Hz panel.
    @Volatile private var videoFrameRate: Float = 0f

    override fun eventProperty(property: String) {}

    override fun eventProperty(property: String, value: String) {
        handler.post {
            if (isReleased) return@post
            when (property) {
                "track-list" -> {
                    val newTracks = getTracks(value)
                    currentTracks = newTracks
                    // Capture the content video frame rate and hint the Android
                    // compositor (Surface.setFrameRate) so MPV playback is paced to
                    // the source rate, matching what ExoPlayer does.
                    val videoFps = newTracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                        ?.let { group ->
                            (0 until group.length)
                                .map { group.getTrackFormat(it).frameRate }
                                .firstOrNull { it > 0f }
                        }
                    if (videoFps != null && videoFps != videoFrameRate) {
                        videoFrameRate = videoFps
                        applySurfaceFrameRate()
                    }
                    listeners.sendEvent(EVENT_TRACKS_CHANGED) { listener ->
                        listener.onTracksChanged(currentTracks)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        handler.post {
            if (isReleased) return@post
            when (property) {
                "paused" -> {
                    if (isPlayerReady) {
                        setPlayerStateAndNotifyIfChanged(
                            playWhenReady = !value,
                            playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                        )
                    }
                }

                "eof-reached" -> {
                    if (value && isPlayerReady) {
                        if (currentMediaItemIndex < (internalMediaItems.size - 1)) {
                            if (pauseAtEndOfMediaItems) {
                                setPlayerStateAndNotifyIfChanged(
                                    playWhenReady = false,
                                    playWhenReadyChangeReason =
                                        PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                    playbackState = STATE_READY,
                                )
                            } else {
                                prepareMediaItem(currentMediaItemIndex + 1)
                                // Mark the advance AFTER prepareMediaItem (whose
                                // resetInternalState clears the flag) so the
                                // upcoming END_FILE for the finished item isn't
                                // mistaken for an initial-open failure.
                                seamlessAdvanceInFlight = true
                                playWhenReady = true
                            }
                        } else {
                            setPlayerStateAndNotifyIfChanged(
                                playWhenReady = false,
                                playWhenReadyChangeReason =
                                    PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM,
                                playbackState = STATE_ENDED,
                            )
                            resetInternalState()
                        }
                    }
                }

                "paused-for-cache" -> {
                    if (isPlayerReady) {
                        if (value) {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                        } else {
                            setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                        }
                    }
                }

                "seekable" -> {
                    isSeekable = value
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        handler.post {
            if (isReleased) return@post
            when (property) {
                "playlist-count" -> {
                    if (!isPlayerReady && value > 0) {
                        listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                            listener.onTimelineChanged(
                                currentTimeline,
                                TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                            )
                        }
                    }
                }

                "playlist-current-pos" -> {
                    if (value < 0) return@post
                    currentMediaItemIndex = value.toInt()
                    val newMediaItem = currentMediaItem
                    if (oldMediaItem?.mediaId != newMediaItem?.mediaId) {
                        oldMediaItem = newMediaItem
                        listeners.sendEvent(EVENT_MEDIA_ITEM_TRANSITION) { listener ->
                            listener.onMediaItemTransition(
                                newMediaItem,
                                MEDIA_ITEM_TRANSITION_REASON_AUTO,
                            )
                        }
                    }
                }

                "video-params/w" -> {
                    currentVideoWidth = value.toInt()
                    notifyVideoSizeChangedIfReady()
                }

                "video-params/h" -> {
                    currentVideoHeight = value.toInt()
                    notifyVideoSizeChangedIfReady()
                }
            }
        }
    }

    private fun notifyVideoSizeChangedIfReady() {
        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            val newSize = VideoSize(currentVideoWidth, currentVideoHeight)
            listeners.sendEvent(EVENT_VIDEO_SIZE_CHANGED) { listener ->
                listener.onVideoSizeChanged(newSize)
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        handler.post {
            if (isReleased) return@post
            when (property) {
                "time-pos" -> {
                    if (playbackState != STATE_BUFFERING) {
                        currentPositionMs = secondsToMillis(value)
                    }
                }

                "duration" -> {
                    val durationMs = secondsToMillis(value)
                    if (durationMs > 0) {
                        currentDurationMs = durationMs
                        listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                            listener.onTimelineChanged(
                                currentTimeline,
                                TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
                            )
                        }
                    }
                }

                "demuxer-cache-time" -> {
                    currentCacheDurationMs = (value * 1000).toLong()
                }

                "speed" -> {
                    playbackParameters = playbackParameters.withSpeed(value.toFloat())
                    listeners.sendEvent(EVENT_PLAYBACK_PARAMETERS_CHANGED) { listener ->
                        listener.onPlaybackParametersChanged(playbackParameters)
                    }
                }
            }
        }
    }

    private fun secondsToMillis(seconds: Double): Long =
        if (seconds.isFinite() && seconds >= 0.0) {
            (seconds * C.MILLIS_PER_SECOND).toLong()
        } else {
            0L
        }

    override fun event(eventId: Int) {
        handler.post {
            if (isReleased) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    if (!isPlayerReady) {
                        for (command in initialCommands) {
                            mpv.command(command)
                        }
                    }
                }

                MPVLib.MpvEvent.MPV_EVENT_SEEK -> {
                    setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
                }

                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    awaitingInitialLoad = false
                    currentPlayerError = null
                    if (!isPlayerReady) {
                        isPlayerReady = true
                        applyPendingInitialSeek()
                        setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                        if (playWhenReady) {
                            Log.d(TAG, "Starting playback...")
                            mpv.setPropertyBoolean("pause", false)
                        }
                        for (videoListener in videoListeners) {
                            videoListener.onRenderedFirstFrame()
                        }
                    } else {
                        setPlayerStateAndNotifyIfChanged(playbackState = STATE_READY)
                    }
                }

                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (awaitingInitialLoad && !isPlayerReady) {
                        if (seamlessAdvanceInFlight) {
                            // Expected END_FILE for the item we just advanced past
                            // during a seamless playlist transition — not an open
                            // failure. Consume the suppression; a genuine failure to
                            // open the NEXT item will arrive with the flag cleared.
                            seamlessAdvanceInFlight = false
                        } else {
                            failPlaybackOpen()
                        }
                    }
                }
            }
        }
    }

    private fun failPlaybackOpen() {
        awaitingInitialLoad = false
        isPlayerReady = false
        val error = PlaybackException(
            "MPV could not open the playback stream. Check the server certificate and network connection.",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
        currentPlayerError = error
        setPlayerStateAndNotifyIfChanged(
            playWhenReady = false,
            playbackState = STATE_IDLE,
        )
        listeners.sendEvent(EVENT_PLAYER_ERROR) { listener ->
            listener.onPlayerError(error)
        }
    }

    private fun setPlayerStateAndNotifyIfChanged(
        playWhenReady: Boolean = getPlayWhenReady(),
        @Player.PlayWhenReadyChangeReason
        playWhenReadyChangeReason: Int = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
        @Player.State playbackState: Int = getPlaybackState(),
    ) {
        var playerStateChanged = false
        val wasPlaying = isPlaying
        if (playbackState != getPlaybackState()) {
            this.playbackState = playbackState
            listeners.queueEvent(EVENT_PLAYBACK_STATE_CHANGED) { listener ->
                listener.onPlaybackStateChanged(playbackState)
            }
            playerStateChanged = true
        }
        if (playWhenReady != getPlayWhenReady()) {
            this.currentPlayWhenReady = playWhenReady
            listeners.queueEvent(EVENT_PLAY_WHEN_READY_CHANGED) { listener ->
                listener.onPlayWhenReadyChanged(playWhenReady, playWhenReadyChangeReason)
            }
            playerStateChanged = true
        }
        if (playerStateChanged) {
            listeners.queueEvent(C.INDEX_UNSET) { listener ->
                listener.onPlaybackStateChanged(playbackState)
            }
        }
        if (wasPlaying != isPlaying) {
            listeners.queueEvent(EVENT_IS_PLAYING_CHANGED) { listener ->
                listener.onIsPlayingChanged(isPlaying)
            }
        }
        listeners.flushEvents()
    }

    private val timeline: Timeline =
        object : Timeline() {
            override fun getWindowCount(): Int = internalMediaItems.size

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long,
            ): Window {
                val currentMediaItem =
                    internalMediaItems.getOrNull(windowIndex) ?: MediaItem.Builder().build()
                val durationUs = Util.msToUs(currentDurationMs ?: C.TIME_UNSET)
                return window.set(
                    windowIndex,
                    currentMediaItem,
                    null,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    isSeekable,
                    false,
                    null,
                    0L,
                    durationUs,
                    windowIndex,
                    windowIndex,
                    0L,
                )
            }

            override fun getPeriodCount(): Int = internalMediaItems.size

            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                val durationUs = Util.msToUs(currentDurationMs ?: C.TIME_UNSET)
                return period.set(null, null, periodIndex, durationUs, 0L)
            }

            override fun getIndexOfPeriod(uid: Any): Int = C.INDEX_UNSET

            override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex as Any
        }

    override fun getApplicationLooper(): Looper = Looper.getMainLooper()

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        videoListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        videoListeners.remove(listener)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        mpv.command(arrayOf("playlist-clear"))
        mpv.command(arrayOf("playlist-remove", "current"))
        internalMediaItems = mediaItems
        awaitingInitialLoad = false
        currentPlayerError = null
        if (resetPosition) {
            initialSeekTo = 0L
            currentPositionMs = 0L
        }
    }

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        mpv.command(arrayOf("playlist-clear"))
        mpv.command(arrayOf("playlist-remove", "current"))
        internalMediaItems = mediaItems
        awaitingInitialLoad = false
        currentPlayerError = null
        initialIndex = startIndex
        initialSeekTo = startPositionMs.coerceAtLeast(0L)
        currentPositionMs = initialSeekTo
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        internalMediaItems.addAll(index, mediaItems)
        applyHttpHeaderFields()
        mediaItems.forEach { mediaItem ->
            mpv.command(
                arrayOf(
                    "loadfile",
                    "${mediaItem.localConfiguration?.uri}",
                    "insert-at",
                    index.toString(),
                )
            )
        }
    }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {}

    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) {}

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex > internalMediaItems.size || fromIndex >= toIndex) return

        if (fromIndex == 0 && toIndex == internalMediaItems.size) {
            mpv.command(arrayOf("playlist-clear"))
            internalMediaItems.clear()
            currentMediaItemIndex = 0
            oldMediaItem = null
            awaitingInitialLoad = false
            currentPlayerError = null
            resetInternalState()
            listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
                listener.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
            return
        }

        for (index in (toIndex - 1) downTo fromIndex) {
            internalMediaItems.removeAt(index)
            mpv.command(arrayOf("playlist-remove", index.toString()))
        }
        currentMediaItemIndex = currentMediaItemIndex.coerceAtMost(
            (internalMediaItems.size - 1).coerceAtLeast(0),
        )
        listeners.sendEvent(EVENT_TIMELINE_CHANGED) { listener ->
            listener.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        }
    }

    override fun prepare() {
        applyHttpHeaderFields()
        internalMediaItems.forEachIndexed { index, mediaItem ->
            mpv.command(
                arrayOf(
                    "loadfile",
                    "${mediaItem.localConfiguration?.uri}",
                    if (index == 0) "replace" else "append",
                )
            )
        }

        internalMediaItems.getOrNull(initialIndex)?.let { mediaItem ->
            resetInternalState()
            currentPlayerError = null
            awaitingInitialLoad = true
            mediaItem.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                initialCommands.add(
                    arrayOf("sub-add",
                        "${subtitle.uri}",
                        "auto",
                        "${subtitle.label}",
                        "${subtitle.language}",
                    )
                )
            }
            if (initialIndex > 0) {
                mpv.command(arrayOf("playlist-play-index", "$initialIndex"))
            }
            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
        }
    }

    private fun applyHttpHeaderFields() {
        val headerString = httpHeaderFieldsProvider()
            .filter { (name, value) ->
                name.isNotBlank() &&
                    value.isNotBlank() &&
                    !name.containsHeaderSeparator() &&
                    !value.containsHeaderSeparator() &&
                    ':' !in name
            }
            .joinToString("\r\n") { (name, value) -> "$name: $value" }
        mpv.setPropertyString("http-header-fields", headerString)
    }

    private fun String.containsHeaderSeparator(): Boolean =
        any { it == '\r' || it == '\n' }

    private fun prepareMediaItem(index: Int) {
        internalMediaItems.getOrNull(index)?.let { mediaItem ->
            resetInternalState()
            currentPlayerError = null
            awaitingInitialLoad = true
            mediaItem.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                initialCommands.add(
                    arrayOf("sub-add",
                        "${subtitle.uri}",
                        "auto",
                        "${subtitle.label}",
                        "${subtitle.language}",
                    )
                )
            }
            if (currentMediaItemIndex != index) {
                mpv.command(arrayOf("playlist-play-index", "$index"))
            }
            setPlayerStateAndNotifyIfChanged(playbackState = STATE_BUFFERING)
        }
    }

    override fun getPlaybackState(): Int = playbackState

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = currentPlayerError

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (currentPlayWhenReady != playWhenReady) {
            setPlayerStateAndNotifyIfChanged(
                playWhenReady = playWhenReady,
                playWhenReadyChangeReason = PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            if (isPlayerReady) {
                if (handleAudioFocus && playWhenReady) {
                    val res = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
                    if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        mpv.setPropertyBoolean("pause", true)
                    } else {
                        mpv.setPropertyBoolean("pause", false)
                    }
                } else {
                    mpv.setPropertyBoolean("pause", !playWhenReady)
                }
            }
        }
    }

    override fun getPlayWhenReady(): Boolean = currentPlayWhenReady

    override fun setRepeatMode(repeatMode: Int) {}

    override fun getRepeatMode(): Int = repeatMode

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {}

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = playbackState == STATE_BUFFERING

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
        isRepeatingCurrentItem: Boolean,
    ) {
        if (mediaItemIndex == currentMediaItemIndex) {
            val targetMs =
                if (positionMs != C.TIME_UNSET) {
                    positionMs.coerceAtLeast(0L)
                } else if (initialSeekTo > 0L) {
                    initialSeekTo
                } else {
                    currentPositionMs ?: 0L
                }

            initialSeekTo =
                if (isPlayerReady) {
                    if (positionMs == C.TIME_UNSET && initialSeekTo <= 0L) {
                        currentPositionMs = targetMs
                        return
                    }
                    performSeek(targetMs)
                    currentPositionMs = targetMs
                    0L
                } else {
                    currentPositionMs = targetMs
                    Log.d(TAG, "MPV not ready, storing initial seek: $targetMs ms")
                    targetMs
                }
        } else {
            prepareMediaItem(mediaItemIndex)
            play()
        }
    }

    private fun applyPendingInitialSeek() {
        if (initialSeekTo <= 0L) return

        val targetMs = initialSeekTo
        performSeek(targetMs)
        currentPositionMs = targetMs
        initialSeekTo = 0L
    }

    private fun performSeek(targetMs: Long) {
        mpv.command(arrayOf("seek", "${targetMs.toDouble().div(C.MILLIS_PER_SECOND)}", "absolute"))
        Log.d(TAG, "MPV seeking to $targetMs ms")
    }

    override fun getSeekBackIncrement(): Long = seekBackIncrement

    override fun getSeekForwardIncrement(): Long = seekForwardIncrement

    override fun getMaxSeekToPreviousPosition(): Long = 0

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val requestedSpeed = playbackParameters.speed.toDouble()
        if (passthroughEnabled && requestedSpeed != 1.0) {
            Log.w(TAG, "Blocking MPV speed=$requestedSpeed while audio passthrough is enabled")
            this.playbackParameters = this.playbackParameters.withSpeed(1.0f)
            mpv.setPropertyDouble("speed", 1.0)
            return
        }
        this.playbackParameters = playbackParameters
        mpv.setPropertyDouble("speed", requestedSpeed)
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun stop() {
        if (isReleased) return
        mpv.command(arrayOf("stop"))
        isPlayerReady = false
        isSeekable = false
        awaitingInitialLoad = false
        currentPlayerError = null
        initialSeekTo = 0L
        currentPositionMs = 0L
        currentDurationMs = null
        currentCacheDurationMs = null
        currentTracks = Tracks.EMPTY
        initialCommands.clear()
        setPlayerStateAndNotifyIfChanged(playbackState = STATE_IDLE)
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        handler.removeCallbacksAndMessages(null)
        if (handleAudioFocus) {
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
        }
        mpv.removeObserver(this)
        mpv.destroy()
    }

    override fun getCurrentTracks(): Tracks = currentTracks

    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackSelectionParameters

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelectionParameters = parameters

        val disabledTrackTypes =
            parameters.disabledTrackTypes.map { MpvTrackType.fromMedia3TrackType(it) }

        val notOverriddenTypes =
            mutableSetOf(MpvTrackType.VIDEO, MpvTrackType.AUDIO, MpvTrackType.SUBTITLE)
        for (override in parameters.overrides) {
            val trackType = MpvTrackType.fromMedia3TrackType(override.key.type)
            notOverriddenTypes.remove(trackType)
            val id = override.key.getFormat(0).id ?: continue

            selectTrack(trackType, id)
        }
        for (notOverriddenType in notOverriddenTypes) {
            if (notOverriddenType in disabledTrackTypes) {
                selectTrack(notOverriddenType, "no")
            } else {
                selectTrack(notOverriddenType, "auto")
            }
        }

        listeners.sendEvent(EVENT_TRACK_SELECTION_PARAMETERS_CHANGED) { listener ->
            listener.onTrackSelectionParametersChanged(parameters)
        }
    }

    private fun selectTrack(trackType: MpvTrackType, id: String) {
        mpv.setPropertyString(trackType.propertyName, id)
    }

    override fun getMediaMetadata(): MediaMetadata =
        currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = MediaMetadata.EMPTY

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {}

    override fun getCurrentTimeline(): Timeline = timeline

    override fun getCurrentPeriodIndex(): Int = currentMediaItemIndex

    override fun getCurrentMediaItemIndex(): Int = currentMediaItemIndex

    override fun getDuration(): Long = currentDurationMs ?: C.TIME_UNSET

    override fun getCurrentPosition(): Long =
        (currentPositionMs ?: initialSeekTo).coerceAtLeast(0L)

    override fun getBufferedPosition(): Long =
        (currentCacheDurationMs ?: getCurrentPosition()).coerceAtMost(duration)

    override fun getTotalBufferedDuration(): Long =
        (bufferedPosition - currentPosition).coerceAtLeast(0)

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = C.INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = C.INDEX_UNSET

    override fun getContentPosition(): Long = currentPosition

    override fun getContentBufferedPosition(): Long = bufferedPosition

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setVolume(audioVolume: Float) {
        mpv.setPropertyInt("volume", (audioVolume * 100).toInt())
    }

    override fun getVolume(): Float = (mpv.getPropertyInt("volume") ?: 0) / 100F

    override fun clearVideoSurface() {
        detachVideoSurface(currentSurface)
    }

    override fun clearVideoSurface(surface: Surface?) {
        detachVideoSurface(surface)
    }

    override fun setVideoSurface(surface: Surface?) {
        attachVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (currentSurfaceHolder == surfaceHolder) {
            surfaceHolder?.surface?.takeIf { it.isValid }?.let { surface ->
                attachVideoSurface(surface)
                updateVideoSurfaceSize(surfaceHolder.surfaceFrame.width(), surfaceHolder.surfaceFrame.height())
            }
            return
        }

        currentSurfaceHolder?.removeCallback(surfaceCallback)
        currentSurfaceHolder = surfaceHolder
        surfaceHolder?.addCallback(surfaceCallback)

        val surface = surfaceHolder?.surface
        if (surface != null && surface.isValid) {
            attachVideoSurface(surface)
            updateVideoSurfaceSize(surfaceHolder.surfaceFrame.width(), surfaceHolder.surfaceFrame.height())
        } else if (surfaceHolder == null) {
            detachVideoSurface(currentSurface)
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder != null && currentSurfaceHolder != surfaceHolder) return

        currentSurfaceHolder?.removeCallback(surfaceCallback)
        currentSurfaceHolder = null
        detachVideoSurface(currentSurface)
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        setVideoSurfaceHolder(surfaceView?.holder)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        clearVideoSurfaceHolder(surfaceView?.holder)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        if (currentTextureView == textureView) {
            if (textureView?.isAvailable == true) {
                attachTextureSurface(textureView.surfaceTexture, textureView.width, textureView.height)
            }
            return
        }

        clearVideoTextureView(currentTextureView)
        currentTextureView = textureView
        textureView?.surfaceTextureListener = textureSurfaceListener
        if (textureView?.isAvailable == true) {
            attachTextureSurface(textureView.surfaceTexture, textureView.width, textureView.height)
        }
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (textureView != null && currentTextureView != textureView) return

        currentTextureView?.surfaceTextureListener = null
        currentTextureView = null
        releaseTextureSurface(currentTextureSurface)
    }

    override fun getVideoSize(): VideoSize {
        val width = mpv.getPropertyInt("width")
        val height = mpv.getPropertyInt("height")
        if (width == null || height == null) return VideoSize.UNKNOWN
        return VideoSize(width, height)
    }

    override fun getSurfaceSize(): Size {
        val mpvSize = (mpv.getPropertyString("android-surface-size") ?: "").split("x")
        return try {
            Size(mpvSize[0].toInt(), mpvSize[1].toInt())
        } catch (_: IndexOutOfBoundsException) {
            Size.UNKNOWN
        }
    }

    override fun getCurrentCues(): CueGroup = CueGroup(emptyList(), 0)

    override fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
            .setMinVolume(0)
            .setMaxVolume(100)
            .build()
    }

    override fun getDeviceVolume(): Int = mpv.getPropertyInt("volume") ?: 0

    override fun isDeviceMuted(): Boolean = mpv.getPropertyBoolean("mute") ?: false

    override fun mute() {
        mpv.setPropertyBoolean("mute", true)
    }

    override fun unmute() {
        mpv.setPropertyBoolean("mute", false)
    }

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) {
        mpv.setPropertyInt("volume", volume)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        mpv.setPropertyInt("volume", volume)
    }

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() {
        setDeviceVolume((getDeviceVolume() + 1).coerceAtMost(100), 0)
    }

    override fun increaseDeviceVolume(flags: Int) {
        setDeviceVolume((getDeviceVolume() + 1).coerceAtMost(100), flags)
    }

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() {
        setDeviceVolume((getDeviceVolume() - 1).coerceAtLeast(0), 0)
    }

    override fun decreaseDeviceVolume(flags: Int) {
        setDeviceVolume((getDeviceVolume() - 1).coerceAtLeast(0), flags)
    }

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) {
        mpv.setPropertyBoolean("mute", muted)
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        mpv.setPropertyBoolean("mute", muted)
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {}

    override fun getAvailableCommands(): Player.Commands {
        return Player.Commands.Builder()
            .addAll(permanentAvailableCommands)
            .addIf(COMMAND_SEEK_TO_DEFAULT_POSITION, !isPlayingAd)
            .addIf(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_PREVIOUS,
                !currentTimeline.isEmpty &&
                    (hasPreviousMediaItem() ||
                        !isCurrentMediaItemLive ||
                        isCurrentMediaItemSeekable) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem() && !isPlayingAd)
            .addIf(
                COMMAND_SEEK_TO_NEXT,
                !currentTimeline.isEmpty &&
                    (hasNextMediaItem() || (isCurrentMediaItemLive && isCurrentMediaItemDynamic)) &&
                    !isPlayingAd,
            )
            .addIf(COMMAND_SEEK_TO_MEDIA_ITEM, !isPlayingAd)
            .addIf(COMMAND_SEEK_BACK, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(COMMAND_SEEK_FORWARD, isCurrentMediaItemSeekable && !isPlayingAd)
            .build()
    }

    private fun resetInternalState() {
        isPlayerReady = false
        seamlessAdvanceInFlight = false
        isSeekable = false
        playbackState = STATE_IDLE
        currentPositionMs = null
        currentDurationMs = null
        currentCacheDurationMs = null
        currentTracks = Tracks.EMPTY
        playbackParameters = PlaybackParameters.DEFAULT
        initialCommands.clear()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusCallback = {
                    if (getPlayWhenReady()) {
                        playWhenReady = true
                    }
                    audioFocusCallback = {}
                }
                playWhenReady = false
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                volume = AUDIO_FOCUS_DUCKING
                audioFocusCallback = {
                    volume = 1F
                    audioFocusCallback = {}
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusCallback()
            }
        }
    }

    private fun attachVideoSurface(surface: Surface?) {
        Log.i(TAG, "attachVideoSurface: surface=${surface != null} valid=${surface?.isValid} sameAsCurrent=${surface == currentSurface}")
        if (surface == null || !surface.isValid) {
            detachVideoSurface(currentSurface)
            return
        }

        if (currentSurface != null && currentSurface != surface) {
            mpv.detachSurface()
        }

        currentSurface = surface
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setOptionString("vid", "auto")
        applySurfaceFrameRate()
    }

    /**
     * Hint the Android compositor to pace presentation to the content frame rate
     * (and, on capable panels, switch the display to a matching refresh rate). This
     * is what ExoPlayer does via VideoFrameReleaseHelper; mpv's generic Android vo
     * does not, so without it MPV playback judders even on a 120Hz panel. API 30+.
     */
    private fun applySurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = currentSurface ?: return
        val fps = videoFrameRate
        if (fps <= 0f || !surface.isValid) return
        runCatching {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            Log.i(TAG, "Surface.setFrameRate($fps) applied for smooth MPV pacing")
        }
    }

    private fun detachVideoSurface(surface: Surface?) {
        if (surface != null && currentSurface != null && currentSurface != surface) return

        mpv.detachSurface()
        currentSurface = null
    }

    private fun updateVideoSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    private fun attachTextureSurface(
        surfaceTexture: SurfaceTexture?,
        width: Int,
        height: Int,
    ) {
        if (surfaceTexture == null) {
            releaseTextureSurface(currentTextureSurface)
            return
        }

        if (currentSurfaceTexture != surfaceTexture) {
            releaseTextureSurface(currentTextureSurface)
            currentSurfaceTexture = surfaceTexture
            currentTextureSurface = Surface(surfaceTexture)
        }

        attachVideoSurface(currentTextureSurface)
        updateVideoSurfaceSize(width, height)
    }

    private fun releaseTextureSurface(surface: Surface?) {
        if (surface == null) return

        detachVideoSurface(surface)
        surface.release()
        if (surface == currentTextureSurface) {
            currentTextureSurface = null
            currentSurfaceTexture = null
        }
    }

    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachVideoSurface(holder.surface)
                updateVideoSurfaceSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                attachVideoSurface(holder.surface)
                updateVideoSurfaceSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                detachVideoSurface(holder.surface)
            }
        }

    private val textureSurfaceListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                attachTextureSurface(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                attachTextureSurface(surfaceTexture, width, height)
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releaseTextureSurface(currentTextureSurface)
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }

    fun setOption(name: String, value: String) {
        try {
            mpv.setPropertyString(name, value)
            Log.d(TAG, "MPV option set: $name = $value")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set MPV option: $name = $value", e)
        }
    }

    fun setAudioPassthroughCodecs(codecs: List<String>) {
        val value = codecs
            .mapNotNull { codec ->
                // mpv's `audio-spdif` only accepts these bitstream formats.
                // Normalize common server/codec spelling variants so they aren't
                // dropped on a hyphen/underscore mismatch. Anything genuinely
                // unsupported (e.g. AC-4, which mpv cannot bitstream) is LOGGED
                // rather than silently dropped, so a capability/engine mismatch is
                // diagnosable instead of degrading to silent software decode.
                when (codec.lowercase().replace('_', '-')) {
                    "ac3", "ac-3" -> "ac3"
                    "eac3", "e-ac3", "eac3-joc", "ec3" -> "eac3"
                    "dts" -> "dts"
                    "dts-hd", "dtshd", "dts-hd-ma", "dtshd-ma" -> "dts-hd"
                    "truehd", "mlp" -> "truehd"
                    else -> {
                        Log.w(TAG, "Unsupported mpv passthrough codec dropped: $codec")
                        null
                    }
                }
            }
            .distinct()
            .joinToString(",")
        passthroughEnabled = value.isNotBlank()
        setOption("audio-spdif", value)
        if (passthroughEnabled) {
            playbackParameters = playbackParameters.withSpeed(1.0f)
            mpv.setPropertyDouble("speed", 1.0)
        }
    }

    companion object {
        private const val TAG = "SiloMpvPlayer"
        private const val AUDIO_FOCUS_DUCKING = 0.5f

        private val permanentAvailableCommands: Player.Commands =
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_SET_PLAYLIST_METADATA,
                    COMMAND_SET_SPEED_AND_PITCH,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_METADATA,
                    COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_SET_VIDEO_SURFACE,
                    COMMAND_GET_TRACKS,
                    COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                )
                .build()

        private fun JSONObject.optNullableString(name: String): String? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getString(name)
            } else {
                null
            }
        }

        private fun JSONObject.optNullableDouble(name: String): Double? {
            return if (this.has(name) && !this.isNull(name)) {
                this.getDouble(name)
            } else {
                null
            }
        }

        private fun createTracksGroupfromMpvJson(json: JSONObject): Tracks.Group {
            val trackType = MpvTrackType.entries.first { it.type == json.optString("type") }

            val baseFormat =
                Format.Builder()
                    .setId(json.optInt("id"))
                    .setLabel(json.optNullableString("title"))
                    .setLanguage(json.optNullableString("lang"))
                    .setSelectionFlags(
                        (if (json.optBoolean("default")) C.SELECTION_FLAG_DEFAULT else 0) or
                            (if (json.optBoolean("forced")) C.SELECTION_FLAG_FORCED else 0)
                    )
                    .setCodecs(json.optNullableString("codec"))
                    .build()

            val format =
                when (trackType) {
                    MpvTrackType.VIDEO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("video/${baseFormat.codecs}")
                            .setWidth(json.optInt("demux-w", Format.NO_VALUE))
                            .setHeight(json.optInt("demux-h", Format.NO_VALUE))
                            .setFrameRate(
                                (json.optNullableDouble("demux-fps") ?: Format.NO_VALUE).toFloat()
                            )
                            .build()
                    }

                    MpvTrackType.AUDIO -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("audio/${baseFormat.codecs}")
                            .setChannelCount(json.optInt("demux-channel-count", Format.NO_VALUE))
                            .setSampleRate(json.optInt("demux-samplerate", Format.NO_VALUE))
                            .build()
                    }

                    MpvTrackType.SUBTITLE -> {
                        baseFormat
                            .buildUpon()
                            .setSampleMimeType("text/${baseFormat.codecs}")
                            .build()
                    }
                }

            val trackGroup = TrackGroup(format)

            return Tracks.Group(
                trackGroup,
                false,
                IntArray(trackGroup.length) { C.FORMAT_HANDLED },
                BooleanArray(trackGroup.length) { json.optBoolean("selected") },
            )
        }

        private fun getTracks(trackList: String): Tracks {
            var tracks = Tracks.EMPTY
            val trackGroups = mutableListOf<Tracks.Group>()
            try {
                val currentTrackList = JSONArray(trackList)
                for (index in 0 until currentTrackList.length()) {
                    val tracksGroup =
                        createTracksGroupfromMpvJson(currentTrackList.getJSONObject(index))
                    trackGroups.add(tracksGroup)
                }
                if (trackGroups.isNotEmpty()) {
                    tracks = Tracks(trackGroups)
                }
            } catch (_: JSONException) {}
            return tracks
        }
    }

    data class Property(val name: String, val format: Int)
}
