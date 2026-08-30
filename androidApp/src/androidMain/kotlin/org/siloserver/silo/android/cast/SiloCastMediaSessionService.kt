package org.siloserver.silo.android.cast

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.common.player.SiloMediaSessionBitmapLoader
import org.siloserver.silo.repository.CatalogRepository
import kotlin.math.roundToLong

/**
 * Publishes Silo Remote Control as an Android Media3 session. The player is a
 * projection of the TV's state: system play/pause/seek/next commands are sent
 * over SiloCast and incoming TV state invalidates the Media3 timeline.
 *
 * Keeping this session in a MediaSessionService also gives an engaged remote
 * session a foreground-service lifetime while the TV is playing, so swiping
 * away the phone UI does not immediately tear down the control socket.
 */
@UnstableApi
class SiloCastMediaSessionService : MediaSessionService() {
    private val controller: SiloCastController by inject()
    private val catalogRepository: CatalogRepository by inject()

    private lateinit var player: SiloCastRemotePlayer
    private var mediaSession: MediaSession? = null
    private var mediaSessionBitmapLoader: SiloMediaSessionBitmapLoader? = null
    private lateinit var scope: CoroutineScope
    private var stateJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkContentId: String? = null
    private var artworkUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = SiloCastRemotePlayer(Looper.getMainLooper(), controller)
        val bitmapLoader = SiloMediaSessionBitmapLoader(this)
        mediaSessionBitmapLoader = bitmapLoader
        mediaSession = MediaSession.Builder(this, player)
            .setBitmapLoader(bitmapLoader)
            .build()
            .also(::addSession)

        stateJob = scope.launch {
            controller.state.collect { state ->
                val playback = state.playbackState
                player.update(
                    playback = playback,
                    targetName = state.connectedTarget?.name,
                    artworkUrl = artworkUrl.takeIf { playback?.contentId == artworkContentId },
                )
                if (playback?.contentId.isNullOrBlank()) {
                    pauseAllPlayersAndStopSelf()
                }
            }
        }
        artworkJob = scope.launch {
            controller.state
                .map { it.playbackState?.contentId }
                .distinctUntilChanged()
                .collectLatest { contentId ->
                    artworkContentId = contentId
                    artworkUrl = null
                    player.updateArtwork(contentId = contentId, artworkUrl = null)
                    if (contentId.isNullOrBlank()) return@collectLatest

                    val artwork = resolveCastArtwork(catalogRepository, contentId)
                    if (artworkContentId == contentId) {
                        // Wide backdrop is intentional for Android's wide
                        // system media canvas; the poster is only a fallback.
                        artworkUrl = artwork.backdropUrl ?: artwork.posterUrl
                        player.updateArtwork(contentId, artworkUrl)
                    }
                }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Media3's default stops a paused session as soon as Recents dismisses
        // the Activity. Keep an active remote session for the foreground grace
        // window; once it is no longer foreground, the platform requires stop.
        val hasRemoteMedia = !controller.state.value.playbackState?.contentId.isNullOrBlank()
        if (!hasRemoteMedia || !isPlaybackOngoing()) {
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        artworkJob?.cancel()
        scope.cancel()
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        mediaSessionBitmapLoader?.close()
        mediaSessionBitmapLoader = null
        player.release()
        super.onDestroy()
    }
}

@UnstableApi
internal class SiloCastRemotePlayer(
    looper: Looper,
    private val controller: SiloCastController,
) : SimpleBasePlayer(looper) {
    private var playback: SiloCastPlaybackState? = null
    private var targetName: String? = null
    private var artworkContentId: String? = null
    private var artworkUrl: String? = null

    fun update(
        playback: SiloCastPlaybackState?,
        targetName: String?,
        artworkUrl: String?,
    ) {
        verifyApplicationThread()
        this.playback = playback
        this.targetName = targetName
        this.artworkContentId = playback?.contentId
        this.artworkUrl = artworkUrl
        invalidateState()
    }

    fun updateArtwork(contentId: String?, artworkUrl: String?) {
        verifyApplicationThread()
        if (playback?.contentId != contentId) return
        artworkContentId = contentId
        this.artworkUrl = artworkUrl
        invalidateState()
    }

    override fun getState(): State {
        val remote = playback
        val contentId = remote?.contentId?.takeIf(String::isNotBlank)
            ?: return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_RELEASE).build())
                .setPlaybackState(Player.STATE_IDLE)
                .build()

        val durationMs = remote.duration
            .takeIf { it.isFinite() && it > 0.0 }
            ?.times(1000.0)
            ?.roundToLong()
            ?: C.TIME_UNSET
        val positionMs = controller.displayTime()
            .takeIf { it.isFinite() }
            ?.times(1000.0)
            ?.roundToLong()
            ?.coerceAtLeast(0L)
            ?.let { position ->
                if (durationMs == C.TIME_UNSET) position else position.coerceAtMost(durationMs)
            }
            ?: 0L
        val wantsToPlay = controller.isPlaying() || remote.isLoading || remote.isBuffering
        val playbackState = if (remote.isLoading || remote.isBuffering) {
            Player.STATE_BUFFERING
        } else {
            Player.STATE_READY
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(remote.title)
            .setSubtitle(remote.subtitle ?: targetName?.let { "Playing on $it" })
            .setIsPlayable(true)
            .apply {
                remote.subtitle?.takeIf(String::isNotBlank)?.let { setArtist(it) }
                if (durationMs != C.TIME_UNSET) setDurationMs(durationMs)
                artworkUrl
                    ?.takeIf { artworkContentId == contentId && it.isNotBlank() }
                    ?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        val mediaItem = MediaItem.Builder()
            .setMediaId(contentId)
            .setMediaMetadata(metadata)
            .build()
        val itemData = MediaItemData.Builder(remote.sessionId ?: contentId)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1_000L)
            .setIsSeekable(durationMs != C.TIME_UNSET)
            .build()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .apply {
                if (remote.hasNextEpisode) add(Player.COMMAND_SEEK_TO_NEXT)
            }
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .setIsLoading(remote.isLoading || remote.isBuffering)
            .setPlayWhenReady(wantsToPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(positionMs)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setPlaybackParameters(
                PlaybackParameters(
                    remote.playbackSpeed.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f,
                ),
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controller.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_TO_NEXT) {
            controller.playNext()
        } else if (positionMs != C.TIME_UNSET) {
            controller.seek(positionMs.coerceAtLeast(0L) / 1000.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stopPlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
