package org.siloserver.silo.common.ui.components

import android.media.MediaCodecInfo.VideoCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RawRes
import org.siloserver.silo.common.R

/**
 * The startup splash ships at two tiers because weak TV boxes garble anything
 * richer than they can actually decode. An onn 4K stick (Realtek, API 34)
 * accepted a 4K60 splash and then presented half-reconstructed frames — the
 * logo drew with its lower macroblock rows missing. Its decoder declares
 * `performance-point-3840x2160 30-30` and a 1,879,200 blocks/sec ceiling
 * against the 1,944,000 that 4K60 needs, so the capability data had the answer
 * all along; nothing was asking it.
 *
 * [startupSplashRes] asks. Devices whose AVC decoder covers 1080p60 get the
 * HD asset; everything else gets the 720p30 baseline, which is under every
 * ceiling we've seen and still oversized for the box the splash draws into.
 */
@RawRes
fun startupSplashRes(): Int =
    if (supportsAvc1080p60()) R.raw.startup_splash_hd else R.raw.startup_splash

private const val HD_WIDTH = 1920
private const val HD_HEIGHT = 1080
private const val HD_FRAME_RATE = 60

/**
 * True when some decoder claims 1080p60 AVC. Prefers hardware decoders where
 * the platform can identify them: a software decoder's advertised performance
 * points describe a CPU that may be busy doing everything else during a cold
 * launch, which is exactly when the splash plays.
 */
private fun supportsAvc1080p60(): Boolean = runCatching {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filterNot { it.isEncoder }
    val preferred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        decoders.filter { it.isHardwareAccelerated }.ifEmpty { decoders }
    } else {
        decoders
    }
    preferred.any { info ->
        val videoCapabilities = runCatching {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull()?.videoCapabilities
        videoCapabilities?.coversHd() == true
    }
}.getOrDefault(false)

private fun VideoCapabilities.coversHd(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val points = supportedPerformancePoints
        if (!points.isNullOrEmpty()) {
            val target = VideoCapabilities.PerformancePoint(HD_WIDTH, HD_HEIGHT, HD_FRAME_RATE)
            return points.any { it.covers(target) }
        }
    }
    // Pre-Q, or a decoder that publishes no performance points: fall back to
    // the size/rate limits, which encode the same blocks-per-second ceiling.
    return areSizeAndRateSupported(HD_WIDTH, HD_HEIGHT, HD_FRAME_RATE.toDouble())
}
