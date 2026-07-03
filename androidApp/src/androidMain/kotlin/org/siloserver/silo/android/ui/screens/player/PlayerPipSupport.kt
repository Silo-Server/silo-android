package org.siloserver.silo.android.ui.screens.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.VideoSize

/** Broadcast action for the PiP window's remote transport buttons. */
const val ACTION_PIP_CONTROL = "org.siloserver.silo.PIP_CONTROL"
const val EXTRA_PIP_CONTROL = "pip_control"
const val PIP_CONTROL_PLAY_PAUSE = 1
const val PIP_CONTROL_REWIND = 2
const val PIP_CONTROL_FORWARD = 3

/** Seek delta applied by the PiP rewind/forward remote actions. */
const val PIP_SEEK_DELTA_MS = 10_000L

/**
 * Bridge from [org.siloserver.silo.android.MainActivity.onUserLeaveHint] to
 * the player screen. On API 26–30 there is no `setAutoEnterEnabled`, so the
 * screen installs an enter-PiP callback here while PiP-eligible and clears it
 * on dispose; the single activity invokes whatever is installed. On API 31+
 * the callback stays null and auto-enter params handle user-leave.
 */
object PipOnUserLeave {
    @Volatile
    var enterPip: (() -> Unit)? = null
}

/**
 * Builds the live [PictureInPictureParams] for the video player: aspect ratio
 * from the current [videoSize] (16:9 before the first frame), the three
 * remote transport actions, and — on API 31+ — auto-enter + seamless resize.
 */
@RequiresApi(Build.VERSION_CODES.O)
fun buildPipParams(
    videoSize: VideoSize?,
    isPlaying: Boolean,
    context: Context,
    shouldAutoEnter: Boolean = false,
): PictureInPictureParams {
    // The platform rejects ratios outside [1/2.39, 2.39] with an
    // IllegalArgumentException, so clamp to the exact bounds.
    val aspectRatio = videoSize
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            when {
                ratio < 1f / MAX_PIP_ASPECT -> Rational(100, 239)
                ratio > MAX_PIP_ASPECT -> Rational(239, 100)
                else -> Rational(size.width, size.height)
            }
        } ?: Rational(16, 9)

    val actions = listOf(
        pipRemoteAction(
            context = context,
            iconRes = android.R.drawable.ic_media_rew,
            title = "Rewind 10 seconds",
            controlCode = PIP_CONTROL_REWIND,
        ),
        pipRemoteAction(
            context = context,
            iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            title = if (isPlaying) "Pause" else "Play",
            controlCode = PIP_CONTROL_PLAY_PAUSE,
        ),
        pipRemoteAction(
            context = context,
            iconRes = android.R.drawable.ic_media_ff,
            title = "Forward 10 seconds",
            controlCode = PIP_CONTROL_FORWARD,
        ),
    )

    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(aspectRatio)
        .setActions(actions)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(shouldAutoEnter)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

@RequiresApi(Build.VERSION_CODES.O)
private fun pipRemoteAction(
    context: Context,
    iconRes: Int,
    title: String,
    controlCode: Int,
): RemoteAction {
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        controlCode,
        Intent(ACTION_PIP_CONTROL)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PIP_CONTROL, controlCode),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(Icon.createWithResource(context, iconRes), title, title, pendingIntent)
}

/**
 * Whether the hosting activity is currently in Picture-in-Picture mode.
 * Seeds from the live activity state and tracks changes via the activity's
 * PiP-mode-changed listener; always false below API 26.
 */
@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current as? ComponentActivity
    var isInPip by remember(activity) {
        mutableStateOf(
            activity != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity.isInPictureInPictureMode,
        )
    }
    DisposableEffect(activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onDispose { }
        } else {
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                isInPip = info.isInPictureInPictureMode
            }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
        }
    }
    return isInPip
}

private const val MAX_PIP_ASPECT = 2.39f
