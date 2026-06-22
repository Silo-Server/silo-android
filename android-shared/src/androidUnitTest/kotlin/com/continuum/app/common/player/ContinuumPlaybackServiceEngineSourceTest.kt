package com.continuum.app.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-assertion guard (repo convention): the live MediaSession service must
 * actually pick its engine from the request, be able to bind MPV, serialize swaps,
 * and fall back safely — otherwise the selector/Task-0 work has no runtime effect.
 * The behavioral proof is the Track-A device matrix (Task 6).
 */
class ContinuumPlaybackServiceEngineSourceTest {
    private val src = File(
        "src/androidMain/kotlin/com/continuum/app/common/player/ContinuumPlaybackService.kt",
    ).readText()

    @Test
    fun serviceHandlesSetEngineCommand() {
        assertTrue(src.contains("PlaybackEngineCommand.SET_ENGINE"))
        assertTrue(src.contains("MediaSession.Callback"))
        // Result future completes after the swap, not immediately (req #2).
        assertTrue(src.contains("SettableFuture"))
    }

    @Test
    fun servicePicksEngineFromRequestAndCanBindMpv() {
        assertTrue(src.contains("VideoPlaybackBackendSelector.select"))
        assertTrue(src.contains("createMpvPlayer"))
        assertTrue(src.contains("session.player = newPlayer"))
    }

    @Test
    fun engineSwapIsSerializedAndFallsBackAndEmitsDecision() {
        assertTrue(src.contains("engineSwitchMutex.withLock"))
        assertTrue(src.contains("PlaybackBackendFallback.onStartFailure"))
        assertTrue(src.contains("PlaybackEngineDecision.from"))
        // MPV built off-main under NonCancellable (its Player threading is pinned to
        // main); ANR fix (req #3) + cancellation-leak guard (req #4).
        assertTrue(src.contains("withContext(NonCancellable + Dispatchers.Default)"))
        assertTrue(src.contains("playerFactory.createMpvPlayer()"))
        // Pending built player tracked + swept on teardown (req #4).
        assertTrue(src.contains("pendingBuiltPlayer"))
    }

    @Test
    fun serviceStopsOldEngineBeforeDeferringRelease() {
        val bindBody = src.substringAfter("private fun bindNewPlayer(")
            .substringBefore("/** Carry full playback state across a swap")
        val stopIndex = bindBody.indexOf("old.stop()")
        val holdIndex = bindBody.indexOf("previousPlayer = old")

        assertTrue(stopIndex >= 0, "old engine should be stopped after a successful swap")
        assertTrue(bindBody.contains("old.clearMediaItems()"))
        assertTrue(holdIndex > stopIndex, "release deferral must happen after stopping the old engine")
        assertTrue(!bindBody.substringBefore("previousPlayer = old").contains("old.release()"))
    }
}
