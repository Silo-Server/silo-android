package org.siloserver.silo.common.player.seek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingSeekPresentationGuardTest {
    @Test
    fun forwardSeekSuppressesPreSeekReportsUntilMidpointIsCrossed() {
        val guard = PendingSeekPresentationGuard()
        val pending = guard.begin(
            originPositionMs = 10_000L,
            targetPositionMs = 110_000L,
            nowElapsedRealtimeMs = 1_000L,
        )

        assertEquals(
            SeekPositionDecision.Suppress(pending.generation, 110_000L),
            guard.onPositionReport(positionMs = 20_000L, nowElapsedRealtimeMs = 1_100L),
        )
        assertEquals(
            SeekPositionDecision.Publish(
                positionMs = 60_000L,
                completedGeneration = pending.generation,
                completion = SeekPresentationCompletion.MidpointCrossed,
            ),
            guard.onPositionReport(positionMs = 60_000L, nowElapsedRealtimeMs = 1_200L),
        )
        assertNull(guard.pending)
    }

    @Test
    fun backwardSeekUsesTheSameDistanceRule() {
        val guard = PendingSeekPresentationGuard()
        val pending = guard.begin(
            originPositionMs = 100_000L,
            targetPositionMs = 20_000L,
            nowElapsedRealtimeMs = 0L,
        )

        assertEquals(
            SeekPositionDecision.Suppress(pending.generation, 20_000L),
            guard.onPositionReport(positionMs = 90_000L, nowElapsedRealtimeMs = 1L),
        )
        assertEquals(
            SeekPositionDecision.Publish(
                positionMs = 59_000L,
                completedGeneration = pending.generation,
                completion = SeekPresentationCompletion.MidpointCrossed,
            ),
            guard.onPositionReport(positionMs = 59_000L, nowElapsedRealtimeMs = 2L),
        )
    }

    @Test
    fun targetToleranceReleasesGuardOnlyOnTargetSideOfMidpoint() {
        val guard = PendingSeekPresentationGuard(targetToleranceMs = 500L)
        val pending = guard.begin(
            originPositionMs = 10_000L,
            targetPositionMs = 10_400L,
            nowElapsedRealtimeMs = 0L,
        )

        assertEquals(
            SeekPositionDecision.Suppress(pending.generation, 10_400L),
            guard.onPositionReport(positionMs = 10_000L, nowElapsedRealtimeMs = 1L),
        )
        assertEquals(
            SeekPositionDecision.Publish(
                positionMs = 10_200L,
                completedGeneration = pending.generation,
                completion = SeekPresentationCompletion.TargetTolerance,
            ),
            guard.onPositionReport(positionMs = 10_200L, nowElapsedRealtimeMs = 2L),
        )
    }

    @Test
    fun positionReportReleasesGuardAtConfiguredTimeout() {
        val guard = PendingSeekPresentationGuard(timeoutMs = 50L)
        val pending = guard.begin(0L, 100_000L, nowElapsedRealtimeMs = 10L)

        assertEquals(
            SeekPositionDecision.Suppress(pending.generation, 100_000L),
            guard.onPositionReport(positionMs = 0L, nowElapsedRealtimeMs = 59L),
        )
        assertEquals(
            SeekPositionDecision.Publish(
                positionMs = 0L,
                completedGeneration = pending.generation,
                completion = SeekPresentationCompletion.TimedOut,
            ),
            guard.onPositionReport(positionMs = 0L, nowElapsedRealtimeMs = 60L),
        )
        assertNull(guard.pending)
    }

    @Test
    fun staleExpiryCannotClearLatestGeneration() {
        val guard = PendingSeekPresentationGuard()
        val first = guard.begin(0L, 100_000L, nowElapsedRealtimeMs = 0L)
        val second = guard.begin(100_000L, 200_000L, nowElapsedRealtimeMs = 10L)

        assertNull(guard.expire(first.generation, nowElapsedRealtimeMs = 5_000L))
        assertEquals(second, guard.pending)
        assertEquals(
            SeekPositionDecision.Suppress(second.generation, 200_000L),
            guard.onPositionReport(positionMs = 100_000L, nowElapsedRealtimeMs = 5_009L),
        )
        assertEquals(
            ExpiredSeekPresentation(second.generation, 200_000L),
            guard.expire(second.generation, nowElapsedRealtimeMs = 5_010L),
        )
        assertNull(guard.pending)
    }

    @Test
    fun generationQualifiedCancelCannotCancelNewerSeek() {
        val guard = PendingSeekPresentationGuard()
        val first = guard.begin(0L, 10_000L, 0L)
        val second = guard.begin(10_000L, 20_000L, 1L)

        assertNull(guard.cancel(expectedGeneration = first.generation))
        assertEquals(second, guard.pending)
        assertEquals(second, guard.cancel(expectedGeneration = second.generation))
        assertNull(guard.pending)
    }

    @Test
    fun reportPublishesNormallyWhenNoSeekIsPending() {
        val guard = PendingSeekPresentationGuard()

        assertEquals(
            SeekPositionDecision.Publish(positionMs = 42_000L),
            guard.onPositionReport(positionMs = 42_000L, nowElapsedRealtimeMs = 0L),
        )
    }
}
