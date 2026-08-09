package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudiobookPlayerTeardownSourceTest {
    private val viewModelSource = File(
        requireNotNull(System.getProperty("user.dir")),
        "src/androidMain/kotlin/org/siloserver/silo/common/player/AudiobookPlayerViewModel.kt",
    ).readText()

    private val onClearedSource = viewModelSource
        .substringAfter("override fun onCleared() {")
        .substringBefore("\n    companion object")

    private val singleFileStartSource = viewModelSource
        .substringAfter("private suspend fun startSingleFileSession(")
        .substringBefore("private suspend fun startPartSession(")

    private val partStartSource = viewModelSource
        .substringAfter("private suspend fun startPartSession(")
        .substringBefore("private suspend fun retireActiveSession(")

    @Test
    fun `onCleared captures and submits external session finalization without blocking`() {
        assertFalse(onClearedSource.contains("runBlocking"))
        assertTrue(onClearedSource.contains("val state = _uiState.value"))
        assertTrue(onClearedSource.contains("val sessionId = state.sessionId"))
        assertTrue(
            onClearedSource.contains(
                "val positionSeconds = sessionLocalPosition(state)",
            ),
        )
        assertTrue(onClearedSource.contains("val isPaused = true"))
        assertTrue(
            onClearedSource.contains(
                "playbackSessionLifecycle.reportAndStopExternalSessionAsync(",
            ),
        )
        assertTrue(onClearedSource.contains("sessionId = sessionId"))
        assertTrue(onClearedSource.contains("positionSeconds = positionSeconds"))
        assertTrue(onClearedSource.contains("isPaused = isPaused"))
    }

    @Test
    fun `single file start cannot publish after stop or teardown invalidates it`() {
        assertTrue(singleFileStartSource.contains("generation != startGeneration || isClosing"))
        assertTrue(singleFileStartSource.contains("playbackSessionManager.stopSession"))
        assertTrue(singleFileStartSource.indexOf("generation != startGeneration || isClosing") <
            singleFileStartSource.indexOf("applyStartedSession("))
    }

    @Test
    fun `audiobook starts delegate durable progress to the client timeline`() {
        assertTrue(partStartSource.contains("startPosition = startPosition"))
        assertTrue(partStartSource.contains("progressPersistence = ProgressPersistenceV3.CLIENT"))
    }
}
