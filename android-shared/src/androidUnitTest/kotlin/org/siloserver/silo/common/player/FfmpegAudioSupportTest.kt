package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Freezes the codec short-codes and MIME types that the server + capability
 * detector rely on. Changing these without also updating
 * `scripts/build-ffmpeg-aar.sh` `ENABLED_DECODERS` (what the AAR can
 * actually decode) would cause the client to over-report capabilities and
 * trip playback errors the server thought couldn't happen.
 */
class FfmpegAudioSupportTest {

    @Test
    fun `codecShortCodes match the plan's codec table`() {
        // Order matters only for log readability — the consumer dedup-merges
        // with platform codecs via `distinct()`.
        assertEquals(
            listOf("ac3", "eac3", "eac3_joc", "truehd", "dts", "dts_hd", "alac"),
            FfmpegAudioSupport.codecShortCodes,
        )
    }

    @Test
    fun `mimeTypes cover every codec short-code`() {
        assertEquals(
            setOf(
                MimeTypes.AUDIO_AC3,
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS,
                MimeTypes.AUDIO_DTS_EXPRESS,
                MimeTypes.AUDIO_DTS_HD,
                MimeTypes.AUDIO_ALAC,
            ),
            FfmpegAudioSupport.mimeTypes,
        )
    }

    @Test
    fun `AC4 is not advertised — skipped in v1 per the plan`() {
        // Dropping or adding AC-4 requires rebuilding the AAR with
        // --enable-decoder=ac4 and updating scripts/build-ffmpeg-aar.sh.
        assertTrue(MimeTypes.AUDIO_AC4 !in FfmpegAudioSupport.mimeTypes)
        assertTrue("ac4" !in FfmpegAudioSupport.codecShortCodes)
    }

    @Test
    fun `runtime codec advertisement contains only native-supported decoders`() {
        val supported = setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)

        assertEquals(
            listOf("dts", "dts_hd"),
            FfmpegAudioSupport.supportedCodecShortCodes { it in supported },
        )
        assertEquals(
            supported,
            FfmpegAudioSupport.supportedMimeTypes { it in supported },
        )
    }

    @Test
    fun `DTS Express aliases to the server DTS codec and ALAC stays distinct`() {
        val supported = setOf(MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_ALAC)

        assertEquals(
            listOf("dts", "alac"),
            FfmpegAudioSupport.supportedCodecShortCodes { it in supported },
        )
        assertEquals(
            supported,
            FfmpegAudioSupport.supportedMimeTypes { it in supported },
        )
    }

    @Test
    fun `native load failure advertises no FFmpeg codecs`() {
        assertEquals(
            emptyList(),
            FfmpegAudioSupport.supportedCodecShortCodes { false },
        )
        assertEquals(
            emptySet(),
            FfmpegAudioSupport.supportedMimeTypes { false },
        )
    }
}
