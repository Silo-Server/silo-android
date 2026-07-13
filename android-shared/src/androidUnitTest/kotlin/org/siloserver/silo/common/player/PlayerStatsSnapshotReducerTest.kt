package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerStatsSnapshotReducerTest {

    @Test
    fun `VideoFormatChanged fills resolution codec frame rate and hdr`() {
        val format = Format.Builder()
            .setSampleMimeType("video/avc")
            .setCodecs("avc1.640028")
            .setWidth(1920).setHeight(1080)
            .setFrameRate(23.976f)
            .setColorInfo(
                ColorInfo.Builder()
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .build(),
            )
            .build()

        val result = reducePlayerStats(
            PlayerStatsSnapshot(),
            PlaybackAnalyticsListener.Event.VideoFormatChanged(format),
        )

        assertEquals("avc1.640028", result.videoCodec)
        assertEquals("1920x1080", result.resolution)
        assertEquals(23.976f, result.frameRate)
        assertEquals("HDR10", result.hdrMode)
        assertEquals("video/avc", result.videoMimeType)
        assertEquals(1920, result.videoWidth)
        assertEquals(1080, result.videoHeight)
        assertEquals("st2084", result.colorTransfer)
        assertEquals("limited", result.colorRange)
    }

    @Test
    fun `first frame diagnostics include decoder format and timing evidence`() {
        val snapshot = PlayerStatsSnapshot(
            videoDecoderName = "OMX.Nvidia.h265.decode",
            videoDecoderInitializationMs = 37,
            videoCodec = "hev1.2.4.L153.B0",
            videoMimeType = "video/hevc",
            videoWidth = 3840,
            videoHeight = 2160,
            colorTransfer = "st2084",
            colorRange = "limited",
        )

        assertEquals(
            mapOf(
                "decoder_name" to "OMX.Nvidia.h265.decode",
                "decoder_init_ms" to "37",
                "first_frame_ms" to "412",
                "video_mime" to "video/hevc",
                "video_codecs" to "hev1.2.4.L153.B0",
                "video_width" to "3840",
                "video_height" to "2160",
                "color_transfer" to "st2084",
                "color_range" to "limited",
            ),
            snapshot.firstFrameDiagnostics(412),
        )
    }

    @Test
    fun `unknown format dimensions preserve the last known video size`() {
        val result = reducePlayerStats(
            PlayerStatsSnapshot(videoWidth = 3840, videoHeight = 2160, resolution = "3840x2160"),
            PlaybackAnalyticsListener.Event.VideoFormatChanged(
                Format.Builder().setSampleMimeType("video/hevc").build(),
            ),
        )

        assertEquals(3840, result.videoWidth)
        assertEquals(2160, result.videoHeight)
        assertEquals("3840x2160", result.resolution)
    }

    @Test
    fun `DroppedFrames accumulates across events`() {
        val initial = PlayerStatsSnapshot(droppedFrames = 3)

        val result = reducePlayerStats(
            initial,
            PlaybackAnalyticsListener.Event.DroppedFrames(count = 2, elapsedMs = 100L),
        )

        assertEquals(5, result.droppedFrames)
    }

    @Test
    fun `BandwidthEstimate updates bitrateBps`() {
        val result = reducePlayerStats(
            PlayerStatsSnapshot(),
            PlaybackAnalyticsListener.Event.BandwidthEstimate(bitrateBps = 5_000_000L),
        )

        assertEquals(5_000_000L, result.bitrateBps)
    }

    @Test
    fun `LoadError leaves snapshot unchanged`() {
        val initial = PlayerStatsSnapshot(droppedFrames = 7, bitrateBps = 1_000L)

        val result = reducePlayerStats(
            initial,
            PlaybackAnalyticsListener.Event.LoadError(IllegalStateException("test")),
        )

        assertEquals(initial, result)
    }

    @Test
    fun `PlayerError leaves snapshot unchanged`() {
        val initial = PlayerStatsSnapshot(droppedFrames = 7, bitrateBps = 1_000L)

        val result = reducePlayerStats(
            initial,
            PlaybackAnalyticsListener.Event.PlayerError(
                PlaybackException("test", null, PlaybackException.ERROR_CODE_UNSPECIFIED),
            ),
        )

        assertEquals(initial, result)
    }

    @Test
    fun `TrackSnapshot leaves snapshot unchanged`() {
        val initial = PlayerStatsSnapshot(droppedFrames = 7, bitrateBps = 1_000L)

        val result = reducePlayerStats(
            initial,
            PlaybackAnalyticsListener.Event.TrackSnapshot("tracks"),
        )

        assertEquals(initial, result)
    }

    @Test
    fun `Dolby Vision codec produces Dolby Vision HDR mode`() {
        val format = Format.Builder()
            .setSampleMimeType("video/dolby-vision")
            .setCodecs("dvhe.05.06")
            .setWidth(3840).setHeight(2160)
            .build()

        val result = reducePlayerStats(
            PlayerStatsSnapshot(),
            PlaybackAnalyticsListener.Event.VideoFormatChanged(format),
        )

        assertEquals("Dolby Vision", result.hdrMode)
    }

    @Test
    fun `AudioUnderrun increments counter`() {
        val initial = PlayerStatsSnapshot(audioUnderruns = 2)

        val result = reducePlayerStats(initial, PlaybackAnalyticsListener.Event.AudioUnderrun)

        assertEquals(3, result.audioUnderruns)
    }
}
