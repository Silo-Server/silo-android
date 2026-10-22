package org.siloserver.silo.common.player

/**
 * Buffering policy derived from what the player can observe. There is no user
 * setting and no server-supplied mode: the numbers follow from the device and
 * the stream.
 */
data class PlaybackBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
) {
    init {
        require(maxBufferMs - minBufferMs == MAX_LOAD_IDLE_MS) {
            "idle window must be exactly MAX_LOAD_IDLE_MS; maxBufferMs is derived, never written by hand"
        }
    }

    companion object {
        /**
         * How long the load control may stop reading the socket, in MEDIA
         * time.
         *
         * DefaultLoadControl fills to maxBufferMs, then requests nothing until
         * the buffer drains below minBufferMs — so the gap between them is
         * literally how long the connection sits idle, in the player's media
         * clock. Upstream proxies close an idle response body based on WALL
         * CLOCK time: nginx's send_timeout defaults to 60s. The old
         * hand-written 50s/120s pair left a 70s gap and dropped the connection
         * every time the buffer filled on a long direct-play file.
         *
         * Those two clocks only agree at 1.0x. DefaultLoadControl scales
         * minBufferUs for speeds ABOVE 1.0, but not below it, and the UI
         * offers rates down to [SLOWEST_PLAYBACK_SPEED] (0.5x, see
         * SPEED_PRESETS in TvAudiobookSpeedPanel.kt and the clamp in
         * AudiobookSpeedSheet.kt) for audiobooks, which share this load
         * control. At 0.5x, one media-time second of idle window takes two
         * wall-clock seconds to elapse — so a naive 30s media-time window
         * becomes 60s of wall clock, exactly nginx's default send_timeout,
         * with zero margin.
         *
         * 15_000 is that same 30s wall-clock budget scaled down by the
         * slowest rate (30_000 * SLOWEST_PLAYBACK_SPEED = 15_000): at 0.5x it
         * stretches back out to 30s of wall clock, half of
         * ASSUMED_PROXY_SEND_TIMEOUT_MS, so the window holds at every speed
         * the UI offers, not just 1.0x.
         *
         * maxBufferMs is therefore never written by hand; it is always
         * minBufferMs + this. Depth can grow without ever widening the window.
         */
        const val MAX_LOAD_IDLE_MS = 15_000

        /**
         * The slowest rate the UI lets a viewer select (see SPEED_PRESETS in
         * TvAudiobookSpeedPanel.kt and the 0.5f..3.0f clamp in
         * AudiobookSpeedSheet.kt). Named so the derivation of
         * MAX_LOAD_IDLE_MS above isn't a bare magic number.
         */
        const val SLOWEST_PLAYBACK_SPEED = 0.5

        /** The timeout MAX_LOAD_IDLE_MS is chosen to stay clear of. */
        const val ASSUMED_PROXY_SEND_TIMEOUT_MS = 60_000

        /** Never buffer less than this, however constrained the device. */
        const val MIN_DEPTH_MS = 20_000

        /**
         * Never buffer more than this even when memory allows. Past a few
         * minutes we are mostly prefetching content the viewer may seek away
         * from — wasted bandwidth, and wasted allowance on mobile data.
         */
        const val MAX_DEPTH_MS = 180_000

        private const val START_MS = 2_000

        /**
         * After a stall the viewer is watching a spinner, so the cushion we
         * rebuild before resuming is deliberately small.
         */
        const val REBUFFER_MS = 5_000

        fun forConditions(
            deviceProfile: PlaybackBufferDeviceProfile = PlaybackBufferDeviceProfile.Unknown,
        ): PlaybackBufferPolicy {
            val depthMs = MAX_DEPTH_MS
            return PlaybackBufferPolicy(
                minBufferMs = depthMs,
                maxBufferMs = depthMs + MAX_LOAD_IDLE_MS,
                bufferForPlaybackMs = START_MS,
                bufferForPlaybackAfterRebufferMs = REBUFFER_MS,
                targetBufferBytes = memoryBudgetBytes(deviceProfile),
                prioritizeTimeOverSizeThresholds = false,
            )
        }

        /**
         * The byte ceiling this device can afford. SiloLoadControl sizes the
         * real target from the stream's bitrate and clamps it to this.
         *
         * This is a fraction of the app's own heap rather than a pick from
         * fixed tiers. A fixed tier either starves a small-heap device (a
         * flat 48 MiB floor is half of a 96 MB heap — a real OOM risk) or
         * leaves a large-heap device's headroom unused (a flat 160 MiB
         * ceiling caps a 1 GB heap the same as a 384 MB one). Scaling with
         * memoryClassMb keeps the budget proportionate at both ends without
         * hand-picking where the tier boundaries should sit.
         *
         * The fraction is half the heap, not a quarter. Measured hardware:
         * the NVIDIA Shield reports memoryClass=192MB and the Google TV
         * Streamer reports memoryClass=384MB, and neither is flagged
         * low-RAM. A quarter-heap rule gives the Shield 48 MiB and the
         * Streamer 96 MiB — LESS buffer than each device shipped with before
         * this policy existed (96 MiB and 160 MiB respectively), the exact
         * opposite of scaling correctly from small-memory devices to large
         * ones. Half the heap gives the Shield 96 MiB and the Streamer
         * 192 MiB (at the cap), both at or above their prior fixed values.
         */
        internal fun memoryBudgetBytes(deviceProfile: PlaybackBufferDeviceProfile): Int {
            val proportionalBytes =
                if (deviceProfile.memoryClassMb > 0) {
                    deviceProfile.memoryClassMb.toLong() * MIB / MEMORY_BUDGET_HEAP_DIVISOR
                } else {
                    null
                }

            if (deviceProfile.isLowRamDevice || deviceProfile.memoryClassMb <= 0) {
                // A flat 24 MiB is the conservative fallback for a heap size
                // we don't trust at all (unknown, or explicitly flagged
                // low-RAM). But when memoryClassMb IS known, even on a
                // low-RAM device, ignoring it can hand out MORE than the
                // proportional share would — a low-RAM stick reporting 48MB
                // would get 24 MiB verbatim, half its heap, exactly the flaw
                // the proportional rule exists to remove. Take the smaller
                // of the two so the flat fallback only ever tightens the
                // budget, never loosens it.
                return proportionalBytes
                    ?.coerceAtMost(LOW_RAM_MEMORY_BUDGET_BYTES.toLong())
                    ?.toInt()
                    ?: LOW_RAM_MEMORY_BUDGET_BYTES
            }
            return checkNotNull(proportionalBytes)
                .coerceIn(MIN_MEMORY_BUDGET_BYTES.toLong(), MAX_MEMORY_BUDGET_BYTES.toLong())
                .toInt()
        }

        private const val MIB = 1024 * 1024

        /** The budget is this fraction (1/2) of the app heap — see [memoryBudgetBytes]. */
        private const val MEMORY_BUDGET_HEAP_DIVISOR = 2L

        /** Never budget less than this, however small the heap. */
        private const val MIN_MEMORY_BUDGET_BYTES = 16 * MIB

        /** Never budget more than this even on a very large heap. */
        private const val MAX_MEMORY_BUDGET_BYTES = 192 * MIB

        /**
         * Fixed fallback for devices that report no usable heap size, or that
         * flag themselves as low-RAM outright — conservative rather than
         * proportional, since half of an unknown heap is not a number worth
         * trusting. When memoryClassMb is known, this is only a ceiling on
         * the proportional share (see [memoryBudgetBytes]), not a value
         * handed out regardless of what the device actually reports.
         */
        private const val LOW_RAM_MEMORY_BUDGET_BYTES = 24 * MIB
    }
}

data class PlaybackBufferDeviceProfile(
    val memoryClassMb: Int,
    val isLowRamDevice: Boolean,
) {
    companion object {
        val Unknown = PlaybackBufferDeviceProfile(memoryClassMb = 0, isLowRamDevice = false)
    }
}
