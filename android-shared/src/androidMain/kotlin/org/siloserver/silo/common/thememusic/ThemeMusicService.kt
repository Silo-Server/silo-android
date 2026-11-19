package org.siloserver.silo.common.thememusic

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.model.catalog.ThemeSelection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.ThemeAudioFormatV2
import org.siloserver.silo.network.apiv2.ThemeMusicV2Api
import org.siloserver.silo.playback.theme.ThemeAudio
import org.siloserver.silo.playback.theme.ThemeGrant
import org.siloserver.silo.playback.theme.ThemeMusicCoordinator
import org.siloserver.silo.playback.theme.ThemeMusicPlayer

/**
 * Theme music for the phone and TV detail pages: one app-wide
 * [ThemeMusicCoordinator] fed by the `ui.theme_music_*` settings, the
 * server's theme capability and short-lived playback grants, playing through
 * its own small ExoPlayer.
 *
 * The ExoPlayer manages audio focus, so starting a video, an audiobook or
 * another app's audio pauses the theme. A theme never starts over audio that
 * is already playing.
 */
class ThemeMusicService(
    context: Context,
    private val api: ThemeMusicV2Api,
    private val tokens: TokenManager,
    settings: PlayerSettingsStore,
    /** Emits when the active server or profile changes. */
    identityChanges: Flow<Unit>,
    /** Emits when a playback session starts playing ([playbackStarts]). */
    playbackStarts: Flow<Unit>,
    private val dataSourceFactory: DataSource.Factory,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /** What this device decodes, probed once; the server converts the rest to AAC. */
    private val acceptedFormats: List<ThemeAudioFormatV2> by lazy { probeAcceptedFormats() }

    private val coordinator = ThemeMusicCoordinator(
        scope = scope,
        player = ThemeMusicPlayer(scope, ::grant, ::createAudio),
        capability = ::readCapability,
        otherAudioActive = { audioManager?.isMusicActive == true },
    )

    init {
        scope.launch {
            combine(settings.themeMusicEnabledFlow, settings.themeMusicLoopFlow, ::Pair)
                .collect { (enabled, loop) -> coordinator.setPreferences(enabled, loop) }
        }
        scope.launch { identityChanges.collect { coordinator.reset() } }
        // A paused theme holds no audio focus, so the session's focus request
        // alone would not end it.
        scope.launch { playbackStarts.collect { coordinator.interrupt() } }
    }

    /** [page] is on screen with [selection] loaded. Main thread. */
    fun show(page: Any, selection: ThemeSelection) = coordinator.show(page, selection)

    /** [page] left the screen or the app went to the background. Main thread. */
    fun hide(page: Any) = coordinator.hide(page)

    private suspend fun readCapability(): Boolean? = when (val result = api.capability()) {
        is ApiResult.Success -> result.data.isAvailable
        // A server without the route predates themes.
        is ApiResult.Error -> if (result.code == 404) false else null
        is ApiResult.NetworkError -> null
    }

    private suspend fun grant(ownerId: String, themeId: String): ThemeGrant? {
        val scope = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() } ?: return null
        return when (val result = api.grant(ownerId, themeId, acceptedFormats, scope)) {
            is ApiResult.Success -> ThemeGrant(result.data.url, result.data.isConverted)
            else -> null
        }
    }

    private fun createAudio(url: String, loop: Boolean, listener: ThemeAudio.Listener): ThemeAudio =
        ExoThemeAudio(appContext, dataSourceFactory, url, loop, listener)

    companion object {
        /** Container and codec pairs ExoPlayer extracts, with the decoder each needs. */
        private val CANDIDATES: List<Pair<ThemeAudioFormatV2, String>> = listOf(
            ThemeAudioFormatV2("mp3", "mp3") to "audio/mpeg",
            ThemeAudioFormatV2("mp4", "aac") to "audio/mp4a-latm",
            ThemeAudioFormatV2("m4a", "aac") to "audio/mp4a-latm",
            ThemeAudioFormatV2("aac", "aac") to "audio/mp4a-latm",
            ThemeAudioFormatV2("flac", "flac") to "audio/flac",
            ThemeAudioFormatV2("ogg", "vorbis") to "audio/vorbis",
            ThemeAudioFormatV2("ogg", "opus") to "audio/opus",
            ThemeAudioFormatV2("opus", "opus") to "audio/opus",
            ThemeAudioFormatV2("wav", "pcm") to PCM,
        )
        private const val PCM = "audio/raw"

        private fun probeAcceptedFormats(): List<ThemeAudioFormatV2> {
            val decoders = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filterNot { it.isEncoder }
                    .flatMap { it.supportedTypes.asList() }
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())
            // PCM needs no decoder. With no answer at all, send nothing and get
            // the original, as the server does for clients that do not say.
            if (decoders.isEmpty()) return emptyList()
            return CANDIDATES.filter { (_, mime) -> mime == PCM || mime in decoders }.map { it.first }
        }
    }
}

/** Each time the app's playback session [player] starts playing. Collect on the main thread. */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
fun playbackStarts(player: StateFlow<Player?>): Flow<Unit> = player.flatMapLatest { session ->
    if (session == null) {
        emptyFlow()
    } else {
        callbackFlow {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) trySend(Unit)
                }
            }
            session.addListener(listener)
            awaitClose { session.removeListener(listener) }
        }
    }
}

/** One theme on a dedicated ExoPlayer. The grant URL stays inside the player. */
@OptIn(UnstableApi::class)
private class ExoThemeAudio(
    context: Context,
    dataSourceFactory: DataSource.Factory,
    url: String,
    loop: Boolean,
    listener: ThemeAudio.Listener,
) : ThemeAudio {
    private val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory).setLoadErrorHandlingPolicy(GrantErrorPolicy),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.volume = 0f
        setLooping(loop)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) listener.onPlaying()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // A permanent focus loss: a video started, or the user chose
                // other audio. A transient loss pauses and resumes on its own.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    listener.onInterrupted()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) listener.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) = listener.onError()
        })
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    override var volume: Float
        get() = player.volume
        set(value) { player.volume = value }

    override fun setLooping(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun release() = player.release()
}

/**
 * A refused grant (expired or revoked, 4xx) fails at once instead of being
 * retried, so the player asks for a fresh grant without a silent gap.
 */
@OptIn(UnstableApi::class)
private object GrantErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val refused = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)
            ?.responseCode in 400..499
        return if (refused) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
    }
}
