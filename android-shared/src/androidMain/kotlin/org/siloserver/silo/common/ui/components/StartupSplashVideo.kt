package org.siloserver.silo.common.ui.components

import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Initial app-loading animation shown before the root navigation graph is ready.
 *
 * [minVisibleMillis] keeps the splash up at least that long after playback
 * starts; [maxVisibleMillis] (0 = uncapped) force-completes even if the video
 * is still playing or stalled — mirroring StartupSplashView's 4s
 * `maximumDisplayDuration` on Apple, where the splash never outlives the cap.
 */
@OptIn(UnstableApi::class)
@Composable
fun StartupSplashVideo(
    modifier: Modifier = Modifier,
    resizeMode: StartupSplashResizeMode = StartupSplashResizeMode.Fit,
    backgroundColor: Color = Color.Black,
    minVisibleMillis: Long = 0L,
    maxVisibleMillis: Long = 0L,
    onPlaybackComplete: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnPlaybackComplete = rememberUpdatedState(onPlaybackComplete)
    val completionDispatched = remember { AtomicBoolean(false) }
    val playbackStarted = remember { AtomicBoolean(false) }
    var playbackFinished by remember { mutableStateOf(false) }
    var playbackVisibleStartedAt by remember { mutableStateOf(0L) }
    // Tier the asset to what this device's decoder actually sustains — a 4K60
    // splash decoded into garbled half-frames on an onn 4K stick. See
    // [startupSplashRes].
    val splashRes = remember { startupSplashRes() }
    val player = remember(context, splashRes) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
            setMediaItem(
                MediaItem.fromUri(
                    RawResourceDataSource.buildRawResourceUri(splashRes),
                ),
            )
            prepare()
            playWhenReady = false
        }
    }

    LaunchedEffect(playbackFinished, minVisibleMillis) {
        if (!playbackFinished) return@LaunchedEffect
        val elapsed = (SystemClock.elapsedRealtime() - playbackVisibleStartedAt).coerceAtLeast(0L)
        val remaining = (minVisibleMillis - elapsed).coerceAtLeast(0L)
        if (remaining > 0L) delay(remaining)
        completionDispatched.dispatchOnce(currentOnPlaybackComplete.value)
    }

    // Hard cap: complete even if the video is still playing (or never started —
    // a stalled prepare must not hold the app hostage). Re-anchors to the
    // playback-visible timestamp once playback actually starts.
    LaunchedEffect(maxVisibleMillis, playbackVisibleStartedAt) {
        if (maxVisibleMillis <= 0L) return@LaunchedEffect
        val elapsed = if (playbackVisibleStartedAt == 0L) {
            0L
        } else {
            (SystemClock.elapsedRealtime() - playbackVisibleStartedAt).coerceAtLeast(0L)
        }
        val remaining = (maxVisibleMillis - elapsed).coerceAtLeast(0L)
        if (remaining > 0L) delay(remaining)
        completionDispatched.dispatchOnce(currentOnPlaybackComplete.value)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playbackFinished = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFinished = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.resizeMode = resizeMode.toPlayerViewResizeMode()
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    post {
                        if (playbackStarted.compareAndSet(false, true)) {
                            playbackVisibleStartedAt = SystemClock.elapsedRealtime()
                            player.seekTo(0)
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                }
            },
            update = { view ->
                view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

enum class StartupSplashResizeMode {
    Fit,
    Crop,
}

@OptIn(UnstableApi::class)
private fun StartupSplashResizeMode.toPlayerViewResizeMode(): Int =
    when (this) {
        StartupSplashResizeMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        StartupSplashResizeMode.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

private fun AtomicBoolean.dispatchOnce(block: () -> Unit) {
    if (compareAndSet(false, true)) block()
}
