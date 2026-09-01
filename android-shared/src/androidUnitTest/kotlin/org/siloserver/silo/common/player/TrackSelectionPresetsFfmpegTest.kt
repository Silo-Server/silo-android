package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the pure MIME-preference helpers exposed internally by
 * [TrackSelectionPresets]. The higher-level `buildTvParameters` /
 * `buildPhoneParameters` need an Android [android.content.Context] to
 * produce a [androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters],
 * which a plain JVM unit test can't supply — but all the FFmpeg-aware
 * logic lives in the helpers below, so tests here cover the meaningful
 * surface without Robolectric.
 */
class TrackSelectionPresetsFfmpegTest {

    private val stereoOnlySink = AudioPassthroughCapabilities(
        passthroughCodecs = emptyList(),
        spatializerEnabled = false,
        maxChannels = 2,
    )

    private val eac3PassthroughSink = AudioPassthroughCapabilities(
        passthroughCodecs = listOf("ac3", "eac3"),
        spatializerEnabled = false,
        maxChannels = 6,
    )

    /** Shield / eARC / full Atmos AVR — every passthrough codec advertised. */
    private val atmosAvrSink = AudioPassthroughCapabilities(
        passthroughCodecs = listOf("ac3", "eac3", "eac3_joc", "truehd", "dts", "dts_hd", "ac4"),
        spatializerEnabled = false,
        maxChannels = 8,
    )

    // -----------------------------------------------------------------------
    // TV presets
    // -----------------------------------------------------------------------

    @Test
    fun `TV stereo sink + FFmpeg on = FFmpeg MIMEs widen the preferred list`() {
        val mimes = TrackSelectionPresets.buildTvAudioMimePreferences(
            caps = stereoOnlySink,
            ffmpegAvailable = true,
            ffmpegMimeTypes = FfmpegAudioSupport.mimeTypes,
        )
        // Every FFmpeg-decodable MIME is reachable and must appear in the list.
        assertContains(mimes, MimeTypes.AUDIO_TRUEHD)
        assertContains(mimes, MimeTypes.AUDIO_DTS_HD)
        assertContains(mimes, MimeTypes.AUDIO_DTS)
        assertContains(mimes, MimeTypes.AUDIO_DTS_EXPRESS)
        assertContains(mimes, MimeTypes.AUDIO_ALAC)
        assertContains(mimes, MimeTypes.AUDIO_E_AC3_JOC)
        assertContains(mimes, MimeTypes.AUDIO_E_AC3)
        assertContains(mimes, MimeTypes.AUDIO_AC3)
        // AAC is always the ever-present fallback.
        assertContains(mimes, MimeTypes.AUDIO_AAC)
        // AC-4 stays out (not in FFmpeg's codec list, not in passthrough).
        assertFalse(MimeTypes.AUDIO_AC4 in mimes)
    }

    @Test
    fun `TV stereo sink + FFmpeg off = only AAC reachable`() {
        val mimes = TrackSelectionPresets.buildTvAudioMimePreferences(
            caps = stereoOnlySink,
            ffmpegAvailable = false,
        )
        assertEquals(listOf(MimeTypes.AUDIO_AAC), mimes)
    }

    @Test
    fun `TV passthrough AVR + FFmpeg off = passthrough MIMEs plus AAC, no FFmpeg overlap`() {
        val mimes = TrackSelectionPresets.buildTvAudioMimePreferences(
            caps = atmosAvrSink,
            ffmpegAvailable = false,
        )
        // Every passthrough codec ends up in the list, and so does AAC.
        assertContains(mimes, MimeTypes.AUDIO_AC4)
        assertContains(mimes, MimeTypes.AUDIO_TRUEHD)
        assertContains(mimes, MimeTypes.AUDIO_E_AC3_JOC)
        assertContains(mimes, MimeTypes.AUDIO_AAC)
    }

    @Test
    fun `DTS passthrough makes both core and Express MIME variants reachable`() {
        val mimes = TrackSelectionPresets.buildTvAudioMimePreferences(
            caps = AudioPassthroughCapabilities(
                passthroughCodecs = listOf("dts"),
                maxChannels = 6,
            ),
            ffmpegAvailable = false,
        )

        assertContains(mimes, MimeTypes.AUDIO_DTS)
        assertContains(mimes, MimeTypes.AUDIO_DTS_EXPRESS)
    }

    @Test
    fun `TV desired order — E-AC-3 JOC beats TrueHD beats AC-3 beats AAC`() {
        val mimes = TrackSelectionPresets.buildTvAudioMimePreferences(
            caps = atmosAvrSink,
            ffmpegAvailable = false,
        )
        val jocIdx = mimes.indexOf(MimeTypes.AUDIO_E_AC3_JOC)
        val truehdIdx = mimes.indexOf(MimeTypes.AUDIO_TRUEHD)
        val ac3Idx = mimes.indexOf(MimeTypes.AUDIO_AC3)
        val aacIdx = mimes.indexOf(MimeTypes.AUDIO_AAC)
        assertTrue(jocIdx in 0 until truehdIdx, "JOC should precede TrueHD")
        assertTrue(truehdIdx < ac3Idx, "TrueHD should precede AC-3")
        assertTrue(ac3Idx < aacIdx, "AC-3 should precede AAC")
    }

