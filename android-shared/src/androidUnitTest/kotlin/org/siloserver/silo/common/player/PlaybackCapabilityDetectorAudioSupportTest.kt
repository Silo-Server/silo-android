package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCapabilityDetectorAudioSupportTest {

    private fun decoder(mime: String, codec: String, maxChannels: Int?, name: String = "c2.test") =
        PlatformAudioDecodeCapability(mime, codec, name, maxChannels)

    private val sixChannelEac3 = listOf(decoder(MimeTypes.AUDIO_E_AC3, "eac3", 6))
    private val stereoOnlyEac3 = listOf(decoder(MimeTypes.AUDIO_E_AC3, "eac3", 2))

    /**
     * The live failure: four ERROR_CODE_DECODING_FAILED on Pixel 7 Pro and
     * Pixel 10 Pro XL, every one a six-channel track, because the codec list
     * claimed E-AC3 was decodable without ever asking how many channels the
     * decoder took.
     */
    @Test
    fun `six channel E-AC3 is refused by a stereo-only decoder`() {
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, stereoOnlyEac3, ffmpegAvailable = false),
        )
    }

    @Test
    fun `two channel E-AC3 is accepted by the same decoder`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 2, stereoOnlyEac3, ffmpegAvailable = false),
        )
    }

    @Test
    fun `six channel E-AC3 is accepted by a six channel decoder`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, sixChannelEac3, ffmpegAvailable = false),
        )
    }

    /** Several decoders can expose one MIME; the widest is the one that runs. */
    @Test
    fun `the widest decoder for a MIME decides`() {
        val both = listOf(
            decoder(MimeTypes.AUDIO_E_AC3, "eac3", 2, "c2.narrow"),
            decoder(MimeTypes.AUDIO_E_AC3, "eac3", 6, "c2.wide"),
        )
        assertTrue(canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, both, ffmpegAvailable = false))
    }

    /** A limit must never be borrowed across MIME types. */
    @Test
    fun `a wide decoder for another codec does not vouch for this one`() {
        val aacOnly = listOf(decoder(MimeTypes.AUDIO_AAC, "aac", 8))
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, aacOnly, ffmpegAvailable = false),
        )
    }

    /** A limit is still never borrowed from an unrelated codec. */
    @Test
    fun `AAC support does not vouch for E-AC3`() {
        assertFalse(
            canDecodeAudio(
                MimeTypes.AUDIO_E_AC3,
                6,
                listOf(decoder(MimeTypes.AUDIO_AAC, "aac", 8)),
                ffmpegAvailable = false,
            ),
        )
    }

    /**
     * A device that will not state a limit is not thereby claiming an unlimited
     * one, but refusing everything it reports would reject tracks that play
     * fine. Existence answers the question; the limit does not exist to compare.
     */
    @Test
    fun `an unstated limit does not refuse the codec`() {
        val unknown = listOf(decoder(MimeTypes.AUDIO_E_AC3, "eac3", null))
        assertTrue(canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, unknown, ffmpegAvailable = false))
    }

    @Test
    fun `an unknown channel count asks only whether the codec exists`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 0, stereoOnlyEac3, ffmpegAvailable = false),
        )
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 0, emptyList(), ffmpegAvailable = false),
        )
    }

    @Test
    fun `DTS HD is decodable when FFmpeg renderer is available`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_DTS_HD, 6, emptyList(), ffmpegAvailable = true),
        )
    }

    @Test
    fun `DTS HD is not decodable without FFmpeg renderer`() {
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_DTS_HD, 6, emptyList(), ffmpegAvailable = false),
        )
    }

    /**
     * EXTENSION_RENDERER_MODE_ON puts the platform renderer first, but order is
     * only the tie-break: the track selector takes whichever renderer reports
     * the greatest format support. MediaCodecAudioRenderer answers
     * FORMAT_EXCEEDS_CAPABILITIES for a channel count its decoder will not
     * take, and FfmpegAudioRenderer answers FORMAT_HANDLED — so FFmpeg wins
     * this one and the track really does play.
     */
    @Test
    fun `FFmpeg rescues a channel count the platform decoder refuses`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, stereoOnlyEac3, ffmpegAvailable = true),
        )
    }

    /** Without it, the same device cannot play the track. */
    @Test
    fun `without FFmpeg the platform channel limit stands`() {
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, stereoOnlyEac3, ffmpegAvailable = false),
        )
    }

    @Test
    fun `FFmpeg fills a gap the platform cannot cover`() {
        assertTrue(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, emptyList(), ffmpegAvailable = true),
        )
    }

    /**
     * Media3 soft-matches JOC onto a plain E-AC3 decoder
     * (MediaCodecUtil.getAlternativeCodecMimeType), so refusing it here would
     * reject content the player would happily have handled.
     */
    @Test
    fun `JOC is accepted by a plain E-AC3 decoder, as Media3 does`() {
        assertTrue(
            platformCanDecodeAudio(MimeTypes.AUDIO_E_AC3_JOC, 6, sixChannelEac3),
        )
    }

    @Test
    fun `JOC still respects that decoders channel limit`() {
        assertFalse(
            platformCanDecodeAudio(MimeTypes.AUDIO_E_AC3_JOC, 6, stereoOnlyEac3),
        )
    }

    /** Codec absence and an unusable layout are different verdicts. */
    @Test
    fun `a channel-limited decoder still counts as having the codec`() {
        assertTrue(
            platformCanDecodeAudio(MimeTypes.AUDIO_E_AC3, 0, stereoOnlyEac3),
            "asking with no channel count answers only whether the codec exists",
        )
        assertFalse(
            platformCanDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, stereoOnlyEac3),
        )
    }

    @Test
    fun `an absent codec is not decodable at all`() {
        assertFalse(
            canDecodeAudio(MimeTypes.AUDIO_E_AC3, 6, emptyList(), ffmpegAvailable = false),
        )
    }

    @Test
    fun `codec names map only for MIME types this project tracks`() {
        assertEquals("eac3", platformAudioCodecName(MimeTypes.AUDIO_E_AC3))
        assertEquals("eac3_joc", platformAudioCodecName(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(null, platformAudioCodecName(MimeTypes.AUDIO_DTS_HD))
    }
}
