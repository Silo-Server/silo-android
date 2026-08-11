package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.AudioPassthroughEntry

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

/**
 * The sink carrying a codec says nothing about it carrying that many channels
 * of it. maxChannels is a maximum across ALL codecs, so a receiver taking
 * 8-channel TrueHD but only 6-channel E-AC-3 reports 8 — and would wave through
 * an 8-channel E-AC-3 track its own entry excludes.
 */
class SinkPassthroughLayoutTest {

    private val receiver = AudioPassthroughCapabilities(
        passthroughCodecs = listOf("truehd", "eac3"),
        maxChannels = 8,
        entries = listOf(
            AudioPassthroughEntry("truehd", channelCounts = listOf(2, 6, 8)),
            AudioPassthroughEntry("eac3", channelCounts = listOf(2, 6)),
        ),
    )

    @Test
    fun `a layout the codec entry excludes is refused even under the aggregate maximum`() {
        assertFalse(
            sinkCanPassthrough("eac3", 8, receiver),
            "8 <= maxChannels of 8, but this receiver's E-AC-3 entry stops at 6",
        )
    }

    @Test
    fun `a layout the codec entry lists is accepted`() {
        assertTrue(sinkCanPassthrough("eac3", 6, receiver))
        assertTrue(sinkCanPassthrough("truehd", 8, receiver))
    }

    @Test
    fun `a codec the sink does not carry is refused`() {
        assertFalse(sinkCanPassthrough("dts_hd", 6, receiver))
    }

    /**
     * Pre-API-29 routes cannot be probed per format. Refusing everything there
     * would be worse than the imprecision, so the aggregate still decides.
     */
    @Test
    fun `without entries the aggregate maximum decides`() {
        val old = AudioPassthroughCapabilities(
            passthroughCodecs = listOf("eac3"),
            maxChannels = 6,
            entries = emptyList(),
        )
        assertTrue(sinkCanPassthrough("eac3", 6, old))
        assertFalse(sinkCanPassthrough("eac3", 8, old))
    }

    @Test
    fun `an unknown channel count asks only whether the codec is carried`() {
        assertTrue(sinkCanPassthrough("eac3", 0, receiver))
        assertFalse(sinkCanPassthrough("dts", 0, receiver))
    }
}

/**
 * Entries record what was PROBED, not everything the sink accepts — only
 * 2/6/8 are ever tried. Treating that list as exhaustive turns a partial probe
 * into a refusal of layouts nobody asked about.
 */
class SinkPassthroughPartialEntriesTest {

    private val receiver = AudioPassthroughCapabilities(
        passthroughCodecs = listOf("eac3", "eac3_joc"),
        maxChannels = 8,
        entries = listOf(AudioPassthroughEntry("eac3", channelCounts = listOf(2, 6))),
    )

    @Test
    fun `a probed layout the entry omits is refused`() {
        assertFalse(
            sinkCanPassthrough("eac3", 8, receiver),
            "8 is probed, so its absence is a real no",
        )
    }

    @Test
    fun `an unprobed layout under this codecs own ceiling is allowed`() {
        assertTrue(
            sinkCanPassthrough("eac3", 5, receiver),
            "5 channels is never probed and sits under the 6 this codec proved",
        )
    }

    /**
     * The aggregate maxChannels is 8 here, but that 8 belongs to another codec.
     * This sink was ASKED for 8-channel E-AC-3 and said no, so its E-AC-3
     * ceiling is 6 — borrowing the aggregate would accept 7 on a path that
     * cannot carry it, and broken audio costs more than a transcode.
     */
    @Test
    fun `an unprobed layout above this codecs own ceiling is refused`() {
        assertFalse(
            sinkCanPassthrough("eac3", 7, receiver),
            "7 would only pass by borrowing another codec's limit",
        )
        assertFalse(sinkCanPassthrough("eac3", 9, receiver))
    }

    /**
     * A JOC stream whose layout only the plain E-AC-3 entry covers must still
     * pass: Android permits JOC through an E-AC-3 path.
     */
    @Test
    fun `JOC falls back to the plain E-AC-3 entry for its layout`() {
        val jocNarrow = AudioPassthroughCapabilities(
            passthroughCodecs = listOf("eac3_joc", "eac3"),
            maxChannels = 8,
            entries = listOf(
                AudioPassthroughEntry("eac3_joc", channelCounts = listOf(2)),
                AudioPassthroughEntry("eac3", channelCounts = listOf(2, 6)),
            ),
        )
        assertFalse(
            sinkCanPassthrough("eac3_joc", 6, jocNarrow),
            "its own entry stops at stereo",
        )
        assertTrue(
            sinkCanPassthrough("eac3", 6, jocNarrow),
            "but the E-AC-3 path carries it, which is the fallback checkPlayability uses",
        )
    }

    /**
     * Derived from the probe list, not restated, so this asserts the value
     * rather than a duplicate declaration. If the probe gains a layout, the
     * reader learns about it automatically and this test is what notices.
     */
    @Test
    fun `the probed set is exactly what the capability manager probes`() {
        assertEquals(setOf(2, 6, 8), PROBED_PASSTHROUGH_CHANNEL_COUNTS)
        assertEquals(
            PASSTHROUGH_LAYOUT_PROBES.map { it.channelCount }.toSet(),
            PROBED_PASSTHROUGH_CHANNEL_COUNTS,
        )
    }
}
