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
    fun `Dolby Vision codec produces 'Dolby Vision' HDR mode`() {
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
