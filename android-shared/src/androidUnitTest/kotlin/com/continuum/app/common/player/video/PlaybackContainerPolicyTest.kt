package com.continuum.app.common.player.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackContainerPolicyTest {
    @Test
    fun mpvDirectContainersIncludeCommonOriginalFormats() {
        listOf("mkv", "matroska", "mp4", "m4v", "webm", "avi", "mov", "qt", "ts", "mpegts", "mpeg-ts", "m2ts", "mts")
            .forEach { container ->
                assertTrue(isMpvOriginalPlaybackContainer(container), "container=$container")
            }
    }

    @Test
    fun mpvPreferredContainersExcludePlainMedia3FriendlyFormats() {
        listOf("mkv", "matroska", "avi", "mov", "qt", "ts", "mpegts", "mpeg-ts", "m2ts", "mts")
            .forEach { container ->
                assertTrue(isMpvPreferredOriginalPlaybackContainer(container), "container=$container")
            }
        assertFalse(isMpvPreferredOriginalPlaybackContainer("mp4"))
        assertFalse(isMpvPreferredOriginalPlaybackContainer("webm"))
    }
}
