package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerStatsSnapshotReducerTest {

    @Test
    fun `Dolby Vision source reports the selected decoder base range`() {
        for ((transfer, expected) in listOf(
            C.COLOR_TRANSFER_ST2084 to "HDR10",
            C.COLOR_TRANSFER_HLG to "HLG",
            C.COLOR_TRANSFER_SDR to "SDR",
        )) {
            val format = Format.Builder()
                .setSampleMimeType("video/dolby-vision")
                .setCodecs("dvhe.08.06")
                .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
                .build()
            val input = reducePlayerStats(
                PlayerStatsSnapshot(hdrMode = "Dolby Vision"),
                PlaybackAnalyticsListener.Event.VideoFormatChanged(format),
            )
            assertEquals(null, input.hdrMode)
            val output = reducePlayerStats(
                input,
                PlaybackAnalyticsListener.Event.VideoOutputFormatChanged(format, "video/hevc"),
            )
            assertEquals(expected, output.hdrMode)
            assertEquals("dvhe.08.06", output.videoCodec)
            val nativeOutput = reducePlayerStats(
                output,
                PlaybackAnalyticsListener.Event.VideoOutputFormatChanged(format, "video/dolby-vision"),
            )
            assertEquals("Dolby Vision", nativeOutput.hdrMode)
        }
    }

    @Test
    fun `unknown output clears the previous HDR mode`() {
        val format = Format.Builder().setCodecs("dvhe.08.06").build()
        for (mime in listOf(null, "video/hevc")) {
            val output = reducePlayerStats(
                PlayerStatsSnapshot(hdrMode = "Dolby Vision"),
                PlaybackAnalyticsListener.Event.VideoOutputFormatChanged(format, mime),
            )
            assertEquals(null, output.hdrMode)
        }
    }

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
        assertEquals(null, result.hdrMode)
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
    fun `Dolby Vision input waits for decoder output`() {
        val format = Format.Builder()
            .setSampleMimeType("video/dolby-vision")
            .setCodecs("dvhe.05.06")
            .setWidth(3840).setHeight(2160)
            .build()

        val result = reducePlayerStats(
            PlayerStatsSnapshot(),
            PlaybackAnalyticsListener.Event.VideoFormatChanged(format),
        )

        assertEquals(null, result.hdrMode)
    }

    @Test
    fun `AudioUnderrun increments counter`() {
        val initial = PlayerStatsSnapshot(audioUnderruns = 2)

        val result = reducePlayerStats(initial, PlaybackAnalyticsListener.Event.AudioUnderrun)

        assertEquals(3, result.audioUnderruns)
    }

    @Test
    fun `playback timing tracks startup first frame and rebuffers`() {
        var snapshot = PlayerStatsSnapshot()
        snapshot = reducePlayerStats(
            snapshot,
            PlaybackAnalyticsListener.Event.PlaybackStateChanged(
                state = Player.STATE_BUFFERING,
                realtimeMs = 100,
                totalBufferedDurationMs = 0,
                playWhenReady = true,
            ),
        )
        snapshot = reducePlayerStats(
            snapshot,
            PlaybackAnalyticsListener.Event.PlaybackStateChanged(
                state = Player.STATE_READY,
                realtimeMs = 350,
                totalBufferedDurationMs = 4_000,
                playWhenReady = true,
            ),
        )
        snapshot = reducePlayerStats(snapshot, PlaybackAnalyticsListener.Event.FirstFrameRendered(500))
        snapshot = reducePlayerStats(
            snapshot,
            PlaybackAnalyticsListener.Event.PlaybackStateChanged(
                state = Player.STATE_BUFFERING,
                realtimeMs = 1_000,
                totalBufferedDurationMs = 250,
                playWhenReady = true,
            ),
        )
        snapshot = reducePlayerStats(
            snapshot,
            PlaybackAnalyticsListener.Event.PlaybackStateChanged(
                state = Player.STATE_READY,
                realtimeMs = 1_300,
                totalBufferedDurationMs = 3_500,
                playWhenReady = true,
            ),
        )

        assertEquals(250L, snapshot.startupReadyMs)
        assertEquals(400L, snapshot.firstFrameMs)
        assertEquals(3_500L, snapshot.bufferedDurationMs)
        assertEquals(1, snapshot.rebufferCount)
        assertEquals(300L, snapshot.rebufferTotalMs)
        assertEquals(300L, snapshot.rebufferMaxMs)
    }

    @Test
    fun `seek timing closes on the next observable ready state`() {
        var snapshot = reducePlayerStats(
            PlayerStatsSnapshot(firstFrameMs = 400),
            PlaybackAnalyticsListener.Event.SeekStarted(realtimeMs = 2_000),
        )

        snapshot = reducePlayerStats(
            snapshot,
            PlaybackAnalyticsListener.Event.PlaybackStateChanged(
                state = Player.STATE_READY,
                realtimeMs = 2_275,
                totalBufferedDurationMs = 5_000,
                playWhenReady = true,
            ),
        )

        assertEquals(1, snapshot.seekCount)
        assertEquals(275L, snapshot.lastSeekDurationMs)
        assertEquals(275L, snapshot.seekTotalMs)
        assertEquals(275L, snapshot.seekMaxMs)
    }

    @Test
    fun `terminal state always clears prior session stats but only logs while detailed`() {
        val previous = PlayerStatsSnapshot(droppedFrames = 9, firstFrameMs = 400)

        val outsideCapture = finishPlayerStats(previous, detailedCapture = false)
        val duringCapture = finishPlayerStats(previous, detailedCapture = true)

        assertEquals(PlayerStatsSnapshot(), outsideCapture.next)
        assertEquals(null, outsideCapture.finalSnapshot)
        assertEquals(PlayerStatsSnapshot(), duringCapture.next)
        assertEquals(previous, duringCapture.finalSnapshot)
    }
}
