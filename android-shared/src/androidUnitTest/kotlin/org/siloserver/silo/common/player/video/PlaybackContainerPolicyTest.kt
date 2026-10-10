package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackContainerPolicyTest {
    @Test
    fun media3ContainersIncludeValidatedOriginalFormats() {
        listOf(
            "mkv", "matroska", "mp4", "m4v", "webm", "avi", "mov", "qt", "ts", "mpegts", "mpeg-ts",
        )
            .forEach { container ->
                assertTrue(normalizedPlaybackContainer(container) in media3OriginalPlaybackContainers, "container=$container")
            }
    }

    @Test
    fun media3ContainersExcludeBdavTransportStreams() {
        // Media3's TsExtractor reads 188-byte packets only; Blu-ray/AVCHD
        // m2ts/mts use 192-byte packets and must be remuxed by the server.
        listOf("m2ts", "mts", ".M2TS", " MTS ")
            .forEach { container ->
                assertFalse(normalizedPlaybackContainer(container) in media3OriginalPlaybackContainers, "container=$container")
            }
    }
}
