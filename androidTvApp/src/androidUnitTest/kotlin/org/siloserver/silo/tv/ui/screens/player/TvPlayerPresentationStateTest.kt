package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TvPlayerPresentationStateTest {
    @Test
    fun positionOnlyChangesProduceEqualPresentationState() {
        val first = TvPlayerViewModel.UiState(position = 12.0, duration = 100.0)
        val second = first.copy(position = 12.5)

        assertEquals(first.withoutPlaybackClock(), second.withoutPlaybackClock())
        assertEquals(PlaybackClock(12.0, 100.0), first.toPlaybackClock())
        assertEquals(PlaybackClock(12.5, 100.0), second.toPlaybackClock())
    }

    @Test
    fun durationOnlyChangesProduceEqualPresentationState() {
        val first = TvPlayerViewModel.UiState(position = 12.0, duration = 100.0)
        val second = first.copy(duration = 101.0)

        assertEquals(first.withoutPlaybackClock(), second.withoutPlaybackClock())
        assertEquals(PlaybackClock(12.0, 100.0), first.toPlaybackClock())
        assertEquals(PlaybackClock(12.0, 101.0), second.toPlaybackClock())
    }
}
