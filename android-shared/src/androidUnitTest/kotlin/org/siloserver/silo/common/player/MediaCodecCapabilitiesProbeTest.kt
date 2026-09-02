package org.siloserver.silo.common.player

import android.media.MediaCodecInfo.CodecProfileLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression guard for the resolution label the client reports in its
 * `ClientCodecCapabilities.maxResolution` payload.
 *
 * The server's `access.qualityRank` map in
 * `Silo/internal/access/quality.go` keys off the canonical
 * `"{vertical}p"` strings. An earlier iteration of this probe reported
 * `"4k"` / `"8k"` instead, which the server parsed as rank 0 (unknown)
 * and consequently failed `resolutionFits()` for every file — forcing
 * every playback session into TRANSCODE, even for codec- and container-
 * compatible files that should have direct-played.
 *
 * If someone regresses these strings back to the pretty labels, the
 * entire FFmpeg audio extension work (direct-play of E-AC3/TrueHD/DTS)
 * becomes unreachable server-side. These asserts pin the shape.
 */
class MediaCodecCapabilitiesProbeTest {

    @Test
    fun `8K decoders bucket to 4320p`() {
        assertEquals("4320p", MediaCodecCapabilitiesProbe.resolutionBucket(4320))
        assertEquals("4320p", MediaCodecCapabilitiesProbe.resolutionBucket(5000))
    }

    @Test
    fun `4K decoders bucket to 2160p`() {
        assertEquals("2160p", MediaCodecCapabilitiesProbe.resolutionBucket(2160))
        assertEquals("2160p", MediaCodecCapabilitiesProbe.resolutionBucket(3000))
    }

    @Test
    fun `1440p and 1080p buckets are canonical`() {
        assertEquals("1440p", MediaCodecCapabilitiesProbe.resolutionBucket(1440))
        assertEquals("1440p", MediaCodecCapabilitiesProbe.resolutionBucket(2000))
        assertEquals("1080p", MediaCodecCapabilitiesProbe.resolutionBucket(1080))
        assertEquals("1080p", MediaCodecCapabilitiesProbe.resolutionBucket(1200))
    }

    @Test
    fun `720p and sub-HD fall through correctly`() {
        assertEquals("720p", MediaCodecCapabilitiesProbe.resolutionBucket(720))
        assertEquals("720p", MediaCodecCapabilitiesProbe.resolutionBucket(1000))
        assertEquals("sd", MediaCodecCapabilitiesProbe.resolutionBucket(480))
        assertEquals("sd", MediaCodecCapabilitiesProbe.resolutionBucket(0))
    }

    @Test
    fun `no bucket reports the pretty label shape the server rejects`() {
        // Explicit: we never return "4k"/"8k" — the server's qualityRank map
        // won't recognize those and every resolutionFits() check collapses.
        val allBuckets = listOf(0, 480, 720, 1080, 1440, 2160, 4320)
            .map(MediaCodecCapabilitiesProbe::resolutionBucket)
            .toSet()
        assertEquals(
            setOf("sd", "720p", "1080p", "1440p", "2160p", "4320p"),
            allBuckets,
        )
        assert("4k" !in allBuckets) { "maxResolution must use canonical 2160p" }
        assert("8k" !in allBuckets) { "maxResolution must use canonical 4320p" }
    }

    @Test
    fun `heightFloor round-trips through resolutionBucket`() {
        // Contract: bucket(floor(b)) == b for every non-SD bucket. Used by
        // the codec-filter step — if this breaks, we'd advertise codecs at
        // a bucket they can't actually reach and MediaCodec would crash at
        // playback time with NO_EXCEEDS_CAPABILITIES.
        listOf("720p", "1080p", "1440p", "2160p", "4320p").forEach { bucket ->
            val floor = MediaCodecCapabilitiesProbe.heightFloorForBucket(bucket)
            assertEquals(
                bucket,
                MediaCodecCapabilitiesProbe.resolutionBucket(floor),
                "bucket($floor) should round-trip to $bucket",
            )
        }
    }

    @Test
    fun `heightFloor returns zero for unrecognized buckets`() {
        // "sd" and anything else we don't understand falls through to zero,
        // which becomes an always-true floor in the filter step — no codec
        // gets dropped for failing a nonsense comparison.
        assertEquals(0, MediaCodecCapabilitiesProbe.heightFloorForBucket("sd"))
        assertEquals(0, MediaCodecCapabilitiesProbe.heightFloorForBucket(""))
        assertEquals(0, MediaCodecCapabilitiesProbe.heightFloorForBucket("4k"))
    }

