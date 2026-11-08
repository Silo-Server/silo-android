package org.siloserver.silo.common.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.siloserver.silo.player.DolbyVisionDetection

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
    @Volatile private var depthMs: Int = policy.minBufferMs
    @Volatile private var targetBytes: Int = policy.targetBufferBytes
    @Volatile private var isPlaying: Boolean = false

    private val stalledStartGuard = StalledStartLoadGuard()

    /**
     * Mirrors [Player.isPlaying] onto the playback thread. Paused, buffering
     * and focus-suppressed playback all read false, so none of them can count
     * as a stalled start; this is the same condition Media3's stuck-playing
     * detector uses.
     */
    val playingListener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@SiloLoadControl.isPlaying = isPlaying
        }
    }

    /** The forward buffer the memory budget currently affords, in ms. */
    internal fun currentDepthMs(): Int = depthMs

    /**
     * Keeps loading past the byte target while the player reports playing but
     * its position does not move.
     *
     * The byte target can stop the loader before a renderer holds enough to
     * start. A Shield passing DTS-HD through to HDMI is the measured case:
     * Media3 sizes that AudioTrack at one second of the format's 18 Mbps peak
     * (2.25 MB), and AudioFlinger does not start a compressed direct track
     * until the buffer is full. At DTS-HD MA's real ~3.7 Mbps that is about
     * five seconds of audio. A 91 Mbps Dolby Vision remux under the halved
     * Dolby Vision target holds about 4.4 seconds of muxed media, so the
     * loader stopped with the AudioTrack at 2.04 MB, audio never started, the
     * position never moved, nothing drained the buffer, and Media3 failed the
     * session as stuck ten seconds later. HDR10 from the same file, with the
     * full budget, crossed the threshold and played.
     *
     * The override only applies while the time rule still wants more media,
     * and stops at [stalledStartCeilingBytes], well inside the heap headroom
     * the Dolby Vision halving protects. Once the position moves, the ordinary
     * target applies again and the buffer drains back below it.
     */
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (super.shouldContinueLoading(parameters)) {
            stalledStartGuard.reset()
            return true
        }
        val stalled = stalledStartGuard.isStalled(
            nowMs = SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
            playbackPositionUs = parameters.playbackPositionUs,
        )
        if (!stalled || parameters.bufferedDurationUs >= policy.minBufferMs * 1_000L) return false
        val ceilingBytes = stalledStartCeilingBytes(targetBytes, policy.targetBufferBytes)
        val allocatedBytes = getAllocator(parameters.playerId).totalBytesAllocated
        if (allocatedBytes >= ceilingBytes) return false
        if (stalledStartGuard.markLogged()) {
            Log.i(
                TAG,
                "stalled_start_load allocated_bytes=$allocatedBytes ceiling_bytes=$ceilingBytes " +
                    "buffered_ms=${parameters.bufferedDurationUs / 1_000}",
            )
        }
        return true
    }

    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelections: Array<out ExoTrackSelection?>,
    ): Int {
        val sizingTracks = trackSelections.mapNotNull { selection ->
            selection?.let {
                val format = it.selectedFormat
                val trackType = MimeTypes.getTrackType(format.sampleMimeType)
                if (trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO) {
                    BufferSizingTrackBitrates(
                        averageBitrateBps = format.averageBitrate,
                        peakBitrateBps = format.peakBitrate,
                        latestNetworkEstimateBps = it.latestBitrateEstimate,
                        isDolbyVision = isDolbyVisionBufferTrack(
                            sampleMimeType = format.sampleMimeType,
                            codecs = format.codecs,
                        ),
                    )
                } else {
                    null
                }
            }
        }
        val selectedBitrateBps = selectBufferSizingBitrateBps(sizingTracks)
        val hasDolbyVision = sizingTracks.any(BufferSizingTrackBitrates::isDolbyVision)
        val budgetBytes = playbackBufferBudgetBytes(
            baseBudgetBytes = policy.targetBufferBytes,
            hasDolbyVision = hasDolbyVision,
            minimumBytes = MIN_TARGET_BUFFER_BYTES,
        )
        val fallback = super.calculateTargetBufferBytes(parameters, trackSelections)
        val result =
            computeBufferSizing(
                selectedBitrateBps = selectedBitrateBps,
                desiredDepthMs = policy.minBufferMs,
                minimumDepthMs = PlaybackBufferPolicy.MIN_DEPTH_MS,
                budgetBytes = budgetBytes,
                minimumBytes = MIN_TARGET_BUFFER_BYTES,
                unknownBitrateFallbackBytes = fallback,
            )
        depthMs = result.depth.ms
        targetBytes = result.target.bytes
        Log.d(
            TAG,
            "target_bytes=${result.target.bytes} budget_bytes=$budgetBytes " +
                "depth_ms=${result.depth.ms} dolby_vision=$hasDolbyVision",
        )
        return result.target.bytes
    }

    companion object {
        private const val TAG = "SiloLoadControl"
        internal const val MIN_TARGET_BUFFER_BYTES = 16 * 1024 * 1024
    }
}

