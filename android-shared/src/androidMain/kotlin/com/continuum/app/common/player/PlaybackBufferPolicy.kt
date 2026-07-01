package com.continuum.app.common.player

enum class PlaybackBufferMode(val wireValue: String, val label: String) {
    QuickStart("quick_start", "Quick start"),
    Balanced("balanced", "Balanced"),
    SmoothPlayback("smooth_playback", "Smooth playback");

    companion object {
        fun fromWire(value: String?): PlaybackBufferMode = entries.firstOrNull {
            it.wireValue == value
        } ?: SmoothPlayback
    }
}

data class PlaybackBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val prioritizeTimeOverSizeThresholds: Boolean,
) {
    companion object {
        fun forMode(mode: PlaybackBufferMode): PlaybackBufferPolicy = when (mode) {
            PlaybackBufferMode.QuickStart -> PlaybackBufferPolicy(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 3_000,
                bufferForPlaybackAfterRebufferMs = 6_000,
                targetBufferBytes = 160 * MIB,
                prioritizeTimeOverSizeThresholds = false,
            )
            PlaybackBufferMode.Balanced -> PlaybackBufferPolicy(
                minBufferMs = 60_000,
                maxBufferMs = 120_000,
                bufferForPlaybackMs = 7_000,
                bufferForPlaybackAfterRebufferMs = 12_000,
                targetBufferBytes = 192 * MIB,
                prioritizeTimeOverSizeThresholds = false,
            )
            PlaybackBufferMode.SmoothPlayback -> PlaybackBufferPolicy(
                minBufferMs = 45_000,
                maxBufferMs = 90_000,
                bufferForPlaybackMs = 5_000,
                bufferForPlaybackAfterRebufferMs = 15_000,
                targetBufferBytes = 256 * MIB,
                prioritizeTimeOverSizeThresholds = false,
            )
        }

        private const val MIB = 1024 * 1024
    }
}
