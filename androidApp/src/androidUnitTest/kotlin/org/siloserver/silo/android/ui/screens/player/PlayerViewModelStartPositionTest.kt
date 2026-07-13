package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerViewModelStartPositionTest {
    private val source = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt",
    ).readText()

    @Test
    fun offlinePlaybackUsesSharedStartPositionResolver() {
        val offlineBranch = source
            .substringAfter("private suspend fun tryLocalPlayback")
            .substringBefore("override fun onCleared")

        assertTrue(offlineBranch.contains("resolvePlaybackStartPosition("))
        assertFalse(offlineBranch.contains("?: watchDetail?.userData?.positionSeconds"))
    }

    @Test
    fun offlinePlaybackOwnsAPlanFreeLocalTransport() {
        val offlineBranch = source
            .substringAfter("private suspend fun tryLocalPlayback")
            .substringBefore("override fun onCleared")

        assertTrue(offlineBranch.contains("sessionId = null"))
        assertTrue(offlineBranch.contains("streamUrl = media.uriString"))
        assertTrue(offlineBranch.contains("playbackPlan = null"))
        assertTrue(offlineBranch.contains("requestHeaders = emptyMap()"))
        assertTrue(offlineBranch.contains("delivery = null"))
    }

    @Test
    fun userSeeksUseDedicatedSeekRequestChannel() {
        val executeSeekBody = source
            .substringAfter("private fun executeSeekTarget(")
            .substringBefore("private fun startSeekReanchor(")

        assertTrue(
            source.contains("private val seekRequestChannel = Channel<Double>(capacity = Channel.BUFFERED)"),
            "mobile player must buffer seek commands until PlayerScreen starts collecting",
        )
        assertTrue(
            source.contains("val seekRequests: Flow<Double> = seekRequestChannel.receiveAsFlow()"),
            "mobile player must expose seek requests for PlayerScreen to collect",
        )
        assertTrue(
            executeSeekBody.contains("seekRequestChannel.trySend("),
            "resolved native seeks must emit an explicit player command instead of relying on mirrored position state",
        )
        assertFalse(source.contains("private val _seekRequests"))
    }

    @Test
    fun serverSeekRecoveryQueuesTheLatestTargetWithoutCancellingTheRunningRequest() {
        val executeSeekBody = source
            .substringAfter("private fun executeSeekTarget(")
            .substringBefore("private fun startSeekReanchor(")
        val enqueueBody = source
            .substringAfter("private fun enqueueServerSeekRecovery(")
            .substringBefore("private suspend fun runServerSeekRecovery(")
        val recoveryBody = source
            .substringAfter("private suspend fun runServerSeekRecovery(")
            .substringBefore("private fun publishSeekFailure(")

        assertTrue(executeSeekBody.contains("positionReportsBlockedForPendingLoad"))
        assertTrue(executeSeekBody.contains("awaitingMediaMountGeneration != null"))
        assertTrue(executeSeekBody.contains("startSeekReanchor(targetSourceSec, \"reanchor_in_flight\")"))
        assertTrue(enqueueBody.contains("queuedServerSeek = request"))
        assertFalse(enqueueBody.contains("seekRecoveryJob?.cancel()"))
        assertTrue(recoveryBody.contains("val latestAfterReanchor = queuedServerSeek"))
        assertTrue(recoveryBody.contains("if (activeSeekId != request.seekId) return"))
    }

    @Test
    fun reanchorHandoffIgnoresOldPlayerErrorsAndRollsBackNonDestructively() {
        val errorBody = source
            .substringAfter("fun onPlayerError(error:")
            .substringBefore("private fun startProtocolV3Replan(")
        val failureBody = source
            .substringAfter("private fun publishSeekFailure(")
            .substringBefore("private fun isCurrentServerSeek(")

        assertTrue(errorBody.contains("action=ignore_stale_player_error"))
        assertTrue(errorBody.contains("awaitingMediaMountGeneration != null"))
        assertTrue(failureBody.contains("request.mode == ServerSeekRecoveryMode.REANCHOR"))
        assertTrue(failureBody.contains("request.rollbackAllowed"))
        assertTrue(failureBody.contains("!seekRecoveryRollbackInvalidated"))
        assertTrue(failureBody.contains("seekPresentationGuard.cancel()?.originPositionMs"))
        assertTrue(failureBody.contains("error = null"))
    }

    @Test
    fun mountPendingTargetIsReevaluatedAgainstTheWinningPlan() {
        val mountAckBody = source
            .substringAfter("fun onMediaMountApplied(generation: Long)")
            .substringBefore("/** Called by the player when the current position changes.")
        val executeSeekBody = source
            .substringAfter("private fun executeSeekTarget(")
            .substringBefore("private fun startSeekReanchor(")

        assertTrue(executeSeekBody.contains("pendingNativeSeekAfterMount = targetSourceSec to immediate"))
        assertTrue(executeSeekBody.contains("state.sessionId == null || state.playbackPlan == null"))
        assertTrue(mountAckBody.contains("executeSeekTarget(targetSeconds, immediate)"))
    }

    @Test
    fun playerProgressIgnoresUnknownMediaSessionPositions() {
        val onPositionChangedBody = source
            .substringAfter("fun onPositionChanged(positionMs: Long, durationMs: Long, bufferedPositionMs: Long = 0L)")
            .substringBefore("/**\n     * Called when the player's actual playing state changes.")

        assertTrue(
            onPositionChangedBody.contains("if (positionMs < 0) return"),
            "MediaSession can report C.TIME_UNSET/-1; that must not overwrite progress or trigger resume reports",
        )
        assertTrue(
            onPositionChangedBody.contains(
                "if (positionReportsBlockedForPendingLoad || awaitingMediaMountGeneration != null) return",
            ),
            "old player callbacks must not overwrite a server recovery target before the new mount is applied",
        )
    }

    @Test
    fun onExitClearsPlayableStateBeforeNavigationCompletes() {
        val onExitBody = source
            .substringAfter("fun onExit()")
            .substringBefore("private fun scheduleControlsHide()")

        assertTrue(
            onExitBody.contains("sessionId = null"),
            "mobile exit must clear the active playback session id",
        )
        assertTrue(
            onExitBody.contains("streamUrl = null"),
            "mobile exit must clear the stale stream URL so Compose cannot remount it",
        )
        assertTrue(
            onExitBody.contains("playMethod = null"),
            "mobile exit must clear play method along with the stale stream URL",
        )
    }
}