/**
 * Tracks whether a playing player's position has stopped moving for
 * [stallMs]. Called on the playback thread by [SiloLoadControl].
 */
internal class StalledStartLoadGuard(
    private val stallMs: Long = STALLED_START_LOAD_AFTER_MS,
) {
    private var lastPositionUs: Long = C.TIME_UNSET
    private var stalledSinceMs: Long? = null
    private var logged = false

    fun reset() {
        lastPositionUs = C.TIME_UNSET
        stalledSinceMs = null
        logged = false
    }

    fun isStalled(nowMs: Long, isPlaying: Boolean, playbackPositionUs: Long): Boolean {
        if (!isPlaying || playbackPositionUs != lastPositionUs) {
            lastPositionUs = playbackPositionUs
            stalledSinceMs = if (isPlaying) nowMs else null
            logged = false
            return false
        }
        val stalledSince = stalledSinceMs ?: nowMs.also { stalledSinceMs = it }
        return nowMs - stalledSince >= stallMs
    }

    /** True the first time it is called during a stall, so the override logs once. */
    fun markLogged(): Boolean {
        if (logged) return false
        logged = true
        return true
    }
}

/** Long enough to skip ordinary start-up gaps, far inside Media3's 10 s stuck-playing timeout. */
internal const val STALLED_START_LOAD_AFTER_MS = 1_000L

/**
 * How far a stalled start may load: halfway from the byte target to the
 * undivided device budget. On a Shield that lets a Dolby Vision start grow
 * from 48 to 72 MiB, short of the 96 MiB that exhausted its heap. A route
 * whose target is already the full budget gets no extension. Media3 only
 * consults this before starting a load, so one chunk may land past it:
 * about 1 MiB for progressive streams, a whole segment for HLS.
 */
internal fun stalledStartCeilingBytes(targetBytes: Int, budgetBytes: Int): Int =
    targetBytes + (budgetBytes - targetBytes).coerceAtLeast(0) / 2

internal fun isDolbyVisionBufferTrack(
    sampleMimeType: String?,
    codecs: String?,
): Boolean = sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
    DolbyVisionDetection.isDolbyVision(videoCodec = codecs)

internal data class BufferSizingTrackBitrates(
    val averageBitrateBps: Int,
    val peakBitrateBps: Int,
    val latestNetworkEstimateBps: Long,
    val isDolbyVision: Boolean = false,
)

/**
 * Leaves extra Java-heap headroom while decoding Dolby Vision.
 *
 * Media3's allocator target is not the process's complete playback cost. The
 * extractor, codec bridge and OkHttp all need live Java allocations beside
 * that target. A real Shield with a 192 MiB growth limit reached 192/192 MiB
 * and crashed while the ordinary half-heap policy allowed a 96 MiB target for
 * a DV profile-5 stream. Halving only the Dolby Vision target keeps the
 * established buffer policy for every other route while leaving the decoder
 * path enough room to keep reading and reporting playback.
 */
internal fun playbackBufferBudgetBytes(
    baseBudgetBytes: Int,
    hasDolbyVision: Boolean,
    minimumBytes: Int,
): Int {
    require(baseBudgetBytes > 0)
    require(minimumBytes > 0)
    if (!hasDolbyVision) return baseBudgetBytes
    return (baseBudgetBytes / DOLBY_VISION_BUDGET_DIVISOR)
        .coerceAtLeast(minimumBytes.coerceAtMost(baseBudgetBytes))
}

private const val DOLBY_VISION_BUDGET_DIVISOR = 2

