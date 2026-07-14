package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerViewModelSharedCoordinatorTest {
    private val viewModelSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/PlayerViewModel.kt",
    ).readText()
    private val moduleSource = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/android/di/AndroidModule.kt",
    ).readText()

    @Test
    fun mobilePlayerViewModelStartsRemotePlaybackThroughSharedCoordinator() {
        assertTrue(
            viewModelSource.contains("VideoPlaybackSessionCoordinator"),
            "Mobile player ViewModel must depend on the shared video playback coordinator",
        )
        assertTrue(
            viewModelSource.contains("VideoPlaybackStartRequest("),
            "Mobile player ViewModel must build the shared video playback start request",
        )
        assertTrue(
            viewModelSource.contains("contentId = contentId"),
            "Mobile player ViewModel must pass contentId into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("preferredFileId = preferredFileId"),
            "Mobile player ViewModel must pass the requested file id into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("roomId = null"),
            "Mobile player ViewModel must explicitly pass no Watch Together room id for normal mobile startup",
        )
        assertTrue(
            viewModelSource.contains("resumePositionOverride = resumePositionOverride"),
            "Mobile player ViewModel must preserve explicit resume override semantics",
        )
        assertTrue(
            viewModelSource.contains("subtitleTrackIndex = initialSubtitleTrackIndex"),
            "Mobile player ViewModel must pass initial subtitle selection into the shared start request",
        )
        assertTrue(
            viewModelSource.contains("videoPlaybackCoordinator.start("),
            "Mobile player ViewModel must delegate remote startup to the shared coordinator",
        )
    }

    @Test
    fun mobilePlayerViewModelKeepsOfflineFallbackBeforeRemoteCoordinatorStartup() {
        assertTrue(
            viewModelSource.contains("private suspend fun tryLocalPlayback"),
            "Mobile player ViewModel must retain the offline/local playback fallback",
        )

        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val localIndex = loadContentBody.indexOf("tryLocalPlayback(")
        val coordinatorIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(localIndex >= 0, "loadContent must try local playback")
        assertTrue(coordinatorIndex >= 0, "loadContent must start remote playback through the coordinator")
        assertTrue(
            localIndex < coordinatorIndex,
            "Mobile offline/local playback fallback must run before remote coordinator startup",
        )
        assertFalse(
            loadContentBody.contains("playbackSessionManager.startSession("),
            "Mobile initial remote startup must not start sessions directly in PlayerViewModel",
        )
    }

    @Test
    fun mobilePlayerRefreshesServerBackedSettingsAfterOfflineFallbackBeforeRemoteStartup() {
        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")
        val localIndex = loadContentBody.indexOf("tryLocalPlayback(")
        val refreshIndex = loadContentBody.indexOf("playerSettingsStore.refreshFromServer()")
        val coordinatorIndex = loadContentBody.indexOf("videoPlaybackCoordinator.start(")

        assertTrue(localIndex >= 0, "loadContent must keep the offline/local fast path")
        assertTrue(refreshIndex >= 0, "Remote startup must refresh effective server settings")
        assertTrue(coordinatorIndex >= 0, "Remote startup must use the coordinator")
        assertTrue(
            localIndex < refreshIndex,
            "Mobile must not force a settings network call before local downloaded playback",
        )
        assertTrue(
            refreshIndex < coordinatorIndex,
            "Mobile must pull user/device effective settings before choosing remote quality/subtitle defaults",
        )
    }

    @Test
    fun loadContentRethrowsCoroutineCancellation() {
        val loadContentBody = viewModelSource
            .substringAfter("fun loadContent(")
            .substringBefore("private fun startIntroAutoSkipObserver")

        assertTrue(viewModelSource.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(loadContentBody.contains("catch (e: CancellationException)"))
        assertTrue(loadContentBody.contains("throw e"))
        assertTrue(loadContentBody.contains("playerSettingsStore.refreshFromServer()"))
    }

    @Test
    fun mobileVersionSwitchRestartsThroughTheV3Coordinator() {
        val body = viewModelSource
            .substringAfter("private fun startVersionPlayback(")
            .substringBefore("/**\n     * Handles a failed quality/version switch.")
        assertTrue(body.contains("sessionLifecycle.stop()"))
        assertTrue(body.contains("loadContent("))
        assertTrue(body.contains("preferredFileId = version.fileId"))
        assertTrue(body.contains("resumePositionOverride = state.position"))
        assertTrue(body.contains("suppressResumeRewind = true"))
        assertFalse(body.contains("startSessionV2("))
        assertFalse(body.contains("startTranscodeFallback("))
    }

    @Test
    fun mobileUnsupportedPlaybackUsesV3ReplanAndAdoptsReplacement() {
        val body = viewModelSource
            .substringAfter("private fun startProtocolV3Replan(")
            .substringBefore("private fun Playability.failureClassification")
        assertTrue(body.contains("replanActiveVideoSession("))
        assertTrue(body.contains("sessionLifecycle.adoptActiveSession("))
        assertTrue(body.contains("decision.plan.stream.headers"))
        assertFalse(body.contains("startTranscodeFallback"))
    }

    @Test
    fun mobileRecoveryIsSingleFlightAndAttemptScoped() {
        val body = viewModelSource
            .substringAfter("private fun startProtocolV3Replan(")
            .substringBefore("private fun Playability.failureClassification")
        assertTrue(body.contains("if (recoveryJob?.isActive == true) return"))
        assertTrue(body.contains("clientPlaybackContext = playbackContext"))
        assertTrue(body.contains("capabilities = capabilities"))
        assertTrue(body.contains("positionSeconds = state.position"))
        assertTrue(body.contains("audioTrackIndex = selectedAudioTrackIndex"))
        assertTrue(body.contains("selectedServerAudioTrackIndex("))
        assertTrue(body.contains("subtitleTrackIndex = selectedSubtitleTrackIndex"))
    }

    @Test
    fun mobilePlaybackStarterOwnsProtocolV3Startup() {
        val starterSource = java.io.File(
            "src/androidMain/kotlin/org/siloserver/silo/android/ui/screens/player/MobileVideoPlaybackStarter.kt",
        ).readText()
        assertTrue(starterSource.contains("startVideoSessionV3("))
        assertTrue(starterSource.contains("detectPlaybackContext("))
        assertTrue(starterSource.contains("formFactor = \"mobile\""))
        assertTrue(starterSource.contains("subtitleTrackIndex = request.subtitleTrackIndex"))
        assertTrue(starterSource.contains("VideoSessionStartV3.ServerUpgradeRequired"))
        assertTrue(starterSource.contains("playbackPlanV3 = readyV3.plan"))
        assertTrue(starterSource.contains("requestHeaders = readyV3.plan.stream.headers"))
        assertFalse(starterSource.contains("startSessionV2("))
        assertFalse(starterSource.contains("startTranscodeFallback("))
        assertFalse(starterSource.contains("playMethod = PlayMethod.DIRECT"))
        assertTrue(starterSource.contains("resolvePlaybackStartRequestPosition("))
        assertTrue(starterSource.contains("readyV3.plan.timeline.playerStartSeconds"))
        assertTrue(starterSource.contains("readyV3.plan.timeline.sourceStartSeconds"))
        assertTrue(starterSource.contains("adoptActiveSession("))
    }

    @Test
    fun mobilePlayerViewModelUsesSharedVersionSelectorForUiFallback() {
        assertTrue(
            viewModelSource.contains("selectPlaybackVersion("),
            "Mobile UI fallback selection must use the same shared version selector as playback startup.",
        )
        assertFalse(
            viewModelSource.contains("private fun preferredVersionIndex("),
            "Mobile PlayerViewModel must not carry a second quality-ranking implementation.",
        )
        assertFalse(
            viewModelSource.contains("private fun resolutionRank("),
            "Resolution ranking belongs in the shared playback selector.",
        )
    }

    @Test
    fun androidModuleWiresMobileStarterAndCoordinatorIntoPlayerViewModel() {
        assertTrue(
            moduleSource.contains("MobileVideoPlaybackStarter"),
            "Android Koin module must provide the mobile video playback starter",
        )
        assertTrue(
            moduleSource.contains("VideoPlaybackSessionCoordinator"),
            "Android Koin module must provide the shared video playback coordinator",
        )
        assertTrue(
            moduleSource.contains("mobileVideoPlaybackStarter"),
            "Android Koin module should name the mobile starter binding",
        )
        assertTrue(
            moduleSource.contains("videoPlaybackCoordinator = get()"),
            "Android Koin module must inject the shared coordinator into PlayerViewModel",
        )
    }
}
