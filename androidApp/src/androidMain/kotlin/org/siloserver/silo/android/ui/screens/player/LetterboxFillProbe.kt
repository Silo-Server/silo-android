package org.siloserver.silo.android.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
 *  about one row, which [MATTE_MARGIN_FLOOR] holds back; 64 columns keep a small
 *  bright object near the picture edge from averaging away into apparent black. */
private const val SAMPLE_WIDTH = 64
private const val SAMPLE_HEIGHT = MATTE_SAMPLE_ROWS

/**
 * The evidence bar is fixed at [MATTE_SAMPLES_TO_SETTLE] frames, so the only
 * thing between playback start and a settled picture is how fast those frames
 * are collected — the interval, not the count. Gathering them back to back gets
 * the same proof inside a few hundred milliseconds, which reads as "it started
 * expanded" rather than "it grew".
 *
 * A read-back is a small composer blit into a 64x144 bitmap, off the render
 * thread and asynchronous, so a short burst of them does not contend with frame
 * production. The burst is bounded by [FAST_SAMPLE_BUDGET] anyway: a film
 * opening on a fade yields no usable frames, and must not spin at this rate for
 * the whole of a slow title sequence.
 */
private const val FAST_INTERVAL_MS = 100L
private const val FAST_SAMPLE_BUDGET = 20
private const val SETTLING_INTERVAL_MS = 400L
private const val SETTLED_INTERVAL_MS = 750L

/** Read-backs that may fail before the expansion is surrendered. */
private const val MAX_CONSECUTIVE_COPY_FAILURES = 4

/**
 * Watches the decoded frame and reports the aspect of the content rect to fit —
 * see [LetterboxMatte.kt] for the rule and why fitting it can never cut picture.
 * Returns [videoAspect] itself whenever there is nothing to discount, which
 * renders as an ordinary fit.
 *
 * Frames come from `PixelCopy` against the video SurfaceView, which reads that
 * surface's own buffer. Nothing about the playback pipeline changes: no
 * TextureView, no GL effects chain, no second decoder, no extra network — so
 * tunneled decoding, HDR10 and Dolby Vision passthrough are untouched, which
 * they would not be if the frames were routed through a readable path instead.
 *
 * [cacheKey] names the exact file for [LetterboxMatteCache]. A remembered matte
 * applies during composition, before the first frame is presented, so a replay
 * or a resume opens at its final size; live frames then take over completely.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberLetterboxContentAspect(
    playerView: PlayerView?,
    enabled: Boolean,
    videoAspect: Float,
    mediaKey: Any?,
    cacheKey: String?,
): Float {
    val context = LocalContext.current
    val cache = remember(context) { LetterboxMatteCache(context.applicationContext) }

    // Resolved in the same composition that first lays the surface out, so the
    // very first presented frame is already at its final size. Keyed on the
    // media so a new item never inherits the previous one's answer.
    var contentAspect by remember(cacheKey, enabled, videoAspect) {
        val remembered = if (enabled && videoAspect > 0f && cacheKey != null) {
            cache.read(cacheKey)
        } else {
            null
        }
        mutableFloatStateOf(
            if (remembered != null) {
                contentAspect(videoAspect, safeMatteFraction(remembered))
            } else {
                videoAspect
            },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(playerView, enabled, videoAspect, mediaKey, cacheKey, lifecycleOwner) {
        if (!enabled || playerView == null || videoAspect <= 0f) {
            contentAspect = videoAspect
            return@LaunchedEffect
        }

        val estimator = LetterboxFillEstimator()
        cacheKey?.let { key -> cache.read(key)?.let(estimator::seed) }
        contentAspect = estimator.contentAspectFor(videoAspect)

        var persistedMatte: Float? = null
        // Never recycled, deliberately. `PixelCopy` has no cancellation path, so
        // a request still outstanding when this effect is disposed may yet write
        // into the destination — recycling it out from under the platform turns
        // a harmless abandoned read-back into a native write to freed memory.
        // 36KB waiting for the collector is the cheaper side of that trade.
        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        // RESUMED, not STARTED: a backgrounded or picture-in-picture player
        // has no reason to be reading frames back.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var failures = 0
            var samplesTaken = 0
            while (isActive) {
                val surfaceView = playerView.videoSurfaceView as? SurfaceView
                if (surfaceView != null && surfaceView.holder.surface?.isValid == true) {
                    if (copySurface(surfaceView, bitmap)) {
                        failures = 0
                        samplesTaken++
                        bitmap.getPixels(
                            pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT,
                        )
                        contentAspect = estimator.onSample(
                            sample = measureMatte(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT),
                            codedAspect = videoAspect,
                        )
                        // Record the running minimum as it settles, not just
                        // at teardown: playback usually ends with the process
                        // being killed, which never reaches a finally block.
                        // Only a settled estimate is worth remembering — an
                        // unsettled one is a single frame's guess, and the next
                        // play would apply it from ITS first frame, bypassing
                        // the very settling that held it back here.
                        val measured = estimator.observedMatte?.takeIf { estimator.isSettled }
                        if (cacheKey != null && measured != null && measured != persistedMatte) {
                            persistedMatte = measured
                            cache.write(cacheKey, measured)
                        }
                    } else if (++failures >= MAX_CONSECUTIVE_COPY_FAILURES) {
                        // A surface that will not read back is not evidence
                        // for a crop, whatever it showed or remembered before.
                        // A secure or otherwise unreadable surface refuses for
                        // good, so give up rather than asking again every
                        // interval for the rest of playback; `repeatOnLifecycle`
                        // runs this block afresh on the next resume, which is
                        // recovery enough for a surface merely being torn down.
                        estimator.reset()
                        contentAspect = videoAspect
                        break
                    }
                }
                delay(
                    when {
                        estimator.isSettled -> SETTLED_INTERVAL_MS
                        samplesTaken < FAST_SAMPLE_BUDGET -> FAST_INTERVAL_MS
                        else -> SETTLING_INTERVAL_MS
                    },
                )
            }
        }
    }

    return contentAspect
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
