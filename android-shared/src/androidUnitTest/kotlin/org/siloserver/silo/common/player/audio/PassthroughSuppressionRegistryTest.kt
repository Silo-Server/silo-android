package org.siloserver.silo.common.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassthroughSuppressionRegistryTest {
    private val trueHdEightChannel = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
        .setChannelCount(8)
        .build()

    @Test
    fun suppressionIsBoundedToOneLayoutAndOneAttempt() {
        PassthroughSuppressionRegistry.beginAttempt("attempt-a")
        assertTrue(PassthroughSuppressionRegistry.suppressForSinglePcmRetry(MimeTypes.AUDIO_TRUEHD, 8))
        assertTrue(PassthroughSuppressionRegistry.isSuppressed(trueHdEightChannel))
        assertFalse(PassthroughSuppressionRegistry.suppressForSinglePcmRetry(MimeTypes.AUDIO_TRUEHD, 8))

        PassthroughSuppressionRegistry.beginAttempt("attempt-b")
        assertFalse(PassthroughSuppressionRegistry.isSuppressed(trueHdEightChannel))
    }

    @Test
    fun suppressingSinkMarksEncodedFormatUnsupportedToForcePcmDecode() {
        val source = File(
            "src/androidMain/kotlin/org/siloserver/silo/common/player/audio/PassthroughSuppressionRegistry.kt",
        ).readText()
        val suppressedBranch = source.substringAfter("if (PassthroughSuppressionRegistry.isSuppressed(format))")
            .substringBefore("} else")
        assertTrue(suppressedBranch.contains("AudioSink.SINK_FORMAT_UNSUPPORTED"))
        assertFalse(suppressedBranch.contains("SINK_FORMAT_SUPPORTED_WITH_TRANSCODING"))
    }
}
