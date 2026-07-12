package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackContainerPolicyTest {
    @Test
    fun media3ContainersIncludeValidatedOriginalFormats() {
        listOf(
            "mkv", "matroska", "mp4", "m4v", "webm", "avi", "mov", "qt", "ts", "mpegts", "mpeg-ts", "m2ts", "mts",
        )
            .forEach { container ->
                assertTrue(normalizedPlaybackContainer(container) in media3OriginalPlaybackContainers, "container=$container")
            }
    }
}
