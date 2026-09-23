package org.siloserver.silo.common.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import org.siloserver.silo.common.R
import org.siloserver.silo.common.player.PipActionCapability
import org.siloserver.silo.common.player.LEGACY_PLAYER_SEEK_INTERVALS
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.model.settings.SeekIntervalPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SiloPictureInPictureSurface {
    Mobile,
    Tv,
}

private const val DEFAULT_VIDEO_WIDTH = 16
private const val DEFAULT_VIDEO_HEIGHT = 9
private const val MIN_PIP_ASPECT_RATIO = 1f / 2.39f
private const val MAX_PIP_ASPECT_RATIO = 2.39f

data class SiloPictureInPicturePlaybackState(
    val enabled: Boolean = true,
    val videoActive: Boolean = false,
    val isPlaying: Boolean = false,
    val videoWidth: Int = DEFAULT_VIDEO_WIDTH,
    val videoHeight: Int = DEFAULT_VIDEO_HEIGHT,
    val sourceRectHint: Rect? = null,
) {
    val hasVideoSize: Boolean = videoWidth > 0 && videoHeight > 0
}

fun siloCanEnterPictureInPicture(
    surface: SiloPictureInPictureSurface,
    sdkInt: Int,
    deviceSupportsPictureInPicture: Boolean,
    enabled: Boolean,
    videoActive: Boolean,
    isPlaying: Boolean,
): Boolean {
    if (!enabled || !deviceSupportsPictureInPicture || !videoActive || !isPlaying) return false
    return when (surface) {
        SiloPictureInPictureSurface.Mobile -> sdkInt >= Build.VERSION_CODES.O
        // Android TV PiP existed earlier in name, but Google TV support is
        // dependable only with Android 14's TV multitasking model.
        SiloPictureInPictureSurface.Tv -> sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }
}

fun siloShouldUpdatePictureInPictureParams(
    sdkInt: Int,
    deviceSupportsPictureInPicture: Boolean,
    wasEnabled: Boolean,
    enabled: Boolean,
): Boolean =
    sdkInt >= Build.VERSION_CODES.O &&
        deviceSupportsPictureInPicture &&
        (wasEnabled || enabled)

