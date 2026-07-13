package org.siloserver.silo.common.player.seek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickSkipAccumulatorTest {
    @Test
    fun rapidSkipsAccumulateFromPendingTargetWhenEnginePositionIsStale() {
        val accumulator = QuickSkipAccumulator()
        val bounds = SeekBoundsMs(endPositionMs = 600_000L)

        val first = accumulator.addSkip(
            deltaMs = 30_000L,
            enginePositionMs = 100_000L,
            bounds = bounds,
            nowElapsedRealtimeMs = 1_000L,
        )
        val second = accumulator.addSkip(
            deltaMs = 30_000L,
            enginePositionMs = 100_000L,
            bounds = bounds,
            nowElapsedRealtimeMs = 1_050L,
        )
        val third = accumulator.addSkip(
            deltaMs = -10_000L,
            enginePositionMs = 100_000L,
            bounds = bounds,
            nowElapsedRealtimeMs = 1_100L,
        )

        assertEquals(130_000L, first.targetPositionMs)
        assertEquals(160_000L, second.targetPositionMs)
        assertEquals(150_000L, third.targetPositionMs)
        assertEquals(1_300L, third.commitAtElapsedRealtimeMs)
        assertEquals(third, accumulator.pending)
    }

    @Test
    fun targetsAreClampedToTimelineBounds() {
        val bounds = SeekBoundsMs(startPositionMs = 10_000L, endPositionMs = 120_000L)
        val accumulator = QuickSkipAccumulator()

        val lower = accumulator.addSkip(
            deltaMs = Long.MIN_VALUE,
            enginePositionMs = 15_000L,
            bounds = bounds,
            nowElapsedRealtimeMs = 0L,
        )
        assertEquals(10_000L, lower.targetPositionMs)

        accumulator.cancel()
        val upper = accumulator.addSkip(
            deltaMs = Long.MAX_VALUE,
            enginePositionMs = 110_000L,
            bounds = bounds,
            nowElapsedRealtimeMs = 1L,
        )
        assertEquals(120_000L, upper.targetPositionMs)
    }

    @Test
    fun staleDeadlineCannotCommitNewerGeneration() {
        val accumulator = QuickSkipAccumulator()
        val bounds = SeekBoundsMs(endPositionMs = 600_000L)
        val first = accumulator.addSkip(30_000L, 100_000L, bounds, 1_000L)
        val second = accumulator.addSkip(30_000L, 100_000L, bounds, 1_100L)

        assertNull(accumulator.commitIfDue(first.generation, 1_200L))
        assertEquals(second, accumulator.pending)
        assertNull(accumulator.commitIfDue(second.generation, 1_299L))

        assertEquals(
            QuickSkipCommit(second.generation, 160_000L),
            accumulator.commitIfDue(second.generation, 1_300L),
        )
        assertNull(accumulator.pending)
    }

    @Test
    fun flushCommitsLatestTargetImmediately() {
        val accumulator = QuickSkipAccumulator()
        val pending = accumulator.addSkip(
            deltaMs = 10_000L,
            enginePositionMs = 40_000L,
            bounds = SeekBoundsMs(),
            nowElapsedRealtimeMs = 5L,
        )

        assertEquals(
            QuickSkipCommit(pending.generation, 50_000L),
            accumulator.flush(),
        )
        assertNull(accumulator.pending)
        assertNull(accumulator.flush())
    }

    @Test
    fun cancelDropsPendingTargetWithoutCommitting() {
        val accumulator = QuickSkipAccumulator()
        accumulator.addSkip(10_000L, 40_000L, SeekBoundsMs(), 5L)

        accumulator.cancel()

        assertNull(accumulator.pending)
    }
}