    @Test
    fun `AVC evidence uses ffprobe profile and level vocabulary`() {
        assertEquals(
            "high",
            MediaCodecCapabilitiesProbe.profileName("h264", CodecProfileLevel.AVCProfileHigh),
        )
        assertEquals(
            8,
            MediaCodecCapabilitiesProbe.profileBitDepth("h264", CodecProfileLevel.AVCProfileHigh),
        )
        assertEquals(
            41,
            MediaCodecCapabilitiesProbe.normalizedLevel("h264", CodecProfileLevel.AVCLevel41),
        )
    }

    @Test
    fun `HEVC evidence uses ffprobe profile and level vocabulary`() {
        assertEquals(
            "main 10",
            MediaCodecCapabilitiesProbe.profileName("hevc", CodecProfileLevel.HEVCProfileMain10),
        )
        assertEquals(
            10,
            MediaCodecCapabilitiesProbe.profileBitDepth("hevc", CodecProfileLevel.HEVCProfileMain10),
        )
        assertEquals(
            153,
            MediaCodecCapabilitiesProbe.normalizedLevel("hevc", CodecProfileLevel.HEVCMainTierLevel51),
        )
    }

    @Test
    fun `legacy and web codec MIME types use server canonical names`() {
        assertEquals("mpeg2video", MediaCodecCapabilitiesProbe.codecName("video/mpeg2"))
        assertEquals("mpeg4", MediaCodecCapabilitiesProbe.codecName("video/mp4v-es"))
        assertEquals("vc1", MediaCodecCapabilitiesProbe.codecName("video/x-ms-wmv"))
        assertEquals("vc1", MediaCodecCapabilitiesProbe.codecName("video/wvc1"))
        assertEquals("vp8", MediaCodecCapabilitiesProbe.codecName("video/x-vnd.on2.vp8"))
        assertEquals("av1", MediaCodecCapabilitiesProbe.codecName("video/av01"))
        assertNull(MediaCodecCapabilitiesProbe.codecName("video/not-real"))
    }

    @Test
    fun `legacy profile evidence matches ffprobe vocabulary`() {
        assertEquals(
            "main",
            MediaCodecCapabilitiesProbe.profileName("mpeg2video", CodecProfileLevel.MPEG2ProfileMain),
        )
        assertEquals(
            "advanced simple profile",
            MediaCodecCapabilitiesProbe.profileName(
                "mpeg4",
                CodecProfileLevel.MPEG4ProfileAdvancedSimple,
            ),
        )
        assertEquals(
            8,
            MediaCodecCapabilitiesProbe.profileBitDepth(
                "mpeg4",
                CodecProfileLevel.MPEG4ProfileAdvancedSimple,
            ),
        )
    }

    @Test
    fun `pre API 29 software decoder heuristic rejects common software implementations`() {
        assertTrue(MediaCodecCapabilitiesProbe.isLikelySoftwareDecoderName("OMX.google.h264.decoder"))
        assertTrue(MediaCodecCapabilitiesProbe.isLikelySoftwareDecoderName("c2.android.avc.decoder"))
        assertTrue(MediaCodecCapabilitiesProbe.isLikelySoftwareDecoderName("OMX.ffmpeg.video.decoder"))
        assertFalse(MediaCodecCapabilitiesProbe.isLikelySoftwareDecoderName("OMX.Nvidia.mpeg2v.decode"))
        assertFalse(MediaCodecCapabilitiesProbe.isLikelySoftwareDecoderName("c2.amlogic.hevc.decoder"))
    }

    @Test
    fun `Dolby Vision decoders are listed with profile and level bounds`() {
        assertEquals("dolby_vision", MediaCodecCapabilitiesProbe.codecName("video/dolby-vision"))
        assertEquals(
            "profile 8",
            MediaCodecCapabilitiesProbe.profileName("dolby_vision", CodecProfileLevel.DolbyVisionProfileDvheSt),
        )
        assertEquals(
            10,
            MediaCodecCapabilitiesProbe.profileBitDepth("dolby_vision", CodecProfileLevel.DolbyVisionProfileDvheSt),
        )
        assertEquals(7, MediaCodecCapabilitiesProbe.dolbyVisionProfileNumber(CodecProfileLevel.DolbyVisionProfileDvheDtb))
        assertEquals(9, MediaCodecCapabilitiesProbe.dolbyVisionLevelNumber(CodecProfileLevel.DolbyVisionLevelUhd60))
        assertEquals(6, MediaCodecCapabilitiesProbe.normalizedLevel("dolby_vision", CodecProfileLevel.DolbyVisionLevelUhd24))
        assertNull(MediaCodecCapabilitiesProbe.dolbyVisionLevelNumber(0))
    }
}