class SiloPictureInPictureCoordinator(
    /**
     * Video intervals the PiP skip actions use (see
     * [SiloPlaybackService.dispatchPictureInPictureAction]); read whenever the
     * params are rebuilt so titles and glyphs match the action.
     */
    private val seekIntervals: () -> SeekIntervalPair = { LEGACY_PLAYER_SEEK_INTERVALS },
) {
    private val _isInPictureInPictureMode = MutableStateFlow(false)
    val isInPictureInPictureMode: StateFlow<Boolean> = _isInPictureInPictureMode.asStateFlow()

    private val stateBySurface =
        mutableMapOf<SiloPictureInPictureSurface, SiloPictureInPicturePlaybackState>()

    fun setInPictureInPictureMode(value: Boolean) {
        _isInPictureInPictureMode.value = value
    }

    fun updatePlaybackState(
        activity: Activity?,
        surface: SiloPictureInPictureSurface,
        state: SiloPictureInPicturePlaybackState,
    ) {
        val previousState = stateBySurface.put(surface, state)
        // setPictureInPictureParams throws IllegalStateException on devices
        // without FEATURE_PICTURE_IN_PICTURE (e.g. some Android TV hardware), so
        // gate on device support and avoid touching the API when PiP has always
        // been disabled for this surface. A true -> false transition still has
        // to update the params so mobile auto-enter is switched off.
        if (activity != null && siloShouldUpdatePictureInPictureParams(
                sdkInt = Build.VERSION.SDK_INT,
                deviceSupportsPictureInPicture = activity.supportsPictureInPicture(),
                wasEnabled = previousState?.enabled == true,
                enabled = state.enabled,
            )
        ) {
            activity.setPictureInPictureParams(buildParams(activity, surface, state))
        }
    }

    fun clearPlaybackState(activity: Activity?, surface: SiloPictureInPictureSurface) {
        val previousState = stateBySurface.remove(surface)
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            previousState?.enabled == true &&
            activity.supportsPictureInPicture()
        ) {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build(),
            )
        }
    }

    fun enterPictureInPictureIfEligible(
        activity: Activity,
        surface: SiloPictureInPictureSurface,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val state = stateBySurface[surface] ?: return false
        if (!canEnter(activity, surface, state)) return false
        activity.enterPictureInPictureMode(buildParams(activity, surface, state))
        return true
    }

    private fun canEnter(
        activity: Activity,
        surface: SiloPictureInPictureSurface,
        state: SiloPictureInPicturePlaybackState,
    ): Boolean =
        siloCanEnterPictureInPicture(
            surface = surface,
            sdkInt = Build.VERSION.SDK_INT,
            deviceSupportsPictureInPicture = activity.supportsPictureInPicture(),
            enabled = state.enabled,
            videoActive = state.videoActive,
            isPlaying = state.isPlaying,
        )

    private fun Activity.supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun buildParams(
        context: Context,
        surface: SiloPictureInPictureSurface,
        state: SiloPictureInPicturePlaybackState,
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatioFor(state))
            .setActions(remoteActions(context, state.isPlaying))
        val rect = state.sourceRectHint
        if (rect != null && !rect.isEmpty) {
            builder.setSourceRectHint(rect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Gate auto-enter on the SAME eligibility the explicit path uses, so
            // a surface whose PiP floor is above API 31 (TV requires API 34)
            // never auto-enters a mode enterPictureInPictureIfEligible refuses.
            builder.setAutoEnterEnabled(
                siloCanEnterPictureInPicture(
                    surface = surface,
                    sdkInt = Build.VERSION.SDK_INT,
                    deviceSupportsPictureInPicture = context.packageManager
                        .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
                    enabled = state.enabled,
                    videoActive = state.videoActive,
                    isPlaying = state.isPlaying,
                ),
            )
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun remoteActions(context: Context, isPlaying: Boolean): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val playPauseAction = if (isPlaying) {
            SiloPlaybackService.ACTION_PIP_PAUSE
        } else {
            SiloPlaybackService.ACTION_PIP_PLAY
        }
        val playPauseIcon = if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val intervals = seekIntervals()
        // Numbered glyphs exist for the legacy 10s back / 30s forward only;
        // any other interval uses the plain arrow and names the value in the
        // action title.
        val backIcon = if (intervals.backSeconds == 10) R.drawable.ic_pip_replay_10 else R.drawable.ic_pip_replay
        val forwardIcon =
            if (intervals.forwardSeconds == 30) R.drawable.ic_pip_forward_30 else R.drawable.ic_pip_forward
        val backTitle = "Back ${intervals.backSeconds} seconds"
        val forwardTitle = "Forward ${intervals.forwardSeconds} seconds"
        return listOf(
            RemoteAction(
                Icon.createWithResource(context, backIcon),
                backTitle,
                backTitle,
                pendingServiceIntent(context, SiloPlaybackService.ACTION_PIP_SKIP_BACK),
            ),
            RemoteAction(
                Icon.createWithResource(context, playPauseIcon),
                playPauseTitle,
                playPauseTitle,
                pendingServiceIntent(context, playPauseAction),
            ),
            RemoteAction(
                Icon.createWithResource(context, forwardIcon),
                forwardTitle,
                forwardTitle,
                pendingServiceIntent(context, SiloPlaybackService.ACTION_PIP_SKIP_FORWARD),
            ),
        )
    }

    private fun pendingServiceIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            PipActionCapability.intent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun aspectRatioFor(state: SiloPictureInPicturePlaybackState): Rational {
        val width = state.videoWidth.takeIf { it > 0 } ?: DEFAULT_VIDEO_WIDTH
        val height = state.videoHeight.takeIf { it > 0 } ?: DEFAULT_VIDEO_HEIGHT
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio < MIN_PIP_ASPECT_RATIO -> Rational(9, 16)
            ratio > MAX_PIP_ASPECT_RATIO -> Rational(239, 100)
            else -> Rational(width, height)
        }
    }

}
