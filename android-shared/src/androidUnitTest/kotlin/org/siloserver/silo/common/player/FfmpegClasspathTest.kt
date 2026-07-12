package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against the `media3-decoder-ffmpeg-1.10.1.aar` being dropped from
 * `android-shared/libs/` or the `implementation(files(...))` line being
 * removed from `android-shared/build.gradle.kts`. Either regression would
 * silently revert this module to the pre-FFmpeg state (platform decoders
 * only → TRANSCODE fallback on devices without E-AC-3 / TrueHD / DTS
 * MediaCodec decoders).
 */
class FfmpegClasspathTest {

    @Test
    fun `FfmpegAudioRenderer is reachable via Class-forName`() {
        val result = runCatching {
            Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer")
        }
        assertTrue(
            result.isSuccess,
            "media3-decoder-ffmpeg AAR is missing from the classpath. " +
                "Check android-shared/libs/media3-decoder-ffmpeg-1.10.1.aar " +
                "and the implementation(files(\"libs/...\")) dependency in " +
                "android-shared/build.gradle.kts.",
        )
    }

    @Test
    fun `FfmpegAudioSupport isAvailable agrees with Class-forName`() {
        // Whole point of this helper — consumers that need to know whether
        // FFmpeg is usable must get the same answer as what the renderer
        // factory sees.
        assertTrue(
            FfmpegAudioSupport.isAvailable(),
            "FfmpegAudioSupport.isAvailable() returned false even though " +
                "FfmpegAudioRenderer is on the classpath.",
        )
    }
}