    // -----------------------------------------------------------------------
    // Phone presets
    // -----------------------------------------------------------------------

    @Test
    fun `Phone stereo + FFmpeg on + spatializer off = FFmpeg MIMEs in list, no JOC priority`() {
        val mimes = TrackSelectionPresets.buildPhoneAudioMimePreferences(
            caps = stereoOnlySink,
            spatializerOn = false,
            ffmpegAvailable = true,
            ffmpegMimeTypes = FfmpegAudioSupport.mimeTypes,
        )
        assertContains(mimes, MimeTypes.AUDIO_E_AC3)
        assertContains(mimes, MimeTypes.AUDIO_TRUEHD)
        assertContains(mimes, MimeTypes.AUDIO_DTS_HD)
        assertContains(mimes, MimeTypes.AUDIO_DTS_EXPRESS)
        assertContains(mimes, MimeTypes.AUDIO_ALAC)
        assertContains(mimes, MimeTypes.AUDIO_AAC)
        // Without spatializer, JOC should not be prioritized ahead of E-AC-3.
        val jocIdx = mimes.indexOf(MimeTypes.AUDIO_E_AC3_JOC)
        val eac3Idx = mimes.indexOf(MimeTypes.AUDIO_E_AC3)
        if (jocIdx != -1) {
            assertTrue(eac3Idx < jocIdx, "Without spatializer, E-AC-3 leads JOC")
        }
    }

    @Test
    fun `Phone stereo + FFmpeg off = only AAC — plan's canonical Pixel 8 baseline`() {
        val mimes = TrackSelectionPresets.buildPhoneAudioMimePreferences(
            caps = stereoOnlySink,
            spatializerOn = false,
            ffmpegAvailable = false,
        )
        assertEquals(listOf(MimeTypes.AUDIO_AAC), mimes)
    }

    @Test
    fun `Phone stereo + FFmpeg on + spatializer on = JOC and AC-4 lead the list`() {
        val mimes = TrackSelectionPresets.buildPhoneAudioMimePreferences(
            caps = stereoOnlySink,
            spatializerOn = true,
            ffmpegAvailable = true,
            ffmpegMimeTypes = FfmpegAudioSupport.mimeTypes,
        )
        // Spatializer elevates JOC to the top; AC-4 would follow if reachable,
        // but it's not in FFmpeg's set or passthrough, so it drops out.
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, mimes.first())
        assertFalse(MimeTypes.AUDIO_AC4 in mimes)
    }

    @Test
    fun `Phone passthrough exposed via dock = passthrough codec reaches list without FFmpeg`() {
        val mimes = TrackSelectionPresets.buildPhoneAudioMimePreferences(
            caps = eac3PassthroughSink,
            spatializerOn = false,
            ffmpegAvailable = false,
        )
        assertContains(mimes, MimeTypes.AUDIO_E_AC3)
        assertContains(mimes, MimeTypes.AUDIO_AC3)
        assertFalse(MimeTypes.AUDIO_TRUEHD in mimes)
        assertContains(mimes, MimeTypes.AUDIO_AAC)
    }

    @Test
    fun `MIME preferences never contain duplicates — filter is set-based`() {
        // FFmpeg MIMEs and passthrough MIMEs overlap on AC-3 / E-AC-3, so
        // we specifically exercise the deduplication path.
        val mimes = TrackSelectionPresets.buildPhoneAudioMimePreferences(
            caps = eac3PassthroughSink,
            spatializerOn = true,
            ffmpegAvailable = true,
            ffmpegMimeTypes = FfmpegAudioSupport.mimeTypes,
        )
        assertEquals(mimes.size, mimes.toSet().size, "MIME list must be duplicate-free")
    }

    // -----------------------------------------------------------------------
    // TV video MIME — HDR toggle (A.3d-hdr)
    // -----------------------------------------------------------------------

    @Test
    fun `TV video MIMEs include DV when display supports DV and HDR allowed`() {
        val mimes = TrackSelectionPresets.buildTvVideoMimePreferences(
            displayHdr = HdrCapabilities(dolbyVisionProfiles = listOf(5)),
            allowHdr = true,
        )
        assertEquals(
            listOf(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264),
            mimes,
        )
    }

    @Test
    fun `TV video MIMEs drop DV when HDR disabled even if display supports DV`() {
        val mimes = TrackSelectionPresets.buildTvVideoMimePreferences(
            displayHdr = HdrCapabilities(dolbyVisionProfiles = listOf(5, 8)),
            allowHdr = false,
        )
        assertEquals(
            listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264),
            mimes,
        )
    }

    @Test
    fun `TV video MIMEs omit DV on non-DV displays regardless of HDR toggle`() {
        val mimes = TrackSelectionPresets.buildTvVideoMimePreferences(
            displayHdr = HdrCapabilities(hdr10 = true, dolbyVisionProfiles = emptyList()),
            allowHdr = true,
        )
        assertEquals(
            listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264),
            mimes,
        )
    }
}
