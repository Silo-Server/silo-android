package org.siloserver.silo.common.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
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
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
                else -> error("Unexpected delegate call: ${method.name}")
            }
        } as AudioSink
        val sink = PassthroughSuppressingAudioSink(delegate)

        PassthroughSuppressionRegistry.beginAttempt("sink-format-attempt")
        assertTrue(PassthroughSuppressionRegistry.suppressForSinglePcmRetry(MimeTypes.AUDIO_TRUEHD, 8))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(trueHdEightChannel))
        assertFalse(sink.supportsFormat(trueHdEightChannel))
    }
}
