package com.continuum.app.common.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread

/**
 * Android analogue of the tvOS `HDRDisplayController` in the iOS app. Negotiates
 * the HDMI output mode (resolution + refresh rate) to match the content we're
 * about to render, so the sink stays in a consistent mode instead of whatever
 * the home screen set.
 *
 * Done via [android.view.WindowManager.LayoutParams.preferredDisplayModeId].
 * The display driver picks among [Display.getSupportedModes], and we feed it
 * the mode whose resolution & refresh rate best match the content. On most
 * Android TV devices this triggers an HDMI handshake (1–2 s black screen) —
 * hence the coalescing that suppresses redundant applies.
 *
 * This controller only negotiates resolution and refresh rate; it does not
 * pick an HDR color mode. HDR is signalled out-of-band by the decoder's
 * `Format.colorInfo` on the playback `SurfaceView`, which drives the platform's
 * implicit HDR negotiation regardless of API level. (Per-mode HDR support can
 * be inspected via [Display.Mode.getSupportedHdrTypes] on API 34+, but that is
 * a capability probe done in [DisplayHdrProbe], not a selection made here.)
 *
 * Lifecycle:
 *   - [attach] once per player mount (records original mode for restore).
 *   - [applyForMedia] on every video-size or frame-rate change.
 *   - [restore] on player dismissal.
 *
 * TV-only: phones / tablets typically don't expose multiple display modes and
 * calling this has no effect.
 */
class HdrDisplayController {

    companion object {
        private const val TAG = "HdrDisplayController"
    }

    private var window: Window? = null
    private var originalModeId: Int = 0
    private var lastAppliedSignature: String? = null

    @MainThread
    fun attach(activity: Activity) {
        window = activity.window
        // activity.display requires API 30+; fall back to the deprecated
        // getter below API 30.
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
        originalModeId = display?.mode?.modeId ?: 0
    }

    /**
     * Picks the display mode that best matches the content and applies it.
     *
     * Priority order:
     * 1. Same width/height as the mode's resolution (avoids upscaling by the
     *    HDMI link when content is 1080p on a 4K TV that would otherwise stay
     *    at the home-screen 4K mode).
     * 2. Closest refresh rate to `frameRateHz` (matches 23.976/24/25/29.97/30/50/59.94/60).
     *
     * No-op if no window is attached, the display doesn't enumerate modes, or
     * the computed signature matches the last apply.
     */
    @MainThread
    fun applyForMedia(
        videoWidth: Int,
        videoHeight: Int,
        frameRateHz: Float,
    ) {
        val w = window ?: return
        val display = defaultDisplay(w) ?: return
        val supported = display.supportedModes
        if (supported.isEmpty()) return

        val signature = "${videoWidth}x${videoHeight}@${(frameRateHz * 1000).toInt()}"
        if (signature == lastAppliedSignature) return

        val best = pickBestMode(supported, videoWidth, videoHeight, frameRateHz) ?: return
        val params = w.attributes
        if (params.preferredDisplayModeId == best.modeId) {
            lastAppliedSignature = signature
            return
        }
        params.preferredDisplayModeId = best.modeId
        w.attributes = params
        lastAppliedSignature = signature
        Log.i(
            TAG,
            "Applied display mode ${best.modeId}: ${best.physicalWidth}x${best.physicalHeight}@${best.refreshRate} for content $signature",
        )
    }

    @MainThread
    fun restore() {
        val w = window ?: return
        val params = w.attributes
        if (params.preferredDisplayModeId == 0 && lastAppliedSignature == null) return
        params.preferredDisplayModeId = originalModeId
        w.attributes = params
        lastAppliedSignature = null
        Log.i(TAG, "Restored display mode $originalModeId")
    }

    private fun pickBestMode(
        modes: Array<Display.Mode>,
        videoWidth: Int,
        videoHeight: Int,
        frameRateHz: Float,
    ): Display.Mode? {
        // Prefer modes whose resolution matches the content or is the closest
        // larger resolution. Content upscaling by the panel is near-lossless;
        // HDMI link resampling is what we want to avoid.
        val sameRes = modes.filter { it.physicalWidth == videoWidth && it.physicalHeight == videoHeight }
        val candidates = sameRes.ifEmpty { modes.toList() }

        // Shared refresh-rate scoring lives in RefreshRateSelector; the phone
        // RefreshRateMatcher uses the same helper without the resolution
        // pre-filter (phone panels have a fixed native resolution).
        return RefreshRateSelector.pickByRefreshRateAmong(candidates, frameRateHz)
    }

    private fun defaultDisplay(window: Window): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.context.display
        } else {
            @Suppress("DEPRECATION")
            (window.context.getSystemService(WindowManager::class.java))?.defaultDisplay
        }
    }
}