internal fun selectBufferSizingBitrateBps(
    tracks: List<BufferSizingTrackBitrates>,
): Long? {
    if (tracks.isEmpty()) return null
    val mediaBitrates = tracks.map { track ->
        track.averageBitrateBps.takeIf { it > 0 }?.toLong()
            ?: track.peakBitrateBps.takeIf { it > 0 }?.toLong()
    }
    val knownMediaBitrate = mediaBitrates.filterNotNull().sum()
    if (mediaBitrates.all { it != null }) return knownMediaBitrate

    // A partial sum is not the stream bitrate. Single-rendition HLS commonly
    // carries no video BANDWIDTH metadata while its AAC track does expose a
    // bitrate. Treating that audio-only number as the complete route sized an
    // 84 Mbps 4K stream to the 16 MiB floor: one segment filled the allocator,
    // DefaultLoadControl stopped loading, and playback remained buffering at
    // two seconds even though the server had produced the following segments.
    // Use one live estimate for the unknown media portion; when it is not yet
    // available, keep the result unknown so Media3's renderer-aware fallback
    // controls the target instead of an incomplete sum.
    val unknownMediaEstimate = tracks
        .filterIndexed { index, _ -> mediaBitrates[index] == null }
        .maxOfOrNull { it.latestNetworkEstimateBps }
        ?.takeIf { it > 0L }
        ?: return null
    return saturatingAdd(knownMediaBitrate, unknownMediaEstimate)
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

/**
 * The forward buffer this device can actually hold at this bitrate.
 *
 * Memory is finite, so a high-bitrate stream genuinely cannot be buffered as
 * deeply as a low-bitrate one. Computing that reduction here — rather than
 * letting the byte clamp truncate the buffer wherever it happens to land —
 * means the resulting depth is a number the code chose and can be reasoned
 * about, and it keeps maxBufferMs one idle window above a depth that is real.
 *
 * The budget is authoritative: when it affords less than [minimumDepthMs],
 * that shortfall is reported honestly rather than padded up to a floor the
 * loader cannot actually hold — a false-but-round number is worse than an
 * honest one `currentDepthMs()` can be trusted to reflect. [minimumDepthMs]
 * only bounds the *unknown-bitrate* branch below, where there is no bitrate
 * to derive a number from at all.
 *
 * The division by 115/100 mirrors the same overhead margin
 * [calculateBitrateTargetBufferBytes] multiplies back in when it turns a
 * depth into bytes. Without it, a budget-derived depth still produces a byte
 * figure that overshoots the budget once that margin is applied, silently
 * clamps back down to the ceiling, and erases the depth's effect on the byte
 * target — the two must agree, or reducing the depth changes nothing.
 */
internal fun affordableDepthMs(
    desiredDepthMs: Int,
    selectedBitrateBps: Long?,
    budgetBytes: Int,
    minimumDepthMs: Int,
): Int {
    val bitrate = selectedBitrateBps?.takeIf { it > 0L }
        ?: return desiredDepthMs.coerceAtLeast(minimumDepthMs)
    val affordableMs = budgetBytes.toLong() * 8L * 1_000L * 100L / (bitrate * 115L)
    return affordableMs.coerceAtMost(desiredDepthMs.toLong()).toInt()
}

/** A forward-buffer depth, in milliseconds. Wrapped so it cannot be confused with [BufferTargetBytes]. */
@JvmInline
internal value class BufferDepthMs(val ms: Int)

/** A load-control byte target. Wrapped so it cannot be confused with [BufferDepthMs]. */
@JvmInline
internal value class BufferTargetBytes(val bytes: Int)

/**
 * The composed result of sizing the buffer: the depth chosen and the bytes it
 * maps to. Both are wrapped value classes rather than bare `Int`s so that
 * assigning the wrong one to the wrong destination — e.g. storing the byte
 * target where the depth belongs — is a compile error, not a bug only a test
 * exercising the Media3 override could catch.
 */
internal data class BufferSizingResult(val depth: BufferDepthMs, val target: BufferTargetBytes)

/**
 * Composes [affordableDepthMs] and [calculateBitrateTargetBufferBytes] into the
 * single decision `calculateTargetBufferBytes` needs: how deep a buffer the
 * budget affords, and how many bytes that depth costs at this bitrate.
 *
 * Kept separate from the Media3 override so it can be tested directly without
 * constructing track selections — the override is a thin adapter over this.
 */
internal fun computeBufferSizing(
    selectedBitrateBps: Long?,
    desiredDepthMs: Int,
    minimumDepthMs: Int,
    budgetBytes: Int,
    minimumBytes: Int,
    unknownBitrateFallbackBytes: Int,
): BufferSizingResult {
    val depthMs =
        affordableDepthMs(
            desiredDepthMs = desiredDepthMs,
            selectedBitrateBps = selectedBitrateBps,
            budgetBytes = budgetBytes,
            minimumDepthMs = minimumDepthMs,
        )
    val targetBytes =
        calculateBitrateTargetBufferBytes(
            selectedBitrateBps = selectedBitrateBps,
            desiredForwardBufferMs = depthMs,
            // calculateBitrateTargetBufferBytes requires maximumBytes >=
            // minimumBytes. That holds today only because
            // MIN_MEMORY_BUDGET_BYTES and MIN_TARGET_BUFFER_BYTES are both
            // exactly 16 MiB — a coincidence a future change to either
            // constant could break, throwing IllegalArgumentException on the
            // playback thread during track selection. The relation is
            // restored by lowering the floor rather than raising the ceiling,
            // so the memory budget stays the binding limit: a device whose
            // budget is under the nominal floor gets a smaller buffer, never
            // one that overruns the heap it was allowed.
            minimumBytes = minimumBytes.coerceIn(1, budgetBytes.coerceAtLeast(1)),
            maximumBytes = budgetBytes.coerceAtLeast(1),
            unknownBitrateFallbackBytes = unknownBitrateFallbackBytes,
        )
    return BufferSizingResult(depth = BufferDepthMs(depthMs), target = BufferTargetBytes(targetBytes))
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
        try {
            Math.multiplyExact(
                Math.multiplyExact(bitrate, desiredForwardBufferMs.toLong()),
                115L,
            ) / (8L * 1_000L * 100L)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    } ?: unknownBitrateFallbackBytes.toLong()
    return desiredBytes.coerceIn(minimumBytes.toLong(), maximumBytes.toLong()).toInt()
}
