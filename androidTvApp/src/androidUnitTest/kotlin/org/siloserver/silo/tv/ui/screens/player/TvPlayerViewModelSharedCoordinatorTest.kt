package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlayerViewModelSharedCoordinatorTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvPlayerViewModel.kt",
    ).readText()
    private val moduleSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt",
    ).readText()

    @Test
    fun tvNativeSeekCommandsSurviveCollectorAndRemountGaps() {
        assertTrue(
            viewModelSource.contains("private val seekRequestChannel = Channel<Double>(capacity = Channel.BUFFERED)"),
        )
        assertTrue(
            viewModelSource.contains("val seekRequests: Flow<Double> = seekRequestChannel.receiveAsFlow()"),
        )
        assertTrue(viewModelSource.contains("seekRequestChannel.trySend("))
        assertFalse(viewModelSource.contains("private val _seekRequests"))
    }

    @Test
    fun tvReanchorHandoffIgnoresOldErrorsAndKeepsHealthyPlaybackOnApiFailure() {
        val errorBody = viewModelSource
            .substringAfter("fun onPlayerError(error:")
            .substringBefore("private fun startProtocolV3Replan(")
        val failureBody = viewModelSource
            .substringAfter("private fun handleSeekRecoveryFailure(")
            .substringBefore("private inline fun updateSeekRecoveryIfCurrent(")

        assertTrue(errorBody.contains("action=ignore_stale_player_error"))
        assertTrue(errorBody.contains("transportMountGate.suppressPositionReports"))
        assertTrue(failureBody.contains("request.operation as? TvSeekRecoveryOperation.Reanchor"))
        assertTrue(failureBody.contains("reanchor.rollbackAllowed"))
        assertTrue(failureBody.contains("!seekRecoveryRollbackInvalidated"))
        assertTrue(failureBody.contains("transportMountGate.reset()"))
        assertTrue(failureBody.contains("error = null"))
    }

    @Test
    fun tvMountPendingTargetIsReevaluatedAgainstTheWinningPlan() {
        val mountAckBody = viewModelSource
            .substringAfter("fun onTransportMountApplied(nonce: Long)")
            .substringBefore("/**\n     * Invalidates seek work")
        val executeSeekBody = viewModelSource
            .substringAfter("private fun executeSeekTarget(")
            .substringBefore("private fun startSeekReanchor(")

        assertTrue(executeSeekBody.contains("pendingNativeSeekAfterMount = targetSourceSec"))
        assertTrue(executeSeekBody.contains("state.sessionId == null || state.playbackPlan == null"))
        assertTrue(mountAckBody.contains("executeSeekTarget(targetSeconds)"))
    }

    @Test
    fun tvPlayerViewModelStartsPlaybackThroughSharedCoordinator() {
        assertTrue(
            viewModelSource.contains("VideoPlaybackSessionCoordinator"),
            "TV player ViewModel must depend on the shared video playback coordinator",
        )
        assertTrue(
            viewModelSource.contains("VideoPlaybackStartRequest("),
            "TV player ViewModel must build the shared video playback start request",
        )
        assertTrue(
            viewModelSource.contains("contentId = contentId"),
            "TV player ViewModel must pass contentId into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("preferredFileId = preferredFileIdOverride ?: preferredFileId"),
            "TV player ViewModel must pass the resolved preferred file id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("roomId = roomId"),
            "TV player ViewModel must pass Watch Together room id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("resumePositionOverride = startPositionOverride"),
            "TV player ViewModel must pass the explicit resume override into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("videoPlaybackCoordinator.start("),
            "TV player ViewModel must delegate initial startup to the shared coordinator",
        )
        assertFalse(
            viewModelSource.contains("resolvePlaybackStartPosition"),
            "TV player ViewModel must not resolve initial playback positions directly",
        )
        assertFalse(
            viewModelSource.contains("resolvePlaybackStartRequestPosition"),
            "TV player ViewModel must not resolve session start positions directly",
        )
    }

    @Test
    fun tvPlayerRefreshesServerBackedSettingsBeforeRemoteStartup() {
        val loadContentBody = viewModelSource
            .substringAfter("private fun loadContent(")
            .substringBefore("is VideoPlayerUiState.Ready ->")
        val refreshIndex = loadContentBody.indexOf("playerSettingsStore.refreshFromServer()")
        val startIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(refreshIndex >= 0, "TV playback startup must refresh effective server settings")
        assertTrue(startIndex >= 0, "TV playback startup must still use the coordinator")
        assertTrue(
            refreshIndex < startIndex,
            "TV must pull user/device effective settings before choosing quality/autoplay/subtitle defaults",
        )
    }

    @Test
    fun tvPlaybackStarterOwnsTvStartupAlgorithm() {
        val starterFile = java.io.File(
            "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/player/TvVideoPlaybackStarter.kt",
        )
        assertTrue(
            starterFile.exists(),
            "TV playback startup algorithm must live in TvVideoPlaybackStarter",
        )
        val starterSource = starterFile.readText()

        assertTrue(
            starterSource.contains("class TvVideoPlaybackStarter"),
            "TV starter must expose TvVideoPlaybackStarter",
        )
        assertTrue(starterSource.contains("startVideoSessionV3("))
        assertTrue(starterSource.contains("detectPlaybackContext("))
        assertTrue(starterSource.contains("formFactor = \"tv\""))
        assertTrue(starterSource.contains("VideoSessionStartV3.ServerUpgradeRequired"))
        assertTrue(starterSource.contains("playbackPlanV3 = readyV3.plan"))
        assertTrue(starterSource.contains("requestHeaders = readyV3.plan.stream.headers"))
        assertFalse(starterSource.contains("startSessionV2("))
        assertFalse(starterSource.contains("startTranscodeFallback("))
        assertTrue(starterSource.contains("readyV3.plan.timeline.playerStartSeconds"))
        assertTrue(starterSource.contains("readyV3.plan.timeline.sourceStartSeconds"))
        assertTrue(starterSource.contains("startPositionSeconds = playerStartPos"))
        assertTrue(starterSource.contains("sourceStartPositionSeconds = sourceStartPos"))
        assertTrue(
            starterSource.contains("resolvePlaybackStartRequestPosition("),
            "TV starter must preserve explicit Start Over request semantics",
        )
        assertTrue(
            starterSource.contains("import org.siloserver.silo.model.playback.resolvePlaybackStartRequestPosition"),
            "TV starter must use the shared start-request resolver that mobile and audiobooks use",
        )
        assertFalse(starterSource.contains("resolvePlaybackStartPosition("))
        assertFalse(
            starterSource.contains("private fun resolvePlaybackStartPosition("),
            "TV starter must not keep a private copy of shared resume semantics",
        )
        assertFalse(
            starterSource.contains("private fun resolvePlaybackStartRequestPosition("),
            "TV starter must not keep a private copy of shared start-request semantics",
        )
        assertTrue(
            starterSource.contains("adoptActiveSession("),
            "TV starter must adopt the initial session into PlaybackSessionLifecycle",
        )
        assertTrue(
            starterSource.contains("fileResolution = effectiveVersion?.resolution"),
            "TV starter must preserve the effective version resolution for later recovery fallbacks",
        )
        assertTrue(starterSource.contains("fileId = effectiveFileId"))
        assertTrue(starterSource.contains("subtitleUrls = buildPlaybackSubtitleChoices("))
        assertFalse(
            starterSource
                .substringAfter("val effectiveVersion =")
                .substringBefore("val resolvedDelivery =")
                .contains("?: version"),
            "An unknown effective file must not borrow metadata from the requested version",
        )
        assertFalse(
            starterSource.contains("manageProgress = false"),
            "TV starter must let PlaybackSessionLifecycle own progress reporting and resume persistence",
        )
        assertFalse(
            starterSource.contains("stopSessionOnStop = false"),
            "TV starter must let PlaybackSessionLifecycle stop the adopted session on exit",
        )
    }

    @Test
    fun tvUnsupportedPlaybackUsesV3ReplanAndAdoptsReplacement() {
        val unsupportedBody = viewModelSource
            .substringAfter("fun onUnsupportedPlayback(")
            .substringBefore("fun onPositionChanged(positionMs: Long, durationMs: Long)")

        assertTrue(unsupportedBody.contains("replanActiveVideoSession("))
        assertTrue(
            unsupportedBody.contains("sessionLifecycle.adoptActiveSession("),
            "fallback success must re-home lifecycle progress/stop ownership to the returned session",
        )
        assertTrue(
            unsupportedBody.contains("StartParams("),
            "fallback lifecycle adoption must preserve restart parameters for 404 recovery",
        )
        assertTrue(
            viewModelSource.contains("selectedFileResolution = result.fileResolution"),
            "TV player state must retain the selected source resolution from startup",
        )
        assertTrue(unsupportedBody.contains("fileId = effectiveFileId"))
        assertTrue(unsupportedBody.contains("selectedFileId = effectiveFileId"))
        assertTrue(unsupportedBody.contains("mediaFileId = effectiveFileId"))
        assertTrue(unsupportedBody.contains("buildPlaybackSubtitleChoices("))
        assertTrue(
            viewModelSource.contains("fun onVideoQualitySelectionApplied(resolution: String?)"),
            "runtime quality switches must update the selected source resolution",
        )
        assertTrue(unsupportedBody.contains("decision.plan.stream.headers"))
        assertFalse(unsupportedBody.contains("startTranscodeFallback"))
        assertTrue(
            viewModelSource.contains("private val capabilityDetector: PlaybackCapabilityDetector"),
            "TV fallback lifecycle adoption needs real device capabilities for recovery restarts",
        )
    }

    @Test
    fun tvPlayNextUsesLatestQualitySelection() {
        assertTrue(
            viewModelSource.contains("val selectedQuality = state.selectedFileResolution"),
            "play-next must keep using selectedFileResolution as its quality hint",
        )
        assertTrue(
            viewModelSource.contains("selectedFileResolution = resolution"),
            "selectedFileResolution must be refreshed when the HUD quality picker succeeds",
        )
    }

    @Test
    fun androidTvModuleWiresStarterAndCoordinator() {
        assertTrue(
            moduleSource.contains("TvVideoPlaybackStarter"),
            "TV Koin module must provide the TV video playback starter",
        )
        assertTrue(
            moduleSource.contains("VideoPlaybackSessionCoordinator"),
            "TV Koin module must provide the shared video playback coordinator",
        )
        assertTrue(
            moduleSource.contains("capabilityDetector = get()"),
            "TV Koin module must inject PlaybackCapabilityDetector into TvPlayerViewModel",
        )
    }
}
