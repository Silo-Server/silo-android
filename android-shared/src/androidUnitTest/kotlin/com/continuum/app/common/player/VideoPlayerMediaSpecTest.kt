package com.continuum.app.common.player

import androidx.media3.common.MimeTypes
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoPlayerMediaSpecTest {
    @Test
    fun startPositionMsConvertsSecondsToMilliseconds() {
        val spec = baseSpec(startPositionSeconds = 31.427)

        assertEquals(31_427L, spec.startPositionMs)
    }

    @Test
    fun startPositionMsClampsNegativeValuesToZero() {
        val spec = baseSpec(startPositionSeconds = -42.0)

        assertEquals(0L, spec.startPositionMs)
    }

    @Test
    fun startPositionMsClampsInvalidValuesToZero() {
        assertEquals(0L, baseSpec(startPositionSeconds = Double.NaN).startPositionMs)
        assertEquals(0L, baseSpec(startPositionSeconds = Double.POSITIVE_INFINITY).startPositionMs)
    }

    @Test
    fun durationMsConvertsPositiveFiniteSeconds() {
        val spec = baseSpec(
            startPositionSeconds = 0.0,
            durationSeconds = 7_641.718,
        )

        assertEquals(7_641_718L, spec.durationMs)
    }

    @Test
    fun durationMsDropsUnknownInvalidOrNegativeValues() {
        assertEquals(null, baseSpec(startPositionSeconds = 0.0, durationSeconds = 0.0).durationMs)
        assertEquals(null, baseSpec(startPositionSeconds = 0.0, durationSeconds = -1.0).durationMs)
        assertEquals(null, baseSpec(startPositionSeconds = 0.0, durationSeconds = Double.NaN).durationMs)
        assertEquals(null, baseSpec(startPositionSeconds = 0.0, durationSeconds = Double.POSITIVE_INFINITY).durationMs)
    }

    @Test
    fun videoContainerMimeTypeNormalizesDirectPlaybackContainers() {
        assertEquals("video/x-matroska", videoContainerMimeType(" .MKV "))
        assertEquals("video/mp4", videoContainerMimeType("m4v"))
        assertEquals("video/webm", videoContainerMimeType("webm"))
        assertEquals("video/quicktime", videoContainerMimeType("qt"))
        assertEquals("video/mp2t", videoContainerMimeType("mts"))
        assertEquals("video/mp2t", videoContainerMimeType("mpeg-ts"))
        assertEquals("video/x-msvideo", videoContainerMimeType("avi"))
        assertEquals(null, videoContainerMimeType(null))
    }

    @Test
    fun mediaItemMimeTypeKeepsRemuxAsHlsManifest() {
        assertEquals(MimeTypes.APPLICATION_M3U8, mediaItemMimeType(PlayMethod.REMUX, "mkv"))
        assertEquals(MimeTypes.APPLICATION_M3U8, mediaItemMimeType(PlayMethod.TRANSCODE, "mp4"))
        assertEquals("video/x-matroska", mediaItemMimeType(PlayMethod.DIRECT, "mkv"))
    }

    @Test
    fun mediaItemMimeTypeUsesPlanDeliveryWhenPresent() {
        assertEquals(
            MimeTypes.VIDEO_MP4,
            mediaItemMimeType(
                playMethod = PlayMethod.REMUX,
                container = "mkv",
                delivery = PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
            ),
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            mediaItemMimeType(
                playMethod = PlayMethod.DIRECT,
                container = "mkv",
                delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
            ),
        )
    }

    private fun baseSpec(
        startPositionSeconds: Double,
        durationSeconds: Double = 0.0,
    ) = VideoPlayerMediaSpec(
        streamUrl = "https://lib.strm.cafe/api/stream/movie",
        playMethod = PlayMethod.DIRECT,
        serverUrl = "https://lib.strm.cafe",
        container = "mkv",
        title = "Michael",
        subtitle = "Movie",
        artworkUrl = "https://lib.strm.cafe/poster.jpg",
        startPositionSeconds = startPositionSeconds,
        durationSeconds = durationSeconds,
    )
}
