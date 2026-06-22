package com.continuum.app.common.player.video

import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackInfo
import com.continuum.app.model.playback.PlaybackSessionResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDeliveryResolverTest {
    @Test
    fun remuxProgressivePlaybackInfoIsPlayableWithoutStartingFallback() {
        val session = session(
            playMethod = PlayMethod.REMUX,
            streamUrl = "https://server.test/api/playback/stream/episode.mkv",
            playbackInfo = PlaybackInfo(streamType = "progressive"),
        )

        assertEquals(PlaybackDelivery.SERVER_REMUX_PROGRESSIVE, session.resolvedPlaybackDelivery())
        assertTrue(session.canPlayResolvedStreamDirectly())
    }

    @Test
    fun remuxHlsPlaylistIsNotDirectPlayable() {
        val session = session(
            playMethod = PlayMethod.REMUX,
            streamUrl = "https://server.test/api/playback/transcode/session/master.m3u8?token=redacted",
        )

        assertEquals(PlaybackDelivery.SERVER_REMUX_HLS, session.resolvedPlaybackDelivery())
        assertFalse(session.canPlayResolvedStreamDirectly())
    }

    @Test
    fun transcodeHlsPlaybackInfoIsNotDirectPlayable() {
        val session = session(
            playMethod = PlayMethod.TRANSCODE,
            streamUrl = "https://server.test/api/playback/transcode/session/master.m3u8",
            playbackInfo = PlaybackInfo(streamType = "hls"),
        )

        assertEquals(PlaybackDelivery.SERVER_TRANSCODE_HLS, session.resolvedPlaybackDelivery())
        assertFalse(session.canPlayResolvedStreamDirectly())
    }

    @Test
    fun directPlaybackIsOriginalHttpAndDirectPlayable() {
        val session = session(
            playMethod = PlayMethod.DIRECT,
            streamUrl = "https://server.test/api/playback/stream/movie.mkv",
        )

        assertEquals(PlaybackDelivery.ORIGINAL_HTTP, session.resolvedPlaybackDelivery())
        assertTrue(session.canPlayResolvedStreamDirectly())
    }

    private fun session(
        playMethod: PlayMethod,
        streamUrl: String,
        playbackInfo: PlaybackInfo? = null,
    ) = PlaybackSessionResponse(
        sessionId = "session-1",
        userId = 1,
        profileId = "profile-1",
        mediaFileId = 44,
        playMethod = playMethod,
        streamUrl = streamUrl,
        playbackInfo = playbackInfo,
    )
}
