package com.continuum.app.common.player.backend

/**
 * Device-class floor for enabling the MPV backend. Pure (primitive inputs) so
 * it is unit-testable without Android; production call sites pass
 * [android.os.Build.VERSION.SDK_INT] and [android.os.Build.SUPPORTED_ABIS].
 */
object MpvDeviceFloor {
    /**
     * MPV uses the published dev.jdtech.mpv:libmpv artifacts, which require API 26+.
     * Android 7 (API 24-25) remains supported through the Media3 path only.
     */
    const val MIN_SDK_FOR_MPV = 26

    fun isMpvSupported(sdkInt: Int, supportedAbis: List<String>): Boolean {
        if (sdkInt < MIN_SDK_FOR_MPV) return false
        // Require a 64-bit ABI for the initial rollout; ARMv7-only TV boxes are
        // revisited after the device matrix proves the native libs there.
        return supportedAbis.any { it == "arm64-v8a" || it == "x86_64" }
    }
}
