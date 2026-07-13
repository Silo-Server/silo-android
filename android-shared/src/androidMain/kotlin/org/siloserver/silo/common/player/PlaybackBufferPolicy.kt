package org.siloserver.silo.common.player

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
        fun forMode(
            mode: PlaybackBufferMode,
            deviceProfile: PlaybackBufferDeviceProfile = PlaybackBufferDeviceProfile.Unknown,
        ): PlaybackBufferPolicy = when (mode) {
            PlaybackBufferMode.QuickStart -> PlaybackBufferPolicy(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 2_000,
                bufferForPlaybackAfterRebufferMs = 6_000,
                targetBufferBytes = targetBufferBytes(deviceProfile, low = 32, medium = 64, roomy = 128),
                prioritizeTimeOverSizeThresholds = false,
            )
            PlaybackBufferMode.Balanced -> PlaybackBufferPolicy(
                minBufferMs = 50_000,
                maxBufferMs = 120_000,
                bufferForPlaybackMs = 3_000,
                bufferForPlaybackAfterRebufferMs = 10_000,
                targetBufferBytes = targetBufferBytes(deviceProfile, low = 48, medium = 96, roomy = 160),
                prioritizeTimeOverSizeThresholds = false,
            )
            PlaybackBufferMode.SmoothPlayback -> PlaybackBufferPolicy(
                minBufferMs = 90_000,
                maxBufferMs = 180_000,
                bufferForPlaybackMs = 5_000,
                bufferForPlaybackAfterRebufferMs = 15_000,
                targetBufferBytes = targetBufferBytes(deviceProfile, low = 64, medium = 128, roomy = 192),
                prioritizeTimeOverSizeThresholds = false,
            )
        }

        private fun targetBufferBytes(
            deviceProfile: PlaybackBufferDeviceProfile,
            low: Int,
            medium: Int,
            roomy: Int,
        ): Int = when {
            deviceProfile.isLowRamDevice || deviceProfile.memoryClassMb in 1 until 192 -> low * MIB
            deviceProfile.memoryClassMb <= 0 -> low * MIB
            deviceProfile.memoryClassMb in 192 until 384 -> medium * MIB
            else -> roomy * MIB
        }

        private const val MIB = 1024 * 1024
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
