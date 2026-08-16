package org.siloserver.silo.android.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Sampled frame size. 144 rows over a 2160-row frame resolve the matte edge to
 *  well inside [MATTE_ENGAGE_MARGIN]; 64 columns keep a small bright object
 *  near the picture edge from averaging away into apparent black. */
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = 144

/** Fast while deciding so the crop settles about a second in rather than
 *  visibly snapping later; slower once it has, since from then on sampling
 *  only exists to notice the picture opening up. */
private const val SETTLING_INTERVAL_MS = 250L
private const val ENGAGED_INTERVAL_MS = 750L

/** Read-backs that may fail before the crop is surrendered. */
private const val MAX_CONSECUTIVE_COPY_FAILURES = 4

/**
 * Watches the decoded frame and reports whether its encoded letterbox matte is
 * thick enough to zoom into — see [LetterboxMatte.kt] for the reasoning and the
 * safety margins.
 *
 * Frames come from `PixelCopy` against the video SurfaceView, which reads that
 * surface's own buffer. Nothing about the playback pipeline changes: no
 * TextureView, no GL effects chain, no second decoder, no extra network — so
 * tunneled decoding, HDR10 and Dolby Vision passthrough are untouched, which
 * they would not be if the frames were routed through a readable path instead.
 *
 * Returns false — today's plain `RESIZE_MODE_FIT` — whenever the evidence is
 * missing or ambiguous: no surface, a secure buffer that refuses read-back, a
 * fade to black, or a matte too thin to clip into safely.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberLetterboxFillEngaged(
    playerView: PlayerView?,
    enabled: Boolean,
    videoAspect: Float,
    mediaKey: Any?,
): Boolean {
    var engaged by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(playerView, enabled, videoAspect, mediaKey, lifecycleOwner) {
        // A new item, a new surface or a disabled setting starts from uncropped:
        // the previous item's evidence says nothing about this one.
        engaged = false
        if (!enabled || playerView == null || videoAspect <= 0f) return@LaunchedEffect

        val estimator = LetterboxFillEstimator()
        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        try {
            // RESUMED, not STARTED: a backgrounded or picture-in-picture player
            // has no reason to be reading frames back.
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var failures = 0
                while (isActive && !estimator.isAbandoned) {
                    val surfaceView = playerView.videoSurfaceView as? SurfaceView
                    val width = playerView.width
                    val height = playerView.height
                    if (
                        surfaceView != null &&
                        width > 0 &&
                        height > 0 &&
                        surfaceView.holder.surface?.isValid == true
                    ) {
                        if (copySurface(surfaceView, bitmap)) {
                            failures = 0
                            bitmap.getPixels(
                                pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT,
                            )
                            engaged = estimator.onSample(
                                sample = measureMatte(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT),
                                videoAspect = videoAspect,
                                containerAspect = width.toFloat() / height,
                            )
                        } else if (++failures >= MAX_CONSECUTIVE_COPY_FAILURES) {
                            // A surface that will not read back is not evidence
                            // for a crop, whatever it showed before.
                            estimator.reset()
                            engaged = false
                        }
                    }
                    delay(if (engaged) ENGAGED_INTERVAL_MS else SETTLING_INTERVAL_MS)
                }
                if (estimator.isAbandoned) engaged = false
            }
        } finally {
            bitmap.recycle()
        }
    }

    return engaged
}

/** One read-back of [surfaceView]'s buffer into [bitmap]; false on any refusal. */
private suspend fun copySurface(surfaceView: SurfaceView, bitmap: Bitmap): Boolean =
    suspendCancellableCoroutine { continuation ->
        val requested = runCatching {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                },
                Handler(Looper.getMainLooper()),
            )
        }
        // The surface can be torn down between the validity check and here.
        if (requested.isFailure && continuation.isActive) continuation.resume(false)
    }
