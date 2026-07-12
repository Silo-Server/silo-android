package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiloPlaybackServiceSubtitleSyncSourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/SiloPlaybackService.kt",
    ).readText()

    @Test
    fun subtitleSyncChangesReparseCurrentMediaItemAtSamePosition() {
        assertTrue(
            // Call site sources the sole service-owned player;
            // the reparse still reprepares the current item to rebuild sidecar cues.
            source.contains("reparseCurrentMediaItemAtCurrentPosition(p, offsetMs)"),
            "subtitle sync changes must reprepare the current item so parsed sidecar cue timestamps are rebuilt",
        )
        assertTrue(source.contains("player.setMediaItems(mediaItems, currentIndex, positionMs)"))
        assertTrue(source.contains("player.prepare()"))
        assertTrue(source.contains("player.playWhenReady = playWhenReady"))
    }

    @Test
    fun subtitleSyncReparseIsLimitedToItemsWithTextTracks() {
        assertTrue(source.contains("val hasConfiguredSubtitles ="))
        assertTrue(source.contains("val hasTextTracks ="))
        assertTrue(source.contains("if (!hasConfiguredSubtitles && !hasTextTracks) return"))
    }

    @Test
    fun subtitleSyncDoesNotUseNoOpSeekAsItsOnlyRefreshMechanism() {
        val syncJob = source.substringAfter("subtitleSyncJob = scope.launch")
            .substringBefore("private fun createPlaybackPlayer")

        assertFalse(
            syncJob.contains("player.seekTo(player.currentPosition)"),
            "seekTo(currentPosition) does not force Media3 to reparse already-loaded sidecar cues",
        )
    }
}
