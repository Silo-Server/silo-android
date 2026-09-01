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

    /**
     * Media3 1.11 turned both `configure` overloads into default interface
     * methods that call each other. A wrapper that forwards only abstract
     * members never reaches the delegate and overflows the stack on the first
     * audio format change. The wrapper must hand every call to the real sink.
     */
    @Test
    fun configureReachesTheDelegateInsteadOfRecursing() {
        val configured = mutableListOf<Format>()
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "configure" -> {
                    val config = args?.firstOrNull()
                    val format = when (config) {
                        is Format -> config
                        is AudioSink.AudioSinkConfig -> config.format
                        else -> null
                    }
                    format?.let(configured::add)
                    null
                }
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink
        val sink = PassthroughSuppressingAudioSink(delegate)

        sink.configure(trueHdEightChannel, 0, null)

        assertEquals(listOf(trueHdEightChannel), configured)
    }

    @Test
    fun suppressionIsBoundedToOneLayoutAndOneAttempt() {
        PassthroughSuppressionRegistry.beginAttempt("attempt-a")
        assertTrue(PassthroughSuppressionRegistry.suppressForSinglePcmRetry(MimeTypes.AUDIO_TRUEHD, 8))
        assertTrue(PassthroughSuppressionRegistry.isSuppressed(trueHdEightChannel))
        val diagnostics = PassthroughSuppressionRegistry.diagnosticsSnapshot()
        assertEquals(listOf("audio/true-hd:8"), diagnostics.suppressedFormats)
        assertTrue(diagnostics.retryUsed)
        assertFalse(diagnostics.toString().contains("attempt-a"))
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
