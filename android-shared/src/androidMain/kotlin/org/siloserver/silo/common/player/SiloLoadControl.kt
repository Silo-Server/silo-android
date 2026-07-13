package org.siloserver.silo.common.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Media3 load control with a bitrate-scaled, heap-bounded allocation target.
 *
 * Time thresholds still decide startup/rebuffer behavior. The byte target is a
 * safety rail: low-bitrate video is no longer forced to allocate a 4K-sized
 * buffer, while a high-bitrate remux can grow up to the device-class cap and
 * never consume the entire app heap trying to satisfy 50 seconds literally.
 */
@UnstableApi
class SiloLoadControl(
    private val policy: PlaybackBufferPolicy,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    policy.minBufferMs,
    policy.minBufferMs,
    policy.maxBufferMs,
    policy.maxBufferMs,
    policy.bufferForPlaybackMs,
    policy.bufferForPlaybackMs,
    policy.bufferForPlaybackAfterRebufferMs,
    policy.bufferForPlaybackAfterRebufferMs,
    C.LENGTH_UNSET,
    policy.prioritizeTimeOverSizeThresholds,
    policy.prioritizeTimeOverSizeThresholds,
    0,
    false,
) {
    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelections: Array<out ExoTrackSelection?>,
    ): Int {
        val selectedBitrateBps = trackSelections.sumOf { selection ->
            selection?.selectedBitrateBps() ?: 0L
        }.takeIf { it > 0L }
        val fallback = super.calculateTargetBufferBytes(parameters, trackSelections)
        return calculateBitrateTargetBufferBytes(
            selectedBitrateBps = selectedBitrateBps,
            desiredForwardBufferMs = policy.minBufferMs,
            minimumBytes = MIN_TARGET_BUFFER_BYTES,
            maximumBytes = policy.targetBufferBytes,
            unknownBitrateFallbackBytes = fallback,
        )
    }

    private fun ExoTrackSelection.selectedBitrateBps(): Long {
        val format = selectedFormat
        return listOf(
            latestBitrateEstimate,
            format.averageBitrate.toLong(),
            format.peakBitrate.toLong(),
            format.bitrate.toLong(),
        ).maxOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    companion object {
        internal const val MIN_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    }
}

internal fun calculateBitrateTargetBufferBytes(
    selectedBitrateBps: Long?,
    desiredForwardBufferMs: Int,
    minimumBytes: Int,
    maximumBytes: Int,
    unknownBitrateFallbackBytes: Int,
): Int {
    require(minimumBytes > 0)
    require(maximumBytes >= minimumBytes)
    val desiredBytes = selectedBitrateBps?.takeIf { it > 0L }?.let { bitrate ->
        // 15% allows for container/segment overhead and ordinary bitrate
        // variance without turning a stream's nominal bitrate into a promise.
        (bitrate * desiredForwardBufferMs.toLong() * 115L) / (8L * 1_000L * 100L)
    } ?: unknownBitrateFallbackBytes.toLong()
    return desiredBytes.coerceIn(minimumBytes.toLong(), maximumBytes.toLong()).toInt()
}
