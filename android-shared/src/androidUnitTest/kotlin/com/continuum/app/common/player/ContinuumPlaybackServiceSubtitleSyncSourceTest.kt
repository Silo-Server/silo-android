package com.continuum.app.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuumPlaybackServiceSubtitleSyncSourceTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/com/continuum/app/common/player/ContinuumPlaybackService.kt",
    ).readText()

    @Test
    fun subtitleSyncChangesReparseCurrentMediaItemAtSamePosition() {
        assertTrue(
            // Call site now sources the engine from activePlayer (engine-swap support);
            // the reparse still reprepares the current item to rebuild sidecar cues.
            source.contains("reparseCurrentMediaItemAtCurrentPosition(p, offsetMs)"),
            "subtitle sync changes must reprepare the current item so parsed sidecar cue timestamps are rebuilt",
        )
        assertTrue(source.contains("player.setMediaItems(mediaItems, currentIndex, positionMs)"))
        assertTrue(source.contains("player.prepare()"))
        assertTrue(source.contains("player.playWhenReady = playWhenReady"))
    }

    @Test
    fun syncOffsetsAreForwardedToMpvInsteadOfMedia3OnlyProcessors() {
        assertTrue(source.contains("import com.continuum.app.common.player.mpv.MpvPlayer"))
        assertTrue(source.contains("(p as? MpvPlayer)?.setAudioDelayMs(delayMs)"))
        assertTrue(source.contains("(p as? MpvPlayer)?.setSubtitleDelayMs(offsetMs)"))
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
