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
}
