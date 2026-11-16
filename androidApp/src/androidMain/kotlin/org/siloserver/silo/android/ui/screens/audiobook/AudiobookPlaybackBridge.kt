@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.android.ui.screens.audiobook

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import org.siloserver.silo.common.player.SiloPlaybackService

/**
 * Connects [session]'s player view model to the shared playback service for
 * as long as the session runs. It has no UI: the app shell keeps it composed while
 * the session is active, so playback continues whether the full player or the
 * mini player is showing.
 *
 * The view model never touches Media3. It publishes the stream, pause intent,
 * speed and seeks; this applies them to a [MediaController] and reports the
 * engine's state back. The service's one player also plays video, so a single
 * MediaSession owns foreground audio at any time.
 */
@Composable
fun AudiobookPlaybackBridge(session: ActiveAudiobook) {
    val viewModel = session.player
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    // The controller while the engine still holds this book. The engine is
    // shared with video; the book must not pause, speed up, seek or read a
    // video that has taken it over.
    fun bookController(): MediaController? = controller?.takeIf { it.holdsAudiobook() }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, SiloPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    controller = runCatching { future.get() }.getOrNull()
                }
            },
            MoreExecutors.directExecutor(),
        )
        onDispose {
            controller?.let { c ->
                // A recreated activity picks the session up again, so the book
                // keeps playing through it. Otherwise the session has ended:
                // clear the engine, unless a video has already loaded into it.
                if (activity?.isChangingConfigurations != true && c.holdsAudiobook()) {
                    runCatching { c.pause(); c.stop(); c.clearMediaItems() }
                }
                c.release()
            }
            controller = null
            if (!future.isDone) future.cancel(true)
        }
    }

    // Wire the stream URL into Media3 once both the controller and the
    // metadata have resolved. The MediaItem is rebuilt when streamUrl changes
    // (a multi-part book moving to its next part). Resume-on-open is applied
    // here too: if the VM resolved a saved position, start the item there.
    LaunchedEffect(controller, state.streamUrl) {
        val c = controller ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        // Prepare each distinct stream exactly once. The effect can re-run with
        // the same url (e.g. the controller rebinding); without this guard the
        // second pass — after the resume position was already consumed — would
        // setMediaItem with startMs=0 and reset the book back to 0:00.
        if (url == session.preparedUrl) return@LaunchedEffect
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    // Tags the item so session seeks (headset, lock screen)
                    // use the audiobook intervals rather than the video ones.
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setTitle(state.title)
                    .setArtist(state.author ?: state.narrator)
                    .also { mb ->
                        state.coverUrl?.takeIf { it.isNotBlank() }
                            ?.let { mb.setArtworkUri(android.net.Uri.parse(it)) }
                    }
                    .build(),
            )
            .build()
        // Apply the resume position as the media item's start position rather
        // than a post-prepare seekTo: a seek issued before the timeline window
        // is known gets dropped, leaving the book at 0:00. setMediaItem with a
        // start position is honored once the source prepares.
        val resume = viewModel.resumePositionSeconds.value
        val startMs = ((resume ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()
        c.setMediaItem(mediaItem, startMs)
        c.prepare()
        if (resume != null && resume > 0) viewModel.consumeResumePosition()
        c.playWhenReady = true
        session.preparedUrl = url
    }

    // Flush position on every pause so a process kill loses at most a couple
    // of seconds. Periodic save inside the VM covers the in-flight case.
    LaunchedEffect(controller, state.isPaused) {
        if (state.isPaused) viewModel.flushPosition()
    }

    // Listener bridge — Player state changes go back into the VM so the UI
    // is always a function of Media3's truth.
    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            // Events from a video that has taken the engine are not the book's.
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (c.holdsAudiobook()) viewModel.onPlayingChanged(isPlaying)
                }

                // Pause intent — distinct from transient buffering. Without
                // this a seek's rebuffer would latch the book paused.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (c.holdsAudiobook()) viewModel.onPauseStateChanged(!playWhenReady)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (c.holdsAudiobook()) viewModel.onPlayerError(error)
                }
            }
            c.addListener(listener)
            onDispose { runCatching { c.removeListener(listener) } }
        }
    }

    // Position poller — MediaController doesn't push position updates;
    // sample at 4Hz which is smooth enough for an audiobook slider. The
    // current stream URI rides along so the VM can tie cross-part settle
    // to stream identity, not just engine time.
    LaunchedEffect(controller) {
        while (true) {
            bookController()?.let { c ->
                viewModel.onPositionChanged(
                    c.currentPosition / 1000.0,
                    c.currentMediaItem?.localConfiguration?.uri?.toString(),
                )
            }
            delay(250)
        }
    }

    // Mirror play/pause intent into the controller.
    LaunchedEffect(controller, state.isPaused) {
        bookController()?.playWhenReady = !state.isPaused
    }

    // Apply speed when the user changes it.
    LaunchedEffect(controller, state.playbackSpeed) {
        bookController()?.playbackParameters = PlaybackParameters(state.playbackSpeed)
    }

    // Apply seeks requested by the VM (chapter jumps, skips). A seek stays
    // pending until a controller holding the book applies it, so one asked for
    // while the controller reconnects (a recreated activity) isn't lost.
    val pendingSeek by viewModel.pendingSeekToSeconds.collectAsState()
    LaunchedEffect(controller, pendingSeek) {
        val seconds = pendingSeek ?: return@LaunchedEffect
        val c = bookController() ?: return@LaunchedEffect
        c.seekTo((seconds * 1000).toLong())
        viewModel.consumePendingSeek()
    }
}

/** True while the engine holds an audiobook item, or nothing yet. The service's
 *  one player also plays video; only the book's own items carry this type. */
private fun Player.holdsAudiobook(): Boolean {
    val item = currentMediaItem ?: return true
    return item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
}
