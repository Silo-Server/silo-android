package com.continuum.app.tv.ui.screens.player

import com.continuum.app.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerEngineRequestHelpersTest {
    @Test
    fun styledSubtitleDetectionAcceptsAssCodecsAndUrls() {
        assertTrue(
            PlayerSubtitleInfo(
                index = 0,
                codec = "text/x-ssa",
                url = "/stream/session/subtitles/0",
            ).isStyledSubtitle(),
        )
        assertTrue(
            PlayerSubtitleInfo(
                index = 1,
                codec = null,
                url = "/stream/session/subtitles/1.ass?token=redacted",
            ).isStyledSubtitle(),
        )
        assertFalse(
            PlayerSubtitleInfo(
                index = 2,
                codec = "srt",
                url = "/stream/session/subtitles/2.srt",
            ).isStyledSubtitle(),
        )
    }

    @Test
    fun hardContainerDetectionFlagsMpvOriginalContainers() {
        assertTrue(isHardPlaybackContainer("mkv"))
        assertTrue(isHardPlaybackContainer(".Matroska"))
        assertTrue(isHardPlaybackContainer("avi"))
        assertTrue(isHardPlaybackContainer("mov"))
        assertTrue(isHardPlaybackContainer("qt"))
        assertTrue(isHardPlaybackContainer("ts"))
        assertTrue(isHardPlaybackContainer("mpegts"))
        assertTrue(isHardPlaybackContainer("mpeg-ts"))
        assertTrue(isHardPlaybackContainer("m2ts"))
        assertTrue(isHardPlaybackContainer(".MTS"))
        assertFalse(isHardPlaybackContainer("mp4"))
        assertFalse(isHardPlaybackContainer("webm"))
        assertFalse(isHardPlaybackContainer(null))
    }
}
