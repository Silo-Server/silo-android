package org.siloserver.silo.common.player.audio

import android.media.AudioFormat
import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdmiSinkDtsSupportTest {
    private val pcm = AudioFormat.ENCODING_PCM_16BIT
    private val iec = AudioFormat.ENCODING_IEC61937

    /** LG 2025 TV: Dolby codecs listed, no DTS — the onn 4K Pro silent-DTS case. */
    @Test
    fun dolbyOnlySinkDoesNotDecodeDts() {
        val lgC5 = intArrayOf(pcm, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC, iec)
        assertFalse(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(lgC5)))
    }

    @Test
    fun sinkListingDtsDecodesDts() {
        val avr = intArrayOf(pcm, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_DTS_HD)
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(avr)))
    }

    /** API 34 DTS-HD MA (29) arrives as a literal on older platforms too. */
    @Test
    fun dtsHdMaLiteralCountsAsDts() {
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(intArrayOf(pcm, AudioFormat.ENCODING_AC3, 29))))
    }

    /** PCM and IEC 61937 name no codec, so the platform keeps the decision. */
    @Test
    fun undescribedSinkKeepsPlatformBehavior() {
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(intArrayOf(pcm, iec))))
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(intArrayOf())))
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(emptyList()))
    }

    @Test
    fun anyUndescribedSinkKeepsPlatformBehavior() {
        val dolbyOnly = intArrayOf(pcm, AudioFormat.ENCODING_AC3)
        assertTrue(HdmiSinkDtsSupport.sinkAdvertisesDts(listOf(dolbyOnly, intArrayOf(pcm))))
    }

    @Test
    fun dtsGoesToFfmpegOnlyWhenTheSinkLacksDtsAndFfmpegDecodesIt() {
        val ffmpegDecodes = { _: String -> true }
        assertTrue(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_DTS_HD, ffmpegDecodes) { false })
        assertTrue(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_DTS, ffmpegDecodes) { false })
        assertFalse(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_DTS_HD, ffmpegDecodes) { true })
        assertFalse(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_DTS_HD, { false }) { false })
    }

    @Test
    fun nonDtsCodecsNeverGoToFfmpeg() {
        val ffmpegDecodes = { _: String -> true }
        assertFalse(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_TRUEHD, ffmpegDecodes) { false })
        assertFalse(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_E_AC3, ffmpegDecodes) { false })
        assertFalse(HdmiSinkDtsSupport.shouldDecodeWithFfmpeg(null, ffmpegDecodes) { false })
    }
}
