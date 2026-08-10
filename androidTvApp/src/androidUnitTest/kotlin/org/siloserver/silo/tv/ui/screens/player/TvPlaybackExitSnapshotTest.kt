package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackTimeline
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPlaybackExitSnapshotTest {
    @Test
    fun suppliedFinalPlayerSampleReplacesStaleState() {
        val snapshot = resolveTvPlaybackExitSnapshot(
            currentPositionSeconds = 12.0,
            currentDurationSeconds = 100.0,
            positionMs = 37_000,
            durationMs = 120_000,
            timeline = null,
            serverDurationSeconds = 0.0,
        )

        assertEquals(TvPlaybackExitSnapshot(37.0, 120.0), snapshot)
    }

    @Test
    fun missingFinalSamplePreservesCurrentState() {
        val snapshot = resolveTvPlaybackExitSnapshot(
            currentPositionSeconds = 37.0,
            currentDurationSeconds = 120.0,
            positionMs = null,
            durationMs = null,
            timeline = null,
            serverDurationSeconds = 0.0,
        )

        assertEquals(TvPlaybackExitSnapshot(37.0, 120.0), snapshot)
    }

    @Test
    fun finalPlayerSampleRetainsReanchoredSourceCoordinates() {
        val snapshot = resolveTvPlaybackExitSnapshot(
            currentPositionSeconds = 3_001.0,
            currentDurationSeconds = 3_600.0,
            positionMs = 5_000,
            durationMs = 600_000,
            timeline = PlaybackTimeline(timelineOffsetSeconds = 3_000.0),
            serverDurationSeconds = 3_600.0,
        )

        assertEquals(TvPlaybackExitSnapshot(3_005.0, 3_600.0), snapshot)
    }

    @Test
    fun protocolV3DoesNotSubstitutePlayerDurationWhenSourceDurationIsUnknown() {
        val snapshot = resolveTvPlaybackExitSnapshot(
            currentPositionSeconds = 3_001.0,
            currentDurationSeconds = 0.0,
            positionMs = 5_000,
            durationMs = 600_000,
            timeline = PlaybackTimeline(timelineOffsetSeconds = 3_000.0),
            serverDurationSeconds = 0.0,
            allowPlayerDuration = false,
        )

        assertEquals(TvPlaybackExitSnapshot(3_005.0, 0.0), snapshot)
    }
}
